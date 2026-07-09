package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.PrinterPrintEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * A window that collects the bands printed on the emulated Game Boy Printer and shows them as a
 * continuous paper roll, the way the real printer feeds paper. Each [PrinterPrintEvent] appends
 * a band (plus the paper feed its margins request); the roll can be saved to a PNG or cleared.
 * The window and its Swing widgets are created lazily the first time something is printed.
 */
class SwingPrinter(eventBus: EventBus) {

  private val scale = 2

  // pixels of paper feed per margin unit (cosmetic; keeps successive sheets apart)
  private val marginPixels = 3

  private var paper: BufferedImage = BufferedImage(PAPER_WIDTH, 1, BufferedImage.TYPE_INT_RGB)
  private var contentHeight = 0

  private var frame: JFrame? = null
  private var canvas: JPanel? = null
  private var scrollPane: JScrollPane? = null

  init {
    eventBus.register<PrinterPrintEvent> { event -> SwingUtilities.invokeLater { append(event) } }
  }

  private fun ensureUi() {
    if (frame != null) {
      return
    }
    val canvas =
        object : JPanel() {
          override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (contentHeight == 0) {
              return
            }
            (g as Graphics2D).setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(
                paper, 0, 0, PAPER_WIDTH * scale, contentHeight * scale, 0, 0, PAPER_WIDTH, contentHeight, null)
          }

          override fun getPreferredSize(): Dimension =
              Dimension(PAPER_WIDTH * scale, maxOf(1, contentHeight) * scale)
        }
    canvas.background = Color.LIGHT_GRAY
    this.canvas = canvas

    val scrollPane = JScrollPane(canvas)
    scrollPane.preferredSize = Dimension(PAPER_WIDTH * scale + 30, 320)
    this.scrollPane = scrollPane

    val save = JButton("Save as PNG…")
    save.addActionListener { save() }
    val clear = JButton("Clear")
    clear.addActionListener { clear() }
    val toolbar = JPanel()
    toolbar.add(save)
    toolbar.add(clear)

    val frame = JFrame("Game Boy Printer")
    frame.layout = BorderLayout()
    frame.add(toolbar, BorderLayout.NORTH)
    frame.add(scrollPane, BorderLayout.CENTER)
    frame.defaultCloseOperation = JFrame.HIDE_ON_CLOSE
    frame.pack()
    this.frame = frame
  }

  private fun append(event: PrinterPrintEvent) {
    ensureUi()
    val top = event.topMargin * marginPixels
    val bottom = event.bottomMargin * marginPixels
    val added = top + event.height + bottom
    grow(contentHeight + added)

    var y = contentHeight
    fillWhite(y, top)
    y += top
    for (row in 0 until event.height) {
      for (x in 0 until PAPER_WIDTH) {
        paper.setRGB(x, y + row, event.argb[row * event.width + x] and 0xFFFFFF)
      }
    }
    y += event.height
    fillWhite(y, bottom)
    contentHeight += added

    canvas?.revalidate()
    canvas?.repaint()
    val frame = frame!!
    if (!frame.isVisible) {
      frame.setLocationRelativeTo(null)
      frame.isVisible = true
    }
    frame.toFront()
    // keep the newest band in view
    SwingUtilities.invokeLater {
      val bar = scrollPane?.verticalScrollBar ?: return@invokeLater
      bar.value = bar.maximum
    }
  }

  private fun fillWhite(y: Int, height: Int) {
    for (i in 0 until height) {
      for (x in 0 until PAPER_WIDTH) {
        paper.setRGB(x, y + i, 0xFFFFFF)
      }
    }
  }

  private fun grow(minHeight: Int) {
    if (paper.height >= minHeight) {
      return
    }
    val taller = BufferedImage(PAPER_WIDTH, minHeight, BufferedImage.TYPE_INT_RGB)
    val g = taller.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, PAPER_WIDTH, minHeight)
    g.drawImage(paper, 0, 0, null)
    g.dispose()
    paper = taller
  }

  private fun clear() {
    paper = BufferedImage(PAPER_WIDTH, 1, BufferedImage.TYPE_INT_RGB)
    contentHeight = 0
    canvas?.revalidate()
    canvas?.repaint()
  }

  private fun save() {
    if (contentHeight == 0) {
      JOptionPane.showMessageDialog(
          frame,
          "Nothing has been printed yet.",
          "Game Boy Printer",
          JOptionPane.INFORMATION_MESSAGE)
      return
    }
    val chooser = JFileChooser()
    chooser.dialogTitle = "Save printout"
    chooser.fileFilter = FileNameExtensionFilter("PNG image", "png")
    chooser.selectedFile = File("printout.png")
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
      return
    }
    var file = chooser.selectedFile
    if (!file.name.lowercase().endsWith(".png")) {
      file = File(file.parentFile, file.name + ".png")
    }
    try {
      ImageIO.write(paper.getSubimage(0, 0, PAPER_WIDTH, contentHeight), "png", file)
    } catch (e: Exception) {
      JOptionPane.showMessageDialog(
          frame,
          "Couldn't save the image: ${e.message}",
          "Game Boy Printer",
          JOptionPane.ERROR_MESSAGE)
    }
  }

  companion object {
    private const val PAPER_WIDTH = 160
  }
}
