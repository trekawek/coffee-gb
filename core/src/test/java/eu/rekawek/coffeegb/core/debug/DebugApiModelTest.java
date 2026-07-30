package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason;
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DebugApiModelTest {

    @Test
    public void resultKeepsSuccessAndExpectedFailureDisjoint() {
        DebugSnapshot snapshot = snapshot(true);
        DebugResult<DebugSnapshot> success = DebugResult.success(snapshot);
        assertTrue(success.isSuccess());
        assertFalse(success.isFailure());
        assertSame(snapshot, success.value());
        assertThrows(IllegalStateException.class, success::error);

        DebugResult<DebugSnapshot> failure = DebugResult.failure(
                DebugErrorCode.NOT_PAUSED, "The session must be paused before stepping");
        assertFalse(failure.isSuccess());
        assertTrue(failure.isFailure());
        assertEquals(DebugErrorCode.NOT_PAUSED, failure.error().code());
        assertThrows(IllegalStateException.class, failure::value);

        DebugResult<Void> acknowledgement = DebugResult.success();
        assertTrue(acknowledgement.isSuccess());
        assertNull(acknowledgement.value());
        assertThrows(NullPointerException.class, () -> DebugResult.success(null));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugError(DebugErrorCode.INTERNAL_ERROR, "  "));
    }

    @Test
    public void memoryBlockOwnsItsInputAndExposesOnlyIndexedValues() {
        byte[] source = {(byte) 0x80, 0x23, (byte) 0xff};
        DebugMemoryBlock block = new DebugMemoryBlock(
                DebugAddressSpace.SYSTEM_BUS, 0xc000, source);
        source[0] = 0;

        assertEquals(DebugAddressSpace.SYSTEM_BUS, block.addressSpace());
        assertEquals(0xc000, block.startAddress());
        assertEquals(0xc003, block.endExclusive());
        assertEquals(3, block.length());
        assertEquals((byte) 0x80, block.byteAt(0));
        assertEquals(0x80, block.unsignedByteAt(0));
        assertEquals(0xff, block.unsignedByteAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> block.byteAt(3));
        assertEquals(block, new DebugMemoryBlock(
                DebugAddressSpace.SYSTEM_BUS, 0xc000,
                new byte[]{(byte) 0x80, 0x23, (byte) 0xff}));
    }

    @Test
    public void memoryRequestsCannotWrapTheNamedAddressSpace() {
        DebugMemoryRequest exact = new DebugMemoryRequest(
                DebugAddressSpace.SYSTEM_BUS, 0, DebugMemoryRequest.MAX_LENGTH);
        assertEquals(0x10000, exact.endExclusive());
        DebugMemoryRequest empty = new DebugMemoryRequest(
                DebugAddressSpace.HIGH_RAM, 0xffff, 0);
        assertEquals(0xffff, empty.endExclusive());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xffff, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0, 0x10001));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMemoryBlock(DebugAddressSpace.SYSTEM_BUS, 0xffff,
                        new byte[]{1, 2}));
    }

    @Test
    public void snapshotIsCoherentDetachedValueData() {
        DebugSnapshot snapshot = snapshot(true);
        assertEquals(7, snapshot.sessionGeneration());
        assertEquals(11, snapshot.sequence());
        assertEquals(1234, snapshot.masterTick());
        assertEquals(9, snapshot.frame());
        assertEquals(45, snapshot.framePosition());
        assertTrue(snapshot.paused());
        assertEquals(0x12a0, snapshot.registers().af());
        assertEquals(0x3456, snapshot.registers().bc());
        assertEquals(0x789a, snapshot.registers().de());
        assertEquals(0xbcde, snapshot.registers().hl());
        assertEquals(DebugCpuState.OPCODE_FETCH, snapshot.execution().cpuState());
        assertEquals("mbc3", snapshot.mapper().mapperId());
    }

    @Test
    public void scalarDtosRejectImpossibleOrAmbiguousValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new DebugRegisters(0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugInterruptState(true, false, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugTimerState(0, 0, 0, 0, false, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugPpuState(true, DebugPpuMode.DISABLED, 0, 0,
                        0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugApuState(false, -1, true, false, false, false,
                        0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMapperState("", 0, 0, DebugFeatureState.DISABLED,
                        DebugFeatureState.DISABLED, DebugFeatureState.DISABLED));
        DebugMapperState unknownBanks =
                new DebugMapperState("plain", -1, -1, DebugFeatureState.UNKNOWN,
                        DebugFeatureState.UNKNOWN, DebugFeatureState.UNKNOWN);
        assertEquals(-1, unknownBanks.romBank());
        assertEquals(-1, unknownBanks.ramBank());
        assertEquals(DebugFeatureState.UNKNOWN, unknownBanks.ramEnabled());
        assertThrows(NullPointerException.class,
                () -> new DebugMapperState("plain", 0, 0, null,
                        DebugFeatureState.DISABLED, DebugFeatureState.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugMapperState("plain", -2, 0, DebugFeatureState.DISABLED,
                        DebugFeatureState.DISABLED, DebugFeatureState.DISABLED));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugExecutionState(DebugCpuState.EXECUTING, 0x100, -1, 0,
                        false, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugSnapshot(-1, 0, 0, 0, 0, true,
                        registers(), interrupts(), timer(), ppu(), apu(), mapper(), execution()));
    }

    @Test
    public void capabilitiesNegotiateEveryStepKindAndMemoryBound() {
        DebugCapabilities capabilities = new DebugCapabilities(
                true, true, true, false, true, true, true, 4096);
        assertTrue(capabilities.supports(DebugStepKind.INSTRUCTION));
        assertFalse(capabilities.supports(DebugStepKind.MACHINE_CYCLE));
        assertTrue(capabilities.supports(DebugStepKind.FRAME));
        assertEquals(4096, capabilities.maxMemoryReadLength());
        assertEquals(DebugHistoryCapabilities.disabled(), capabilities.history());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugCapabilities(true, true, true, true, true,
                        false, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugCapabilities(true, true, true, true, true,
                        true, true, 0));
    }

    @Test
    public void historyConfigurationAndCapabilitiesEnforcePublicBounds() {
        DebugHistoryConfiguration disabled = DebugHistoryConfiguration.disabled();
        assertFalse(disabled.enabled());
        assertEquals(0, disabled.maxFrames());
        assertEquals(0, disabled.memoryBudgetBytes());

        DebugHistoryConfiguration defaults = DebugHistoryConfiguration.defaults();
        assertTrue(defaults.enabled());
        assertEquals(DebugHistoryConfiguration.DEFAULT_MAX_FRAMES, defaults.maxFrames());
        assertEquals(DebugHistoryConfiguration.DEFAULT_MEMORY_BUDGET_BYTES,
                defaults.memoryBudgetBytes());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryConfiguration(false, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryConfiguration(true, 0,
                        DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryConfiguration(true, 1,
                        DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES - 1));

        DebugHistoryCapabilities history = new DebugHistoryCapabilities(
                true, true, false, DebugHistoryConfiguration.MAX_FRAMES,
                DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES);
        DebugCapabilities capabilities = new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.noneOf(DebugBreakpointKind.class), 0,
                EnumSet.noneOf(TraceCategory.class), 0, 0, history);
        assertSame(history, capabilities.history());

        assertThrows(NullPointerException.class, () -> new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.noneOf(DebugBreakpointKind.class), 0,
                EnumSet.noneOf(TraceCategory.class), 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryCapabilities(false, true, false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryCapabilities(true, false, false, 1,
                        DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES + 1));
    }

    @Test
    public void historyStatusPinsBoundedMonotonicPointsAndReverseResult() {
        DebugHistoryConfiguration configuration = DebugHistoryConfiguration.defaults();
        DebugHistoryPoint first = new DebugHistoryPoint(1, 100, 2);
        DebugHistoryPoint second = new DebugHistoryPoint(2, 200, 3);
        DebugHistoryStatus status = new DebugHistoryStatus(
                configuration, 2, 4_096, 3, first, second,
                DebugHistoryTruncationReason.FRAME_BUDGET);

        assertSame(first, status.oldest());
        assertSame(second, status.newest());
        assertEquals(DebugHistoryTruncationReason.FRAME_BUDGET,
                status.lastTruncationReason());

        DebugReverseStepResult result = new DebugReverseStepResult(
                DebugStepKind.FRAME, second, snapshot(true), status);
        assertEquals(DebugStepKind.FRAME, result.kind());
        assertSame(second, result.restoredPoint());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryPoint(0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 0, 1, 0,
                        null, null, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 1, 1, 0,
                        first, second, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        second, first, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(DebugHistoryConfiguration.disabled(),
                        1, 1, 0, first, first, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(
                        new DebugHistoryConfiguration(true, 1,
                                DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES),
                        2, 1, 0, first, second, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(
                        new DebugHistoryConfiguration(true, 2,
                                DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES),
                        2, DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES + 1,
                        0, first, second, DebugHistoryTruncationReason.NONE));
        assertThrows(NullPointerException.class,
                () -> new DebugReverseStepResult(null, first, snapshot(true), status));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.MACHINE_CYCLE, first, snapshot(true), status));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.FRAME, first, snapshot(false), status));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.FRAME, first, snapshot(true), status));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.FRAME, first, snapshot(true),
                        new DebugHistoryStatus(
                                DebugHistoryConfiguration.disabled(), 0, 0, 0,
                                null, null, DebugHistoryTruncationReason.NONE)));
    }

    @Test
    public void capabilitiesNegotiateBoundedBreakpointsAndTrace() {
        EnumSet<DebugBreakpointKind> breakpointKinds = EnumSet.of(
                DebugBreakpointKind.PROGRAM_COUNTER, DebugBreakpointKind.MEMORY);
        EnumSet<TraceCategory> traceCategories = EnumSet.of(
                TraceCategory.CPU, TraceCategory.MEMORY);
        DebugCapabilities capabilities = new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                breakpointKinds, 32, traceCategories, 2048, 256);

        breakpointKinds.clear();
        traceCategories.clear();
        assertTrue(capabilities.breakpoints());
        assertTrue(capabilities.supports(DebugBreakpointKind.PROGRAM_COUNTER));
        assertFalse(capabilities.supports(DebugBreakpointKind.OPCODE));
        assertEquals(32, capabilities.maxBreakpoints());
        assertTrue(capabilities.trace());
        assertTrue(capabilities.supports(TraceCategory.MEMORY));
        assertFalse(capabilities.supports(TraceCategory.TIMER));
        assertEquals(2048, capabilities.maxTraceCapacity());
        assertEquals(256, capabilities.maxTraceReadEntries());

        assertThrows(IllegalArgumentException.class, () -> new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.noneOf(DebugBreakpointKind.class), 1,
                EnumSet.noneOf(TraceCategory.class), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.of(DebugBreakpointKind.PROGRAM_COUNTER), 1,
                EnumSet.of(TraceCategory.CPU), 0, 1));
    }

    @Test
    public void breakpointHitRequiresAPausedSnapshotAtOrAfterTheMatch() {
        DebugBreakpointId id = new DebugBreakpointId(5);
        DebugBreakpointHit hit = new DebugBreakpointHit(id, 1234, snapshot(true));
        assertSame(id, hit.breakpointId());
        assertThrows(IllegalArgumentException.class,
                () -> new DebugBreakpointHit(id, 1234, snapshot(false)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugBreakpointHit(id, 1235, snapshot(true)));
    }

    @Test
    public void stepResultPinsRequestedBoundaryAndPausedFinalSnapshot() {
        DebugSnapshot paused = snapshot(true);
        DebugStepResult instruction = new DebugStepResult(
                DebugStepKind.INSTRUCTION,
                DebugStepStopReason.INSTRUCTION_RETIRED,
                8,
                1,
                paused);
        assertSame(paused, instruction.snapshot());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugStepResult(DebugStepKind.FRAME,
                        DebugStepStopReason.INSTRUCTION_RETIRED, 8, 1, paused));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugStepResult(DebugStepKind.INSTRUCTION,
                        DebugStepStopReason.INSTRUCTION_RETIRED, 8, 1, snapshot(false)));
    }

    private static DebugSnapshot snapshot(boolean paused) {
        return new DebugSnapshot(
                7,
                11,
                1234,
                9,
                45,
                paused,
                registers(),
                interrupts(),
                timer(),
                ppu(),
                apu(),
                mapper(),
                execution());
    }

    private static DebugRegisters registers() {
        return new DebugRegisters(0x12, 0xa0, 0x34, 0x56, 0x78, 0x9a,
                0xbc, 0xde, 0xfffe, 0x100);
    }

    private static DebugInterruptState interrupts() {
        return new DebugInterruptState(true, false, 0xe1, 0x01, 0x01);
    }

    private static DebugTimerState timer() {
        return new DebugTimerState(0xabcd, 1, 2, 5, false, 0);
    }

    private static DebugPpuState ppu() {
        return new DebugPpuState(true, DebugPpuMode.PIXEL_TRANSFER, 42, 123,
                0x91, 0x83, 2, 3, 42, 4, 5);
    }

    private static DebugApuState apu() {
        return new DebugApuState(true, 4, true, false, true, false,
                0x77, 0xf3, 0x85);
    }

    private static DebugMapperState mapper() {
        return new DebugMapperState("mbc3", 5, 2, DebugFeatureState.ENABLED,
                DebugFeatureState.DISABLED, DebugFeatureState.DISABLED);
    }

    private static DebugExecutionState execution() {
        return new DebugExecutionState(DebugCpuState.OPCODE_FETCH, 0x3e, -1, 0,
                false, false, 77);
    }
}
