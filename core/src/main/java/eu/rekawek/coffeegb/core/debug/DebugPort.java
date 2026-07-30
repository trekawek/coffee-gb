package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous, session-bound debugger command port.
 *
 * <p>Implementations enqueue commands for execution at documented emulation-thread safe points.
 * Expected command failures are returned as {@link DebugResult} values.
 */
public interface DebugPort extends AutoCloseable {

    long sessionGeneration();

    DebugCapabilities capabilities();

    CompletionStage<DebugResult<DebugSnapshot>> pause();

    CompletionStage<DebugResult<DebugSnapshot>> resume();

    CompletionStage<DebugResult<DebugSnapshot>> snapshot();

    CompletionStage<DebugResult<DebugStepResult>> step(DebugStepKind kind);

    CompletionStage<DebugResult<DebugMemoryBlock>> readMemory(DebugMemoryRequest request);

    CompletionStage<DebugResult<Void>> setButton(DebugButton button, boolean pressed);

    /** Installs or replaces a breakpoint with the same caller-assigned id. */
    CompletionStage<DebugResult<DebugBreakpoint>> setBreakpoint(DebugBreakpoint breakpoint);

    CompletionStage<DebugResult<Void>> removeBreakpoint(DebugBreakpointId breakpointId);

    CompletionStage<DebugResult<DebugBreakpointList>> listBreakpoints();

    /** Returns the most recent automatic breakpoint stop in this session. */
    CompletionStage<DebugResult<DebugBreakpointHit>> lastBreakpointHit();

    /** Reconfigures and clears the bounded trace while preserving its sequence space. */
    CompletionStage<DebugResult<TraceConfiguration>> configureTrace(
            TraceConfiguration configuration);

    CompletionStage<DebugResult<TraceReadResult>> readTrace(TraceReadRequest request);

    boolean isClosed();

    @Override
    void close();
}
