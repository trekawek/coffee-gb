package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.font.TextAttribute
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

internal data class DesktopShortcutGuideRow(
    val action: String,
    val shortcut: String,
    val note: String = "",
)

internal data class DesktopShortcutGuideGroup(
    val title: String,
    val description: String,
    val rows: List<DesktopShortcutGuideRow>,
)

/** Builds the visible reference from the same resolved shortcut registry used by Swing actions. */
internal fun desktopShortcutGuide(
    actions: DesktopActionRegistry,
    menuShortcutMask: Int = debuggerMenuShortcutMask(),
): List<DesktopShortcutGuideGroup> {
  fun mainRow(label: String, command: DesktopCommand): DesktopShortcutGuideRow {
    val resolved = actions.shortcut(command)
    val configured = resolved?.keyStroke ?: resolved?.proposedKeyStroke
    return DesktopShortcutGuideRow(
        action = label,
        shortcut =
            when {
              configured == null -> "Not assigned"
              resolved?.keyStroke == null -> "${desktopKeyStrokeText(configured)} (inactive)"
              else -> desktopKeyStrokeText(configured)
            },
        note = resolved?.inactiveReason.orEmpty(),
    )
  }

  val stateSlotShortcut =
      actions.stateSlotShortcuts().firstOrNull()?.let { stroke ->
        val modifierText = desktopModifierText(stroke.modifiers)
        listOf(modifierText, "0–9").filter(String::isNotBlank).joinToString("+")
      } ?: "Not assigned"

  return listOf(
      DesktopShortcutGuideGroup(
          title = "Main window",
          description = "Application commands while the emulator window is active.",
          rows =
              listOf(
                  mainRow("Open ROM", DesktopCommand.OPEN_ROM),
                  mainRow("Preferences", DesktopCommand.PREFERENCES),
                  mainRow("Quit Coffee GB", DesktopCommand.QUIT),
                  mainRow("Pause / Resume", DesktopCommand.PAUSE),
                  mainRow("Reset", DesktopCommand.RESET),
                  mainRow("Save current state slot", DesktopCommand.SAVE_STATE),
                  mainRow("Load current state slot", DesktopCommand.LOAD_STATE),
                  DesktopShortcutGuideRow("Select state slot", stateSlotShortcut),
                  mainRow("Full Screen", DesktopCommand.FULLSCREEN),
                  mainRow("Screenshot", DesktopCommand.SCREENSHOT),
              ),
      ),
      DesktopShortcutGuideGroup(
          title = "Gameplay",
          description = "Unmodified game input yields to text fields, menus, and dialogs.",
          rows =
              listOf(
                  DesktopShortcutGuideRow(
                      "Game Boy buttons",
                      "Configured per player",
                      "Preferences > Controls",
                  ),
                  DesktopShortcutGuideRow("Rewind", "Backspace", "When rewind is enabled"),
                  DesktopShortcutGuideRow(
                      "Cartridge tilt",
                      "I / J / K / L",
                      "Mouse-over-display and supported gamepads also work",
                  ),
              ),
      ),
      DesktopShortcutGuideGroup(
          title = "Debugger window",
          description = "These commands apply only to the focused debugger tool.",
          rows =
              listOf(
                  DesktopShortcutGuideRow("Pause / Resume", "F6"),
                  DesktopShortcutGuideRow("Step instruction / frame", "F7 / Shift+F7"),
                  DesktopShortcutGuideRow("Back instruction / frame", "F8 / Shift+F8"),
                  DesktopShortcutGuideRow("Toggle breakpoint at current PC", "F9"),
                  DesktopShortcutGuideRow(
                      "Copy current tool",
                      desktopKeyStrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuShortcutMask)),
                  ),
                  DesktopShortcutGuideRow(
                      "Increase / decrease font",
                      desktopKeyStrokeText(
                              KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuShortcutMask)) +
                          " / " +
                          desktopKeyStrokeText(
                              KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuShortcutMask)),
                  ),
                  DesktopShortcutGuideRow(
                      "Reset font",
                      desktopKeyStrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_0, menuShortcutMask)),
                  ),
              ),
      ),
  )
}

internal fun desktopKeyStrokeText(keyStroke: KeyStroke): String =
    listOf(desktopModifierText(keyStroke.modifiers), KeyEvent.getKeyText(keyStroke.keyCode))
        .filter(String::isNotBlank)
        .joinToString("+")

private fun desktopModifierText(modifiers: Int): String =
    buildList {
          if (modifiers and InputEvent.META_DOWN_MASK != 0) add("Command")
          if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
          if (modifiers and InputEvent.ALT_DOWN_MASK != 0) add("Alt")
          if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
        }
        .joinToString("+")

internal class DesktopShortcutGuidePanel(
    groups: List<DesktopShortcutGuideGroup>,
) : JPanel(BorderLayout()), DesktopThemeRefreshHook {
  private data class TableSurface(
      val table: JTable,
      val description: JTextArea,
      val scroll: JScrollPane,
      val panel: JPanel,
  )

  private val tabs = JTabbedPane()
  private val surfaces =
      groups.map { group ->
        val model =
            object : DefaultTableModel(
                group.rows.map { arrayOf(it.action, it.shortcut, it.note) }.toTypedArray(),
                arrayOf("Action", "Shortcut", "Notes"),
            ) {
              override fun isCellEditable(row: Int, column: Int): Boolean = false
            }
        val table =
            JTable(model).apply {
              setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
              autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
              rowHeight = rowHeight.coerceAtLeast(24)
              getAccessibleContext().accessibleName = "${group.title} shortcuts"
              getAccessibleContext().accessibleDescription = group.description
            }
        table.columnModel.getColumn(0).preferredWidth = 220
        table.columnModel.getColumn(1).preferredWidth = 175
        table.columnModel.getColumn(2).preferredWidth = 300
        val description =
            readOnlyHelpText(group.description, "${group.title} shortcut context").apply {
              border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            }
        val scroll =
            JScrollPane(table).apply {
              preferredSize = Dimension(720, 330)
              getAccessibleContext().accessibleName = "${group.title} shortcut table"
            }
        val panel =
            JPanel(BorderLayout(0, 8)).apply {
              border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
              add(description, BorderLayout.NORTH)
              add(scroll, BorderLayout.CENTER)
            }
        tabs.addTab(group.title, panel)
        TableSurface(table, description, scroll, panel)
      }

  init {
    check(SwingUtilities.isEventDispatchThread()) { "Shortcut help must be created on the EDT" }
    getAccessibleContext().accessibleName = "Keyboard shortcut contexts"
    getAccessibleContext().accessibleDescription =
        "Main window, gameplay, and debugger window shortcut reference"
    tabs.accessibleContext.accessibleName = "Shortcut context"
    add(tabs, BorderLayout.CENTER)
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    background = tokens.surface
    tabs.background = tokens.surface
    tabs.foreground = tokens.primaryText
    surfaces.forEach { surface ->
      surface.panel.background = tokens.surface
      surface.description.background = tokens.surface
      surface.description.foreground = tokens.secondaryText
      surface.table.background = tokens.elevatedSurface
      surface.table.foreground = tokens.primaryText
      surface.table.gridColor = tokens.border
      surface.table.selectionBackground = tokens.accent
      surface.table.selectionForeground = tokens.onAccent
      surface.table.tableHeader.background = tokens.elevatedSurface
      surface.table.tableHeader.foreground = tokens.primaryText
      surface.scroll.viewport.background = tokens.elevatedSurface
      surface.scroll.border = BorderFactory.createLineBorder(tokens.border)
    }
  }
}

internal class DesktopAboutPanel(
    internal val version: String,
    private val clipboardWriter: DesktopClipboardWriter = systemHelpClipboardWriter(),
    private val uriOpener: DesktopUriOpener = systemHelpUriOpener(),
) : JPanel(), DesktopThemeRefreshHook {
  internal val versionInformation =
      "Coffee GB $version\nLicense: MIT\nSource: $COFFEE_GB_SOURCE_URL"
  internal val copyButton =
      JButton("Copy version info").apply {
        mnemonic = KeyEvent.VK_C
        putClientProperty("html.disable", true)
        getAccessibleContext().accessibleDescription =
            "Copy the Coffee GB version, license, and source repository"
      }
  internal val copyStatus =
      JLabel(" ").apply {
        putClientProperty("html.disable", true)
        getAccessibleContext().accessibleName = "Copy version information status"
      }
  internal val sourceLink =
      JButton(COFFEE_GB_SOURCE_URL).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        border = BorderFactory.createEmptyBorder()
        isContentAreaFilled = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
        putClientProperty("html.disable", true)
        toolTipText = "Open Coffee GB source repository"
        getAccessibleContext().accessibleName = "Coffee GB source repository"
        getAccessibleContext().accessibleDescription = "Open the Coffee GB source repository"
      }

  private val productName =
      JLabel("Coffee GB").apply {
        font = font.deriveFont(Font.BOLD, font.size2D * 1.35f)
        alignmentX = Component.LEFT_ALIGNMENT
        putClientProperty("html.disable", true)
      }
  private val productDescription =
      readOnlyHelpText(
          "Pocket Brew desktop emulator for Game Boy and Game Boy Color.",
          "Coffee GB description",
      )
  private val versionLabel =
      JLabel("Version: $version").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        putClientProperty("html.disable", true)
      }
  private val licenseLabel =
      JLabel("License: MIT").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        putClientProperty("html.disable", true)
      }
  private val sourceLabel = JLabel("Source:").apply {
    alignmentX = Component.LEFT_ALIGNMENT
    putClientProperty("html.disable", true)
    labelFor = sourceLink
  }
  private val copyRow =
      JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(copyButton)
        add(Box.createHorizontalStrut(12))
        add(copyStatus)
      }
  private var actionFailed = false

  init {
    check(SwingUtilities.isEventDispatchThread()) { "About content must be created on the EDT" }
    getAccessibleContext().accessibleName = "About Coffee GB"
    getAccessibleContext().accessibleDescription =
        "Coffee GB version, license, and source repository"
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    alignmentX = Component.LEFT_ALIGNMENT
    border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
    add(productName)
    add(Box.createVerticalStrut(6))
    add(productDescription)
    add(Box.createVerticalStrut(14))
    add(versionLabel)
    add(Box.createVerticalStrut(5))
    add(licenseLabel)
    add(Box.createVerticalStrut(14))
    add(sourceLabel)
    add(Box.createVerticalStrut(4))
    add(sourceLink)
    add(Box.createVerticalStrut(14))
    add(copyRow)
    copyButton.addActionListener {
      runCatching { clipboardWriter.copy(versionInformation) }
          .onSuccess { showCopyStatus("Version info copied.", failure = false) }
          .onFailure { showCopyStatus("Could not copy version info.", failure = true) }
    }
    sourceLink.addActionListener {
      runCatching { uriOpener.open(COFFEE_GB_SOURCE_URI) }
          .onFailure { showCopyStatus("Could not open the source link.", failure = true) }
    }
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    background = tokens.surface
    copyRow.background = tokens.surface
    productName.foreground = tokens.primaryText
    productDescription.background = tokens.surface
    productDescription.foreground = tokens.secondaryText
    versionLabel.foreground = tokens.primaryText
    licenseLabel.foreground = tokens.primaryText
    sourceLabel.foreground = tokens.primaryText
    sourceLink.foreground = tokens.accent
    copyStatus.foreground = if (actionFailed) tokens.danger else tokens.secondaryText
  }

  private fun showCopyStatus(message: String, failure: Boolean) {
    actionFailed = failure
    copyStatus.text = message
    copyStatus.accessibleContext.accessibleDescription = message
    repaint()
  }
}

internal class DesktopHelpDialogs(
    private val dialogFactory: DesktopDialogFactory,
) {
  fun showShortcuts(owner: java.awt.Window, actions: DesktopActionRegistry) {
    val content = DesktopShortcutGuidePanel(desktopShortcutGuide(actions))
    dialogFactory.showInformation(
        owner,
        DesktopInformationSpec(
            title = "Keyboard Shortcuts",
            heading = "Keyboard shortcuts",
            description =
                "Shortcuts are scoped to the focused window. Gameplay assignments take precedence over unmodified main-window shortcuts.",
            contentAccessibleName = "Keyboard shortcut reference",
            buttons = closeInformationButtons(),
        ),
        content,
    )
  }

  fun showAbout(owner: java.awt.Window, version: String) {
    dialogFactory.showContent(
        owner,
        DesktopContentSpec(
            title = "About Coffee GB",
            accessibleDescription = "Coffee GB version, license, and source repository.",
            contentAccessibleName = "Coffee GB application information",
            buttons = closeInformationButtons(),
        ),
        DesktopAboutPanel(version),
    )
  }

  private fun closeInformationButtons(): DesktopDialogButtons<Unit> =
      DesktopDialogButtons(
          cancel =
              DesktopDialogAction(
                  label = "Close",
                  result = Unit,
                  mnemonic = KeyEvent.VK_C,
                  accessibleDescription = "Close this information window",
              ),
          defaultButton = DesktopDialogDefaultButton.CANCEL,
      )
}

private fun readOnlyHelpText(value: String, accessibleName: String): JTextArea =
    JTextArea(value).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      alignmentX = Component.LEFT_ALIGNMENT
      putClientProperty("html.disable", true)
      getAccessibleContext().accessibleName = accessibleName
      getAccessibleContext().accessibleDescription = value
    }

private fun systemHelpClipboardWriter(): DesktopClipboardWriter =
    DesktopClipboardWriter { text ->
      val selection = StringSelection(text)
      Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }

internal fun interface DesktopUriOpener {
  fun open(uri: URI)
}

private fun systemHelpUriOpener(): DesktopUriOpener =
    DesktopUriOpener { uri ->
      check(java.awt.Desktop.isDesktopSupported()) { "Opening links is not supported on this desktop" }
      val desktop = java.awt.Desktop.getDesktop()
      check(desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
        "Opening links is not supported on this desktop"
      }
      desktop.browse(uri)
    }

private const val COFFEE_GB_SOURCE_URL = "https://github.com/trekawek/coffee-gb"
private val COFFEE_GB_SOURCE_URI = URI.create(COFFEE_GB_SOURCE_URL)
