package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason;
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult;
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

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
    public void inspectionRequestsAreBoundedImmutableAndResolveTheirOwnSnapshotAnchors() {
        DebugAnchoredMemoryRequest code = new DebugAnchoredMemoryRequest(
                DebugInspectionAnchor.PROGRAM_COUNTER, 0, 3);
        DebugAnchoredMemoryRequest stack = new DebugAnchoredMemoryRequest(
                DebugInspectionAnchor.STACK_POINTER, -2, 2);
        DebugMemoryRequest memory = new DebugMemoryRequest(
                DebugAddressSpace.WORK_RAM, 0xc000, 4);
        List<DebugAnchoredMemoryRequest> anchors = new ArrayList<>(List.of(code, stack));
        List<DebugMemoryRequest> ranges = new ArrayList<>(List.of(memory));
        DebugInspectionRequest request = new DebugInspectionRequest(anchors, ranges);
        anchors.clear();
        ranges.clear();

        assertEquals(3, request.blockCount());
        assertEquals(9, request.totalBytes());
        assertEquals(new DebugMemoryRequest(DebugAddressSpace.ROM, 0x100, 3),
                code.resolve(snapshot(true)));
        assertEquals(new DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0xfffc, 2),
                stack.resolve(snapshot(true)));
        assertEquals(2, request.anchoredRequests().size());
        assertEquals(1, request.memoryRequests().size());
        assertThrows(UnsupportedOperationException.class,
                () -> request.memoryRequests().clear());

        assertThrows(IllegalArgumentException.class, () ->
                new DebugAnchoredMemoryRequest(DebugInspectionAnchor.PROGRAM_COUNTER, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new DebugAnchoredMemoryRequest(DebugInspectionAnchor.PROGRAM_COUNTER,
                        0x7eff, 2).resolve(snapshot(true)));
        assertThrows(IllegalArgumentException.class, () ->
                new DebugAnchoredMemoryRequest(DebugInspectionAnchor.STACK_POINTER,
                        2, 1).resolve(snapshot(true)));
        assertThrows(IllegalArgumentException.class, () ->
                new DebugInspectionRequest(List.of(), List.of(
                        new DebugMemoryRequest(DebugAddressSpace.ROM, 0, 4096),
                        new DebugMemoryRequest(DebugAddressSpace.ROM, 4096, 1))));
        assertThrows(IllegalArgumentException.class, () ->
                new DebugInspectionRequest(
                        java.util.Collections.nCopies(17, code), List.of()));
    }

    @Test
    public void inspectionResultPinsRequestOrderAndSnapshotIdentity() {
        DebugSnapshot snapshot = snapshot(true);
        DebugAnchoredMemoryRequest code = new DebugAnchoredMemoryRequest(
                DebugInspectionAnchor.PROGRAM_COUNTER, 0, 2);
        DebugMemoryRequest memory = new DebugMemoryRequest(
                DebugAddressSpace.SYSTEM_BUS, 0xc000, 1);
        DebugInspectionRequest request = new DebugInspectionRequest(
                List.of(code), List.of(memory));
        DebugMemoryBlock codeBlock = new DebugMemoryBlock(
                DebugAddressSpace.ROM, 0x100, new byte[]{0x3e, 0x12});
        DebugMemoryBlock memoryBlock = new DebugMemoryBlock(
                DebugAddressSpace.SYSTEM_BUS, 0xc000, new byte[]{0x34});
        List<DebugMemoryBlock> anchoredBlocks = new ArrayList<>(List.of(codeBlock));
        List<DebugMemoryBlock> memoryBlocks = new ArrayList<>(List.of(memoryBlock));

        DebugInspectionResult result = new DebugInspectionResult(
                snapshot, request, anchoredBlocks, memoryBlocks);
        anchoredBlocks.clear();
        memoryBlocks.clear();

        assertSame(snapshot, result.snapshot());
        assertSame(request, result.request());
        assertEquals(List.of(codeBlock), result.anchoredBlocks());
        assertEquals(List.of(memoryBlock), result.memoryBlocks());
        assertThrows(IllegalArgumentException.class, () -> new DebugInspectionResult(
                snapshot, request, List.of(), List.of(memoryBlock)));
        assertThrows(IllegalArgumentException.class, () -> new DebugInspectionResult(
                snapshot, request,
                List.of(new DebugMemoryBlock(
                        DebugAddressSpace.ROM, 0x101, new byte[]{0x3e, 0x12})),
                List.of(memoryBlock)));
    }

    @Test
    public void peripheralInspectionPayloadsAreTypedOwnedAndBounded() {
        byte[] source = new byte[DebugGraphicsInspection.VRAM_BANK_LENGTH];
        source[0] = (byte) 0x91;
        DebugByteData bank0 = new DebugByteData(source);
        source[0] = 0;
        assertEquals(0x91, bank0.unsignedByteAt(0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> bank0.byteAt(DebugGraphicsInspection.VRAM_BANK_LENGTH));

        DebugGraphicsInspection graphics = new DebugGraphicsInspection(
                DebugGraphicsHardwareMode.CGB_NATIVE, 1,
                0x91, 0xfc, 0xff, 0xff, 0x40, 0x40,
                bank0,
                new DebugByteData(new byte[DebugGraphicsInspection.VRAM_BANK_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.OAM_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.CGB_PALETTE_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.CGB_PALETTE_LENGTH]));
        assertEquals(DebugGraphicsHardwareMode.CGB_NATIVE, graphics.hardwareMode());
        assertEquals(1, graphics.selectedVramBank());
        assertSame(bank0, graphics.vramBank0());

        List<DebugAudioChannelInspection> channels = List.of(
                audioChannel(1), audioChannel(2), audioChannel(3), audioChannel(4));
        DebugAudioInspection audio = new DebugAudioInspection(
                true, 3, 0x77, 0xf3, 0xf0, channels,
                new DebugByteData(new byte[DebugAudioInspection.WAVE_RAM_LENGTH]));
        assertEquals(channels, audio.channels());
        assertThrows(UnsupportedOperationException.class, () -> audio.channels().clear());

        assertThrows(IllegalArgumentException.class, () -> new DebugGraphicsInspection(
                DebugGraphicsHardwareMode.DMG, 1,
                0, 0, 0, 0, -1, -1,
                bank0, new DebugByteData(new byte[0]),
                new DebugByteData(new byte[DebugGraphicsInspection.OAM_LENGTH]),
                new DebugByteData(new byte[0]), new DebugByteData(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> new DebugGraphicsInspection(
                DebugGraphicsHardwareMode.CGB_COMPATIBILITY, 1,
                0, 0, 0, 0, 0, 0,
                bank0,
                new DebugByteData(new byte[DebugGraphicsInspection.VRAM_BANK_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.OAM_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.CGB_PALETTE_LENGTH]),
                new DebugByteData(new byte[DebugGraphicsInspection.CGB_PALETTE_LENGTH])));
        assertThrows(IllegalArgumentException.class, () -> new DebugAudioInspection(
                true, 0, 0, 0, 0x80, channels, new DebugByteData(new byte[15])));
    }

    @Test
    public void audioChannelInspectionUsesHardwareSpecificRegisterBounds() {
        for (int channel : List.of(1, 2, 4)) {
            assertEquals(64, audioChannel(channel, 64, 0).lengthCounter());
            assertThrows(IllegalArgumentException.class,
                    () -> audioChannel(channel, 65, 0));
        }
        assertEquals(256, audioChannel(3, 256, 0).lengthCounter());
        assertThrows(IllegalArgumentException.class,
                () -> audioChannel(3, 257, 0));

        assertEquals(0x7f, audioChannel(1, 0, 0x7f).nr0());
        assertEquals(0x80, audioChannel(3, 0, 0x80).nr0());
        for (int channel : List.of(2, 4)) {
            assertThrows(IllegalArgumentException.class,
                    () -> audioChannel(channel, 0, 1));
        }
    }

    @Test
    public void inspectionOptionalsMatchRequestedSectionsAndTracePage() {
        EnumSet<DebugInspectionSection> sections =
                EnumSet.allOf(DebugInspectionSection.class);
        TraceReadRequest traceRequest = TraceReadRequest.initial(4);
        DebugInspectionRequest request = new DebugInspectionRequest(
                List.of(), List.of(), sections, Optional.of(traceRequest));
        sections.clear();
        assertEquals(EnumSet.allOf(DebugInspectionSection.class), request.sections());
        assertEquals(0, request.blockCount());
        assertEquals(0, request.totalBytes());
        DebugInspectionRequest fullMemoryPlusFixedPayloads = new DebugInspectionRequest(
                List.of(),
                List.of(new DebugMemoryRequest(DebugAddressSpace.ROM, 0,
                        DebugInspectionRequest.MAX_TOTAL_BYTES)),
                EnumSet.allOf(DebugInspectionSection.class), Optional.of(traceRequest));
        assertEquals(DebugInspectionRequest.MAX_TOTAL_BYTES,
                fullMemoryPlusFixedPayloads.totalBytes());

        DebugGraphicsInspection graphics = dmgGraphics();
        DebugAudioInspection audio = audioInspection();
        TraceReadResult trace = new TraceReadResult(List.of(), -1, 0, 0, 0, 0);
        DebugInspectionResult result = new DebugInspectionResult(
                snapshot(true), request, List.of(), List.of(),
                Optional.of(graphics), Optional.of(audio), Optional.of(trace));
        assertSame(graphics, result.graphics().orElseThrow());
        assertSame(audio, result.audio().orElseThrow());
        assertSame(trace, result.trace().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> new DebugInspectionResult(
                snapshot(true), request, List.of(), List.of(),
                Optional.empty(), Optional.of(audio), Optional.of(trace)));
    }

    @Test
    public void inspectionTracePageCannotReuseOrMoveBehindItsRequestCursor() {
        TraceReadRequest traceRequest = new TraceReadRequest(5, 4);
        DebugInspectionRequest request = new DebugInspectionRequest(
                List.of(), List.of(), EnumSet.noneOf(DebugInspectionSection.class),
                Optional.of(traceRequest));
        TraceEntry staleEntry = new TraceEntry(
                5, 10, TraceSource.CPU, new CpuInstructionTrace(0x100, 0, -1));
        TraceReadResult stalePage = new TraceReadResult(
                List.of(staleEntry), 5, 0, 0, 0, 6);
        assertThrows(IllegalArgumentException.class, () -> new DebugInspectionResult(
                snapshot(true), request, List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(stalePage)));

        TraceReadResult backwardsEmptyPage = new TraceReadResult(
                List.of(), 4, 0, 0, 0, 6);
        assertThrows(IllegalArgumentException.class, () -> new DebugInspectionResult(
                snapshot(true), request, List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(backwardsEmptyPage)));

        TraceReadResult stationaryEmptyPage = new TraceReadResult(
                List.of(), 5, 0, 0, 0, 6);
        DebugInspectionResult result = new DebugInspectionResult(
                snapshot(true), request, List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(stationaryEmptyPage));
        assertSame(stationaryEmptyPage, result.trace().orElseThrow());
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
        assertTrue(capabilities.coherentInspection());
        assertEquals(DebugInspectionRequest.MAX_BLOCKS, capabilities.maxInspectionBlocks());
        assertEquals(4096, capabilities.maxInspectionBytes());
        assertEquals(DebugHistoryCapabilities.disabled(), capabilities.history());
        assertTrue(capabilities.inspectionSections().isEmpty());
        assertFalse(capabilities.coherentTraceInspection());

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
        DebugHistoryPosition firstPosition = DebugHistoryPosition.atCheckpoint(first);
        DebugHistoryStatus status = new DebugHistoryStatus(
                configuration, 2, 4_096, 3, first, second, firstPosition, 1,
                DebugHistoryTruncationReason.FRAME_BUDGET);

        assertSame(first, status.oldest());
        assertSame(second, status.newest());
        assertSame(firstPosition, status.cursor());
        assertEquals(1, status.futureCheckpointCount());
        assertEquals(DebugHistoryTruncationReason.FRAME_BUDGET,
                status.lastTruncationReason());

        DebugReverseStepResult result = new DebugReverseStepResult(
                DebugStepKind.FRAME, firstPosition, first, snapshot(true), status);
        assertEquals(DebugStepKind.FRAME, result.kind());
        assertSame(firstPosition, result.restoredPosition());
        assertSame(first, result.replayAnchor());
        assertSame(first, result.restoredPoint());

        DebugHistoryPosition instructionPosition =
                new DebugHistoryPosition(150, 2, 50);
        DebugHistoryStatus instructionStatus = new DebugHistoryStatus(
                configuration, 2, 4_096, 3, first, second, instructionPosition, 1,
                DebugHistoryTruncationReason.NONE);
        DebugReverseStepResult instructionResult = new DebugReverseStepResult(
                DebugStepKind.INSTRUCTION, instructionPosition, first,
                snapshot(true), instructionStatus);
        assertSame(instructionPosition, instructionResult.restoredPosition());
        assertSame(first, instructionResult.replayAnchor());

        // An instruction replay can reach the same public coordinates as a retained checkpoint
        // whose capture-time RTC state belongs to the old branch. The cursor has no checkpoint
        // identity, so a positive future count is the discriminator in this logical-equality case.
        DebugHistoryStatus equalCoordinateFuture = new DebugHistoryStatus(
                configuration, 2, 4_096, 3, first, second,
                DebugHistoryPosition.atCheckpoint(second), 1,
                DebugHistoryTruncationReason.NONE);
        assertEquals(1, equalCoordinateFuture.futureCheckpointCount());

        DebugHistoryStatus legacyStatus = new DebugHistoryStatus(
                configuration, 2, 4_096, 3, first, second,
                DebugHistoryTruncationReason.FRAME_BUDGET);
        assertEquals(DebugHistoryPosition.atCheckpoint(second), legacyStatus.cursor());
        assertEquals(0, legacyStatus.futureCheckpointCount());
        DebugReverseStepResult legacyResult = new DebugReverseStepResult(
                DebugStepKind.FRAME, second, snapshot(true), legacyStatus);
        assertSame(second, legacyResult.restoredPoint());

        DebugHistoryPosition unanchoredCursor = new DebugHistoryPosition(25, 0, 25);
        DebugHistoryStatus unanchored = new DebugHistoryStatus(
                configuration, 0, 0, 0, null, null, unanchoredCursor, 0,
                DebugHistoryTruncationReason.NONE);
        assertSame(unanchoredCursor, unanchored.cursor());

        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryPoint(0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryPosition(-1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryPosition(0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryPosition(0, 0, -1));
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
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        first, second, null, 1, DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        first, second, firstPosition, -1,
                        DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        first, second, firstPosition, 2,
                        DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        first, second, firstPosition, 0,
                        DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(configuration, 2, 1, 0,
                        first, second, new DebugHistoryPosition(50, 1, 0), 1,
                        DebugHistoryTruncationReason.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugHistoryStatus(DebugHistoryConfiguration.disabled(),
                        0, 0, 0, null, null, unanchoredCursor, 0,
                        DebugHistoryTruncationReason.NONE));
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
                        DebugStepKind.FRAME, second, snapshot(true), status));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.FRAME, new DebugHistoryPosition(100, 2, 1), first,
                        snapshot(true),
                        new DebugHistoryStatus(
                                configuration, 2, 1, 0, first, second,
                                new DebugHistoryPosition(100, 2, 1), 1,
                                DebugHistoryTruncationReason.NONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugReverseStepResult(
                        DebugStepKind.INSTRUCTION, firstPosition,
                        new DebugHistoryPoint(3, 250, 4), snapshot(true), status));
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
    public void capabilitiesNegotiatePeripheralAndCoherentTraceInspection() {
        EnumSet<DebugInspectionSection> sections =
                EnumSet.allOf(DebugInspectionSection.class);
        DebugCapabilities capabilities = new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.noneOf(DebugBreakpointKind.class), 0,
                EnumSet.of(TraceCategory.CPU), 16, 8,
                DebugHistoryCapabilities.disabled(), sections, 4);
        sections.clear();

        assertTrue(capabilities.supportsInspection(DebugInspectionSection.GRAPHICS));
        assertTrue(capabilities.supportsInspection(DebugInspectionSection.AUDIO));
        assertTrue(capabilities.coherentTraceInspection());
        assertEquals(4, capabilities.maxInspectionTraceEntries());
        assertThrows(IllegalArgumentException.class, () -> new DebugCapabilities(
                true, true, true, false, true, true, true, 4096,
                EnumSet.noneOf(DebugBreakpointKind.class), 0,
                EnumSet.of(TraceCategory.CPU), 16, 8,
                DebugHistoryCapabilities.disabled(),
                EnumSet.of(DebugInspectionSection.GRAPHICS), 9));
    }

    @Test
    public void breakpointHitPinsItsDefinitionAndSeparatesHistoricalOwnership() {
        DebugBreakpointId id = new DebugBreakpointId(5);
        DebugBreakpoint definition =
                new DebugBreakpoint(id, true, DebugPcCondition.at(0x100));
        DebugBreakpointHit hit =
                new DebugBreakpointHit(definition, 1234, snapshot(true), true);
        assertSame(id, hit.breakpointId());
        assertSame(definition, hit.breakpoint().orElseThrow());
        assertTrue(hit.activePause());

        DebugBreakpointHit historical = hit.withActivePause(false);
        assertFalse(historical.activePause());
        assertSame(definition, historical.breakpoint().orElseThrow());
        assertSame(hit.snapshot(), historical.snapshot());

        DebugBreakpointHit legacy = new DebugBreakpointHit(id, 1234, snapshot(true));
        assertTrue(legacy.breakpoint().isEmpty());
        assertTrue(legacy.activePause());
        assertThrows(IllegalArgumentException.class,
                () -> new DebugBreakpointHit(id, 1234, snapshot(false)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugBreakpointHit(id, 1235, snapshot(true)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugBreakpointHit(
                        id,
                        1234,
                        snapshot(true),
                        Optional.of(new DebugBreakpoint(
                                new DebugBreakpointId(6), true, DebugPcCondition.at(0x100))),
                        true));
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

    private static DebugGraphicsInspection dmgGraphics() {
        return new DebugGraphicsInspection(
                DebugGraphicsHardwareMode.DMG, 0,
                0x91, 0xfc, 0xff, 0xff, -1, -1,
                new DebugByteData(new byte[DebugGraphicsInspection.VRAM_BANK_LENGTH]),
                new DebugByteData(new byte[0]),
                new DebugByteData(new byte[DebugGraphicsInspection.OAM_LENGTH]),
                new DebugByteData(new byte[0]),
                new DebugByteData(new byte[0]));
    }

    private static DebugAudioInspection audioInspection() {
        return new DebugAudioInspection(
                true, 0, 0x77, 0, 0xf0,
                List.of(audioChannel(1), audioChannel(2),
                        audioChannel(3), audioChannel(4)),
                new DebugByteData(new byte[DebugAudioInspection.WAVE_RAM_LENGTH]));
    }

    private static DebugAudioChannelInspection audioChannel(int channel) {
        return audioChannel(channel, 0, 0);
    }

    private static DebugAudioChannelInspection audioChannel(
            int channel, int lengthCounter, int nr0) {
        return new DebugAudioChannelInspection(
                channel, false, false, 0, lengthCounter, false,
                nr0, 0, 0, 0, 0);
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
