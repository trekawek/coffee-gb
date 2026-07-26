package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.Controller.HardwareProfileEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerTest {

  @Test
  fun persistedSgb2SelectionReloadsTheRunningSessionWithExactProfile() {
    val eventBus = EventBusImpl()
    val properties = EmulatorProperties()
    val profileEvents = LinkedBlockingQueue<HardwareProfileEvent>()
    eventBus.register<HardwareProfileEvent> { profileEvents.add(it) }
    val sgbRom =
        Files.createTempFile("coffee-gb-sgb2-profile-reload", ".gb").toFile().also { file ->
          file.writeBytes(
              ROM.readBytes().also { bytes ->
                bytes[0x143] = 0
                bytes[0x146] = 0x03
              })
        }
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
        HardwareProfileRegistry.SGB.id()
    val controller = BasicController(eventBus, properties, null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(sgbRom))
      assertEquals(
          HardwareProfileRegistry.SGB,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )

      properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] =
          HardwareProfileRegistry.SGB2.id()
      eventBus.post(Controller.UpdatedSystemMappingEvent())
      assertEquals(
          HardwareProfileRegistry.SGB2,
          assertNotNull(profileEvents.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).profile,
      )
    } finally {
      controller.close()
      eventBus.close()
      sgbRom.delete()
    }
  }

  @Test
  fun disabledRewindCreatesNoFrameCapturesAndCannotFreezeForwardEmulation() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }
    val rewind = RewindManager(enabled = false)
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory.DEFAULT,
            rewind,
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertNotNull(frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(0, rewind.captureCount)

      frames.clear()
      eventBus.post(Controller.RewindEvent(true))
      assertNotNull(
          frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "a disabled rewind request must not stop normal frame progress",
      )
      assertEquals(0, rewind.captureCount)
      assertEquals(0, rewind.historySize)
    } finally {
      controller.close()
      eventBus.close()
    }
  }

  @Test
  fun failedSnapshotSaveEmitsOnlyFailureRetainsOldFileAndControllerLaterSaves() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<Controller.SnapshotSaveFailedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotSaveFailedEvent> { failures.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-controller-save-failure")
    val rom = directory.resolve("state-test.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    val persistence = ToggleFailWriter()
    val controller =
        BasicController(
            eventBus,
            EmulatorProperties(),
            null,
            RomSessionPreparer(),
            SnapshotManagerFactory { configuration ->
              SnapshotManager.testing(
                  configuration,
                  LegacySnapshotMigrationPolicy.PRESERVE,
                  persistence = persistence,
              )
            },
        )

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(Controller.SaveSnapshotEvent(0))
      assertEquals(0, assertNotNull(saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)
      val file = directory.resolve("state-test.sn0")
      val prior = Files.readAllBytes(file)

      persistence.fail = true
      eventBus.post(Controller.SaveSnapshotEvent(0))
      val failure = assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(0, failure.slot)
      assertTrue(failure.message.contains("previous state remains recoverable"))
      assertNull(saved.poll(250, TimeUnit.MILLISECONDS), "failure must not post success")
      assertTrue(prior.contentEquals(Files.readAllBytes(file)))

      persistence.fail = false
      eventBus.post(Controller.SaveSnapshotEvent(1))
      assertEquals(
          1,
          assertNotNull(
                  saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "controller must keep processing after a persistence failure",
              )
              .slot,
      )
      assertEquals("CGBS", Files.readAllBytes(directory.resolve("state-test.sn1")).copyOf(4).toString(Charsets.US_ASCII))
    } finally {
      controller.close()
      eventBus.close()
      Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun failedLoadDoesNotPreventLoadingAnotherRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    val controller = BasicController(eventBus, EmulatorProperties(), null)
    val invalidRom = Files.createTempFile("coffee-gb-invalid-rom", ".gbc").toFile()
    invalidRom.writeText("not a Game Boy ROM")

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertEquals("CPU_INSTRS", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)

      eventBus.post(LoadRomEvent(invalidRom))
      val failure = failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertNotNull(failure, "the failed load should be reported")
      assertEquals(invalidRom, failure.rom)

      eventBus.post(LoadRomEvent(ROM))
      assertEquals(
          "CPU_INSTRS",
          started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName,
          "the controller thread should keep processing load requests",
      )
    } finally {
      controller.close()
      eventBus.close()
      invalidRom.delete()
    }
  }

  @Test
  fun rejectedSnapshotIsReportedAndControllerKeepsProcessingEvents() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<Controller.SnapshotLoadFailedEvent>()
    val saved = LinkedBlockingQueue<Controller.SnapshotSavedEvent>()
    val restored = LinkedBlockingQueue<Controller.SnapshotRestoredEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<Controller.SnapshotLoadFailedEvent> { failures.add(it) }
    eventBus.register<Controller.SnapshotSavedEvent> { saved.add(it) }
    eventBus.register<Controller.SnapshotRestoredEvent> { restored.add(it) }
    val directory = Files.createTempDirectory("coffee-gb-controller-state")
    val rom = directory.resolve("state-test.gb").toFile().also { it.writeBytes(ROM.readBytes()) }
    directory.resolve("state-test.sn0").toFile().writeBytes(byteArrayOf(1, 2, 3, 4))
    val controller = BasicController(eventBus, EmulatorProperties(), null)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(rom))
      assertNotNull(started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

      eventBus.post(Controller.RestoreSnapshotEvent(0))
      assertEquals(0, assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)

      eventBus.post(Controller.SaveSnapshotEvent(1))
      assertEquals(
          1,
          assertNotNull(
                  saved.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                  "the controller thread should remain alive after a rejected snapshot",
              )
              .slot,
      )
      val portable = directory.resolve("state-test.sn1").toFile().readBytes()
      assertEquals("CGBS", portable.copyOf(4).toString(Charsets.US_ASCII))
      assertTrue(!LegacySnapshotImporter.hasJavaSerializationHeader(portable))

      eventBus.post(Controller.RestoreSnapshotEvent(1))
      assertEquals(1, assertNotNull(restored.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).slot)
    } finally {
      controller.close()
      eventBus.close()
      Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }
      Files.deleteIfExists(directory)
    }
  }

  @Test
  fun pausesCurrentSessionWhileNextRomIsPrepared() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val loading = LinkedBlockingQueue<RomLoadingEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<RomLoadingEvent> { loading.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }

    val nextRom = namedRom("NEXT_GAME")
    val preparing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          if (event.rom == nextRom) {
            preparing.countDown()
            try {
              release.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertEquals("CPU_INSTRS", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      loading.clear()
      frames.clear()

      eventBus.post(LoadRomEvent(nextRom))
      assertTrue(preparing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(nextRom, loading.poll(1, TimeUnit.SECONDS)?.rom)
      assertNull(stopped.poll(250, TimeUnit.MILLISECONDS), "the old session must stay active")
      frames.clear()
      assertNull(frames.poll(250, TimeUnit.MILLISECONDS), "the old session must be frozen")
      eventBus.post(Controller.ResumeEmulationEvent())
      assertNull(
          frames.poll(250, TimeUnit.MILLISECONDS),
          "resume during loading must apply to the new session, not restart the old game",
      )

      release.countDown()
      assertEquals("NEXT_GAME", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
    } finally {
      release.countDown()
      controller.close()
      eventBus.close()
      nextRom.delete()
    }
  }

  @Test
  fun rapidLoadBurstStartsOnlyTheLatestRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }

    val firstRom = namedRom("FIRST_GAME")
    val middleRom = namedRom("MIDDLE_GAME")
    val lastRom = namedRom("LAST_GAME")
    val firstPreparationStarted = CountDownLatch(1)
    val neverReleaseFirst = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          if (event.rom == firstRom) {
            firstPreparationStarted.countDown()
            try {
              neverReleaseFirst.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(firstRom))
      assertTrue(firstPreparationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(LoadRomEvent(middleRom))
      eventBus.post(LoadRomEvent(lastRom))

      assertEquals("LAST_GAME", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      assertNull(started.poll(500, TimeUnit.MILLISECONDS), "superseded ROMs must never start")
      assertEquals(firstRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
      assertEquals(middleRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
    } finally {
      controller.close()
      eventBus.close()
      firstRom.delete()
      middleRom.delete()
      lastRom.delete()
    }
  }

  private fun namedRom(title: String): File {
    val bytes = ROM.readBytes()
    for (address in 0x0134 until 0x0143) {
      bytes[address] = 0
    }
    title.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x0134, endIndex = title.length.coerceAtMost(15))
    return Files.createTempFile("coffee-gb-$title", ".gbc").toFile().also { it.writeBytes(bytes) }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    const val TIMEOUT_SECONDS = 10L
  }

  private class ToggleFailWriter : AtomicFileWriter() {
    @Volatile var fail = false

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (fail) {
        throw IOException("injected snapshot replacement failure")
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }
}
