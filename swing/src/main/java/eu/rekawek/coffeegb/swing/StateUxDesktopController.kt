package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingFailedEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingDiscardEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingMode
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingSavedEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStartRequestEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStatusEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingStopRequestEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingRetrySaveEvent
import eu.rekawek.coffeegb.controller.state.StateBrowserCatalog
import eu.rekawek.coffeegb.controller.state.StateBrowserEntry
import eu.rekawek.coffeegb.controller.state.StateCatalogReadyEvent
import eu.rekawek.coffeegb.controller.state.StateDeleteRequestEvent
import eu.rekawek.coffeegb.controller.state.StateExportRequestEvent
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent
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
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityEvent
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityRequestEvent
import eu.rekawek.coffeegb.controller.state.StateUserError
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
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
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.border.TitledBorder
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
    initialBounds: Rectangle? = null,
    onBoundsChanged: (Rectangle) -> Unit = {},
    private val onDesktopStatus: (String, DesktopCommand?) -> Unit = { _, _ -> },
    onSlotLoadAvailability: (slot: Int, available: Boolean) -> Unit = { _, _ -> },
    private val onPortableCatalog: (StateBrowserCatalog, Set<Int>) -> Unit = { _, _ -> },
    private val onRememberResumeDecision: (resume: Boolean) -> Unit = {},
    private val onInputRecordingPhase: (ReplayRecordingPhase) -> Unit = {},
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) : AutoCloseable {
  private val eventBus = rootEventBus.fork("desktop-state-ux")
  private val requestIds = AtomicLong()
  private var currentSession: StateUxSessionEvent? = null
  private var latestPortableCatalogRequest = 0L
  private var latestPortableCatalogSessionId: Long? = null
  private val browser =
      StateBrowserWindowHost(
          initialSession = { currentSession },
          viewFactory =
              StateBrowserWindowViewFactory { session ->
                StateBrowserDialog(
                    owner,
                    eventBus,
                    ::nextRequestId,
                    captureDisplayImage,
                    session,
                    initialBounds,
                    onBoundsChanged,
                    dialogFactory,
                )
              },
      )
  private var pendingClose: PendingClose? = null
  private var inputRecordingPhase = ReplayRecordingPhase.IDLE
  private var closed = false
  private val slotLoadAvailability =
      StateSlotLoadAvailabilityTracker(
          nextRequestId = ::nextRequestId,
          postRequest = eventBus::post,
          publish = onSlotLoadAvailability,
      )

  init {
    requireEdt("State desktop controller construction")
    eventBus.register<StateUxSessionEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        val current = currentSession
        if (current == null || event.sessionId >= current.sessionId) {
          currentSession = event
          if (!event.available || latestPortableCatalogSessionId != event.sessionId) {
            latestPortableCatalogRequest = 0L
            latestPortableCatalogSessionId = null
            publishEmptyPortableCatalog()
          }
          pendingClose = null
          slotLoadAvailability.sessionChanged(event)
          browser.updateSession(event)
        }
      }
    }
    eventBus.register<StateCatalogReadyEvent> { event ->
      onEdt {
        if (closed || !acceptsStateCatalogRequest(latestPortableCatalogRequest, event.requestId) ||
            event.sessionId != latestPortableCatalogSessionId ||
            event.sessionId != currentSession?.sessionId) {
          return@onEdt
        }
        // BasicController coalesces browser and portable catalog requests into one global latest
        // request. A newer same-session result is therefore also the authoritative answer for an
        // older portable request; advance the high-water mark so an older late result is ignored.
        latestPortableCatalogRequest = event.requestId
        onPortableCatalog(event.catalog, event.compatibilitySlots)
      }
    }
    eventBus.register<ControllerOwnershipChangingEvent> {
      onEdt {
        if (closed) return@onEdt
        val current = currentSession ?: return@onEdt
        val unavailable =
            current.copy(
                available = false,
                gameDirectory = null,
                unavailableReason =
                    StateUserError(
                        "Managed states are unavailable in linked play.",
                        "Portable managed-state capture and restore are supported only by a standalone local session.",
                        "Return to single-player emulation to manage states.",
                    ),
            )
        currentSession = unavailable
        latestPortableCatalogRequest = 0L
        latestPortableCatalogSessionId = null
        publishEmptyPortableCatalog()
        pendingClose = null
        slotLoadAvailability.sessionChanged(unavailable)
        browser.updateSession(unavailable)
      }
    }
    eventBus.register<StateOperationCompletedEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        if (!isCurrent(event.sessionId)) return@onEdt
        if (event.operation == StateOperation.SAVE &&
            latestPortableCatalogSessionId == event.sessionId) {
          // A quick save writes its thumbnail off-thread.  Only request a new catalog after the
          // authoritative completion event, so the menu cannot race a still-old thumbnail.
          refreshPortableCatalog()
        }
        slotLoadAvailability.operationCompleted(event)
        browser.operationCompleted(event)
        if (event.recoveryMessages.isNotEmpty()) {
          onDesktopStatus(
              "${event.message} Open Manage States to review storage recovery details.",
              DesktopCommand.MANAGE_STATES,
          )
        }
        when (event.operation) {
          StateOperation.SCREENSHOT ->
              event.path?.let {
                onDesktopStatus(
                    "${event.message} Use Open Save Folder to reveal the screenshot.",
                    DesktopCommand.OPEN_SAVE_FOLDER,
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
        if (closed) return@onEdt
        if (!isCurrent(event.sessionId)) return@onEdt
        slotLoadAvailability.operationFailed(event)
        browser.operationFailed(event)
        showStateError(owner, event.error)
      }
    }
    eventBus.register<StateSlotLoadAvailabilityEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        slotLoadAvailability.availabilityChanged(event)
      }
    }
    eventBus.register<StateResumeAvailableEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        if (!isCurrent(event.sessionId)) return@onEdt
        val expectedSessionId = event.sessionId
        val saved =
            event.savedAt?.let {
              formatStateTimestamp(it, RESUME_TIME_FORMAT)
            } ?: "an unknown time"
        val duration =
            event.playDurationNanos?.let(::formatDuration)?.let { " after $it of play" }.orEmpty()
        val choice =
            dialogFactory.showDecision(
                owner,
                DesktopDecisionSpec(
                    title = "Resume previous game",
                    heading = "Resume the autosave from $saved$duration?",
                    message =
                        "Resume continues from that saved session. Start fresh leaves this launch at its normal beginning.",
                    buttons =
                        DesktopDialogButtons(
                            primary =
                                DesktopDialogAction(
                                    "Resume",
                                    StateResumeChoice.RESUME,
                                ),
                            cancel =
                                DesktopDialogAction(
                                    "Start fresh",
                                    StateResumeChoice.START_FRESH,
                                ),
                            defaultButton = DesktopDialogDefaultButton.NONE,
                        ),
                    remember =
                        DesktopDecisionRememberOption(
                            results =
                                setOf(
                                    StateResumeChoice.RESUME,
                                    StateResumeChoice.START_FRESH,
                                ),
                            onSelected = { selected ->
                              onRememberResumeDecision(selected == StateResumeChoice.RESUME)
                            },
                        ),
                ),
            )
        if (isCurrent(expectedSessionId)) {
          eventBus.post(
              StateResumeDecisionEvent(
                  event.requestId,
                  expectedSessionId,
                  choice == StateResumeChoice.RESUME,
              ))
        }
      }
    }
    eventBus.register<StatePrepareCloseCompletedEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        finishClosePreparation(event)
      }
    }
    eventBus.register<ReplayRecordingStatusEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        event.sessionId?.let { sessionId ->
          if (!isCurrent(sessionId)) return@onEdt
        }
        inputRecordingPhase = event.phase
        onInputRecordingPhase(event.phase)
        if (event.phase == ReplayRecordingPhase.ARMING) {
          event.message?.let { message -> onDesktopStatus(message, null) }
        }
      }
    }
    eventBus.register<ReplayRecordingSavedEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        inputRecordingPhase = ReplayRecordingPhase.IDLE
        onInputRecordingPhase(ReplayRecordingPhase.IDLE)
        onDesktopStatus(
            "Input recording saved as ${event.path.fileName}. Use Open Save Folder to reveal it.",
            DesktopCommand.OPEN_SAVE_FOLDER,
        )
      }
    }
    eventBus.register<ReplayRecordingFailedEvent> { event ->
      onEdt {
        if (closed) return@onEdt
        event.sessionId?.let { sessionId ->
          if (!isCurrent(sessionId)) return@onEdt
        }
        if (event.recoverable) {
          val session = currentSession ?: return@onEdt
          when (
              JOptionPane.showOptionDialog(
                  owner,
                  "${event.summary}\n\n${event.detail}",
                  "Input recording was not saved",
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.ERROR_MESSAGE,
                  null,
                  arrayOf("Retry Save", "Discard Recording"),
                  "Retry Save",
              )) {
            0 -> eventBus.post(ReplayRecordingRetrySaveEvent(nextRequestId(), session.sessionId))
            1 -> eventBus.post(ReplayRecordingDiscardEvent(nextRequestId(), session.sessionId))
          }
          return@onEdt
        }
        showStateError(
            owner,
            StateUserError(
                event.summary,
                event.detail,
                if (event.recoverable) "Keep the game open and retry saving the recording."
                else "Keep the game open and start a new recording after resolving this issue.",
            ),
        )
      }
    }
  }

  fun showBrowser() {
    requireEdt("State browser opening")
    if (closed) return
    browser.showOrRaise()
  }

  fun refreshPortableCatalog() {
    requireEdt("Portable state catalog refresh")
    val session = currentSession?.takeIf { it.available }
    if (session == null || closed) {
      latestPortableCatalogRequest = 0L
      latestPortableCatalogSessionId = null
      publishEmptyPortableCatalog()
      return
    }
    latestPortableCatalogSessionId = session.sessionId
    latestPortableCatalogRequest = nextRequestId()
    publishEmptyPortableCatalog()
    eventBus.post(
        eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent(
            latestPortableCatalogRequest,
            session.sessionId,
        ))
  }

  private fun publishEmptyPortableCatalog() {
    onPortableCatalog(StateBrowserCatalog(emptyList(), false, null, emptyList()), emptySet())
  }

  fun takeScreenshot() {
    requireEdt("Screenshot request")
    if (!requireAvailableSession()) return
    val expectedSessionId = checkNotNull(currentSession).sessionId
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
    if (!isCurrent(expectedSessionId)) return
    eventBus.post(StateScreenshotRequestEvent(nextRequestId(), expectedSessionId, image))
  }

  fun toggleInputRecording() {
    requireEdt("Input recording request")
    if (closed) return
    when (inputRecordingPhase) {
      ReplayRecordingPhase.ARMING,
      ReplayRecordingPhase.RECORDING -> {
        val session = currentSession ?: return
        eventBus.post(ReplayRecordingStopRequestEvent(nextRequestId(), session.sessionId))
      }
      ReplayRecordingPhase.IDLE -> startInputRecording()
      ReplayRecordingPhase.SAVING ->
          onDesktopStatus("Input recording is being saved. Please wait.", null)
      ReplayRecordingPhase.UNSAVED ->
          onDesktopStatus("Input recording needs saving before another recording can start.", null)
    }
  }

  private fun startInputRecording() {
    if (!requireAvailableSession()) return
    val expectedSessionId = checkNotNull(currentSession).sessionId
    val choice = showInputRecordingModeDialog() ?: return
    if (!isCurrent(expectedSessionId)) return
    eventBus.post(
        ReplayRecordingStartRequestEvent(
            nextRequestId(),
            expectedSessionId,
            choice,
            includeSensitiveInitialState = choice == ReplayRecordingMode.CURRENT_SESSION,
        ))
  }

  /** No option is preselected: choosing the privacy-sensitive mode is always deliberate. */
  private fun showInputRecordingModeDialog(): ReplayRecordingMode? {
    val current = JRadioButton("From current moment")
    val cleanBoot = JRadioButton("Restart from clean boot")
    val consent =
        JCheckBox(
            "I understand this file includes the current emulator and cartridge save state.",
        ).apply { isEnabled = false }
    ButtonGroup().apply {
      add(current)
      add(cleanBoot)
    }
    current.addActionListener { consent.isEnabled = true }
    cleanBoot.addActionListener {
      consent.isSelected = false
      consent.isEnabled = false
    }
    val panel =
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.Y_AXIS)
          add(JLabel("Choose how to begin input recording:"))
          add(Box.createVerticalStrut(10))
          add(current)
          add(
              JLabel(
                  "  Continues now. The replay includes emulator memory and cartridge RAM/save data, but never ROM bytes or paths."))
          add(consent)
          add(Box.createVerticalStrut(10))
          add(cleanBoot)
          add(
              JLabel(
                  "  Restarts in a battery-isolated clean boot. The replay and session contain no save state."))
        }
    val result =
        JOptionPane.showOptionDialog(
            owner,
            panel,
            "Start Input Recording",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf("Start Recording", "Cancel"),
            "Cancel",
        )
    if (result != 0) return null
    return when {
      current.isSelected && consent.isSelected -> ReplayRecordingMode.CURRENT_SESSION
      cleanBoot.isSelected -> ReplayRecordingMode.CLEAN_BOOT
      current.isSelected -> {
        onDesktopStatus("Confirm the current-session data notice before recording.", null)
        null
      }
      else -> {
        onDesktopStatus("Choose a recording mode before starting.", null)
        null
      }
    }
  }

  fun saveSlot(slot: Int) {
    requireEdt("Quick state save request")
    val ref = StateRef.Slot(slot)
    if (!requireAvailableSession()) return
    val expectedSessionId = checkNotNull(currentSession).sessionId
    val thumbnail = captureStateImage("state preview") ?: return
    if (!isCurrent(expectedSessionId)) return
    eventBus.post(
        StateSaveRequestEvent(
            nextRequestId(),
            expectedSessionId,
            ref,
            "Slot $slot",
            thumbnail,
        ))
  }

  fun loadSlot(slot: Int) {
    requireEdt("Quick state load request")
    val ref = StateRef.Slot(slot)
    if (!requireAvailableSession()) return
    val expectedSessionId = checkNotNull(currentSession).sessionId
    val requestId = nextRequestId()
    slotLoadAvailability.quickLoadRequested(requestId, expectedSessionId, slot)
    eventBus.post(StateLoadRefRequestEvent(requestId, expectedSessionId, ref))
  }

  fun selectSlot(slot: Int) {
    requireEdt("Quick state slot selection")
    slotLoadAvailability.slotSelected(slot)
  }

  fun openSaveFolder() {
    requireEdt("Open-save-folder request")
    if (!requireAvailableSession()) return
    val expectedSessionId = checkNotNull(currentSession).sessionId
    eventBus.post(StateOpenFolderRequestEvent(nextRequestId(), expectedSessionId))
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
    val choice =
        dialogFactory.showError(
            owner,
            DesktopErrorSpec(
                title = "Autosave failed",
                summary = error.summary,
                recovery =
                    error.suggestedAction +
                        " Closing without autosave can lose progress from this session.",
                sanitizedDetails = error.detail,
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction(
                                "Retry autosave",
                                StateCloseAutosaveChoice.RETRY,
                            ),
                        secondary =
                            listOf(
                                DesktopDialogAction(
                                    "Close without autosave",
                                    StateCloseAutosaveChoice.CLOSE_WITHOUT_AUTOSAVE,
                                    destructive = true,
                                )),
                        cancel =
                            DesktopDialogAction(
                                "Cancel",
                                StateCloseAutosaveChoice.CANCEL,
                            ),
                        defaultButton = DesktopDialogDefaultButton.CANCEL,
                    ),
            ),
        )
    when (choice) {
      StateCloseAutosaveChoice.RETRY -> prepareClose(pending.onPrepared)
      StateCloseAutosaveChoice.CLOSE_WITHOUT_AUTOSAVE -> {
        val requestId = nextRequestId()
        pendingClose = PendingClose(requestId, pending.onPrepared)
        eventBus.post(StateSkipCloseAutosaveRequestEvent(requestId, event.sessionId))
      }
      StateCloseAutosaveChoice.CANCEL -> Unit
    }
  }

  private fun requireAvailableSession(): Boolean {
    if (currentSession?.available == true) return true
    currentSession?.unavailableReason?.let {
      showStateError(owner, it)
      return false
    }
    onDesktopStatus("State management requires a running local game.", null)
    return false
  }

  private fun captureStateImage(description: String): StateImage? =
      try {
        captureDisplayImage()
      } catch (failure: RuntimeException) {
        showStateError(
            owner,
            StateUserError(
                "The $description could not be captured.",
                failure.message ?: failure.javaClass.name,
                "Keep the game open and retry after the next frame.",
            ),
        )
        null
      }

  private fun isCurrent(sessionId: Long): Boolean =
      currentSession?.sessionId == sessionId

  private fun nextRequestId(): Long = requestIds.incrementAndGet()

  override fun close() {
    requireEdt("State desktop controller disposal")
    if (closed) return
    closed = true
    pendingClose = null
    browser.close()
    eventBus.close()
  }

  private data class PendingClose(
      val requestId: Long,
      val onPrepared: () -> Unit,
  )

  private enum class StateResumeChoice {
    RESUME,
    START_FRESH,
  }

  private enum class StateCloseAutosaveChoice {
    RETRY,
    CLOSE_WITHOUT_AUTOSAVE,
    CANCEL,
  }

  private companion object {
    val RESUME_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
  }
}

/** Correlates one selected-slot availability probe without performing filesystem work on the EDT. */
internal class StateSlotLoadAvailabilityTracker(
    private val nextRequestId: () -> Long,
    private val postRequest: (StateSlotLoadAvailabilityRequestEvent) -> Unit,
    private val publish: (slot: Int, available: Boolean) -> Unit,
) {
  private var session: StateUxSessionEvent? = null
  private var selectedSlot = 0
  private var pending: Pending? = null
  private var pendingQuickLoad: PendingQuickLoad? = null

  fun sessionChanged(event: StateUxSessionEvent) {
    val current = session
    if (current != null && event.sessionId < current.sessionId) return
    session = event
    pending = null
    pendingQuickLoad = null
    publish(selectedSlot, false)
    request()
  }

  fun slotSelected(slot: Int) {
    require(slot in 0..9)
    selectedSlot = slot
    pending = null
    publish(slot, false)
    request()
  }

  fun quickLoadRequested(requestId: Long, expectedSessionId: Long, slot: Int) {
    require(requestId > 0)
    require(expectedSessionId > 0)
    require(slot in 0..9)
    val current = session
    if (current?.available != true || current.sessionId != expectedSessionId) return
    pendingQuickLoad = PendingQuickLoad(requestId, expectedSessionId, slot)
  }

  fun availabilityChanged(event: StateSlotLoadAvailabilityEvent) {
    val expected = pending ?: return
    if (event.requestId != expected.requestId ||
        event.sessionId != expected.sessionId ||
        event.ref.index != expected.slot ||
        event.sessionId != session?.sessionId ||
        event.ref.index != selectedSlot) {
      return
    }
    pending = null
    publish(selectedSlot, event.available)
  }

  fun operationCompleted(event: StateOperationCompletedEvent) {
    val current = session ?: return
    if (event.sessionId != current.sessionId) return
    if (event.operation == StateOperation.LOAD &&
        pendingQuickLoad?.requestId == event.requestId) {
      pendingQuickLoad = null
    }
    val slot = (event.ref as? StateRef.Slot)?.index ?: return
    if (slot != selectedSlot) return
    when (event.operation) {
      StateOperation.SAVE -> {
        if (!current.available) return
        pending = null
        publish(slot, true)
      }
      StateOperation.DELETE -> {
        pending = null
        publish(slot, false)
        request()
      }
      else -> Unit
    }
  }

  fun operationFailed(event: StateOperationFailedEvent) {
    if (event.operation != StateOperation.LOAD) return
    val quickLoad = pendingQuickLoad ?: return
    if (event.requestId != quickLoad.requestId ||
        event.sessionId != quickLoad.sessionId) {
      return
    }
    pendingQuickLoad = null
    val current = session
    if (current?.available != true ||
        current.sessionId != quickLoad.sessionId ||
        selectedSlot != quickLoad.slot) {
      return
    }
    pending = null
    publish(selectedSlot, false)
    request()
  }

  private fun request() {
    val current = session?.takeIf { it.available } ?: return
    val requestId = nextRequestId()
    pending = Pending(requestId, current.sessionId, selectedSlot)
    postRequest(
        StateSlotLoadAvailabilityRequestEvent(
            requestId,
            current.sessionId,
            StateRef.Slot(selectedSlot),
        ))
  }

  private data class Pending(
      val requestId: Long,
      val sessionId: Long,
      val slot: Int,
  )

  private data class PendingQuickLoad(
      val requestId: Long,
      val sessionId: Long,
      val slot: Int,
  )
}

internal interface StateBrowserWindowView : AutoCloseable {
  fun showOrRaise()

  fun updateSession(event: StateUxSessionEvent)

  fun operationCompleted(event: StateOperationCompletedEvent)

  fun operationFailed(event: StateOperationFailedEvent)
}

/**
 * Catalog requests share one controller-level coalescing stream. Consumers accept their own
 * request or any newer request from the same session, but never an older result or an unarmed
 * callback.
 */
internal fun acceptsStateCatalogRequest(outstandingRequestId: Long, completedRequestId: Long): Boolean =
    outstandingRequestId > 0L && completedRequestId >= outstandingRequestId

internal fun interface StateBrowserWindowViewFactory {
  fun create(initialSession: StateUxSessionEvent?): StateBrowserWindowView
}

/** Owns one lazy modeless State Manager view for the full desktop-controller lifetime. */
internal class StateBrowserWindowHost(
    private val initialSession: () -> StateUxSessionEvent?,
    private val viewFactory: StateBrowserWindowViewFactory,
) : AutoCloseable {
  private var view: StateBrowserWindowView? = null
  private var closed = false

  init {
    requireEdt("State browser host construction")
  }

  fun showOrRaise() {
    requireEdt("State browser display")
    if (closed) return
    retainedView().showOrRaise()
  }

  fun updateSession(event: StateUxSessionEvent) {
    requireEdt("State browser session update")
    if (!closed) view?.updateSession(event)
  }

  fun operationCompleted(event: StateOperationCompletedEvent) {
    requireEdt("State browser completion")
    if (!closed) view?.operationCompleted(event)
  }

  fun operationFailed(event: StateOperationFailedEvent) {
    requireEdt("State browser failure")
    if (!closed) view?.operationFailed(event)
  }

  private fun retainedView(): StateBrowserWindowView =
      view ?: viewFactory.create(initialSession()).also { view = it }

  override fun close() {
    requireEdt("State browser host disposal")
    if (closed) return
    closed = true
    try {
      view?.close()
    } finally {
      view = null
    }
  }
}

private enum class StateNamedChoice {
  SAVE,
  CANCEL,
}

private enum class StateDeleteChoice {
  DELETE,
  KEEP,
}

internal fun validateNamedStateLabel(value: String): DesktopInlineValidation {
  val label = value.trim()
  return when {
    label.isEmpty() -> DesktopInlineValidation(false, "Enter a state name.")
    label.codePointCount(0, label.length) > 120 ->
        DesktopInlineValidation(false, "Use no more than 120 characters.")
    label.any(Character::isISOControl) ->
        DesktopInlineValidation(false, "State names cannot contain control characters.")
    else -> DesktopInlineValidation(true)
  }
}

internal class StateBrowserDialog(
    owner: JFrame,
    rootEventBus: EventBus,
    private val nextRequestId: () -> Long,
    private val captureDisplayImage: () -> StateImage,
    initialSession: StateUxSessionEvent?,
    initialBounds: Rectangle? = null,
    private val onBoundsChanged: (Rectangle) -> Unit = {},
    private val dialogFactory: DesktopDialogFactory = DesktopDialogFactory(),
) : StateBrowserWindowView {
  private val dialog =
      JDialog(owner, "States — Coffee GB", Dialog.ModalityType.MODELESS)
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
  private var positioned = initialBounds != null
  private var closed = false

  init {
    requireEdt("State browser construction")
    dialog.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = publishBounds()
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
    configureButton(close, KeyEvent.VK_C) { hide() }
    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING))
    listOf(save, load, named, delete, export, refresh, close).forEach(buttons::add)

    status.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
    status.accessibleContext.accessibleName = "State browser status"
    val bottom = JPanel(BorderLayout())
    bottom.add(status, BorderLayout.CENTER)
    bottom.add(buttons, BorderLayout.SOUTH)

    val content = StateBrowserContentPanel(preview, detail)
    dialog.contentPane = content
    dialog.rootPane.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    content.add(split, BorderLayout.CENTER)
    content.add(bottom, BorderLayout.SOUTH)
    configureKeys()

    eventBus.register<StateCatalogReadyEvent> { event ->
      onEdt {
        if (!acceptsStateCatalogRequest(latestCatalogRequest, event.requestId) ||
            event.sessionId != session?.sessionId ||
            closed) {
          return@onEdt
        }
        // Catalog requests from the portable menu and this dialog share the controller's global
        // coalescing key. A newer result superseding this dialog's request is still authoritative
        // for the same session; remember its id to reject older late results.
        latestCatalogRequest = event.requestId
        applyCatalog(event.catalog)
      }
    }
    updateAvailability()
    dialog.pack()
    initialBounds?.let { dialog.bounds = Rectangle(it) }
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentMoved(event: ComponentEvent) = publishBounds()

          override fun componentResized(event: ComponentEvent) = publishBounds()
        })
  }

  override fun showOrRaise() {
    requireEdt("State browser display")
    if (closed) return
    if (!positioned) {
      dialog.setLocationRelativeTo(dialog.owner)
      positioned = true
    }
    dialog.isVisible = true
    dialog.toFront()
    dialog.requestFocus()
    requestCatalog()
  }

  override fun updateSession(event: StateUxSessionEvent) {
    requireEdt("State browser session update")
    val previous = session
    if (previous != null && event.sessionId < previous.sessionId) return
    session = event
    pendingOperations.clear()
    latestCatalogRequest = 0
    model.entries = emptyList()
    updateAvailability()
    if (event.available && dialog.isVisible) requestCatalog()
  }

  override fun operationCompleted(event: StateOperationCompletedEvent) {
    requireEdt("State browser completion")
    val owned = pendingOperations.remove(event.requestId)
    if (event.recoveryMessages.isNotEmpty()) {
      status.text = event.message
      detail.text = event.recoveryMessages.joinToString("\n")
      detail.caretPosition = 0
    }
    when (stateBrowserCompletionAction(event.operation, owned)) {
      StateBrowserCompletionAction.IGNORE -> return
      StateBrowserCompletionAction.REFRESH_CATALOG -> {
        if (owned) status.text = event.message
        // Quick-save requests originate in the main Game menu, not this modeless dialog. Refresh
        // their shared catalog without pretending that the browser owns the operation/status.
        if (dialog.isVisible) requestCatalog(announce = owned)
      }
      StateBrowserCompletionAction.UPDATE_SELECTION -> {
        status.text = event.message
        updateSelection()
      }
    }
  }

  override fun operationFailed(event: StateOperationFailedEvent) {
    requireEdt("State browser failure")
    if (!pendingOperations.remove(event.requestId)) return
    status.text = event.error.summary
    updateSelection()
  }

  private fun hide() {
    requireEdt("State browser hiding")
    if (closed) return
    publishBounds()
    dialog.isVisible = false
  }

  override fun close() {
    requireEdt("State browser disposal")
    if (closed) return
    closed = true
    pendingOperations.clear()
    eventBus.close()
    publishBounds()
    dialog.dispose()
  }

  private fun requestCatalog(announce: Boolean = true) {
    requireEdt("State catalog request")
    val expectedSessionId = availableSessionId()
    if (expectedSessionId == null || closed) {
      updateAvailability()
      return
    }
    latestCatalogRequest = nextRequestId()
    if (announce) status.text = "Refreshing states…"
    eventBus.post(
        eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent(
            latestCatalogRequest,
            expectedSessionId,
        ))
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
    val expectedSessionId = availableSessionId() ?: return
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
    if (availableSessionId() != expectedSessionId) return
    pendingOperations += requestId
    status.text = "Saving $label…"
    eventBus.post(
        StateSaveRequestEvent(
            requestId,
            expectedSessionId,
            selected.ref,
            label,
            thumbnail,
        ))
    updateSelection()
  }

  private fun loadSelected() {
    requireEdt("State load request")
    val expectedSessionId = availableSessionId() ?: return
    val selected = selectedEntry() ?: return
    if (!selected.canLoad) {
      status.text = selected.disabledReason ?: "This state cannot be loaded."
      return
    }
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Loading ${model.name(selected)}…"
    eventBus.post(StateLoadRequestEvent(requestId, expectedSessionId, selected.key))
    updateSelection()
  }

  private fun newNamed() {
    requireEdt("Named state request")
    val expectedSessionId = availableSessionId() ?: return
    val labelField =
        JTextField(36).apply {
          getAccessibleContext().accessibleName = "State name"
          getAccessibleContext().accessibleDescription =
              "A printable state name containing at most 120 characters"
          putClientProperty("html.disable", true)
        }
    val labelPrompt = JLabel("State name:").apply { labelFor = labelField }
    val form =
        JPanel(BorderLayout(0, 6)).apply {
          getAccessibleContext().accessibleName = "Named state fields"
          add(labelPrompt, BorderLayout.NORTH)
          add(labelField, BorderLayout.CENTER)
        }
    fun publishValidation() {
      val validation = validateNamedStateLabel(labelField.text)
      val shell =
          SwingUtilities.getAncestorOfClass(DesktopFormPanel::class.java, labelField)
              as? DesktopFormPanel<*>
      shell?.setSubmissionState(validation.valid, validation.message)
    }
    labelField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = publishValidation()

          override fun removeUpdate(event: DocumentEvent) = publishValidation()

          override fun changedUpdate(event: DocumentEvent) = publishValidation()
        })
    SwingUtilities.invokeLater { labelField.requestFocusInWindow() }
    val result =
        dialogFactory.showForm(
            dialog,
            DesktopFormSpec(
                title = "New named state",
                heading = "Save a named state",
                description = "Choose a recognizable name for this point in the game.",
                contentAccessibleName = "Named state fields",
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction(
                                "Save state",
                                StateNamedChoice.SAVE,
                            ),
                        cancel =
                            DesktopDialogAction(
                                "Cancel",
                                StateNamedChoice.CANCEL,
                            ),
                        defaultButton = DesktopDialogDefaultButton.PRIMARY,
                    ),
                initiallyValid = false,
                modality = DesktopOwnedDialogModality.DOCUMENT,
            ),
            form,
        )
    if (result != StateNamedChoice.SAVE) return
    val label = labelField.text.trim()
    check(validateNamedStateLabel(label).valid) { "The named-state form submitted invalid text" }
    val requestId = nextRequestId()
    val thumbnail = captureThumbnailOrReport() ?: return
    if (availableSessionId() != expectedSessionId) return
    pendingOperations += requestId
    status.text = "Saving $label…"
    eventBus.post(
        StateSaveRequestEvent(
            requestId,
            expectedSessionId,
            StateRef.Named(UUID.randomUUID()),
            label,
            thumbnail,
        ))
    updateSelection()
  }

  private fun deleteSelected() {
    requireEdt("State delete request")
    val expectedSessionId = availableSessionId() ?: return
    val selected = selectedEntry() ?: return
    if (selected.isEmpty) return
    val choice =
        dialogFactory.showDecision(
            dialog,
            DesktopDecisionSpec(
                title = "Delete state",
                heading = "Delete ${model.name(selected)}?",
                message =
                    "This permanently removes the state from source ${selected.key.sourceIndex + 1}. This action cannot be undone.",
                buttons =
                    DesktopDialogButtons(
                        primary =
                            DesktopDialogAction(
                                "Delete state",
                                StateDeleteChoice.DELETE,
                                destructive = true,
                            ),
                        cancel =
                            DesktopDialogAction(
                                "Keep state",
                                StateDeleteChoice.KEEP,
                            ),
                        defaultButton = DesktopDialogDefaultButton.CANCEL,
                    ),
                modality = DesktopOwnedDialogModality.DOCUMENT,
            ),
        )
    if (choice != StateDeleteChoice.DELETE) return
    if (availableSessionId() != expectedSessionId) return
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Deleting ${model.name(selected)}…"
    eventBus.post(StateDeleteRequestEvent(requestId, expectedSessionId, selected.key))
    updateSelection()
  }

  private fun exportSelected() {
    requireEdt("State export request")
    val expectedSessionId = availableSessionId() ?: return
    val selected = selectedEntry() ?: return
    if (selected.isEmpty) return
    val chooser =
        JFileChooser().apply {
          dialogTitle = "Export ${model.name(selected)}"
          fileFilter = FileNameExtensionFilter("Coffee GB state (*.cgbstate)", "cgbstate")
          selectedFile = java.io.File(model.exportFileName(selected))
        }
    if (chooser.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) return
    if (availableSessionId() != expectedSessionId) return
    var destination = chooser.selectedFile.toPath()
    if (!destination.fileName.toString().endsWith(".cgbstate", ignoreCase = true)) {
      destination =
          destination.resolveSibling(destination.fileName.toString() + ".cgbstate")
    }
    val requestId = nextRequestId()
    pendingOperations += requestId
    status.text = "Exporting ${model.name(selected)}…"
    eventBus.post(
        StateExportRequestEvent(
            requestId,
            expectedSessionId,
            selected.key,
            destination,
        ))
    updateSelection()
  }

  private fun selectedEntry(): StateBrowserEntry? =
      table.selectedRow.takeIf { it >= 0 }?.let(model::entryAt)

  private fun availableSessionId(): Long? =
      session?.takeIf { it.available }?.sessionId

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
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide-state-browser", ::hide)
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "load-selected-state", ::loadSelected)
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-selected-state", ::deleteSelected)
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutMask()),
        "new-named-state",
        ::newNamed,
    )
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh-states") { requestCatalog() }
  }

  private fun publishBounds() {
    if (dialog.width > 0 && dialog.height > 0) {
      onBoundsChanged(Rectangle(dialog.bounds))
    }
  }
}

internal class StateBrowserContentPanel(
    private val preview: JLabel,
    private val detail: JTextArea,
) : JPanel(BorderLayout(8, 8)), DesktopThemeRefreshHook {
  init {
    desktopThemeChanged(DesktopThemeTokens.capture(DesktopAppearance.SYSTEM))
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    val labelFont = UIManager.getFont("Label.font") ?: preview.font
    val detailFont = UIManager.getFont("TextArea.font") ?: labelFont
    preview.border =
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(tokens.border),
            "Preview",
            TitledBorder.LEADING,
            TitledBorder.TOP,
            labelFont,
            tokens.primaryText,
        )
    detail.font = detailFont.deriveFont(Font.PLAIN)
    detail.background = tokens.elevatedSurface
    detail.foreground = tokens.primaryText
    detail.caretColor = tokens.primaryText
    revalidate()
    repaint()
  }
}

internal enum class StateBrowserCompletionAction {
  IGNORE,
  REFRESH_CATALOG,
  UPDATE_SELECTION,
}

internal fun stateBrowserCompletionAction(
    operation: StateOperation,
    ownedByBrowser: Boolean,
): StateBrowserCompletionAction =
    when {
      operation == StateOperation.SAVE -> StateBrowserCompletionAction.REFRESH_CATALOG
      !ownedByBrowser -> StateBrowserCompletionAction.IGNORE
      operation == StateOperation.DELETE -> StateBrowserCompletionAction.REFRESH_CATALOG
      else -> StateBrowserCompletionAction.UPDATE_SELECTION
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
  val owner = desktopDialogOwner(parent)
  DesktopDialogFactory().showError(
      owner,
      DesktopErrorSpec(
          title = "State operation failed",
          summary = error.summary,
          recovery = error.suggestedAction,
          sanitizedDetails = error.detail,
          buttons =
              DesktopDialogButtons(
                  cancel = DesktopDialogAction("Close", Unit),
              ),
      ),
  )
}

private fun showSelectablePath(
    parent: Component,
    title: String,
    message: String,
    path: Path,
) {
  DesktopDialogFactory().showInformation(
      desktopDialogOwner(parent),
      DesktopInformationSpec(
          title = title,
          heading = title,
          description = message,
          contentAccessibleName = "Copyable path",
          buttons =
              DesktopDialogButtons(
                  cancel = DesktopDialogAction("Close", Unit),
              ),
      ),
      selectablePathPanel(message, path),
  )
}

internal fun selectablePathPanel(message: String, path: Path): JPanel {
  val field = JTextField(path.toAbsolutePath().normalize().toString(), 52)
  field.isEditable = false
  field.caretPosition = 0
  field.accessibleContext.accessibleName = "Copyable path"
  val panel = JPanel(BorderLayout(0, 8))
  panel.add(JLabel(message), BorderLayout.NORTH)
  panel.add(field, BorderLayout.CENTER)
  return panel
}

private fun desktopDialogOwner(component: Component): Window =
    (component as? Window)
        ?: checkNotNull(SwingUtilities.getWindowAncestor(component)) {
          "State dialogs require an owned Swing component"
        }

private fun requireEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the Event Dispatch Thread" }
}

private fun onEdt(action: () -> Unit) {
  if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
}

private fun menuShortcutMask(): Int =
    java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
