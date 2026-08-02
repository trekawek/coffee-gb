package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus;
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleTest {

    @Test
    public void helpListsSupportedCommandsAndOmitsAbandonedApuMutation() {
        Harness harness = new Harness(100);

        harness.console.executeLine("help");

        String output = harness.output();
        assertTrue(output.contains("pause"));
        assertTrue(output.contains("resume"));
        assertTrue(output.contains("step"));
        assertTrue(output.contains("show state"));
        assertTrue(output.contains("memory read ADDRESS-SPACE ADDRESS LENGTH"));
        assertTrue(output.contains("cpu show opcode"));
        assertFalse(output.contains("apu chan"));
    }

    @Test
    public void executionCommandsUseOnlyTheAttachedPortAndRenderImmutableResults() {
        Harness harness = new Harness(100);
        FakeDebugPort port = new FakeDebugPort();
        harness.console.setDebugPort(port);

        harness.console.executeLine("pause");
        harness.console.executeLine("step");
        harness.console.executeLine("step frame");
        harness.console.executeLine("resume");

        assertEquals(1, port.pauseCalls);
        assertEquals(1, port.resumeCalls);
        assertEquals(2, port.stepCalls);
        assertEquals(DebugStepKind.FRAME, port.lastStepKind);
        String output = harness.output();
        assertTrue(output.contains("Paused at tick 42, PC=4567"));
        assertTrue(output.contains("Step INSTRUCTION stopped for INSTRUCTION_RETIRED"));
        assertTrue(output.contains("Step FRAME stopped for FRAME_BOUNDARY"));
        assertTrue(output.contains("Resumed at tick 42, PC=4567, paused=false"));
        assertEquals("", harness.error());
    }

    @Test
    public void stateCommandUsesOneCoherentSnapshotForFormerAgentInspectionValues() {
        Harness harness = new Harness(100);
        FakeDebugPort port = new FakeDebugPort();
        harness.console.setDebugPort(port);

        harness.console.executeLine("state");

        assertEquals(1, port.snapshotCalls);
        String output = harness.output();
        assertTrue(output.contains("session=7 sequence=9 tick=42 frame=3+11 paused=true"));
        assertTrue(output.contains("AF=1230 BC=4567 DE=89AB HL=CDEF SP=FFFE PC=4567"));
        assertTrue(output.contains("CPU=EXECUTING opcode=3E extended=-- cycle=2"));
        assertTrue(output.contains("IME=true pendingEnable=false IF=05 IE=1F pending=05"));
        assertTrue(output.contains("PPU=PIXEL_TRANSFER LCD=true LY=22 dot=105 LCDC=91"));
        assertTrue(output.contains("APU=true sequencer=4 channels=1-3-"));
        assertTrue(output.contains("MAPPER=MBC1 ROM=2 RAM=1"));
    }

    @Test
    public void memoryCommandParsesNamedViewAndRefusesReadsBeyondCapability() {
        Harness harness = new Harness(100);
        FakeDebugPort port = new FakeDebugPort();
        harness.console.setDebugPort(port);

        harness.console.executeLine("mem ROM 0x0100 4");

        assertEquals(new DebugMemoryRequest(DebugAddressSpace.ROM, 0x100, 4), port.lastMemoryRequest);
        assertEquals(1, port.memoryCalls);
        assertTrue(harness.output()
                .contains("ROM 0100-0103 (4 bytes)" + System.lineSeparator() + "0100: 3E 12 3C 00"));

        harness.console.executeLine("memory read ROM 0x0200 5");

        assertEquals(1, port.memoryCalls);
        assertTrue(
                harness.error().contains(
                        "INVALID_ARGUMENT: Requested 5 bytes, but this session advertises a maximum of 4."));
    }

    @Test
    public void detachedClosedAndReplacedSessionsHaveExplicitErrors() {
        Harness harness = new Harness(100);

        harness.console.executeLine("state");
        assertTrue(harness.error().contains("NO_ACTIVE_SESSION"));

        FakeDebugPort closed = new FakeDebugPort();
        closed.closed = true;
        harness.console.setDebugPort(closed);
        harness.console.executeLine("state");
        assertTrue(harness.error().contains("PORT_CLOSED"));
        assertEquals(0, closed.snapshotCalls);

        FakeDebugPort replaced = new FakeDebugPort();
        replaced.snapshotResult = completedFailure(
                DebugErrorCode.SESSION_REPLACED, "The command belongs to an earlier session");
        harness.console.setDebugPort(replaced);
        harness.console.executeLine("state");
        assertTrue(
                harness.error().contains(
                        "SESSION_REPLACED: The command belongs to an earlier session"));
    }

    @Test
    public void boundedWaitReportsIndeterminateTimeoutWithoutCancellingOwnerCommand() {
        Harness harness = new Harness(20);
        FakeDebugPort port = new FakeDebugPort();
        CompletableFuture<DebugResult<DebugSnapshot>> pending = new CompletableFuture<>();
        port.snapshotResult = pending;
        harness.console.setDebugPort(port);

        long started = System.nanoTime();
        harness.console.executeLine("state");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue("Console wait took " + elapsedMillis + " ms", elapsedMillis < 1_000);
        assertTrue(harness.error().contains("CONSOLE_TIMEOUT: No result after 20 ms"));
        assertTrue(harness.error().contains("may still complete on its session"));
        assertFalse(pending.isCancelled());
    }

    @Test
    public void staticOpcodeLookupUsesInjectedConsoleOutputWithoutAMachine() {
        Harness harness = new Harness(100);

        harness.console.executeLine("cpu show opcode 00");

        assertTrue(harness.output().contains("0x00   NOP"));
        assertEquals("", harness.error());
    }

    private static <T> CompletionStage<DebugResult<T>> completedFailure(
            DebugErrorCode code, String message) {
        return CompletableFuture.completedFuture(DebugResult.failure(code, message));
    }

    private static DebugSnapshot snapshot(boolean paused) {
        return new DebugSnapshot(
                7,
                9,
                42,
                3,
                11,
                paused,
                new DebugRegisters(0x12, 0x30, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0xfffe, 0x4567),
                new DebugInterruptState(true, false, 0x05, 0x1f, 0x05),
                new DebugTimerState(0x1234, 0x56, 0x78, 0x05, true, 2),
                new DebugPpuState(
                        true,
                        DebugPpuMode.PIXEL_TRANSFER,
                        22,
                        105,
                        0x91,
                        0x83,
                        1,
                        2,
                        3,
                        4,
                        5),
                new DebugApuState(true, 4, true, false, true, false, 0x77, 0xf3, 0x85),
                new DebugMapperState(
                        "MBC1",
                        2,
                        1,
                        DebugFeatureState.ENABLED,
                        DebugFeatureState.DISABLED,
                        DebugFeatureState.UNKNOWN),
                new DebugExecutionState(
                        DebugCpuState.EXECUTING, 0x3e, -1, 2, false, false, 123));
    }

    private static final class Harness {

        private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();

        private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        private final Console console;

        private Harness(long timeoutMillis) {
            console = new Console(
                    new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
                    new PrintStream(errorBytes, true, StandardCharsets.UTF_8),
                    timeoutMillis);
        }

        private String output() {
            return outputBytes.toString(StandardCharsets.UTF_8);
        }

        private String error() {
            return errorBytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeDebugPort implements DebugPort {

        private final DebugCapabilities capabilities = new DebugCapabilities(
                true, true, true, false, true, true, true, 4);

        private boolean closed;

        private int pauseCalls;

        private int resumeCalls;

        private int snapshotCalls;

        private int stepCalls;

        private int memoryCalls;

        private DebugStepKind lastStepKind;

        private DebugMemoryRequest lastMemoryRequest;

        private CompletionStage<DebugResult<DebugSnapshot>> snapshotResult =
                CompletableFuture.completedFuture(DebugResult.success(ConsoleTest.snapshot(true)));

        @Override
        public long sessionGeneration() {
            return 7;
        }

        @Override
        public DebugCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public CompletionStage<DebugResult<DebugSnapshot>> pause() {
            pauseCalls++;
            return CompletableFuture.completedFuture(
                    DebugResult.success(ConsoleTest.snapshot(true)));
        }

        @Override
        public CompletionStage<DebugResult<DebugSnapshot>> resume() {
            resumeCalls++;
            return CompletableFuture.completedFuture(
                    DebugResult.success(ConsoleTest.snapshot(false)));
        }

        @Override
        public CompletionStage<DebugResult<DebugSnapshot>> snapshot() {
            snapshotCalls++;
            return snapshotResult;
        }

        @Override
        public CompletionStage<DebugResult<DebugInspectionResult>> inspect(
                DebugInspectionRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugStepResult>> step(DebugStepKind kind) {
            stepCalls++;
            lastStepKind = kind;
            DebugStepStopReason reason = kind == DebugStepKind.FRAME
                    ? DebugStepStopReason.FRAME_BOUNDARY
                    : DebugStepStopReason.INSTRUCTION_RETIRED;
            return CompletableFuture.completedFuture(
                    DebugResult.success(new DebugStepResult(
                            kind, reason, 4, 1, ConsoleTest.snapshot(true))));
        }

        @Override
        public CompletionStage<DebugResult<DebugHistoryStatus>> configureHistory(
                DebugHistoryConfiguration configuration) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugHistoryStatus>> historyStatus() {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugReverseStepResult>> stepBackward(
                DebugStepKind kind) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugMemoryBlock>> readMemory(
                DebugMemoryRequest request) {
            memoryCalls++;
            lastMemoryRequest = request;
            return CompletableFuture.completedFuture(
                    DebugResult.success(new DebugMemoryBlock(
                            request.addressSpace(),
                            request.address(),
                            new byte[]{0x3e, 0x12, 0x3c, 0x00})));
        }

        @Override
        public CompletionStage<DebugResult<Void>> setButton(DebugButton button, boolean pressed) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugBreakpoint>> setBreakpoint(
                DebugBreakpoint breakpoint) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<Void>> removeBreakpoint(
                DebugBreakpointId breakpointId) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugBreakpointList>> listBreakpoints() {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<DebugBreakpointHit>> lastBreakpointHit() {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<TraceConfiguration>> configureTrace(
                TraceConfiguration configuration) {
            return unsupported();
        }

        @Override
        public CompletionStage<DebugResult<TraceReadResult>> readTrace(TraceReadRequest request) {
            return unsupported();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        private static <T> CompletionStage<DebugResult<T>> unsupported() {
            return completedFailure(
                    DebugErrorCode.UNSUPPORTED_TOPOLOGY, "Not implemented by the test port");
        }
    }
}
