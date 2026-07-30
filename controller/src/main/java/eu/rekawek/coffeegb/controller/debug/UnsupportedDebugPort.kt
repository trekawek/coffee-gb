package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugError
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

/** Session token for a topology that deliberately exposes no mutable debug owner. */
internal class UnsupportedDebugPort(
    private val generation: Long,
    private val topologyMessage: String,
) : DebugPort {
  private val terminalError = AtomicReference<DebugError?>()

  init {
    require(generation >= 0) { "Debug session generation must not be negative" }
    require(topologyMessage.isNotBlank()) { "Unsupported-topology message must not be blank" }
  }

  override fun sessionGeneration(): Long = generation

  override fun capabilities(): DebugCapabilities = NO_CAPABILITIES

  override fun pause(): CompletionStage<DebugResult<DebugSnapshot>> = unavailable()

  override fun resume(): CompletionStage<DebugResult<DebugSnapshot>> = unavailable()

  override fun snapshot(): CompletionStage<DebugResult<DebugSnapshot>> = unavailable()

  override fun step(kind: DebugStepKind?): CompletionStage<DebugResult<DebugStepResult>> =
      unavailable()

  override fun readMemory(
      request: DebugMemoryRequest?
  ): CompletionStage<DebugResult<DebugMemoryBlock>> = unavailable()

  override fun setButton(
      button: DebugButton?,
      pressed: Boolean,
  ): CompletionStage<DebugResult<Void>> = unavailable()

  override fun setBreakpoint(
      breakpoint: DebugBreakpoint?
  ): CompletionStage<DebugResult<DebugBreakpoint>> = unavailable()

  override fun removeBreakpoint(
      breakpointId: DebugBreakpointId?
  ): CompletionStage<DebugResult<Void>> = unavailable()

  override fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>> = unavailable()

  override fun lastBreakpointHit(): CompletionStage<DebugResult<DebugBreakpointHit>> = unavailable()

  override fun configureTrace(
      configuration: TraceConfiguration?
  ): CompletionStage<DebugResult<TraceConfiguration>> = unavailable()

  override fun readTrace(
      request: TraceReadRequest?
  ): CompletionStage<DebugResult<TraceReadResult>> = unavailable()

  override fun isClosed(): Boolean = terminalError.get() != null

  override fun close() {
    terminalError.compareAndSet(
        null,
        DebugError(DebugErrorCode.PORT_CLOSED, "The debug port is closed"),
    )
  }

  internal fun invalidateForSessionReplacement() {
    terminalError.compareAndSet(
        null,
        DebugError(DebugErrorCode.SESSION_REPLACED, "The debug session was replaced"),
    )
  }

  private fun <T> unavailable(): CompletionStage<DebugResult<T>> {
    val error =
        terminalError.get()
            ?: DebugError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, topologyMessage)
    return CompletableFuture.completedFuture(DebugResult.failure<T>(error)).minimalCompletionStage()
  }

  private companion object {
    val NO_CAPABILITIES = DebugCapabilities(false, false, false, false, false, false, false, 0)
  }
}
