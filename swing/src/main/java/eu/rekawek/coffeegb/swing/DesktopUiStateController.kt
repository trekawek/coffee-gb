package eu.rekawek.coffeegb.swing

import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * EDT owner for harmless window placement and last-category state. It deliberately never records
 * fullscreen geometry, utility visibility, drafts, paths, or feature data.
 */
internal class DesktopUiStateController(
    private val frame: JFrame,
    private val store: DesktopUiStateStore,
    initial: DesktopUiState,
    private val screenProvider: ScreenLayoutProvider = AwtScreenLayoutProvider(frame),
    private val isFullscreen: () -> Boolean,
    private val onSaveFailure: () -> Unit = {},
) : AutoCloseable {
  private var state = initial
  private var installed = false
  private var restoring = false
  private val saveTimer =
      Timer(SAVE_DEBOUNCE_MILLIS) { persistNow() }.apply {
        isRepeats = false
        isCoalesce = true
      }
  private val componentListener =
      object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = captureMainWindow()

        override fun componentResized(event: ComponentEvent) = captureMainWindow()
      }
  private val stateListener = WindowStateListener { captureMainWindow() }
  fun restoreMainWindow(legacyOuterSize: DesktopSize? = null) {
    requireEdt()
    restoring = true
    try {
      val layout = screenProvider.snapshot()
      val saved =
          DesktopWindowPlacement.restorableBounds(
              state.mainWindow.normalBounds,
              layout,
              DesktopUiStateStore.MAIN_MINIMUM_SIZE,
          )
      val restored =
          saved
              ?: legacyOuterSize?.let { size ->
                DesktopWindowPlacement.centeredBounds(
                    size,
                    layout.primary().usableBounds,
                    DesktopUiStateStore.MAIN_MINIMUM_SIZE,
                )
              }
      if (restored != null) {
        frame.bounds = Rectangle(restored.x, restored.y, restored.width, restored.height)
      } else {
        frame.setLocationRelativeTo(null)
      }
      if (state.mainWindow.maximized) {
        frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
      }
    } finally {
      restoring = false
    }
  }

  fun install() {
    requireEdt()
    if (installed) return
    installed = true
    frame.addComponentListener(componentListener)
    frame.addWindowStateListener(stateListener)
  }

  fun utilityBounds(window: DesktopUtilityWindow): Rectangle? {
    requireEdt()
    val bounds =
        DesktopWindowPlacement.restorableBounds(
            state.bounds(window),
            screenProvider.snapshot(),
            DesktopUiStateStore.UTILITY_MINIMUM_SIZE,
        ) ?: return null
    return Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
  }

  fun rememberUtilityBounds(window: DesktopUtilityWindow, bounds: Rectangle) {
    requireEdt()
    val value =
        runCatching { DesktopBounds(bounds.x, bounds.y, bounds.width, bounds.height) }
            .getOrNull()
            ?.takeIf {
              DesktopWindowPlacement.isPlausible(
                  it,
                  DesktopUiStateStore.UTILITY_MINIMUM_SIZE,
              )
            }
            ?: return
    state = state.copy(utilityBounds = state.utilityBounds + (window to value))
    scheduleSave()
  }

  fun lastPreferencesCategory(): DesktopPreferencesCategory = state.lastPreferencesCategory

  fun rememberPreferencesCategory(category: DesktopPreferencesCategory) {
    requireEdt()
    if (category == state.lastPreferencesCategory) return
    state = state.copy(lastPreferencesCategory = category)
    scheduleSave()
  }

  fun snapshot(): DesktopUiState = state

  override fun close() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(::close)
      return
    }
    if (installed) {
      captureMainWindow(schedule = false)
      frame.removeComponentListener(componentListener)
      frame.removeWindowStateListener(stateListener)
      installed = false
    }
    saveTimer.stop()
    persistNow()
  }

  private fun captureMainWindow(schedule: Boolean = true) {
    if (!installed || restoring || isFullscreen()) return
    val maximized = frame.extendedState and Frame.MAXIMIZED_BOTH != 0
    var normalBounds = state.mainWindow.normalBounds
    if (!maximized) {
      val bounds = frame.bounds
      val candidate =
          runCatching { DesktopBounds(bounds.x, bounds.y, bounds.width, bounds.height) }
              .getOrNull()
      if (
          candidate != null &&
              DesktopWindowPlacement.isPlausible(
                  candidate,
                  DesktopUiStateStore.MAIN_MINIMUM_SIZE,
              )
      ) {
        normalBounds = candidate
      }
    }
    val next = DesktopMainWindowState(normalBounds, maximized)
    if (next == state.mainWindow) return
    state = state.copy(mainWindow = next)
    if (schedule) scheduleSave()
  }

  private fun scheduleSave() {
    saveTimer.restart()
  }

  private fun persistNow() {
    if (!store.save(state)) onSaveFailure()
  }

  private fun requireEdt() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Desktop UI state must be coordinated on the Event Dispatch Thread"
    }
  }

  private companion object {
    const val SAVE_DEBOUNCE_MILLIS = 400
  }
}
