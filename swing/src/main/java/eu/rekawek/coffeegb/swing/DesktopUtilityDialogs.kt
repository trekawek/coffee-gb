package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.genie.PatchFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.DocumentFilter
import javax.swing.text.PlainDocument

internal enum class DesktopUtilityFormResult {
  APPLY,
  CANCEL,
}

internal data class DesktopInlineValidation(
    val valid: Boolean,
    val message: String? = null,
)

/** Validates the Barcode Boy format only; JAN/EAN checksum validation is intentionally absent. */
internal fun validateBarcodeBoyCode(value: String): DesktopInlineValidation =
    when {
      value.isEmpty() -> DesktopInlineValidation(false, "Enter a 13-digit barcode.")
      value.any { it !in '0'..'9' } ->
          DesktopInlineValidation(false, "Use decimal digits only.")
      value.length != BARCODE_BOY_DIGITS ->
          DesktopInlineValidation(
              false,
              "Enter exactly 13 digits (${value.length} of $BARCODE_BOY_DIGITS).",
          )
      else -> DesktopInlineValidation(true)
    }

/**
 * Caps the editable Barcode Boy field without silently converting input. Invalid characters remain
 * visible long enough for the inline validator to explain them.
 */
internal class BarcodeBoyDocumentFilter : DocumentFilter() {
  @Throws(BadLocationException::class)
  override fun insertString(
      filterBypass: FilterBypass,
      offset: Int,
      string: String?,
      attributes: AttributeSet?,
  ) {
    replace(filterBypass, offset, 0, string, attributes)
  }

  @Throws(BadLocationException::class)
  override fun replace(
      filterBypass: FilterBypass,
      offset: Int,
      length: Int,
      text: String?,
      attributes: AttributeSet?,
  ) {
    val replacement = text.orEmpty()
    val nextLength = filterBypass.document.length - length + replacement.length
    if (nextLength <= BARCODE_BOY_DIGITS) {
      filterBypass.replace(offset, length, replacement, attributes)
    }
  }
}

internal class BarcodeBoyForm(
    barcodeBoySelected: Boolean,
    private val onSelectBarcodeBoy: () -> Boolean,
    initialBarcode: String = "",
) : JPanel(GridBagLayout()) {
  internal val barcodeField =
      JTextField(16).apply {
        getAccessibleContext().accessibleName = "Barcode number"
        getAccessibleContext().accessibleDescription =
            "Exactly 13 decimal digits sent to the selected Barcode Boy"
        document =
            PlainDocument().apply { documentFilter = BarcodeBoyDocumentFilter() }
      }
  internal val prerequisiteText =
      literalUtilityText("", "Barcode Boy prerequisite", columns = 42)
  internal val selectDeviceButton =
      literalUtilityButton(
          "Select Barcode Boy",
          "Select Barcode Boy as the current link-port device",
      ).apply { mnemonic = KeyEvent.VK_B }

  private var selected = barcodeBoySelected

  val barcode: String
    get() = barcodeField.text

  val submissionValidation: DesktopInlineValidation
    get() {
      val barcodeValidation = validateBarcodeBoyCode(barcode)
      if (!barcodeValidation.valid) return barcodeValidation
      return if (selected) {
        DesktopInlineValidation(true)
      } else {
        DesktopInlineValidation(false, "Select Barcode Boy before scanning.")
      }
    }

  init {
    check(SwingUtilities.isEventDispatchThread()) { "Barcode Boy forms must be created on the EDT" }
    getAccessibleContext().accessibleName = "Barcode Boy scan fields"
    getAccessibleContext().accessibleDescription =
        "Enter exactly 13 decimal digits and select Barcode Boy before scanning"

    val barcodeLabel = JLabel("Barcode number:").apply { labelFor = barcodeField }
    add(
        barcodeLabel,
        GridBagConstraints().apply {
          gridx = 0
          gridy = 0
          anchor = GridBagConstraints.LINE_START
          insets = Insets(0, 0, 0, 8)
        },
    )
    add(
        barcodeField,
        GridBagConstraints().apply {
          gridx = 1
          gridy = 0
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
        },
    )
    add(
        prerequisiteText,
        GridBagConstraints().apply {
          gridx = 0
          gridy = 1
          gridwidth = 2
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(10, 0, 0, 0)
        },
    )
    add(
        selectDeviceButton,
        GridBagConstraints().apply {
          gridx = 0
          gridy = 2
          gridwidth = 2
          anchor = GridBagConstraints.LINE_START
          insets = Insets(4, 0, 0, 0)
        },
    )

    barcodeField.document.onTextChanged(::publishValidation)
    selectDeviceButton.addActionListener {
      val nowSelected = runCatching(onSelectBarcodeBoy).getOrDefault(false)
      if (nowSelected) setBarcodeBoySelected(true)
    }
    setBarcodeBoySelected(selected)
    barcodeField.text = initialBarcode
  }

  fun setBarcodeBoySelected(isSelected: Boolean) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Barcode Boy prerequisite updates must run on the EDT"
    }
    selected = isSelected
    prerequisiteText.text =
        if (selected) {
          "Barcode Boy is selected and ready to receive a scan."
        } else {
          "Barcode Boy must be selected as the link-port device before scanning."
        }
    prerequisiteText.accessibleContext.accessibleDescription = prerequisiteText.text
    selectDeviceButton.isVisible = !selected
    selectDeviceButton.isEnabled = !selected
    publishValidation()
  }

  internal fun spec(): DesktopFormSpec<DesktopUtilityFormResult> =
      DesktopFormSpec(
          title = "Barcode Boy",
          heading = "Scan a barcode",
          description =
              "Enter the 13 decimal digits printed below the barcode. No checksum is calculated.",
          contentAccessibleName = "Barcode Boy scan fields",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Scan",
                          DesktopUtilityFormResult.APPLY,
                          mnemonic = KeyEvent.VK_S,
                          accessibleDescription = "Send this barcode to Barcode Boy",
                      ),
                  cancel =
                      DesktopDialogAction("Cancel", DesktopUtilityFormResult.CANCEL),
                  defaultButton = DesktopDialogDefaultButton.PRIMARY,
              ),
          initiallyValid = submissionValidation.valid,
          modality = DesktopOwnedDialogModality.DOCUMENT,
      )

  private fun publishValidation() {
    val validation = submissionValidation
    publishFormValidation(validation)
  }
}

internal class BarcodeBoyDialog(
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) {
  /** Returns true only after a syntactically valid barcode has been submitted. */
  fun show(
      owner: Window,
      barcodeBoySelected: Boolean,
      onSelectBarcodeBoy: () -> Boolean,
      onScan: (String) -> Unit,
  ): Boolean {
    check(SwingUtilities.isEventDispatchThread()) { "Barcode Boy dialogs must be shown on the EDT" }
    val form = BarcodeBoyForm(barcodeBoySelected, onSelectBarcodeBoy)
    val result = dialogFactory.showForm(owner, form.spec(), form)
    if (result != DesktopUtilityFormResult.APPLY) return false
    check(form.submissionValidation.valid) { "The dialog submitted an invalid barcode" }
    onScan(form.barcode)
    return true
  }
}

internal class FullChangerForm(
    choices: List<String>,
    currentChoice: String?,
    resetChoice: String = choices.firstOrNull().orEmpty(),
) : JPanel(BorderLayout(0, 10)) {
  private val allChoices = choices.toList()
  private val defaultChoice = resetChoice
  internal val searchField =
      JTextField(24).apply {
        getAccessibleContext().accessibleName = "Search Cosmic Characters"
        getAccessibleContext().accessibleDescription =
            "Filter the supplied Cosmic Character list by name or number"
      }
  internal val currentChoiceText =
      JTextField(currentChoice ?: "No character selected", 32).apply {
        isEditable = false
        putClientProperty(DISABLE_HTML_PROPERTY, true)
        getAccessibleContext().accessibleName = "Current Cosmic Character"
        getAccessibleContext().accessibleDescription = text
      }
  internal val resetButton =
      literalUtilityButton(
          "Reset",
          "Clear the search and select the default Cosmic Character",
      ).apply { mnemonic = KeyEvent.VK_R }
  internal val choiceModel = DefaultListModel<String>()
  internal val choiceList =
      JList(choiceModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        getAccessibleContext().accessibleName = "Cosmic Characters"
        getAccessibleContext().accessibleDescription = "Filtered Full Changer character choices"
        cellRenderer = LiteralStringListCellRenderer()
      }
  internal val resultCount =
      literalUtilityText("", "Full Changer search results", columns = 38)

  val selectedChoice: String?
    get() = choiceList.selectedValue

  init {
    check(SwingUtilities.isEventDispatchThread()) { "Full Changer forms must be created on the EDT" }
    require(allChoices.isNotEmpty()) { "Full Changer needs at least one character" }
    require(allChoices.none(String::isBlank)) { "Full Changer choices cannot be blank" }
    require(allChoices.distinct().size == allChoices.size) {
      "Full Changer choices must be distinct"
    }
    require(defaultChoice in allChoices) { "The reset choice must be in the supplied list" }

    getAccessibleContext().accessibleName = "Full Changer character picker"
    getAccessibleContext().accessibleDescription =
        "Search the supplied list, review the current choice, and apply one character"

    val currentRow = JPanel(BorderLayout(8, 0))
    val currentLabel = JLabel("Current character:").apply { labelFor = currentChoiceText }
    currentRow.add(currentLabel, BorderLayout.WEST)
    currentRow.add(currentChoiceText, BorderLayout.CENTER)

    val searchRow = JPanel(BorderLayout(8, 0))
    val searchLabel = JLabel("Search:").apply { labelFor = searchField }
    searchRow.add(searchLabel, BorderLayout.WEST)
    searchRow.add(searchField, BorderLayout.CENTER)
    searchRow.add(resetButton, BorderLayout.EAST)

    val header = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(currentRow)
      add(Box.createVerticalStrut(8))
      add(searchRow)
      add(Box.createVerticalStrut(4))
      add(resultCount)
    }
    add(header, BorderLayout.NORTH)
    add(
        JScrollPane(choiceList).apply {
          preferredSize = Dimension(500, 280)
          getAccessibleContext().accessibleName = "Cosmic Character results"
        },
        BorderLayout.CENTER,
    )

    searchField.document.onTextChanged { refreshChoices(choiceList.selectedValue) }
    choiceList.addListSelectionListener {
      if (!it.valueIsAdjusting) publishSelectionValidation()
    }
    resetButton.addActionListener {
      searchField.text = ""
      choiceList.setSelectedValue(defaultChoice, true)
      choiceList.requestFocusInWindow()
    }
    refreshChoices(currentChoice?.takeIf { it in allChoices } ?: defaultChoice)
  }

  internal fun spec(): DesktopFormSpec<DesktopUtilityFormResult> =
      DesktopFormSpec(
          title = "Full Changer",
          heading = "Choose a Cosmic Character",
          description =
              "Search the available transformations, then apply one to the running game.",
          contentAccessibleName = "Full Changer character picker",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Apply",
                          DesktopUtilityFormResult.APPLY,
                          mnemonic = KeyEvent.VK_A,
                          accessibleDescription = "Apply the selected Cosmic Character",
                      ),
                  cancel =
                      DesktopDialogAction("Cancel", DesktopUtilityFormResult.CANCEL),
                  defaultButton = DesktopDialogDefaultButton.PRIMARY,
              ),
          initiallyValid = selectedChoice != null,
          modality = DesktopOwnedDialogModality.DOCUMENT,
      )

  private fun refreshChoices(preferredSelection: String?) {
    val query = searchField.text.trim().lowercase(Locale.ROOT)
    val matches =
        if (query.isEmpty()) allChoices
        else allChoices.filter { it.lowercase(Locale.ROOT).contains(query) }
    choiceModel.clear()
    matches.forEach(choiceModel::addElement)
    val selection = preferredSelection?.takeIf { it in matches } ?: matches.firstOrNull()
    choiceList.setSelectedValue(selection, true)
    resultCount.text =
        when (matches.size) {
          0 -> "No characters match this search."
          1 -> "1 character"
          else -> "${matches.size} characters"
        }
    resultCount.accessibleContext.accessibleDescription = resultCount.text
    publishSelectionValidation()
  }

  private fun publishSelectionValidation() {
    publishFormValidation(
        if (selectedChoice == null) {
          DesktopInlineValidation(false, "Choose a Cosmic Character to apply.")
        } else {
          DesktopInlineValidation(true)
        },
    )
  }
}

internal class FullChangerDialog(
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) {
  /** Returns the applied value from the supplied list, or null when cancelled. */
  fun show(
      owner: Window,
      choices: List<String>,
      currentChoice: String?,
      resetChoice: String = choices.firstOrNull().orEmpty(),
      onApply: (String) -> Unit,
  ): String? {
    check(SwingUtilities.isEventDispatchThread()) { "Full Changer dialogs must be shown on the EDT" }
    val form = FullChangerForm(choices, currentChoice, resetChoice)
    val result = dialogFactory.showForm(owner, form.spec(), form)
    if (result != DesktopUtilityFormResult.APPLY) return null
    val selected = checkNotNull(form.selectedChoice) { "The dialog submitted without a selection" }
    onApply(selected)
    return selected
  }
}

internal class ActionReplaySlotForm(
    currentFile: Path?,
    private val browseForFile: () -> Path?,
) : JPanel(BorderLayout(0, 10)) {
  internal val fileText =
      JTextField(36).apply {
        isEditable = false
        putClientProperty(DISABLE_HTML_PROPERTY, true)
        getAccessibleContext().accessibleName = "Action Replay slot cartridge"
      }
  internal val browseButton =
      literalUtilityButton(
          "Browse…",
          "Choose a cartridge file for the Action Replay slot",
      ).apply { mnemonic = KeyEvent.VK_B }
  internal val removeButton =
      literalUtilityButton(
          "Remove",
          "Remove the cartridge attachment from the Action Replay slot",
      ).apply { mnemonic = KeyEvent.VK_R }
  internal val attachmentStatus =
      literalUtilityText("", "Action Replay attachment status", columns = 44)

  var attachment: Path? = currentFile
    private set

  init {
    check(SwingUtilities.isEventDispatchThread()) {
      "Action Replay slot forms must be created on the EDT"
    }
    getAccessibleContext().accessibleName = "Action Replay slot attachment"
    getAccessibleContext().accessibleDescription =
        "Review, browse for, or remove the cartridge attached to the Action Replay slot"

    val fileRow = JPanel(BorderLayout(8, 0))
    val fileLabel = JLabel("Current file:").apply { labelFor = fileText }
    fileRow.add(fileLabel, BorderLayout.WEST)
    fileRow.add(fileText, BorderLayout.CENTER)

    val actions = JPanel(FlowLayout(FlowLayout.LEADING, 8, 0))
    actions.add(browseButton)
    actions.add(removeButton)

    val impact =
        literalUtilityText(
            "The selected game cartridge is inserted the next time an Action Replay cartridge " +
                "is opened. Other cartridges are unaffected.",
            "Action Replay cartridge impact",
            columns = 44,
        )

    val body = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(fileRow)
      add(Box.createVerticalStrut(8))
      add(actions)
      add(Box.createVerticalStrut(10))
      add(impact)
      add(Box.createVerticalStrut(6))
      add(attachmentStatus)
    }
    add(body, BorderLayout.CENTER)

    browseButton.addActionListener {
      runCatching(browseForFile)
          .onSuccess { selected ->
            if (selected != null) {
              attachment = selected
              updateAttachmentPresentation("Cartridge selected. Apply to save this attachment.")
            }
          }
          .onFailure {
            attachmentStatus.text = "Could not choose a cartridge file."
            attachmentStatus.accessibleContext.accessibleDescription = attachmentStatus.text
          }
    }
    removeButton.addActionListener {
      attachment = null
      updateAttachmentPresentation("Attachment removed. Apply to save this change.")
    }
    updateAttachmentPresentation(null)
  }

  internal fun spec(): DesktopFormSpec<DesktopUtilityFormResult> =
      DesktopFormSpec(
          title = "Action Replay Slot",
          heading = "Attach a game cartridge",
          description =
              "Choose the cartridge that an Action Replay will load in its secondary slot.",
          contentAccessibleName = "Action Replay slot attachment",
          buttons =
              DesktopDialogButtons(
                  primary =
                      DesktopDialogAction(
                          "Apply",
                          DesktopUtilityFormResult.APPLY,
                          mnemonic = KeyEvent.VK_A,
                          accessibleDescription = "Save the Action Replay slot attachment",
                      ),
                  cancel =
                      DesktopDialogAction("Cancel", DesktopUtilityFormResult.CANCEL),
                  defaultButton = DesktopDialogDefaultButton.PRIMARY,
              ),
          modality = DesktopOwnedDialogModality.DOCUMENT,
      )

  private fun updateAttachmentPresentation(status: String?) {
    fileText.text = attachment?.toString() ?: "No cartridge attached"
    fileText.caretPosition = 0
    fileText.accessibleContext.accessibleDescription = fileText.text
    removeButton.isEnabled = attachment != null
    attachmentStatus.text = status.orEmpty()
    attachmentStatus.isVisible = !status.isNullOrBlank()
    attachmentStatus.accessibleContext.accessibleDescription =
        status ?: "No unsaved attachment change"
    revalidate()
    repaint()
  }
}

internal class ActionReplaySlotDialog(
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) {
  /** Applies either the selected path or null after an explicit removal; Cancel never calls back. */
  fun show(
      owner: Window,
      currentFile: Path?,
      browseForFile: () -> Path?,
      onApply: (Path?) -> Unit,
  ): Boolean {
    check(SwingUtilities.isEventDispatchThread()) {
      "Action Replay slot dialogs must be shown on the EDT"
    }
    val form = ActionReplaySlotForm(currentFile, browseForFile)
    val result = dialogFactory.showForm(owner, form.spec(), form)
    if (result != DesktopUtilityFormResult.APPLY) return false
    onApply(form.attachment)
    return true
  }
}

internal enum class DesktopCheatsPage {
  DATABASE,
  MANUAL_ENTRY,
}

/** Syntactic validation delegates to the same parser that creates emulator patches. */
internal fun validateManualCheatCode(value: String): DesktopInlineValidation {
  val code = value.trim()
  if (code.isEmpty()) {
    return DesktopInlineValidation(false, "Enter a Game Genie or GameShark code.")
  }
  return if (runCatching { PatchFactory.createPatches(code) }.isSuccess) {
    DesktopInlineValidation(true)
  } else {
    DesktopInlineValidation(false, "Enter a valid Game Genie or GameShark code.")
  }
}

/**
 * One Cheats shell with a caller-supplied database page and the currently supported manual-add
 * operation. It intentionally contains no active-code list, toggle, or removal action.
 */
internal class DesktopCheatsDialogPanel(
    databasePage: JComponent,
    initialPage: DesktopCheatsPage,
    private val validator: (String) -> DesktopInlineValidation = ::validateManualCheatCode,
    private val onManualCode: (String) -> Unit,
    private val onClose: () -> Unit,
    initialTokens: DesktopThemeTokens,
) : JPanel(BorderLayout(0, initialTokens.spacing.section)), DesktopThemeRefreshHook {
  internal val tabs = JTabbedPane()
  internal val manualCodeField =
      JTextField(24).apply {
        getAccessibleContext().accessibleName = "Manual cheat code"
        getAccessibleContext().accessibleDescription = "A Game Genie or GameShark code"
      }
  internal val manualValidation =
      literalUtilityText("", "Manual cheat validation", columns = 42)
  internal val manualStatus =
      literalUtilityText("", "Manual cheat status", columns = 42).apply { isVisible = false }
  internal val addCodeButton =
      literalUtilityButton(
          "Add code",
          "Validate and add this code to the running game",
      ).apply { mnemonic = KeyEvent.VK_A }
  internal val closeButton =
      literalUtilityButton("Close", "Close the Cheats dialog").apply {
        mnemonic = KeyEvent.VK_C
      }

  private val manualPage = JPanel(BorderLayout(0, initialTokens.spacing.related))
  private val buttonBar = JPanel(FlowLayout(FlowLayout.TRAILING, initialTokens.spacing.related, 0))
  private var rootPaneBinding: JRootPane? = null
  private var statusFailure = false
  private var successColor = initialTokens.success
  private var dangerColor = initialTokens.danger

  init {
    check(SwingUtilities.isEventDispatchThread()) { "Cheats panels must be created on the EDT" }
    if (databasePage.accessibleContext.accessibleName.isNullOrBlank()) {
      databasePage.accessibleContext.accessibleName = "Cheat database"
    }
    databasePage.accessibleContext.accessibleDescription =
        databasePage.accessibleContext.accessibleDescription
            ?: "Browse and apply entries from the bundled cheat database"

    getAccessibleContext().accessibleName = "Cheats"
    getAccessibleContext().accessibleDescription =
        "Browse the bundled database or add a validated Game Genie or GameShark code"

    val codeRow = JPanel(BorderLayout(initialTokens.spacing.related, 0))
    val codeLabel = JLabel("Code:").apply { labelFor = manualCodeField }
    codeRow.add(codeLabel, BorderLayout.WEST)
    codeRow.add(manualCodeField, BorderLayout.CENTER)
    codeRow.add(addCodeButton, BorderLayout.EAST)

    val help =
        literalUtilityText(
            "Enter one Game Genie or GameShark code. The code is added to the current game.",
            "Manual cheat instructions",
            columns = 42,
        )
    val manualBody = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(help)
      add(Box.createVerticalStrut(initialTokens.spacing.section))
      add(codeRow)
      add(Box.createVerticalStrut(initialTokens.spacing.related))
      add(manualValidation)
      add(Box.createVerticalStrut(initialTokens.spacing.compact))
      add(manualStatus)
    }
    manualPage.add(manualBody, BorderLayout.NORTH)
    manualPage.border =
        BorderFactory.createEmptyBorder(
            initialTokens.spacing.section,
            initialTokens.spacing.section,
            initialTokens.spacing.section,
            initialTokens.spacing.section,
        )
    manualPage.accessibleContext.accessibleName = "Manual Entry"
    manualPage.accessibleContext.accessibleDescription =
        "Validate and add one Game Genie or GameShark code"

    tabs.addTab("Database", databasePage)
    tabs.addTab("Manual Entry", manualPage)
    tabs.setMnemonicAt(DATABASE_TAB_INDEX, KeyEvent.VK_D)
    tabs.setMnemonicAt(MANUAL_TAB_INDEX, KeyEvent.VK_M)
    tabs.selectedIndex =
        if (initialPage == DesktopCheatsPage.DATABASE) DATABASE_TAB_INDEX else MANUAL_TAB_INDEX
    tabs.accessibleContext.accessibleName = "Cheat source"
    tabs.accessibleContext.accessibleDescription =
        "Database and Manual Entry pages; no active-code management is available"

    buttonBar.add(closeButton)
    add(tabs, BorderLayout.CENTER)
    add(buttonBar, BorderLayout.SOUTH)

    manualCodeField.document.onTextChanged {
      manualStatus.isVisible = false
      updateManualValidation()
    }
    addCodeButton.addActionListener(::submitManualCode)
    closeButton.addActionListener { onClose() }
    tabs.addChangeListener { updateDefaultButton() }
    updateManualValidation()
    desktopThemeChanged(initialTokens)
  }

  fun bindRootPane(rootPane: JRootPane) {
    check(SwingUtilities.isEventDispatchThread()) { "Cheats root panes must be bound on the EDT" }
    rootPaneBinding = rootPane
    configureDesktopDialogRootPane(rootPane, null, onClose)
    updateDefaultButton()
  }

  fun selectPage(page: DesktopCheatsPage) {
    tabs.selectedIndex =
        if (page == DesktopCheatsPage.DATABASE) DATABASE_TAB_INDEX else MANUAL_TAB_INDEX
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    check(SwingUtilities.isEventDispatchThread()) { "Cheats themes must update on the EDT" }
    background = tokens.surface
    border =
        BorderFactory.createEmptyBorder(
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
            tokens.spacing.dialogEdge,
        )
    buttonBar.background = tokens.surface
    manualPage.background = tokens.surface
    successColor = tokens.success
    dangerColor = tokens.danger
    manualValidation.foreground = dangerColor
    manualStatus.foreground = if (statusFailure) dangerColor else successColor
  }

  private fun updateManualValidation() {
    val validation = validator(manualCodeField.text)
    addCodeButton.isEnabled = validation.valid
    manualValidation.text = validation.message.orEmpty()
    manualValidation.isVisible = !validation.message.isNullOrBlank()
    manualValidation.accessibleContext.accessibleDescription =
        validation.message ?: "The manual cheat code is valid"
    updateDefaultButton()
  }

  private fun submitManualCode(@Suppress("UNUSED_PARAMETER") event: java.awt.event.ActionEvent) {
    val code = manualCodeField.text.trim()
    val validation = validator(code)
    if (!validation.valid) {
      updateManualValidation()
      manualCodeField.requestFocusInWindow()
      return
    }
    runCatching { onManualCode(code) }
        .onSuccess {
          statusFailure = false
          manualStatus.text = "Code added."
          manualStatus.accessibleContext.accessibleDescription = manualStatus.text
          manualStatus.foreground = successColor
          manualStatus.isVisible = true
        }
        .onFailure {
          statusFailure = true
          manualStatus.text = "Could not add this code."
          manualStatus.accessibleContext.accessibleDescription = manualStatus.text
          manualStatus.foreground = dangerColor
          manualStatus.isVisible = true
        }
  }

  private fun updateDefaultButton() {
    rootPaneBinding?.defaultButton =
        if (tabs.selectedIndex == MANUAL_TAB_INDEX && addCodeButton.isEnabled) {
          addCodeButton
        } else {
          null
        }
  }

  private companion object {
    const val DATABASE_TAB_INDEX = 0
    const val MANUAL_TAB_INDEX = 1
  }
}

internal class DesktopCheatsDialog(
    private val tokenProvider: () -> DesktopThemeTokens = {
      DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)
    },
) {
  fun show(
      owner: Window,
      databasePage: JComponent,
      initialPage: DesktopCheatsPage = DesktopCheatsPage.DATABASE,
      onManualCode: (String) -> Unit,
  ) {
    check(SwingUtilities.isEventDispatchThread()) { "Cheats dialogs must be shown on the EDT" }
    check(!GraphicsEnvironment.isHeadless()) { "Cheats dialogs are unavailable in headless mode" }
    val previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val dialog = JDialog(owner, "Cheats", Dialog.ModalityType.DOCUMENT_MODAL)
    val panel =
        DesktopCheatsDialogPanel(
            databasePage = databasePage,
            initialPage = initialPage,
            onManualCode = onManualCode,
            onClose = dialog::dispose,
            initialTokens = tokenProvider(),
        )
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.isResizable = true
    dialog.contentPane = panel
    dialog.accessibleContext.accessibleName = "Cheats"
    dialog.accessibleContext.accessibleDescription = panel.accessibleContext.accessibleDescription
    panel.bindRootPane(dialog.rootPane)
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            dialog.dispose()
          }

          override fun windowOpened(event: WindowEvent) {
            if (initialPage == DesktopCheatsPage.MANUAL_ENTRY) {
              panel.manualCodeField.requestFocusInWindow()
            } else {
              databasePage.requestFocusInWindow()
            }
          }
        },
    )
    dialog.pack()
    val minimumSize = Dimension(560, 360)
    dialog.minimumSize = minimumSize
    dialog.size =
        Dimension(
            maxOf(dialog.width, minimumSize.width),
            maxOf(dialog.height, minimumSize.height),
        )
    dialog.setLocationRelativeTo(owner)
    constrainUtilityDialog(dialog)
    dialog.isVisible = true
    if (previousFocus?.isShowing == true) previousFocus.requestFocusInWindow()
  }
}

private fun JComponent.publishFormValidation(validation: DesktopInlineValidation) {
  val shell =
      SwingUtilities.getAncestorOfClass(DesktopFormPanel::class.java, this)
          as? DesktopFormPanel<*>
  shell?.setSubmissionState(validation.valid, validation.message)
}

private fun javax.swing.text.Document.onTextChanged(action: () -> Unit) {
  addDocumentListener(
      object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) = action()

        override fun removeUpdate(event: DocumentEvent) = action()

        override fun changedUpdate(event: DocumentEvent) = action()
      },
  )
}

private class LiteralStringListCellRenderer : DefaultListCellRenderer() {
  override fun getListCellRendererComponent(
      list: JList<*>?,
      value: Any?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean,
  ): Component {
    val component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
    if (component is JLabel) {
      component.text = value?.toString().orEmpty()
      component.putClientProperty(DISABLE_HTML_PROPERTY, true)
      component.accessibleContext.accessibleName = component.text
      component.accessibleContext.accessibleDescription = component.text
    }
    return component
  }
}

private fun literalUtilityText(
    value: String,
    accessibleName: String,
    columns: Int,
): JTextArea =
    JTextArea(value).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      this.columns = columns
      alignmentX = Component.LEFT_ALIGNMENT
      putClientProperty(DISABLE_HTML_PROPERTY, true)
      getAccessibleContext().accessibleName = accessibleName
      getAccessibleContext().accessibleDescription = value
    }

private fun literalUtilityButton(
    text: String,
    accessibleDescription: String,
): JButton =
    JButton(text).apply {
      putClientProperty(DISABLE_HTML_PROPERTY, true)
      getAccessibleContext().accessibleName = text
      getAccessibleContext().accessibleDescription = accessibleDescription
    }

private fun constrainUtilityDialog(dialog: JDialog) {
  val configuration = dialog.graphicsConfiguration ?: dialog.owner?.graphicsConfiguration ?: return
  val screen = configuration.bounds
  val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
  val usable =
      Rectangle(
          screen.x + insets.left,
          screen.y + insets.top,
          (screen.width - insets.left - insets.right).coerceAtLeast(1),
          (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
      )
  dialog.bounds = fitDesktopDialogBounds(dialog.bounds, usable)
}

private const val BARCODE_BOY_DIGITS = 13
private const val DISABLE_HTML_PROPERTY = "html.disable"
