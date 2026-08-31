package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativeFrameStoreTest {

    @Test
    public void approvedDmgCgbAndSgbNativeFixturesHaveStablePixelHashes() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int index = 0; index < dmg.length; index++) {
                dmg[index] = index & 3;
            }
            store.publish(new Display.DmgFrameReadyEvent(dmg));
            NativeFrameStore.Snapshot dmgFrame = requireSnapshot(store);
            assertEquals(Display.DISPLAY_WIDTH, dmgFrame.width());
            assertEquals(Display.DISPLAY_HEIGHT, dmgFrame.height());
            assertEquals("2c1db295273711ddd25d81e7efc83de6ea6f25232a119e6a67cf8da947ca684e", dmgFrame.sha256());

            int[] cgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int index = 0; index < cgb.length; index++) {
                cgb[index] = (index * 73) & 0x7fff;
            }
            store.publish(new Display.GbcFrameReadyEvent(cgb));
            assertEquals("a81ffd277afc629be08883743bb5b96e13849e2074271cbf40f3d69fe23d230b", requireSnapshot(store).sha256());

            int[] sgb = new int[SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
            for (int index = 0; index < sgb.length; index++) {
                sgb[index] = ((index * 17) & 0xff) << 16 | ((index * 29) & 0xff) << 8 | (index & 0xff);
            }
            store.publish(new SgbDisplay.SgbFrameReadyEvent(sgb, true));
            NativeFrameStore.Snapshot sgbFrame = requireSnapshot(store);
            assertEquals(SuperGameboy.SGB_DISPLAY_WIDTH, sgbFrame.width());
            assertEquals(SuperGameboy.SGB_DISPLAY_HEIGHT, sgbFrame.height());
            assertEquals("c543b47d4b32eaed8f82c4678e0c2e143bea1bdae7d29d1ee352743510d86438", sgbFrame.sha256());

            int[] sgbCenter = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            store.publish(new SgbDisplay.SgbFrameReadyEvent(sgbCenter, false));
            NativeFrameStore.Snapshot centerFrame = requireSnapshot(store);
            assertEquals(Display.DISPLAY_WIDTH, centerFrame.width());
            assertEquals(Display.DISPLAY_HEIGHT, centerFrame.height());
        } finally {
            store.close();
        }
    }

    @Test
    public void sixHundredFramesReuseThreeFixedBuffersAndKeepOnlyTheNewestFrame() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            int[][] fixedBuffers = new int[store.bufferCount()][];
            for (int index = 0; index < fixedBuffers.length; index++) {
                fixedBuffers[index] = store.bufferAt(index);
            }
            int[] source = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int frame = 0; frame < 600; frame++) {
                source[0] = frame & 3;
                store.publish(new Display.DmgFrameReadyEvent(source));
                NativeFrameStore.Frame rendered = store.takeLatest();
                assertNotNull(rendered);
                store.framePresented(rendered);
                store.finishDrawing(rendered);
            }

            assertEquals(3, store.bufferCount());
            for (int index = 0; index < fixedBuffers.length; index++) {
                assertSame(fixedBuffers[index], store.bufferAt(index));
            }
            assertEquals(0, store.droppedFrames());
            assertEquals(0xff051f2a, requireSnapshot(store).pixels()[0]);
        } finally {
            store.close();
        }
    }

    @Test
    public void beginningBenchmarkEpochDoesNotWakeRendererWithoutARealFrame() {
        NativeFrameStore store = new NativeFrameStore();
        AtomicInteger notifications = new AtomicInteger();
        NativeFrameStore.Listener listener = notifications::incrementAndGet;
        try {
            store.addListener(listener);
            store.beginBenchmarkEpoch(7L);
            assertEquals(0, notifications.get());

            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            store.publish(new Display.DmgFrameReadyEvent(pixels));
            assertEquals(1, notifications.get());
        } finally {
            store.removeListener(listener);
            store.close();
        }
    }

    @Test
    public void grayscaleAffectsDmgFramesButNotTheStoredSelectionForCgb() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            pixels[0] = 1;
            store.setGrayscale(true);
            store.publish(new Display.DmgFrameReadyEvent(pixels));
            int grayscale = requireSnapshot(store).pixels()[0];
            store.setGrayscale(false);
            store.publish(new Display.DmgFrameReadyEvent(pixels));
            int green = requireSnapshot(store).pixels()[0];
            assertEquals(0xffaaaaaa, grayscale);
            assertEquals(0xff99c886, green);
        } finally {
            store.close();
        }
    }

    @Test
    public void dmgProfileAcceptsRawDmgFrames() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.DMG);
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            pixels[0] = 1;
            store.publish(new Display.DmgFrameReadyEvent(pixels));

            assertNotNull(store.snapshot());
            assertEquals(0xff99c886, requireSnapshot(store).pixels()[0]);
        } finally {
            store.close();
        }
    }

    @Test
    public void hardwareProfileSelectsTheInitialSkinPresentation() {
        NativeFrameStore store = new NativeFrameStore();
        AtomicInteger notifications = new AtomicInteger();
        NativeFrameStore.Listener listener = notifications::incrementAndGet;
        store.addListener(listener);
        try {
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());

            store.setHardwareProfile(HardwareProfileRegistry.CGB);
            assertEquals(NativeFrameStore.Presentation.CGB, store.presentation());
            assertEquals(1, notifications.get());

            store.setHardwareProfile(HardwareProfileRegistry.CGB0);
            assertEquals(NativeFrameStore.Presentation.CGB, store.presentation());
            assertEquals(1, notifications.get());

            store.setHardwareProfile(HardwareProfileRegistry.MGB);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            assertEquals(2, notifications.get());

            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            store.setHardwareProfile(HardwareProfileRegistry.SGB2);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            assertEquals(2, notifications.get());
        } finally {
            store.removeListener(listener);
            store.close();
        }
    }

    @Test
    public void cgbProfileKeepsCgbSkinForEitherNativeFrameEvent() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.CGB);
            store.publish(new Display.DmgFrameReadyEvent(
                    new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));

            NativeFrameStore.Frame frame = store.takeLatest();
            assertNotNull(frame);
            assertEquals(NativeFrameStore.Presentation.CGB, frame.presentation());
            assertEquals(NativeFrameStore.Presentation.CGB, store.presentation());
            store.finishDrawing(frame);

            store.publish(new Display.GbcFrameReadyEvent(
                    new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));
            NativeFrameStore.Frame colorFrame = store.takeLatest();
            assertNotNull(colorFrame);
            assertEquals(NativeFrameStore.Presentation.CGB,
                    colorFrame.presentation());
            store.finishDrawing(colorFrame);
        } finally {
            store.close();
        }
    }

    @Test
    public void sgbProfileRejectsRawDmgButAcceptsDerivedSgbFrames() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            dmg[0] = 1;
            store.publish(new Display.DmgFrameReadyEvent(dmg));
            assertNull(store.snapshot());

            int[] sgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            sgb[0] = 0x00f80000;
            store.publish(new SgbDisplay.SgbFrameReadyEvent(sgb, false));
            NativeFrameStore.Snapshot frame = requireSnapshot(store);
            assertEquals(Display.DISPLAY_WIDTH, frame.width());
            assertEquals(Display.DISPLAY_HEIGHT, frame.height());
            assertEquals(0xfff80000, frame.pixels()[0]);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
        } finally {
            store.close();
        }
    }

    @Test
    public void sgbSkinTracksBorderedFramesWithoutChangingAClaimedFrame() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            int[] borderedPixels = new int[
                    SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
            store.publish(new SgbDisplay.SgbFrameReadyEvent(borderedPixels, true));
            NativeFrameStore.Frame bordered = store.takeLatest();
            assertNotNull(bordered);
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    bordered.presentation());
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    store.presentation());

            int[] centerPixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            store.publish(new SgbDisplay.SgbFrameReadyEvent(centerPixels, false));
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    bordered.presentation());
            store.finishDrawing(bordered);

            NativeFrameStore.Frame borderless = store.takeLatest();
            assertNotNull(borderless);
            assertEquals(NativeFrameStore.Presentation.DMG, borderless.presentation());
            store.finishDrawing(borderless);

            store.publish(new SgbDisplay.SgbFrameReadyEvent(borderedPixels, true));
            NativeFrameStore.Frame restored = store.takeLatest();
            assertNotNull(restored);
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    restored.presentation());
            store.finishDrawing(restored);
        } finally {
            store.close();
        }
    }

    @Test
    public void malformedSgbLengthAbortsWritingSlotAndReclaimsPrimarySlot() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            int borderedExpected = SuperGameboy.SGB_DISPLAY_WIDTH
                    * SuperGameboy.SGB_DISPLAY_HEIGHT;
            assertThrows(IllegalArgumentException.class,
                    () -> store.publish(new SgbDisplay.SgbFrameReadyEvent(
                            new int[borderedExpected - 1], true)));
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());

            int expected = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;
            int[] valid = new int[expected];
            valid[0] = 0x00010203;
            store.publish(new SgbDisplay.SgbFrameReadyEvent(valid, false));
            NativeFrameStore.Frame frame = store.takeLatest();
            assertNotNull(frame);
            assertSame(store.bufferAt(0), frame.pixels());
            store.finishDrawing(frame);
            NativeFrameStore.Snapshot snapshot = requireSnapshot(store);
            assertEquals(0xff010203, snapshot.pixels()[0]);
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
        } finally {
            store.close();
        }
    }

    @Test
    public void staleReservationCannotPublishOrAbortANewWriter() throws Exception {
        NativeFrameStore store = new NativeFrameStore();
        try {
            long stale = reserve(store);
            store.beginBenchmarkEpoch(1L);
            long current = reserve(store);
            assertTrue((stale & 3L) != (current & 3L));

            // The old owner is allowed to release its preserved WRITING claim after the epoch
            // reset. A new writer can then claim the same slot.
            publish(store, stale);
            long replacement = reserve(store);
            assertTrue((stale & 3L) == (replacement & 3L));
            assertTrue(stale != replacement);

            publish(store, stale);
            assertNull(store.snapshot());
            abort(store, stale);
            assertNull(store.snapshot());
            publish(store, replacement);
            assertNotNull(store.snapshot());
            publish(store, current);
            assertNotNull(store.snapshot());
        } finally {
            store.close();
        }
    }

    @Test
    public void clearInvalidatesAnInFlightReservationBeforeAReplacementPublishes() throws Exception {
        NativeFrameStore store = new NativeFrameStore();
        AtomicInteger notifications = new AtomicInteger();
        NativeFrameStore.Listener listener = notifications::incrementAndGet;
        store.addListener(listener);
        try {
            long stale = reserve(store);
            store.clear();
            int afterClear = notifications.get();
            long current = reserve(store);
            assertTrue((stale & 3L) != (current & 3L));

            publish(store, stale);
            assertNull(store.snapshot());
            assertEquals(afterClear, notifications.get());

            publish(store, current);
            assertNotNull(store.snapshot());
            assertEquals(afterClear + 1, notifications.get());
        } finally {
            store.removeListener(listener);
            store.close();
        }
    }

    @Test
    public void profileTransitionFromSgbToDmgSurvivesClear() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            store.clear();
            assertEquals(NativeFrameStore.Presentation.DMG, store.presentation());
            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            store.publish(new Display.DmgFrameReadyEvent(dmg));
            assertNull(store.snapshot());

            store.setHardwareProfile(HardwareProfileRegistry.DMG);
            store.clear();
            store.publish(new Display.DmgFrameReadyEvent(dmg));
            assertNotNull(store.snapshot());
        } finally {
            store.close();
        }
    }

    @Test
    public void replacementClearAndBenchmarkEpochRetainTheLastPresentation() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            int[] pixels = new int[
                    SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
            store.publish(new SgbDisplay.SgbFrameReadyEvent(pixels, true));
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    store.presentation());

            store.beginBenchmarkEpoch(9L);
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    store.presentation());

            store.clear();
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    store.presentation());
        } finally {
            store.close();
        }
    }

    @Test
    public void successfulStopClearRestoresTheDefaultDmgPresentation() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            store.setHardwareProfile(HardwareProfileRegistry.SGB);
            int[] pixels = new int[
                    SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
            store.publish(new SgbDisplay.SgbFrameReadyEvent(pixels, true));
            assertEquals(NativeFrameStore.Presentation.SGB_BORDER,
                    store.presentation());

            store.clearToDefaultPresentation();
            assertEquals(NativeFrameStore.Presentation.DMG,
                    store.presentation());
            store.publish(new Display.DmgFrameReadyEvent(
                    new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));
            assertNotNull(store.snapshot());
        } finally {
            store.close();
        }
    }

    @Test
    public void claimingTheNewestFrameDropsStaleQueuedFramesWithoutRetainingCorePixels() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            int[] first = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            int[] second = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            first[0] = 1;
            second[0] = 3;
            store.publish(new Display.DmgFrameReadyEvent(first));
            store.publish(new Display.DmgFrameReadyEvent(second));
            first[0] = 0;
            second[0] = 0;

            NativeFrameStore.Frame frame = store.takeLatest();
            assertNotNull(frame);
            assertEquals(0xff051f2a, frame.pixels()[0]);
            store.finishDrawing(frame);
            assertEquals(0xff051f2a, requireSnapshot(store).pixels()[0]);
            assertEquals(1, store.droppedFrames());
        } finally {
            store.close();
        }
    }

    @Test
    public void reusingPublishedSlotsAndTakingNewestCountEachDiscardOnce() {
        NativeFrameStore store = new NativeFrameStore();
        try {
            int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int frame = 0; frame < 4; frame++) {
                pixels[0] = frame;
                store.publish(new Display.DmgFrameReadyEvent(pixels));
            }
            assertEquals(1, store.droppedFrames());

            NativeFrameStore.Frame newest = store.takeLatest();
            assertNotNull(newest);
            assertEquals(3, store.droppedFrames());
            store.finishDrawing(newest);
        } finally {
            store.close();
        }
    }

    private static NativeFrameStore.Snapshot requireSnapshot(NativeFrameStore store) {
        NativeFrameStore.Snapshot frame = store.snapshot();
        assertNotNull(frame);
        return frame;
    }

    private static long reserve(NativeFrameStore store) throws Exception {
        var method = NativeFrameStore.class.getDeclaredMethod(
                "reserve", int.class, int.class);
        method.setAccessible(true);
        return (long) method.invoke(store, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
    }

    private static void publish(NativeFrameStore store, long claim) throws Exception {
        var method = NativeFrameStore.class.getDeclaredMethod("publish", long.class);
        method.setAccessible(true);
        method.invoke(store, claim);
    }

    private static void abort(NativeFrameStore store, long claim) throws Exception {
        var method = NativeFrameStore.class.getDeclaredMethod("abortWriting", long.class);
        method.setAccessible(true);
        method.invoke(store, claim);
    }
}
