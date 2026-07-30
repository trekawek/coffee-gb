package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import java.util.EnumSet
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class UnsupportedDebugPortTest {

  @Test
  fun linkedTopologyRejectsEveryOperationWithoutOwningAResultThread() {
    val port = UnsupportedDebugPort(7, "linked topology")

    assertFalse(port.capabilities().pauseResume())
    assertFalse(port.capabilities().snapshot())
    assertFalse(port.capabilities().memoryRead())
    assertEquals(0, port.capabilities().maxMemoryReadLength())
    assertFalse(port.capabilities().breakpoints())
    assertTrue(port.capabilities().breakpointKinds().isEmpty())
    assertEquals(0, port.capabilities().maxBreakpoints())
    assertFalse(port.capabilities().trace())
    assertTrue(port.capabilities().traceCategories().isEmpty())
    assertEquals(0, port.capabilities().maxTraceCapacity())
    assertEquals(0, port.capabilities().maxTraceReadEntries())
    assertTrue(port.capabilities().inspectionSections().isEmpty())
    assertEquals(0, port.capabilities().maxInspectionTraceEntries())
    assertFalse(port.capabilities().coherentTraceInspection())
    assertFalse(port.capabilities().history().checkpointHistory())
    assertFalse(port.capabilities().history().reverseFrame())
    assertFalse(port.capabilities().history().reverseInstruction())
    assertEquals(0, port.capabilities().history().maxFrames())
    assertEquals(0, port.capabilities().history().maxMemoryBudgetBytes())
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.pause())
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.snapshot())
    assertError(
        DebugErrorCode.UNSUPPORTED_TOPOLOGY,
        port.inspect(DebugInspectionRequest(emptyList(), emptyList())),
    )
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.step(DebugStepKind.INSTRUCTION))
    assertError(
        DebugErrorCode.UNSUPPORTED_TOPOLOGY,
        port.configureHistory(DebugHistoryConfiguration.defaults()),
    )
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.historyStatus())
    assertError(
        DebugErrorCode.UNSUPPORTED_TOPOLOGY,
        port.stepBackward(DebugStepKind.FRAME),
    )
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(1), true, DebugPcCondition.at(0x100))
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.setBreakpoint(breakpoint))
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.removeBreakpoint(breakpoint.id()))
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.listBreakpoints())
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.lastBreakpointHit())
    assertError(
        DebugErrorCode.UNSUPPORTED_TOPOLOGY,
        port.configureTrace(TraceConfiguration(8, EnumSet.of(TraceCategory.CPU))),
    )
    assertError(
        DebugErrorCode.UNSUPPORTED_TOPOLOGY,
        port.readTrace(TraceReadRequest.initial(4)),
    )

    port.close()
    assertTrue(port.isClosed)
    assertError(DebugErrorCode.PORT_CLOSED, port.historyStatus())
    assertError(
        DebugErrorCode.PORT_CLOSED,
        port.configureHistory(DebugHistoryConfiguration.disabled()),
    )
    assertError(DebugErrorCode.PORT_CLOSED, port.stepBackward(DebugStepKind.FRAME))
    assertError(DebugErrorCode.PORT_CLOSED, port.readTrace(TraceReadRequest.initial(1)))
  }

  @Test
  fun replacementKeepsItsDistinctTerminalReason() {
    val port = UnsupportedDebugPort(11, "linked topology")
    port.invalidateForSessionReplacement()
    port.close()

    assertTrue(port.isClosed)
    assertError(DebugErrorCode.SESSION_REPLACED, port.snapshot())
    assertError(DebugErrorCode.SESSION_REPLACED, port.historyStatus())
    assertError(DebugErrorCode.SESSION_REPLACED, port.stepBackward(DebugStepKind.FRAME))
  }

  private fun <T> assertError(
      expected: DebugErrorCode,
      stage: CompletionStage<DebugResult<T>>,
  ) {
    val result = stage.toCompletableFuture().get(1, TimeUnit.SECONDS)
    assertTrue(result.isFailure)
    assertEquals(expected, result.error().code())
  }
}
