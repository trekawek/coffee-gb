package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

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
        } finally {
            store.close();
        }
    }

    private static NativeFrameStore.Snapshot requireSnapshot(NativeFrameStore store) {
        NativeFrameStore.Snapshot frame = store.snapshot();
        assertNotNull(frame);
        return frame;
    }
}
