package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.Profile;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.Scenario;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Focused proof for the LCDC.7-on retention seam. The matrix comparisons use the same
 * PERFORMANCE core: the scalar side disables batching, while the other side uses normal
 * production batching. The final import case separately checks that a retained line can be
 * handed to Accuracy without rendering a second copy of the already composed line.
 */
public final class PerformanceLcdcRetentionTest {
    private static final int LCDC = 0xff40;
    private static final int MAX_TICKS = 3 * 456 * 154;
    private static final Field SCANLINE_END = field("performanceScanlineEndTick");

    private static final Profile[] LCDC_PROFILES = {
            Profile.DMG, Profile.CGB, Profile.CGB_X2, Profile.CGB0, Profile.CGB0_X2
    };

    @Test
    public void matrixLcdcV2CpuOnOnWritesRetainTheDirectLineAndMatchScalarPixels()
            throws Exception {
        for (Profile profile : LCDC_PROFILES) {
            try (Fixture scalar = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), true, null);
                    Fixture batched = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), false,
                            new PerformanceDiagnostics(4_096))) {
                Evidence evidence = driveUntilFrameAndHandoff(scalar, batched, MAX_TICKS);
                assertTrue(profile + " did not execute a real CPU LCDC.7-on->on write",
                        evidence.onOnWrites > 0);
                assertTrue(profile + " lost the direct cursor at an enabled->enabled write",
                        evidence.retainedWrites > 0);
                assertTrue(profile + " did not complete the retained predicted handoff",
                        evidence.handoffs > 0);
                assertTrue(profile + " did not arm a direct line",
                        evidence.arms > 0);

                PerformanceDiagnostics.Snapshot snapshot = batched.diagnostics.snapshot();
                assertTrue(profile + " diagnostics did not record a direct arm",
                        snapshot.directLines() > 0);
                long completedLines = batched.gameboy.getGpu().getPerformanceScanlineLines();
                assertTrue(profile + " arm/completion accounting under lifecycle fences",
                        snapshot.directLines() >= completedLines
                                && snapshot.directLines() - completedLines <= 1);
                assertTrue(profile + " did not execute a positive scalar/epoch path",
                        snapshot.ticks() > 0);
                assertEquivalent(scalar.gameboy, batched.gameboy, profile + " final CPU/PPU seam");
                assertFramesEqual(profile + " matrix LCDC v2", scalar.frames, batched.frames);
            }
        }
    }

    @Test
    public void matrixLcdcV2BatchedWindowsMatchScalarCanonicalState() throws Exception {
        for (Profile profile : LCDC_PROFILES) {
            try (Fixture scalar = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), true, null);
                    Fixture batched = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), false,
                            new PerformanceDiagnostics(4_096))) {
                int budget = 2 * 456 * 154;
                long scalarFrames = scalar.gameboy.runTicks(budget);
                long batchedFrames = batched.gameboy.runTicks(budget);
                assertEquals(profile + " batched/scalar frame callbacks",
                        scalarFrames, batchedFrames);
                assertDeepStateEquals(profile + " batched/scalar canonical endpoint",
                        scalar.gameboy.captureStateWithoutTimeSource(),
                        batched.gameboy.captureStateWithoutTimeSource());
                assertTrue(profile + " batched path did no positive committed work",
                        batched.gameboy.getPerformanceEpochTicks()
                                + batched.gameboy.getPerformanceBulkTicks()
                                + batched.gameboy.getGpu().getPerformanceScanlineFastTicks() > 0);
                assertTrue(profile + " batched path did not complete a direct line",
                        batched.gameboy.getGpu().getPerformanceScanlineLines() > 0);
                assertTrue(profile + " diagnostics did not record a direct arm",
                        batched.diagnostics.snapshot().directLines() > 0);
            }
        }
    }

    @Test
    public void delayedCpuWindowEnableIsOldLineSnapshotAndNextLineState() throws Exception {
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB, Profile.CGB_X2,
                Profile.CGB0, Profile.CGB0_X2}) {
            byte[] image = delayedWindowEnableImage(profile);
            try (Fixture scalar = fixture(profile, image, true, null);
                    Fixture batched = fixture(profile, image, false,
                            new PerformanceDiagnostics(4_096))) {
                int writeLine = -1;
                int counterBeforeWrite = Integer.MIN_VALUE;
                boolean retained = false;
                boolean nextLineObserved = false;
                for (int tick = 0; tick < MAX_TICKS && !nextLineObserved; tick++) {
                    Gpu gpu = batched.gameboy.getGpu();
                    boolean beforeCursor = gpu.isPerformanceScanlineCursorActive();
                    int beforeEnd = SCANLINE_END.getInt(gpu);
                    int beforeDot = gpu.getTicksInLine();
                    int beforeLcdc = gpu.getByte(LCDC);
                    long scalarFrames = scalar.gameboy.runTicks(1);
                    long batchedFrames = batched.gameboy.runTicks(1);
                    assertEquals(profile + " frame event at tick " + tick,
                            scalarFrames, batchedFrames);
                    int afterLcdc = gpu.getByte(LCDC);
                    if (beforeLcdc != afterLcdc && (beforeLcdc & 0x80) != 0
                            && (afterLcdc & 0x80) != 0 && afterLcdc == 0xb1) {
                        assertTrue(profile + " window write was not a direct-line CPU write",
                                beforeCursor);
                        assertTrue(profile + " window write ended the current direct line",
                                gpu.isPerformanceScanlineCursorActive());
                        assertEquals(profile + " window write moved the predicted handoff",
                                beforeEnd, SCANLINE_END.getInt(gpu));
                        retained = true;
                        writeLine = gpu.getLine();
                        counterBeforeWrite = gpu.getPerformanceWindowLineCounter();
                    }
                    if (beforeCursor && !gpu.isPerformanceScanlineCursorActive()) {
                        // A CGB speed-switch tail may invalidate a cursor that was armed
                        // before STOP has completed.  That is an intentional lifecycle fence,
                        // not the retained-line handoff under test.  Once the authored write
                        // has been observed on a direct line, every subsequent drop must use
                        // that line's predicted endpoint.
                        if (retained && gpu.getLine() == writeLine) {
                            assertEquals(profile + " direct handoff was not at the predicted dot",
                                    beforeEnd, beforeDot + 1);
                        }
                    }
                    if (retained && gpu.getLine() > writeLine
                            && gpu.getPerformanceWindowLineCounter() > counterBeforeWrite) {
                        assertEquals(profile + " next line did not retain the authored LCDC value",
                                0xb1, gpu.getByte(LCDC));
                        nextLineObserved = true;
                    }
                    assertEquivalent(scalar.gameboy, batched.gameboy,
                            profile + " window CPU/PPU tick " + tick);
                }
                assertTrue(profile + " did not observe the authored on->on window write", retained);
                assertTrue(profile + " did not observe the following line's window state",
                        nextLineObserved);
                for (int tail = 0; tail < 2 * 456 * 154
                        && scalar.frames.isEmpty(); tail++) {
                    assertEquals(profile + " delayed-window tail frame " + tail,
                            scalar.gameboy.runTicks(1), batched.gameboy.runTicks(1));
                    assertEquivalent(scalar.gameboy, batched.gameboy,
                            profile + " delayed-window tail " + tail);
                }
                assertFramesEqual(profile + " delayed window", scalar.frames, batched.frames);
            }
        }
    }

    @Test
    public void authoredCpuLcdOffOnResetsLineAndDropsAnyCursor() throws Exception {
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB, Profile.CGB_X2,
                Profile.CGB0}) {
            byte[] image = lcdOffOnImage(profile);
            try (Fixture scalar = fixture(profile, image, true, null);
                    Fixture batched = fixture(profile, image, false, null)) {
                settleSpeedSwitch(scalar, batched, profile);
                boolean sawOff = false;
                boolean sawOnReset = false;
                for (int tick = 0; tick < MAX_TICKS && !sawOnReset; tick++) {
                    boolean beforeLcd = batched.gameboy.getGpu().isLcdEnabled();
                    long scalarFrames = scalar.gameboy.runTicks(1);
                    long batchedFrames = batched.gameboy.runTicks(1);
                    assertEquals(profile + " off/on frame event " + tick,
                            scalarFrames, batchedFrames);
                    boolean afterLcd = batched.gameboy.getGpu().isLcdEnabled();
                    if (beforeLcd && !afterLcd) {
                        sawOff = true;
                        assertFalse(profile + " LCD-off write retained a direct cursor",
                                batched.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    }
                    if (sawOff && !beforeLcd && afterLcd) {
                        sawOnReset = true;
                        assertEquals(profile + " LCD-on reset line", 0,
                                batched.gameboy.getGpu().getLine());
                        assertTrue(profile + " LCD-on did not enter first line",
                                batched.gameboy.getGpu().isFirstLine());
                        assertFalse(profile + " LCD-on retained old direct cursor",
                                batched.gameboy.getGpu().isPerformanceScanlineCursorActive());
                    }
                    assertEquivalent(scalar.gameboy, batched.gameboy,
                            profile + " off/on CPU/PPU tick " + tick);
                }
                assertTrue(profile + " CPU never disabled the LCD (lcdc="
                                + Integer.toHexString(batched.gameboy.getGpu().getByte(LCDC))
                                + ", pc=" + Integer.toHexString(
                                batched.gameboy.getCpu().getRegisters().getPC())
                                + ", speed=" + batched.gameboy.getSpeedMode().getSpeedMode()
                                + ", cpu=" + batched.gameboy.getCpu().getState() + ")", sawOff);
                assertTrue(profile + " CPU LCD-off/on did not reset the PPU", sawOnReset);
            }
        }
    }

    @Test
    public void retainedCursorCaptureRestorePreservesPredictedHandoffAndContinuation()
            throws Exception {
        // A fresh x2 receiver currently exposes the independent Gameboy restore-order bug:
        // GPU state is restored before SpeedMode, so prepareForTick sees a stale x1 domain
        // and clears the imported cursor.  Keep this retention continuation proof on stable
        // domains; the fresh-target x2 failure remains explicit in the Accuracy import test.
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB0}) {
            try (Fixture source = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), false, null);
                    Fixture peer = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), false, null)) {
                // On x2 the first cursor belongs to the pre-STOP startup topology and is
                // intentionally invalidated by the speed switch.  Restore a real retained
                // line after that lifecycle fence, so this test exercises the imported
                // cursor rather than the startup cursor.
                seekRetainedCursor(source, profile);
                int end = SCANLINE_END.getInt(source.gameboy.getGpu());
                var saved = source.gameboy.captureStateWithoutTimeSource();
                peer.gameboy.restoreStateSilently(saved);
                assertTrue(profile + " restore lost the direct cursor",
                        peer.gameboy.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile + " restore changed predicted handoff", end,
                        SCANLINE_END.getInt(peer.gameboy.getGpu()));
                for (int tick = 0; tick < 64; tick++) {
                    assertEquals(profile + " restore frame event " + tick,
                            source.gameboy.runTicks(1), peer.gameboy.runTicks(1));
                    assertEquivalent(source.gameboy, peer.gameboy,
                            profile + " restore continuation " + tick);
                    assertLocksEqual(source.gameboy, peer.gameboy,
                            profile + " restore locks " + tick);
                }
                assertDeepStateEquals(profile + " restored continuation canonical state",
                        source.gameboy.captureStateWithoutTimeSource(),
                        peer.gameboy.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void retainedCursorImportedIntoAccuracyFinishesOnlyTheExistingLine() throws Exception {
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB_X2, Profile.CGB0}) {
            try (Fixture source = fixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile), false, null);
                    Fixture accuracy = accuracyFixture(profile, PerformanceWorkloads.image(
                            Scenario.LCDC_WRITES, profile))) {
                seekRetainedCursor(source, profile);
                int end = SCANLINE_END.getInt(source.gameboy.getGpu());
                accuracy.gameboy.restoreStateSilently(
                        source.gameboy.captureStateWithoutTimeSource());
                assertEquals(ExecutionMode.ACCURACY, accuracy.gameboy.getExecutionMode());
                assertTrue(profile + " Accuracy import lost the existing cursor",
                        accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile + " Accuracy import changed the endpoint", end,
                        SCANLINE_END.getInt(accuracy.gameboy.getGpu()));
                source.gameboy.getGpu().setPerformanceScanlineEnabled(false);

                int continuation = 0;
                while (accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive()
                        && continuation++ < 512) {
                    assertEquals(profile + " imported cursor frame event",
                            source.gameboy.tick(), accuracy.gameboy.tick());
                    assertEquivalent(source.gameboy, accuracy.gameboy,
                            profile + " imported cursor continuation " + continuation);
                }
                assertFalse(profile + " Accuracy import stranded the cursor",
                        accuracy.gameboy.getGpu().isPerformanceScanlineCursorActive());
                assertTrue(profile + " Accuracy import did not finish the line",
                        continuation > 0);
                for (int tick = 0; tick < 456; tick++) {
                    assertEquals(profile + " imported scalar tail frame event",
                            source.gameboy.tick(), accuracy.gameboy.tick());
                    assertEquivalent(source.gameboy, accuracy.gameboy,
                            profile + " imported scalar tail " + tick);
                }
            }
        }
    }

    private static void seekRetainedCursor(Fixture fixture, Profile profile) throws Exception {
        fixture.gameboy.getGpu().setPerformanceScanlineEnabled(true);
        settleSpeedSwitch(fixture, profile);
        int guard = 0;
        while (guard++ < MAX_TICKS) {
            Gpu gpu = fixture.gameboy.getGpu();
            boolean beforeCursor = gpu.isPerformanceScanlineCursorActive();
            int beforeLcdc = gpu.getByte(LCDC);
            // Keep the same PERFORMANCE scheduler path used by the matrix proof.  A direct
            // tick() call can take the scalar fallback and miss the direct owner boundary
            // that the retained cursor is meant to witness.
            fixture.gameboy.runTicks(1);
            int afterLcdc = gpu.getByte(LCDC);
            if (beforeCursor && gpu.isPerformanceScanlineCursorActive()
                    && beforeLcdc != afterLcdc && (beforeLcdc & 0x80) != 0
                    && (afterLcdc & 0x80) != 0) {
                assertTrue(profile + " retained-cursor seek did not find an active line",
                        gpu.isPerformanceScanlineCursorActive());
                return;
            }
        }
        throw new AssertionError(profile + " did not observe a post-startup retained cursor");
    }

    private static void settleSpeedSwitch(Fixture fixture, Profile profile) throws Exception {
        if (!profile.doubleSpeed) {
            return;
        }
        int guard = 0;
        while ((fixture.gameboy.getSpeedMode().getSpeedMode() != 2
                        || fixture.gameboy.getCpu().isSpeedSwitching())
                && guard++ < MAX_TICKS) {
            fixture.gameboy.runTicks(1);
        }
        assertFalse(profile + " speed switch did not complete before retained-cursor seek",
                fixture.gameboy.getCpu().isSpeedSwitching());
        assertEquals(profile + " retained-cursor seek did not reach x2", 2,
                fixture.gameboy.getSpeedMode().getSpeedMode());
    }

    private static Evidence driveUntilFrameAndHandoff(Fixture scalar, Fixture batched,
                                                       int maxTicks) throws Exception {
        Evidence evidence = new Evidence();
        for (int tick = 0; tick < maxTicks; tick++) {
            Gpu gpu = batched.gameboy.getGpu();
            boolean beforeCursor = gpu.isPerformanceScanlineCursorActive();
            int beforeEnd = SCANLINE_END.getInt(gpu);
            int beforeDot = gpu.getTicksInLine();
            int beforeLcdc = gpu.getByte(LCDC);
            long scalarFrames = scalar.gameboy.runTicks(1);
            long batchedFrames = batched.gameboy.runTicks(1);
            assertEquals("frame event at tick " + tick, scalarFrames, batchedFrames);
            int afterLcdc = gpu.getByte(LCDC);
            boolean afterCursor = gpu.isPerformanceScanlineCursorActive();
            if (!beforeCursor && afterCursor) evidence.arms++;
            if (beforeLcdc != afterLcdc && (beforeLcdc & 0x80) != 0
                    && (afterLcdc & 0x80) != 0 && beforeCursor) {
                evidence.onOnWrites++;
                if (afterCursor && SCANLINE_END.getInt(gpu) == beforeEnd) {
                    evidence.retainedWrites++;
                    evidence.retainedLine = gpu.getLine();
                    evidence.retainedEnd = beforeEnd;
                    assertLocksEqual(scalar.gameboy, batched.gameboy,
                            "retained LCDC locks at tick " + tick);
                }
            }
            if (beforeCursor && !afterCursor) {
                // The initial CGB_X2 STOP can end a pre-switch cursor.  Count only the
                // predicted handoff for a line whose on->on write was already retained.
                if (evidence.retainedWrites > 0 && gpu.getLine() == evidence.retainedLine
                        && beforeEnd == evidence.retainedEnd) {
                    assertEquals("cursor ended before its predicted handoff at tick " + tick,
                            beforeEnd, beforeDot + 1);
                    evidence.handoffs++;
                }
            }
            assertEquivalent(scalar.gameboy, batched.gameboy,
                    "LCDC v2 CPU/PPU tick " + tick);
            if (evidence.retainedWrites > 0 && evidence.handoffs > 0
                    && !scalar.frames.isEmpty() && !batched.frames.isEmpty()) {
                return evidence;
            }
        }
        return evidence;
    }

    private static void assertFramesEqual(String label, List<int[]> expected, List<int[]> actual) {
        assertEquals(label + " frame count", expected.size(), actual.size());
        assertTrue(label + " emitted no complete frame", !expected.isEmpty());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(label + " frame " + i, expected.get(i), actual.get(i));
        }
    }

    private static void assertEquivalent(Gameboy expected, Gameboy actual, String label) {
        assertEquals(label + " line", expected.getGpu().getLine(), actual.getGpu().getLine());
        assertEquals(label + " dot", expected.getGpu().getTicksInLine(), actual.getGpu().getTicksInLine());
        assertEquals(label + " mode", expected.getGpu().getMode(), actual.getGpu().getMode());
        assertEquals(label + " CPU state", expected.getCpu().getState(), actual.getCpu().getState());
        assertEquals(label + " AF", expected.getCpu().getRegisters().getAF(), actual.getCpu().getRegisters().getAF());
        assertEquals(label + " BC", expected.getCpu().getRegisters().getBC(), actual.getCpu().getRegisters().getBC());
        assertEquals(label + " DE", expected.getCpu().getRegisters().getDE(), actual.getCpu().getRegisters().getDE());
        assertEquals(label + " HL", expected.getCpu().getRegisters().getHL(), actual.getCpu().getRegisters().getHL());
        assertEquals(label + " SP", expected.getCpu().getRegisters().getSP(), actual.getCpu().getRegisters().getSP());
        assertEquals(label + " PC", expected.getCpu().getRegisters().getPC(), actual.getCpu().getRegisters().getPC());
        assertEquals(label + " speed", expected.getSpeedMode().getSpeedMode(), actual.getSpeedMode().getSpeedMode());
        assertLocksEqual(expected, actual, label + " lock state");
    }

    private static void assertLocksEqual(Gameboy expected, Gameboy actual, String label) {
        for (int address : new int[]{0x8000, 0xff40, 0xff41, 0xff44}) {
            assertEquals(label + " address " + Integer.toHexString(address),
                    expected.getGpu().getByte(address), actual.getGpu().getByte(address));
        }
        // DMG OAM reads are intentionally row-dependent and can mutate the read path while
        // the OAM bug is active.  Keep the lock witness side-effect free on DMG; native CGB
        // exposes the ordinary OAM byte and can include it in this cross-session comparison.
        if (expected.getGpu().isGbc() && actual.getGpu().isGbc()) {
            assertEquals(label + " address fe00", expected.getGpu().getByte(0xfe00),
                    actual.getGpu().getByte(0xfe00));
        }
    }

    /** Record/array-aware equality for the canonical machine-state graph. */
    private static void assertDeepStateEquals(String path, Object expected, Object actual) {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        assertEquals(path + " type", expected.getClass(), actual.getClass());
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(path + " length", length, Array.getLength(actual));
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        Array.get(expected, i), Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof List<?> expectedList) {
            List<?> actualList = (List<?>) actual;
            assertEquals(path + " size", expectedList.size(), actualList.size());
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        expectedList.get(i), actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            assertEquals(path, expected, actual);
            return;
        }
        try {
            for (RecordComponent component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepStateEquals(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot compare " + path, e);
        }
    }

    private static Fixture fixture(Profile profile, byte[] image, boolean scalar,
                                   PerformanceDiagnostics diagnostics) throws Exception {
        return new Fixture(profile, image, ExecutionMode.PERFORMANCE, scalar, diagnostics);
    }

    private static Fixture accuracyFixture(Profile profile, byte[] image) throws Exception {
        return new Fixture(profile, image, ExecutionMode.ACCURACY, false, null);
    }

    private static Field field(String name) {
        try {
            Field result = Gpu.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] delayedWindowEnableImage(Profile profile) {
        byte[] image = baseImage(profile);
        int p = programStart(image, profile);
        image[p++] = (byte) 0xf3;       // DI
        p = emitWaitForMode3(image, p);
        image[p++] = 0x3e; image[p++] = (byte) 0xb1; // LCD on + window on
        image[p++] = (byte) 0xe0; image[p++] = 0x40; // real CPU FF40 write
        image[p++] = 0x18; image[p] = (byte) 0xfe; // stable post-write loop
        return image;
    }

    private static byte[] lcdOffOnImage(Profile profile) {
        byte[] image = baseImage(profile);
        int p = programStart(image, profile);
        image[p++] = (byte) 0xf3;       // DI
        p = emitWaitForMode3(image, p);
        image[p++] = 0x3e; image[p++] = 0;
        image[p++] = (byte) 0xe0; image[p++] = 0x40; // real LCD-off write
        image[p++] = 0x3e; image[p++] = (byte) 0x91;
        image[p++] = (byte) 0xe0; image[p++] = 0x40; // real LCD-on write
        image[p++] = 0x18; image[p] = (byte) 0xfe; // stable loop
        return image;
    }

    private static void settleSpeedSwitch(Fixture scalar, Fixture batched, Profile profile)
            throws Exception {
        if (!profile.doubleSpeed) {
            return;
        }
        int guard = 0;
        while ((batched.gameboy.getSpeedMode().getSpeedMode() != 2
                        || batched.gameboy.getCpu().isSpeedSwitching())
                && guard++ < MAX_TICKS) {
            // Stop exactly at the completed STOP boundary.  A coarse chunk can execute
            // the one-shot FF40 off/on sequence before monitoring begins.
            int ticks = 1;
            assertEquals(profile + " speed-switch setup frame callbacks",
                    scalar.gameboy.runTicks(ticks), batched.gameboy.runTicks(ticks));
            assertEquivalent(scalar.gameboy, batched.gameboy,
                    profile + " speed-switch setup " + guard);
        }
        assertFalse(profile + " speed switch did not complete before LCDC fixture",
                batched.gameboy.getCpu().isSpeedSwitching());
    }

    private static int emitWaitForMode3(byte[] image, int p) {
        int loop = p;
        image[p++] = (byte) 0xf0; image[p++] = 0x41; // LD A,(STAT)
        image[p++] = (byte) 0xe6; image[p++] = 0x03; // mode bits
        image[p++] = (byte) 0xfe; image[p++] = 0x03; // PixelTransfer
        image[p++] = (byte) 0xc2; image[p++] = (byte) loop; image[p++] = (byte) (loop >> 8);
        return p;
    }

    private static byte[] baseImage(Profile profile) {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = profile.color ? (byte) 0x80 : 0;
        return image;
    }

    private static int programStart(byte[] image, Profile profile) {
        int p = 0x150;
        if (profile.doubleSpeed) {
            image[p++] = 0x3e; image[p++] = 1;
            image[p++] = (byte) 0xe0; image[p++] = 0x4d;
            image[p++] = 0x10; image[p++] = 0;
        }
        return p;
    }

    private static final class Evidence {
        private int arms;
        private int onOnWrites;
        private int retainedWrites;
        private int handoffs;
        private int retainedLine = -1;
        private int retainedEnd;
    }

    private static final class Fixture implements AutoCloseable {
        private final PlayerInputHub input = new PlayerInputHub();
        private final PlayerInputHub.SourceHandle source;
        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);
        private final Gameboy gameboy;
        private final List<int[]> frames = new ArrayList<>();
        private final PerformanceDiagnostics diagnostics;

        private Fixture(Profile profile, byte[] image, ExecutionMode executionMode,
                        boolean scalar, PerformanceDiagnostics diagnostics) throws Exception {
            eventBus.register(event -> frames.add(event.pixels().clone()),
                    Display.DmgFrameReadyEvent.class);
            eventBus.register(event -> frames.add(event.pixels().clone()),
                    Display.GbcFrameReadyEvent.class);
            gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                    .setHardwareProfile(profile.hardware)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(executionMode)
                    .setRtcTimeSource(() -> 0L)
                    .setPlayerInputSource(input)
                    .setSupportBatterySave(false)
                    .build();
            source = input.openSource(0);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            this.diagnostics = diagnostics;
            if (diagnostics != null) gameboy.setPerformanceDiagnostics(diagnostics);
            if (executionMode == ExecutionMode.PERFORMANCE) {
                gameboy.setPerformanceBatchingEnabled(!scalar);
            }
        }

        @Override
        public void close() {
            source.close();
            gameboy.closeSilently();
            eventBus.close();
        }
    }
}
