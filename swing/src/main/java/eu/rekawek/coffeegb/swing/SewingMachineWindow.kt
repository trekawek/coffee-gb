package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.SewingAction
import eu.rekawek.coffeegb.controller.Controller.SewingSnapshot
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/** Modeless: the guest continues transferring and the simulated motor continues stitching. */
internal class SewingMachineWindow(private val owner: Window, private val bus: EventBus) {
  private var dialog: JDialog? = null
  private var generation: Long? = null

  fun sessionChanged() { dialog?.dispose(); dialog = null }

  fun show(sessionGeneration: Long?) {
    dialog?.let { it.toFront(); return }
    generation = sessionGeneration
    val panel = SewingMachinePanel()
    val window = JDialog(owner, "Sewing machine", Dialog.ModalityType.MODELESS)
    dialog = window
    var pending = false
    var closed = false
    fun request(action: SewingAction, value: Int = 0) {
      if (closed) return
      if (action == SewingAction.SNAPSHOT && pending) return
      val event = Controller.SewingControlEvent(action, value, generation)
      if (action == SewingAction.SNAPSHOT) pending = true
      bus.post(event)
      event.completion.whenComplete { snapshot, error ->
        SwingUtilities.invokeLater {
          if (action == SewingAction.SNAPSHOT) pending = false
          if (!closed) {
            if (error != null) panel.status.text = error.cause?.message ?: error.message
            else panel.render(snapshot)
          }
        }
      }
    }
    panel.command = ::request
    panel.export = {
      val chooser = JFileChooser().apply {
        dialogTitle = "Export stitched fabric"
        fileFilter = FileNameExtensionFilter("PNG image", "png")
        selectedFile = java.io.File("stitched-fabric.png")
      }
      if (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile.let { if (it.name.endsWith(".png", true)) it else java.io.File(it.path + ".png") }
        if (!file.exists() || JOptionPane.showConfirmDialog(window, "Replace ${file.name}?", "Export fabric",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
          val copy = panel.copyImage()
          object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() { check(ImageIO.write(copy, "png", file)) }
            override fun done() {
              runCatching { get() }.onFailure { panel.status.text = "Could not export fabric: ${it.cause?.message ?: it.message}" }
            }
          }.execute()
        }
      }
    }
    val timer = Timer(150) { request(SewingAction.SNAPSHOT) }
    window.addWindowListener(object : WindowAdapter() {
      override fun windowClosed(e: WindowEvent) {
        closed = true; timer.stop()
        // Releasing the tool also releases its foot control, scoped to the original game.
        bus.post(Controller.SewingControlEvent(SewingAction.PEDAL, 0, sessionGeneration))
        if (dialog === window) dialog = null
      }
    })
    window.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    window.contentPane = panel
    window.pack(); window.setLocationRelativeTo(owner); window.isVisible = true
    request(SewingAction.SNAPSHOT); timer.start()
  }
}

internal class SewingMachinePanel : JPanel(BorderLayout(12, 12)) {
  var command: (SewingAction, Int) -> Unit = { _, _ -> }
  var export: () -> Unit = {}
  val status = JLabel("Waiting for the sewing machine…")
  internal val model = JComboBox(arrayOf("Singer IZEK-1500", "Jaguar JN-100", "Jaguar JN-2000"))
  internal val arm = JCheckBox("EM-2000 embroidery arm")
  internal val hoop = JCheckBox("Large embroidery hoop")
  internal val pedal = JCheckBox("Hold foot pedal")
  internal val paused = JCheckBox("Pause stitching")
  private val speed = JSpinner(SpinnerNumberModel(60, 1, 600, 10))
  private val canvas = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
  private val preview = JLabel(ImageIcon(canvas))
  private var updating = false
  init {
    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
    val controls = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    fun button(label: String, action: SewingAction) = JButton(label).apply {
      alignmentX = Component.LEFT_ALIGNMENT
      addActionListener { command(action, 0) }; controls.add(this)
    }
    controls.add(JLabel("Machine")); controls.add(model); controls.add(arm); controls.add(hoop)
    controls.add(Box.createVerticalStrut(12)); controls.add(pedal); controls.add(paused)
    button("Stitch once", SewingAction.STEP)
    button("SW: next embroidery section", SewingAction.ADVANCE)
    controls.add(Box.createVerticalStrut(12)); controls.add(JLabel("Simulated stitches / second")); controls.add(speed)
    controls.add(JButton("Thread color…").apply {
      addActionListener { JColorChooser.showDialog(this@SewingMachinePanel, "Thread color", Color.BLUE)?.let { command(SewingAction.COLOR, it.rgb) } }
    })
    button("Clear fabric", SewingAction.CLEAR)
    controls.add(JButton("Export PNG…").apply { addActionListener { export() } })
    model.addActionListener { if (!updating) command(SewingAction.MODEL, model.selectedIndex) }
    arm.addActionListener { if (!updating) command(SewingAction.ARM, if (arm.isSelected) 1 else 0) }
    hoop.addActionListener { if (!updating) command(SewingAction.HOOP, if (hoop.isSelected) 1 else 0) }
    pedal.addActionListener { if (!updating) command(SewingAction.PEDAL, if (pedal.isSelected) 1 else 0) }
    paused.addActionListener { if (!updating) command(SewingAction.PAUSE, if (paused.isSelected) 1 else 0) }
    speed.addChangeListener { if (!updating) command(SewingAction.SPEED, (speed.value as Number).toInt()) }
    controls.components.filterIsInstance<JComponent>().forEach {
      it.alignmentX = Component.LEFT_ALIGNMENT
      it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height)
    }
    controls.add(Box.createVerticalGlue())
    add(controls, BorderLayout.WEST); add(JScrollPane(preview), BorderLayout.CENTER); add(status, BorderLayout.SOUTH)
    getAccessibleContext().accessibleName = "Sewing machine controls and stitched fabric preview"
  }
  fun render(snapshot: SewingSnapshot) {
    updating = true
    try {
      model.selectedIndex = snapshot.model; arm.isSelected = snapshot.arm; arm.isEnabled = snapshot.model == 2
      hoop.isSelected = snapshot.largeHoop; hoop.isEnabled = snapshot.arm
      pedal.isSelected = snapshot.pedal; paused.isSelected = snapshot.paused; speed.value = snapshot.speed
      canvas.setRGB(0, 0, 512, 512, snapshot.pixels, 0, 512); preview.repaint()
      status.text = "${snapshot.stitches} stitches" + if (snapshot.finished) " · Section finished" else ""
    } finally { updating = false }
  }
  fun copyImage(): BufferedImage = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB).also {
    it.setData(canvas.data)
  }
}
