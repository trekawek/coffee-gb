package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.VBlank;
import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StatRegisterTest {

    @Test
    public void quietTicksLeaveTimingStaleUntilAStatConsumerNeedsIt() throws Exception {
        Fixture quiet = quietFixture();
        int staleTicksInLine = statTiming(quiet.stat).ticksInLine;
        long staleGeneration = statTimingGeneration(quiet.stat);

        quiet.tick();

        assertEquals(staleGeneration, statTimingGeneration(quiet.stat));
        assertEquals(staleTicksInLine, statTiming(quiet.stat).ticksInLine);
        assertNotEquals(quiet.gpu.getTicksInLine(), statTiming(quiet.stat).ticksInLine);

        quiet.stat.getByte(StatRegister.ADDRESS);
        assertStatTimingMatchesGpu(quiet);

        Fixture checkpoint = quietFixture();
        checkpoint.advanceTo(1, 447);
        assertNotEquals(checkpoint.gpu.getTicksInLine(), statTiming(checkpoint.stat).ticksInLine);
        checkpoint.tick();
        assertStatTimingMatchesGpu(checkpoint);

        Fixture dirty = quietFixture();
        dirty.gpu.setByteFromCpu(GpuRegister.SCX.getAddress(), 1);
        dirty.tick();
        assertStatTimingMatchesGpu(dirty);

        Fixture signalled = quietFixture();
        signalled.interrupts.requestInterrupt(LCDC);
        signalled.interrupts.clearInterrupt(LCDC);
        signalled.tick();
        assertStatTimingMatchesGpu(signalled);

        Fixture scheduled = new Fixture(true);
        scheduled.advanceTo(1, 100);
        scheduled.stat.setByte(StatRegister.ADDRESS, 0x40);
        scheduled.gpu.setByte(GpuRegister.LYC.getAddress(), 3);
        long deadline = longField(scheduled.stat, "nextLycIrqEvent");
        assertTrue(deadline > longField(scheduled.stat, "lycIrqClock"));
        while (longField(scheduled.stat, "lycIrqClock") < deadline) {
            scheduled.tick();
        }
        assertStatTimingMatchesGpu(scheduled);
    }

    @Test(timeout = 10_000)
    public void nativePostGpuFactsAndTimingMatchGenericAtEveryNativeFrameDot() throws Exception {
        Fixture generic = nativeStressFixture();
        Fixture specialized = nativeStressFixture();
        Set<Integer> mode0Endpoints = new HashSet<>();
        int frameTicks = 154 * 456;

        for (int dot = 0; dot < frameTicks; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            assertNativeGpuDot(generic, specialized, dot);
            if (generic.gpu.getMode0InterruptTick() != Integer.MAX_VALUE) {
                mode0Endpoints.add(generic.gpu.getMode0InterruptTick());
            }
        }

        assertEquals("one complete native frame", 0, generic.gpu.getLine());
        assertEquals("one complete native frame dot", 1, generic.gpu.getTicksInLine());
        assertTrue("stress OAM must exercise variable mode-0 endpoints",
                mode0Endpoints.size() > 1);
    }

    @Test(timeout = 10_000)
    public void nativePostGpuStatTickMatchesGenericAtEveryDotForNativeFrame() {
        Fixture generic = nativeStressFixture();
        Fixture specialized = nativeStressFixture();
        int frameTicks = 154 * 456;

        for (int dot = 0; dot < frameTicks; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals("STAT memento at dot " + dot,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals("interrupt memento at dot " + dot,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
    }

    @Test(timeout = 5_000)
    public void nativePostGpuFirstLineAfterLcdEnableMatchesGeneric() throws Exception {
        Fixture generic = nativeStressFixture();
        Fixture specialized = nativeStressFixture();
        generic.gpu.setByte(0xff40, 0x00);
        specialized.gpu.setByte(0xff40, 0x00);
        generic.gpu.setByte(0xff40, 0x91);
        specialized.gpu.setByte(0xff40, 0x91);

        for (int dot = 0; dot < 455 + 8; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            assertNativeGpuDot(generic, specialized, dot);
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals("first-line STAT at dot " + dot,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals("first-line IF at dot " + dot,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
    }

    @Test(timeout = 5_000)
    public void nativePostGpuLcdOffOnRestartMatchesGeneric() throws Exception {
        Fixture generic = nativeStressFixture();
        Fixture specialized = nativeStressFixture();
        advanceGpuPair(generic, specialized, 3 * 456 + 200);
        generic.gpu.setByte(0xff40, 0x00);
        specialized.gpu.setByte(0xff40, 0x00);
        for (int dot = 0; dot < 12; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            assertNativeGpuDot(generic, specialized, dot);
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals("LCD-off STAT at dot " + dot,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals("LCD-off IF at dot " + dot,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
        generic.gpu.setByte(0xff40, 0x91);
        specialized.gpu.setByte(0xff40, 0x91);

        for (int dot = 0; dot < 455 + 16; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            assertNativeGpuDot(generic, specialized, dot);
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals("LCD restart STAT at dot " + dot,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals("LCD restart IF at dot " + dot,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
    }

    @Test(timeout = 5_000)
    public void nativePostGpuFallbackIsGenericAfterNormalSpeedOrCompatibility() {
        Fixture normalGeneric = new Fixture(true, false);
        Fixture normalSpecialized = new Fixture(true, false);
        assertNativePostGpuFallback(normalGeneric, normalSpecialized, "normal speed");

        Fixture compatGeneric = new Fixture(true, true);
        Fixture compatSpecialized = new Fixture(true, true);
        compatGeneric.speedMode.setDmgCompat(true);
        compatSpecialized.speedMode.setDmgCompat(true);
        assertNativePostGpuFallback(compatGeneric, compatSpecialized, "compatibility");
    }

    @Test(timeout = 5_000)
    public void nativePostGpuContinuationMatchesAfterCaptureRestore() {
        Fixture generic = nativeStressFixture();
        Fixture specialized = nativeStressFixture();
        advanceNativePair(generic, specialized, 8_192, "warmup");

        var genericGpuState = generic.gpu.captureState();
        var genericStatState = generic.stat.captureState();
        var genericInterruptState = generic.interrupts.captureState();
        var specializedGpuState = specialized.gpu.captureState();
        var specializedStatState = specialized.stat.captureState();
        var specializedInterruptState = specialized.interrupts.captureState();

        advanceNativePair(generic, specialized, 2_048, "uninterrupted continuation");

        generic.gpu.restoreState(genericGpuState);
        generic.stat.restoreState(genericStatState);
        generic.interrupts.restoreState(genericInterruptState);
        specialized.gpu.restoreState(specializedGpuState);
        specialized.stat.restoreState(specializedStatState);
        specialized.interrupts.restoreState(specializedInterruptState);
        advanceNativePair(generic, specialized, 2_048, "restored continuation");
    }

    @Test
    public void performanceQuietSpanLooksAheadAcrossLineStatAndHaltCheckpoints() {
        Fixture lineTail = quietFixture();
        lineTail.advanceTo(1, 445);
        assertTrue(lineTail.stat.canTickPerformanceQuietSpan(1));
        assertTrue(lineTail.stat.canTickPerformanceQuietSpan(2));
        assertFalse("a three-dot span must not jump over dot 448",
                lineTail.stat.canTickPerformanceQuietSpan(3));

        Fixture mode0Probe = quietFixture();
        int probeTicks = 0;
        while (mode0Probe.gpu.getMode0InterruptTick() == Integer.MAX_VALUE
                && probeTicks++ < 1_000) {
            mode0Probe.tick();
        }
        assertTrue("mode-0 prediction was not published", probeTicks < 1_000);
        int mode0Tick = mode0Probe.gpu.getMode0InterruptTick();
        Fixture mode0 = quietFixture();
        mode0.advanceTo(1, mode0Tick - 2);
        assertTrue(mode0.stat.canTickPerformanceQuietSpan(1));
        assertFalse("span must stop at the mode-0 edge",
                mode0.stat.canTickPerformanceQuietSpan(2));

        Fixture haltWake = quietFixture();
        haltWake.advanceTo(1, mode0Tick);
        assertFalse("span must stop at the mode-0 +2 HALT synchronizer",
                haltWake.stat.canTickPerformanceQuietSpan(2));
    }

    @Test
    public void nativeCgbCheckpointReplayAdmitsOnlyNonTargetVisibleLineWindows() {
        Fixture lineStart = settledNativeLycOnlyFixture(10, 0, 72);
        assertEquals(54,
                lineStart.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
        assertEquals(54,
                lineStart.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));

        Fixture lineTail = settledNativeLycOnlyFixture(10, 447, 72);
        assertEquals("tail must stop before the line rollover", 8,
                lineTail.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
        assertEquals("canonical tail has a closed-form endpoint", 8,
                lineTail.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));
        assertEquals(0, lineTail.stat.performanceNativeCgbCheckpointReplaySpanLimit(0));

        Fixture midLine = settledNativeLycOnlyFixture(10, 100, 72);
        assertEquals("mid-line checkpoints retain exact replay", 0,
                midLine.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));

        Fixture normalSpeed = new Fixture(true, false);
        normalSpeed.gpu.setByte(GpuRegister.LYC.getAddress(), 72);
        normalSpeed.stat.setByte(StatRegister.ADDRESS, 0x40);
        normalSpeed.advanceTo(10, 0);
        assertEquals("normal-speed CGB", 0,
                normalSpeed.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));

        assertEquals("LYC equality line", 0,
                settledNativeLycOnlyFixture(72, 0, 72).stat
                        .performanceNativeCgbCheckpointReplaySpanLimit(54));
        assertEquals("preceding comparator line", 0,
                settledNativeLycOnlyFixture(71, 447, 72).stat
                        .performanceNativeCgbCheckpointReplaySpanLimit(54));
        assertEquals("VBlank handoff line", 0,
                settledNativeLycOnlyFixture(143, 0, 72).stat
                        .performanceNativeCgbCheckpointReplaySpanLimit(54));
    }

    @Test
    public void nativeCgbCheckpointReplayRejectsPendingAndMismatchedStatPlanes()
            throws Exception {
        for (String field : new String[]{
                "lycIrqStatSource", "lycIrqStatLatch", "modeIrqStatLatch",
                "mode0IrqStatLatch", "lycIrqValueLatch", "modeIrqLycLatch",
                "mode0IrqLycLatch"}) {
            Fixture mismatch = settledNativeLycOnlyFixture(10, 0, 72);
            setIntField(mismatch.stat, field, 0);
            assertEquals(field, 0,
                    mismatch.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
        }

        for (String field : new String[]{
                "pendingModeIrqStatClock", "pendingModeIrqLycClock",
                "pendingMode0IrqStatClock", "pendingMode0IrqLycClock",
                "pendingCgbMode2PublicationClock", "pendingLycWriteIrq",
                "pendingLycComparatorIrq"}) {
            Fixture pending = settledNativeLycOnlyFixture(10, 0, 72);
            setLongField(pending.stat, field, longField(pending.stat, "lycIrqClock") + 1);
            assertEquals(field, 0,
                    pending.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
        }

        for (String field : new String[]{
                "pendingCgbMode0Interrupt", "pendingCgbMode1Interrupt",
                "pendingCgbMode2Interrupt", "pendingCgbMode2LateReplay",
                "pendingCgbFrameMode2Interrupt", "retractableCgbMode2Interrupt",
                "releaseTailLycCpuAcceptance"}) {
            Fixture pending = settledNativeLycOnlyFixture(10, 0, 72);
            setBooleanField(pending.stat, field, true);
            assertEquals(field, 0,
                    pending.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
        }

        Fixture dirty = settledNativeLycOnlyFixture(10, 0, 72);
        dirty.gpu.setByteFromCpu(GpuRegister.SCX.getAddress(), 1);
        assertEquals("dirty evaluator", 0,
                dirty.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));

        Fixture signalled = settledNativeLycOnlyFixture(10, 0, 72);
        signalled.interrupts.requestInterrupt(LCDC);
        signalled.interrupts.clearInterrupt(LCDC);
        assertTrue(signalled.interrupts.hasPpuTickSignals());
        assertEquals("PPU signal", 0,
                signalled.stat.performanceNativeCgbCheckpointReplaySpanLimit(54));
    }

    @Test
    public void nativeCgbCheckpointReplayMatchesScalarAtLineStartAndTail() {
        int[][] windows = {{10, 0, 13}, {10, 447, 8}};
        for (int[] window : windows) {
            Fixture scalar = settledNativeLycOnlyFixture(window[0], window[1], 72);
            Fixture replay = settledNativeLycOnlyFixture(window[0], window[1], 72);
            int span = replay.stat.performanceNativeCgbCheckpointReplaySpanLimit(window[2]);
            assertEquals(window[0] + ":" + window[1], window[2], span);

            for (int dot = 0; dot < span; dot++) {
                scalar.gpu.tick();
                scalar.stat.tick();
                replay.gpu.tick();
                replay.stat.tickNativeCgbPerformancePostGpu();
            }

            assertEquals("GPU line", scalar.gpu.getLine(), replay.gpu.getLine());
            assertEquals("GPU dot", scalar.gpu.getTicksInLine(), replay.gpu.getTicksInLine());
            assertEquals("STAT state", scalar.stat.captureState(), replay.stat.captureState());
            assertEquals("interrupt state", scalar.interrupts.captureState(),
                    replay.interrupts.captureState());
        }
    }

    @Test
    public void nativeCgbCheckpointAggregateRejectsNonCanonicalEndpointPlanes()
            throws Exception {
        for (String field : new String[]{
                "coincidence", "intCoincidence", "intLine", "lycWriteSuppressed",
                "lycComparatorSignal", "pendingCgbMode2IfHighAtCapture",
                "cgbMode2CapturedAtLineEdge", "previousMode0Window",
                "previousMode1Window", "previousMode2Window"}) {
            Fixture nonCanonical = settledNativeLycOnlyFixture(10, 0, 72);
            setBooleanField(nonCanonical.stat, field, true);
            assertEquals(field, 0,
                    nonCanonical.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));
        }

        for (String field : new String[]{"suppressedLycIrqLine", "modeBlockedLycIrqLine"}) {
            Fixture nonCanonical = settledNativeLycOnlyFixture(10, 0, 72);
            setIntField(nonCanonical.stat, field, 7);
            assertEquals(field, 0,
                    nonCanonical.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));
        }

        Fixture wrongRegisteredLy = settledNativeLycOnlyFixture(10, 0, 72);
        setIntField(wrongRegisteredLy.stat, "registeredLy", 9);
        assertEquals("registered LY", 0,
                wrongRegisteredLy.stat.performanceNativeCgbCheckpointAggregateSpanLimit(54));

        Fixture tail = settledNativeLycOnlyFixture(10, 447, 72);
        setBooleanField(tail.stat, "previousMode0Window", false);
        assertEquals("tail mode-0 history", 0,
                tail.stat.performanceNativeCgbCheckpointAggregateSpanLimit(8));
    }

    @Test
    public void nativeCgbCheckpointAggregateMatchesScalarAcrossPartialPrefixes() {
        int[][] windows = {
                {1, 0, 54}, {10, 6, 54}, {142, 12, 54},
                {10, 447, 8}, {10, 451, 4}
        };
        for (int[] window : windows) {
            Fixture scalar = settledNativeLycOnlyFixture(window[0], window[1], 72);
            Fixture aggregate = settledNativeLycOnlyFixture(window[0], window[1], 72);
            int span = aggregate.stat
                    .performanceNativeCgbCheckpointAggregateSpanLimit(window[2]);
            assertEquals(window[0] + ":" + window[1], window[2], span);

            for (int dot = 0; dot < span; dot++) {
                scalar.gpu.tick();
                scalar.stat.tick();
            }
            int remaining = span;
            int prefix = 1;
            while (remaining > 0) {
                int chunk = Math.min(remaining, prefix);
                for (int dot = 0; dot < chunk; dot++) {
                    aggregate.gpu.tick();
                }
                aggregate.stat
                        .advancePerformanceNativeCgbCheckpointAggregateSpanTrusted(chunk);
                remaining -= chunk;
                prefix = prefix == 5 ? 1 : prefix + 1;
            }

            assertEquals("GPU line", scalar.gpu.getLine(), aggregate.gpu.getLine());
            assertEquals("GPU dot", scalar.gpu.getTicksInLine(),
                    aggregate.gpu.getTicksInLine());
            assertEquals("STAT state", scalar.stat.captureState(),
                    aggregate.stat.captureState());
            assertEquals("interrupt state", scalar.interrupts.captureState(),
                    aggregate.interrupts.captureState());
            assertEquals("STAT readback", scalar.stat.getByte(StatRegister.ADDRESS),
                    aggregate.stat.getByte(StatRegister.ADDRESS));
            assertEquals("LY readback", scalar.gpu.getByte(GpuRegister.LY.getAddress()),
                    aggregate.gpu.getByte(GpuRegister.LY.getAddress()));
        }
    }

    @Test
    public void performanceSgbLcdOffSpanRejectsEveryLiveStatPublicationPlane()
            throws Exception {
        Fixture settled = settledDmgLcdOffFixture();
        assertEquals(0, settled.stat.performanceSgbLcdOffSpanLimit(0));

        Fixture lcdOn = new Fixture(false);
        lcdOn.tick();
        assertTrue(lcdOn.gpu.isLcdEnabled());
        assertEquals(0, lcdOn.stat.performanceSgbLcdOffSpanLimit(54));

        Fixture cgb = new Fixture(true);
        cgb.gpu.setByte(0xff40, 0);
        cgb.tick();
        assertFalse(cgb.gpu.isLcdEnabled());
        assertEquals(0, cgb.stat.performanceSgbLcdOffSpanLimit(54));

        Fixture dirty = new Fixture(false);
        dirty.gpu.setByte(0xff40, 0);
        assertFalse(dirty.gpu.isLcdEnabled());
        assertEquals(0, dirty.stat.performanceSgbLcdOffSpanLimit(54));

        for (String field : new String[]{
                "pendingModeIrqStatClock",
                "pendingModeIrqLycClock",
                "pendingMode0IrqStatClock",
                "pendingMode0IrqLycClock",
                "pendingCgbMode2PublicationClock",
                "pendingLycWriteIrq",
                "pendingLycComparatorIrq"}) {
            Fixture pending = settledDmgLcdOffFixture();
            setLongField(pending.stat, field, longField(pending.stat, "lycIrqClock"));
            assertEquals(field, 0, pending.stat.performanceSgbLcdOffSpanLimit(54));
        }

        Fixture pendingMode0 = settledDmgLcdOffFixture();
        setBooleanField(pendingMode0.stat, "pendingCgbMode0Interrupt", true);
        assertEquals(0, pendingMode0.stat.performanceSgbLcdOffSpanLimit(54));

        Fixture signalled = settledDmgLcdOffFixture();
        signalled.interrupts.requestInterrupt(LCDC);
        signalled.interrupts.clearInterrupt(LCDC);
        assertTrue(signalled.interrupts.hasPpuTickSignals());
        assertEquals(0, signalled.stat.performanceSgbLcdOffSpanLimit(54));
    }

    @Test
    public void performanceSgbLcdOffSpanIgnoresEveryNextLycDeadlinePosition()
            throws Exception {
        String[] labels = {"ahead", "next", "equal", "expired one", "expired many"};
        long[] offsets = {100, 1, 0, -1, -1_000};
        for (int i = 0; i < offsets.length; i++) {
            Fixture fixture = settledDmgLcdOffFixture();
            long clock = longField(fixture.stat, "lycIrqClock");
            setLongField(fixture.stat, "nextLycIrqEvent", clock + offsets[i]);

            assertEquals(labels[i], 54, fixture.stat.performanceSgbLcdOffSpanLimit(54));
            if (offsets[i] <= 1) {
                assertEquals(labels[i] + " remains fenced by the generic path", 0,
                        fixture.stat.performanceSettledHaltSpanLimit(54));
            }
        }
    }

    @Test(timeout = 5_000)
    public void performanceSgbLcdOffSpanRejectsAllLcdOnModeAndLineBoundaries() {
        Fixture fixture = new Fixture(false);
        fixture.advanceToHBlank();
        assertEquals("mode 0", 0, fixture.stat.performanceSgbLcdOffSpanLimit(54));

        int[][] boundaries = {
                {0, 447}, {0, 448}, {0, 451}, {0, 452}, {0, 454}, {0, 455},
                {1, 447}, {1, 448}, {1, 451}, {1, 452}, {1, 454}, {1, 455},
                {143, 447}, {143, 448}, {143, 451}, {143, 452}, {143, 454}, {143, 455},
                {144, 0}, {144, 4}, {144, 8}, {144, 447}, {144, 448}, {144, 451},
                {144, 452}, {144, 454}, {144, 455},
                {152, 451}, {152, 452}, {152, 454}, {152, 455},
                {153, 0}, {153, 3}, {153, 4}, {153, 6}, {153, 8}, {153, 447},
                {153, 448}, {153, 451}, {153, 452}, {153, 454}, {153, 455}
        };
        for (int[] boundary : boundaries) {
            fixture.advanceTo(boundary[0], boundary[1]);
            assertTrue("LCD unexpectedly off at " + boundary[0] + ":" + boundary[1],
                    fixture.gpu.isLcdEnabled());
            assertEquals(boundary[0] + ":" + boundary[1], 0,
                    fixture.stat.performanceSgbLcdOffSpanLimit(54));
        }
    }

    @Test
    public void hblankEnableMasksStatWriteGlitchAtOamBoundary() {
        Fixture fixture = new Fixture();
        fixture.advanceToHBlank();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();

        fixture.advanceToNextLineStart();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void oamPulseHasEndedAtReadableLineStart() {
        Fixture fixture = new Fixture();
        fixture.advanceToNextLineStart();
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void lycEdgeIsNotRepeatedWhenIfIsClearedWhileComparatorSettles() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);

        fixture.advanceToNextLineStart();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        fixture.clearInterrupts();
        for (int i = 0; i < 4; i++) {
            fixture.tick();
        }
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbLycEdgeTakesPrecedenceOverRetiringMode0Source() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceToHBlank();
        fixture.clearInterrupts();

        fixture.advanceTo(1, 0);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void vblankSourceMasksLineZeroOamSource() {
        Fixture fixture = new Fixture();
        fixture.advanceTo(144, 8);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x30);
        fixture.clearInterrupts();

        fixture.advanceTo(0, 4);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbLyAdvancesAtDot452AndRetains153ForFourDots() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(152, 451);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());
        fixture.advanceTo(153, 3);
        assertEquals(153, fixture.readLy());
        fixture.tick();
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void earlyCgbLyReadCompatibilityOnlyAdvancesTheCpuBusLatch() {
        Fixture fixture = new Fixture(true, false, true);
        fixture.advanceTo(143, 447);

        assertEquals(143, fixture.readLy());
        fixture.tick();

        assertEquals(143, fixture.gpu.getVisibleLy());
        assertEquals(144, fixture.readLy());
    }

    @Test
    public void ordinaryHaltWakeSamplesNextCgbVblankLyAcrossReadCycle() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(144, 450);

        assertEquals(144, fixture.readLy());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(144, fixture.readLy());
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(145, fixture.readLy());

        fixture.tick();
        assertEquals(144, fixture.readLy());
        fixture.tick();
        assertEquals(145, fixture.readLy());
    }

    @Test
    public void lycHaltWakeKeepsTheCurrentCgbVblankLyAcrossReadCycle() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(144, 450);

        fixture.stat.captureCpuStatReadPhase(false, false, true);

        assertEquals(144, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartCpuPhaseSkipsTheTransientLine153Latch() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(153, 0);

        assertEquals(153, fixture.gpu.getVisibleLy());
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartLyDotZeroExceptionExpiresAfterTheFirstFrame() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(153, 0);
        assertEquals(0, fixture.readLy());

        fixture.advanceTo(0, 0);
        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 2);
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void rephasedNormalSpeedCgbCpuReadSeesLyRippleAtLineTail() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(5, 455);

        assertEquals(6, fixture.gpu.getVisibleLy());
        assertEquals(4, fixture.readLy());
    }

    @Test
    public void rephasedDoubleSpeedCgbCpuReadSeesLyRippleBeforeLineTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(5, 451);

        assertEquals(5, fixture.gpu.getVisibleLy());
        assertEquals(4, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartRealignsCpuVisibleLyLatch() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(5, 451);

        assertEquals(5, fixture.gpu.getVisibleLy());
        assertEquals(5, fixture.readLy());
    }

    @Test
    public void rephasedCgbCpuReadSeesLyResetRippleWithoutMovingComparatorLatch() {
        Fixture normalSpeed = new Fixture(true);
        normalSpeed.gpu.onSpeedSwitch();
        normalSpeed.advanceTo(153, 2);

        assertEquals(153, normalSpeed.gpu.getVisibleLy());
        assertEquals(0, normalSpeed.readLy());
        normalSpeed.tick();
        assertEquals(153, normalSpeed.readLy());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.advanceTo(153, 1);

        assertEquals(153, doubleSpeed.gpu.getVisibleLy());
        assertEquals(0, doubleSpeed.readLy());
    }

    @Test
    public void cgbDoubleSpeedTailLycEdgeDuringVblankIsReleasedAtLineStart() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 152);
        fixture.clearInterrupts();
        fixture.advanceTo(151, 453);

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());

        fixture.advanceTo(152, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void cgbDoubleSpeedNewFrameLycEdgeUsesTwoDotCpuCycle() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(152, 400);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.clearInterrupts();
        fixture.advanceTo(153, 5);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());

        fixture.advanceTo(153, 8);
        assertTrue(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void cgbStatProjectsNextLineModeAtDot454() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedNormalSpeedCgbStatProjectsMode2InFinalCpuBusSlot() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 449);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedDoubleSpeedCgbStatProjectsMode2InFinalCpuBusSlot() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 452);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedDoubleSpeedCpuReadSamplesMode3AtEndOfBusCycle() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 76);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());

        fixture.stat.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void lcdRestartRealignsDoubleSpeedMode3CpuReadPhase() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 76);

        fixture.stat.captureCpuStatReadPhase(false, false, false);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedNormalSpeedCgbStatProjectsMode2AtFrameTail() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(153, 449);

        assertEquals(Mode.VBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedCgbDmgCompatibilityKeepsOrdinaryTailMux() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 450);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedCgbLycSourceKeepsCurrentModeInTailMux() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(1, 450);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedCgbLycTailReadsOutgoingStateAtVblankBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(143, 454);

        assertEquals(0xc0, fixture.stat.getByte(StatRegister.ADDRESS));
        fixture.tick();
        assertEquals(0xc4, fixture.stat.getByte(StatRegister.ADDRESS));
        fixture.tick();
        assertEquals(0xc1, fixture.stat.getByte(StatRegister.ADDRESS));
    }

    @Test
    public void rephasedCgbMode1RequestIsHiddenInFinalLine143IfBusSlot() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);

        fixture.tick();

        assertTrue(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbLycStatReadRetainsHblankThroughDot454OnObjectFreeLine() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(0, 454);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbSameLineLycWriteReleasesDot454ModeReadMux() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(1, 100);

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 454);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbStatExposesPixelTransferAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 77);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbCpuBusUsesSettledPixelTransferModeAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbMode2HandlerReadRetainsTheSourceLatchAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.beginCpuReadPhase(false, false, false, false);
        assertEquals(-2, cpuStatModeOverride(fixture.stat));
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        assertEquals(Mode.OamSearch.ordinal(), cpuStatModeOverride(fixture.stat));
    }

    @Test
    public void ordinaryHaltWakeRetainsCgbOamSearchAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void packedCpuStatReadPhaseRetainsEveryCpuInputFlag() throws Exception {
        for (int flags = 0; flags < 16; flags++) {
            Fixture fixture = new Fixture(true);
            fixture.advanceTo(1, 78);

            fixture.stat.beginCpuReadPhase(flags);

            int expected = flags;
            if ((flags & Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE) != 0) {
                expected |= 1 << 4;
            }
            assertEquals(expected, intField(fixture.stat, "cpuStatReadPhaseFlags"));
        }
    }

    @Test
    public void nativeCgbPackedReadPhaseMatchesGenericPhaseAcrossEdgesAndInputs()
            throws Exception {
        Fixture probe = new Fixture(true, true);
        probe.gpu.onSpeedSwitch();
        probe.advanceTo(1, 100);
        int mode0 = probe.gpu.getMode0InterruptTick();
        int mode0Target = mode0 == Integer.MAX_VALUE ? 250 : mode0;
        int[] targets = {100, Math.max(13, mode0Target - 2), Math.max(13, mode0Target - 1),
                mode0Target, Math.min(447, mode0Target + 2)};
        int[] enables = {0x00, 0x08, 0x20, 0x48};

        for (int target : targets) {
            for (int enable : enables) {
                for (int flags = 0; flags < 16; flags++) {
                    Fixture generic = new Fixture(true, true);
                    Fixture nativeCgb = new Fixture(true, true);
                    generic.gpu.onSpeedSwitch();
                    nativeCgb.gpu.onSpeedSwitch();
                    generic.stat.setByte(StatRegister.ADDRESS, enable);
                    nativeCgb.stat.setByte(StatRegister.ADDRESS, enable);
                    generic.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
                    nativeCgb.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
                    generic.advanceTo(1, target);
                    nativeCgb.advanceTo(1, target);

                    boolean genericEdge = generic.stat.beginCpuReadPhase(flags);
                    generic.stat.finishCpuReadPhase(0, false, false);
                    int phaseWord = nativeCgb.gpu.getNativeCgbPerformancePhaseWord();
                    long nativeTimingGeneration = statTimingGeneration(nativeCgb.stat);
                    boolean nativeEdge = nativeCgb.stat.beginNativeCgbPerformanceReadPhase(
                            flags, phaseWord);
                    nativeCgb.stat.finishNativeCgbPerformanceReadPhase(0, phaseWord);
                    assertEquals(nativeTimingGeneration, statTimingGeneration(nativeCgb.stat));

                    assertEquals("mode-0 edge target=" + target + " enable=" + enable
                                    + " flags=" + flags,
                            genericEdge, nativeEdge);
                    assertEquals("STAT state target=" + target + " enable=" + enable
                                    + " flags=" + flags,
                            generic.stat.captureState(), nativeCgb.stat.captureState());
                    assertEquals("IF state target=" + target + " enable=" + enable
                                    + " flags=" + flags,
                            generic.interrupts.captureState(), nativeCgb.interrupts.captureState());
                    assertEquals("STAT read target=" + target + " enable=" + enable
                                    + " flags=" + flags,
                            generic.stat.getByte(StatRegister.ADDRESS),
                            nativeCgb.stat.getByte(StatRegister.ADDRESS));
                }
            }
        }
    }

    @Test(timeout = 5_000)
    public void nativeCgbUnblockedMode0PreviewMatchesGenericAtMinusTwo() {
        Fixture generic = new Fixture(true, true);
        Fixture nativeCgb = new Fixture(true, true);
        generic.gpu.onSpeedSwitch();
        nativeCgb.gpu.onSpeedSwitch();
        generic.stat.setByte(StatRegister.ADDRESS, 0x48);
        nativeCgb.stat.setByte(StatRegister.ADDRESS, 0x48);
        // Keep the LYC source enabled but unblocked at the mode-0 preview dot.
        generic.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        nativeCgb.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);

        int phaseWord = nativeCgb.gpu.getNativeCgbPerformancePhaseWord();
        int remaining = 2 * 456;
        while ((phaseWord & Gpu.NATIVE_CGB_PHASE_MODE0_READ_PREVIEW) == 0
                && remaining-- > 0) {
            generic.tick();
            nativeCgb.tick();
            phaseWord = nativeCgb.gpu.getNativeCgbPerformancePhaseWord();
        }
        assertTrue("native mode-0 preview was not reached", remaining >= 0);

        boolean genericEdge = generic.stat.beginCpuReadPhase(0);
        generic.stat.finishCpuReadPhase(0, false, false);
        boolean nativeEdge = nativeCgb.stat.beginNativeCgbPerformanceReadPhase(0, phaseWord);
        nativeCgb.stat.finishNativeCgbPerformanceReadPhase(0, phaseWord);

        assertFalse(genericEdge);
        assertEquals(genericEdge, nativeEdge);
        assertEquals(generic.interrupts.captureState(), nativeCgb.interrupts.captureState());
        assertEquals(generic.interrupts.getByte(0xff0f), nativeCgb.interrupts.getByte(0xff0f));
    }

    @Test(timeout = 5_000)
    public void nativeCgbMode0IfReadMaskMatchesGenericBusOrdering() {
        Fixture generic = new Fixture(true, true);
        Fixture nativeCgb = new Fixture(true, true);
        generic.gpu.onSpeedSwitch();
        nativeCgb.gpu.onSpeedSwitch();
        generic.stat.setByte(StatRegister.ADDRESS, 0x48);
        nativeCgb.stat.setByte(StatRegister.ADDRESS, 0x48);
        generic.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        nativeCgb.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        generic.advanceTo(1, 100);
        nativeCgb.advanceTo(1, 100);
        int mode0Tick = nativeCgb.gpu.getMode0InterruptTick();
        int previewTick = (mode0Tick == Integer.MAX_VALUE ? 250 : mode0Tick) - 2;
        generic.advanceTo(1, previewTick - 4);
        nativeCgb.advanceTo(1, previewTick - 4);
        generic.interrupts.requestInterrupt(LCDC);
        nativeCgb.interrupts.requestInterrupt(LCDC);
        generic.interrupts.clearInterrupt(LCDC);
        nativeCgb.interrupts.clearInterrupt(LCDC);
        generic.tick();
        nativeCgb.tick();
        generic.advanceTo(1, previewTick);
        nativeCgb.advanceTo(1, previewTick);
        int phaseWord = nativeCgb.gpu.getNativeCgbPerformancePhaseWord();
        assertTrue((phaseWord & Gpu.NATIVE_CGB_PHASE_MODE0_READ_PREVIEW) != 0);

        generic.stat.beginCpuReadPhase(0);
        generic.stat.finishCpuReadPhase(2, false, false);
        nativeCgb.stat.beginNativeCgbPerformanceReadPhase(0, phaseWord);
        nativeCgb.stat.finishNativeCgbPerformanceReadPhase(2, phaseWord);
        generic.tick();
        nativeCgb.tick();
        generic.tick();
        nativeCgb.tick();

        assertEquals(generic.stat.captureState(), nativeCgb.stat.captureState());
        assertEquals(generic.interrupts.captureState(), nativeCgb.interrupts.captureState());
        assertTrue(generic.interrupts.isInterruptFlagSet(LCDC));
        assertTrue(nativeCgb.interrupts.isInterruptFlagSet(LCDC));
        assertEquals(0, generic.lcdInterruptFlag());
        assertEquals(0, nativeCgb.lcdInterruptFlag());
        assertEquals(1 << LCDC.ordinal(), generic.lcdInterruptFlag());
        assertEquals(1 << LCDC.ordinal(), nativeCgb.lcdInterruptFlag());
    }

    @Test(timeout = 5_000)
    public void nativePostGpuConsumesMode0ReadMaskAndPpuSignalLikeGeneric() {
        Fixture generic = new Fixture(true, true);
        Fixture specialized = new Fixture(true, true);
        generic.gpu.onSpeedSwitch();
        specialized.gpu.onSpeedSwitch();
        generic.stat.setByte(StatRegister.ADDRESS, 0x48);
        specialized.stat.setByte(StatRegister.ADDRESS, 0x48);
        generic.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        specialized.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        generic.advanceTo(1, 100);
        specialized.advanceTo(1, 100);

        int mode0Tick = specialized.gpu.getMode0InterruptTick();
        int target = mode0Tick == Integer.MAX_VALUE ? 250 : Math.max(100, mode0Tick - 1);
        generic.advanceTo(1, target);
        specialized.advanceTo(1, target);

        generic.interrupts.requestInterrupt(LCDC);
        specialized.interrupts.requestInterrupt(LCDC);
        generic.interrupts.clearInterrupt(LCDC);
        specialized.interrupts.clearInterrupt(LCDC);
        generic.interrupts.maskMode0LcdcReadForTicks(2);
        specialized.interrupts.maskMode0LcdcReadForTicks(2);
        assertTrue(generic.interrupts.hasPendingCpuReadPhase());
        assertTrue(specialized.interrupts.hasPendingCpuReadPhase());

        for (int dot = 0; dot < 5; dot++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals("PPU-signal STAT at dot " + dot,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals("PPU-signal IF at dot " + dot,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
            assertEquals("PPU-signal FF0F at dot " + dot,
                    generic.interrupts.getByte(0xff0f), specialized.interrupts.getByte(0xff0f));
        }
    }

    @Test
    public void nativeCgbSpecializedReadResolvesAfterRestoreLikeGeneric() throws Exception {
        Fixture generic = new Fixture(true, true);
        Fixture nativeCgb = new Fixture(true, true);
        generic.gpu.onSpeedSwitch();
        nativeCgb.gpu.onSpeedSwitch();
        generic.stat.setByte(StatRegister.ADDRESS, 0x48);
        nativeCgb.stat.setByte(StatRegister.ADDRESS, 0x48);
        generic.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        nativeCgb.gpu.setByte(GpuRegister.LYC.getAddress(), 0x22);
        generic.advanceTo(1, 100);
        nativeCgb.advanceTo(1, 100);

        var genericGpuState = generic.gpu.captureState();
        var genericStatState = generic.stat.captureState();
        var genericInterruptState = generic.interrupts.captureState();
        var nativeGpuState = nativeCgb.gpu.captureState();
        var nativeStatState = nativeCgb.stat.captureState();
        var nativeInterruptState = nativeCgb.interrupts.captureState();

        for (int flags = 0; flags < 16; flags++) {
            generic.gpu.restoreState(genericGpuState);
            generic.stat.restoreState(genericStatState);
            generic.interrupts.restoreState(genericInterruptState);
            nativeCgb.gpu.restoreState(nativeGpuState);
            nativeCgb.stat.restoreState(nativeStatState);
            nativeCgb.interrupts.restoreState(nativeInterruptState);

            boolean genericEdge = generic.stat.beginCpuReadPhase(flags);
            generic.stat.finishCpuReadPhase(0, false, false);
            int phaseWord = nativeCgb.gpu.getNativeCgbPerformancePhaseWord();
            boolean nativeEdge = nativeCgb.stat.beginNativeCgbPerformanceReadPhase(
                    flags, phaseWord);
            nativeCgb.stat.finishNativeCgbPerformanceReadPhase(0, phaseWord);

            assertEquals("edge flags=" + flags, genericEdge, nativeEdge);
            assertEquals("FF41 flags=" + flags,
                    generic.stat.getByte(StatRegister.ADDRESS),
                    nativeCgb.stat.getByte(StatRegister.ADDRESS));
            assertEquals("STAT flags=" + flags,
                    generic.stat.captureState(), nativeCgb.stat.captureState());
            assertEquals("IF flags=" + flags,
                    generic.interrupts.captureState(), nativeCgb.interrupts.captureState());
        }
    }

    @Test
    public void stalePackedCpuStatReadPhaseIsHiddenUntilTheNextCapture() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        fixture.stat.beginCpuReadPhase(Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        int capturedFlags = intField(fixture.stat, "cpuStatReadPhaseFlags");

        fixture.tick();

        assertEquals(capturedFlags, intField(fixture.stat, "cpuStatReadPhaseFlags"));
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.beginCpuReadPhase(0);
        assertEquals(0, intField(fixture.stat, "cpuStatReadPhaseFlags"));
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void doubleSpeedMode2HandlerReadRetainsTheSourceLatchAtDot80() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 0);
        fixture.gpu.onDoubleSpeedMode2Dispatch();
        fixture.advanceTo(1, 80);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void mode2HandlerReadRetainsModeZeroAtTheLineEdge() {
        Fixture normal = new Fixture(true);
        normal.stat.setByte(StatRegister.ADDRESS, 0x20);
        normal.advanceTo(1, 454);
        normal.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), normal.readStatMode());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.stat.setByte(StatRegister.ADDRESS, 0x20);
        doubleSpeed.advanceTo(1, 0);
        doubleSpeed.gpu.onDoubleSpeedMode2Dispatch();
        doubleSpeed.advanceTo(2, 0);
        doubleSpeed.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), doubleSpeed.readStatMode());
    }

    @Test
    public void ordinaryHaltWakeStatHoldExpiresAfterOneScanline() {
        Fixture recent = new Fixture(true);
        recent.stat.setByte(StatRegister.ADDRESS, 0x20);
        recent.advanceTo(1, 3);
        recent.stat.captureCpuStatReadPhase(false, false, true);
        recent.advanceTo(1, 454);
        recent.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), recent.readStatMode());

        Fixture stale = new Fixture(true);
        stale.stat.setByte(StatRegister.ADDRESS, 0x20);
        stale.advanceTo(1, 3);
        stale.stat.captureCpuStatReadPhase(false, false, true);
        stale.advanceTo(2, 454);
        stale.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.OamSearch.ordinal(), stale.readStatMode());
    }

    @Test
    public void trustedNoReadCaptureTracksOrdinaryWakeEdgeExpiryAndRearm()
            throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 3);
        int ordinary = Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE;

        long firstClock = longField(fixture.stat, "lycIrqClock");
        fixture.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals(firstClock, longField(fixture.stat, "ordinaryHaltWakeStatClock"));

        for (int tick = 0; tick < 456; tick++) {
            fixture.tick();
        }
        fixture.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("continuous ordinary wake must not rearm at the 456-dot limit",
                firstClock, longField(fixture.stat, "ordinaryHaltWakeStatClock"));
        fixture.tick();
        fixture.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("expired continuous wake must remain anchored to its first edge",
                firstClock, longField(fixture.stat, "ordinaryHaltWakeStatClock"));

        fixture.stat.capturePerformanceNoCpuReadPhaseTrusted(0);
        fixture.tick();
        long rearmClock = longField(fixture.stat, "lycIrqClock");
        fixture.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("false-to-true ordinary wake must rearm",
                rearmClock, longField(fixture.stat, "ordinaryHaltWakeStatClock"));

        Fixture restored = new Fixture(true);
        restored.stat.restoreState(fixture.stat.captureState());
        assertEquals(fixture.stat.captureState(), restored.stat.captureState());
    }

    @Test
    public void trustedNoReadCaptureDoesNotPublishFf41OrFf44ReadApertures() {
        int ordinary = Cpu.STAT_READ_PHASE_ORDINARY_HALT_WAKE;

        Fixture dot78 = new Fixture(true);
        dot78.advanceTo(1, 78);
        dot78.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("no-read dot-78 packet published the old mode-2 mux",
                Mode.PixelTransfer.ordinal(), dot78.readStatMode());

        Fixture dot454 = new Fixture(true);
        dot454.stat.setByte(StatRegister.ADDRESS, 0x20);
        dot454.advanceTo(1, 454);
        dot454.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("no-read dot-454 packet published the old HBlank mux",
                Mode.OamSearch.ordinal(), dot454.readStatMode());

        Fixture vblankLy = new Fixture(true);
        vblankLy.advanceTo(144, 450);
        assertEquals(144, vblankLy.readLy());
        vblankLy.stat.capturePerformanceNoCpuReadPhaseTrusted(ordinary);
        assertEquals("no-read VBlank packet published an FF44 line-edge sample",
                144, vblankLy.readLy());
    }

    @Test
    public void cgbFrameStartCpuBusRetainsOamSearchModeAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void synchronousHaltEntryRetainsCgbHblankOnTheFinalDot() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 454);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(true, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void asynchronousHaltEntryExposesDmgOamSearchAtLineRollover() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 455);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, true, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void ordinaryHaltWakeExposesDmgOamSearchAcrossLineEdgeReadCycle() {
        Fixture fixture = new Fixture(false);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        fixture.advanceTo(1, 448);
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        fixture.advanceTo(1, 452);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, true, true);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbMode0SourceCpuReadSamplesTheUpcomingMode2Latch() {
        for (boolean doubleSpeed : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(true, doubleSpeed);
            fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
            fixture.gpu.setByte(GpuRegister.SCX.getAddress(), doubleSpeed ? 0 : 3);
            fixture.advanceTo(1, doubleSpeed ? 452 : 450);

            assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
            fixture.stat.captureCpuStatReadPhase(false, false, false);
            assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        }

        Fixture earlierFineScrollPhase = new Fixture(true);
        earlierFineScrollPhase.stat.setByte(StatRegister.ADDRESS, 0x08);
        earlierFineScrollPhase.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        earlierFineScrollPhase.advanceTo(1, 450);
        earlierFineScrollPhase.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), earlierFineScrollPhase.readStatMode());

        for (boolean synchronous : new boolean[] {false, true}) {
            Fixture haltEntry = new Fixture(true);
            haltEntry.stat.setByte(StatRegister.ADDRESS, 0x08);
            haltEntry.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
            haltEntry.advanceTo(1, 450);

            haltEntry.stat.captureCpuStatReadPhase(synchronous, !synchronous, false);

            assertEquals(Mode.HBlank.ordinal(), haltEntry.readStatMode());
        }

        Fixture ordinaryHaltWake = new Fixture(true);
        ordinaryHaltWake.stat.setByte(StatRegister.ADDRESS, 0x08);
        ordinaryHaltWake.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        ordinaryHaltWake.advanceTo(1, 450);
        ordinaryHaltWake.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), ordinaryHaltWake.readStatMode());
    }

    @Test
    public void cgbOamDmaObjectLineCpuReadRetainsMode3AtTheMode0Edge() {
        Fixture fixture = new Fixture(true);
        for (int i = 0; i < 9; i++) {
            fixture.oam.setByte(0xfe00 + i * 4, 16);
            fixture.oam.setByte(0xfe01 + i * 4, 160);
        }
        fixture.advanceTo(1, 0);
        while (fixture.gpu.getMode() != Mode.HBlank
                || !fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }

        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.dma.setByte(0xff46, 0x12);
        for (int i = 0; i < 8; i++) {
            fixture.dma.tick();
        }
        assertTrue(fixture.dma.ownsOamForPpu());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbWindowEnabledBackgroundMode3ReleaseFollowsReadableLatchPhase() {
        for (int[] timing : new int[][] {{0, 250}, {2, 252}, {3, 250}, {5, 250}}) {
            int scrollX = timing[0];
            Fixture fixture = new Fixture(true);
            fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0xff);
            fixture.gpu.setByte(0xff40, 0xb1);
            fixture.gpu.setByte(GpuRegister.SCX.getAddress(), scrollX);
            int releaseTick = timing[1];
            fixture.advanceTo(1, releaseTick - 1);

            assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
            fixture.tick();
            assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        }
    }

    @Test
    public void cgbWindowDisabledBackgroundWaitsForShiftedHblankEdge() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 250);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbMode0SourceCpuReadSamplesTheEventAlignedHblankLatch() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 250);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture mode2Source = new Fixture(true);
        mode2Source.stat.setByte(StatRegister.ADDRESS, 0x20);
        mode2Source.advanceTo(1, 250);
        mode2Source.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), mode2Source.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedMode0SourceCpuReadLooksAheadToTheEventLatch() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 248);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbLine153CpuReadSamplesTheImminentCoincidenceRelease() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 153);
        fixture.advanceTo(153, 6);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.stat.beginCpuReadPhase(false, false, false, false);
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        fixture.tick();
        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void lcdRestartedCgbLineZeroRetainsBootPhaseMode3TailUntilRephased() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 254);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture interruptSourceSelected = new Fixture(true);
        interruptSourceSelected.gpu.setByte(0xff40, 0x11);
        interruptSourceSelected.gpu.setByte(0xff40, 0x91);
        interruptSourceSelected.stat.setByte(StatRegister.ADDRESS, 0x20);
        interruptSourceSelected.advanceTo(1, 0);
        interruptSourceSelected.advanceTo(0, 254);
        assertEquals(Mode.HBlank.ordinal(), interruptSourceSelected.readStatMode());

        Fixture rephased = new Fixture(true);
        rephased.gpu.setByte(0xff40, 0x11);
        rephased.gpu.setByte(0xff40, 0x91);
        rephased.gpu.onSpeedSwitch();
        rephased.advanceTo(1, 0);
        rephased.advanceTo(0, 254);
        assertEquals(Mode.HBlank.ordinal(), rephased.readStatMode());
    }

    @Test
    public void cgbStartedWindowModeReadPredictsHblankTwoDotsAhead() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 240);

        while (fixture.readStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
        }
        assertEquals(257, fixture.gpu.getTicksInLine());
    }

    @Test
    public void cgbDisabledWindowModeReadUsesTwoDotM0Prediction() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 15);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 120);
        fixture.gpu.setByteFromCpu(0xff40, 0x91);
        fixture.advanceTo(1, 240);

        while (fixture.readStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
        }
        assertEquals(259, fixture.gpu.getTicksInLine());
    }

    @Test
    public void cgbDoubleSpeedSelectedObjectsKeepTheirPredictedWindowTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        for (int i = 0; i < 9; i++) {
            fixture.oam.setByte(0xfe00 + 4 * i, 16);
            fixture.oam.setByte(0xfe01 + 4 * i, 8);
        }
        fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 8);
        fixture.gpu.setByte(0xff40, 0xb3);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 314);

        assertTrue(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(315, fixture.gpu.getTicksInLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(316, fixture.gpu.getTicksInLine());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedObjectTailDoesNotUseACpuModeOverride() {
        Fixture fixture = new Fixture(true, true);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 16);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 0);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 260);

        assertTrue(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.HBlank, fixture.gpu.getMode());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        assertEquals(-1, fixture.gpu.getCpuReadStatModeOverride(false, false, false));

        fixture.tick();
        assertEquals(-1, fixture.gpu.getCpuReadStatModeOverride(false, false, false));
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedWindowMode3LatchFollowsTheFullOutputTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 7);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 258);

        assertFalse(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(259, fixture.gpu.getTicksInLine());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedBackgroundCpuReadSettlesAtEndOfMachineCycle() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 244);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.advanceTo(1, 248);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture phaseThree = new Fixture(true);
        phaseThree.gpu.setByte(GpuRegister.SCX.getAddress(), 1);
        phaseThree.gpu.onSpeedSwitch();
        phaseThree.advanceTo(1, 247);

        assertEquals(Mode.PixelTransfer.ordinal(), phaseThree.readStatMode());

        Fixture phaseTwo = new Fixture(true);
        phaseTwo.gpu.onSpeedSwitch();
        phaseTwo.advanceTo(1, 246);

        assertEquals(Mode.HBlank.ordinal(), phaseTwo.readStatMode());
    }

    @Test
    public void rephasedCpuReadSeesMode3AtDot74Boundary() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 73);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(74, fixture.gpu.getTicksInLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void speedSwitchCompletionRetainsOldStatPhaseUntilNextLine() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 248);
        fixture.gpu.onSpeedSwitchComplete();
        var completionLine = fixture.gpu.captureState();

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());

        fixture.advanceTo(2, 248);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        fixture.gpu.restoreState(completionLine);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void sameLineScxWriteRetainsDynamicStatPhase() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 200);
        fixture.gpu.setByteFromCpu(GpuRegister.SCX.getAddress(), 2);
        fixture.advanceTo(1, 248);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbCoincidenceRemainsReadableThroughDot452() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(0, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedCoincidenceSwitchesToNextLineAtDot454() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(0, 453);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbLcdEnableLineReleasesCoincidenceAtItsShortenedEdge() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(0, 450);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(451, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbLcdRestartGridReleasesLaterCoincidenceAtStoredDot452() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 451);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(452, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedLcdEnableLineReleasesCoincidenceAtDot453() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(0, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(453, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedUsesTwoDotReadCyclesAtStatBoundaries() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());

        fixture.advanceTo(1, 77);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedUsesCpuLyEdgeAndRetains153AfterRollover() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(152, 451);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 1);
        assertEquals(153, fixture.readLy());
        fixture.tick();
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void doubleSpeedMode0PreviewIsReadOnlyAndExpiresAtThePeripheralTick() {
        Fixture fixture = new Fixture(true, true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.advanceTo(1, 248);
        assertEquals(250, fixture.gpu.getMode0InterruptTick());
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void mode0CpuLookaheadRequiresANewEligibleInterruptEdge() {
        Fixture disabled = new Fixture(true);
        disabled.advanceTo(1, 249);
        assertEquals(250, disabled.gpu.getMode0InterruptTick());
        assertFalse(disabled.stat.isMode0InterruptEdgeNextTick());

        Fixture alreadyAsserted = new Fixture(true);
        alreadyAsserted.stat.setByte(StatRegister.ADDRESS, 0x08);
        alreadyAsserted.advanceTo(1, 249);
        alreadyAsserted.interrupts.requestInterrupt(LCDC);
        assertFalse(alreadyAsserted.stat.isMode0InterruptEdgeNextTick());

        Fixture eligible = new Fixture(true);
        eligible.stat.setByte(StatRegister.ADDRESS, 0x08);
        eligible.advanceTo(1, 249);
        eligible.clearInterrupts();
        assertTrue(eligible.stat.isMode0InterruptEdgeNextTick());
    }

    @Test
    public void dmgIfReadPreviewsTerminalWindowMode0OnlyAtItsFinalBusPhase() {
        Fixture fixture = new Fixture(false);
        fixture.oam.setByte(0xfe00, 0x10);
        fixture.oam.setByte(0xfe01, 0xa7);
        fixture.gpu.setByte(0xff40, 0xb7);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 0xa6);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 252);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.advanceTo(1, 256);
        assertEquals(258, fixture.gpu.getMode0InterruptTick());
        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedNormalSpeedMode2IsReadableAtTheEarlyCpuBusPhase() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 20);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 450);

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedNormalSpeedFrameMode2IsReadableBeforeItsLatchSettles() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 452);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedMode2TailReadAlsoSamplesTheUpcomingVblankBit() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(143, 452);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << VBlank.ordinal(),
                fixture.interrupts.getByte(0xff0f) & (1 << VBlank.ordinal()));
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0,
                fixture.interrupts.getByte(0xff0f) & (1 << VBlank.ordinal()));
    }

    @Test
    public void normalSpeedMode0AcknowledgeWinsOnlyInsideItsCaptureWindow() {
        Fixture captured = new Fixture(true);
        captured.stat.setByte(StatRegister.ADDRESS, 0x08);
        captured.advanceTo(1, 246);
        captured.interrupts.requestInterrupt(LCDC);
        captured.interrupts.clearInterrupt(LCDC);
        captured.tick();
        captured.advanceTo(1, 250);
        assertEquals(0, captured.lcdInterruptFlag());

        Fixture older = new Fixture(true);
        older.stat.setByte(StatRegister.ADDRESS, 0x08);
        older.advanceTo(1, 245);
        older.interrupts.requestInterrupt(LCDC);
        older.interrupts.clearInterrupt(LCDC);
        older.tick();
        older.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), older.lcdInterruptFlag());

        Fixture dmgCaptured = new Fixture(false);
        dmgCaptured.stat.setByte(StatRegister.ADDRESS, 0x08);
        dmgCaptured.advanceTo(1, 244);
        dmgCaptured.interrupts.requestInterrupt(LCDC);
        dmgCaptured.interrupts.clearInterrupt(LCDC);
        dmgCaptured.tick();
        dmgCaptured.advanceTo(1, 250);
        assertEquals(0, dmgCaptured.lcdInterruptFlag());

        Fixture dmgOlder = new Fixture(false);
        dmgOlder.stat.setByte(StatRegister.ADDRESS, 0x08);
        dmgOlder.advanceTo(1, 243);
        dmgOlder.interrupts.requestInterrupt(LCDC);
        dmgOlder.interrupts.clearInterrupt(LCDC);
        dmgOlder.tick();
        dmgOlder.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), dmgOlder.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedMode0SetWinsOnlyTheFollowingAcknowledgeSlot() {
        Fixture captured = new Fixture(true, true);
        captured.stat.setByte(StatRegister.ADDRESS, 0x08);
        captured.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());
        captured.interrupts.clearInterrupt(LCDC);
        captured.tick();
        assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());

        Fixture later = new Fixture(true, true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 252);
        later.interrupts.clearInterrupt(LCDC);
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0SetWinsOnlyTheCoincidentInterruptFlagWrite() {
        Fixture coincident = new Fixture(true);
        coincident.stat.setByte(StatRegister.ADDRESS, 0x08);
        coincident.advanceTo(1, 250);
        coincident.clearInterrupts();
        coincident.tick();
        assertEquals(1 << LCDC.ordinal(), coincident.lcdInterruptFlag());

        Fixture later = new Fixture(true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 251);
        later.clearInterrupts();
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void dmgMode0ClearWinsTheSamePeripheralSlot() {
        Fixture coincident = new Fixture(false);
        coincident.stat.setByte(StatRegister.ADDRESS, 0x08);
        coincident.advanceTo(1, 249);
        coincident.clearInterrupts();
        coincident.tick();
        assertEquals(0, coincident.lcdInterruptFlag());

        Fixture ordinary = new Fixture(false);
        ordinary.stat.setByte(StatRegister.ADDRESS, 0x08);
        ordinary.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), ordinary.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedMode0SetWinsTwoAndThreeDotFlagWriteSlots() {
        for (int clearTick : new int[] {251, 252}) {
            Fixture captured = new Fixture(true, true);
            captured.stat.setByte(StatRegister.ADDRESS, 0x08);
            captured.advanceTo(1, clearTick);
            captured.clearInterrupts();
            captured.tick();
            assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());
        }

        Fixture later = new Fixture(true, true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 253);
        later.clearInterrupts();
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void capturedMode0IfReadMasksOnlyTheBusValue() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 245);
        fixture.interrupts.requestInterrupt(LCDC);
        fixture.interrupts.clearInterrupt(LCDC);
        fixture.tick();
        fixture.advanceTo(1, 249);
        fixture.stat.captureCpuInterruptReadPhase(2, false, false);

        fixture.tick();

        assertTrue(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertEquals(0, fixture.lcdInterruptFlag());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        Fixture older = new Fixture(true);
        older.stat.setByte(StatRegister.ADDRESS, 0x08);
        older.advanceTo(1, 249);
        older.clearInterrupts();
        older.stat.captureCpuInterruptReadPhase(2, false, false);
        older.tick();
        assertEquals(1 << LCDC.ordinal(), older.lcdInterruptFlag());
    }

    @Test
    public void mode0RequestCanRetainCpuPhaseOrWaitForInstruction() {
        Fixture phased = new Fixture(true);
        phased.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        phased.interrupts.enableInterrupts(false);
        phased.stat.setByte(StatRegister.ADDRESS, 0x08);
        phased.advanceTo(1, 249);
        phased.clearInterrupts();
        phased.stat.captureCpuInterruptReadPhase(0, true, false);
        phased.tick();
        assertTrue(phased.interrupts.isInterruptRequested());
        assertFalse(phased.interrupts.isUnphasedPpuInterruptRequested());

        Fixture instruction = new Fixture(true);
        instruction.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        instruction.interrupts.enableInterrupts(false);
        instruction.stat.setByte(StatRegister.ADDRESS, 0x08);
        instruction.advanceTo(1, 249);
        instruction.clearInterrupts();
        instruction.stat.captureCpuInterruptReadPhase(0, false, true);
        instruction.tick();
        assertTrue(instruction.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(instruction.interrupts.isInterruptRequested());
        instruction.interrupts.onInstructionFinished();
        assertTrue(instruction.interrupts.isInterruptRequested());
    }

    @Test
    public void rephasedDoubleSpeedCgbReleasesCoincidenceInFinalVblankBusSlot() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        fixture.advanceTo(143, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        Fixture unrephased = new Fixture(true, true);
        unrephased.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        unrephased.advanceTo(143, 453);
        assertEquals(0x04, unrephased.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedTailLycEdgeIsReadableBeforeCpuAcceptance() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.clearInterrupts();
        fixture.advanceTo(0, 453);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());

        fixture.advanceTo(1, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void cgbDoubleSpeedLycWriteRequestCrossesThirdPpuClock() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(1, 100);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.clearInterrupts();

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbNormalSpeedMidLineLycWriteRequestCrossesFifthPpuClock() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 100);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.clearInterrupts();

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        for (int clock = 0; clock < 4; clock++) {
            fixture.tick();
            assertEquals("LYC write response must not arrive early", 0,
                    fixture.lcdInterruptFlag());
        }
        fixture.tick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void midLineStatWriteRaisesTheAlreadyActiveModeZeroSource() {
        Fixture fixture = new Fixture(true);
        fixture.advanceToHBlank();
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.tick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void lcdRestartReevaluatesTheRetiredModeLineBeforeLycEdge() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceToHBlank();
        fixture.clearInterrupts();

        fixture.gpu.setByte(0xff40, 0x00);
        fixture.tick();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.tick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void restoredLcdOffStateClearsStatLineBeforeRestart() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceToHBlank();
        fixture.clearInterrupts();
        fixture.gpu.setByte(0xff40, 0x00);
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.tick();
        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        fixture.tick();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.tick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbDoubleSpeedVblankFlagIsReadableBeforeCpuAcceptance() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << VBlank.ordinal());
        fixture.clearInterrupts();
        fixture.advanceTo(143, 453);

        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        fixture.tick();
        assertTrue(fixture.interrupts.isInterruptFlagSet(VBlank));
        assertFalse(fixture.interrupts.isInterruptRequested());

        fixture.advanceTo(144, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void cgbDmgCompatibilityUsesItsOwnLyBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.advanceTo(152, 449);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());
        fixture.advanceTo(153, 4);
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbDmgCompatibilityUsesItsOwnStatBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        fixture.advanceTo(153, 454);
        assertEquals(Mode.VBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbLateLycWriteDoesNotUnmaskCapturedMode0Event() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(1, 250);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0DisableWithinCaptureWindowDoesNotWithdrawEvent() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbTailMode0EnableDoesNotCreateCombinationalInterrupt() {
        for (boolean doubleSpeed : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(true, doubleSpeed);
            fixture.advanceTo(1, 450);
            fixture.clearInterrupts();

            fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
            fixture.advanceTo(2, 0);

            assertEquals(0, fixture.lcdInterruptFlag());
        }
    }

    @Test
    public void cgbMode0EnableDuringOamOnlyArmsTheNextHblankEvent() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 40);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.advanceToHBlank();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbTailMode0EnableUsesCoincidencePhaseWhenSourceIsDisabled() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 450);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 0);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbTailMode0SuppressionExpiresBeforeLaterScanlines() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 450);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 449);
        fixture.clearInterrupts();

        fixture.advanceTo(2, 450);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 452);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode0BlocksLycEnableAfterTwentyDotSettleWindow() {
        Fixture atBoundary = activeCgbMode0(false);
        int mode0Tick = atBoundary.gpu.getMode0InterruptTick();
        atBoundary.advanceTo(1, mode0Tick + 20);
        atBoundary.clearInterrupts();

        atBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(1 << LCDC.ordinal(), atBoundary.lcdInterruptFlag());

        Fixture afterBoundary = activeCgbMode0(false);
        mode0Tick = afterBoundary.gpu.getMode0InterruptTick();
        afterBoundary.advanceTo(1, mode0Tick + 21);
        afterBoundary.clearInterrupts();

        afterBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(0, afterBoundary.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbMode0ReleasesLycEnableForFinalEightCpuClocks() {
        Fixture beforeBoundary = activeCgbMode0(true);
        beforeBoundary.advanceTo(1, 451);
        beforeBoundary.clearInterrupts();

        beforeBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(0, beforeBoundary.lcdInterruptFlag());

        Fixture atBoundary = activeCgbMode0(true);
        atBoundary.advanceTo(1, 452);
        atBoundary.clearInterrupts();

        atBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(1 << LCDC.ordinal(), atBoundary.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode1LycEnableBoundaryFollowsRephasedClock() {
        assertEquals(0, cgbMode1LycEnableFlagAt(449, true));
        assertEquals(1 << LCDC.ordinal(), cgbMode1LycEnableFlagAt(450, true));
        assertEquals(0, cgbMode1LycEnableFlagAt(452, false));
        assertEquals(1 << LCDC.ordinal(), cgbMode1LycEnableFlagAt(453, false));
    }

    @Test
    public void cgbVblankLycToMode1HandoffUsesTheEarlyComparatorRelease() {
        Fixture beforeRelease = new Fixture(true);
        beforeRelease.gpu.setByte(GpuRegister.LYC.getAddress(), 144);
        beforeRelease.stat.setByte(StatRegister.ADDRESS, 0x40);
        beforeRelease.advanceTo(144, 446);
        beforeRelease.clearInterrupts();

        beforeRelease.stat.setByte(StatRegister.ADDRESS, 0x10);

        assertEquals(0, beforeRelease.lcdInterruptFlag());

        Fixture atRelease = new Fixture(true);
        atRelease.gpu.setByte(GpuRegister.LYC.getAddress(), 144);
        atRelease.stat.setByte(StatRegister.ADDRESS, 0x40);
        atRelease.advanceTo(144, 450);
        atRelease.clearInterrupts();

        atRelease.stat.setByte(StatRegister.ADDRESS, 0x10);

        assertEquals(1 << LCDC.ordinal(), atRelease.lcdInterruptFlag());
    }

    @Test
    public void dmgStatWriteSettlesBeforeFollowingMode0Edge() {
        Fixture fixture = new Fixture();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 100);

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void dmgStatWriteGlitchSamplesLineStartCoincidence() {
        Fixture matching = new Fixture(false);
        matching.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        matching.advanceTo(1, 0);
        matching.clearInterrupts();

        matching.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(1 << LCDC.ordinal(), matching.lcdInterruptFlag());

        Fixture different = new Fixture(false);
        different.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        different.advanceTo(1, 0);
        different.clearInterrupts();
        different.stat.setByte(StatRegister.ADDRESS, 0x00);
        assertEquals(0, different.lcdInterruptFlag());
    }

    @Test
    public void dmgLineZeroStatGlitchCannotArmTheRetiredMode2Event() {
        Fixture fixture = new Fixture(false);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(153, 455);
        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(0, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void dmgOutgoingLycBlocksMode1StatWriteInTheFinalVblankSlot() {
        Fixture captured = dmgLycMatchAt(148, 451);
        captured.tick();
        assertEquals(452, captured.gpu.getTicksInLine());

        captured.stat.setByte(StatRegister.ADDRESS, 0x50);

        assertEquals(0, captured.lcdInterruptFlag());

        Fixture afterHandoff = dmgLycMatchAt(148, 455);
        afterHandoff.tick();
        assertEquals(149, afterHandoff.gpu.getLine());

        afterHandoff.stat.setByte(StatRegister.ADDRESS, 0x50);

        assertEquals(1 << LCDC.ordinal(), afterHandoff.lcdInterruptFlag());
    }

    @Test
    public void dmgOutgoingLycBlocksMode0StatWriteInTheFinalVisibleLineSlot() {
        Fixture captured = dmgLycMatchAt(143, 451);
        captured.tick();
        assertEquals(452, captured.gpu.getTicksInLine());

        captured.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, captured.lcdInterruptFlag());

        Fixture afterHandoff = dmgLycMatchAt(143, 455);
        afterHandoff.tick();
        assertEquals(144, afterHandoff.gpu.getLine());

        afterHandoff.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(1 << LCDC.ordinal(), afterHandoff.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0StatCaptureSurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.advanceToHBlank();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void modeEventRegisterLatchesSurviveMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 244);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();

        fixture.advanceTo(1, 250);
        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 250);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbM2EventPublishesBeforeSameTimestampCpuRead() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();

        fixture.stat.preCpuTick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        fixture.stat.preCpuTick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void firstPostFrameLyc0M2HandoffPublishesBeforeCpuIo() {
        Fixture fixture = pendingFrameLyc0M2Event(0x60, 0, 1);

        fixture.stat.publishFrameLyc0Mode2HandoffBeforeCpu();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void ordinaryM2HandoffsDoNotPublishEarlyBeforeCpuIo() {
        Fixture m2Only = pendingFrameLyc0M2Event(0x20, 0, 1);
        m2Only.stat.publishFrameLyc0Mode2HandoffBeforeCpu();
        assertEquals(0, m2Only.lcdInterruptFlag());

        Fixture ordinaryLine = pendingFrameLyc0M2Event(0x60, 4, 5);
        ordinaryLine.stat.publishFrameLyc0Mode2HandoffBeforeCpu();
        assertEquals(0, ordinaryLine.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode2DisableAtDot450CancelsScheduledEvent() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.stat.preCpuTick();
        fixture.advanceTo(2, 0);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode2CapturesLycWriteAtSixClockBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 444);

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 448);
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void cgbMode2CaptureDoesNotRetriggerWhenIfWasAlreadyHigh() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();
        fixture.advanceTo(2, 0);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbFrameMode2CapturesAtDot454AndPublishesAtDot455() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void frameMode2DoesNotRetriggerInsideTheInterruptAcknowledgeWindow() {
        Fixture cgb = new Fixture(true);
        cgb.stat.setByte(StatRegister.ADDRESS, 0x20);
        cgb.advanceTo(153, 446);
        cgb.interrupts.clearInterrupt(LCDC);
        cgb.tick();
        cgb.advanceTo(0, 0);
        assertEquals(0, cgb.lcdInterruptFlag());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.stat.setByte(StatRegister.ADDRESS, 0x20);
        doubleSpeed.advanceTo(153, 452);
        doubleSpeed.interrupts.clearInterrupt(LCDC);
        doubleSpeed.tick();
        doubleSpeed.advanceTo(0, 0);
        assertEquals(0, doubleSpeed.lcdInterruptFlag());

        Fixture dmg = new Fixture();
        dmg.stat.setByte(StatRegister.ADDRESS, 0x20);
        dmg.advanceTo(153, 448);
        dmg.interrupts.clearInterrupt(LCDC);
        dmg.tick();
        dmg.advanceTo(0, 0);
        assertEquals(0, dmg.lcdInterruptFlag());
    }

    @Test
    public void frameMode2RetriggersAfterTheAcknowledgeWindow() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 442);
        fixture.interrupts.clearInterrupt(LCDC);
        fixture.tick();
        fixture.advanceTo(0, 0);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedNormalSpeedCgbFrameMode2PublishesAfterRollover() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();
        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(0, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void recentVblankAcknowledgeConsumesNormalSpeedFrameEdge() {
        for (boolean gbc : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(gbc);
            fixture.advanceTo(143, 449);
            fixture.interrupts.requestInterrupt(VBlank);
            fixture.interrupts.clearInterrupt(VBlank);

            fixture.advanceTo(144, 0);

            assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        }
    }

    @Test
    public void olderVblankAcknowledgeDoesNotConsumeNormalSpeedFrameEdge() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(143, 447);
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.interrupts.clearInterrupt(VBlank);

        fixture.advanceTo(144, 0);

        assertTrue(fixture.interrupts.isInterruptFlagSet(VBlank));
    }

    @Test
    public void doubleSpeedVblankTailIsASingleCapturedOccurrence() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(143, 452);
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.interrupts.clearInterrupt(VBlank);

        fixture.advanceTo(143, 454);
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        fixture.advanceTo(144, 0);
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
    }

    @Test
    public void normalSpeedCgbLyc153FrameMode2PublishesAtDot454() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 153);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();

        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void normalSpeedCgbFrameMode2DoesNotRetriggerAfterCapturedHighIf() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        fixture.clearInterrupts();
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbFrameMode2DisableCancelsCapturedEvent() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();
        fixture.tick();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void pendingNormalSpeedCgbFrameMode2SurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();
        fixture.tick();
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode2CapturesStatWriteAtTwoClockBoundary() {
        assertEquals(1 << LCDC.ordinal(), cgbMode2StatCaptureFlagAt(448));
        assertEquals(0, cgbMode2StatCaptureFlagAt(449));
    }

    @Test
    public void doubleSpeedCgbMode2DisableRetractsRequestThroughDot454() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();

        fixture.advanceTo(1, 454);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void doubleSpeedCgbMode2DisableCannotRetractRequestAfterDot454() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();

        fixture.advanceTo(1, 455);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void retractableDoubleSpeedCgbMode2EventSurvivesMementoRoundTrip() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.advanceTo(1, 455);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        fixture.advanceTo(1, 454);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbLycSourceSharesScheduledM2PublicationBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.preCpuTick();
        // LYC is sampled as a blocker by the shared MSTAT event. Enabling the
        // source does not defer that event past the same-timestamp CPU callback.
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void pendingCgbM2EventSurvivesMementoRoundTrip() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        fixture.clearInterrupts();

        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        assertEquals(448, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void normalSpeedCgbPublishesCapturedMode1AtDot455() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();

        fixture.advanceTo(143, 454);
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode1CanBeDisabledInItsCaptureDot() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbPublishesCapturedMode1AtDot454() {
        Fixture fixture = new Fixture(true, true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 453);
        fixture.clearInterrupts();

        fixture.tick();
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbMode1CaptureDoesNotRetriggerWhenIfWasAlreadyHigh() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();
        fixture.advanceTo(143, 455);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void dmgLyc143Mode1CaptureStraddlesTheLateInterruptAcknowledge() {
        Fixture acknowledgeBefore = new Fixture(false);
        acknowledgeBefore.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeBefore.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeBefore.advanceTo(143, 444);
        acknowledgeBefore.interrupts.requestInterrupt(LCDC);
        acknowledgeBefore.interrupts.clearInterrupt(LCDC);
        acknowledgeBefore.advanceTo(143, 448);

        assertEquals(1 << LCDC.ordinal(), acknowledgeBefore.lcdInterruptFlag());

        Fixture acknowledgeAfter = new Fixture(false);
        acknowledgeAfter.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeAfter.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeAfter.advanceTo(143, 448);
        acknowledgeAfter.interrupts.clearInterrupt(LCDC);
        acknowledgeAfter.advanceTo(144, 0);

        assertEquals(0, acknowledgeAfter.lcdInterruptFlag());

        Fixture explicitClear = new Fixture(false);
        explicitClear.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        explicitClear.stat.setByte(StatRegister.ADDRESS, 0x50);
        explicitClear.advanceTo(143, 447);
        explicitClear.clearInterrupts();
        explicitClear.tick();

        assertEquals(0, explicitClear.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbMode1RecapturesIfAtDot452() {
        Fixture acknowledgeBefore = new Fixture(true, true);
        acknowledgeBefore.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeBefore.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeBefore.advanceTo(143, 451);
        acknowledgeBefore.interrupts.clearInterrupt(LCDC);
        acknowledgeBefore.advanceTo(143, 454);

        assertEquals(1 << LCDC.ordinal(), acknowledgeBefore.lcdInterruptFlag());

        Fixture acknowledgeAfter = new Fixture(true, true);
        acknowledgeAfter.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeAfter.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeAfter.advanceTo(143, 452);
        acknowledgeAfter.interrupts.clearInterrupt(LCDC);
        acknowledgeAfter.advanceTo(143, 454);

        assertEquals(0, acknowledgeAfter.lcdInterruptFlag());
    }

    @Test
    public void pendingCgbMode1EventSurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);
        var gpuMemento = fixture.gpu.captureState();
        var statMemento = fixture.stat.captureState();
        var interruptMemento = fixture.interrupts.captureState();

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        fixture.clearInterrupts();

        fixture.gpu.restoreState(gpuMemento);
        fixture.stat.restoreState(statMemento);
        fixture.interrupts.restoreState(interruptMemento);
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void lateModeWriteDoesNotSuppressTheNextFramesLine143Event() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(143, 453);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();

        fixture.advanceTo(143, 100);
        fixture.clearInterrupts();
        do {
            fixture.tick();
        } while (fixture.gpu.getLine() != 143 || !fixture.gpu.isMode0IntWindow());

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    private static Fixture pendingNormalSpeedCgbM2Event() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
        return fixture;
    }

    private static Fixture pendingFrameLyc0M2Event(int stat, int lyc, int line) {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), lyc);
        fixture.stat.setByte(StatRegister.ADDRESS, stat);
        fixture.advanceTo(line, 447);
        fixture.clearInterrupts();
        fixture.tick();
        fixture.tick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
        return fixture;
    }

    private static Fixture publishedDoubleSpeedCgbM2Event() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 452);
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        return fixture;
    }

    private static int cgbMode2StatCaptureFlagAt(int writeTick) {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();
        if (writeTick == 449) {
            fixture.stat.preCpuTick();
            fixture.tick();
        }

        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        while (fixture.gpu.getTicksInLine() < 450) {
            fixture.stat.preCpuTick();
            fixture.tick();
        }
        fixture.stat.preCpuTick();
        return fixture.lcdInterruptFlag();
    }

    private static Fixture activeCgbMode0(boolean doubleSpeed) {
        Fixture fixture = new Fixture(true, doubleSpeed);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 100);
        fixture.advanceToHBlank();
        return fixture;
    }

    private static Fixture dmgLycMatchAt(int line, int tick) {
        Fixture fixture = new Fixture(false);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), line);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(line, tick);
        fixture.clearInterrupts();
        return fixture;
    }

    private static int cgbMode1LycEnableFlagAt(int tick, boolean rephased) {
        Fixture fixture = new Fixture(true);
        if (rephased) {
            fixture.gpu.onSpeedSwitch();
        }
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(153, tick);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x50);

        return fixture.lcdInterruptFlag();
    }

    private static int cpuStatModeOverride(StatRegister stat) {
        return intField(stat, "cpuStatModeOverride");
    }

    private static Fixture nativeStressFixture() {
        Fixture fixture = new Fixture(true, true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x78);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0x47);
        fixture.interrupts.setByte(0xffff, 0x1f);
        for (int i = 0; i < 40; i++) {
            int address = 0xfe00 + i * 4;
            fixture.oam.setByte(address, 16 + (i * 11) % 128);
            fixture.oam.setByte(address + 1, 8 + (i * 17) % 168);
            fixture.oam.setByte(address + 2, i * 3);
            fixture.oam.setByte(address + 3, i & 0x0f);
        }
        return fixture;
    }

    private static void assertNativeGpuDot(Fixture generic, Fixture specialized, int dot)
            throws Exception {
        int genericFacts = generic.gpu.getNativeCgbPerformancePostStatFacts();
        int specializedFacts = specialized.gpu.getNativeCgbPerformancePostStatFacts();
        assertEquals("native facts at dot " + dot, genericFacts, specializedFacts);
        assertEquals("native facts validity at dot " + dot,
                (genericFacts & Gpu.NATIVE_CGB_POST_STAT_FACTS_VALID) != 0,
                (specializedFacts & Gpu.NATIVE_CGB_POST_STAT_FACTS_VALID) != 0);
        assertEquals("native facts checkpoint at dot " + dot,
                generic.gpu.isStatEventCheckpointForTick(),
                (specializedFacts & Gpu.NATIVE_CGB_POST_STAT_CHECKPOINT) != 0);
        GpuTimingSnapshot expected = new GpuTimingSnapshot();
        GpuTimingSnapshot actual = new GpuTimingSnapshot();
        generic.gpu.captureStatTimingForTick(expected);
        specialized.gpu.captureNativeCgbPerformancePostStatTiming(actual);
        assertEquals("generic coincidence release at dot " + dot,
                expected.firstLine ? 452 : 454, generic.gpu.getCoincidenceReleaseTick());
        assertTimingSnapshotsEqual("GPU timing at dot " + dot, expected, actual);
    }

    private static void assertTimingSnapshotsEqual(
            String path, GpuTimingSnapshot expected, GpuTimingSnapshot actual) {
        assertEquals(path + ".line", expected.line, actual.line);
        assertEquals(path + ".ticksInLine", expected.ticksInLine, actual.ticksInLine);
        assertEquals(path + ".visibleLy", expected.visibleLy, actual.visibleLy);
        assertEquals(path + ".earlyLineEdgeTick", expected.earlyLineEdgeTick,
                actual.earlyLineEdgeTick);
        assertEquals(path + ".mode0InterruptTick", expected.mode0InterruptTick,
                actual.mode0InterruptTick);
        assertEquals(path + ".cpuMachineCycleDots", expected.cpuMachineCycleDots,
                actual.cpuMachineCycleDots);
        assertEquals(path + ".dmgCompat", expected.dmgCompat, actual.dmgCompat);
        assertEquals(path + ".lcdEnabled", expected.lcdEnabled, actual.lcdEnabled);
        assertEquals(path + ".firstLine", expected.firstLine, actual.firstLine);
        assertEquals(path + ".statModeLatchRephasedBySpeedSwitch",
                expected.statModeLatchRephasedBySpeedSwitch,
                actual.statModeLatchRephasedBySpeedSwitch);
        assertEquals(path + ".mode0HaltWakeTick", expected.mode0HaltWakeTick,
                actual.mode0HaltWakeTick);
        assertEquals(path + ".mode0IntWindow", expected.mode0IntWindow, actual.mode0IntWindow);
        assertEquals(path + ".mode1IntWindow", expected.mode1IntWindow, actual.mode1IntWindow);
        assertEquals(path + ".mode2IntWindow", expected.mode2IntWindow, actual.mode2IntWindow);
        assertEquals(path + ".doubleSpeed", expected.doubleSpeed, actual.doubleSpeed);
        assertEquals(path + ".nativeDoubleSpeed", expected.nativeDoubleSpeed,
                actual.nativeDoubleSpeed);
    }

    private static void advanceGpuPair(Fixture generic, Fixture specialized, int ticks) {
        for (int i = 0; i < ticks; i++) {
            generic.gpu.tick();
            specialized.gpu.tick();
        }
    }

    private static void advanceNativePair(
            Fixture generic, Fixture specialized, int ticks, String path) {
        for (int i = 0; i < ticks; i++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals(path + " STAT at dot " + i,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals(path + " IF at dot " + i,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
    }

    private static void assertNativePostGpuFallback(
            Fixture generic, Fixture specialized, String path) {
        for (int i = 0; i < 1_024; i++) {
            generic.gpu.tick();
            specialized.gpu.tick();
            int facts = specialized.gpu.getNativeCgbPerformancePostStatFacts();
            assertEquals(path + " facts invalid at dot " + i, 0,
                    facts & Gpu.NATIVE_CGB_POST_STAT_FACTS_VALID);
            generic.stat.tick();
            specialized.stat.tickNativeCgbPerformancePostGpu();
            assertEquals(path + " STAT at dot " + i,
                    generic.stat.captureState(), specialized.stat.captureState());
            assertEquals(path + " IF at dot " + i,
                    generic.interrupts.captureState(), specialized.interrupts.captureState());
        }
    }

    private static int intField(StatRegister stat, String name) {
        try {
            Field field = StatRegister.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(stat);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setLongField(StatRegister stat, String name, long value)
            throws Exception {
        Field field = StatRegister.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(stat, value);
    }

    private static void setBooleanField(StatRegister stat, String name, boolean value)
            throws Exception {
        Field field = StatRegister.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(stat, value);
    }

    private static void setIntField(StatRegister stat, String name, int value)
            throws Exception {
        Field field = StatRegister.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(stat, value);
    }

    private static Fixture settledDmgLcdOffFixture() {
        Fixture fixture = new Fixture(false);
        fixture.gpu.setByte(0xff40, 0);
        fixture.tick();
        assertFalse(fixture.gpu.isLcdEnabled());
        assertEquals(54, fixture.stat.performanceSgbLcdOffSpanLimit(54));
        return fixture;
    }

    private static Fixture quietFixture() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 100);
        assertFalse(fixture.gpu.isStatEventCheckpoint());
        return fixture;
    }

    private static Fixture settledNativeLycOnlyFixture(int line, int ticksInLine, int lyc) {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), lyc);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(line, ticksInLine);
        return fixture;
    }

    private static GpuTimingSnapshot statTiming(StatRegister stat) throws Exception {
        Field field = StatRegister.class.getDeclaredField("timing");
        field.setAccessible(true);
        return (GpuTimingSnapshot) field.get(stat);
    }

    private static long statTimingGeneration(StatRegister stat) throws Exception {
        return longField(stat, "timingGeneration");
    }

    private static long longField(StatRegister stat, String name) throws Exception {
        Field field = StatRegister.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(stat);
    }

    private static void assertStatTimingMatchesGpu(Fixture fixture) throws Exception {
        GpuTimingSnapshot timing = statTiming(fixture.stat);
        assertEquals(fixture.gpu.getLine(), timing.line);
        assertEquals(fixture.gpu.getTicksInLine(), timing.ticksInLine);
        assertEquals(fixture.gpu.getVisibleLy(), timing.visibleLy);
        assertEquals(fixture.gpu.getMode0InterruptTick(), timing.mode0InterruptTick);
        assertEquals(fixture.gpu.isLcdEnabled(), timing.lcdEnabled);
    }

    private static class Fixture {

        private final InterruptManager interrupts;

        private final StatRegister stat;

        private final SpeedMode speedMode;

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Dma dma;

        private final Gpu gpu;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean gbc) {
            this(gbc, false);
        }

        private Fixture(boolean gbc, boolean doubleSpeed) {
            this(gbc, doubleSpeed, false);
        }

        private Fixture(boolean gbc, boolean doubleSpeed, boolean earlyCgbLyReadEdge) {
            interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            speedMode = doubleSpeed ? new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return 2;
                }
            } : new SpeedMode(gbc);
            dma = new Dma(new Ram(0, 0x10000), oam, speedMode);
            gpu = new Gpu(
                    new Display(gbc),
                    dma,
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    gbc,
                    speedMode,
                    false,
                    earlyCgbLyReadEdge);
            stat.init(gpu);
        }

        private void advanceToHBlank() {
            while (!gpu.isMode0IntWindow()) {
                tick();
            }
        }

        private void advanceToNextLineStart() {
            int targetLine = gpu.getLine() + 1;
            while (gpu.getLine() != targetLine || gpu.getTicksInLine() != 0) {
                tick();
            }
        }

        private void advanceTo(int line, int ticksInLine) {
            do {
                tick();
            } while (gpu.getLine() != line || gpu.getTicksInLine() != ticksInLine);
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }

        private void clearInterrupts() {
            interrupts.setByteFromCpu(0xff0f, 0);
        }

        private int lcdInterruptFlag() {
            return interrupts.getByte(0xff0f) & (1 << LCDC.ordinal());
        }

        private int readLy() {
            return gpu.getByte(GpuRegister.LY.getAddress());
        }

        private int readStatMode() {
            return stat.getByte(StatRegister.ADDRESS) & 0x03;
        }
    }
}
