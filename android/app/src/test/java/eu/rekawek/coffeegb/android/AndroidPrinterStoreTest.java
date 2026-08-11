package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.android.menu.MenuPreview;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AndroidPrinterStoreTest {

    @Test
    public void copiesPrinterPixelsAndAddsPaperMargins() {
        AndroidPrinterStore store = new AndroidPrinterStore();
        int[] source = new int[AndroidPrinterStore.WIDTH * 2];
        source[0] = 0xff112233;
        source[source.length - 1] = 0xffabcdef;

        assertTrue(store.append(new Controller.PrinterPrintEvent(source, AndroidPrinterStore.WIDTH,
                2, 1, 2, 0)));
        source[0] = 0;

        AndroidPrinterStore.Snapshot snapshot = store.snapshot();
        assertNotNull(snapshot);
        assertEquals(11, snapshot.height());
        int[] paper = snapshot.copyArgb();
        assertEquals(0xffffffff, paper[0]);
        assertEquals(0xff112233, paper[AndroidPrinterStore.WIDTH * 3]);
        assertEquals(0xffabcdef, paper[AndroidPrinterStore.WIDTH * 5 - 1]);
        assertEquals(0xffffffff, paper[paper.length - 1]);

        store.clear();
        assertNull(store.snapshot());
    }

    @Test
    public void rejectsMalformedAndOversizedStripsWithoutRetainingThem() {
        AndroidPrinterStore store = new AndroidPrinterStore();
        assertFalse(store.append(new Controller.PrinterPrintEvent(new int[1], 159, 1, 0, 0, 0)));
        assertFalse(store.append(new Controller.PrinterPrintEvent(new int[AndroidPrinterStore.WIDTH],
                AndroidPrinterStore.WIDTH, 1, Integer.MAX_VALUE, 0, 0)));

        assertEquals(2, store.omittedStrips());
        assertNull(store.snapshot());
    }

    @Test
    public void longRollPreviewIsBoundedDetachedAndAspectPreservingWithoutChangingExport() {
        AndroidPrinterStore store = new AndroidPrinterStore();
        int sourceHeight = 1_600;
        int[] source = new int[AndroidPrinterStore.WIDTH * sourceHeight];
        java.util.Arrays.fill(source, 0xff314159);
        assertTrue(store.append(new Controller.PrinterPrintEvent(source,
                AndroidPrinterStore.WIDTH, sourceHeight, 0, 0, 0)));

        AndroidPrinterStore.Snapshot snapshot = store.snapshot();
        MenuPreview preview = snapshot.preview(160, 192);

        assertEquals(MenuPreview.State.READY, preview.state());
        assertTrue(preview.width() <= 160);
        assertTrue(preview.height() <= 192);
        assertEquals(sourceHeight / (double) AndroidPrinterStore.WIDTH,
                preview.height() / (double) preview.width(), .6);
        int[] detached = preview.copyPixels();
        detached[0] = 0;
        assertEquals(0xff314159, preview.copyPixels()[0]);
        assertEquals(AndroidPrinterStore.WIDTH * sourceHeight,
                snapshot.copyArgb().length);
    }
}
