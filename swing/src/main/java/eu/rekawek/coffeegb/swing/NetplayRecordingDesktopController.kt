package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingRetryEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStartEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStatusEvent
import eu.rekawek.coffeegb.controller.replay.NetplayRecordingStopEvent
import eu.rekawek.coffeegb.controller.replay.ReplayRecordingPhase
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.Window
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** The selected destination is known before capture, so disconnect/quit can also save the log. */
internal class NetplayRecordingDesktopController(
    private val owner: Window?,
    rootEventBus: EventBus,
    private val onStatus: (NetplayRecordingStatusEvent) -> Unit,
    private val onMessage: (String) -> Unit,
    private val choosePath: () -> Path? = {
      val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      val chooser = JFileChooser().apply {
        dialogTitle = "Save netplay input log"
        fileFilter = FileNameExtensionFilter("Netplay input log (*.jsonl)", "jsonl")
        selectedFile = java.io.File("coffee-gb-netplay-$stamp.jsonl")
      }
      if (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
        val selected = chooser.selectedFile.toPath()
        if (selected.fileName.toString().endsWith(".jsonl", ignoreCase = true)) selected
        else selected.resolveSibling("${selected.fileName}.jsonl")
      } else null
    },
) : AutoCloseable {
  private val eventBus = rootEventBus.fork("desktop-netplay-recording")
  private var latest: NetplayRecordingStatusEvent? = null
  private var closed = false
  val isLinked: Boolean get() = latest?.available == true

  init {
    eventBus.register<NetplayRecordingStatusEvent> { event ->
      SwingUtilities.invokeLater {
        if (!closed && event.sessionId >= (latest?.sessionId ?: 0)) {
          latest = event
          onStatus(event)
          when {
            event.savedPath != null -> onMessage("Netplay input log saved: ${event.savedPath}")
            event.error != null -> onMessage("${event.error} Press Record to retry saving.")
            event.phase == ReplayRecordingPhase.RECORDING ->
                onMessage("Recording all netplay players. Press Stop in Input Recording to save the log.")
            event.phase == ReplayRecordingPhase.SAVING -> onMessage("Saving netplay input log…")
          }
        }
      }
    }
  }

  fun start() {
    val current = latest?.takeIf { it.available } ?: return
    if (current.phase != ReplayRecordingPhase.IDLE &&
        current.phase != ReplayRecordingPhase.UNSAVED) return
    val path = choosePath() ?: return
    // A chooser runs a nested EDT loop. Never send its result to a replacement session.
    if (latest != current) return
    if (current.phase == ReplayRecordingPhase.UNSAVED) {
      eventBus.post(NetplayRecordingRetryEvent(current.sessionId, path))
    } else {
      eventBus.post(NetplayRecordingStartEvent(current.sessionId, path))
    }
  }

  fun stop() {
    latest?.takeIf { it.available && it.phase == ReplayRecordingPhase.RECORDING }?.let {
      eventBus.post(NetplayRecordingStopEvent(it.sessionId))
    }
  }

  override fun close() {
    closed = true
    eventBus.close()
  }
}
