package eu.rekawek.coffeegb.core.debug;

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

    boolean isClosed();

    @Override
    void close();
}
