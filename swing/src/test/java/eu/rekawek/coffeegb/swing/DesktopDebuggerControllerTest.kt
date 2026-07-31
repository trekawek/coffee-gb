package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.lang.reflect.Proxy
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JMenu
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
      controller.showTool(DebuggerWorkspaceTool.EXECUTION)
      controller.applyLayout(DebuggerWorkspaceLayout.FULL)
    }
    assertEquals(1, factoryCalls.get())
    assertEquals(listOf("tool:EXECUTION", "layout:FULL"), view.calls)

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
      controller.showTool(DebuggerWorkspaceTool.MEMORY)
      controller.applyLayout(DebuggerWorkspaceLayout.CPU)
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

    onEdt { controller.showTool(DebuggerWorkspaceTool.AUDIO) }

    assertEquals(listOf("session:7:available", "tool:AUDIO"), view.calls)
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
    assertFailsWith<IllegalStateException> {
      controller.showTool(DebuggerWorkspaceTool.EXECUTION)
    }
    assertFailsWith<IllegalStateException> {
      controller.applyLayout(DebuggerWorkspaceLayout.CPU)
    }
    assertFailsWith<IllegalStateException> { controller.close() }

    onEdt { controller.close() }
    rootEventBus.close()
  }

  @Test
  fun `debug menu exposes every window and a separate layout submenu`() {
    val tools = mutableListOf<DebuggerWorkspaceTool>()
    val layouts = mutableListOf<DebuggerWorkspaceLayout>()
    val actions = DebuggerMenuActions(tools::add, layouts::add)
    val menu = onEdt { createDebugMenu(actions) }

    onEdt {
      assertEquals("Debug", menu.text)
      assertTrue(menu.isEnabled)
      assertEquals(DebuggerWorkspaceTool.entries.size + 2, menu.menuComponentCount)
      val toolItems = menu.menuComponents.take(DebuggerWorkspaceTool.entries.size)
      assertTrue(toolItems.all { it is JMenuItem && it !is JMenu })
      assertEquals(
          DebuggerWorkspaceTool.entries.map { it.title },
          toolItems.map { (it as JMenuItem).text },
      )
      toolItems.map { it as JMenuItem }.forEach { item ->
        assertTrue(item.isEnabled)
        assertFalse(item.accessibleContext.accessibleDescription.isNullOrBlank())
        item.doClick()
      }

      val layoutMenu = menu.menuComponents.filterIsInstance<JMenu>().single()
      assertEquals("Layout", layoutMenu.text)
      assertFalse(layoutMenu.accessibleContext.accessibleDescription.isNullOrBlank())
      val layoutItems = layoutMenu.menuComponents.filterIsInstance<JMenuItem>()
      assertEquals(DebuggerWorkspaceLayout.entries.map { it.title }, layoutItems.map { it.text })
      layoutItems.forEach { item ->
        assertFalse(item.accessibleContext.accessibleDescription.isNullOrBlank())
        item.doClick()
      }
    }
    assertEquals(DebuggerWorkspaceTool.entries.toList(), tools)
    assertEquals(DebuggerWorkspaceLayout.entries.toList(), layouts)
    assertFailsWith<IllegalStateException> { createDebugMenu(actions) }
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

    override fun showTool(tool: DebuggerWorkspaceTool) {
      recordEdt()
      calls += "tool:${tool.name}"
    }

    override fun applyLayout(layout: DebuggerWorkspaceLayout) {
      recordEdt()
      calls += "layout:${layout.name}"
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
