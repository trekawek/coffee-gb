package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
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
import java.awt.desktop.QuitResponse
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executor
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JList
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
import javax.swing.filechooser.FileNameExtensionFilter
import org.slf4j.LoggerFactory

internal enum class MissingRecentRecovery {
  LOCATE,
  REMOVE,
  CANCEL,
}

private enum class RomOpenConfirmation {
  OPEN,
  CANCEL,
}

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
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
    private val onUpdate: (RomOpenUpdate) -> Unit = {},
) : AutoCloseable {

  private val progress =
      RomOpenProgressDialog(
          owner,
          onCancel = { requestId -> service.cancel(requestId) },
          onRetry = { requestId -> service.retryPersistence(requestId) },
      )

  private val service =
      RomOpenService(eventBus, properties.recentRoms) { update -> handleUpdate(update) }

  private var archiveSelectionHost: DesktopArchiveSelectionHost? = null

  /** Installs the in-screen archive-entry host after the emulator overlay is constructed. */
  internal fun setArchiveSelectionHost(host: DesktopArchiveSelectionHost) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Archive-selection host must be installed on the Event Dispatch Thread"
    }
    archiveSelectionHost = host
  }

  fun open(path: Path, source: RomOpenSource) {
    open(listOf(RomOpenInput.LocalPath(path)), source)
  }

  fun open(inputs: List<RomOpenInput>, source: RomOpenSource) {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater { open(inputs, source) }
      return
    }
    beginOpen(inputs, source)
  }

  fun ownsVisibleRequest(requestId: Long): Boolean =
      service.ownsVisibleRequest(requestId)

  fun hasActiveRequest(): Boolean = service.hasActiveRequest()

  fun cancel(requestId: Long) {
    service.cancel(requestId)
  }

  /** Drains current opening work without permanently invalidating desktop callbacks. */
  fun quiesce() {
    service.quiesce()
    closeProgressUi()
  }

  override fun close() {
    service.close()
    closeProgressUi()
  }

  private fun closeProgressUi() {
    if (SwingUtilities.isEventDispatchThread()) {
      progress.close()
    } else {
      try {
        runDesktopEdtStep(progress::close)
      } catch (interrupted: InterruptedException) {
        throw interrupted
      } catch (failure: Exception) {
        LOG.warn("Unable to close ROM progress UI", failure)
      }
    }
  }

  private fun confirm(path: Path): Boolean {
    val fileName = path.fileName?.toString()?.takeIf(String::isNotBlank) ?: path.toString()
    val running = sessionState.isRunning()
    return proceedWithRomChange(properties.romChangeConfirmationPolicy, running) {
      dialogFactory.showDecision(
          owner,
          DesktopDecisionSpec(
              title = if (running) "Replace game" else "Open ROM",
              heading = if (running) "Replace the running game?" else "Open this game?",
              message =
                  if (running) {
                    "$fileName will replace the current game after its save work completes."
                  } else {
                    "Open $fileName in Coffee GB."
                  },
              buttons =
                  DesktopDialogButtons(
                      primary =
                          DesktopDialogAction(
                              if (running) "Save and open" else "Open",
                              RomOpenConfirmation.OPEN,
                              mnemonic = KeyEvent.VK_O,
                          ),
                      cancel =
                          DesktopDialogAction(
                              if (running) "Keep playing" else "Cancel",
                              RomOpenConfirmation.CANCEL,
                          ),
                      defaultButton = DesktopDialogDefaultButton.CANCEL,
                  ),
              remember =
                  DesktopDecisionRememberOption(
                      results = setOf(RomOpenConfirmation.OPEN),
                      onSelected = {
                        properties.romChangeConfirmationPolicy = RomChangeConfirmationPolicy.NEVER
                      },
                  ),
              modality = DesktopOwnedDialogModality.DOCUMENT,
          )) == RomOpenConfirmation.OPEN
    }
  }

  private fun handleUpdate(update: RomOpenUpdate) {
    check(SwingUtilities.isEventDispatchThread()) {
      "ROM-open UI updates must run on the Event Dispatch Thread"
    }
    onUpdate(update)
    when (update) {
      is RomOpenUpdate.Progress -> handleProgress(update)
      is RomOpenUpdate.Opened -> {
        archiveSelectionHost?.closeArchiveSelection(update.requestId)
        progress.close(update.requestId)
        onRecentChanged()
      }
      is RomOpenUpdate.Cancelled -> {
        archiveSelectionHost?.closeArchiveSelection(update.requestId)
        progress.close(update.requestId)
      }
      is RomOpenUpdate.Failed -> {
        archiveSelectionHost?.closeArchiveSelection(update.requestId)
        progress.close(update.requestId)
        if (update.source == RomOpenSource.RECENT &&
            update.failure.kind == RomOpenFailureKind.MISSING) {
          offerMissingRecentRecovery(update.path)
        } else {
          showRomOpenError(owner, update.failure, dialogFactory)
        }
      }
    }
  }

  private fun handleProgress(update: RomOpenUpdate.Progress) {
    when (update.stage) {
      RomOpenStage.AWAITING_ARCHIVE_SELECTION -> {
        progress.close(update.requestId)
        presentArchiveSelection(
            archiveSelectionHost,
            update.requestId,
            update.candidates,
            chooseNative = { candidates ->
              chooseArchiveCandidate(owner, candidates, dialogFactory)
            },
        ) { selected ->
          applyArchiveSelectionIfCurrent(
              update.requestId,
              selected,
              service::ownsVisibleRequest,
              service::cancel,
          ) { candidate ->
            progress.show(update.requestId, "Opening ${candidate.displayName()}…")
            service.selectArchive(update.requestId, candidate.token())
          }
        }
      }
      RomOpenStage.AWAITING_PERSISTENCE_DECISION ->
          progress.showPersistenceFailure(
              update.requestId,
              update.persistenceFileName ?: "the current game's save",
          )
      // Routine opening work is represented by DesktopMainPanel's nonmodal task banner. Keep the
      // retained progress UI only for persistence decisions that require input.
      else -> {
        archiveSelectionHost?.closeArchiveSelection(update.requestId)
        progress.close(update.requestId)
      }
    }
  }

  private fun offerMissingRecentRecovery(path: Path?) {
    path ?: return
    val name = path.fileName?.toString()?.takeIf(String::isNotBlank) ?: "This recent ROM"
    val result =
      dialogFactory.showDecision(
            owner,
            DesktopDecisionSpec(
                title = "Recent ROM not found",
                heading = "$name is no longer available",
                message =
                    "Choose its new location, remove the unavailable entry from Recent ROMs, " +
                        "or keep the list unchanged.",
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction(
                                "Locate file…",
                                MissingRecentRecovery.LOCATE,
                                mnemonic = KeyEvent.VK_L,
                            ),
                        secondary =
                            listOf(
                                DesktopDialogAction(
                                    "Remove from Recent",
                                    MissingRecentRecovery.REMOVE,
                                    mnemonic = KeyEvent.VK_R,
                                    destructive = true,
                                )),
                        cancel =
                            DesktopDialogAction("Cancel", MissingRecentRecovery.CANCEL),
                        defaultButton = DesktopDialogDefaultButton.CANCEL,
                    ),
                modality = DesktopOwnedDialogModality.DOCUMENT,
            ))
    when (result) {
      MissingRecentRecovery.LOCATE -> locateMissingRecent(path)
      MissingRecentRecovery.REMOVE -> {
        service.removeRecent(path)
        onRecentChanged()
      }
      MissingRecentRecovery.CANCEL -> Unit
    }
  }

  private fun locateMissingRecent(missingPath: Path) {
    val chooser =
        RomFileChooser().apply {
          dialogTitle = "Locate ${missingPath.fileName ?: "recent ROM"}"
          fileFilter =
              FileNameExtensionFilter(
                  "Game Boy ROMs and archives (*.gb, *.gbc, *.rom, *.zip, *.7z)",
                  "gb",
                  "gbc",
                  "rom",
                  "zip",
                  "7z",
              )
          isAcceptAllFileFilterUsed = false
          missingPath.parent?.let(::useConfiguredDirectory)
          getAccessibleContext().accessibleName = "Locate the missing recent ROM"
    }
    if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
      beginOpen(
          listOf(RomOpenInput.LocalPath(chooser.selectedFile.toPath())),
          RomOpenSource.CHOOSER,
          recentPathToReplace = missingPath,
      )
    }
  }

  private fun beginOpen(
      inputs: List<RomOpenInput>,
      source: RomOpenSource,
      recentPathToReplace: Path? = null,
  ): Long? {
    val singlePath = (inputs.singleOrNull() as? RomOpenInput.LocalPath)?.path
    if (singlePath != null && !confirm(singlePath)) return null
    return service.open(RomOpenRequest(inputs, source, recentPathToReplace))
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

/** Uses the portable archive page when installed, otherwise preserving the native Swing chooser. */
internal fun presentArchiveSelection(
    host: DesktopArchiveSelectionHost?,
    requestId: Long,
    candidates: List<RomSourceSnapshot.ArchiveCandidate>,
    chooseNative: (List<RomSourceSnapshot.ArchiveCandidate>) ->
        RomSourceSnapshot.ArchiveCandidate?,
    onDecision: (RomSourceSnapshot.ArchiveCandidate?) -> Unit,
) {
  if (host == null) {
    onDecision(chooseNative(candidates))
  } else {
    host.showArchiveSelection(
        requestId,
        candidates,
        onSelected = onDecision,
        onCancelled = { onDecision(null) },
    )
  }
}

/** Revalidates ownership before applying an asynchronous archive-selection result. */
internal fun <T> applyArchiveSelectionIfCurrent(
    requestId: Long,
    selected: T?,
    ownsVisibleRequest: (Long) -> Boolean,
    cancel: (Long) -> Unit,
    accept: (T) -> Unit,
): Boolean {
  if (!ownsVisibleRequest(requestId)) {
    return false
  }
  if (selected == null) {
    cancel(requestId)
  } else {
    accept(selected)
  }
  return true
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
    dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
): RomSourceSnapshot.ArchiveCandidate? {
  if (candidates.isEmpty()) return null
  val list =
      JList(candidates.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        visibleRowCount = minOf(candidates.size, 10)
        getAccessibleContext().accessibleName = "ROMs in archive"
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
  val content =
      JScrollPane(list).apply {
        preferredSize = Dimension(540, minOf(360, 75 + candidates.size * 28))
        getAccessibleContext().accessibleName = "ROMs available in the archive"
      }
  val outcome =
      dialogFactory.showForm(
          owner,
          DesktopFormSpec(
              title = "Choose ROM — Coffee GB",
              heading = "Choose a game from this archive",
              description =
                  "The archive contains more than one supported ROM. Select the game to open.",
              contentAccessibleName = "ROM archive contents",
              buttons =
                  DesktopDialogButtons(
                      primary =
                          DesktopDialogAction(
                              "Open selected ROM",
                              ArchiveSelectionDecision.OPEN,
                              mnemonic = KeyEvent.VK_O,
                          ),
                      cancel =
                          DesktopDialogAction(
                              "Cancel",
                              ArchiveSelectionDecision.CANCEL,
                          ),
                      defaultButton = DesktopDialogDefaultButton.PRIMARY,
                  ),
              modality = DesktopOwnedDialogModality.DOCUMENT,
          ),
          content,
      )
  return list.selectedValue.takeIf { outcome == ArchiveSelectionDecision.OPEN }
}

private enum class ArchiveSelectionDecision {
  OPEN,
  CANCEL,
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

internal fun showRomOpenError(
    owner: Window,
    failure: RomOpenFailure,
    dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) {
  dialogFactory.showError(
      owner,
      DesktopErrorSpec(
          title = "Unable to open ROM — Coffee GB",
          summary = failure.message,
          recovery =
              "Check the selected file or archive, then try again. Technical details are available below.",
          sanitizedDetails = failure.technicalDetails,
          buttons =
              DesktopDialogButtons(
                  cancel = DesktopDialogAction("Close", Unit),
              ),
          modality = DesktopOwnedDialogModality.DOCUMENT,
      ),
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
        getAccessibleContext().accessibleName = "ROM open error"
        getAccessibleContext().accessibleDescription = failure.message
        columns = 48
      }
  val details =
      JTextArea(failure.technicalDetails).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        caretPosition = 0
        getAccessibleContext().accessibleName = "Technical error details"
      }
  val detailsScroll =
      JScrollPane(details).apply {
        preferredSize = Dimension(580, 150)
        isVisible = false
        getAccessibleContext().accessibleName = "Technical error details"
      }
  val toggle =
      JToggleButton("Show technical details").apply {
        getAccessibleContext().accessibleName = "Show technical error details"
        addActionListener {
          detailsScroll.isVisible = isSelected
          text = if (isSelected) "Hide technical details" else "Show technical details"
          SwingUtilities.getWindowAncestor(this)?.pack()
        }
      }
  val copy =
      JButton("Copy details").apply {
        mnemonic = KeyEvent.VK_C
        getAccessibleContext().accessibleName = "Copy technical error details"
        getAccessibleContext().accessibleDescription =
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
 * Retains platform open-file callbacks received before settings or Swing construction completes.
 * Identical CLI/platform startup deliveries are collapsed once the desktop ROM coordinator is
 * ready.
 */
internal class DesktopOpenFilesBridge(
    private val uiExecutor: Executor =
        Executor { task -> SwingUtilities.invokeLater(task) },
) {
  private val lock = Any()
  private val pending = mutableListOf<List<Path>>()
  private var receiver: ((List<Path>) -> Unit)? = null

  fun accept(paths: List<Path>) {
    val normalized =
        paths.asSequence()
            .take(MAX_EXTERNAL_ROM_INPUTS)
            .map { it.toAbsolutePath().normalize() }
            .toList()
    if (normalized.isEmpty()) {
      return
    }
    val target =
        synchronized(lock) {
          val current = receiver
          if (current == null) {
            if (pending.size == MAX_PENDING_DELIVERIES) {
              pending.removeAt(0)
            }
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

  private companion object {
    const val MAX_PENDING_DELIVERIES = 16
  }
}

/**
 * Queues and coalesces native application-quit requests outside the platform callback. A request
 * received before the Swing close coordinator attaches is retained until it is ready.
 */
internal class DesktopQuitBridge(
    private val uiExecutor: Executor =
        Executor { task -> SwingUtilities.invokeLater(task) },
) {
  private val lock = Any()
  private var pending = false
  private var deliveryInFlight = false
  private var receiver: (() -> Unit)? = null

  fun accept() {
    val target =
        synchronized(lock) {
          val current = receiver
          if (current == null) {
            pending = true
            null
          } else if (deliveryInFlight) {
            null
          } else {
            deliveryInFlight = true
            current
          }
        }
    target?.let(::dispatch)
  }

  fun attach(receiver: () -> Unit) {
    val target =
        synchronized(lock) {
          check(this.receiver == null) { "Desktop quit bridge is already attached" }
          this.receiver = receiver
          if (pending) {
            pending = false
            deliveryInFlight = true
            receiver
          } else {
            null
          }
        }
    target?.let(::dispatch)
  }

  private fun dispatch(receiver: () -> Unit) {
    try {
      uiExecutor.execute {
        try {
          receiver()
        } finally {
          synchronized(lock) { deliveryInFlight = false }
        }
      }
    } catch (failure: RuntimeException) {
      synchronized(lock) { deliveryInFlight = false }
      throw failure
    }
  }
}

/** Visible and screen-reader feedback for a supported drag entering the ROM drop target. */
internal class RomDropFeedback(
    private val root: JRootPane,
    private val enabled: () -> Boolean = { true },
) : AutoCloseable {
  private var normalBorder = root.border
  private var activeDuringThemeChange = false
  private val idleDescription =
      "Drop one Game Boy ROM or ZIP or 7z archive here to open it"
  private val message =
      object : JLabel("Drop to open this ROM or archive", SwingConstants.CENTER),
          DesktopThemePrepareHook,
          DesktopThemeRefreshHook {
        override fun desktopThemeWillChange() {
          activeDuringThemeChange = isVisible
          root.border = normalBorder
        }

        override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
          normalBorder = root.border
          background = tokens.elevatedSurface
          foreground = tokens.primaryText
          border =
              BorderFactory.createCompoundBorder(
                  BorderFactory.createLineBorder(tokens.focus, 2),
                  BorderFactory.createEmptyBorder(8, 16, 8, 16),
              )
          highlight = BorderFactory.createLineBorder(tokens.focus, 3)
          update(activeDuringThemeChange)
        }

        init {
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
          getAccessibleContext().accessibleName = "ROM or archive drop target active"
          getAccessibleContext().accessibleDescription =
              "Release to open the dropped Game Boy ROM or ZIP or 7z archive"
          isVisible = false
        }
      }
  private var highlight =
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
    val effectiveActive = active && enabled()
    root.border = if (effectiveActive) highlight else normalBorder
    message.isVisible = effectiveActive
    root.accessibleContext.accessibleDescription =
        if (effectiveActive) {
          "ROM drop target active. Release to open the selected input."
        } else {
          idleDescription
        }
    if (effectiveActive) {
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
    private val enabled: () -> Boolean = { true },
) : TransferHandler() {

  override fun canImport(support: TransferSupport): Boolean {
    if (!enabled()) {
      feedback(false)
      return false
    }
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
    if (!enabled() || !canImport(support)) {
      feedback(false)
      return false
    }
    return try {
      val inputs =
          if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                .asSequence()
                .take(MAX_EXTERNAL_ROM_INPUTS)
                .map { RomOpenInput.LocalPath(it.toPath()) }
                .toList()
          } else {
            parseDroppedText(
                support.transferable.getTransferData(DataFlavor.stringFlavor) as String)
          }
      feedback(false)
      if (inputs.isEmpty() || !enabled()) {
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

internal fun parseDroppedText(value: String): List<RomOpenInput> {
  if (value.length > MAX_DROP_TEXT_CHARS) {
    return rejectedDropInput(
        "Dropped text exceeds the $MAX_DROP_TEXT_CHARS-character safety limit.",
        "Drop text character limit exceeded (${value.length} characters)",
    )
  }
  val utf8Bytes = value.toByteArray(StandardCharsets.UTF_8).size
  if (utf8Bytes > MAX_DROP_TEXT_UTF8_BYTES) {
    return rejectedDropInput(
        "Dropped text exceeds Coffee GB's encoded-size safety limit.",
        "Drop text UTF-8 limit exceeded ($utf8Bytes bytes)",
    )
  }

  val inputs = ArrayList<RomOpenInput>(MAX_EXTERNAL_ROM_INPUTS)
  for (line in value.lineSequence()) {
    if (line.length > MAX_DROP_TEXT_LINE_CHARS) {
      return rejectedDropInput(
          "A dropped URI or path is too long to process safely.",
          "Drop text line limit exceeded (${line.length} characters)",
      )
    }
    val item = line.trim()
    if (item.isEmpty() || item.startsWith("#")) {
      continue
    }
    inputs +=
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
    if (inputs.size == MAX_EXTERNAL_ROM_INPUTS) {
      break
    }
  }
  return inputs
}

private fun rejectedDropInput(message: String, technicalDetails: String): List<RomOpenInput> =
    listOf(
        RomOpenInput.Rejected(
            RomOpenFailure(
                RomOpenFailureKind.INPUT_LIMIT_EXCEEDED,
                message,
                technicalDetails,
            )))

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
        open(
            event.files.asSequence()
                .take(MAX_EXTERNAL_ROM_INPUTS)
                .map(File::toPath)
                .toList())
      }
      true
    }
  } catch (failure: RuntimeException) {
    LOG.warn("Desktop open-file integration is unavailable", failure)
    false
  }
}

internal fun installDesktopQuitHandler(quit: () -> Unit): Boolean {
  if (!Desktop.isDesktopSupported()) {
    return false
  }
  return try {
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
      false
    } else {
      desktop.setQuitHandler { _, response -> handleDesktopQuitRequest(response, quit) }
      true
    }
  } catch (failure: RuntimeException) {
    LOG.warn("Desktop quit integration is unavailable", failure)
    false
  }
}

/** Cancels Java's synchronous default exit before handing termination to the desktop coordinator. */
internal fun handleDesktopQuitRequest(response: QuitResponse, quit: () -> Unit) {
  response.cancelQuit()
  quit()
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

internal const val MAX_EXTERNAL_ROM_INPUTS = 2
internal const val MAX_DROP_TEXT_CHARS = 16 * 1024
internal const val MAX_DROP_TEXT_UTF8_BYTES = 32 * 1024
internal const val MAX_DROP_TEXT_LINE_CHARS = 4 * 1024
