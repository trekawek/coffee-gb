package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.replay.ReplayCodec
import eu.rekawek.coffeegb.controller.replay.ReplayInitialMode
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackLoadRequestEvent
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackPhase
import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackStatusEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingMode
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingSavedEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStartRequestEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStatusEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStopRequestEvent
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerInputRecordingTest {
  @Test
  fun `current-session recording persists an embedded replay and rejects rewind`() {
    val directory = Files.createTempDirectory("controller-input-recording")
    val rom = directory.resolve("game.gb").toFile().also { ROM.copyTo(it) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val statuses = LinkedBlockingQueue<ReplayRecordingStatusEvent>()
    val saved = LinkedBlockingQueue<ReplayRecordingSavedEvent>()
    val playback = LinkedBlockingQueue<ReplayPlaybackStatusEvent>()
    val playbackStates = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<ReplayRecordingStatusEvent>(statuses::add)
    eventBus.register<ReplayRecordingSavedEvent>(saved::add)
    eventBus.register<ReplayPlaybackStatusEvent>(playback::add)
    eventBus.register<Controller.SessionPlaybackStateEvent>(playbackStates::add)
    val controller = BasicController(eventBus, properties, null)
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      val session = await(sessions) { it.available }

      eventBus.post(
          ReplayRecordingStartRequestEvent(
              1,
              session.sessionId,
              ReplayRecordingMode.CURRENT_SESSION,
              includeSensitiveInitialState = true,
          ))
      assertEquals(
          ReplayRecordingPhase.RECORDING,
          await(statuses) { it.phase == ReplayRecordingPhase.RECORDING }.phase,
      )

      eventBus.post(Controller.RewindEvent(true))
      assertEquals(
          ReplayRecordingPhase.RECORDING,
          await(statuses) { it.message?.contains("Rewind is unavailable") == true }.phase,
      )

      playbackStates.clear()
      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(playbackStates) { it.paused }.paused)
      eventBus.post(Controller.ResumeEmulationEvent())
      assertTrue(!await(playbackStates) { !it.paused }.paused)
      eventBus.post(Controller.RewindEvent(true))
      assertEquals(
          ReplayRecordingPhase.RECORDING,
          await(statuses) { it.message?.contains("Rewind is unavailable") == true }.phase,
      )

      eventBus.post(ReplayRecordingStopRequestEvent(2, session.sessionId))
      val artifact = assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val replay = ReplayCodec.decode(Files.readAllBytes(artifact.path))
      assertEquals(ReplayInitialMode.EMBEDDED_SESSION_STATE, replay.initialConditions.mode)
      assertNotNull(replay.embeddedState)
      assertTrue(artifact.path.parent.fileName.toString() == "replays")

      eventBus.post(ReplayPlaybackLoadRequestEvent(3, session.sessionId, artifact.path))
      assertEquals(
          ReplayPlaybackPhase.PLAYING,
          await(playback) { it.phase == ReplayPlaybackPhase.PLAYING }.phase,
      )
      assertEquals(
          ReplayPlaybackPhase.COMPLETED,
          await(playback) { it.phase == ReplayPlaybackPhase.COMPLETED }.phase,
      )

      eventBus.post(Controller.RewindEvent(true))
      assertEquals(
          ReplayPlaybackPhase.COMPLETED,
          await(playback) { it.message?.contains("Rewind is unavailable") == true }.phase,
      )
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  @Test
  fun `clean-boot recording persists no session state`() {
    val directory = Files.createTempDirectory("controller-clean-boot-recording")
    val rom = directory.resolve("game.gb").toFile().also { ROM.copyTo(it) }
    val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                advanced = settings.advanced.copy(bootstrapMode = Gameboy.BootstrapMode.NORMAL),
                saves =
                    ApplicationSettings.Saves(
                        directory = directory.resolve("saves"),
                        resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                    ))
          }
        }
    val eventBus = EventBusImpl()
    val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    val statuses = LinkedBlockingQueue<ReplayRecordingStatusEvent>()
    val saved = LinkedBlockingQueue<ReplayRecordingSavedEvent>()
    val playback = LinkedBlockingQueue<ReplayPlaybackStatusEvent>()
    val playbackStates = LinkedBlockingQueue<Controller.SessionPlaybackStateEvent>()
    eventBus.register<StateUxSessionEvent>(sessions::add)
    eventBus.register<ReplayRecordingStatusEvent>(statuses::add)
    eventBus.register<ReplayRecordingSavedEvent>(saved::add)
    eventBus.register<ReplayPlaybackStatusEvent>(playback::add)
    eventBus.register<Controller.SessionPlaybackStateEvent>(playbackStates::add)
    val controller = BasicController(eventBus, properties, null)
    controller.startController()
    try {
      eventBus.post(Controller.LoadRomEvent(rom))
      val session = await(sessions) { it.available }

      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(playbackStates) { it.paused }.paused)

      eventBus.post(
          ReplayRecordingStartRequestEvent(
              1,
              session.sessionId,
              ReplayRecordingMode.CLEAN_BOOT,
              includeSensitiveInitialState = false,
          ))
      val recording = await(statuses) { it.phase == ReplayRecordingPhase.RECORDING }
      eventBus.post(Controller.RewindEvent(true))
      assertTrue(
          await(statuses) { it.message?.contains("Rewind is unavailable") == true }.tickCount > 0L)

      playbackStates.clear()
      eventBus.post(Controller.PauseEmulationEvent())
      assertTrue(await(playbackStates) { it.paused }.paused)
      eventBus.post(Controller.RewindEvent(true))
      assertEquals(
          ReplayRecordingPhase.RECORDING,
          await(statuses) { it.message?.contains("Rewind is unavailable") == true }.phase,
      )
      eventBus.post(
          ReplayRecordingStopRequestEvent(
              2,
              assertNotNull(recording.sessionId),
          ))

      val artifact = assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      val replay = ReplayCodec.decode(Files.readAllBytes(artifact.path))
      assertEquals(ReplayInitialMode.BOOT_REFERENCE, replay.initialConditions.mode)
      assertEquals(null, replay.embeddedState)

      eventBus.post(
          ReplayPlaybackLoadRequestEvent(
              3,
              assertNotNull(recording.sessionId),
              artifact.path,
          ))
      assertEquals(
          ReplayPlaybackPhase.PLAYING,
          await(playback) { it.phase == ReplayPlaybackPhase.PLAYING }.phase,
      )
      assertEquals(
          ReplayPlaybackPhase.COMPLETED,
          await(playback) { it.phase == ReplayPlaybackPhase.COMPLETED }.phase,
      )
    } finally {
      controller.close()
      eventBus.close()
      properties.close()
      deleteTree(directory)
    }
  }

  private fun <T> await(queue: LinkedBlockingQueue<T>, predicate: (T) -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
      val value = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
      if (predicate(value)) return value
    }
    throw AssertionError("Timed out waiting for expected event")
  }

  private fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    const val TIMEOUT_SECONDS = 10L
  }
}
