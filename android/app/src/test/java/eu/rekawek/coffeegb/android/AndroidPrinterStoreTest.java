package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.Controller;
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
}
