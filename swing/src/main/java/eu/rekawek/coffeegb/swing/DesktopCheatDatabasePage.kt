package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.genie.CheatDatabase
import eu.rekawek.coffeegb.core.genie.PatchFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The database half of the unified Cheats dialog.
 *
 * Searching, choosing a matching game, choosing one or more supported entries, and adding them all
 * happen on one page. Database strings are always rendered literally.
 */
internal class DesktopCheatDatabasePage(
    suggestedTitle: String,
    private val findGames: (String, Int) -> List<CheatDatabase.CheatList>,
    private val onAddCodes: (List<String>) -> Unit,
    private val isSupportedCode: (String) -> Boolean = { code ->
      runCatching { PatchFactory.createPatches(code) }.isSuccess
    },
) : JPanel(BorderLayout(0, 10)) {
  internal val gameTitleField =
      JTextField(suggestedTitle, 28).apply {
        getAccessibleContext().accessibleName = "Game title"
        getAccessibleContext().accessibleDescription =
            "Search the bundled cheat database by ROM filename or cartridge title"
      }
  internal val gameModel = DefaultListModel<CheatDatabase.CheatList>()
  internal val gameList =
      JList(gameModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
        cellRenderer = LiteralCheatListRenderer()
        getAccessibleContext().accessibleName = "Matching games"
        getAccessibleContext().accessibleDescription = "Games matching the entered title"
      }
  internal val cheatList =
      ToggleSelectionList<CheatDatabase.Cheat>(emptyList()).apply {
        visibleRowCount = 10
        cellRenderer = LiteralCheatRenderer()
        getAccessibleContext().accessibleName = "Available cheats"
        getAccessibleContext().accessibleDescription =
            "Supported cheats for the selected game; select one or more"
      }
  internal val gameStatus = literalStatus("Cheat database search status")
  internal val cheatStatus = literalStatus("Cheat selection status")
  internal val addSelectedButton =
      JButton("Add selected").apply {
        mnemonic = java.awt.event.KeyEvent.VK_A
        putClientProperty(DISABLE_HTML_PROPERTY, true)
        getAccessibleContext().accessibleName = "Add selected cheats"
        getAccessibleContext().accessibleDescription =
            "Add the selected Game Genie or GameShark codes to the running game"
        isEnabled = false
      }

  init {
    check(SwingUtilities.isEventDispatchThread()) {
      "Cheat database pages must be created on the EDT"
    }
    getAccessibleContext().accessibleName = "Cheat database"
    getAccessibleContext().accessibleDescription =
        "Search the bundled database and add supported cheats to the running game"

    val searchRow = JPanel(BorderLayout(8, 0))
    searchRow.add(JLabel("Game title:").apply { labelFor = gameTitleField }, BorderLayout.WEST)
    searchRow.add(gameTitleField, BorderLayout.CENTER)
    add(searchRow, BorderLayout.NORTH)

    val games =
        listColumn("Matching games", gameList, gameStatus).apply {
          minimumSize = Dimension(220, 220)
        }
    val addRow = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply { add(addSelectedButton) }
    val cheats =
        listColumn("Supported cheats", cheatList, cheatStatus, addRow).apply {
          minimumSize = Dimension(300, 220)
        }
    add(
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, games, cheats).apply {
          resizeWeight = 0.38
          isContinuousLayout = true
          border = BorderFactory.createEmptyBorder()
          preferredSize = Dimension(720, 330)
          getAccessibleContext().accessibleName = "Cheat database results"
        },
        BorderLayout.CENTER,
    )

    gameTitleField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = refreshGames()

          override fun removeUpdate(event: DocumentEvent) = refreshGames()

          override fun changedUpdate(event: DocumentEvent) = refreshGames()
        },
    )
    gameList.addListSelectionListener {
      if (!it.valueIsAdjusting) refreshCheats(gameList.selectedValue)
    }
    cheatList.addListSelectionListener {
      if (!it.valueIsAdjusting) updateCheatSelectionStatus()
    }
    addSelectedButton.addActionListener { addSelectedCodes() }
    refreshGames()
  }

  private fun refreshGames() {
    val title = gameTitleField.text.trim()
    gameModel.clear()
    replaceCheats(emptyList())
    cheatStatus.text = "Choose a matching game."
    updateAccessibleStatus(cheatStatus)
    if (title.isEmpty()) {
      gameStatus.text = "Enter a game title to search."
      updateAccessibleStatus(gameStatus)
      return
    }

    val games = runCatching { findGames(title, MAXIMUM_RESULTS).toList() }
    games
        .onSuccess { matches ->
          matches.forEach(gameModel::addElement)
          gameStatus.text =
              when (matches.size) {
                0 -> "No matching games. Try a shorter title."
                1 -> "1 matching game"
                else -> "${matches.size} matching games"
              }
          if (matches.isNotEmpty()) gameList.selectedIndex = 0
        }
        .onFailure { gameStatus.text = "Could not load the cheat database." }
    updateAccessibleStatus(gameStatus)
  }

  private fun refreshCheats(game: CheatDatabase.CheatList?) {
    val supported = game?.cheats()?.filter { isSupportedCode(it.code()) }.orEmpty()
    replaceCheats(supported)
    cheatStatus.text =
        when {
          game == null -> "Choose a matching game."
          supported.isEmpty() ->
              "This entry contains no supported Game Genie or GameShark codes."
          supported.size == 1 -> "1 supported cheat; select it to add."
          else -> "${supported.size} supported cheats; select one or more to add."
        }
    updateAccessibleStatus(cheatStatus)
    if (supported.isNotEmpty()) cheatList.selectedIndex = 0
  }

  private fun replaceCheats(cheats: List<CheatDatabase.Cheat>) {
    val model = cheatList.model as DefaultListModel<CheatDatabase.Cheat>
    model.clear()
    cheats.forEach(model::addElement)
    cheatList.clearSelection()
    addSelectedButton.isEnabled = false
  }

  private fun updateCheatSelectionStatus() {
    val selected = cheatList.selectedValuesList.size
    addSelectedButton.isEnabled = selected > 0
    if (selected > 0) {
      cheatStatus.text = if (selected == 1) "1 cheat selected." else "$selected cheats selected."
      updateAccessibleStatus(cheatStatus)
    }
  }

  private fun addSelectedCodes() {
    val selected = cheatList.selectedValuesList
    if (selected.isEmpty()) return
    runCatching { onAddCodes(selected.map(CheatDatabase.Cheat::code)) }
        .onSuccess {
          cheatStatus.text =
              if (selected.size == 1) "Cheat added." else "${selected.size} cheats added."
        }
        .onFailure { cheatStatus.text = "Could not add the selected cheats." }
    updateAccessibleStatus(cheatStatus)
  }

  private fun listColumn(
      heading: String,
      list: JList<*>,
      status: JTextArea,
      trailing: Component? = null,
  ): JPanel =
      JPanel(BorderLayout(0, 6)).apply {
        border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        add(JLabel(heading).apply { labelFor = list }, BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(8, 0)).apply {
              add(status, BorderLayout.CENTER)
              trailing?.let { add(it, BorderLayout.EAST) }
            },
            BorderLayout.SOUTH,
        )
      }

  private fun updateAccessibleStatus(status: JTextArea) {
    status.accessibleContext.accessibleDescription = status.text
  }

  private class LiteralCheatListRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
          if (it is JLabel) {
            it.text = (value as? CheatDatabase.CheatList)?.name().orEmpty()
            makeLiteral(it)
          }
        }
  }

  private class LiteralCheatRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
          if (it is JLabel) {
            val cheat = value as? CheatDatabase.Cheat
            it.text = cheat?.let(::cheatLabel).orEmpty()
            it.toolTipText = cheat?.let { entry -> "${entry.description()} (${entry.code()})" }
            makeLiteral(it)
          }
        }
  }

  private companion object {
    const val MAXIMUM_RESULTS = 25
    const val CHEAT_CODE_MAX_LENGTH = 36
    const val DISABLE_HTML_PROPERTY = "html.disable"

    fun literalStatus(accessibleName: String): JTextArea =
        JTextArea().apply {
          isEditable = false
          isOpaque = false
          lineWrap = true
          wrapStyleWord = true
          putClientProperty(DISABLE_HTML_PROPERTY, true)
          getAccessibleContext().accessibleName = accessibleName
        }

    fun cheatLabel(cheat: CheatDatabase.Cheat): String {
      val code = cheat.code()
      val visibleCode =
          if (code.length <= CHEAT_CODE_MAX_LENGTH) code
          else "${code.take(CHEAT_CODE_MAX_LENGTH - 1)}…"
      return "${cheat.description()} ($visibleCode)"
    }

    fun makeLiteral(label: JLabel) {
      label.putClientProperty(DISABLE_HTML_PROPERTY, true)
      label.accessibleContext.accessibleName = label.text
      label.accessibleContext.accessibleDescription = label.text
    }
  }
}
