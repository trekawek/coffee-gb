package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityEvent
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityRequestEvent
import eu.rekawek.coffeegb.controller.state.StateUserError
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import java.awt.Color
import java.awt.Font
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.LineBorder
import javax.swing.border.TitledBorder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class StateUxDesktopControllerTest {

  @Test
  fun `catalog consumers accept a newer coalesced request but reject stale or unarmed results`() {
    assertTrue(acceptsStateCatalogRequest(outstandingRequestId = 10, completedRequestId = 10))
    assertTrue(acceptsStateCatalogRequest(outstandingRequestId = 10, completedRequestId = 11))
    assertFalse(acceptsStateCatalogRequest(outstandingRequestId = 11, completedRequestId = 10))
    assertFalse(acceptsStateCatalogRequest(outstandingRequestId = 0, completedRequestId = 11))
  }

  @Test
  fun `selected slot availability ignores stale probes and follows save delete and session state`() {
    var nextRequestId = 0L
    val requests = mutableListOf<StateSlotLoadAvailabilityRequestEvent>()
    val publications = mutableListOf<Pair<Int, Boolean>>()
    val tracker =
        StateSlotLoadAvailabilityTracker(
            nextRequestId = { ++nextRequestId },
            postRequest = requests::add,
            publish = { slot, available -> publications += slot to available },
        )

    tracker.sessionChanged(StateUxSessionEvent(1, true, Path.of("game-1")))
    assertEquals(StateRef.Slot(0), requests.single().ref)
    tracker.slotSelected(4)
    assertEquals(StateRef.Slot(4), requests.last().ref)

    tracker.availabilityChanged(StateSlotLoadAvailabilityEvent(1, 1, StateRef.Slot(0), true))
    assertEquals(4 to false, publications.last())
    tracker.availabilityChanged(StateSlotLoadAvailabilityEvent(2, 1, StateRef.Slot(4), true))
    assertEquals(4 to true, publications.last())

    tracker.operationCompleted(
        StateOperationCompletedEvent(3, 1, StateOperation.DELETE, StateRef.Slot(4), message = "Deleted"))
    assertEquals(4 to false, publications.last())
    assertEquals(3, requests.last().requestId)
    tracker.operationCompleted(
        StateOperationCompletedEvent(4, 1, StateOperation.SAVE, StateRef.Slot(4), message = "Saved"))
    assertEquals(4 to true, publications.last())

    tracker.sessionChanged(StateUxSessionEvent(2, false, null))
    assertEquals(4 to false, publications.last())
    tracker.availabilityChanged(StateSlotLoadAvailabilityEvent(3, 1, StateRef.Slot(4), true))
    assertEquals(4 to false, publications.last())
  }

  @Test
  fun `unrelated browser load failure does not invalidate selected quick slot`() {
    var nextRequestId = 0L
    val requests = mutableListOf<StateSlotLoadAvailabilityRequestEvent>()
    val publications = mutableListOf<Pair<Int, Boolean>>()
    val tracker =
        StateSlotLoadAvailabilityTracker(
            nextRequestId = { ++nextRequestId },
            postRequest = requests::add,
            publish = { slot, available -> publications += slot to available },
        )

    tracker.sessionChanged(StateUxSessionEvent(1, true, Path.of("game-1")))
    tracker.availabilityChanged(StateSlotLoadAvailabilityEvent(1, 1, StateRef.Slot(0), true))
    val quickLoadRequestId = ++nextRequestId
    tracker.quickLoadRequested(quickLoadRequestId, expectedSessionId = 1, slot = 0)
    val publicationCount = publications.size
    val probeCount = requests.size

    tracker.operationFailed(
        StateOperationFailedEvent(
            requestId = 99,
            sessionId = 1,
            operation = StateOperation.LOAD,
            error = stateFailure(),
        ))

    assertEquals(publicationCount, publications.size)
    assertEquals(probeCount, requests.size)
    assertEquals(0 to true, publications.last())

    tracker.operationFailed(
        StateOperationFailedEvent(
            requestId = quickLoadRequestId,
            sessionId = 1,
            operation = StateOperation.LOAD,
            error = stateFailure(),
        ))

    assertEquals(0 to false, publications.last())
    assertEquals(probeCount + 1, requests.size)
  }

  @Test
  fun `save completion cannot revive slot after current session becomes unavailable`() {
    var nextRequestId = 0L
    val publications = mutableListOf<Pair<Int, Boolean>>()
    val tracker =
        StateSlotLoadAvailabilityTracker(
            nextRequestId = { ++nextRequestId },
            postRequest = {},
            publish = { slot, available -> publications += slot to available },
        )

    tracker.sessionChanged(StateUxSessionEvent(1, true, Path.of("game-1")))
    tracker.availabilityChanged(StateSlotLoadAvailabilityEvent(1, 1, StateRef.Slot(0), true))
    tracker.sessionChanged(StateUxSessionEvent(1, false, null))
    val publicationCount = publications.size

    tracker.operationCompleted(
        StateOperationCompletedEvent(
            requestId = 2,
            sessionId = 1,
            operation = StateOperation.SAVE,
            ref = StateRef.Slot(0),
            message = "Saved",
        ))

    assertEquals(publicationCount, publications.size)
    assertEquals(0 to false, publications.last())
  }

  @Test
  fun `named state validation retains draft until printable bounded text is entered`() {
    assertFalse(validateNamedStateLabel("   ").valid)
    assertFalse(validateNamedStateLabel("bad\u0000name").valid)
    assertFalse(validateNamedStateLabel("x".repeat(121)).valid)
    assertTrue(validateNamedStateLabel("Before the final boss").valid)
  }

  @Test
  fun `external quick save refreshes an open state browser without owning its status`() {
    assertEquals(
        StateBrowserCompletionAction.REFRESH_CATALOG,
        stateBrowserCompletionAction(StateOperation.SAVE, ownedByBrowser = false),
    )
    assertEquals(
        StateBrowserCompletionAction.IGNORE,
        stateBrowserCompletionAction(StateOperation.DELETE, ownedByBrowser = false),
    )
    assertEquals(
        StateBrowserCompletionAction.REFRESH_CATALOG,
        stateBrowserCompletionAction(StateOperation.DELETE, ownedByBrowser = true),
    )
    assertEquals(
        StateBrowserCompletionAction.UPDATE_SELECTION,
        stateBrowserCompletionAction(StateOperation.LOAD, ownedByBrowser = true),
    )
  }

  @Test
  fun `selectable path panel exposes normalized copyable path`() =
      onEdt {
        val path = Path.of("screenshots", "draft", "..", ".", "capture.png")
        val expected = path.toAbsolutePath().normalize().toString()

        val panel = selectablePathPanel("Screenshot saved", path)
        val message = panel.components.filterIsInstance<JLabel>().single()
        val field = panel.components.filterIsInstance<JTextField>().single()

        assertEquals("Screenshot saved", message.text)
        assertEquals(expected, field.text)
        assertFalse(field.isEditable)
        assertEquals("Copyable path", field.accessibleContext.accessibleName)

        field.selectAll()
        assertEquals(expected, field.selectedText)
      }

  @Test
  fun `state browser host retains one view forwards events and disposes only with its owner`() {
    val initial = StateUxSessionEvent(1, true, Path.of("game"))
    val next = StateUxSessionEvent(2, false, null)
    val completed =
        StateOperationCompletedEvent(
            requestId = 4,
            sessionId = 2,
            operation = StateOperation.SAVE,
            message = "Saved",
        )
    val failed =
        StateOperationFailedEvent(
            requestId = 5,
            sessionId = 2,
            operation = StateOperation.LOAD,
            error = StateUserError("Load failed", "State was unavailable", "Refresh and retry"),
        )
    val view = RecordingStateBrowserView()
    var factoryCalls = 0
    lateinit var receivedInitial: StateUxSessionEvent
    val host =
        onEdt {
          StateBrowserWindowHost(
              initialSession = { initial },
              viewFactory =
                  StateBrowserWindowViewFactory { session ->
                    factoryCalls++
                    receivedInitial = checkNotNull(session)
                    view
                  },
          )
        }

    onEdt {
      host.updateSession(next)
      host.showOrRaise()
      host.showOrRaise()
      host.updateSession(next)
      host.operationCompleted(completed)
      host.operationFailed(failed)
      host.close()
      host.close()
      host.showOrRaise()
    }

    assertEquals(1, factoryCalls)
    assertSame(initial, receivedInitial)
    assertEquals(2, view.showCalls)
    assertEquals(listOf(next), view.sessions)
    assertEquals(listOf(completed), view.completions)
    assertEquals(listOf(failed), view.failures)
    assertEquals(1, view.closeCalls)
  }

  @Test
  fun `state browser cached preview and detail styles follow live theme tokens`() =
      onEdt {
        val preview = JLabel("No preview")
        val detail = JTextArea()
        val panel = StateBrowserContentPanel(preview, detail)
        val tokens =
            DesktopThemeTokens.capture(DesktopAppearance.SYSTEM).copy(
                elevatedSurface = Color(0x202428),
                primaryText = Color(0xF2F4F6),
                border = Color(0x8A929A),
            )

        panel.desktopThemeChanged(tokens)

        val title = assertIs<TitledBorder>(preview.border)
        assertEquals(tokens.primaryText, title.titleColor)
        assertEquals(tokens.border, assertIs<LineBorder>(title.border).lineColor)
        assertEquals(tokens.elevatedSurface, detail.background)
        assertEquals(tokens.primaryText, detail.foreground)
        assertEquals(tokens.primaryText, detail.caretColor)
        assertEquals(Font.PLAIN, detail.font.style)
      }

  private class RecordingStateBrowserView : StateBrowserWindowView {
    var showCalls = 0
    var closeCalls = 0
    val sessions = mutableListOf<StateUxSessionEvent>()
    val completions = mutableListOf<StateOperationCompletedEvent>()
    val failures = mutableListOf<StateOperationFailedEvent>()

    override fun showOrRaise() {
      showCalls++
    }

    override fun updateSession(event: StateUxSessionEvent) {
      sessions += event
    }

    override fun operationCompleted(event: StateOperationCompletedEvent) {
      completions += event
    }

    override fun operationFailed(event: StateOperationFailedEvent) {
      failures += event
    }

    override fun close() {
      closeCalls++
    }
  }

  private fun stateFailure() =
      StateUserError("Load failed", "State was unavailable", "Refresh and retry")

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
