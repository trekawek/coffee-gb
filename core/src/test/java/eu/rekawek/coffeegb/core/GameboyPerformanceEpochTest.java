package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Focused native-CGB smoke coverage for the coarse PERFORMANCE epoch entrance. */
public final class GameboyPerformanceEpochTest {

    @Test
    public void nativeDoubleSpeedLoopUsesEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            // The first batch performs the speed-switch countdown on the legacy scheduler;
            // the next owner batch starts in native double speed and enters the epoch lane.
            gameboy.runTicks(160_000);
            gameboy.runTicks(100_000);
            assertTrue("test ROM did not enter CGB double speed",
                    gameboy.getSpeedMode().getSpeedMode() == 2);
            assertTrue("native CGB epoch lane did not run speed="
                            + gameboy.getSpeedMode().getSpeedMode()
                            + " cpu=" + gameboy.getCpu().getState()
                            + " mode=" + gameboy.getGpu().getMode()
                            + " line=" + gameboy.getGpu().getLine()
                            + " lcd=" + gameboy.getGpu().isLcdEnabled()
                            + " cpuEpoch=" + gameboy.getCpu().getPerformanceEpochCount()
                            + " cpuTicks=" + gameboy.getCpu().getPerformanceEpochTicks()
                            + " accesses=" + gameboy.getCpu().getPerformanceEpochAccesses(),
                    gameboy.getPerformanceEpochTicks() > 0);
        }
    }

    @Test
    public void stopAwarePreconditioningNeverUsesEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.runTicks(160_000);
            assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
            gameboy.resetPerformanceBulkCounters();

            assertEquals(4_096, gameboy.runTicksUntilStop(4_096, () -> false));
            assertEquals(0L, gameboy.getPerformanceEpochCount());
            assertEquals(0L, gameboy.getPerformanceEpochTicks());
        }
    }

    @Test
    public void stopAwareMeasuredWindowRetainsPerformanceEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.runTicks(160_000);
            assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
            gameboy.resetPerformanceBulkCounters();

            assertEquals(4_096, gameboy.runMeasuredTicksUntilStop(4_096, () -> false));
            assertTrue("measured stop-aware PERFORMANCE path lost native epoch coverage",
                    gameboy.getPerformanceEpochTicks() > 0L);
        }
    }

    @Test
    public void measuredTelemetryJoinsAndFreezesAfterCapture() throws Exception {
        try (Gameboy gameboy = nativeDoubleSpeedSession()) {
            // The first call starts on the legacy normal-speed scheduler; establish native
            // double speed before arming telemetry so the measured window is guaranteed to
            // exercise the epoch lane.
            gameboy.runTicks(160_000);
            gameboy.runTicks(100_000);
            assertEquals("test ROM did not enter CGB double speed", 2,
                    gameboy.getSpeedMode().getSpeedMode());
            gameboy.resetPerformanceBulkCounters();
            gameboy.runTicks(260_000);

            Gameboy.PerformanceTelemetrySnapshot measured =
                    gameboy.getPerformanceTelemetrySnapshot();
            assertEquals("measured master tick window", 260_000L,
                    measured.getSchedulerMasterTicks());
            assertTrue("measured window must contain a native epoch",
                    measured.getSchedulerEpochCount() > 0L);
            assertTrue("measured native epoch ticks must be nonzero",
                    measured.getSchedulerEpochTicks() > 0L);
            assertEquals("master tick ownership", measured.getSchedulerMasterTicks(),
                    measured.getSchedulerScalarTicks()
                            + measured.getSchedulerPhaseTicks()
                            + measured.getSchedulerHaltTicks()
                            + measured.getSchedulerEpochTicks());
            assertEquals("speed ownership", measured.getSchedulerMasterTicks(),
                    measured.getSchedulerSpeed1Ticks()
                            + measured.getSchedulerSpeed2Ticks()
                            + measured.getSchedulerSpeedSwitchTicks());
            assertEquals("packet length ownership", measured.getSchedulerPhaseCount()
                            + measured.getSchedulerHaltCount()
                            + measured.getSchedulerEpochCount(),
                    measured.getSchedulerLengthBucket0()
                            + measured.getSchedulerLengthBucket1()
                            + measured.getSchedulerLengthBucket2()
                            + measured.getSchedulerLengthBucket3()
                            + measured.getSchedulerLengthBucket4());
            assertEquals("PPU epoch ownership", measured.getSchedulerEpochTicks(),
                    measured.getSchedulerPpuDirectTicks()
                            + measured.getSchedulerPpuFallbackTicks()
                            + measured.getSchedulerPpuFastTicks());

            Gameboy.PerformanceTelemetrySnapshot frozen =
                    gameboy.capturePerformanceTelemetrySnapshotAndDisable();
            assertTelemetryEquals(measured, frozen);
            long legacyEpochCount = gameboy.getPerformanceEpochCount();
            long legacyEpochTicks = gameboy.getPerformanceEpochTicks();
            int postCaptureRuns = 0;
            while (gameboy.getPerformanceEpochCount() == legacyEpochCount
                    && postCaptureRuns++ < 64) {
                gameboy.runTicks(4_096);
            }
            assertTrue("post-capture interval must exercise the legacy epoch counters",
                    gameboy.getPerformanceEpochCount() > legacyEpochCount);
            assertTrue("post-capture legacy epoch ticks must advance",
                    gameboy.getPerformanceEpochTicks() > legacyEpochTicks);
            assertTelemetryEquals(frozen, gameboy.getPerformanceTelemetrySnapshot());
        }
    }

    private static void assertTelemetryEquals(
            Gameboy.PerformanceTelemetrySnapshot expected,
            Gameboy.PerformanceTelemetrySnapshot actual) {
        assertEquals(expected.getSchedulerMasterTicks(), actual.getSchedulerMasterTicks());
        assertEquals(expected.getSchedulerScalarTicks(), actual.getSchedulerScalarTicks());
        assertEquals(expected.getSchedulerPhaseCount(), actual.getSchedulerPhaseCount());
        assertEquals(expected.getSchedulerPhaseTicks(), actual.getSchedulerPhaseTicks());
        assertEquals(expected.getSchedulerPhaseMaxTicks(), actual.getSchedulerPhaseMaxTicks());
        assertEquals(expected.getSchedulerHaltCount(), actual.getSchedulerHaltCount());
        assertEquals(expected.getSchedulerHaltTicks(), actual.getSchedulerHaltTicks());
        assertEquals(expected.getSchedulerHaltMaxTicks(), actual.getSchedulerHaltMaxTicks());
        assertEquals(expected.getSchedulerEpochCount(), actual.getSchedulerEpochCount());
        assertEquals(expected.getSchedulerEpochTicks(), actual.getSchedulerEpochTicks());
        assertEquals(expected.getSchedulerEpochMaxTicks(), actual.getSchedulerEpochMaxTicks());
        assertEquals(expected.getSchedulerLengthBucket0(), actual.getSchedulerLengthBucket0());
        assertEquals(expected.getSchedulerLengthBucket1(), actual.getSchedulerLengthBucket1());
        assertEquals(expected.getSchedulerLengthBucket2(), actual.getSchedulerLengthBucket2());
        assertEquals(expected.getSchedulerLengthBucket3(), actual.getSchedulerLengthBucket3());
        assertEquals(expected.getSchedulerLengthBucket4(), actual.getSchedulerLengthBucket4());
        assertEquals(expected.getSchedulerSpeed1Ticks(), actual.getSchedulerSpeed1Ticks());
        assertEquals(expected.getSchedulerSpeed2Ticks(), actual.getSchedulerSpeed2Ticks());
        assertEquals(expected.getSchedulerSpeedSwitchTicks(), actual.getSchedulerSpeedSwitchTicks());
        assertEquals(expected.getSchedulerPpuDirectTicks(), actual.getSchedulerPpuDirectTicks());
        assertEquals(expected.getSchedulerPpuFallbackTicks(), actual.getSchedulerPpuFallbackTicks());
        assertEquals(expected.getSchedulerPpuFastTicks(), actual.getSchedulerPpuFastTicks());
    }

    @Test
    public void physicalDmgEntersEpochWhileCgbCompatibilityStaysLegacy() throws Exception {
        byte[] loop = new byte[0x8000];
        loop[0x100] = (byte) 0xc3;
        loop[0x101] = 0x00;
        loop[0x102] = 0x01;
        try (Gameboy dmg = new Gameboy.GameboyConfiguration(new Rom(loop))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.DMG)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
             Gameboy compat = new Gameboy.GameboyConfiguration(new Rom(loop))
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setGameboyType(GameboyType.CGB)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setSupportBatterySave(false)
                     .build()) {
            dmg.runTicks(100_000);
            compat.runTicks(100_000);
            assertTrue("physical DMG did not enter the coarse epoch lane",
                    dmg.getPerformanceEpochTicks() > 0);
            assertEquals(0L, compat.getPerformanceEpochTicks());
            assertTrue(compat.getSpeedMode().isDmgCompat());
        }
    }

    @Test
    public void physicalDmgRomWramLoopMatchesLegacySchedulerWithEpochCoverage()
            throws Exception {
        byte[] image = dmgRomWramLoop();
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("physical-DMG frame callbacks", scalarFrames, candidateFrames);
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("physical-DMG ROM/WRAM loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 10_000);
            assertEquals("physical DMG used a non-raster epoch plan",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks());
            assertEquals(0L, candidate.getPerformanceEpochMode2ReplayTicks());
            assertEquals(scalar.getAddressSpace().getByte(0xc000),
                    candidate.getAddressSpace().getByte(0xc000));
            assertDeepStateEquals("physical-DMG ROM/WRAM loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgPlainOamWritesDelegateImmediatelyAndMatchScalar() throws Exception {
        assertPhysicalDmgOamLoopMatchesScalar(dmgPlainOamWriteLoop(), 120_000,
                "plain LD-to-OAM");
    }

    @Test
    public void physicalDmgPushOamCorruptionAndSuppressionMatchScalar() throws Exception {
        assertPhysicalDmgOamLoopMatchesScalar(dmgPushOamLoop(), 120_000,
                "PUSH OAM-bug suppression");
    }

    @Test
    public void physicalDmgEpochRetiringHaltPublishesDmaPauseLatch() throws Exception {
        byte[] image = dmgRomWramLoop();
        image[0x200] = 0x76; // HALT, selected after reaching a trusted raster span
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0
                    && candidate.getCpu().performancePhysicalDmgEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("HALT setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted physical-DMG HALT entry", guard < 200_000);
            assertDeepStateEquals("before physical-DMG HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("HALT frame callback", scalar.runTicks(8), candidate.runTicks(8));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertTrue("HALT fetch did not retire inside a physical-DMG epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("after physical-DMG HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void accuracyAndSgbProfilesStayOutsidePhysicalDmgEpoch() throws Exception {
        byte[] image = dmgRomWramLoop();
        try (Gameboy accuracy = physicalDmgSession(
                image, PlayerInputSource.RELEASED, ExecutionMode.ACCURACY);
             Gameboy sgb = new Gameboy.GameboyConfiguration(new Rom(image))
                     .setHardwareProfile(HardwareProfileRegistry.SGB)
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setSupportBatterySave(false)
                     .build()) {
            accuracy.runTicks(100_000);
            sgb.runTicks(100_000);
            assertEquals(0L, accuracy.getPerformanceEpochTicks());
            assertEquals(0L, sgb.getPerformanceEpochTicks());
        }
    }

    @Test
    public void mode2ReplayMatchesScalarAndLeavesTheHandoffTickScalar() throws Exception {
        try (Gameboy scalar = nativeDoubleSpeedSession();
             Gameboy replay = nativeDoubleSpeedSession()) {
            advanceScalarToMode2ReplayStart(scalar);
            advanceScalarToMode2ReplayStart(replay);
            assertDeepStateEquals("mode-2 start", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());
            replay.getGpu().setPerformanceScanlineEnabled(true);
            int mode2Limit = replay.getGpu().performanceEpochMode2ReplaySpanLimit(54);
            replay.getGpu().setPerformanceScanlineEnabled(false);
            assertTrue("mode-2 replay preflight rejected start: limit="
                            + mode2Limit
                            + " cpu=" + replay.getCpu().performanceEpochEntryEligible()
                            + " mode=" + replay.getGpu().getMode()
                            + " dot=" + replay.getGpu().getTicksInLine()
                            + " cursor=" + replay.getGpu().isPerformanceScanlineCursorActive(),
                    mode2Limit > 0);
            assertTrue("CPU did not settle after the speed-switch countdown",
                    replay.getCpu().performanceEpochEntryEligible());

            scalar.resetPerformanceBulkCounters();
            replay.resetPerformanceBulkCounters();
            int replayDots = 79 - replay.getGpu().getTicksInLine();
            for (int i = 0; i < replayDots; i++) {
                scalar.tick();
            }
            assertEquals(0, replay.runTicks(replayDots));

            assertEquals(Mode.OamSearch, replay.getGpu().getMode());
            assertEquals(79, replay.getGpu().getTicksInLine());
            assertTrue("mode-2 epoch coverage was too small: "
                            + replay.getPerformanceEpochMode2ReplayTicks(),
                    replay.getPerformanceEpochMode2ReplayTicks() >= 40);
            assertEquals("mode-2 epochs used the arithmetic raster plan",
                    replay.getPerformanceEpochTicks(),
                    replay.getPerformanceEpochMode2ReplayTicks());
            assertEquals("mode-2 epoch fell back to the per-dot PPU replay",
                    replay.getPerformanceEpochMode2ReplayTicks(),
                    replay.getPerformanceEpochMode2BulkTicks());
            assertEquals(0L, replay.getPerformanceEpochRasterFastTicks());
            assertDeepStateEquals("mode-2 dot 79", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());

            long replayedBeforeHandoff = replay.getPerformanceEpochMode2ReplayTicks();
            assertEquals(scalar.runTicks(1), replay.runTicks(1));
            assertEquals("mode-3 handoff was replayed inside the mode-2 transaction",
                    replayedBeforeHandoff, replay.getPerformanceEpochMode2ReplayTicks());
            assertEquals("mode-3 handoff was included in the mode-2 bulk counter",
                    replayedBeforeHandoff, replay.getPerformanceEpochMode2BulkTicks());
            assertEquals(Mode.PixelTransfer, replay.getGpu().getMode());
            assertTrue("scalar handoff did not arm the direct renderer",
                    replay.getGpu().isPerformanceScanlineCursorActive());
            assertDeepStateEquals("mode-3 handoff", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());
        }
    }

    private static Gameboy nativeDoubleSpeedSession() throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy physicalDmgSession(
            byte[] image, PlayerInputSource inputSource, ExecutionMode executionMode)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(HardwareProfileRegistry.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(executionMode)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static void assertPhysicalDmgOamLoopMatchesScalar(
            byte[] image, int ticks, String label) throws Exception {
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            // SKIP boot can already be inside mode 2, where ordinary OAM writes are locked.
            // Expose OAM explicitly and prove the non-uniform seed took effect so PUSH-class
            // corruption cannot collapse into an all-zero fixed point.
            int scalarLcdc = scalar.getAddressSpace().getByte(0xff40);
            int candidateLcdc = candidate.getAddressSpace().getByte(0xff40);
            assertEquals(label + " initial LCDC", scalarLcdc, candidateLcdc);
            scalar.getAddressSpace().setByte(0xff40, 0x00);
            candidate.getAddressSpace().setByte(0xff40, 0x00);
            for (int offset = 0; offset < 0xa0; offset++) {
                int value = (offset * 37 + 11) & 0xff;
                scalar.getAddressSpace().setByte(0xfe00 + offset, value);
                candidate.getAddressSpace().setByte(0xfe00 + offset, value);
                assertEquals(label + " scalar OAM seed " + offset,
                        value, scalar.getAddressSpace().getByte(0xfe00 + offset));
                assertEquals(label + " candidate OAM seed " + offset,
                        value, candidate.getAddressSpace().getByte(0xfe00 + offset));
            }
            scalar.getAddressSpace().setByte(0xff40, scalarLcdc);
            candidate.getAddressSpace().setByte(0xff40, candidateLcdc);

            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < ticks / 4_000; chunk++) {
                scalarFrames += scalar.runTicks(4_000);
                candidateFrames += candidate.runTicks(4_000);
            }

            assertEquals(label + " frame callbacks", scalarFrames, candidateFrames);
            assertTrue(label + " did not exercise a coarse epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertTrue(label + " did not terminate an epoch on an OAM access",
                    candidate.getCpu().getPerformanceEpochTerminalAccesses() > 0);
            assertDeepStateEquals(label,
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    private static void advanceScalarToMode2ReplayStart(Gameboy gameboy) {
        int guard = 0;
        int stableDoubleSpeedTicks = 0;
        while (!(stableDoubleSpeedTicks >= 1_000
                && !gameboy.getGpu().isFirstLine()
                && gameboy.getGpu().getLine() < 144
                && gameboy.getGpu().getMode() == Mode.OamSearch
                && gameboy.getGpu().getTicksInLine() == 13)
                && guard++ < 400_000) {
            gameboy.tick();
            if (gameboy.getSpeedMode().getSpeedMode() == 2
                    && gameboy.getCpu().getState() != Cpu.State.SPEED_SWITCH) {
                stableDoubleSpeedTicks++;
            } else {
                stableDoubleSpeedTicks = 0;
            }
        }
        assertTrue("test did not reach a settled native-CGB mode-2 dot", guard < 400_000);
    }

    /** Record/array-aware equality for the private immutable component-state graph. */
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

    private static byte[] doubleSpeedLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF4D),A
        image[0x103] = 0x4d;
        image[0x104] = 0x10; // STOP + padding
        image[0x105] = 0x00;
        image[0x106] = (byte) 0xc3; // JP 0106
        image[0x107] = 0x06;
        image[0x108] = 0x01;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] dmgRomWramLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x21; // LD HL,C000
        image[0x101] = 0x00;
        image[0x102] = (byte) 0xc0;
        image[0x103] = 0x7e; // LD A,(HL)
        image[0x104] = 0x3c; // INC A
        image[0x105] = 0x77; // LD (HL),A
        image[0x106] = 0x18; // JR 0103
        image[0x107] = (byte) 0xfb;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] dmgPlainOamWriteLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x21; // LD HL,FE00
        image[0x101] = 0x00;
        image[0x102] = (byte) 0xfe;
        image[0x103] = 0x3e; // LD A,55
        image[0x104] = 0x55;
        image[0x105] = 0x77; // LD (HL),A
        image[0x106] = 0x3c; // INC A
        image[0x107] = 0x18; // JR 0105
        image[0x108] = (byte) 0xfc;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] dmgPushOamLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x31; // LD SP,FE08
        image[0x101] = 0x08;
        image[0x102] = (byte) 0xfe;
        image[0x103] = 0x01; // LD BC,1234
        image[0x104] = 0x34;
        image[0x105] = 0x12;
        image[0x106] = (byte) 0xc5; // PUSH BC (three DMG write corruptions)
        image[0x107] = (byte) 0xc3; // JP 0100
        image[0x108] = 0x00;
        image[0x109] = 0x01;
        image[0x143] = 0x00;
        return image;
    }

}
