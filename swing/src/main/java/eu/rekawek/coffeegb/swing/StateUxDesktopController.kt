package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.state.StateBrowserCatalog
import eu.rekawek.coffeegb.controller.state.StateBrowserEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogReadyEvent
import eu.rekawek.coffeegb.controller.state.StateDeleteRequestEvent
import eu.rekawek.coffeegb.controller.state.StateExportRequestEvent
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOpenFolderRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseCompletedEvent
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseRequestEvent
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateResumeAvailableEvent
import eu.rekawek.coffeegb.controller.state.StateResumeDecisionEvent
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateScreenshotRequestEvent
import eu.rekawek.coffeegb.controller.state.StateSkipCloseAutosaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateUserError
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToolTip
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.plaf.basic.BasicHTML

/**
 * EDT-only desktop orchestration for state events.
 *
 * The emulation controller remains the sole owner of machine capture/apply. This class only
 * captures an immutable published display frame and sends bounded requests.
 */
internal class StateUxDesktopController(
    private val owner: JFrame,
    rootEventBus: EventBus,
    private val captureDisplayImage: () -> StateImage,
) : AutoCloseable {
  private val eventBus = rootEventBus.fork("desktop-state-ux")
  private val requestIds = AtomicLong()
  private var currentSession: StateUxSessionEvent? = null
  private var browser: StateBrowserDialog? = null
  private var pendingClose: PendingClose? = null
  private var closed = false

  init {
    requireEdt("State desktop controller construction")
    eventBus.register<StateUxSessionEvent> { event ->
      onEdt {
        val current = currentSession
        if (current == null || event.sessionId >= current.sessionId) {
          currentSession = event
          browser?.updateSession(event)
        }
      }
    }
    eventBus.register<StateOperationCompletedEvent> { event ->
      onEdt {
        if (!isCurrent(event.sessionId)) return@onEdt
        browser?.operationCompleted(event)
        if (event.recoveryMessages.isNotEmpty()) {
          showTextDetails(
              owner,
              "State storage recovery",
              event.message,
              event.recoveryMessages.joinToString("\n"),
              JOptionPane.WARNING_MESSAGE,
          )
        }
        when (event.operation) {
          StateOperation.SCREENSHOT ->
              event.path?.let {
                showSelectablePath(
                    owner,
                    "Screenshot saved",
                    event.message,
                    it,
                )
              }
          StateOperation.OPEN_FOLDER ->
              if (event.folderOpened == false) {
                event.path?.let { path ->
                  showSelectablePath(
                      owner,
                      "Open save folder",
                      "Desktop folder integration is unavailable. Copy this path into your file manager:",
                      path,
                  )
                }
              }
          else -> Unit
        }
      }
    }
    eventBus.register<StateOperationFailedEvent> { event ->
      onEdt {
        if (!isCurrent(event.sessionId)) return@onEdt
        browser?.operationFailed(event)
        showStateError(owner, event.error)
      }
    }
    eventBus.register<StateResumeAvailableEvent> { event ->
      onEdt {
        if (!isCurrent(event.sessionId)) return@onEdt
        val saved =
            event.savedAt?.let {
              formatStateTimestamp(it, RESUME_TIME_FORMAT)
            } ?: "an unknown time"
        val duration =
            event.playDurationNanos?.let(::formatDuration)?.let { " after $it of play" }.orEmpty()
        val accept =
            JOptionPane.showConfirmDialog(
                owner,
                "Resume the autosave from $saved$duration?",
                "Resume previous game",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
            ) == JOptionPane.YES_OPTION
        eventBus.post(StateResumeDecisionEvent(event.requestId, accept))
      }
    }
    eventBus.register<StatePrepareCloseCompletedEvent> { event ->
      onEdt { finishClosePreparation(event) }
    }
  }

  fun showBrowser() {
    requireEdt("State browser opening")
    if (closed) return
    val existing = browser
    if (existing != null && existing.isDisplayable) {
      existing.toFront()
      existing.requestFocus()
      return
    }
    browser =
        StateBrowserDialog(
                owner,
                eventBus,
                ::nextRequestId,
                captureDisplayImage,
                currentSession,
            )
            .also {
              it.onClosed = { browser = null }
              it.show()
            }
  }

  fun takeScreenshot() {
    requireEdt("Screenshot request")
    if (!requireAvailableSession()) return
    val image =
        try {
          captureDisplayImage()
        } catch (failure: RuntimeException) {
          showStateError(
              owner,
              StateUserError(
                  "The displayed frame could not be captured.",
                  failure.message ?: failure.javaClass.name,
                  "Keep the game open and retry after the next frame.",
              ),
          )
          return
        }
    eventBus.post(StateScreenshotRequestEvent(nextRequestId(), image))
  }

  fun openSaveFolder() {
    requireEdt("Open-save-folder request")
    if (!requireAvailableSession()) return
    eventBus.post(StateOpenFolderRequestEvent(nextRequestId()))
  }

  fun prepareClose(onPrepared: () -> Unit) {
    requireEdt("Close preparation")
    if (closed || currentSession?.available != true) {
      onPrepared()
      return
    }
    if (pendingClose != null) return
    val requestId = nextRequestId()
    pendingClose = PendingClose(requestId, onPrepared)
    eventBus.post(StatePrepareCloseRequestEvent(requestId))
  }

  private fun finishClosePreparation(event: StatePrepareCloseCompletedEvent) {
    requireEdt("Close preparation completion")
    val pending = pendingClose ?: return
    if (pending.requestId != event.requestId || !isCurrent(event.sessionId)) return
    pendingClose = null
    val error = event.error
    if (error == null) {
      pending.onPrepared()
      return
    }
    val options = arrayOf("Retry autosave", "Close without autosave", "Cancel")
    val choice =
        JOptionPane.showOptionDialog(
            owner,
            stateErrorPanel(error),
            "Autosave failed",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options[0],
        )
    when (choice) {
      0 -> prepareClose(pending.onPrepared)
      1 -> {
        val requestId = nextRequestId()
        pendingClose = PendingClose(requestId, pending.onPrepared)
        eventBus.post(StateSkipCloseAutosaveRequestEvent(requestId, event.sessionId))
      }
    }
  }

  private fun requireAvailableSession(): Boolean {
    if (currentSession?.available == true) return true
    currentSession?.unavailableReason?.let {
      showStateError(owner, it)
      return false
    }
    JOptionPane.showMessageDialog(
        owner,
        "State management requires a running local game.",
        "State management unavailable",
        JOptionPane.INFORMATION_MESSAGE,
    )
    return false
  }

  private fun isCurrent(sessionId: Long): Boolean =
      currentSession?.sessionId == sessionId

  private fun nextRequestId(): Long = requestIds.incrementAndGet()

  override fun close() {
    requireEdt("State desktop controller disposal")
    if (closed) return
    closed = true
    pendingClose = null
    browser?.dispose()
    browser = null
    eventBus.close()
  }

  private data class PendingClose(
      val requestId: Long,
      val onPrepared: () -> Unit,
  )

  private companion object {
    val RESUME_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
  }
}

internal class StateBrowserDialog(
    owner: JFrame,
    rootEventBus: EventBus,
    private val nextRequestId: () -> Long,
    private val captureDisplayImage: () -> StateImage,
    initialSession: StateUxSessionEvent?,
) {
  private val dialog =
      JDialog(owner, "Manage States", Dialog.ModalityType.MODELESS)
  private val eventBus = rootEventBus.fork("state-browser-${System.identityHashCode(this)}")
  private val model = StateBrowserTableModel()
  private val table = StateBrowserTable(model)
  private val preview = JLabel("No preview", SwingConstants.CENTER)
  private val detail = JTextArea(4, 48)
  private val status = literalSwingLabel(" ")
  private val save = JButton("Save")
  private val load = JButton("Load")
  private val named = JButton("New Named…")
  private val delete = JButton("Delete")
  private val export = JButton("Export…")
  private val refresh = JButton("Refresh")
  private val close = JButton("Close")
  private var session = initialSession
  private var latestCatalogRequest = 0L
  private val pendingOperations = mutableSetOf<Long>()
  private val disposed = AtomicBoolean()

  var onClosed: () -> Unit = {}

  val isDisplayable: Boolean
    get() = dialog.isDisplayable

  init {
    requireEdt("State browser construction")
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = dispose()
        })
    dialog.minimumSize = Dimension(760, 460)
    dialog.accessibleContext.accessibleName = "Manage save states"

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    table.autoCreateRowSorter = false
    table.fillsViewportHeight = true
    table.accessibleContext.accessibleName = "Save states"
    table.selectionModel.addListSelectionListener { updateSelection() }
    table.columnModel.getColumn(0).preferredWidth = 150
    table.columnModel.getColumn(1).preferredWidth = 135
    table.columnModel.getColumn(2).preferredWidth = 80
    table.columnModel.getColumn(3).preferredWidth = 110
    table.columnModel.getColumn(4).preferredWidth = 100
    table.columnModel.getColumn(5).preferredWidth = 70
    table.columnModel.getColumn(6).preferredWidth = 105

    preview.preferredSize = Dimension(184, 168)
    preview.border = BorderFactory.createTitledBorder("Preview")
    preview.accessibleContext.accessibleName = "Selected state preview"
    detail.isEditable = false
    detail.lineWrap = true
    detail.wrapStyleWord = true
    detail.background = Color(UI_BACKGROUND)
    detail.font = detail.font.deriveFont(Font.PLAIN)
    detail.accessibleContext.accessibleName = "Selected state details"

    val right = JPanel(BorderLayout(6, 6))
    right.add(preview, BorderLayout.NORTH)
    right.add(JScrollPane(detail), BorderLayout.CENTER)
    val split =
        JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JScrollPane(table),
            right,
        )
    split.resizeWeight = 0.78
    split.isContinuousLayout = true

    configureButton(save, KeyEvent.VK_S) { saveSelected() }
    configureButton(load, KeyEvent.VK_L) { loadSelected() }
    configureButton(named, KeyEvent.VK_N) { newNamed() }
    configureButton(delete, KeyEvent.VK_D) { deleteSelected() }
    configureButton(export, KeyEvent.VK_E) { exportSelected() }
    configureButton(refresh, KeyEvent.VK_R) { requestCatalog() }
    configureButton(close, KeyEvent.VK_C) { dispose() }
    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING))
    listOf(save, load, named, delete, export, refresh, close).forEach(buttons::add)

    status.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
    status.accessibleContext.accessibleName = "State browser status"
    val bottom = JPanel(BorderLayout())
    bottom.add(status, BorderLayout.CENTER)
    bottom.add(buttons, BorderLayout.SOUTH)

    dialog.contentPane.layout = BorderLayout(8, 8)
    dialog.rootPane.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    dialog.contentPane.add(split, BorderLayout.CENTER)
    dialog.contentPane.add(bottom, BorderLayout.SOUTH)
    configureKeys()

    eventBus.register<StateCatalogReadyEvent> { event ->
      onEdt {
        if (event.requestId != latestCatalogRequest ||
            event.sessionId != session?.sessionId ||
            disposed.get()) {
          return@onEdt
        }
        applyCatalog(event.catalog)
      }
    }
    updateAvailability()
    dialog.pack()
    dialog.setLocationRelativeTo(owner)
  }

  fun show() {
    requireEdt("State browser display")
    dialog.isVisible = true
    requestCatalog()
  }

  fun requestFocus() = dialog.requestFocus()

  fun toFront() = dialog.toFront()

  fun updateSession(event: StateUxSessionEvent) {
    requireEdt("State browser session update")
    val previous = session
    if (previous != null && event.sessionId < previous.sessionId) return
    session = event
    pendingOperations.clear()
    latestCatalogRequest = 0
    model.entries = emptyList()
    updateAvailability()
    if (event.available) requestCatalog()
  }

  fun operationCompleted(event: StateOperationCompletedEvent) {
    requireEdt("State browser completion")
    if (!pendingOperations.remove(event.requestId)) return
    status.text = event.message
    if (event.operation == StateOperation.SAVE || event.operation == StateOperation.DELETE) {
      requestCatalog()
    } else {
      updateSelection()
    }
  }

  fun operationFailed(event: StateOperationFailedEvent) {
    requireEdt("State browser failure")
    if (!pendingOperations.remove(event.requestId)) return
    status.text = event.error.summary
    updateSelection()
  }

  fun dispose() {
    requireEdt("State browser disposal")
    if (!disposed.compareAndSet(false, true)) return
    pendingOperations.clear()
    eventBus.close()
    dialog.dispose()
    onClosed()
  }

  private fun requestCatalog() {
    requireEdt("State catalog request")
    if (session?.available != true || disposed.get()) {
      updateAvailability()
      return
    }
    latestCatalogRequest = nextRequestId()
    status.text = "Refreshing states…"
    eventBus.post(eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent(latestCatalogRequest))
  }

  private fun applyCatalog(catalog: StateBrowserCatalog) {
    requireEdt("State catalog application")
    model.entries = catalog.entries
    if (model.rowCount > 0) {
      table.setRowSelectionInterval(0, 0)
    }
    status.text =
        buildList {
              add("${catalog.entries.size} state entries.")
              if (catalog.namedStatesTruncated) add("Named-state list is truncated.")
              catalog.namedStatesError?.let { add(it) }
              addAll(catalog.recoveryMessages)
            }
            .joinToString(" ")
    updateSelection()
  }

  private fun saveSelected() {
    requireEdt("State save request")
    val selected = selectedEntry() ?: return
    if (selected.ref == StateRef.Autosave) return
    val label =
        if (selected.ref is StateRef.Named) {
          selected.catalogEntry?.metadata?.label ?: "Named state"
        } else {
          "Slot ${(selected.ref as StateRef.Slot).index}"
        }
    val requestId = nextRequestId()
    val thumbnail = captureThumbnailOrReport() ?: return
    pendingOperations += requestId
    status.text = "Saving $label…"
    eventBus.post(
        StateSaveRequestEvent(
            requestId,
            selected.ref,
            label,
            thumbnail,
        ))
    updateSelection()
  }

  private fun loadSelected() {
    requireEdt("State load request")
    val selected = selectedEntry() ?: return
    if (!selected.canLoad) {
      status.text = selected.disabledReason ?: "This state cannot be loaded."
      return
    }
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Loading ${model.name(selected)}…"
    eventBus.post(StateLoadRequestEvent(requestId, selected.key))
    updateSelection()
  }

  private fun newNamed() {
    requireEdt("Named state request")
    if (session?.available != true) return
    val label =
        JOptionPane.showInputDialog(
            dialog,
            "Name this state (up to 120 characters):",
            "New named state",
            JOptionPane.PLAIN_MESSAGE,
        )?.trim() ?: return
    if (label.isEmpty()) {
      status.text = "Enter a non-empty state name."
      return
    }
    if (label.codePointCount(0, label.length) > 120 || label.any(Character::isISOControl)) {
      status.text = "State names may contain up to 120 printable characters."
      return
    }
    val requestId = nextRequestId()
    val thumbnail = captureThumbnailOrReport() ?: return
    pendingOperations += requestId
    status.text = "Saving $label…"
    eventBus.post(
        StateSaveRequestEvent(
            requestId,
            StateRef.Named(UUID.randomUUID()),
            label,
            thumbnail,
        ))
    updateSelection()
  }

  private fun deleteSelected() {
    requireEdt("State delete request")
    val selected = selectedEntry() ?: return
    if (selected.isEmpty) return
    val answer =
        JOptionPane.showConfirmDialog(
            dialog,
            "Delete ${model.name(selected)} from source ${selected.key.sourceIndex + 1}?",
            "Delete state",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
    if (answer != JOptionPane.YES_OPTION) return
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Deleting ${model.name(selected)}…"
    eventBus.post(StateDeleteRequestEvent(requestId, selected.key))
    updateSelection()
  }

  private fun exportSelected() {
    requireEdt("State export request")
    val selected = selectedEntry() ?: return
    if (selected.isEmpty) return
    val chooser =
        JFileChooser().apply {
          dialogTitle = "Export ${model.name(selected)}"
          fileFilter = FileNameExtensionFilter("Coffee GB state (*.cgbstate)", "cgbstate")
          selectedFile = java.io.File(model.exportFileName(selected))
        }
    if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return
    var destination = chooser.selectedFile.toPath()
    if (!destination.fileName.toString().endsWith(".cgbstate", ignoreCase = true)) {
      destination =
          destination.resolveSibling(destination.fileName.toString() + ".cgbstate")
    }
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Exporting ${model.name(selected)}…"
    eventBus.post(StateExportRequestEvent(requestId, selected.key, destination))
    updateSelection()
  }

  private fun selectedEntry(): StateBrowserEntry? =
      table.selectedRow.takeIf { it >= 0 }?.let(model::entryAt)

  private fun captureThumbnailOrReport(): StateImage? =
      try {
        captureDisplayImage()
      } catch (failure: RuntimeException) {
        showStateError(
            dialog,
            StateUserError(
                "The state preview could not be captured.",
                failure.message ?: failure.javaClass.name,
                "Keep the game open and retry after the next frame.",
            ),
        )
        null
      }

  private fun updateSelection() {
    requireEdt("State browser selection update")
    val selected = selectedEntry()
    val available = session?.available == true
    val busy = pendingOperations.isNotEmpty()
    save.isEnabled =
        available && !busy && selected != null && selected.ref != StateRef.Autosave
    load.isEnabled = available && !busy && selected?.canLoad == true
    named.isEnabled = available && !busy
    delete.isEnabled = available && !busy && selected?.isEmpty == false
    export.isEnabled = available && !busy && selected?.isEmpty == false
    refresh.isEnabled = available && !busy

    if (selected == null) {
      preview.icon = null
      preview.text = "No preview"
      detail.text = "Select a state to see details."
      return
    }
    selected.thumbnail?.let {
      preview.icon = ImageIcon(toBufferedImage(it))
      preview.text = null
    } ?: run {
      preview.icon = null
      preview.text = "No preview"
    }
    detail.text = model.details(selected)
    detail.caretPosition = 0
  }

  private fun updateAvailability() {
    requireEdt("State browser availability update")
    val available = session?.available == true
    status.text =
        if (available) {
          "Ready."
        } else {
          session?.unavailableReason?.summary
              ?: "Open a ROM in a standalone session to manage states."
        }
    updateSelection()
  }

  private fun configureButton(
      button: JButton,
      mnemonic: Int,
      action: () -> Unit,
  ) {
    button.mnemonic = mnemonic
    button.accessibleContext.accessibleName = button.text.replace("…", "")
    button.addActionListener { action() }
  }

  private fun configureKeys() {
    val input = dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    val actions = dialog.rootPane.actionMap
    fun bind(keyStroke: KeyStroke, name: String, action: () -> Unit) {
      input.put(keyStroke, name)
      actions.put(
          name,
          object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = action()
          },
      )
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-state-browser", ::dispose)
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "load-selected-state", ::loadSelected)
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-selected-state", ::deleteSelected)
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutMask()),
        "new-named-state",
        ::newNamed,
    )
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh-states", ::requestCatalog)
  }

  private companion object {
    const val UI_BACKGROUND = 0xEEEEEE
  }
}

internal class StateBrowserTableModel : AbstractTableModel() {
  var entries: List<StateBrowserEntry> = emptyList()
    set(value) {
      field = value
      fireTableDataChanged()
    }

  override fun getRowCount(): Int = entries.size

  override fun getColumnCount(): Int = COLUMNS.size

  override fun getColumnName(column: Int): String = COLUMNS[column]

  override fun getValueAt(row: Int, column: Int): Any {
    val entry = entries[row]
    val catalog = entry.catalogEntry
    return when (column) {
      0 -> name(entry)
      1 -> catalog?.metadata?.savedAt?.let { formatStateTimestamp(it, TIME_FORMAT) } ?: "—"
      2 -> catalog?.metadata?.playDurationNanos?.let(::formatDuration) ?: "—"
      3 ->
          catalog?.inspection?.identities?.firstOrNull()?.identity?.profile?.canonicalProfileId
              ?: "—"
      4 ->
          catalog?.inspection?.identities?.firstOrNull()?.identity?.primaryRom?.hex()?.take(12)
              ?: "—"
      5 ->
          catalog?.inspection?.let { inspection ->
            buildString {
              append("v")
              append(inspection.formatVersion)
              inspection.diagnostics?.coreVersion?.let {
                append(" / ")
                append(boundedStateUiText(it, MAX_DIAGNOSTIC_CHARS))
              }
            }
          } ?: "—"
      6 -> catalog?.status?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Empty"
      7 -> if (entry.key.sourceIndex == 0) "Active" else "Previous ${entry.key.sourceIndex}"
      else -> error("Invalid state-browser column")
    }
  }

  fun entryAt(row: Int): StateBrowserEntry = entries[row]

  fun name(entry: StateBrowserEntry): String =
      entry.catalogEntry?.metadata?.label
          ?: when (val ref = entry.ref) {
            is StateRef.Slot -> "Slot ${ref.index}"
            is StateRef.Named -> "Named ${ref.id.toString().take(8)}"
            StateRef.Autosave -> "Autosave"
          }

  fun details(entry: StateBrowserEntry): String {
    val catalog = entry.catalogEntry
    if (catalog == null) {
      return "${name(entry)} is empty.\nSaving here will create a portable managed state."
    }
    return buildList {
          add("Name: ${name(entry)}")
          add("Reference: ${entry.ref.storageKey()}")
          add("Source: ${if (entry.key.sourceIndex == 0) "active directory" else "previous directory ${entry.key.sourceIndex}"}")
          add("Status: ${catalog.status.name.lowercase()}")
          catalog.detail?.let {
            add("Reason: ${boundedStateUiText(it, MAX_DETAIL_CHARS)}")
          }
          catalog.metadata?.savedAt?.let {
            add("Saved: ${formatStateTimestamp(it, TIME_FORMAT)}")
          }
          catalog.metadata?.playDurationNanos?.let { add("Play time: ${formatDuration(it)}") }
          catalog.inspection?.let {
            add("Format: ${it.formatVersion}; ${it.compression.name.lowercase()}")
            it.diagnostics?.let { diagnostics ->
              add(
                  "Core: ${boundedStateUiText(diagnostics.coreVersion, MAX_DIAGNOSTIC_CHARS)}; " +
                      "build: ${boundedStateUiText(diagnostics.buildId, MAX_DIAGNOSTIC_CHARS)}")
            }
            add("Decoded payload: ${it.decodedPayloadLength} bytes")
          }
          catalog.metadataWarning?.let {
            add("Metadata warning: ${boundedStateUiText(it.message, MAX_DETAIL_CHARS)}")
          }
        }
        .joinToString("\n")
  }

  fun exportFileName(entry: StateBrowserEntry): String =
      when (val ref = entry.ref) {
        is StateRef.Slot -> "coffee-gb-slot-${ref.index}.cgbstate"
        is StateRef.Named -> "coffee-gb-state-${ref.id}.cgbstate"
        StateRef.Autosave -> "coffee-gb-autosave.cgbstate"
      }

  private companion object {
    val COLUMNS =
        arrayOf("State", "Saved", "Play time", "Profile", "ROM", "Format / core", "Status", "Source")
    val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    const val MAX_DIAGNOSTIC_CHARS = 160
    const val MAX_DETAIL_CHARS = 512
  }
}

internal class StateBrowserTable(model: StateBrowserTableModel) : JTable(model) {
  init {
    setDefaultRenderer(Any::class.java, LiteralStateTableCellRenderer())
  }

  override fun getToolTipText(event: MouseEvent): String? {
    val point: Point = event.point
    val row = rowAtPoint(point)
    if (row < 0) return null
    return (model as StateBrowserTableModel).entryAt(row).disabledReason
  }

  override fun createToolTip(): JToolTip =
      JToolTip().also {
        it.component = this
        disableSwingHtml(it)
      }
}

private class LiteralStateTableCellRenderer : DefaultTableCellRenderer() {
  init {
    disableSwingHtml(this)
  }
}

internal fun literalSwingLabel(text: String): JLabel =
    JLabel().also {
      disableSwingHtml(it)
      it.text = text
    }

private fun disableSwingHtml(component: JComponent) {
  component.putClientProperty("html.disable", true)
  component.putClientProperty(BasicHTML.propertyKey, null)
}

private fun toBufferedImage(image: StateImage): BufferedImage =
    BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
      it.setRGB(0, 0, image.width, image.height, image.copyRgb(), 0, image.width)
    }

private fun formatDuration(nanos: Long): String {
  val duration = Duration.ofNanos(nanos.coerceAtLeast(0))
  val hours = duration.toHours()
  val minutes = duration.minusHours(hours).toMinutes()
  val seconds = duration.minusHours(hours).minusMinutes(minutes).seconds
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
  else "%d:%02d".format(minutes, seconds)
}

internal fun formatStateTimestamp(
    instant: Instant,
    formatter: DateTimeFormatter,
): String =
    try {
      boundedStateUiText(formatter.format(instant), 80)
    } catch (_: DateTimeException) {
      boundedStateUiText(instant.toString(), 80)
    } catch (_: ArithmeticException) {
      boundedStateUiText(instant.toString(), 80)
    }

internal fun boundedStateUiText(value: String, maximumChars: Int): String {
  require(maximumChars > 0)
  return value
      .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
      .replace(Regex(" {2,}"), " ")
      .trim()
      .let { if (it.length <= maximumChars) it else it.take(maximumChars - 1) + "…" }
}

private fun showStateError(parent: Component, error: StateUserError) {
  JOptionPane.showMessageDialog(
      parent,
      stateErrorPanel(error),
      "State operation failed",
      JOptionPane.ERROR_MESSAGE,
  )
}

private fun stateErrorPanel(error: StateUserError): JPanel {
  val panel = JPanel(BorderLayout(0, 8))
  panel.add(JLabel("<html><b>${escapeHtml(error.summary)}</b></html>"), BorderLayout.NORTH)
  val details =
      JTextArea(
          "Details:\n${error.detail}\n\nWhat to do:\n${error.suggestedAction}",
          10,
          58,
      )
  details.isEditable = false
  details.lineWrap = true
  details.wrapStyleWord = true
  details.caretPosition = 0
  details.accessibleContext.accessibleName = "Copyable state error details"
  panel.add(JScrollPane(details), BorderLayout.CENTER)
  return panel
}

private fun showSelectablePath(
    parent: Component,
    title: String,
    message: String,
    path: Path,
) {
  val field = JTextField(path.toAbsolutePath().normalize().toString(), 52)
  field.isEditable = false
  field.caretPosition = 0
  field.accessibleContext.accessibleName = "Copyable path"
  val panel = JPanel(BorderLayout(0, 8))
  panel.add(JLabel(message), BorderLayout.NORTH)
  panel.add(field, BorderLayout.CENTER)
  JOptionPane.showMessageDialog(parent, panel, title, JOptionPane.INFORMATION_MESSAGE)
}

private fun showTextDetails(
    parent: Component,
    title: String,
    summary: String,
    details: String,
    messageType: Int,
) {
  val text = JTextArea(details, 8, 54)
  text.isEditable = false
  text.lineWrap = true
  text.wrapStyleWord = true
  text.caretPosition = 0
  val panel = JPanel(BorderLayout(0, 8))
  panel.add(JLabel(summary), BorderLayout.NORTH)
  panel.add(JScrollPane(text), BorderLayout.CENTER)
  JOptionPane.showMessageDialog(parent, panel, title, messageType)
}

private fun escapeHtml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun requireEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the Event Dispatch Thread" }
}

private fun onEdt(action: () -> Unit) {
  if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
}

private fun menuShortcutMask(): Int =
    java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
