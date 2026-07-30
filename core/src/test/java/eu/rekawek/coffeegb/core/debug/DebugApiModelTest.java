package eu.rekawek.coffeegb.core.debug;

import org.junit.Test;

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

        assertThrows(IllegalArgumentException.class,
                () -> new DebugCapabilities(true, true, true, true, true,
                        false, true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugCapabilities(true, true, true, true, true,
                        true, true, 0));
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
