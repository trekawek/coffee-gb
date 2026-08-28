package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Host-only frame skipping must stay aligned with physical PPU frames, not controller frames. */
public class GameboyAdaptiveFrameRenderingTest {

    @Test
    public void requestLatchesAtVblankAndNeverSuppressesTwoPhysicalFramesInARow() throws Exception {
        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = newGameboy()) {
            AtomicInteger published = new AtomicInteger();
            eventBus.register(e -> published.incrementAndGet(), Display.DmgFrameReadyEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            gameboy.requestFrameRenderSuppression(true);

            // This completed scanout started before the request reached its physical edge.
            runToNextFrame(gameboy);
            assertEquals(1, published.get());

            // The next frame is entirely suppressed, then the cap requires a presentation.
            runToNextFrame(gameboy);
            assertEquals(1, published.get());
            runToNextFrame(gameboy);
            assertEquals(2, published.get());
            runToNextFrame(gameboy);
            assertEquals(2, published.get());
        }
    }

    @Test
    public void restoreHoldsThePartialHostFrameThenReturnsToFullRendering() throws Exception {
        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = newGameboy()) {
            AtomicInteger published = new AtomicInteger();
            eventBus.register(e -> published.incrementAndGet(), Display.DmgFrameReadyEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            runToNextFrame(gameboy);
            assertEquals(1, published.get());
            for (int i = 0; i < 123; i++) {
                gameboy.tick();
            }
            var state = gameboy.captureState();

            for (int i = 0; i < 321; i++) {
                gameboy.tick();
            }
            gameboy.restoreState(state);
            assertFalse(gameboy.isCurrentVisibleFrameFullyRendering());

            // A restored mid-scanout state must not compose a new host frame from its tail.
            runToNextFrame(gameboy);
            assertEquals(1, published.get());
            runToNextFrame(gameboy);
            assertEquals(2, published.get());
        }
    }

    @Test
    public void silentRollbackRestoreResetsTheWriteCursorWhenPacingResumes() throws Exception {
        try (EventBus sourceBus = new EventBusImpl();
             EventBus targetBus = new EventBusImpl();
             Gameboy source = newGameboy();
             Gameboy target = newGameboy()) {
            configureStaticPattern(source);
            configureStaticPattern(target);
            source.init(sourceBus, SerialEndpoint.NULL_ENDPOINT, null);
            List<int[]> targetFrames = new ArrayList<>();
            targetBus.register(
                    e -> targetFrames.add(e.pixels().clone()), Display.DmgFrameReadyEvent.class);
            target.init(targetBus, SerialEndpoint.NULL_ENDPOINT, null);

            runToNextFrame(source, target);
            runToNextFrame(source, target);
            target.requestFrameRenderSuppression(true);
            runToNextFrame(source, target);
            assertFalse(target.isCurrentVisibleFrameFullyRendering());

            runUntilLineDot(source, target, 72, 200);
            var replayHead = source.captureState();
            target.restoreStateSilently(replayHead);
            assertFalse("silent rollback preserves the live pacing gate",
                    target.isCurrentVisibleFrameFullyRendering());
            targetFrames.clear();

            // Linked rollback installs replay state silently while presentation may already be
            // suppressed by pacing debt. The restored nonzero cursor must be discarded at the
            // VBlank where output resumes. Sustained debt then yields two comparable publications.
            runToNextFrame(target);
            runToNextFrame(target);
            runToNextFrame(target);
            runToNextFrame(target);

            assertEquals(2, targetFrames.size());
            assertArrayEquals(targetFrames.get(1), targetFrames.get(0));
        }
    }

    @Test
    public void silentRestorePreservesTheExistingHostRenderGate() throws Exception {
        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = newGameboy()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            var state = gameboy.captureState();

            gameboy.restoreStateSilently(state);
            assertTrue(gameboy.isCurrentVisibleFrameFullyRendering());

            gameboy.requestFrameRenderSuppression(true);
            runToNextFrame(gameboy);
            assertFalse(gameboy.isCurrentVisibleFrameFullyRendering());

            gameboy.restoreStateSilently(state);
            assertFalse(gameboy.isCurrentVisibleFrameFullyRendering());
        }
    }

    @Test
    public void superGameBoyKeepsEveryTransferFrameButMayShedFinalHostPresentation()
            throws Exception {
        try (EventBus referenceBus = new EventBusImpl();
             EventBus pacedBus = new EventBusImpl();
             Gameboy reference = newSgbGameboy();
             Gameboy paced = newSgbGameboy()) {
            AtomicInteger dmgTransfers = new AtomicInteger();
            AtomicInteger sgbPresentations = new AtomicInteger();
            List<int[]> referenceAudio = new ArrayList<>();
            List<int[]> pacedAudio = new ArrayList<>();
            pacedBus.register(e -> dmgTransfers.incrementAndGet(), Display.DmgFrameReadyEvent.class);
            pacedBus.register(
                    e -> sgbPresentations.incrementAndGet(), SgbDisplay.SgbFrameReadyEvent.class);
            referenceBus.register(e -> referenceAudio.add(e.buffer().clone()),
                    Sound.SoundSampleEvent.class);
            pacedBus.register(e -> pacedAudio.add(e.buffer().clone()),
                    Sound.SoundSampleEvent.class);
            reference.init(referenceBus, SerialEndpoint.NULL_ENDPOINT, null);
            paced.init(pacedBus, SerialEndpoint.NULL_ENDPOINT, null);

            paced.requestFrameRenderSuppression(true);
            for (int frame = 0; frame < 4; frame++) {
                runToNextFrame(reference, paced);
            }

            assertEquals("SGB DMG pixels remain emulated transfer input", 4, dmgTransfers.get());
            assertEquals("sustained debt still presents every other final SGB frame",
                    2, sgbPresentations.get());
            assertAudioEquals(referenceAudio, pacedAudio);
            assertDeepStateEquals("suppressed SGB host output", reference.captureStateWithoutTimeSource(),
                    paced.captureStateWithoutTimeSource());

            paced.requestFrameRenderSuppression(false);
            runToNextFrame(reference, paced);
            runToNextFrame(reference, paced);
            assertEquals(6, dmgTransfers.get());
            assertEquals("presentation resumes after the physical handoff", 4,
                    sgbPresentations.get());
            assertAudioEquals(referenceAudio, pacedAudio);
            assertDeepStateEquals("resumed SGB host output", reference.captureStateWithoutTimeSource(),
                    paced.captureStateWithoutTimeSource());

            var visibleState = paced.captureState();
            paced.requestFrameRenderSuppression(true);
            runToNextFrame(paced);
            assertFalse(paced.isCurrentVisibleFrameFullyRendering());
            paced.restoreStateSilently(visibleState);
            assertFalse("silent rollback preserves the derived SGB pacing gate",
                    paced.isCurrentVisibleFrameFullyRendering());
            paced.restoreState(visibleState);
            assertTrue("manual restore clears the derived SGB pacing gate",
                    paced.isCurrentVisibleFrameFullyRendering());

            paced.requestFrameRenderSuppression(true);
            runToNextFrame(paced);
            assertFalse(paced.isCurrentVisibleFrameFullyRendering());
            paced.resumeFullFrameRenderingAfterRewindRestore();
            assertTrue("rewind restore immediately clears the derived SGB host gate",
                    paced.isCurrentVisibleFrameFullyRendering());
        }
    }

    private static void assertAudioEquals(List<int[]> expected, List<int[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals("audio event " + i, expected.get(i), actual.get(i));
        }
    }

    private static void assertDeepStateEquals(String path, Object expected, Object actual)
            throws Exception {
        if (expected == null || actual == null) {
            if (expected != actual) {
                throw new AssertionError(path + " expected=" + expected + " actual=" + actual);
            }
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            throw new AssertionError(path + " type expected=" + expected.getClass()
                    + " actual=" + actual.getClass());
        }
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            if (length != Array.getLength(actual)) {
                throw new AssertionError(path + " array length");
            }
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']', Array.get(expected, i),
                        Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof List<?> expectedList) {
            List<?> actualList = (List<?>) actual;
            if (expectedList.size() != actualList.size()) {
                throw new AssertionError(path + " list size");
            }
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']', expectedList.get(i),
                        actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            if (!expected.equals(actual)) {
                throw new AssertionError(path + " expected=" + expected + " actual=" + actual);
            }
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            component.getAccessor().setAccessible(true);
            assertDeepStateEquals(path + '.' + component.getName(),
                    component.getAccessor().invoke(expected),
                    component.getAccessor().invoke(actual));
        }
    }

    private static Gameboy newGameboy() throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(testRom()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy newSgbGameboy() throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(testRom()))
                .setHardwareProfile(HardwareProfileRegistry.SGB2)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] testRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x18; // jr $0100
        rom[0x101] = (byte) 0xfe;
        rom[0x147] = 0;
        return rom;
    }

    private static void configureStaticPattern(Gameboy gameboy) {
        for (int tile = 0; tile < 4; tile++) {
            int low = (tile & 1) == 0 ? 0x00 : 0xff;
            int high = (tile & 2) == 0 ? 0x00 : 0xff;
            for (int row = 0; row < 8; row++) {
                gameboy.getGpu().getVideoRam().setByte(0x8000 + tile * 16 + row * 2, low);
                gameboy.getGpu().getVideoRam().setByte(0x8000 + tile * 16 + row * 2 + 1, high);
            }
        }
        for (int row = 0; row < 32; row++) {
            for (int column = 0; column < 32; column++) {
                int tile = (row * row + row * 3 + column * 2 + column / 3) & 3;
                gameboy.getGpu().getVideoRam().setByte(0x9800 + row * 32 + column, tile);
            }
        }
        gameboy.getGpu().setByte(0xff47, 0xe4);
    }

    private static void runUntilLineDot(Gameboy first, Gameboy second, int line, int dot) {
        for (int tick = 0; tick < 100_000; tick++) {
            if (first.getGpu().getLine() == line && first.getGpu().getTicksInLine() == dot) {
                return;
            }
            first.tick();
            second.tick();
        }
        assertTrue("PPUs did not reach the requested line and dot", false);
    }

    private static void runToNextFrame(Gameboy first, Gameboy second) {
        for (int tick = 0; tick < 100_000; tick++) {
            boolean firstFrame = first.tick();
            boolean secondFrame = second.tick();
            if (firstFrame || secondFrame) {
                assertEquals(firstFrame, secondFrame);
                return;
            }
        }
        assertTrue("PPUs did not reach VBlank", false);
    }

    private static void runToNextFrame(Gameboy gameboy) {
        for (int tick = 0; tick < 100_000; tick++) {
            if (gameboy.tick()) {
                return;
            }
        }
        assertTrue("PPU did not reach VBlank", false);
    }

}
