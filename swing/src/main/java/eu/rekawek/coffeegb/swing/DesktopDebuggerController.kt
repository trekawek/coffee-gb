package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * A modeless debugger surface whose lifetime is owned by [DesktopDebuggerController].
 *
 * [close] disposes only view-owned resources; the emulation session retains ownership of every
 * port supplied through [updateSession].
 */
internal interface DesktopDebuggerView : AutoCloseable {
  /** Replaces or revokes the view's session-bound command port. */
  fun updateSession(event: Controller.SessionDebugPortEvent)

  /** Shows, raises, or reopens the retained modeless surface. */
  fun showWindow()
}

/** Keeps the Swing owner explicit while allowing the lifecycle controller to be tested headlessly. */
internal fun interface DesktopDebuggerViewFactory {
  fun create(owner: JFrame): DesktopDebuggerView
}

/**
 * EDT-owned bridge between session debug-port publication and the modeless desktop debugger.
 *
 * The controller retains at most one view. Closing the window hides it, so the Tools command can
 * reopen the same surface. Session generations are monotonic and a revocation is terminal for its
 * generation; delayed older publications therefore cannot resurrect a stopped or replaced port.
 * The session owns [eu.rekawek.coffeegb.core.debug.DebugPort], so this class never closes one.
 */
internal class DesktopDebuggerController(
    rootEventBus: EventBus,
    private val createView: () -> DesktopDebuggerView,
) : AutoCloseable {
  private val eventBus: EventBus
  private var latestGeneration = NO_GENERATION
  private var currentSession: Controller.SessionDebugPortEvent? = null
  private var view: DesktopDebuggerView? = null
  private var closed = false

  constructor(
      owner: JFrame,
      rootEventBus: EventBus,
      viewFactory: DesktopDebuggerViewFactory,
  ) : this(rootEventBus, { viewFactory.create(owner) })

  init {
    requireDebuggerEdt("Desktop debugger controller construction")
    eventBus = rootEventBus.fork("desktop-debugger")
    eventBus.register<Controller.SessionDebugPortEvent> { event ->
      dispatchSwingMutation {
        if (closed || !acceptSessionEvent(event)) return@dispatchSwingMutation
        view?.updateSession(event)
      }
    }
  }

  fun showDebugger() {
    requireDebuggerEdt("Desktop debugger opening")
    if (closed) return
    val target =
        view
            ?: createView().also { created ->
              currentSession?.let(created::updateSession)
              view = created
            }
    target.showWindow()
  }

  private fun acceptSessionEvent(event: Controller.SessionDebugPortEvent): Boolean {
    val generation = event.generation
    if (generation < 0 || generation < latestGeneration) return false

    val port = event.debugPort
    if (port != null && port.sessionGeneration() != generation) return false

    if (generation == latestGeneration) {
      val currentPort = currentSession?.debugPort
      // Revocation is terminal. A late publication for the same generation must not resurrect it.
      if (currentPort == null) return false
      // A generation identifies one immutable command-port instance.
      if (port != null) return false
    }

    latestGeneration = generation
    currentSession = event
    return true
  }

  override fun close() {
    requireDebuggerEdt("Desktop debugger controller disposal")
    if (closed) return
    closed = true
    currentSession = null

    var failure: Exception? = null
    try {
      eventBus.close()
    } catch (problem: Exception) {
      failure = problem
    }
    try {
      view?.close()
    } catch (problem: Exception) {
      failure?.addSuppressed(problem) ?: run { failure = problem }
    } finally {
      view = null
    }
    failure?.let { throw it }
  }

  private companion object {
    const val NO_GENERATION = -1L
  }
}

private fun requireDebuggerEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the Event Dispatch Thread" }
}
