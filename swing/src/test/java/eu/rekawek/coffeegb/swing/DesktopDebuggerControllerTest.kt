package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.lang.reflect.Proxy
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DesktopDebuggerControllerTest {

  @Test
  fun `one retained view follows monotonic replacement and terminal revocation on the EDT`() {
    val rootEventBus = EventBusImpl()
    val portCloseCalls = AtomicInteger()
    val firstPort = debugPort(2, portCloseCalls)
    val replacementPort = debugPort(3, portCloseCalls)
    val mismatchedPort = debugPort(4, portCloseCalls)
    val view = RecordingDebuggerView()
    val factoryCalls = AtomicInteger()
    val controller =
        onEdt {
          DesktopDebuggerController(rootEventBus) {
            factoryCalls.incrementAndGet()
            view
          }
        }

    onEdt {
      controller.showDebugger()
      controller.showDebugger()
    }
    assertEquals(1, factoryCalls.get())
    assertEquals(listOf("show", "show"), view.calls)

    // Events intentionally originate outside Swing. Acceptance happens only after each callback
    // reaches the EDT, where an older queued publication can no longer replace generation 2.
    rootEventBus.post(Controller.SessionDebugPortEvent(2, firstPort))
    rootEventBus.post(Controller.SessionDebugPortEvent(1, debugPort(1, portCloseCalls)))
    flushEdt()
    assertEquals(listOf(2L), view.sessions.map { it.generation })
    assertSame(firstPort, view.sessions.single().debugPort)

    rootEventBus.post(Controller.SessionDebugPortEvent(2, firstPort))
    rootEventBus.post(Controller.SessionDebugPortEvent(2, null))
    rootEventBus.post(Controller.SessionDebugPortEvent(2, firstPort))
    rootEventBus.post(Controller.SessionDebugPortEvent(3, mismatchedPort))
    rootEventBus.post(Controller.SessionDebugPortEvent(3, replacementPort))
    flushEdt()

    assertEquals(listOf(2L, 2L, 3L), view.sessions.map { it.generation })
    assertEquals(listOf(firstPort, null, replacementPort), view.sessions.map { it.debugPort })
    assertTrue(view.allCallsWereEdt)

    onEdt {
      controller.close()
      controller.close()
      controller.showDebugger()
    }
    assertEquals(1, view.closeCalls)
    assertEquals(0, portCloseCalls.get(), "the emulation session owns every DebugPort")

    // Closing the fork unregisters the desktop subscriber but leaves the shared root usable.
    rootEventBus.post(Controller.SessionDebugPortEvent(4, debugPort(4, portCloseCalls)))
    flushEdt()
    assertEquals(listOf(2L, 2L, 3L), view.sessions.map { it.generation })
    rootEventBus.close()
  }

  @Test
  fun `opening after publication primes the view before showing it`() {
    val rootEventBus = EventBusImpl()
    val view = RecordingDebuggerView()
    val controller = onEdt { DesktopDebuggerController(rootEventBus) { view } }
    val port = debugPort(7)

    rootEventBus.post(Controller.SessionDebugPortEvent(7, port))
    flushEdt()
    assertTrue(view.calls.isEmpty())

    onEdt { controller.showDebugger() }

    assertEquals(listOf("session:7:available", "show"), view.calls)
    assertSame(port, view.sessions.single().debugPort)
    onEdt { controller.close() }
    rootEventBus.close()
  }

  @Test
  fun `controller lifecycle entry points reject non-EDT callers`() {
    val rootEventBus = EventBusImpl()
    assertFailsWith<IllegalStateException> {
      DesktopDebuggerController(rootEventBus) { RecordingDebuggerView() }
    }

    val controller =
        onEdt { DesktopDebuggerController(rootEventBus) { RecordingDebuggerView() } }
    assertFailsWith<IllegalStateException> { controller.showDebugger() }
    assertFailsWith<IllegalStateException> { controller.close() }

    onEdt { controller.close() }
    rootEventBus.close()
  }

  @Test
  fun `tools menu always exposes the debugger action`() {
    val actions = AtomicInteger()
    val menu = onEdt { createToolsMenu { actions.incrementAndGet() } }

    onEdt {
      assertEquals("Tools", menu.text)
      assertTrue(menu.isEnabled)
      val item = menu.menuComponents.filterIsInstance<JMenuItem>().single()
      assertEquals("Debugger", item.text)
      assertTrue(item.isEnabled)
      assertFalse(item.accessibleContext.accessibleDescription.isNullOrBlank())
      item.doClick()
    }
    assertEquals(1, actions.get())
    assertFailsWith<IllegalStateException> { createToolsMenu {} }
  }

  private class RecordingDebuggerView : DesktopDebuggerView {
    val calls = mutableListOf<String>()
    val sessions = mutableListOf<Controller.SessionDebugPortEvent>()
    var closeCalls = 0
    var allCallsWereEdt = true

    override fun updateSession(event: Controller.SessionDebugPortEvent) {
      recordEdt()
      sessions += event
      calls += "session:${event.generation}:${if (event.debugPort == null) "revoked" else "available"}"
    }

    override fun showWindow() {
      recordEdt()
      calls += "show"
    }

    override fun close() {
      recordEdt()
      closeCalls++
      calls += "close"
    }

    private fun recordEdt() {
      allCallsWereEdt = allCallsWereEdt && SwingUtilities.isEventDispatchThread()
    }
  }

  private fun debugPort(generation: Long, closeCalls: AtomicInteger = AtomicInteger()): DebugPort {
    val type = DebugPort::class.java
    return type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
          when (method.name) {
            "sessionGeneration" -> generation
            "isClosed" -> false
            "close" -> {
              closeCalls.incrementAndGet()
              null
            }
            "equals" -> proxy === arguments?.singleOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "TestDebugPort(generation=$generation)"
            else -> throw AssertionError("Unexpected DebugPort call: ${method.name}")
          }
        })
  }

  private fun flushEdt() {
    onEdt { Unit }
  }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
