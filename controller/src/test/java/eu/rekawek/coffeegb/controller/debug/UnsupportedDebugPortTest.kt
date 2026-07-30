package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugStepKind
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
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.pause())
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.snapshot())
    assertError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, port.step(DebugStepKind.INSTRUCTION))

    port.close()
    assertTrue(port.isClosed)
    assertError(DebugErrorCode.PORT_CLOSED, port.snapshot())
  }

  @Test
  fun replacementKeepsItsDistinctTerminalReason() {
    val port = UnsupportedDebugPort(11, "linked topology")
    port.invalidateForSessionReplacement()
    port.close()

    assertTrue(port.isClosed)
    assertError(DebugErrorCode.SESSION_REPLACED, port.snapshot())
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
