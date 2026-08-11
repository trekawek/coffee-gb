package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrinterExportContinuationTest {

    @Test
    public void completionIsTokenOwnedAndStaleCallbacksCannotBecomeActionable() {
        PrinterExportContinuation pending = PrinterExportContinuation.pending(7L, "content://paper");

        assertEquals(PrinterExportContinuation.Phase.PENDING,
                pending.complete(6L, true).phase());
        PrinterExportContinuation ready = pending.complete(7L, true);
        assertEquals(PrinterExportContinuation.Phase.READY, ready.phase());
        assertTrue(ready.actionable());
        assertEquals(PrinterExportContinuation.Phase.READY,
                ready.complete(7L, false).phase());
    }

    @Test
    public void persistedFailureAndMalformedStateRestoreTruthfully() {
        PrinterExportContinuation failed = PrinterExportContinuation
                .restored(9L, "content://paper", "FAILED");
        assertTrue(failed.actionable());
        assertEquals(PrinterExportContinuation.Phase.FAILED, failed.phase());

        PrinterExportContinuation malformed = PrinterExportContinuation
                .restored(0L, "", "READY");
        assertFalse(malformed.actionable());
        assertEquals(PrinterExportContinuation.Phase.NONE, malformed.phase());
    }
}
