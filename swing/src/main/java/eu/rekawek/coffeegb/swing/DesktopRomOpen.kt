package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executor
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.UIManager
import org.slf4j.LoggerFactory

/**
 * Desktop-facing coordinator for every ROM entry route. It performs only syntactic routing on the
 * EDT; [RomOpenService] owns all file, archive, and controller work.
 */
internal class DesktopRomOpen(
    private val owner: JFrame,
    eventBus: EventBus,
    private val properties: EmulatorProperties,
    private val sessionState: RomSessionState,
    private val onRecentChanged: () -> Unit,
) : AutoCloseable {

  private val progress =
      RomOpenProgressDialog(
          owner,
          onCancel = { requestId -> service.cancel(requestId) },
          onRetry = { requestId -> service.retryPersistence(requestId) },
      )

  private val service =
      RomOpenService(eventBus, properties.recentRoms) { update -> handleUpdate(update) }

  fun open(path: Path, source: RomOpenSource) {
    open(listOf(RomOpenInput.LocalPath(path)), source)
  }

  fun open(inputs: List<RomOpenInput>, source: RomOpenSource) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater { open(inputs, source) }
      return
    }
    val singlePath = (inputs.singleOrNull() as? RomOpenInput.LocalPath)?.path
    if (singlePath != null && !confirm(singlePath)) {
      return
    }
    service.open(RomOpenRequest(inputs, source))
  }

  fun ownsVisibleRequest(requestId: Long): Boolean =
      service.ownsVisibleRequest(requestId)

  fun hasActiveRequest(): Boolean = service.hasActiveRequest()

  override fun close() {
    service.close()
    if (SwingUtilities.isEventDispatchThread()) {
      progress.close()
    } else {
      runCatching { SwingUtilities.invokeAndWait(progress::close) }
          .onFailure { LOG.warn("Unable to close ROM progress UI", it) }
    }
  }

  private fun confirm(path: Path): Boolean {
    val fileName = path.fileName?.toString()?.takeIf(String::isNotBlank) ?: path.toString()
    val running = sessionState.isRunning()
    return proceedWithRomChange(properties.romChangeConfirmationPolicy, running) {
      val message =
          if (running) "Replace the running game with $fileName?"
          else "Open $fileName?"
      JOptionPane.showConfirmDialog(
          owner,
          message,
          "Open ROM",
          JOptionPane.YES_NO_OPTION,
          JOptionPane.QUESTION_MESSAGE,
      ) == JOptionPane.YES_OPTION
    }
  }

  private fun handleUpdate(update: RomOpenUpdate) {
    check(SwingUtilities.isEventDispatchThread()) {
      "ROM-open UI updates must run on the Event Dispatch Thread"
    }
    when (update) {
      is RomOpenUpdate.Progress -> handleProgress(update)
      is RomOpenUpdate.Opened -> {
        progress.close(update.requestId)
        onRecentChanged()
      }
      is RomOpenUpdate.Cancelled -> {
        progress.close(update.requestId)
      }
      is RomOpenUpdate.Failed -> {
        progress.close(update.requestId)
        showRomOpenError(owner, update.failure)
        if (update.source == RomOpenSource.RECENT &&
            update.failure.kind == RomOpenFailureKind.MISSING) {
          offerMissingRecentRemoval(update.path)
        }
      }
    }
  }

  private fun handleProgress(update: RomOpenUpdate.Progress) {
    when (update.stage) {
      RomOpenStage.AWAITING_ARCHIVE_SELECTION -> {
        progress.close(update.requestId)
        val selected = chooseArchiveCandidate(owner, update.candidates)
        if (selected == null) {
          service.cancel(update.requestId)
        } else {
          progress.show(update.requestId, "Opening ${selected.displayName()}…")
          service.selectArchive(update.requestId, selected.token())
        }
      }
      RomOpenStage.AWAITING_PERSISTENCE_DECISION ->
          progress.showPersistenceFailure(
              update.requestId,
              update.persistenceFileName ?: "the current game's save",
          )
      else ->
          progress.show(
              update.requestId,
              progressMessage(update),
              update.copiedBytes,
          )
    }
  }

  private fun offerMissingRecentRemoval(path: Path?) {
    if (path == null) {
      return
    }
    val choice =
        JOptionPane.showConfirmDialog(
            owner,
            "This file is no longer available. Remove it from Recent ROMs?",
            "Missing recent ROM",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
        )
    if (choice == JOptionPane.YES_OPTION) {
      service.removeRecent(path)
      onRecentChanged()
    }
  }

  private fun progressMessage(update: RomOpenUpdate.Progress): String {
    val fileName = update.path?.fileName?.toString() ?: "ROM"
    return when (update.stage) {
      RomOpenStage.QUEUED -> "Preparing to open $fileName…"
      RomOpenStage.SNAPSHOTTING ->
          if (update.copiedBytes > 0) {
            "Reading $fileName (${formatByteCount(update.copiedBytes)})…"
          } else {
            "Reading $fileName…"
          }
      RomOpenStage.INSPECTING -> "Checking $fileName…"
      RomOpenStage.PREPARING_CORE -> "Starting $fileName…"
      RomOpenStage.AWAITING_ARCHIVE_SELECTION,
      RomOpenStage.AWAITING_PERSISTENCE_DECISION -> error("Handled separately")
    }
  }
}

internal class RomOpenProgressDialog(
    owner: Window,
    private val onCancel: (Long) -> Unit,
    private val onRetry: (Long) -> Unit,
) : AutoCloseable {

  private val dialog = JDialog(owner, "Opening ROM", Dialog.ModalityType.MODELESS)
  private val status = JLabel("Preparing ROM…")
  private val progress = JProgressBar()
  private val retry = JButton("Retry")
  private val cancel = JButton("Cancel")
  private var requestId: Long? = null

  init {
    status.accessibleContext.accessibleName = "ROM opening status"
    status.putClientProperty("html.disable", true)
    progress.isIndeterminate = true
    progress.accessibleContext.accessibleName = "ROM opening progress"
    retry.accessibleContext.accessibleName = "Retry saving the current game"
    cancel.accessibleContext.accessibleName = "Cancel opening ROM"
    retry.isVisible = false
    retry.addActionListener {
      retry.isEnabled = false
      cancel.isEnabled = false
      requestId?.let(onRetry)
    }
    cancel.addActionListener {
      retry.isEnabled = false
      cancel.isEnabled = false
      status.text = "Cancelling…"
      requestId?.let(onCancel)
    }

    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
      add(retry)
      add(cancel)
    }
    val panel =
        JPanel(BorderLayout(12, 12)).apply {
          border = BorderFactory.createEmptyBorder(14, 16, 10, 16)
          add(status, BorderLayout.NORTH)
          add(progress, BorderLayout.CENTER)
          add(buttons, BorderLayout.SOUTH)
        }
    dialog.contentPane = panel
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            requestId?.let(onCancel)
          }
        })
    dialog.rootPane.defaultButton = cancel
    dialog.accessibleContext.accessibleName = "ROM opening progress"
    dialog.minimumSize = Dimension(380, 145)
  }

  fun show(requestId: Long, message: String, copiedBytes: Long = 0) {
    this.requestId = requestId
    dialog.title = "Opening ROM"
    status.text = message
    status.accessibleContext.accessibleDescription = message
    progress.isVisible = true
    progress.isIndeterminate = true
    progress.isStringPainted = copiedBytes > 0
    progress.string = if (copiedBytes > 0) formatByteCount(copiedBytes) else null
    retry.isVisible = false
    retry.isEnabled = true
    cancel.isEnabled = true
    cancel.text = "Cancel"
    cancel.accessibleContext.accessibleName = "Cancel opening ROM"
    dialog.rootPane.defaultButton = cancel
    showDialog()
  }

  fun showPersistenceFailure(requestId: Long, fileName: String) {
    this.requestId = requestId
    dialog.title = "Save before switching games"
    status.text =
        "Could not safely save $fileName. The current game is retained and paused."
    status.accessibleContext.accessibleDescription =
        "The current game could not be saved and is retained paused"
    progress.isVisible = false
    retry.isVisible = true
    retry.isEnabled = true
    cancel.isEnabled = true
    cancel.text = "Keep current game"
    cancel.accessibleContext.accessibleName = "Cancel opening and keep current game"
    dialog.rootPane.defaultButton = retry
    showDialog()
  }

  fun close(requestId: Long) {
    if (this.requestId == requestId) {
      close()
    }
  }

  override fun close() {
    requestId = null
    dialog.isVisible = false
    dialog.dispose()
  }

  private fun showDialog() {
    dialog.pack()
    dialog.setLocationRelativeTo(dialog.owner)
    if (!dialog.isVisible) {
      dialog.isVisible = true
    }
  }
}

internal fun chooseArchiveCandidate(
    owner: Window,
    candidates: List<RomSourceSnapshot.ArchiveCandidate>,
): RomSourceSnapshot.ArchiveCandidate? {
  if (candidates.isEmpty()) {
    return null
  }
  val list =
      JList(candidates.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        visibleRowCount = minOf(candidates.size, 10)
        accessibleContext.accessibleName = "ROMs in ZIP archive"
        cellRenderer =
            object : DefaultListCellRenderer() {
              override fun getListCellRendererComponent(
                  list: JList<*>?,
                  value: Any?,
                  index: Int,
                  isSelected: Boolean,
                  cellHasFocus: Boolean,
              ) =
                  super.getListCellRendererComponent(
                          list,
                          value,
                          index,
                          isSelected,
                          cellHasFocus,
                      )
                      .also { component ->
                        val candidate = value as RomSourceSnapshot.ArchiveCandidate
                        (component as JLabel).text = archiveCandidateLabel(candidate)
                        component.putClientProperty("html.disable", true)
                        component.toolTipText =
                            "<html>${escapeHtml(candidate.entryName())}</html>"
                        component.accessibleContext.accessibleName =
                            archiveCandidateAccessibleLabel(candidate)
                      }
            }
      }
  val panel =
      JPanel(BorderLayout(0, 8)).apply {
        add(JLabel("Choose a ROM from this ZIP archive:"), BorderLayout.NORTH)
        add(JScrollPane(list), BorderLayout.CENTER)
        preferredSize = Dimension(540, minOf(360, 75 + candidates.size * 28))
      }
  return if (
      JOptionPane.showConfirmDialog(
          owner,
          panel,
          "Choose ROM",
          JOptionPane.OK_CANCEL_OPTION,
          JOptionPane.QUESTION_MESSAGE,
      ) == JOptionPane.OK_OPTION
  ) {
    list.selectedValue
  } else {
    null
  }
}

internal fun archiveCandidateLabel(candidate: RomSourceSnapshot.ArchiveCandidate): String {
  val title = candidate.title().ifBlank { candidate.displayName() }
  return "$title — ${candidate.displayName()} (${formatByteCount(candidate.uncompressedBytes())})"
}

internal fun archiveCandidateAccessibleLabel(
    candidate: RomSourceSnapshot.ArchiveCandidate
): String =
    "${candidate.title().ifBlank { "Untitled ROM" }}, " +
        "${candidate.entryName()}, ${formatByteCount(candidate.uncompressedBytes())}"

internal fun showRomOpenError(owner: Window, failure: RomOpenFailure) {
  JOptionPane.showMessageDialog(
      owner,
      createRomOpenErrorPanel(failure),
      "Unable to open ROM",
      JOptionPane.ERROR_MESSAGE,
  )
}

internal fun createRomOpenErrorPanel(failure: RomOpenFailure): JPanel {
  val message =
      JTextArea(failure.message).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(Font.BOLD)
        accessibleContext.accessibleName = "ROM open error"
        accessibleContext.accessibleDescription = failure.message
        columns = 48
      }
  val details =
      JTextArea(failure.technicalDetails).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        caretPosition = 0
        accessibleContext.accessibleName = "Technical error details"
      }
  val detailsScroll =
      JScrollPane(details).apply {
        preferredSize = Dimension(580, 150)
        isVisible = false
        accessibleContext.accessibleName = "Technical error details"
      }
  val toggle =
      JToggleButton("Show technical details").apply {
        accessibleContext.accessibleName = "Show technical error details"
        addActionListener {
          detailsScroll.isVisible = isSelected
          text = if (isSelected) "Hide technical details" else "Show technical details"
          SwingUtilities.getWindowAncestor(this)?.pack()
        }
      }
  val copy =
      JButton("Copy details").apply {
        mnemonic = KeyEvent.VK_C
        accessibleContext.accessibleName = "Copy technical error details"
        accessibleContext.accessibleDescription =
            "Copy the redacted technical error details to the clipboard"
        addActionListener {
          runCatching {
                Toolkit.getDefaultToolkit()
                    .systemClipboard
                    .setContents(StringSelection(details.text), null)
              }
              .onFailure { failure ->
                LOG.warn("Unable to copy ROM-open diagnostics", failure)
              }
        }
      }
  val buttons =
      JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
        add(toggle)
        add(copy)
      }
  return JPanel(BorderLayout(0, 10)).apply {
    add(message, BorderLayout.NORTH)
    add(detailsScroll, BorderLayout.CENTER)
    add(buttons, BorderLayout.SOUTH)
  }
}

/**
 * Installs the platform open-file callback before settings or Swing construction can delay it.
 * Deliveries received during startup are queued and identical CLI/platform startup deliveries are
 * collapsed once the desktop ROM coordinator is ready.
 */
internal class DesktopOpenFilesBridge(
    private val uiExecutor: Executor =
        Executor { task -> SwingUtilities.invokeLater(task) },
) {
  private val lock = Any()
  private val pending = mutableListOf<List<Path>>()
  private var receiver: ((List<Path>) -> Unit)? = null

  fun accept(paths: List<Path>) {
    val normalized = paths.map { it.toAbsolutePath().normalize() }
    if (normalized.isEmpty()) {
      return
    }
    val target =
        synchronized(lock) {
          val current = receiver
          if (current == null) {
            pending += normalized
          }
          current
        }
    target?.let { dispatch(it, normalized) }
  }

  fun attach(
      cliStartupPath: Path?,
      receiver: (List<Path>) -> Unit,
  ) {
    val startup = cliStartupPath?.toAbsolutePath()?.normalize()
    val queued =
        synchronized(lock) {
          check(this.receiver == null) { "Desktop open-file bridge is already attached" }
          this.receiver = receiver
          pending.toList().also { pending.clear() }
        }
    queued
        .distinct()
        .filterNot { startup != null && it.size == 1 && it.single() == startup }
        .forEach { dispatch(receiver, it) }
  }

  private fun dispatch(receiver: (List<Path>) -> Unit, paths: List<Path>) {
    uiExecutor.execute { receiver(paths) }
  }
}

/** Visible and screen-reader feedback for a supported drag entering the ROM drop target. */
internal class RomDropFeedback(private val root: JRootPane) : AutoCloseable {
  private val normalBorder = root.border
  private val idleDescription =
      "Drop one Game Boy ROM or ZIP archive here to open it"
  private val message =
      JLabel("Drop to open this ROM", SwingConstants.CENTER).apply {
        name = "romDropFeedback"
        isOpaque = true
        background = UIManager.getColor("ToolTip.background") ?: Color(255, 255, 220)
        foreground = UIManager.getColor("ToolTip.foreground") ?: Color.BLACK
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    UIManager.getColor("Component.focusColor") ?: Color(65, 105, 225),
                    2,
                ),
                BorderFactory.createEmptyBorder(8, 16, 8, 16),
            )
        putClientProperty("html.disable", true)
        accessibleContext.accessibleName = "ROM drop target active"
        accessibleContext.accessibleDescription =
            "Release to open the dropped Game Boy ROM or ZIP archive"
        isVisible = false
      }
  private val highlight =
      BorderFactory.createLineBorder(
          UIManager.getColor("Component.focusColor") ?: Color(65, 105, 225),
          3,
      )
  private val resizeListener =
      object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = layoutMessage()
      }
  private val clearTimer =
      Timer(300) { update(false) }.apply {
        isRepeats = false
      }

  init {
    root.accessibleContext.accessibleDescription = idleDescription
    root.layeredPane.add(message, JLayeredPane.DRAG_LAYER)
    root.addComponentListener(resizeListener)
    layoutMessage()
  }

  fun update(active: Boolean) {
    root.border = if (active) highlight else normalBorder
    message.isVisible = active
    root.accessibleContext.accessibleDescription =
        if (active) {
          "ROM drop target active. Release to open the selected input."
        } else {
          idleDescription
        }
    if (active) {
      layoutMessage()
      clearTimer.restart()
    } else {
      clearTimer.stop()
    }
    root.repaint()
  }

  override fun close() {
    clearTimer.stop()
    root.removeComponentListener(resizeListener)
    root.layeredPane.remove(message)
    root.border = normalBorder
    root.accessibleContext.accessibleDescription = idleDescription
  }

  private fun layoutMessage() {
    val preferred = message.preferredSize
    val width = minOf(preferred.width, maxOf(1, root.width - 32))
    message.setBounds(
        maxOf(16, (root.width - width) / 2),
        maxOf(16, root.height - preferred.height - 32),
        width,
        preferred.height,
    )
  }
}

internal class RomDropTransferHandler(
    private val submit: (List<RomOpenInput>) -> Unit,
    private val feedback: (Boolean) -> Unit = {},
) : TransferHandler() {

  override fun canImport(support: TransferSupport): Boolean {
    val accepted =
        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
            support.isDataFlavorSupported(DataFlavor.stringFlavor)
    feedback(accepted && support.isDrop)
    if (accepted && support.isDrop) {
      support.dropAction = COPY
    }
    return accepted
  }

  override fun importData(support: TransferSupport): Boolean {
    if (!canImport(support)) {
      feedback(false)
      return false
    }
    return try {
      val inputs =
          if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                .map { RomOpenInput.LocalPath(it.toPath()) }
          } else {
            parseDroppedText(
                support.transferable.getTransferData(DataFlavor.stringFlavor) as String)
          }
      feedback(false)
      if (inputs.isEmpty()) {
        false
      } else {
        submit(inputs)
        true
      }
    } catch (failure: Exception) {
      LOG.warn("Unable to decode dropped ROM data", failure)
      feedback(false)
      false
    }
  }

  private companion object {
    val LOG = LoggerFactory.getLogger(RomDropTransferHandler::class.java)
  }
}

internal fun parseDroppedText(value: String): List<RomOpenInput> =
    value
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { item ->
          try {
            val uri = URI(item)
            if (uri.scheme.equals("file", ignoreCase = true)) {
              RomOpenInput.LocalPath(Path.of(uri))
            } else {
              RomOpenInput.RemoteUrl(item)
            }
          } catch (_: Exception) {
            RomOpenInput.RemoteUrl(item)
          }
        }
        .toList()

internal fun installDesktopOpenFileHandler(open: (List<Path>) -> Unit): Boolean {
  if (!Desktop.isDesktopSupported()) {
    return false
  }
  return try {
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
      false
    } else {
      desktop.setOpenFileHandler { event ->
        open(event.files.map(File::toPath))
      }
      true
    }
  } catch (failure: RuntimeException) {
    LOG.warn("Desktop open-file integration is unavailable", failure)
    false
  }
}

internal fun formatByteCount(bytes: Long): String =
    when {
      bytes < 1024 -> "$bytes B"
      bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
      else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    }

private fun escapeHtml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val LOG = LoggerFactory.getLogger("DesktopRomOpen")
