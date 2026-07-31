package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Image
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
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
import javax.swing.UIManager

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
    val task: DesktopSessionTask? = null,
    val commands: DesktopCommandPresentation = DesktopCommandPresentation(),
    val netplaySummary: String = "Netplay: Off",
    val persistentStatus: String = "Ready",
    /** Durable recovery notice; routine lifecycle status changes must not erase it. */
    val notice: DesktopNotice? = null,
    /** Compatibility path for direct presentation callers; coordinators use [notice]. */
    val statusRecoveryCommand: DesktopCommand? = null,
) {
  init {
    require(gameTitle == null || gameTitle.isNotBlank())
    require(netplaySummary.isNotBlank())
    require(persistentStatus.isNotBlank())
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
    onOpenRecent: (Path) -> Unit,
    onCancelTask: () -> Unit,
    initialTokens: DesktopThemeTokens,
) : JPanel(BorderLayout()), DesktopThemeRefreshHook {
  private val cards = CardLayout()
  private val home = DesktopHomePanel(actions[DesktopCommand.OPEN_ROM], onOpenRecent)
  private val commandBar = DesktopCommandBar(actions)
  private val taskBanner = DesktopTaskBanner(onCancelTask)
  private val statusBar = DesktopStatusBar(actions)
  private val game = JPanel(BorderLayout())
  private val footer = JPanel(BorderLayout())
  private var presentation = DesktopPresentation()
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
    actions.update(next.commands)
    val playing = next.gameTitle != null
    cards.show(cardHost, if (playing) GAME_CARD else HOME_CARD)
    updateCommandBarVisibility(allowExactOneReveal = false)
    commandBar.setNetplaySummary(next.netplaySummary)
    commandBar.synchronizeStateSlot(next.commands.stateSlot)
    taskBanner.render(next.task)
    statusBar.render(next)
    getAccessibleContext().accessibleDescription = next.visibleStatus
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
    home.updateRecentGames(games.take(MAXIMUM_HOME_RECENTS))
  }

  fun current(): DesktopPresentation = presentation

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
    const val MAXIMUM_HOME_RECENTS = 5
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
    private val onOpenRecent: (Path) -> Unit,
) : JPanel(BorderLayout()) {
  private val content = JPanel()
  private val brand =
      JLabel(CoffeeGbIcon.swingIcon(88), SwingConstants.CENTER).apply {
        getAccessibleContext().accessibleName = "Pocket Brew Coffee GB mark"
      }
  private val title = JLabel("Coffee GB", SwingConstants.CENTER)
  private val recentHeading = JLabel("Recent")
  private val recentList = JPanel()
  private var tokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
  private var recentGames: List<DesktopRecentGame> = emptyList()

  init {
    preferredSize = Dimension(640, 600)
    minimumSize = Dimension(320, 288)
    getAccessibleContext().accessibleName = "Coffee GB home"

    content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
    content.border = BorderFactory.createEmptyBorder(42, 48, 36, 48)

    brand.alignmentX = Component.CENTER_ALIGNMENT
    title.alignmentX = Component.CENTER_ALIGNMENT
    title.font = title.font.deriveFont(Font.BOLD, title.font.size2D * 2.0f)
    val helper = JLabel("Play a Game Boy ROM or supported archive", SwingConstants.CENTER)
    helper.alignmentX = Component.CENTER_ALIGNMENT
    val open = JButton(openAction)
    open.alignmentX = Component.CENTER_ALIGNMENT

    content.add(brand)
    content.add(Box.createVerticalStrut(6))
    content.add(title)
    content.add(Box.createVerticalStrut(8))
    content.add(helper)
    content.add(Box.createVerticalStrut(20))
    content.add(open)
    content.add(Box.createVerticalStrut(8))
    val dropHint = JLabel("You can also drop a ROM or archive anywhere in this window")
    dropHint.alignmentX = Component.CENTER_ALIGNMENT
    content.add(dropHint)
    content.add(Box.createVerticalStrut(28))

    recentHeading.font = recentHeading.font.deriveFont(Font.BOLD)
    recentHeading.alignmentX = Component.LEFT_ALIGNMENT
    recentList.layout = GridLayout(0, 2, 12, 12)
    recentList.alignmentX = Component.LEFT_ALIGNMENT
    content.add(recentHeading)
    content.add(Box.createVerticalStrut(8))
    content.add(recentList)

    add(content, BorderLayout.CENTER)
    updateRecentGames(emptyList())
  }

  fun updateRecentGames(games: List<DesktopRecentGame>) {
    recentGames = games.toList()
    recentList.removeAll()
    recentGames.forEach { game -> recentList.add(createRecentCard(game)) }
    recentHeading.isVisible = recentGames.isNotEmpty()
    recentList.isVisible = recentGames.isNotEmpty()
    revalidate()
    repaint()
  }

  fun applyTheme(tokens: DesktopThemeTokens) {
    this.tokens = tokens
    background = tokens.surface
    content.background = tokens.surface
    recentList.background = tokens.surface
    val labelFont = UIManager.getFont("Label.font")
    if (labelFont != null) {
      title.font = labelFont.deriveFont(Font.BOLD, labelFont.size2D * 2.0f)
      recentHeading.font = labelFont.deriveFont(Font.BOLD)
    }
    updateRecentGames(recentGames)
  }

  private fun createRecentCard(game: DesktopRecentGame): JComponent {
    val path = game.path
    val card = JPanel(BorderLayout(10, 0))
    card.alignmentX = Component.LEFT_ALIGNMENT
    card.border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(tokens.border),
            BorderFactory.createEmptyBorder(6, 6, 6, 6),
        )
    card.background = tokens.elevatedSurface
    val name = path.fileName?.toString() ?: path.toString()
    val open = JButton(name)
    open.horizontalAlignment = SwingConstants.LEADING
    open.isContentAreaFilled = false
    open.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    open.toolTipText = path.toString()
    open.accessibleContext.accessibleName = "Open recent ROM $name"
    open.accessibleContext.accessibleDescription = path.toString()
    open.addActionListener { onOpenRecent(path) }

    val preview =
        JLabel(
            game.thumbnail?.let(::thumbnailIcon),
            SwingConstants.CENTER,
        ).apply {
          preferredSize = RECENT_PREVIEW_SIZE
          minimumSize = RECENT_PREVIEW_SIZE
          background = tokens.surface
          isOpaque = true
          if (game.thumbnail == null) {
            text = "No preview"
            foreground = tokens.secondaryText
            accessibleContext.accessibleName = "No saved preview for $name"
          } else {
            accessibleContext.accessibleName = "Saved preview for $name"
          }
        }
    val parent = JLabel(compactParent(path.parent))
    parent.foreground = tokens.secondaryText
    parent.toolTipText = path.parent?.toString()
    val details = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      background = tokens.elevatedSurface
      add(open)
      add(Box.createVerticalStrut(4))
      add(parent)
    }
    card.add(preview, BorderLayout.LINE_START)
    card.add(details, BorderLayout.CENTER)
    return card
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

  private fun compactParent(parent: Path?): String {
    val value = parent?.toString().orEmpty()
    return if (value.length <= 42) value else "…${value.takeLast(41)}"
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
