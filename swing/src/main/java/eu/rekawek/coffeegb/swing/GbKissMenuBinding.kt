package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.GbKissCancelRequest
import eu.rekawek.coffeegb.controller.GbKissProgressEvent
import eu.rekawek.coffeegb.controller.GbKissReceivedEvent
import eu.rekawek.coffeegb.controller.GbKissTransferRequest
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.ir.GbfFile
import eu.rekawek.coffeegb.core.ir.GbKissLink
import java.awt.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFileChooser
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.filechooser.FileNameExtensionFilter

/** Desktop file dialogs and I/O stay outside the emulation owner. */
internal class GbKissMenuBinding(
    private val owner: Component,
    private val eventBus: EventBus,
    private val onStatus: (String) -> Unit,
) {
  val menu = JMenu("GBKiss Link")
  private val send = JMenuItem("Send GBF file")
  private val receive = JMenuItem("Receive GBF file")
  private val cancel = JMenuItem("Cancel transfer")
  private val status = JMenuItem("Select Send or Receive in the game's GBKiss menu.")
  private var requestId: Long? = null
  private var pendingFile: GbfFile? = null

  init {
    menu.add(send)
    menu.add(receive)
    menu.add(cancel)
    menu.addSeparator()
    menu.add(status)
    status.isEnabled = false
    cancel.isEnabled = false
    send.addActionListener { chooseSend() }
    receive.addActionListener {
      // A received file retained after a cancelled save dialog remains available for export.
      pendingFile?.let { saveReceived(it) } ?: begin(null)
    }
    cancel.addActionListener { requestId?.let { eventBus.post(GbKissCancelRequest(it)) } }
    eventBus.register<GbKissProgressEvent> { event ->
      SwingUtilities.invokeLater {
        if (event.requestId != requestId) return@invokeLater
        val progress = event.progress
        status.text = progress.message()
        onStatus(progress.message())
        val active = progress.status() == GbKissLink.Status.WAITING ||
            progress.status() == GbKissLink.Status.TRANSFERRING
        send.isEnabled = !active
        receive.isEnabled = !active
        cancel.isEnabled = active
      }
    }
    eventBus.register<GbKissReceivedEvent> { event ->
      SwingUtilities.invokeLater {
        if (event.requestId != requestId) return@invokeLater
        pendingFile = event.file
        saveReceived(event.file)
      }
    }
  }

  private fun chooser(title: String) = JFileChooser().apply {
    dialogTitle = title
    fileFilter = FileNameExtensionFilter("GBKiss files (*.gbf)", "gbf")
    isAcceptAllFileFilterUsed = false
  }

  private fun chooseSend() {
    val chooser = chooser("Send GBF file")
    if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
    val path = chooser.selectedFile.toPath()
    send.isEnabled = false
    receive.isEnabled = false
    object : SwingWorker<GbfFile, Unit>() {
      override fun doInBackground(): GbfFile = Files.newInputStream(path).use { input ->
        GbfFile(input.readNBytes(GbfFile.MAX_SIZE + 1))
      }

      override fun done() {
        try {
          begin(get())
        } catch (failure: Exception) {
          send.isEnabled = true
          receive.isEnabled = true
          onStatus("Cannot send GBF file: ${failure.cause?.message ?: failure.message}")
        }
      }
    }.execute()
  }

  private fun begin(file: GbfFile?) {
    val id = ids.incrementAndGet()
    requestId = id
    send.isEnabled = false
    receive.isEnabled = false
    cancel.isEnabled = true
    eventBus.post(GbKissTransferRequest(id, file))
  }

  private fun saveReceived(file: GbfFile) {
    val chooser = chooser("Save received GBF file")
    chooser.selectedFile = File("received.gbf")
    if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
      onStatus("Received GBF file kept in memory. Select Receive GBF file to save it.")
      return
    }
    val selected = chooser.selectedFile
    val destination = if (selected.extension.equals("gbf", ignoreCase = true)) selected
        else File(selected.parentFile, "${selected.name}.gbf")
    object : SwingWorker<Unit, Unit>() {
      override fun doInBackground() {
        Files.write(destination.toPath(), file.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      }

      override fun done() {
        try {
          get()
          if (pendingFile === file) pendingFile = null
          onStatus("GBF file saved to ${destination.name}.")
        } catch (failure: Exception) {
          onStatus("Could not save GBF file. Choose a new filename with Receive GBF file to try again.")
        }
      }
    }.execute()
  }

  companion object { private val ids = AtomicLong() }
}
