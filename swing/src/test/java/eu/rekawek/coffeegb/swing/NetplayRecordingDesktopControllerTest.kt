package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.replay.*
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*
import org.junit.Test

class NetplayRecordingDesktopControllerTest {
  @Test
  fun `netplay controls route start stop retry and ignore an older controller`() {
    val bus = EventBusImpl()
    val path = Path.of("netplay.jsonl")
    val starts = mutableListOf<NetplayRecordingStartEvent>()
    val stops = mutableListOf<NetplayRecordingStopEvent>()
    val retries = mutableListOf<NetplayRecordingRetryEvent>()
    val statuses = mutableListOf<NetplayRecordingStatusEvent>()
    val messages = mutableListOf<String>()
    bus.register<NetplayRecordingStartEvent> { starts.add(it) }
    bus.register<NetplayRecordingStopEvent> { stops.add(it) }
    bus.register<NetplayRecordingRetryEvent> { retries.add(it) }
    val controller = NetplayRecordingDesktopController(null, bus, statuses::add, messages::add, { path })
    fun status(event: NetplayRecordingStatusEvent) {
      bus.post(event)
      SwingUtilities.invokeAndWait {}
    }
    try {
      status(NetplayRecordingStatusEvent(2, ReplayRecordingPhase.IDLE))
      SwingUtilities.invokeAndWait { controller.start() }
      assertEquals(listOf(NetplayRecordingStartEvent(2, path)), starts)
      status(NetplayRecordingStatusEvent(2, ReplayRecordingPhase.RECORDING))
      SwingUtilities.invokeAndWait { controller.stop() }
      assertEquals(listOf(NetplayRecordingStopEvent(2)), stops)
      status(NetplayRecordingStatusEvent(2, ReplayRecordingPhase.UNSAVED, error = "Write failed"))
      SwingUtilities.invokeAndWait { controller.start() }
      assertEquals(listOf(NetplayRecordingRetryEvent(2, path)), retries)
      status(NetplayRecordingStatusEvent(2, ReplayRecordingPhase.IDLE, savedPath = path))
      assertTrue(messages.last().contains(path.toString()))
      status(NetplayRecordingStatusEvent(1, ReplayRecordingPhase.IDLE, available = false))
      SwingUtilities.invokeAndWait { assertTrue(controller.isLinked) }
      status(NetplayRecordingStatusEvent(2, ReplayRecordingPhase.IDLE, available = false))
      SwingUtilities.invokeAndWait { assertFalse(controller.isLinked) }
      assertEquals(2L, statuses.last().sessionId)
    } finally {
      SwingUtilities.invokeAndWait { controller.close() }
      bus.close()
    }
  }

  @Test
  fun `cancelling the file chooser leaves recording idle`() {
    val bus = EventBusImpl()
    var requests = 0
    bus.register<NetplayRecordingStartEvent> { requests++ }
    val controller = NetplayRecordingDesktopController(null, bus, {}, {}, { null })
    try {
      bus.post(NetplayRecordingStatusEvent(1, ReplayRecordingPhase.IDLE))
      SwingUtilities.invokeAndWait { controller.start() }
      assertEquals(0, requests)
    } finally {
      SwingUtilities.invokeAndWait { controller.close() }
      bus.close()
    }
  }
}
