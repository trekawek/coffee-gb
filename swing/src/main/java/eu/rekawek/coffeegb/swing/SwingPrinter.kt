package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.PrinterPrintEvent
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SetPrinterEvent
import eu.rekawek.coffeegb.controller.Controller.SetSerialPeripheralEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.min

internal const val PRINTER_PAPER_WIDTH = 160
internal const val PRINTER_PAPER_MAX_DECODED_PIXELS = 8_388_608L
internal const val PRINTER_PAPER_MAX_AGGREGATE_PIXELS = 16_777_216L
internal const val PRINTER_MARGIN_PIXELS_PER_UNIT = 3

/** Why an otherwise protocol-valid print callback was not added to the visible paper roll. */
internal enum class PrinterPaperOmissionReason {
  CAPACITY,
  INVALID_STRIP,
}

/** Result of one immutable [PrinterPaperModel] transition. */
internal data class PrinterPaperAppendResult(
    val model: PrinterPaperModel,
    val accepted: Boolean,
    val omissionReason: PrinterPaperOmissionReason? = null,
) {
  init {
    require(accepted == (omissionReason == null))
  }
}

/**
 * One decoded strip, including its paper-feed margins.
 *
 * The image is private and is never mutated after construction. Painting and export therefore may
 * safely share the same segment reference without cloning a whole paper generation.
 */
internal class PrinterPaperSegment private constructor(
    val height: Int,
    private val image: BufferedImage,
) {
  val decodedPixels: Long = Math.multiplyExact(PRINTER_PAPER_WIDTH.toLong(), height.toLong())

  fun paint(graphics: Graphics2D, top: Int, scale: Int) {
    val destinationTop = Math.multiplyExact(top, scale)
    val destinationHeight = Math.multiplyExact(height, scale)
    graphics.drawImage(
        image,
        0,
        destinationTop,
        PRINTER_PAPER_WIDTH * scale,
        destinationTop + destinationHeight,
        0,
        0,
        PRINTER_PAPER_WIDTH,
        height,
        null,
    )
  }

  fun copyRgbRow(row: Int, destination: IntArray) {
    require(row in 0 until height)
    require(destination.size >= PRINTER_PAPER_WIDTH)
    image.getRGB(0, row, PRINTER_PAPER_WIDTH, 1, destination, 0, PRINTER_PAPER_WIDTH)
  }

  fun rgbAt(x: Int, y: Int): Int {
    require(x in 0 until PRINTER_PAPER_WIDTH)
    require(y in 0 until height)
    return image.getRGB(x, y) and 0xFFFFFF
  }

  companion object {
    fun decode(event: PrinterPrintEvent, plan: PrinterStripPlan): PrinterPaperSegment {
      val image = BufferedImage(PRINTER_PAPER_WIDTH, plan.totalHeight, BufferedImage.TYPE_INT_RGB)
      val decoded = (image.raster.dataBuffer as DataBufferInt).data
      Arrays.fill(decoded, PAPER_WHITE)
      val destinationOffset = Math.multiplyExact(plan.topMarginHeight, PRINTER_PAPER_WIDTH)
      for (index in 0 until plan.sourcePixelCount) {
        decoded[destinationOffset + index] = event.argb[index] and 0xFFFFFF
      }
      return PrinterPaperSegment(plan.totalHeight, image)
    }
  }
}

private data class PrinterPaperNode(
    val segment: PrinterPaperSegment,
    val previous: PrinterPaperNode?,
)

/** A stable top-to-bottom list of immutable segment references for exactly one generation. */
internal class PrinterPaperSnapshot internal constructor(
    val generation: Long,
    val contentHeight: Int,
    val decodedPixels: Long,
    segments: List<PrinterPaperSegment>,
) {
  internal val segments: List<PrinterPaperSegment> =
      Collections.unmodifiableList(ArrayList(segments))

  val isEmpty: Boolean
    get() = segments.isEmpty()

  fun rgbAt(x: Int, y: Int): Int {
    require(x in 0 until PRINTER_PAPER_WIDTH)
    require(y in 0 until contentHeight)
    var remaining = y
    for (segment in segments) {
      if (remaining < segment.height) return segment.rgbAt(x, remaining)
      remaining -= segment.height
    }
    error("Paper snapshot height does not match its segments")
  }

  /**
   * Creates the bounded immutable preview consumed by the portable Proposal 3 menu.
   *
   * The printer keeps its native 160-pixel roll width. A short roll is copied at native
   * resolution; a taller roll is reduced to the largest aspect-preserving raster that fits the
   * portable preview budget. MenuPreview defensively copies the generated ARGB array, so neither
   * this snapshot nor the producer-owned row buffer can be mutated by the renderer.
   */
  internal fun menuPreview(): MenuPreview {
    if (isEmpty || contentHeight <= 0) return MenuPreview.empty()

    val maximumHeight = MenuPreview.MAX_PIXELS / PRINTER_PAPER_WIDTH
    val previewHeight = min(contentHeight, maximumHeight)
    val previewWidth =
        if (contentHeight <= maximumHeight) {
          PRINTER_PAPER_WIDTH
        } else {
          (PRINTER_PAPER_WIDTH.toLong() * previewHeight / contentHeight)
              .toInt()
              .coerceAtLeast(1)
        }
    val previewPixels = IntArray(Math.multiplyExact(previewWidth, previewHeight))
    val sourceRow = IntArray(PRINTER_PAPER_WIDTH)
    var copiedSourceRow = -1
    for (destinationY in 0 until previewHeight) {
      val sourceY =
          (destinationY.toLong() * contentHeight / previewHeight)
              .toInt()
      if (sourceY != copiedSourceRow) {
        copyRgbRow(sourceY, sourceRow)
        copiedSourceRow = sourceY
      }
      val destinationOffset = destinationY * previewWidth
      for (destinationX in 0 until previewWidth) {
        val sourceX =
            (destinationX.toLong() * PRINTER_PAPER_WIDTH / previewWidth)
                .toInt()
        previewPixels[destinationOffset + destinationX] =
            0xff000000.toInt() or (sourceRow[sourceX] and 0x00ffffff)
      }
    }
    return MenuPreview.ready(previewWidth, previewHeight, previewPixels)
  }

  private fun copyRgbRow(row: Int, destination: IntArray) {
    require(row in 0 until contentHeight)
    require(destination.size >= PRINTER_PAPER_WIDTH)
    var remaining = row
    for (segment in segments) {
      if (remaining < segment.height) {
        segment.copyRgbRow(remaining, destination)
        return
      }
      remaining -= segment.height
    }
    error("Paper snapshot height does not match its segments")
  }
}

/**
 * Segmented immutable paper state.
 *
 * Accepted appends allocate only the new decoded segment and one linked metadata node. Capacity or
 * malformed input creates a new small metadata snapshot while retaining the exact prior paper.
 */
internal class PrinterPaperModel private constructor(
    private val tail: PrinterPaperNode?,
    val segmentCount: Int,
    val contentHeight: Int,
    val decodedPixels: Long,
    val omittedStripCount: Long,
    val rollFull: Boolean,
    val generation: Long,
    private val maximumDecodedPixels: Long,
) {
  val hasContent: Boolean
    get() = segmentCount > 0

  fun append(
      event: PrinterPrintEvent,
      availableDecodedPixels: Long = maximumDecodedPixels,
  ): PrinterPaperAppendResult {
    val plan = PrinterStripPlan.from(event) ?: return omitted(PrinterPaperOmissionReason.INVALID_STRIP)
    val effectiveLimit = min(maximumDecodedPixels, availableDecodedPixels.coerceAtLeast(0L))
    if (decodedPixels > effectiveLimit || plan.decodedPixels > effectiveLimit - decodedPixels) {
      return omitted(PrinterPaperOmissionReason.CAPACITY)
    }
    val nextHeight =
        try {
          Math.addExact(contentHeight, plan.totalHeight)
        } catch (_: ArithmeticException) {
          return omitted(PrinterPaperOmissionReason.CAPACITY)
        }
    val nextSegmentCount =
        try {
          Math.addExact(segmentCount, 1)
        } catch (_: ArithmeticException) {
          return omitted(PrinterPaperOmissionReason.CAPACITY)
        }
    val segment = PrinterPaperSegment.decode(event, plan)
    val next =
        PrinterPaperModel(
            tail = PrinterPaperNode(segment, tail),
            segmentCount = nextSegmentCount,
            contentHeight = nextHeight,
            decodedPixels = decodedPixels + plan.decodedPixels,
            omittedStripCount = omittedStripCount,
            rollFull = rollFull,
            generation = nextGeneration(),
            maximumDecodedPixels = maximumDecodedPixels,
        )
    return PrinterPaperAppendResult(next, accepted = true)
  }

  fun clear(): PrinterPaperModel {
    if (!hasContent && omittedStripCount == 0L) return this
    return empty(maximumDecodedPixels, nextGeneration())
  }

  fun snapshot(): PrinterPaperSnapshot {
    val ordered = arrayOfNulls<PrinterPaperSegment>(segmentCount)
    var index = segmentCount - 1
    var node = tail
    while (node != null) {
      ordered[index--] = node.segment
      node = node.previous
    }
    check(index == -1) { "Paper segment count does not match its immutable chain" }
    @Suppress("UNCHECKED_CAST")
    val segments = ordered.asList() as List<PrinterPaperSegment>
    return PrinterPaperSnapshot(generation, contentHeight, decodedPixels, segments)
  }

  fun paint(graphics: Graphics2D, scale: Int) {
    require(scale > 0)
    var bottom = contentHeight
    var node = tail
    while (node != null) {
      bottom -= node.segment.height
      node.segment.paint(graphics, bottom, scale)
      node = node.previous
    }
    check(bottom == 0) { "Paper height does not match its immutable segment chain" }
  }

  private fun omitted(reason: PrinterPaperOmissionReason): PrinterPaperAppendResult {
    val count =
        if (omittedStripCount == Long.MAX_VALUE) Long.MAX_VALUE else omittedStripCount + 1L
    val next =
        PrinterPaperModel(
            tail = tail,
            segmentCount = segmentCount,
            contentHeight = contentHeight,
            decodedPixels = decodedPixels,
            omittedStripCount = count,
            rollFull = rollFull || reason == PrinterPaperOmissionReason.CAPACITY,
            generation = nextGeneration(),
            maximumDecodedPixels = maximumDecodedPixels,
        )
    return PrinterPaperAppendResult(next, accepted = false, omissionReason = reason)
  }

  private fun nextGeneration(): Long = Math.addExact(generation, 1L)

  companion object {
    fun empty(
        maximumDecodedPixels: Long = PRINTER_PAPER_MAX_DECODED_PIXELS,
        generation: Long = 0L,
    ): PrinterPaperModel {
      require(maximumDecodedPixels in 1L..PRINTER_PAPER_MAX_DECODED_PIXELS)
      require(generation >= 0L)
      return PrinterPaperModel(
          tail = null,
          segmentCount = 0,
          contentHeight = 0,
          decodedPixels = 0L,
          omittedStripCount = 0L,
          rollFull = false,
          generation = generation,
          maximumDecodedPixels = maximumDecodedPixels,
      )
    }
  }
}

internal class PrinterStripPlan private constructor(
    val topMarginHeight: Int,
    val totalHeight: Int,
    val sourcePixelCount: Int,
    val decodedPixels: Long,
) {
  companion object {
    fun from(event: PrinterPrintEvent): PrinterStripPlan? {
      if (
          event.width != PRINTER_PAPER_WIDTH ||
              event.height < 0 ||
              event.topMargin < 0 ||
              event.bottomMargin < 0
      ) {
        return null
      }
      return try {
        val sourcePixels = Math.multiplyExact(event.width, event.height)
        if (sourcePixels != event.argb.size) return null
        val top = Math.multiplyExact(event.topMargin, PRINTER_MARGIN_PIXELS_PER_UNIT)
        val bottom = Math.multiplyExact(event.bottomMargin, PRINTER_MARGIN_PIXELS_PER_UNIT)
        val total = Math.addExact(Math.addExact(top, event.height), bottom)
        if (total <= 0) return null
        val decodedPixels = Math.multiplyExact(PRINTER_PAPER_WIDTH.toLong(), total.toLong())
        PrinterStripPlan(top, total, sourcePixels, decodedPixels)
      } catch (_: ArithmeticException) {
        null
      }
    }
  }
}

internal enum class PrinterExportUnavailableReason {
  EMPTY,
  BUSY,
  RETENTION_LIMIT,
}

internal data class PrinterExportLeaseAttempt(
    val lease: PrinterExportLease? = null,
    val unavailableReason: PrinterExportUnavailableReason? = null,
) {
  init {
    require((lease == null) xor (unavailableReason == null))
  }
}

/** Idempotent single-flight ownership of a stable paper generation. */
internal class PrinterExportLease internal constructor(
    val snapshot: PrinterPaperSnapshot,
    private val release: () -> Unit,
) : AutoCloseable {
  private val closed = AtomicBoolean()

  override fun close() {
    if (closed.compareAndSet(false, true)) release()
  }
}

/**
 * Synchronized owner of the current model and its one possible old-generation export lease.
 *
 * The accounting intentionally counts current and leased decoded pixels separately, even when
 * their immutable segment references overlap. This conservative rule makes the aggregate bound
 * obvious and keeps clear-then-print behavior bounded while an old export is still running.
 */
internal class PrinterPaperStore(
    maximumCurrentPixels: Long = PRINTER_PAPER_MAX_DECODED_PIXELS,
    private val maximumAggregatePixels: Long = PRINTER_PAPER_MAX_AGGREGATE_PIXELS,
) {
  private var current = PrinterPaperModel.empty(maximumCurrentPixels)
  private var activeLease: ActivePrinterLease? = null
  private var leaseSequence = 0L

  init {
    require(maximumAggregatePixels >= maximumCurrentPixels)
    require(maximumAggregatePixels <= PRINTER_PAPER_MAX_AGGREGATE_PIXELS)
  }

  @Synchronized
  fun append(event: PrinterPrintEvent): PrinterPaperAppendResult {
    val leasedPixels = activeLease?.decodedPixels ?: 0L
    val aggregateAvailable = maximumAggregatePixels - leasedPixels
    val result = current.append(event, aggregateAvailable)
    current = result.model
    return result
  }

  @Synchronized
  fun model(): PrinterPaperModel = current

  @Synchronized
  fun clear(): PrinterPaperModel {
    current = current.clear()
    return current
  }

  @Synchronized
  fun acquireExportLease(): PrinterExportLeaseAttempt {
    if (!current.hasContent) {
      return PrinterExportLeaseAttempt(unavailableReason = PrinterExportUnavailableReason.EMPTY)
    }
    if (activeLease != null) {
      return PrinterExportLeaseAttempt(unavailableReason = PrinterExportUnavailableReason.BUSY)
    }
    if (current.decodedPixels > maximumAggregatePixels - current.decodedPixels) {
      return PrinterExportLeaseAttempt(
          unavailableReason = PrinterExportUnavailableReason.RETENTION_LIMIT)
    }
    val id = Math.addExact(leaseSequence, 1L)
    leaseSequence = id
    val snapshot = current.snapshot()
    activeLease = ActivePrinterLease(id, snapshot.decodedPixels)
    return PrinterExportLeaseAttempt(
        lease = PrinterExportLease(snapshot) { releaseLease(id) },
    )
  }

  @Synchronized
  fun hasActiveExport(): Boolean = activeLease != null

  @Synchronized
  private fun releaseLease(id: Long) {
    if (activeLease?.id == id) activeLease = null
  }

  private data class ActivePrinterLease(val id: Long, val decodedPixels: Long)
}

/** Writes one stable paper generation without ever composing the complete roll in memory. */
internal fun interface PrinterPngWriter {
  fun write(snapshot: PrinterPaperSnapshot, destination: Path)
}

internal object StreamingPrinterPngWriter : PrinterPngWriter {
  override fun write(snapshot: PrinterPaperSnapshot, destination: Path) {
    require(!snapshot.isEmpty) { "An empty paper roll cannot be exported" }
    val target = destination.toAbsolutePath()
    val directory = requireNotNull(target.parent) { "The export destination needs a parent" }
    val temporary = Files.createTempFile(directory, ".coffee-gb-printer-", ".png")
    var moved = false
    try {
      writeDirect(snapshot, temporary)
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
      moved = true
    } finally {
      if (!moved) Files.deleteIfExists(temporary)
    }
  }

  private fun writeDirect(snapshot: PrinterPaperSnapshot, destination: Path) {
    DataOutputStream(
            BufferedOutputStream(
                Files.newOutputStream(
                    destination,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ),
                FILE_BUFFER_BYTES,
            ))
        .use { output ->
          output.write(PNG_SIGNATURE)
          val header = ByteArray(13)
          writeInt(header, 0, PRINTER_PAPER_WIDTH)
          writeInt(header, 4, snapshot.contentHeight)
          header[8] = 8 // bits per channel
          header[9] = 2 // true-colour RGB
          header[10] = 0 // DEFLATE compression
          header[11] = 0 // adaptive filtering; each row below chooses filter 0
          header[12] = 0 // no interlace
          writePngChunk(output, "IHDR", header, header.size)

          val chunkedIdat = ChunkedIdatOutput(output)
          val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
          try {
            val compressed = DeflaterOutputStream(chunkedIdat, deflater, DEFLATE_BUFFER_BYTES)
            val rowPixels = IntArray(PRINTER_PAPER_WIDTH)
            val scanline = ByteArray(1 + PRINTER_PAPER_WIDTH * 3)
            scanline[0] = 0
            try {
              for (segment in snapshot.segments) {
                for (row in 0 until segment.height) {
                  segment.copyRgbRow(row, rowPixels)
                  var outputIndex = 1
                  for (rgb in rowPixels) {
                    scanline[outputIndex++] = (rgb ushr 16).toByte()
                    scanline[outputIndex++] = (rgb ushr 8).toByte()
                    scanline[outputIndex++] = rgb.toByte()
                  }
                  compressed.write(scanline)
                }
              }
              compressed.finish()
            } finally {
              compressed.close()
            }
          } finally {
            deflater.end()
          }
          chunkedIdat.finish()
          writePngChunk(output, "IEND", ByteArray(0), 0)
        }
  }

  private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
  }

  private const val FILE_BUFFER_BYTES = 16 * 1024
  private const val DEFLATE_BUFFER_BYTES = 8 * 1024
  private val PNG_SIGNATURE =
      byteArrayOf(
          0x89.toByte(),
          0x50,
          0x4E,
          0x47,
          0x0D,
          0x0A,
          0x1A,
          0x0A,
      )
}

private class ChunkedIdatOutput(
    private val output: DataOutputStream,
) : OutputStream() {
  private val buffer = ByteArray(IDAT_CHUNK_BYTES)
  private var size = 0
  private var finished = false

  override fun write(value: Int) {
    ensureOpen()
    if (size == buffer.size) flushChunk()
    buffer[size++] = value.toByte()
  }

  override fun write(source: ByteArray, offset: Int, length: Int) {
    ensureOpen()
    require(offset >= 0 && length >= 0 && offset <= source.size - length)
    var sourceOffset = offset
    var remaining = length
    while (remaining > 0) {
      if (size == buffer.size) flushChunk()
      val copied = min(remaining, buffer.size - size)
      System.arraycopy(source, sourceOffset, buffer, size, copied)
      sourceOffset += copied
      size += copied
      remaining -= copied
    }
  }

  fun finish() {
    if (finished) return
    if (size > 0) flushChunk()
    finished = true
  }

  override fun close() = finish()

  private fun flushChunk() {
    writePngChunk(output, "IDAT", buffer, size)
    size = 0
  }

  private fun ensureOpen() {
    check(!finished) { "PNG data stream is already finished" }
  }

  private companion object {
    const val IDAT_CHUNK_BYTES = 32 * 1024
  }
}

private fun writePngChunk(
    output: DataOutputStream,
    type: String,
    data: ByteArray,
    length: Int,
) {
  require(type.length == 4)
  require(length in 0..data.size)
  val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
  val crc = CRC32()
  crc.update(typeBytes)
  crc.update(data, 0, length)
  output.writeInt(length)
  output.write(typeBytes)
  output.write(data, 0, length)
  output.writeInt(crc.value.toInt())
}

internal enum class PrinterClearDecision {
  CLEAR,
  CANCEL,
}

internal fun printerClearDecisionSpec(): DesktopDecisionSpec<PrinterClearDecision> =
    DesktopDecisionSpec(
        title = "Clear printer paper?",
        heading = "Clear the entire paper roll?",
        message =
            "This permanently removes every retained printer strip. A PNG export already in progress can still finish.",
        buttons =
            DesktopDialogButtons(
                primary =
                    DesktopDialogAction(
                        label = "Clear paper",
                        result = PrinterClearDecision.CLEAR,
                        mnemonic = KeyEvent.VK_L,
                        accessibleDescription = "Permanently clear the printer paper roll",
                        destructive = true,
                    ),
                cancel =
                    DesktopDialogAction(
                        label = "Cancel",
                        result = PrinterClearDecision.CANCEL,
                        mnemonic = KeyEvent.VK_C,
                    ),
                defaultButton = DesktopDialogDefaultButton.CANCEL,
            ),
    )

internal fun interface PrinterClearConfirmation {
  fun confirm(owner: Window): Boolean
}

private class DesktopPrinterClearConfirmation(
    private val dialogs: DesktopDialogFactory = DesktopDialogFactory(),
) : PrinterClearConfirmation {
  override fun confirm(owner: Window): Boolean =
      dialogs.showDecision(owner, printerClearDecisionSpec()) == PrinterClearDecision.CLEAR
}

internal fun interface PrinterSaveErrorReporter {
  fun report(owner: Window, failure: Throwable)
}

private class DesktopPrinterSaveErrorReporter(
    private val dialogs: DesktopDialogFactory = DesktopDialogFactory(),
) : PrinterSaveErrorReporter {
  override fun report(owner: Window, failure: Throwable) {
    val close = Unit
    dialogs.showError(
        owner,
        DesktopErrorSpec(
            title = "Printer export failed",
            summary = "The printer paper could not be saved as a PNG.",
            recovery = "Choose another writable folder and try again. The paper roll is unchanged.",
            sanitizedDetails = safeFailureType(failure),
            buttons =
                DesktopDialogButtons(
                    cancel = DesktopDialogAction("Close", close),
                ),
        ),
    )
  }

  private fun safeFailureType(failure: Throwable): String =
      failure.javaClass.simpleName.takeIf { it.matches(SAFE_TYPE) } ?: "ExportException"

  private companion object {
    val SAFE_TYPE = Regex("[A-Za-z0-9_$]{1,128}")
  }
}

internal fun interface PrinterFolderOpener {
  fun open(directory: Path): Boolean
}

private object DesktopPrinterFolderOpener : PrinterFolderOpener {
  override fun open(directory: Path): Boolean = DesktopStateExternalActions().openPath(directory)
}

internal interface PrinterWindowBounds {
  /** Returns bounds already validated and clamped against the current screen layout. */
  fun restore(): java.awt.Rectangle?

  fun remember(bounds: java.awt.Rectangle)
}

private object NoPrinterWindowBounds : PrinterWindowBounds {
  override fun restore(): java.awt.Rectangle? = null

  override fun remember(bounds: java.awt.Rectangle) = Unit
}

internal interface PrinterWindowView : AutoCloseable {
  val component: Component

  fun showOrRaise(raise: Boolean)
}

internal fun interface PrinterWindowViewFactory {
  fun create(
      owner: Component,
      content: JPanel,
      windowBounds: PrinterWindowBounds,
  ): PrinterWindowView
}

private object SwingPrinterWindowViewFactory : PrinterWindowViewFactory {
  override fun create(
      owner: Component,
      content: JPanel,
      windowBounds: PrinterWindowBounds,
  ): PrinterWindowView = SwingPrinterWindowView(owner, content, windowBounds)
}

private class SwingPrinterWindowView(
    owner: Component,
    content: JPanel,
    private val windowBounds: PrinterWindowBounds,
) : PrinterWindowView {
  private val dialog: JDialog

  init {
    check(!java.awt.GraphicsEnvironment.isHeadless()) {
      "The Game Boy Printer window is unavailable in headless mode"
    }
    val ownerWindow =
        when (owner) {
          is Window -> owner
          else -> SwingUtilities.getWindowAncestor(owner)
        }
    checkNotNull(ownerWindow) { "The Game Boy Printer requires an attached desktop window" }
    dialog =
        JDialog(ownerWindow, "Printer — Coffee GB", Dialog.ModalityType.MODELESS).apply {
          defaultCloseOperation = JDialog.HIDE_ON_CLOSE
          contentPane = content
          minimumSize = Dimension(360, 280)
          pack()
          val restored = windowBounds.restore()
          if (restored != null && restored.width >= 320 && restored.height >= 240) {
            bounds = restored
          } else {
            setLocationRelativeTo(ownerWindow)
          }
          addWindowListener(
              object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) {
                  rememberBounds()
                }

                override fun windowClosed(event: WindowEvent) {
                  rememberBounds()
                }
              })
        }
  }

  override val component: Component
    get() = dialog

  override fun showOrRaise(raise: Boolean) {
    if (!dialog.isVisible) dialog.isVisible = true
    if (raise) dialog.toFront()
  }

  override fun close() {
    rememberBounds()
    dialog.dispose()
  }

  private fun rememberBounds() {
    val bounds = dialog.bounds
    if (bounds.width >= 320 && bounds.height >= 240) {
      windowBounds.remember(java.awt.Rectangle(bounds))
    }
  }
}

internal data class PrinterDependencies(
    val store: PrinterPaperStore = PrinterPaperStore(),
    val pngWriter: PrinterPngWriter = StreamingPrinterPngWriter,
    val clearConfirmation: PrinterClearConfirmation = DesktopPrinterClearConfirmation(),
    val errorReporter: PrinterSaveErrorReporter = DesktopPrinterSaveErrorReporter(),
    val folderOpener: PrinterFolderOpener = DesktopPrinterFolderOpener,
    val windowBounds: PrinterWindowBounds = NoPrinterWindowBounds,
    val backgroundExecutor: Executor = ForkJoinPool.commonPool(),
    val uiExecutor: Executor = Executor { task -> SwingUtilities.invokeLater(task) },
    val windowFactory: PrinterWindowViewFactory = SwingPrinterWindowViewFactory,
)

/**
 * Retained modeless Game Boy Printer tool.
 *
 * Controller print events are transferred to the EDT and always return without waiting for UI or
 * file I/O. The public one-argument constructor remains the serial-peripheral integration API.
 */
class SwingPrinter internal constructor(
    eventBus: EventBus,
    private val dependencies: PrinterDependencies,
) : AutoCloseable {
  constructor(eventBus: EventBus) : this(eventBus, PrinterDependencies())

  private var window: PrinterWindowView? = null
  private var panel: PrinterPanel? = null
  private var preferredOwner: Component? = null
  private var createdOwner: Component? = null
  private var desktopOwnerAttached = false
  private var showRequested = false
  private var scrollToNewestRequested = false
  private var windowBounds: PrinterWindowBounds = dependencies.windowBounds

  init {
    eventBus.register<PrinterPrintEvent> { event ->
      dependencies.uiExecutor.execute { appendOnEdt(event) }
    }
    // Open from both the current exclusive-selection action and its legacy compatibility adapter.
    // These callbacks only enqueue Swing work, so selecting a serial peripheral never waits for UI.
    eventBus.register<SetSerialPeripheralEvent> { event ->
      if (event.selection == SerialPeripheralSelection.PRINTER) showWindow()
    }
    eventBus.register<SetPrinterEvent> { event ->
      if (event.enabled) showWindow()
    }
  }

  /** Raises the retained tool before or after the first printed strip. */
  fun showWindow(owner: Component? = null) {
    dependencies.uiExecutor.execute {
      if (owner != null && !desktopOwnerAttached && preferredOwner == null) {
        preferredOwner = owner
      }
      showOnEdt(raise = true)
    }
  }

  /** Host bridge for the portable menu; both operations retain the native printer UI semantics. */
  internal fun clearFromPortableMenu() {
    requireEdt()
    requestClear()
  }

  /** Host bridge for the portable menu; the file save dialog remains native Swing. */
  internal fun exportFromPortableMenu() {
    requireEdt()
    requestSave()
  }

  /** Cheap availability query used to keep the portable printer route unreachable when empty. */
  internal fun hasPaper(): Boolean = dependencies.store.model().hasContent

  /** Returns the current immutable paper generation for the portable menu preview. */
  internal fun menuPreview(): MenuPreview = dependencies.store.model().snapshot().menuPreview()

  /** Connects the retained tool to the main desktop owner before first use. */
  internal fun attachDesktopWindow(owner: Component, bounds: PrinterWindowBounds) {
    runOnEdtAndWait { attachDesktopWindowOnEdt(owner, bounds) }
  }

  override fun close() {
    dependencies.uiExecutor.execute {
      window?.close()
      window = null
      panel = null
      createdOwner = null
      showRequested = false
      scrollToNewestRequested = false
    }
  }

  private fun appendOnEdt(event: PrinterPrintEvent) {
    requireEdt()
    val result = dependencies.store.append(event)
    scrollToNewestRequested = scrollToNewestRequested || result.accepted
    showOnEdt(raise = true)
  }

  private fun showOnEdt(raise: Boolean) {
    requireEdt()
    showRequested = true
    val ui = ensureUi() ?: return
    ui.refreshPaper()
    checkNotNull(window).showOrRaise(raise)
    showRequested = false
    if (scrollToNewestRequested) {
      scrollToNewestStrip(ui)
      scrollToNewestRequested = false
    }
  }

  private fun ensureUi(): PrinterPanel? {
    panel?.let { return it }
    val owner = preferredOwner ?: return null
    val createdPanel = PrinterPanel()
    val createdWindow = dependencies.windowFactory.create(owner, createdPanel, windowBounds)
    panel = createdPanel
    window = createdWindow
    createdOwner = owner
    createdPanel.refreshPaper()
    return createdPanel
  }

  private fun attachDesktopWindowOnEdt(owner: Component, bounds: PrinterWindowBounds) {
    requireEdt()
    desktopOwnerAttached = true
    if (window != null && createdOwner !== owner) {
      window?.close()
      window = null
      panel = null
      createdOwner = null
      showRequested = true
    }
    preferredOwner = owner
    windowBounds = bounds
    if (showRequested) showOnEdt(raise = true)
  }

  private fun runOnEdtAndWait(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
      action()
    } else {
      SwingUtilities.invokeAndWait(action)
    }
  }

  private fun requestSave() {
    requireEdt()
    val window = this.window?.component ?: return
    val chooser =
        JFileChooser().apply {
          dialogTitle = "Save printer paper"
          fileFilter = FileNameExtensionFilter("PNG image (*.png)", "png")
          selectedFile = File("printout.png")
        }
    if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return
    var destination = chooser.selectedFile.toPath()
    if (!destination.fileName.toString().endsWith(".png", ignoreCase = true)) {
      destination = destination.resolveSibling(destination.fileName.toString() + ".png")
    }

    val attempt = dependencies.store.acquireExportLease()
    val lease = attempt.lease
    if (lease == null) {
      val message =
          when (attempt.unavailableReason) {
            PrinterExportUnavailableReason.EMPTY -> "Nothing has been printed yet."
            PrinterExportUnavailableReason.BUSY -> "A printer export is already in progress."
            PrinterExportUnavailableReason.RETENTION_LIMIT ->
                "The current paper generation cannot be leased within the export memory limit."
            null -> "The printer paper is unavailable."
          }
      panel?.showOperation(message, failure = true)
      return
    }

    panel?.refreshPaper()
    panel?.showOperation("Saving PNG…", failure = false)
    val stableDestination = destination
    try {
      dependencies.backgroundExecutor.execute {
        val result = runCatching { dependencies.pngWriter.write(lease.snapshot, stableDestination) }
        lease.close()
        dependencies.uiExecutor.execute { completeSave(stableDestination, result) }
      }
    } catch (failure: RuntimeException) {
      lease.close()
      completeSave(stableDestination, Result.failure(failure))
    }
  }

  private fun completeSave(destination: Path, result: Result<Unit>) {
    requireEdt()
    panel?.refreshPaper()
    result.fold(
        onSuccess = {
          panel?.showOperation(
              "Saved ${destination.fileName}.",
              failure = false,
              showDirectory = destination.toAbsolutePath().parent,
          )
        },
        onFailure = { failure ->
          panel?.showOperation("PNG export failed. The paper roll is unchanged.", failure = true)
          (window?.component as? Window)?.let { dependencies.errorReporter.report(it, failure) }
        },
    )
  }

  private fun requestClear() {
    requireEdt()
    val current = dependencies.store.model()
    val window = this.window?.component as? Window ?: return
    if (!current.hasContent || !dependencies.clearConfirmation.confirm(window)) return
    dependencies.store.clear()
    panel?.refreshPaper()
    panel?.showOperation("Paper cleared.", failure = false)
  }

  private fun requestShowDirectory(directory: Path) {
    requireEdt()
    panel?.showOperation("Opening the export folder…", failure = false)
    try {
      dependencies.backgroundExecutor.execute {
        val opened = runCatching { dependencies.folderOpener.open(directory) }.getOrDefault(false)
        dependencies.uiExecutor.execute {
          requireEdt()
          panel?.showOperation(
              if (opened) "Export folder opened."
              else "The export folder could not be opened on this system.",
              failure = !opened,
              showDirectory = if (opened) directory else null,
          )
        }
      }
    } catch (_: RuntimeException) {
      panel?.showOperation(
          "The export folder could not be opened on this system.",
          failure = true,
      )
    }
  }

  private fun scrollToNewestStrip(ui: PrinterPanel) {
    SwingUtilities.invokeLater {
      val bar = ui.scrollPane.verticalScrollBar
      bar.value = bar.maximum
    }
  }

  private fun requireEdt() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Game Boy Printer UI mutations must run on the Event Dispatch Thread"
    }
  }

  private inner class PrinterPanel :
      JPanel(BorderLayout(0, 8)), DesktopThemeRefreshHook {
    private val canvas = PrinterPaperCanvas { dependencies.store.model() }
    internal val scrollPane =
        JScrollPane(canvas).apply {
          preferredSize = Dimension(PRINTER_PAPER_WIDTH * PREVIEW_SCALE + 32, 360)
          getAccessibleContext().accessibleName = "Game Boy Printer paper roll"
          getAccessibleContext().accessibleDescription =
              "A scrollable pixel-accurate preview of retained printer strips"
        }
    private val saveButton =
        JButton("Save PNG…").apply {
          mnemonic = KeyEvent.VK_S
          getAccessibleContext().accessibleDescription = "Save the retained paper roll as a PNG image"
          addActionListener { requestSave() }
        }
    private val clearButton =
        JButton("Clear paper").apply {
          mnemonic = KeyEvent.VK_C
          getAccessibleContext().accessibleDescription =
              "Permanently clear the retained paper after confirmation"
          addActionListener { requestClear() }
        }
    private val toolbar =
        JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
          add(saveButton)
          add(clearButton)
          getAccessibleContext().accessibleName = "Printer paper actions"
        }
    private val rollStatus = JLabel()
    private val helperStatus = JLabel()
    private val operationStatus = JLabel()
    private val showFolderButton =
        JButton("Show in Folder").apply {
          isVisible = false
          mnemonic = KeyEvent.VK_F
          getAccessibleContext().accessibleDescription = "Open the folder containing the saved PNG"
        }
    private val operationRow =
        JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
          add(operationStatus)
          add(showFolderButton)
        }
    private val statusArea =
        JPanel().apply {
          layout = BoxLayout(this, BoxLayout.Y_AXIS)
          add(rollStatus)
          add(Box.createVerticalStrut(3))
          add(helperStatus)
          add(Box.createVerticalStrut(3))
          add(operationRow)
          border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        }
    private var operationFailed = false
    private var currentTokens = DesktopThemeTokens.capture(DesktopAppearance.SYSTEM)

    init {
      border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
      add(toolbar, BorderLayout.NORTH)
      add(scrollPane, BorderLayout.CENTER)
      add(statusArea, BorderLayout.SOUTH)
      rollStatus.accessibleContext.accessibleName = "Printer paper status"
      helperStatus.accessibleContext.accessibleName = "Printer paper guidance"
      operationStatus.accessibleContext.accessibleName = "Printer export status"
      desktopThemeChanged(DesktopThemeTokens.capture(DesktopAppearance.SYSTEM))
    }

    fun refreshPaper() {
      val model = dependencies.store.model()
      saveButton.isEnabled = model.hasContent && !dependencies.store.hasActiveExport()
      clearButton.isEnabled = model.hasContent
      if (model.rollFull) {
        val suffix = if (model.omittedStripCount == 1L) "strip" else "strips"
        rollStatus.text =
            "Paper roll full — save or clear it. ${model.omittedStripCount} $suffix omitted."
        helperStatus.text = "Saving does not free the roll until it is cleared."
        helperStatus.isVisible = true
      } else if (model.omittedStripCount > 0L) {
        val suffix = if (model.omittedStripCount == 1L) "strip" else "strips"
        rollStatus.text = "${model.omittedStripCount} invalid printer $suffix omitted."
        helperStatus.text = "Existing paper is intact; retry printing or clear the roll."
        helperStatus.isVisible = true
      } else if (model.hasContent) {
        val suffix = if (model.segmentCount == 1) "strip" else "strips"
        rollStatus.text =
            "${model.segmentCount} $suffix retained • ${model.contentHeight} paper rows"
        helperStatus.text = ""
        helperStatus.isVisible = false
      } else {
        rollStatus.text = "No paper yet. The next printer strip will appear here."
        helperStatus.text = ""
        helperStatus.isVisible = false
      }
      rollStatus.accessibleContext.accessibleDescription = rollStatus.text
      helperStatus.accessibleContext.accessibleDescription = helperStatus.text
      rollStatus.foreground = if (model.rollFull) currentTokens.warning else currentTokens.primaryText
      canvas.revalidate()
      canvas.repaint()
      revalidate()
      repaint()
    }

    fun showOperation(
        message: String,
        failure: Boolean,
        showDirectory: Path? = null,
    ) {
      operationFailed = failure
      operationStatus.text = message
      operationStatus.accessibleContext.accessibleDescription = message
      showFolderButton.actionListeners.forEach(showFolderButton::removeActionListener)
      if (showDirectory != null) {
        showFolderButton.addActionListener { requestShowDirectory(showDirectory) }
        showFolderButton.isVisible = true
      } else {
        showFolderButton.isVisible = false
      }
      applyStatusColors(currentTokens)
      revalidate()
      repaint()
    }

    override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
      currentTokens = tokens
      background = tokens.surface
      toolbar.background = tokens.surface
      statusArea.background = tokens.surface
      operationRow.background = tokens.surface
      scrollPane.viewport.background = tokens.elevatedSurface
      scrollPane.border = BorderFactory.createLineBorder(tokens.border)
      saveButton.background = tokens.accent
      saveButton.foreground = tokens.onAccent
      clearButton.background = tokens.elevatedSurface
      clearButton.foreground = tokens.danger
      showFolderButton.background = tokens.elevatedSurface
      showFolderButton.foreground = tokens.accent
      rollStatus.foreground = if (dependencies.store.model().rollFull) tokens.warning else tokens.primaryText
      helperStatus.foreground = tokens.secondaryText
      applyStatusColors(tokens)
      canvas.desktopThemeChanged(tokens)
    }

    private fun applyStatusColors(tokens: DesktopThemeTokens) {
      operationStatus.foreground = if (operationFailed) tokens.danger else tokens.secondaryText
    }
  }
}

internal class PrinterPaperCanvas(
    private val modelProvider: () -> PrinterPaperModel,
) : JPanel(), DesktopThemeRefreshHook {
  init {
    isOpaque = true
    super.getAccessibleContext().accessibleName = "Printed paper preview"
  }

  override fun getPreferredSize(): Dimension {
    val height = maxOf(1, modelProvider().contentHeight)
    return Dimension(PRINTER_PAPER_WIDTH * PREVIEW_SCALE, height * PREVIEW_SCALE)
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val model = modelProvider()
    val paperHeight = maxOf(1, model.contentHeight) * PREVIEW_SCALE
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, PRINTER_PAPER_WIDTH * PREVIEW_SCALE, paperHeight)
    if (!model.hasContent) return
    val copy = graphics.create() as Graphics2D
    try {
      copy.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
      )
      model.paint(copy, PREVIEW_SCALE)
    } finally {
      copy.dispose()
    }
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    // Only the area surrounding the physical paper follows the application theme. Segment pixels
    // and blank paper stay literal RGB/white across look-and-feel changes.
    background = tokens.elevatedSurface
    repaint()
  }
}

private const val PREVIEW_SCALE = 2
private const val PAPER_WHITE = 0xFFFFFF
