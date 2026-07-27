package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.NetplayRollbackMetricsSnapshot
import eu.rekawek.coffeegb.controller.network.NetplayRollbackReason
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotListener
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import eu.rekawek.coffeegb.controller.network.v9.V9DiagnosticEndpoint
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState
import eu.rekawek.coffeegb.controller.network.v9.V9LinkMode
import eu.rekawek.coffeegb.controller.network.v9.V9Role
import eu.rekawek.coffeegb.controller.network.v9.V9TransportMetricsSnapshot
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class V9SwingDiagnosticsPanelTest {

  @Test
  fun modelRenderingClipboardAndCloseAreEdtOwnedAndSanitized() {
    val transport = MutableSource(transport(10))
    val rollback = MutableSource(rollback(1))
    val copied = AtomicReference<String>()
    val clipboardOnEdt = AtomicBoolean()
    lateinit var panel: V9SwingDiagnosticsPanel
    SwingUtilities.invokeAndWait {
      panel =
          V9SwingDiagnosticsPanel(transport, rollback) { value ->
            clipboardOnEdt.set(SwingUtilities.isEventDispatchThread())
            copied.set(value)
          }
      assertTrue(panel.text.text.contains("rtt-current-us=10"))
      assertTrue(panel.text.text.contains("remote-address=redacted"))
      panel.copyButton.doClick()
    }
    assertTrue(clipboardOnEdt.get())
    assertFalse(requireNotNull(copied.get()).contains("192.0.2.8"))

    transport.publish(transport(22))
    rollback.publish(rollback(4))
    SwingUtilities.invokeAndWait {
      assertTrue(panel.text.text.contains("rtt-current-us=22"))
      assertTrue(panel.text.text.contains("rollback-count=4"))
      panel.includeAddress.isSelected = true
      panel.includeAddress.doClick()
      panel.includeAddress.doClick()
      assertTrue(panel.text.text.contains("remote-address=192.0.2.8:8765"))
      panel.copyButton.doClick()
    }
    assertTrue(requireNotNull(copied.get()).contains("192.0.2.8:8765"))

    val edtBlocked = CountDownLatch(1)
    val releaseEdt = CountDownLatch(1)
    SwingUtilities.invokeLater {
      edtBlocked.countDown()
      releaseEdt.await()
    }
    assertTrue(edtBlocked.await(1, TimeUnit.SECONDS))
    try {
      transport.publish(transport(777))
      panel.close()
      panel.close()
      assertEquals(0, transport.listenerCount())
      assertEquals(0, rollback.listenerCount())
      transport.publish(transport(999))
    } finally {
      releaseEdt.countDown()
    }
    SwingUtilities.invokeAndWait {
      assertFalse(panel.text.text.contains("rtt-current-us=777"))
      assertFalse(panel.text.text.contains("rtt-current-us=999"))
    }
  }

  @Test
  fun constructionOutsideEdtIsRejected() {
    val failure = runCatching { V9SwingDiagnosticsPanel(MutableSource(transport(1))) }
        .exceptionOrNull()
    assertTrue(failure is IllegalStateException)
  }

  @Test
  fun clipboardFailureIsSanitizedOnTheEdt() {
    lateinit var panel: V9SwingDiagnosticsPanel
    SwingUtilities.invokeAndWait {
      panel =
          V9SwingDiagnosticsPanel(MutableSource(transport(1))) {
            check(SwingUtilities.isEventDispatchThread())
            throw IllegalStateException("C:\\Users\\peer\\private-state.bin")
          }
      panel.copyButton.doClick()
      assertEquals("Clipboard unavailable", panel.status.text)
      assertFalse(panel.status.text.contains("peer"))
      panel.close()
    }
  }

  private fun transport(rtt: Long) =
      V9TransportMetricsSnapshot(
          rtt,
          rtt,
          rtt,
          rtt,
          0,
          0,
          0,
          1,
          2,
          3,
          4,
          4,
          0,
          V9LifecycleState.ACTIVE,
          V9LinkMode.NORMAL,
          V9Role.SERVER,
          1,
          V9DiagnosticEndpoint("192.0.2.8", 8765),
      )

  private fun rollback(count: Long) =
      NetplayRollbackMetricsSnapshot(
          count,
          1,
          2,
          1_000,
          count,
          10,
          300,
          0,
          0,
          NetplayRollbackReason.REMOTE_INPUT,
      )

  private class MutableSource<T>(initial: T) : NetplaySnapshotSource<T> {
    private var value = initial
    private val listeners = mutableListOf<NetplaySnapshotListener<T>>()
    override fun snapshot(): T = value
    override fun addListener(listener: NetplaySnapshotListener<T>): Closeable {
      synchronized(listeners) { listeners += listener }
      listener.onSnapshot(value)
      return Closeable { synchronized(listeners) { listeners.remove(listener) } }
    }
    fun publish(next: T) {
      value = next
      synchronized(listeners) { listeners.toList() }.forEach { it.onSnapshot(next) }
    }
    fun listenerCount(): Int = synchronized(listeners) { listeners.size }
  }
}
