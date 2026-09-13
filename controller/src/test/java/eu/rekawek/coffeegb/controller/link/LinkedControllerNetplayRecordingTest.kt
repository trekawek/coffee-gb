package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStartEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingRetryEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStatusEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStopEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkedControllerNetplayRecordingTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `recording logs both players and late input rollback without changing the machine state`() {
    val recorded = runSession(record = true)
    val ordinary = runSession(record = false)
    assertEquals(ordinary.first, recorded.first)
    val log = Files.readAllLines(recorded.second)
    assertTrue(log.any { it.contains("\"player\":0,\"source\":\"local\",\"pressed\":[\"A\"]") })
    assertTrue(log.any { it.contains("\"player\":1,\"source\":\"remote\",\"pressed\":[\"B\"]") })
    assertTrue(log.any { it.contains("\"source\":\"local\",\"pressed\":[],\"released\":[\"A\"]") })
    assertTrue(log.any { it.contains("\"type\":\"rollback\"") })
    assertFalse(log.any { it.contains("synthetic.gb") || it.contains(temporary.root.toString()) })
  }

  @Test
  fun `closing linked play saves an active recording automatically`() {
    val (_, path) = runSession(record = true, stop = false)
    assertTrue(Files.readAllLines(path).last().contains("\"reason\":\"session_closed\""))
  }

  @Test
  fun `recording can start as soon as the linked owner announces availability`() {
    val (_, path) = runSession(record = true, recordOnAvailable = true)
    assertTrue(Files.readAllLines(path).any {
      it.contains("\"type\":\"recording_started\"") && it.contains("\"frame\":0")
    })
  }

  @Test
  fun `a failed close can save the retained log to a replacement destination`() {
    val (_, path) = runSession(record = true, stop = false, failSaveOnClose = true)
    assertTrue(Files.readAllLines(path).last().contains("\"reason\":\"session_closed\""))
    assertTrue(Files.readString(path).contains("\"pressed\":[\"B\"]"))
  }

  private fun runSession(
      record: Boolean,
      stop: Boolean = true,
      recordOnAvailable: Boolean = false,
      failSaveOnClose: Boolean = false,
  ): Pair<LinkedSessionState, Path> {
    val root = temporary.newFolder().toPath()
    val rom = root.resolve("synthetic.gb")
    val bytes = StateCodecTestSupport.rom()
    Files.write(rom, bytes)
    val path = root.resolve("input.jsonl")
    if (failSaveOnClose) Files.writeString(path, "existing file")
    val properties = EmulatorProperties(
        root.resolve("settings.json"),
        ApplicationSettingsOverrides(
            hardwareProfile = HardwareProfileRegistry.DMG,
            bootstrapMode = Gameboy.BootstrapMode.SKIP,
            batterySavesEnabled = false,
            runtimeWarmupEnabled = false,
        ),
    )
    val bus = EventBusImpl()
    var sessionId = 0L
    var startRequested = false
    bus.register<NetplayRecordingStatusEvent> {
      sessionId = it.sessionId
      if (recordOnAvailable && !startRequested && it.available && it.phase == ReplayRecordingPhase.IDLE) {
        startRequested = true
        bus.post(NetplayRecordingStartEvent(sessionId, path))
      }
    }
    val controller = LinkedController(bus, properties, null)
    controller.timingTicker.disabled = true
    try {
      bus.post(LoadRomEvent(rom.toFile()))
      controller.runFrame()
      bus.post(PeerLoadedGameEvent(bytes, null, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, controller.currentFrame()))
      controller.runFrame()
      val first = controller.currentFrame()
      bus.post(ButtonPressEvent(Button.A))
      controller.runFrame()
      if (record && !recordOnAvailable) bus.post(NetplayRecordingStartEvent(sessionId, path))
      repeat(3) { controller.runFrame() }
      bus.post(LinkedController.RemoteButtonStateEvent(first, Input(listOf(Button.B), emptyList())))
      controller.runFrame()
      bus.post(ButtonReleaseEvent(Button.A))
      controller.runFrame()
      if (record && stop) bus.post(NetplayRecordingStopEvent(sessionId))
      controller.runFrame()
      val state = controller.captureDetachedState()
      if (failSaveOnClose) {
        assertFailsWith<Controller.PersistenceBarrierException> { controller.close() }
        assertEquals("existing file", Files.readString(path))
        val retry = root.resolve("retry.jsonl")
        bus.post(NetplayRecordingRetryEvent(sessionId, retry))
        controller.close()
        return state to retry
      }
      controller.close()
      return state to path
    } finally {
      controller.close()
      bus.close()
      properties.close()
    }
  }
}
