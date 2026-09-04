package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.SwingConstants

internal data class DesktopSessionTask(
    val message: String,
    val cancellable: Boolean = false,
) {
  init {
    require(message.isNotBlank())
  }
}

/** Complete immutable presentation consumed by the main frame content. */
internal data class DesktopPresentation(
    val gameTitle: String? = null,
    /** Mapper capability, not save-file state. */
    val batterySaveActive: Boolean = false,
    /** Controller-owned identity used to restart elapsed play time for a new ROM session. */
    val sessionGeneration: Long? = null,
    /** Snapshot-friendly value; the action bridge supplies a live value while the game runs. */
    val playTimeNanos: Long = 0,
    val task: DesktopSessionTask? = null,
    val commands: DesktopCommandPresentation = DesktopCommandPresentation(),
    val netplaySummary: String = "Netplay: Off",
    val persistentStatus: String = "Ready",
    /** Presentation-thread rate, intentionally distinct from emulated VBlank cadence. */
    val presentedFramesPerSecond: Double? = null,
    /** Durable recovery notice; routine lifecycle status changes must not erase it. */
    val notice: DesktopNotice? = null,
    /** Compatibility path for direct presentation callers; coordinators use [notice]. */
    val statusRecoveryCommand: DesktopCommand? = null,
) {
  init {
    require(gameTitle == null || gameTitle.isNotBlank())
    require(sessionGeneration == null || sessionGeneration >= 0)
    require(playTimeNanos >= 0)
    require(netplaySummary.isNotBlank())
    require(persistentStatus.isNotBlank())
    require(presentedFramesPerSecond == null ||
        (presentedFramesPerSecond.isFinite() && presentedFramesPerSecond >= 0))
  }

  val visibleStatus: String
    get() = notice?.message ?: persistentStatus
}

internal data class DesktopNotice(
    val message: String,
    val recoveryCommand: DesktopCommand? = null,
) {
  init {
    require(message.isNotBlank())
  }
}

/** Home/game card shell. The emulator raster remains an untouched child of [gameSurface]. */
internal class DesktopMainPanel(
    gameSurface: JComponent,
    private val actions: DesktopActionRegistry,
    onOpenRecent: (DesktopRecentGame) -> Unit,
    onCancelTask: () -> Unit,
    initialTokens: DesktopThemeTokens,
    showHomeRecentGames: Boolean = true,
) : JPanel(BorderLayout()), DesktopThemeRefreshHook {
  private val cards = CardLayout()
  private val home =
      DesktopHomePanel(
          actions[DesktopCommand.OPEN_ROM],
          onOpenRecent,
          showHomeRecentGames,
      )
  private val commandBar = DesktopCommandBar(actions)
  private val taskBanner = DesktopTaskBanner(onCancelTask)
  private val statusBar = DesktopStatusBar(actions)
  private val game = JPanel(BorderLayout())
  private val footer = JPanel(BorderLayout())
  private var presentation = DesktopPresentation()
  /** The portable menu is painted by [game]'s display surface, including while the desktop is idle. */
  private var portableMenuVisible = false
  private var exactOneCommandBarSuppressed = false
  private val cardHost =
      object : JPanel(cards) {
        override fun getPreferredSize(): Dimension =
            if (presentation.gameTitle == null) home.preferredSize else game.preferredSize

        override fun getMinimumSize(): Dimension =
            if (presentation.gameTitle == null) home.minimumSize else game.minimumSize
      }
  private var tokens = initialTokens

  init {
    name = "Coffee GB main content"
    getAccessibleContext().accessibleName = "Coffee GB emulator"

    game.add(commandBar, BorderLayout.PAGE_START)
    game.add(gameSurface, BorderLayout.CENTER)
    cardHost.add(home, HOME_CARD)
    cardHost.add(game, GAME_CARD)
    add(cardHost, BorderLayout.CENTER)
    footer.add(taskBanner, BorderLayout.PAGE_START)
    footer.add(statusBar, BorderLayout.PAGE_END)
    add(footer, BorderLayout.PAGE_END)
    addComponentListener(
        object : ComponentAdapter() {
          override fun componentResized(event: ComponentEvent) {
            updateCommandBarVisibility(allowExactOneReveal = true)
          }
        })
    desktopThemeChanged(tokens)
    render(presentation)
  }

  fun render(next: DesktopPresentation) {
    if (next.commands.exactWindowScaleOne && !presentation.commands.exactWindowScaleOne) {
      exactOneCommandBarSuppressed = true
    } else if (!next.commands.exactWindowScaleOne) {
      exactOneCommandBarSuppressed = false
    }
    presentation = next
    actions.updatePresentation(next)
    val playing = next.gameTitle != null
    updateVisibleCard(playing)
    updateCommandBarVisibility(allowExactOneReveal = false)
    commandBar.setNetplaySummary(next.netplaySummary)
    commandBar.synchronizeStateSlot(next.commands.stateSlot)
    taskBanner.render(next.task)
    statusBar.render(next)
    getAccessibleContext().accessibleDescription = next.visibleStatus
    revalidate()
    repaint()
  }

  /**
   * Reveals the display card for a Proposal 3 overlay even when no ROM is loaded.
   *
   * The legacy Home card otherwise owns the idle center area, which would leave a correctly
   * rendered portable Library frame hidden behind its Swing controls.
   */
  fun setPortableMenuVisible(visible: Boolean) {
    if (portableMenuVisible == visible) return
    portableMenuVisible = visible
    updateVisibleCard(presentation.gameTitle != null)
    revalidate()
    repaint()
  }

  private fun updateCommandBarVisibility(allowExactOneReveal: Boolean) {
    val commands = presentation.commands
    val playing = presentation.gameTitle != null
    if (allowExactOneReveal &&
        playing &&
        commands.exactWindowScaleOne &&
        width >= EXACT_ONE_COMMAND_BAR_REVEAL_WIDTH) {
      exactOneCommandBarSuppressed = false
    }
    commandBar.isVisible =
        playing &&
            commands.commandBarVisible &&
            !commands.fullscreen &&
            !(commands.exactWindowScaleOne && exactOneCommandBarSuppressed)
  }

  fun updateRecentRoms(paths: List<Path>) =
      updateRecentGames(paths.map(::DesktopRecentGame))

  fun updateRecentGames(games: List<DesktopRecentGame>) {
    home.updateRecentGames(games)
  }

  fun current(): DesktopPresentation = presentation

  private fun updateVisibleCard(playing: Boolean) {
    cards.show(cardHost, if (playing || portableMenuVisible) GAME_CARD else HOME_CARD)
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    this.tokens = tokens
    background = tokens.surface
    home.applyTheme(tokens)
    taskBanner.applyTheme(tokens)
    statusBar.applyTheme(tokens)
    footer.background = tokens.surface
    repaint()
  }

  private companion object {
    const val HOME_CARD = "home"
    const val GAME_CARD = "game"
    const val EXACT_ONE_COMMAND_BAR_REVEAL_WIDTH = 720
  }
}

/** Always-visible textual status, including the compact full-screen state vocabulary. */
internal class DesktopStatusBar(
    private val actions: DesktopActionRegistry,
) : JPanel(BorderLayout(12, 0)) {
  internal val message = JLabel("Ready")
  internal val session = JLabel()
  internal val recovery = JButton()
  private val trailing = JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0))

  init {
    getAccessibleContext().accessibleName = "Emulator status bar"
    message.accessibleContext.accessibleName = "Emulator status"
    session.horizontalAlignment = SwingConstants.TRAILING
    session.accessibleContext.accessibleName = "Emulator session status"
    recovery.isVisible = false
    trailing.add(session)
    trailing.add(recovery)
    add(message, BorderLayout.CENTER)
    add(trailing, BorderLayout.LINE_END)
  }

  fun render(presentation: DesktopPresentation) {
    message.text = presentation.visibleStatus
    val commandState = presentation.commands
    session.text =
        buildList {
              if (commandState.paused) add("Paused")
              if (commandState.muted) add("Muted")
              if (presentation.gameTitle != null) add("Slot ${commandState.stateSlot}")
              presentation.presentedFramesPerSecond?.let {
                add(String.format(Locale.ROOT, "FPS %.1f", it))
              }
              add(presentation.netplaySummary)
            }
            .joinToString("  ·  ")
    message.toolTipText = presentation.visibleStatus
    session.toolTipText = session.text
    val recoveryCommand =
        presentation.notice?.recoveryCommand ?: presentation.statusRecoveryCommand
    recovery.isVisible = recoveryCommand != null
    if (recoveryCommand != null) {
      recovery.action = actions[recoveryCommand]
      recovery.accessibleContext.accessibleDescription =
          "Recover from the current emulator status"
    } else {
      recovery.action = null
    }
    message.accessibleContext.accessibleDescription = presentation.visibleStatus
    session.accessibleContext.accessibleDescription = session.text
    getAccessibleContext().accessibleDescription =
        listOf(presentation.visibleStatus, session.text).filter(String::isNotBlank).joinToString(". ")
  }

  fun applyTheme(tokens: DesktopThemeTokens) {
    background = tokens.surface
    trailing.background = tokens.surface
    message.foreground = tokens.primaryText
    session.foreground = tokens.secondaryText
    border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, tokens.border),
            BorderFactory.createEmptyBorder(5, 10, 5, 10),
        )
  }
}

internal class DesktopHomePanel(
    openAction: Action,
    private val onOpenRecent: (DesktopRecentGame) -> Unit,
    private val showRecentGames: Boolean = true,
) : JPanel(BorderLayout()) {
  private val content = JPanel()
  private val recentList = JPanel()
  private var tokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
  private var recentGames: List<DesktopRecentGame> = emptyList()

  init {
    preferredSize = Dimension(640, 600)
    minimumSize = Dimension(320, 288)
    getAccessibleContext().accessibleName = "Coffee GB home"

    content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
    content.border = BorderFactory.createEmptyBorder(42, 48, 36, 48)

    val open = JButton(openAction).apply { isFocusable = false }
    val openRow = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
      isOpaque = false
      alignmentX = Component.CENTER_ALIGNMENT
      maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
      add(open)
    }
    recentList.layout = FlowLayout(FlowLayout.CENTER, 12, 12)
    recentList.alignmentX = Component.CENTER_ALIGNMENT
    recentList.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    content.add(Box.createVerticalGlue())
    content.add(openRow)
    content.add(Box.createVerticalStrut(24))
    content.add(recentList)
    content.add(Box.createVerticalGlue())

    add(content, BorderLayout.CENTER)
    updateRecentGames(emptyList())
  }

  fun updateRecentGames(games: List<DesktopRecentGame>) {
    recentGames = games.toList()
    recentList.removeAll()
    if (showRecentGames) {
      recentGames.forEach { game -> recentList.add(createRecentThumbnail(game)) }
    }
    recentList.isVisible = showRecentGames && recentGames.isNotEmpty()
    revalidate()
    repaint()
  }

  fun applyTheme(tokens: DesktopThemeTokens) {
    this.tokens = tokens
    background = tokens.surface
    content.background = tokens.surface
    recentList.background = tokens.surface
    updateRecentGames(recentGames)
  }

  private fun createRecentThumbnail(game: DesktopRecentGame): JComponent {
    val path = game.path
    val name = game.title
    return JButton(game.thumbnail?.let(::thumbnailIcon)).apply {
      preferredSize = RECENT_PREVIEW_SIZE
      minimumSize = RECENT_PREVIEW_SIZE
      background = tokens.elevatedSurface
      isFocusable = false
      toolTipText = path.toString()
      accessibleContext.accessibleName = "Open recent ROM $name"
      accessibleContext.accessibleDescription = path.toString()
      if (game.thumbnail == null) {
        text = "No preview"
        foreground = tokens.secondaryText
      }
      addActionListener { onOpenRecent(game) }
    }
  }

  private fun thumbnailIcon(image: eu.rekawek.coffeegb.controller.state.StateImage): ImageIcon {
    val source = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    source.setRGB(0, 0, image.width, image.height, image.copyRgb(), 0, image.width)
    return ImageIcon(
        source.getScaledInstance(
            RECENT_PREVIEW_SIZE.width,
            RECENT_PREVIEW_SIZE.height,
            Image.SCALE_FAST,
        ))
  }

  private companion object {
    val RECENT_PREVIEW_SIZE = Dimension(104, 94)
  }
}

internal class DesktopCommandBar(
    private val actions: DesktopActionRegistry,
) : JPanel(BorderLayout(8, 0)) {
  // These controls are deliberately pointer-only. Retaining focus after a click lets Swing's
  // button/combo bindings steal emulator keys such as Space, Enter, or the arrows.
  private val slot =
      JComboBox((0..9).map { "Slot $it" }.toTypedArray()).apply { isFocusable = false }
  private val netplay = gameButton(actions[DesktopCommand.NETPLAY])
  private val secondary = JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0))
  private val overflow = JButton("More").apply { isFocusable = false }
  private val overflowMenu = JPopupMenu()
  private var synchronizingSlot = false

  init {
    border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    getAccessibleContext().accessibleName = "Game commands"

    val primary = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0))
    primary.add(gameButton(actions[DesktopCommand.PAUSE]))
    primary.add(gameButton(actions[DesktopCommand.SAVE_STATE]))
    primary.add(gameButton(actions[DesktopCommand.LOAD_STATE]))
    slot.accessibleContext.accessibleName = "Current state slot"
    slot.addActionListener {
      if (!synchronizingSlot) {
        val selected = slot.selectedIndex.coerceIn(0, 9)
        val action = actions.stateSlotActions[selected]
        if (slot.isEnabled && action.isEnabled) {
          action.actionPerformed(
              java.awt.event.ActionEvent(slot, ActionEventId, "select-state-slot"))
        }
      }
    }
    primary.add(slot)

    secondary.add(gameButton(actions[DesktopCommand.OPEN_ROM]))
    secondary.add(gameToggleButton(actions[DesktopCommand.MUTE]))
    secondary.add(netplay)
    secondary.add(gameToggleButton(actions[DesktopCommand.FULLSCREEN]))

    listOf(
            DesktopCommand.OPEN_ROM,
            DesktopCommand.MUTE,
            DesktopCommand.NETPLAY,
            DesktopCommand.FULLSCREEN,
            DesktopCommand.SCREENSHOT,
            DesktopCommand.INPUT_RECORDING,
            DesktopCommand.MANAGE_STATES,
        )
        .forEach { command -> overflowMenu.add(JMenuItem(actions[command])) }
    overflow.toolTipText = "More game commands"
    overflow.accessibleContext.accessibleName = "More game commands"
    overflow.addActionListener { overflowMenu.show(overflow, 0, overflow.height) }

    add(primary, BorderLayout.LINE_START)
    add(secondary, BorderLayout.CENTER)
    add(overflow, BorderLayout.LINE_END)
    addComponentListener(
        object : ComponentAdapter() {
          override fun componentResized(event: ComponentEvent) = updateCompactMode()
        })
    updateCompactMode()
  }

  fun synchronizeStateSlot(stateSlot: Int) {
    require(stateSlot in 0..9)
    slot.isEnabled = actions.stateSlotActions[stateSlot].isEnabled
    if (slot.selectedIndex == stateSlot) return
    synchronizingSlot = true
    try {
      slot.selectedIndex = stateSlot
    } finally {
      synchronizingSlot = false
    }
  }

  fun setNetplaySummary(summary: String) {
    netplay.text = summary
    netplay.toolTipText = "Open Netplay — $summary"
    netplay.accessibleContext.accessibleName = summary
  }

  private fun updateCompactMode() {
    val compact = width in 1 until COMPACT_WIDTH
    secondary.isVisible = !compact
    overflow.isVisible = compact
  }

  private fun gameButton(action: Action): JButton =
      JButton(action).apply { isFocusable = false }

  private fun gameToggleButton(action: Action): JToggleButton =
      JToggleButton(action).apply { isFocusable = false }

  private companion object {
    const val COMPACT_WIDTH = 650
    const val ActionEventId = 1001
  }
}

internal class DesktopTaskBanner(
    onCancel: () -> Unit,
) : JPanel(BorderLayout(12, 0)) {
  private val message = JLabel()
  private val cancel = JButton("Cancel")

  init {
    border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    getAccessibleContext().accessibleName = "Current task"
    message.accessibleContext.accessibleName = "Current task status"
    cancel.addActionListener { onCancel() }
    add(message, BorderLayout.CENTER)
    add(cancel, BorderLayout.LINE_END)
    isVisible = false
  }

  fun render(task: DesktopSessionTask?) {
    isVisible = task != null
    message.text = task?.message.orEmpty()
    cancel.isVisible = task?.cancellable == true
    getAccessibleContext().accessibleDescription = task?.message
  }

  fun applyTheme(tokens: DesktopThemeTokens) {
    background = tokens.elevatedSurface
    message.foreground = tokens.primaryText
    border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, tokens.border),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
  }
}
