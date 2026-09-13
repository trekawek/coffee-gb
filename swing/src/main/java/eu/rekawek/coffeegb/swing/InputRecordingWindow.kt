package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.replay.ReplayPlaybackPhase
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingMode
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

internal data class InputRecordingControlState(
    val ejectEnabled: Boolean,
    val playPauseEnabled: Boolean,
    val stopEnabled: Boolean,
    val recordEnabled: Boolean,
    val resetRecordEnabled: Boolean,
)

internal fun inputRecordingControlState(
    presentation: DesktopCommandPresentation,
    replaySelected: Boolean,
): InputRecordingControlState {
  val transportIdle =
      presentation.recordingPhase == ReplayRecordingPhase.IDLE &&
          presentation.inputPlaybackPhase == ReplayPlaybackPhase.IDLE
  val ordinarySessionReady =
      presentation.gameLoaded &&
          !presentation.netplaySession &&
          presentation.stateCommandsAvailable &&
          !presentation.sessionBusy
  val playbackControllable =
      presentation.gameLoaded &&
          !presentation.netplaySession &&
          presentation.inputPlaybackPhase == ReplayPlaybackPhase.PLAYING &&
          !presentation.sessionBusy
  val recordingActive =
      presentation.recordingPhase == ReplayRecordingPhase.ARMING ||
          presentation.recordingPhase == ReplayRecordingPhase.RECORDING
  val playbackActive =
      !presentation.netplaySession && presentation.inputPlaybackPhase != ReplayPlaybackPhase.IDLE
  return InputRecordingControlState(
      ejectEnabled = ordinarySessionReady && transportIdle,
      playPauseEnabled =
          playbackControllable || (ordinarySessionReady && transportIdle && replaySelected),
      stopEnabled =
          (presentation.netplaySession && recordingActive) ||
              (presentation.gameLoaded && (recordingActive || playbackActive)),
      recordEnabled =
          (ordinarySessionReady && transportIdle && !presentation.paused) ||
              (presentation.netplaySession && !presentation.sessionBusy &&
                  ((presentation.gameLoaded && transportIdle) ||
                      presentation.recordingPhase == ReplayRecordingPhase.UNSAVED)),
      resetRecordEnabled = ordinarySessionReady && transportIdle,
  )
}

internal class InputReplaySelection {
  var path: Path? = null
    private set

  fun loadAndPlay(
      selected: Path?,
      onSelected: () -> Unit,
      playReplay: (Path) -> Unit,
  ): Boolean {
    val replay = selected ?: return false
    path = replay
    onSelected()
    playReplay(replay)
    return true
  }

  fun selectRecorded(replay: Path) {
    path = replay
  }
}

/** Modeless, emoji-only tape controls for deterministic input capture and playback. */
internal class InputRecordingWindow(
    private val owner: Window,
    private val chooseReplay: () -> Path?,
    private val playReplay: (Path) -> Unit,
    private val setPlaybackPaused: (Boolean) -> Unit,
    private val stopTransport: () -> Unit,
    private val startRecording: (ReplayRecordingMode) -> Unit,
) : AutoCloseable {
  private val eject = tapeButton("⏏️", "Load input recording")
  private val playPause = tapeButton("⏯️", "Play input recording")
  private val stop = tapeButton("⏹️", "Stop input recording or playback")
  private val record = tapeButton("🔴", "Record from current moment")
  private val resetRecord = tapeButton("🔄️ 🔴", "Reset and record from boot", 82)
  private val dialog =
      JDialog(owner, BASE_TITLE, Dialog.ModalityType.MODELESS).apply {
        defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        contentPane =
            JPanel(FlowLayout(FlowLayout.CENTER, 8, 8)).apply {
              border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
              add(eject)
              add(playPause)
              add(stop)
              add(record)
              add(resetRecord)
            }
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide")
        rootPane.actionMap.put(
            "hide",
            object : AbstractAction() {
              override fun actionPerformed(event: java.awt.event.ActionEvent) {
                isVisible = false
              }
            },
        )
        isResizable = false
        pack()
      }

  private val replaySelection = InputReplaySelection()
  private var presentation = DesktopCommandPresentation()
  private var shown = false

  init {
    requireEdt("Input recording window construction")
    eject.onTapeAction {
      replaySelection.loadAndPlay(
          chooseReplay(),
          onSelected = { render(presentation) },
          playReplay = playReplay,
      )
    }
    playPause.onTapeAction {
      when (presentation.inputPlaybackPhase) {
        ReplayPlaybackPhase.IDLE -> replaySelection.path?.let(playReplay)
        ReplayPlaybackPhase.PLAYING -> setPlaybackPaused(!presentation.paused)
        ReplayPlaybackPhase.LOADING,
        ReplayPlaybackPhase.COMPLETED -> Unit
      }
    }
    stop.onTapeAction(stopTransport)
    record.onTapeAction { startRecording(ReplayRecordingMode.CURRENT_SESSION) }
    resetRecord.onTapeAction { startRecording(ReplayRecordingMode.CLEAN_BOOT) }
    render(presentation)
  }

  fun show() {
    requireEdt("Input recording window opening")
    if (!shown) {
      dialog.setLocationRelativeTo(dialog.owner)
      shown = true
    }
    dialog.isVisible = true
    dialog.toFront()
  }

  fun render(next: DesktopCommandPresentation) {
    requireEdt("Input recording window update")
    presentation = next
    val controls = inputRecordingControlState(next, replaySelection.path != null)
    eject.isEnabled = controls.ejectEnabled
    playPause.isEnabled = controls.playPauseEnabled
    stop.isEnabled = controls.stopEnabled
    record.isEnabled = controls.recordEnabled
    resetRecord.isEnabled = controls.resetRecordEnabled
    record.toolTipText = when {
      next.netplaySession && next.recordingPhase == ReplayRecordingPhase.UNSAVED ->
          "Save retained netplay input log to another file"
      next.netplaySession -> "Record all netplay players and timing to a diagnostic log"
      else -> "Record from current moment"
    }
    record.accessibleContext.accessibleName = record.toolTipText
    resetRecord.toolTipText = if (next.netplaySession) {
      "Reset and record is available for local games"
    } else "Reset and record from boot"
    playPause.toolTipText =
        when {
          next.inputPlaybackPhase != ReplayPlaybackPhase.PLAYING ->
              "Play input recording from beginning"
          next.paused -> "Resume input playback"
          else -> "Pause input playback"
        }
    dialog.title = if (next.netplaySession) "$BASE_TITLE — Netplay log" else
        replaySelection.path?.fileName?.let { "$BASE_TITLE — $it" } ?: BASE_TITLE
  }

  fun selectRecordedReplay(path: Path) {
    requireEdt("Recorded input replay selection")
    replaySelection.selectRecorded(path)
    render(presentation)
  }

  override fun close() {
    requireEdt("Input recording window closing")
    dialog.dispose()
  }

  private fun JButton.onTapeAction(action: () -> Unit) {
    addActionListener {
      try {
        action()
      } finally {
        restoreEmulatorFocus()
      }
    }
  }

  private fun restoreEmulatorFocus() {
    SwingUtilities.invokeLater {
      if (owner.isDisplayable) {
        owner.toFront()
        owner.requestFocus()
      }
    }
  }

  private companion object {
    const val BASE_TITLE = "Input Recording"

    fun tapeButton(emoji: String, accessibleName: String, width: Int = 54): JButton =
        JButton(emoji).apply {
          preferredSize = Dimension(width, 46)
          minimumSize = preferredSize
          toolTipText = accessibleName
          accessibleContext.accessibleName = accessibleName
        }

    fun requireEdt(operation: String) {
      check(SwingUtilities.isEventDispatchThread()) {
        "$operation must run on the Event Dispatch Thread"
      }
    }
  }
}
