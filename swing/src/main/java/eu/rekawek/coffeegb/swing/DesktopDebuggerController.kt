package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * A retained modeless debugger workspace whose lifetime is owned by [DesktopDebuggerController].
 *
 * [close] disposes only view-owned resources; the emulation session retains ownership of every
 * port supplied through [updateSession].
 */
internal interface DesktopDebuggerView : AutoCloseable {
  /** Replaces or revokes the view's session-bound command port. */
  fun updateSession(event: Controller.SessionDebugPortEvent)

  /** Updates the authoritative effective pause state for the current emulation session. */
  fun updatePlaybackState(event: Controller.SessionPlaybackStateEvent) = Unit

  /** Shows or raises one retained modeless debugger window. */
  fun showTool(tool: DebuggerWorkspaceTool)

  /** Shows and arranges the windows belonging to one built-in layout. */
  fun applyLayout(layout: DebuggerWorkspaceLayout)
}

/** Keeps the Swing owner explicit while allowing the lifecycle controller to be tested headlessly. */
internal fun interface DesktopDebuggerViewFactory {
  fun create(owner: JFrame): DesktopDebuggerView
}

/**
 * EDT-owned bridge between session debug-port publication and the modeless desktop debugger.
 *
 * The controller retains at most one workspace. Closing a tool window hides it, so the main Debug
 * menu can reopen the same window. Session generations are monotonic and a revocation is
 * terminal for its generation; delayed older publications therefore cannot resurrect a stopped or
 * replaced port.
 * The session owns [eu.rekawek.coffeegb.core.debug.DebugPort], so this class never closes one.
 */
internal class DesktopDebuggerController(
    rootEventBus: EventBus,
    private val createView: () -> DesktopDebuggerView,
) : AutoCloseable {
  private val eventBus: EventBus
  private var latestGeneration = NO_GENERATION
  private var currentSession: Controller.SessionDebugPortEvent? = null
  private var currentPlaybackState: Controller.SessionPlaybackStateEvent? = null
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
    eventBus.register<Controller.SessionPlaybackStateEvent> { event ->
      dispatchSwingMutation {
        if (closed || !acceptPlaybackState(event)) return@dispatchSwingMutation
        currentPlaybackState = event
        view?.updatePlaybackState(event)
      }
    }
  }

  fun showTool(tool: DebuggerWorkspaceTool) {
    requireDebuggerEdt("Desktop debugger tool opening")
    if (closed) return
    retainedView().showTool(tool)
  }

  fun applyLayout(layout: DebuggerWorkspaceLayout) {
    requireDebuggerEdt("Desktop debugger layout opening")
    if (closed) return
    retainedView().applyLayout(layout)
  }

  private fun retainedView(): DesktopDebuggerView =
      view
          ?: createView().also { created ->
            currentSession?.let(created::updateSession)
            currentPlaybackState?.let(created::updatePlaybackState)
            view = created
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

    val generationChanged = generation != latestGeneration
    latestGeneration = generation
    currentSession = event
    if (generationChanged || port == null) currentPlaybackState = null
    return true
  }

  private fun acceptPlaybackState(event: Controller.SessionPlaybackStateEvent): Boolean =
      event.sessionGeneration == latestGeneration && currentSession?.debugPort != null

  override fun close() {
    requireDebuggerEdt("Desktop debugger controller disposal")
    if (closed) return
    closed = true
    currentSession = null
    currentPlaybackState = null

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
