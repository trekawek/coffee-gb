package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller.PrinterPrintEvent
import eu.rekawek.coffeegb.controller.Controller.SerialPeripheralSelection
import eu.rekawek.coffeegb.controller.Controller.SetPrinterEvent
import eu.rekawek.coffeegb.controller.Controller.SetSerialPeripheralEvent
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.ui.menu.MenuPreview
import java.awt.Component
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class SwingPrinterTest {

  @Test
  fun `immutable segmented paper preserves margins source pixels and prior generations`() {
    val source = IntArray(PRINTER_PAPER_WIDTH * 2) { index -> if (index < 160) RED else BLUE }
    val empty = PrinterPaperModel.empty(maximumDecodedPixels = PRINTER_PAPER_WIDTH * 10L)

    val appended = empty.append(printEvent(source, height = 2, topMargin = 1, bottomMargin = 1))

    assertTrue(appended.accepted)
    assertEquals(0, empty.contentHeight)
    assertEquals(8, appended.model.contentHeight)
    assertEquals(PRINTER_PAPER_WIDTH * 8L, appended.model.decodedPixels)
    assertEquals(1, appended.model.segmentCount)
    assertEquals(1L, appended.model.generation)
    val stable = appended.model.snapshot()
    assertEquals(WHITE, stable.rgbAt(0, 0))
    assertEquals(WHITE, stable.rgbAt(159, 2))
    assertEquals(RED, stable.rgbAt(0, 3))
    assertEquals(BLUE, stable.rgbAt(159, 4))
    assertEquals(WHITE, stable.rgbAt(0, 5))

    source.fill(GREEN)
    assertEquals(RED, stable.rgbAt(0, 3), "The event's mutable source must not leak into paper")

    val rejected = appended.model.append(solidStrip(height = 3, color = GREEN))
    assertFalse(rejected.accepted)
    assertEquals(PrinterPaperOmissionReason.CAPACITY, rejected.omissionReason)
    assertEquals(8, rejected.model.contentHeight)
    assertEquals(1L, rejected.model.omittedStripCount)
    assertTrue(rejected.model.rollFull)
    assertEquals(RED, stable.rgbAt(0, 3), "A later overflow must not alter the old generation")
  }

  @Test
  fun `portable preview is an immutable ARGB view of the current paper generation`() {
    val source = IntArray(PRINTER_PAPER_WIDTH * 2) { index -> if (index < 160) RED else BLUE }
    val model =
        PrinterPaperModel.empty()
            .append(printEvent(source, height = 2, topMargin = 1, bottomMargin = 1))
            .model

    val preview = model.snapshot().menuPreview()

    assertEquals(MenuPreview.State.READY, preview.state())
    assertEquals(PRINTER_PAPER_WIDTH, preview.width())
    assertEquals(8, preview.height())
    assertEquals(0xffffffff.toInt(), preview.copyPixels()[0])
    assertEquals(0xffcc2211.toInt(), preview.copyPixels()[3 * PRINTER_PAPER_WIDTH])
    assertEquals(0xff2244cc.toInt(), preview.copyPixels()[4 * PRINTER_PAPER_WIDTH])

    val detached = preview.copyPixels()
    detached[3 * PRINTER_PAPER_WIDTH] = 0xff000000.toInt()
    assertEquals(0xffcc2211.toInt(), preview.copyPixels()[3 * PRINTER_PAPER_WIDTH])

    val tall =
        PrinterPaperModel.empty()
            .append(solidStrip(height = 193, color = RED))
            .model
            .snapshot()
            .menuPreview()
    assertEquals(192, tall.height())
    assertTrue(tall.width() < PRINTER_PAPER_WIDTH)
    assertTrue(tall.width() * tall.height() <= MenuPreview.MAX_PIXELS)
  }

  @Test
  fun `checked strip arithmetic rejects malformed callbacks without disturbing retained paper`() {
    val retained = PrinterPaperModel.empty().append(solidStrip(1, RED)).model
    val retainedSnapshot = retained.snapshot()
    val wrongWidth =
        PrinterPrintEvent(
            IntArray(159),
            159,
            1,
            0,
            0,
            0,
        )
    val overflowingMargins =
        PrinterPrintEvent(
            IntArray(0),
            PRINTER_PAPER_WIDTH,
            0,
            Int.MAX_VALUE,
            Int.MAX_VALUE,
            0,
        )

    val first = retained.append(wrongWidth)
    val second = first.model.append(overflowingMargins)

    assertEquals(PrinterPaperOmissionReason.INVALID_STRIP, first.omissionReason)
    assertEquals(PrinterPaperOmissionReason.INVALID_STRIP, second.omissionReason)
    assertEquals(2L, second.model.omittedStripCount)
    assertFalse(second.model.rollFull)
    assertEquals(retained.contentHeight, second.model.contentHeight)
    assertEquals(retained.decodedPixels, second.model.decodedPixels)
    assertEquals(RED, retainedSnapshot.rgbAt(12, 0))
    assertEquals(RED, second.model.snapshot().rgbAt(12, 0))
  }

  @Test
  fun `single flight lease bounds old and current generations until released`() {
    val row = PRINTER_PAPER_WIDTH.toLong()
    val store =
        PrinterPaperStore(
            maximumCurrentPixels = row * 4,
            maximumAggregatePixels = row * 5,
        )
    assertTrue(store.append(solidStrip(2, RED)).accepted)
    val firstGeneration = store.model().generation

    val lease = assertNotNull(store.acquireExportLease().lease)
    assertEquals(firstGeneration, lease.snapshot.generation)
    assertEquals(PrinterExportUnavailableReason.BUSY, store.acquireExportLease().unavailableReason)

    store.clear()
    assertTrue(store.append(solidStrip(3, BLUE)).accepted)
    val aggregateOverflow = store.append(solidStrip(1, GREEN))
    assertFalse(aggregateOverflow.accepted)
    assertEquals(PrinterPaperOmissionReason.CAPACITY, aggregateOverflow.omissionReason)
    assertEquals(3, store.model().contentHeight)
    assertEquals(RED, lease.snapshot.rgbAt(0, 0))
    assertEquals(RED, lease.snapshot.rgbAt(0, 1))

    lease.close()
    lease.close()
    assertFalse(store.hasActiveExport())
    assertTrue(store.append(solidStrip(1, GREEN)).accepted)
    assertEquals(4, store.model().contentHeight)
    assertEquals(GREEN, store.model().snapshot().rgbAt(0, 3))
  }

  @Test
  fun `empty and over-retained generations cannot acquire an export lease`() {
    val row = PRINTER_PAPER_WIDTH.toLong()
    val empty = PrinterPaperStore(maximumCurrentPixels = row * 4, maximumAggregatePixels = row * 5)
    assertEquals(PrinterExportUnavailableReason.EMPTY, empty.acquireExportLease().unavailableReason)

    assertTrue(empty.append(solidStrip(3, RED)).accepted)
    val attempt = empty.acquireExportLease()
    assertNull(attempt.lease)
    assertEquals(PrinterExportUnavailableReason.RETENTION_LIMIT, attempt.unavailableReason)
  }

  @Test
  fun `streaming PNG encodes the leased generation after current paper is cleared`() {
    val firstRow = IntArray(PRINTER_PAPER_WIDTH) { index -> if (index % 2 == 0) RED else BLUE }
    val model =
        PrinterPaperModel.empty()
            .append(printEvent(firstRow, height = 1, topMargin = 1, bottomMargin = 0))
            .model
    val stable = model.snapshot()
    val changed = model.clear().append(solidStrip(1, GREEN)).model
    val destination = Files.createTempFile("coffee-gb-printer-test-", ".png")
    try {
      StreamingPrinterPngWriter.write(stable, destination)

      val image = assertNotNull(ImageIO.read(destination.toFile()))
      assertEquals(PRINTER_PAPER_WIDTH, image.width)
      assertEquals(4, image.height)
      assertEquals(WHITE, image.getRGB(0, 0) and 0xFFFFFF)
      assertEquals(WHITE, image.getRGB(159, 2) and 0xFFFFFF)
      assertEquals(RED, image.getRGB(0, 3) and 0xFFFFFF)
      assertEquals(BLUE, image.getRGB(1, 3) and 0xFFFFFF)
      assertEquals(GREEN, changed.snapshot().rgbAt(0, 0))
      assertEquals(RED, stable.rgbAt(0, 3))
    } finally {
      destination.deleteIfExists()
    }
  }

  @Test
  fun `clear paper decision is destructive explicit and cancel safe by default`() {
    val spec = printerClearDecisionSpec()
    val primary = assertNotNull(spec.buttons.primary)

    assertEquals("Clear paper", primary.label)
    assertTrue(primary.destructive)
    assertEquals(PrinterClearDecision.CLEAR, primary.result)
    assertEquals(PrinterClearDecision.CANCEL, spec.buttons.cancel.result)
    assertEquals(DesktopDialogDefaultButton.CANCEL, spec.buttons.defaultButton)
  }

  @Test
  fun `serial selection and print callbacks only enqueue retained tool work`() {
    val eventBus = EventBusImpl()
    val queued = mutableListOf<Runnable>()
    val store = PrinterPaperStore()
    try {
      SwingPrinter(
          eventBus,
          PrinterDependencies(
              store = store,
              uiExecutor = Executor { task -> queued += task },
          ),
      )

      eventBus.post(SetSerialPeripheralEvent(SerialPeripheralSelection.PRINTER))
      eventBus.post(SetPrinterEvent(true))
      eventBus.post(solidStrip(1, RED))

      assertEquals(3, queued.size)
      assertFalse(store.model().hasContent, "No serial callback may wait for or perform Swing work")
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `print before desktop attachment retains paper and realizes one owned window on attach`() {
    val eventBus = EventBusImpl()
    val queued = mutableListOf<Runnable>()
    val store = PrinterPaperStore()
    val factory = RecordingPrinterWindowFactory()
    val printer =
        SwingPrinter(
            eventBus,
            PrinterDependencies(
                store = store,
                uiExecutor = Executor { task -> queued += task },
                windowFactory = factory,
            ),
        )
    try {
      eventBus.post(solidStrip(1, RED))
      drainOnEdt(queued)

      assertTrue(store.model().hasContent)
      assertEquals(0, factory.owners.size, "Printing must wait for the designated desktop owner")

      val desktopOwner = JPanel()
      printer.attachDesktopWindow(desktopOwner, TestPrinterWindowBounds)

      assertEquals(1, factory.owners.size)
      assertSame(desktopOwner, factory.owners.single())
      assertEquals(1, factory.views.single().showCount)
      assertEquals(1, store.model().segmentCount)

      printer.close()
      drainOnEdt(queued)
      assertEquals(1, factory.views.single().closeCount)
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `attached desktop owner wins over unrelated open owner without duplicating retained window`() {
    val eventBus = EventBusImpl()
    val queued = mutableListOf<Runnable>()
    val factory = RecordingPrinterWindowFactory()
    val printer =
        SwingPrinter(
            eventBus,
            PrinterDependencies(
                uiExecutor = Executor { task -> queued += task },
                windowFactory = factory,
            ),
        )
    try {
      val desktopOwner = JPanel()
      val unrelatedOwner = JPanel()
      printer.attachDesktopWindow(desktopOwner, TestPrinterWindowBounds)

      printer.showWindow(unrelatedOwner)
      eventBus.post(SetPrinterEvent(true))
      eventBus.post(SetSerialPeripheralEvent(SerialPeripheralSelection.PRINTER))
      drainOnEdt(queued)

      assertEquals(1, factory.owners.size)
      assertSame(desktopOwner, factory.owners.single())
      assertTrue(factory.owners.single() !== unrelatedOwner)
      assertEquals(3, factory.views.single().showCount)

      printer.close()
      drainOnEdt(queued)
    } finally {
      eventBus.close()
    }
  }

  @Test
  fun `production decoded retention limits are exact`() {
    assertEquals(8_388_608L, PRINTER_PAPER_MAX_DECODED_PIXELS)
    assertEquals(16_777_216L, PRINTER_PAPER_MAX_AGGREGATE_PIXELS)
    assertEquals(PRINTER_PAPER_MAX_DECODED_PIXELS * 2, PRINTER_PAPER_MAX_AGGREGATE_PIXELS)
  }

  @Test
  fun `paper canvas initializes its lazy accessible context safely`() {
    var canvas: PrinterPaperCanvas? = null

    SwingUtilities.invokeAndWait {
      canvas = PrinterPaperCanvas { PrinterPaperModel.empty() }
    }

    assertEquals("Printed paper preview", assertNotNull(canvas).accessibleContext.accessibleName)
  }

  private fun solidStrip(height: Int, color: Int): PrinterPrintEvent =
      printEvent(IntArray(PRINTER_PAPER_WIDTH * height) { color }, height)

  private fun printEvent(
      pixels: IntArray,
      height: Int,
      topMargin: Int = 0,
      bottomMargin: Int = 0,
  ): PrinterPrintEvent =
      PrinterPrintEvent(
          pixels,
          PRINTER_PAPER_WIDTH,
          height,
          topMargin,
          bottomMargin,
          0,
      )

  private fun drainOnEdt(queued: MutableList<Runnable>) {
    while (queued.isNotEmpty()) {
      val task = queued.removeAt(0)
      SwingUtilities.invokeAndWait { task.run() }
    }
  }

  private class RecordingPrinterWindowFactory : PrinterWindowViewFactory {
    val owners = mutableListOf<Component>()
    val views = mutableListOf<RecordingPrinterWindowView>()

    override fun create(
        owner: Component,
        content: JPanel,
        windowBounds: PrinterWindowBounds,
    ): PrinterWindowView {
      owners += owner
      return RecordingPrinterWindowView(content).also { views += it }
    }
  }

  private class RecordingPrinterWindowView(
      override val component: Component,
  ) : PrinterWindowView {
    var showCount = 0
    var closeCount = 0

    override fun showOrRaise(raise: Boolean) {
      showCount++
    }

    override fun close() {
      closeCount++
    }
  }

  private object TestPrinterWindowBounds : PrinterWindowBounds {
    override fun restore(): java.awt.Rectangle? = null

    override fun remember(bounds: java.awt.Rectangle) = Unit
  }

  private companion object {
    const val WHITE = 0xFFFFFF
    const val RED = 0xCC2211
    const val BLUE = 0x2244CC
    const val GREEN = 0x228833
  }
}
