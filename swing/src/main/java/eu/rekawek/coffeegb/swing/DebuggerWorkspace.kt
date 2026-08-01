package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.EnumMap
import java.util.prefs.Preferences
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager

internal enum class DebuggerWorkspaceTool(
    val title: String,
    val accessibleName: String,
    val preferredSize: Dimension,
) {
  EXECUTION("Execution", "CPU execution debugger", Dimension(1600, 1900)),
  MEMORY("Memory", "Live memory debugger", Dimension(1400, 1100)),
  BREAKPOINTS("Breakpoints", "Breakpoint debugger", Dimension(1460, 1420)),
  VIDEO("Video", "Graphical video debugger", Dimension(1400, 1660)),
  HARDWARE("Hardware & I/O", "Semantic hardware and I O debugger", Dimension(2060, 1200)),
  AUDIO("Audio", "Audio debugger", Dimension(1880, 1050)),
  TIMELINE("Timeline", "Trace timeline debugger", Dimension(1800, 1180)),
}

internal enum class DebuggerWorkspaceLayout(val title: String) {
  CPU("CPU debugging"),
  GRAPHICS("Graphics debugging"),
  TIMING("Timing and I/O"),
  FULL("Full workspace"),
}

internal data class DebuggerWorkspaceToolPreferences(
    val bounds: Rectangle? = null,
    val held: Boolean = false,
)

internal data class DebuggerWorkspacePreferences(
    val tools: Map<DebuggerWorkspaceTool, DebuggerWorkspaceToolPreferences> = emptyMap(),
    val layout: DebuggerWorkspaceLayout = DebuggerWorkspaceLayout.CPU,
) {
  fun state(tool: DebuggerWorkspaceTool): DebuggerWorkspaceToolPreferences =
      tools[tool] ?: DebuggerWorkspaceToolPreferences()
}

/** Only window placement, update-hold state, and the last applied built-in layout are persisted. */
internal class DebuggerWorkspacePreferencesStore(
    private val node: Preferences =
        Preferences.userNodeForPackage(DebuggerWindow::class.java).node("debugger-workspace-v2"),
) {
  fun load(): DebuggerWorkspacePreferences =
      runCatching {
            val states =
                DebuggerWorkspaceTool.entries.associateWith { tool ->
                  val prefix = tool.name.lowercase()
                  val width = node.getInt("$prefix-width", -1)
                  val height = node.getInt("$prefix-height", -1)
                  val bounds =
                      Rectangle(
                              node.getInt("$prefix-x", 0),
                              node.getInt("$prefix-y", 0),
                              width,
                              height,
                          )
                          .takeIf(::validBounds)
                  DebuggerWorkspaceToolPreferences(
                      bounds = bounds,
                      held = node.getBoolean("$prefix-held", false),
                  )
                }
            DebuggerWorkspacePreferences(
                states,
                node.get("layout", null)?.let { encoded ->
                  DebuggerWorkspaceLayout.entries.firstOrNull { it.name == encoded }
                } ?: DebuggerWorkspaceLayout.CPU,
            )
          }
          .getOrDefault(DebuggerWorkspacePreferences())

  fun save(value: DebuggerWorkspacePreferences) {
    runCatching {
      value.tools.forEach { (tool, state) ->
        val prefix = tool.name.lowercase()
        state.bounds?.takeIf(::validBounds)?.let { bounds ->
          node.putInt("$prefix-x", bounds.x)
          node.putInt("$prefix-y", bounds.y)
          node.putInt("$prefix-width", bounds.width)
          node.putInt("$prefix-height", bounds.height)
        }
        node.putBoolean("$prefix-held", state.held)
      }
      node.put("layout", value.layout.name)
    }
  }

  private fun validBounds(bounds: Rectangle): Boolean =
      DesktopWindowPlacement.isPlausible(bounds, Dimension(420, 320))
}

/** Owns the independent modeless windows while [DebuggerPanel] owns the one live data stream. */
internal class DebuggerWorkspace(
    private val owner: JFrame,
    private val panel: DebuggerPanel,
    private val preferencesStore: DebuggerWorkspacePreferencesStore =
        DebuggerWorkspacePreferencesStore(),
) : AutoCloseable {
  private val loaded = preferencesStore.load()
  private val windows = EnumMap<DebuggerWorkspaceTool, ToolWindow>(DebuggerWorkspaceTool::class.java)
  private var currentLayout = loaded.layout
  private var closed = false

  init {
    require(SwingUtilities.isEventDispatchThread()) { "Debugger workspace construction must run on EDT" }
    DebuggerWorkspaceTool.entries.forEach { tool ->
      val saved = loaded.state(tool)
      windows[tool] = ToolWindow(tool, panel.workspaceComponent(tool), saved)
    }
    panel.setWorkspaceMode(true)
    updatePanelInterest()
  }

  fun showTool(tool: DebuggerWorkspaceTool) {
    check(SwingUtilities.isEventDispatchThread()) { "Debugger tool opening must run on EDT" }
    if (closed) return
    revealTool(tool)
    updatePanelInterest()
    savePreferences()
  }

  fun updateSession(event: eu.rekawek.coffeegb.controller.Controller.SessionDebugPortEvent) {
    panel.updateSession(event)
    windows.values.forEach(ToolWindow::sessionChanged)
  }

  override fun close() {
    check(SwingUtilities.isEventDispatchThread()) { "Debugger workspace disposal must run on EDT" }
    if (closed) return
    savePreferences()
    closed = true
    panel.close()
    windows.values.forEach { it.dialog.dispose() }
    windows.clear()
  }

  internal fun visibleTools(): Set<DebuggerWorkspaceTool> =
      windows.filterValues { it.dialog.isVisible }.keys.toSet()

  internal fun heldTools(): Set<DebuggerWorkspaceTool> =
      windows.filterValues { it.hold.isSelected }.keys.toSet()

  private fun revealTool(tool: DebuggerWorkspaceTool) {
    val window = windows.getValue(tool)
    if (!window.positioned) positionWindow(tool, window)
    window.dialog.isVisible = true
    window.dialog.toFront()
  }

  fun applyLayout(layout: DebuggerWorkspaceLayout) {
    check(SwingUtilities.isEventDispatchThread()) { "Debugger layout opening must run on EDT" }
    if (closed) return
    currentLayout = layout
    val visible = toolsFor(layout)
    windows.forEach { (tool, window) ->
      if (tool in visible) revealTool(tool) else window.dialog.isVisible = false
    }
    tile(visible.toList())
    updatePanelInterest()
    savePreferences()
  }

  private fun toolsFor(layout: DebuggerWorkspaceLayout): Set<DebuggerWorkspaceTool> =
      when (layout) {
        DebuggerWorkspaceLayout.CPU ->
            setOf(
                DebuggerWorkspaceTool.EXECUTION,
                DebuggerWorkspaceTool.MEMORY,
                DebuggerWorkspaceTool.BREAKPOINTS,
            )
        DebuggerWorkspaceLayout.GRAPHICS ->
            setOf(
                DebuggerWorkspaceTool.EXECUTION,
                DebuggerWorkspaceTool.VIDEO,
                DebuggerWorkspaceTool.HARDWARE,
            )
        DebuggerWorkspaceLayout.TIMING ->
            setOf(
                DebuggerWorkspaceTool.EXECUTION,
                DebuggerWorkspaceTool.HARDWARE,
                DebuggerWorkspaceTool.TIMELINE,
            )
        DebuggerWorkspaceLayout.FULL -> DebuggerWorkspaceTool.entries.toSet()
      }

  private fun tile(tools: List<DebuggerWorkspaceTool>) {
    if (tools.isEmpty()) return
    val screen = usableScreenBounds()
    val columns = when {
      tools.size <= 1 -> 1
      tools.size <= 4 -> 2
      else -> 3
    }
    val rows = (tools.size + columns - 1) / columns
    val width = screen.width / columns
    val height = screen.height / rows
    val canTileWithoutReducingDefaults =
        tools.all { tool ->
          val defaultSize = debuggerToolDefaultSize(tool, screen)
          width >= defaultSize.width && height >= defaultSize.height
        }
    if (!canTileWithoutReducingDefaults) {
      tools.forEachIndexed { index, tool ->
        val offset = (index * 28) % 196
        val size = debuggerToolDefaultSize(tool, screen)
        windows.getValue(tool).dialog.setBounds(
            (screen.x + offset).coerceAtMost(screen.x + screen.width - size.width),
            (screen.y + offset).coerceAtMost(screen.y + screen.height - size.height),
            size.width,
            size.height,
        )
        windows.getValue(tool).positioned = true
      }
      return
    }
    tools.forEachIndexed { index, tool ->
      val column = index % columns
      val row = index / columns
      windows.getValue(tool).dialog.setBounds(
          screen.x + column * width,
          screen.y + row * height,
          minOf(width, screen.x + screen.width - (screen.x + column * width)),
          minOf(height, screen.y + screen.height - (screen.y + row * height)),
      )
      windows.getValue(tool).positioned = true
    }
  }

  private fun positionWindow(tool: DebuggerWorkspaceTool, window: ToolWindow) {
    val saved = restorableDebuggerBounds(window.savedBounds)
    if (saved != null) {
      window.dialog.bounds = saved
    } else {
      window.dialog.pack()
      val screen = usableScreenBounds()
      val size = debuggerToolDefaultSize(tool, screen)
      window.dialog.size = size
      val offset = tool.ordinal * 24
      window.dialog.setLocation(
          (screen.x + (screen.width - size.width) / 2 + offset)
              .coerceAtMost(screen.x + screen.width - size.width),
          (screen.y + (screen.height - size.height) / 2 + offset)
              .coerceAtMost(screen.y + screen.height - size.height),
      )
    }
    window.positioned = true
  }

  private fun updatePanelInterest() {
    if (closed) return
    val visible = visibleTools()
    val held = heldTools()
    DebuggerWorkspaceTool.entries.forEach { tool ->
      panel.setWorkspaceToolState(tool, tool in visible, tool in held)
    }
    panel.setWorkspaceSamplingActive((visible - held).isNotEmpty())
    panel.setPollingActive(visible.isNotEmpty())
    windows.values.forEach(ToolWindow::updateStatus)
  }

  private fun savePreferences() {
    if (closed) return
    val states =
        windows.mapValues { (_, window) ->
          DebuggerWorkspaceToolPreferences(
              bounds = window.dialog.bounds.takeIf { window.positioned },
              held = window.hold.isSelected,
          )
        }
    preferencesStore.save(DebuggerWorkspacePreferences(states, currentLayout))
  }

  private inner class ToolWindow(
      val tool: DebuggerWorkspaceTool,
      content: Component,
      preferences: DebuggerWorkspaceToolPreferences,
  ) {
    // Native owned dialogs stay above their owner on macOS. Debugger tools are independent normal
    // windows, so activating the emulator can put them behind it just like any other tool window.
    val dialog =
        JDialog(null as Window?, "${tool.title} — Coffee GB", Dialog.ModalityType.MODELESS).apply {
          type = Window.Type.NORMAL
          isAlwaysOnTop = false
        }
    val hold = JCheckBox("Hold updates", preferences.held)
    val live = JLabel("LIVE", SwingConstants.CENTER)
    val footer = JLabel("Waiting for a debugger session")
    val savedBounds = preferences.bounds
    var positioned = false
    private var heldFooterText: String? = null

    init {
      val stateBar = object : JPanel(BorderLayout(8, 0)), DesktopThemeRefreshHook {
        override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
          live.border = BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(tokens.border),
              BorderFactory.createEmptyBorder(2, 7, 2, 7),
          )
          repaint()
        }
      }.apply {
        border = BorderFactory.createEmptyBorder(4, 7, 4, 7)
        live.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY),
            BorderFactory.createEmptyBorder(2, 7, 2, 7),
        )
        add(live, BorderLayout.WEST)
        add(JLabel("Realtime coherent snapshots · no refresh required"), BorderLayout.CENTER)
        add(hold, BorderLayout.EAST)
      }
      footer.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
      footer.accessibleContext.accessibleName = "${tool.title} status"
      val root = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        add(stateBar, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
      }

      dialog.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
      dialog.minimumSize = Dimension(420, 320)
      dialog.preferredSize = tool.preferredSize
      dialog.contentPane = root
      dialog.accessibleContext.accessibleName = tool.accessibleName
      installWorkspaceBindings(root, tool)
      dialog.addWindowListener(
          object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
              SwingUtilities.invokeLater {
                updatePanelInterest()
                savePreferences()
              }
            }
          })
      dialog.addComponentListener(
          object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) {
              updatePanelInterest()
            }

            override fun componentHidden(event: ComponentEvent) {
              updatePanelInterest()
              savePreferences()
            }
          })
      hold.toolTipText =
          "Freeze this window's presentation and withdraw its optional data interest"
      hold.accessibleContext.accessibleDescription = hold.toolTipText
      hold.addActionListener {
        updatePanelInterest()
        savePreferences()
      }
      panel.snapshotLabel.addPropertyChangeListener("text") { updateStatus() }
      panel.statusLabel.addPropertyChangeListener("text") { updateStatus() }
      dialog.pack()
      restorableDebuggerBounds(savedBounds)?.let { bounds ->
        dialog.bounds = bounds
        positioned = true
      }
      if (savedBounds == null) dialog.size = debuggerToolDefaultSize(tool, usableScreenBounds())
      updateStatus()
    }

    fun updateStatus() {
      if (!SwingUtilities.isEventDispatchThread()) {
        SwingUtilities.invokeLater(::updateStatus)
        return
      }
      val held = hold.isSelected
      live.text = if (held) "HELD" else if (dialog.isVisible) "LIVE" else "HIDDEN"
      val stateText = panel.snapshotLabel.text
      if (held) {
        if (heldFooterText == null) heldFooterText = "$stateText · updates held"
        footer.text = heldFooterText
      } else {
        heldFooterText = null
        footer.text = "$stateText · ${panel.statusLabel.text}"
      }
    }

    fun sessionChanged() {
      heldFooterText = null
      updateStatus()
    }
  }

  private fun installWorkspaceBindings(
      root: JComponent,
      tool: DebuggerWorkspaceTool,
  ) {
    val input = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    fun bind(stroke: KeyStroke, name: String, action: () -> Unit) {
      input.put(stroke, name)
      root.actionMap.put(
          name,
          object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = action()
          },
      )
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "workspace-run-control") {
      when {
        panel.pauseButton.isEnabled -> panel.pauseButton.doClick()
        panel.runButton.isEnabled -> panel.runButton.doClick()
      }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), "workspace-step") {
      if (panel.stepInstructionButton.isEnabled) panel.stepInstructionButton.doClick()
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK),
        "workspace-step-frame",
    ) {
      if (panel.stepFrameButton.isEnabled) panel.stepFrameButton.doClick()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), "workspace-back") {
      if (panel.backInstructionButton.isEnabled) panel.backInstructionButton.doClick()
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK),
        "workspace-back-frame",
    ) {
      if (panel.backFrameButton.isEnabled) panel.backFrameButton.doClick()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), "workspace-breakpoint") {
      if (panel.breakpointPane.toggleCurrentPcButton.isEnabled) {
        panel.breakpointPane.toggleCurrentPcButton.doClick()
      }
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_C, debuggerMenuShortcutMask()),
        "workspace-copy",
    ) {
      panel.copyWorkspaceTool(tool)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, debuggerMenuShortcutMask()),
        "workspace-zoom-in",
    ) {
      panel.workspaceZoom(DebuggerUiPreferences.FONT_SCALE_STEP)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, debuggerMenuShortcutMask()),
        "workspace-zoom-out",
    ) {
      panel.workspaceZoom(-DebuggerUiPreferences.FONT_SCALE_STEP)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_0, debuggerMenuShortcutMask()),
        "workspace-zoom-reset",
    ) {
      panel.workspaceResetZoom()
    }
  }

  private fun usableScreenBounds(): Rectangle {
    val configuration = owner.graphicsConfiguration
    val bounds = Rectangle(configuration.bounds)
    val insets = owner.toolkit.getScreenInsets(configuration)
    bounds.x += insets.left
    bounds.y += insets.top
    bounds.width -= insets.left + insets.right
    bounds.height -= insets.top + insets.bottom
    return bounds
  }

  private fun intersectsScreen(bounds: Rectangle): Boolean =
      GraphicsEnvironment.getLocalGraphicsEnvironment()
          .screenDevices
          .flatMap { it.configurations.asIterable() }
          .any { configuration -> configuration.bounds.intersection(bounds).let { it.width >= 80 && it.height >= 60 } }

  private fun restorableDebuggerBounds(bounds: Rectangle?): Rectangle? =
      DesktopWindowPlacement.restorableBounds(
          bounds,
          AwtScreenLayoutProvider(owner).snapshot(),
          Dimension(420, 320),
      )
}

/**
 * Gives every debugger tool its full default workspace while keeping it on the current display.
 *
 * The margin prevents a maximally wide modeless dialog from overlapping the desktop edge or dock.
 * If a display is genuinely smaller than a tool's complete table layout, scrolling remains the
 * only practical fallback.
 */
internal fun debuggerToolDefaultSize(
    tool: DebuggerWorkspaceTool,
    usableScreen: Rectangle,
): Dimension =
    Dimension(
        minOf(tool.preferredSize.width, (usableScreen.width - DEFAULT_WINDOW_SCREEN_MARGIN).coerceAtLeast(1)),
        minOf(tool.preferredSize.height, (usableScreen.height - DEFAULT_WINDOW_SCREEN_MARGIN).coerceAtLeast(1)),
    )

private const val DEFAULT_WINDOW_SCREEN_MARGIN = 48
