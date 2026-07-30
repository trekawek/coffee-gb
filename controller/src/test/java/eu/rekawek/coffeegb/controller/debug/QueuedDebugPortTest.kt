package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import java.util.Collections
import java.util.EnumSet
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class QueuedDebugPortTest {
  @Test
  fun `inspection validates negotiated bounds and preserves its typed FIFO envelope`() {
    val request =
        DebugInspectionRequest(
            listOf(
                DebugAnchoredMemoryRequest(DebugInspectionAnchor.PROGRAM_COUNTER, 0, 3),
                DebugAnchoredMemoryRequest(DebugInspectionAnchor.STACK_POINTER, -2, 2),
            ),
            listOf(DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xc000, 1)),
        )
    val unavailable =
        QueuedDebugPort(
            1,
            DebugCapabilities(true, true, true, true, true, false, true, 0),
            1,
        )
    val bounded = QueuedDebugPort(2, capabilities(maxMemoryReadLength = 4), 1)
    val port = QueuedDebugPort(17, capabilities(), 1)
    try {
      assertFailure(port.inspect(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          unavailable.inspect(DebugInspectionRequest(emptyList(), emptyList())),
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
      )
      assertFailure(bounded.inspect(request), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.inspect(
              DebugInspectionRequest(
                  emptyList(),
                  emptyList(),
                  EnumSet.of(DebugInspectionSection.GRAPHICS),
              )),
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
      )
      assertFailure(
          port.inspect(
              DebugInspectionRequest(
                  emptyList(),
                  emptyList(),
                  emptySet(),
                  Optional.of(TraceReadRequest.initial(1)),
              )),
          DebugErrorCode.TRACE_LIMIT,
      )
      assertEquals(0, port.outstandingRequestCount())

      val stage = port.inspect(request)
      val command = assertIs<QueuedDebugCommand.Inspect>(port.pollCommand())
      assertEquals(1, command.requestId)
      assertEquals(17, command.sessionGeneration)
      assertEquals(request, command.request)

      val snapshot = snapshot(masterTick = 42, frame = 3)
      val result =
          DebugInspectionResult(
              snapshot,
              request,
              listOf(
                  DebugMemoryBlock(DebugAddressSpace.ROM, 0x100, byteArrayOf(0x3e, 0x12, 0)),
                  DebugMemoryBlock(DebugAddressSpace.SYSTEM_BUS, 0xfffc, byteArrayOf(1, 2)),
              ),
              listOf(DebugMemoryBlock(DebugAddressSpace.WORK_RAM, 0xc000, byteArrayOf(3))),
          )
      assertTrue(command.complete(DebugResult.success(result)))
      assertEquals(result, await(stage).value())
    } finally {
      unavailable.close()
      bounded.close()
      port.close()
      assertTrue(unavailable.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
      assertTrue(bounded.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `history commands validate before admission and preserve FIFO typed completion`() {
    val port = QueuedDebugPort(17, advancedCapabilities(), 3)
    val configuration =
        DebugHistoryConfiguration(
            true,
            120,
            DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
        )
    try {
      assertFailure(port.configureHistory(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.configureHistory(
              DebugHistoryConfiguration(
                  true,
                  121,
                  DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
              )),
          DebugErrorCode.HISTORY_LIMIT,
      )
      assertFailure(port.stepBackward(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.stepBackward(DebugStepKind.INSTRUCTION),
          DebugErrorCode.UNSUPPORTED_STEP,
      )
      assertFailure(
          port.stepBackward(DebugStepKind.MACHINE_CYCLE),
          DebugErrorCode.UNSUPPORTED_STEP,
      )
      assertEquals(0, port.outstandingRequestCount())
      assertFalse(port.hasPendingCommands())

      val configure = port.configureHistory(configuration)
      val statusRequest = port.historyStatus()
      val backward = port.stepBackward(DebugStepKind.FRAME)

      val commands = port.drainCommands(3)
      assertEquals(listOf(1L, 2L, 3L), commands.map { it.requestId })
      assertTrue(commands.all { it.sessionGeneration == 17L })
      assertEquals(
          configuration,
          assertIs<QueuedDebugCommand.ConfigureHistory>(commands[0]).configuration,
      )
      assertIs<QueuedDebugCommand.HistoryStatus>(commands[1])
      assertEquals(
          DebugStepKind.FRAME,
          assertIs<QueuedDebugCommand.StepBackward>(commands[2]).kind,
      )

      val point = DebugHistoryPoint(1, 100, 2)
      val status =
          DebugHistoryStatus(
              configuration,
              1,
              4_096,
              0,
              point,
              point,
              DebugHistoryTruncationReason.NONE,
          )
      val reverse =
          DebugReverseStepResult(
              DebugStepKind.FRAME,
              point,
              snapshot(masterTick = point.masterTick(), frame = point.frame()),
              status,
          )
      assertTrue(
          assertIs<QueuedDebugCommand.ConfigureHistory>(commands[0])
              .complete(DebugResult.success(status)))
      assertTrue(
          assertIs<QueuedDebugCommand.HistoryStatus>(commands[1])
              .complete(DebugResult.success(status)))
      assertTrue(
          assertIs<QueuedDebugCommand.StepBackward>(commands[2])
              .complete(DebugResult.success(reverse)))

      assertEquals(status, await(configure).value())
      assertEquals(status, await(statusRequest).value())
      assertEquals(reverse, await(backward).value())
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `advanced commands validate before admission and preserve their FIFO envelopes`() {
    val port = QueuedDebugPort(16, advancedCapabilities(), 6)
    val breakpoint =
        DebugBreakpoint(DebugBreakpointId(41), true, DebugPcCondition.at(0x1234))
    val unsupportedBreakpoint =
        DebugBreakpoint(DebugBreakpointId(42), true, DebugOpcodeCondition.base(0x00))
    val traceConfiguration =
        TraceConfiguration(16, EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY))
    try {
      assertFailure(port.setBreakpoint(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.setBreakpoint(unsupportedBreakpoint),
          DebugErrorCode.UNSUPPORTED_BREAKPOINT,
      )
      assertFailure(port.removeBreakpoint(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(port.configureTrace(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.configureTrace(TraceConfiguration(17, EnumSet.of(TraceCategory.CPU))),
          DebugErrorCode.TRACE_LIMIT,
      )
      assertFailure(
          port.configureTrace(TraceConfiguration(8, EnumSet.of(TraceCategory.PPU))),
          DebugErrorCode.UNSUPPORTED_TRACE_CATEGORY,
      )
      assertFailure(port.readTrace(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(port.readTrace(TraceReadRequest.initial(5)), DebugErrorCode.TRACE_LIMIT)
      assertEquals(0, port.outstandingRequestCount())
      assertFalse(port.hasPendingCommands())

      val set = port.setBreakpoint(breakpoint)
      val remove = port.removeBreakpoint(breakpoint.id())
      val list = port.listBreakpoints()
      val lastHit = port.lastBreakpointHit()
      val configure = port.configureTrace(traceConfiguration)
      val readRequest = TraceReadRequest.initial(4)
      val read = port.readTrace(readRequest)

      val commands = port.drainCommands(6)
      assertEquals((1L..6L).toList(), commands.map { it.requestId })
      assertTrue(commands.all { it.sessionGeneration == 16L })
      assertEquals(breakpoint, assertIs<QueuedDebugCommand.SetBreakpoint>(commands[0]).breakpoint)
      assertEquals(
          breakpoint.id(),
          assertIs<QueuedDebugCommand.RemoveBreakpoint>(commands[1]).breakpointId,
      )
      assertIs<QueuedDebugCommand.ListBreakpoints>(commands[2])
      assertIs<QueuedDebugCommand.LastBreakpointHit>(commands[3])
      assertEquals(
          traceConfiguration,
          assertIs<QueuedDebugCommand.ConfigureTrace>(commands[4]).configuration,
      )
      assertEquals(readRequest, assertIs<QueuedDebugCommand.ReadTrace>(commands[5]).request)

      assertTrue(
          assertIs<QueuedDebugCommand.SetBreakpoint>(commands[0])
              .complete(DebugResult.success(breakpoint)))
      assertTrue(
          assertIs<QueuedDebugCommand.RemoveBreakpoint>(commands[1])
              .complete(DebugResult.success()))
      assertTrue(
          assertIs<QueuedDebugCommand.ListBreakpoints>(commands[2])
              .complete(DebugResult.success(DebugBreakpointList(listOf(breakpoint)))))
      assertTrue(
          assertIs<QueuedDebugCommand.LastBreakpointHit>(commands[3])
              .fail(DebugErrorCode.NO_BREAKPOINT_HIT, "Test completion"))
      assertTrue(
          assertIs<QueuedDebugCommand.ConfigureTrace>(commands[4])
              .complete(DebugResult.success(traceConfiguration)))
      val emptyTrace = TraceReadResult(emptyList(), -1, 0, 0, 0, 0)
      assertTrue(
          assertIs<QueuedDebugCommand.ReadTrace>(commands[5])
              .complete(DebugResult.success(emptyTrace)))

      assertEquals(breakpoint, await(set).value())
      assertTrue(await(remove).isSuccess)
      assertEquals(listOf(breakpoint), await(list).value().breakpoints())
      assertFailure(lastHit, DebugErrorCode.NO_BREAKPOINT_HIT)
      assertEquals(traceConfiguration, await(configure).value())
      assertEquals(emptyTrace, await(read).value())
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `default capacity is exactly sixty four outstanding requests`() {
    val port = QueuedDebugPort(6, capabilities())
    try {
      val admitted = List(64) { port.snapshot() }
      assertFailure(port.snapshot(), DebugErrorCode.QUEUE_FULL)
      assertEquals(64, port.drainCommands(65).size)

      port.close()
      admitted.forEach { assertFailure(it, DebugErrorCode.PORT_CLOSED) }
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    } finally {
      port.close()
    }
  }

  @Test
  fun `capacity covers queued polled and undelivered requests while preserving FIFO`() {
    val port = QueuedDebugPort(7, capabilities(), maxOutstandingRequests = 2)
    try {
      val first = port.setButton(DebugButton.A, true)
      val second = port.setButton(DebugButton.B, false)

      val firstCommand = assertIs<QueuedDebugCommand.SetButton>(port.pollCommand())
      assertEquals(1, firstCommand.requestId)
      assertEquals(DebugButton.A, firstCommand.button)
      assertTrue(firstCommand.pressed)

      // Polling transfers ownership; it does not release admission capacity.
      assertFailure(port.snapshot(), DebugErrorCode.QUEUE_FULL)

      val secondCommand = assertIs<QueuedDebugCommand.SetButton>(port.pollCommand())
      assertEquals(2, secondCommand.requestId)
      assertEquals(DebugButton.B, secondCommand.button)
      assertFalse(secondCommand.pressed)
      assertFalse(port.hasPendingCommands())

      assertTrue(firstCommand.complete(DebugResult.success()))
      assertTrue(await(first).isSuccess)
      awaitCondition { port.outstandingRequestCount() == 1 }

      val third = port.snapshot()
      val thirdCommand = assertIs<QueuedDebugCommand.Snapshot>(port.pollCommand())
      assertEquals(3, thirdCommand.requestId)

      assertTrue(secondCommand.complete(DebugResult.success()))
      assertTrue(await(second).isSuccess)
      thirdCommand.fail(DebugErrorCode.SESSION_BUSY, "Test completion")
      assertFailure(third, DebugErrorCode.SESSION_BUSY)
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `cheap validation rejects invalid or unsupported arguments without consuming capacity`() {
    val port = QueuedDebugPort(8, capabilities(maxMemoryReadLength = 4), 1)
    try {
      assertFailure(port.step(null), DebugErrorCode.INVALID_ARGUMENT)
      assertFailure(
          port.readMemory(DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0x100, 5)),
          DebugErrorCode.INVALID_ARGUMENT,
      )
      assertFailure(port.setButton(null, true), DebugErrorCode.INVALID_ARGUMENT)
      assertEquals(0, port.outstandingRequestCount())
      assertFalse(port.hasPendingCommands())

      val valid = port.readMemory(DebugMemoryRequest(DebugAddressSpace.SYSTEM_BUS, 0x100, 4))
      val command = assertIs<QueuedDebugCommand.ReadMemory>(port.pollCommand())
      assertEquals(0x100, command.request.address())
      assertEquals(4, command.request.length())
      command.fail(DebugErrorCode.SIDE_EFFECTFUL_ADDRESS, "Test completion")
      assertFailure(valid, DebugErrorCode.SIDE_EFFECTFUL_ADDRESS)
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }

    val unsupported =
        QueuedDebugPort(
            9,
            DebugCapabilities(false, false, false, false, false, false, false, 0),
            1,
        )
    try {
      assertFailure(unsupported.pause(), DebugErrorCode.UNSUPPORTED_TOPOLOGY)
      assertFailure(unsupported.snapshot(), DebugErrorCode.UNSUPPORTED_TOPOLOGY)
      assertFailure(
          unsupported.step(eu.rekawek.coffeegb.core.debug.DebugStepKind.FRAME),
          DebugErrorCode.UNSUPPORTED_STEP,
      )
      assertEquals(0, unsupported.outstandingRequestCount())
    } finally {
      unsupported.close()
      assertTrue(unsupported.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `replacement drains queued and owner-held commands exactly once`() {
    val port = QueuedDebugPort(10, capabilities(), 2)
    val first = port.pause()
    val firstCommand = assertIs<QueuedDebugCommand.Pause>(port.pollCommand())
    val second = port.resume()
    val completions = AtomicInteger()
    val firstObserved = first.thenAccept { completions.incrementAndGet() }
    val secondObserved = second.thenAccept { completions.incrementAndGet() }

    port.invalidateForSessionReplacement()

    assertFailure(first, DebugErrorCode.SESSION_REPLACED)
    assertFailure(second, DebugErrorCode.SESSION_REPLACED)
    await(firstObserved)
    await(secondObserved)
    assertFalse(
        firstCommand.fail(DebugErrorCode.INTERNAL_ERROR, "Late owner completion must lose")
    )
    assertEquals(2, completions.get())
    assertTrue(port.isClosed())
    assertFalse(port.hasPendingCommands())
    assertFailure(port.snapshot(), DebugErrorCode.SESSION_REPLACED)
    port.close() // Idempotent and must not replace the first terminal reason.
    assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
  }

  @Test
  fun `ordinary close reports port closed and caller cannot cancel the admitted request`() {
    val port = QueuedDebugPort(11, capabilities(), 1)
    val stage = port.setButton(DebugButton.START, true)
    val callerCopy = stage.toCompletableFuture()
    assertTrue(callerCopy.cancel(true))
    val command = assertIs<QueuedDebugCommand.SetButton>(port.pollCommand())
    assertTrue(command.complete(DebugResult.success()))
    assertTrue(await(stage).isSuccess, "cancelling the exposed copy must not cancel owner work")

    port.close()
    assertFailure(port.snapshot(), DebugErrorCode.PORT_CLOSED)
    assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
  }

  @Test
  fun `accepted continuations run on the result dispatcher rather than owner thread`() {
    val port = QueuedDebugPort(12, capabilities(), 1)
    try {
      val stage = port.setButton(DebugButton.SELECT, true)
      val command = assertIs<QueuedDebugCommand.SetButton>(port.pollCommand())
      val continuationThread = AtomicReference<String>()
      val continued =
          stage.thenApply { result ->
            continuationThread.set(Thread.currentThread().name)
            result
          }
      val owner =
          thread(name = "test-emulation-owner") {
            assertTrue(command.complete(DebugResult.success()))
          }
      owner.join(5_000)
      assertFalse(owner.isAlive)

      assertTrue(await(continued).isSuccess)
      assertTrue(continuationThread.get().startsWith("coffee-gb-debug-results-"))
      assertNotEquals("test-emulation-owner", continuationThread.get())
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `one continuation can await another admitted result without starving delivery`() {
    val port = QueuedDebugPort(15, capabilities(), 2)
    val waiting = CountDownLatch(1)
    try {
      val first = port.snapshot()
      val second = port.snapshot()
      val firstCommand = assertIs<QueuedDebugCommand.Snapshot>(port.pollCommand())
      val secondCommand = assertIs<QueuedDebugCommand.Snapshot>(port.pollCommand())
      val dependent =
          first.thenApply { firstResult ->
            waiting.countDown()
            assertFailure(second, DebugErrorCode.SESSION_BUSY)
            firstResult
          }

      assertTrue(firstCommand.fail(DebugErrorCode.INTERNAL_ERROR, "first result"))
      assertTrue(waiting.await(5, TimeUnit.SECONDS))
      assertTrue(secondCommand.fail(DebugErrorCode.SESSION_BUSY, "second result"))

      assertFailure(dependent, DebugErrorCode.INTERNAL_ERROR)
    } finally {
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `blocked continuation retains admission permit and bounds completion backlog`() {
    val port = QueuedDebugPort(13, capabilities(), 1)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val first = port.setButton(DebugButton.RIGHT, true)
      val blocked =
          first.thenApply { result ->
            entered.countDown()
            release.await()
            result
          }
      val command = assertIs<QueuedDebugCommand.SetButton>(port.pollCommand())
      assertTrue(command.complete(DebugResult.success()))
      assertTrue(entered.await(5, TimeUnit.SECONDS))

      assertFailure(port.pause(), DebugErrorCode.QUEUE_FULL)
      assertEquals(1, port.outstandingRequestCount())

      release.countDown()
      assertTrue(await(blocked).isSuccess)
      awaitCondition { port.outstandingRequestCount() == 0 }
      val admittedAfterRelease = port.pause()
      assertIs<QueuedDebugCommand.Pause>(port.pollCommand())
      port.close()
      assertFailure(admittedAfterRelease, DebugErrorCode.PORT_CLOSED)
    } finally {
      release.countDown()
      port.close()
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun `multiple producers receive one monotonic FIFO admission sequence`() {
    val requestCount = 100
    val port = QueuedDebugPort(14, capabilities(), 128)
    val executor = Executors.newFixedThreadPool(4)
    val start = CountDownLatch(1)
    val stages = Collections.synchronizedList(mutableListOf<CompletionStage<DebugResult<Void>>>())
    try {
      repeat(4) { producer ->
        executor.execute {
          start.await()
          repeat(requestCount / 4) { index ->
            val buttons = DebugButton.values()
            val button = buttons[(producer + index) % buttons.size]
            stages += port.setButton(button, index % 2 == 0)
          }
        }
      }
      start.countDown()
      executor.shutdown()
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

      val commands = port.drainCommands(requestCount)
      assertEquals((1L..requestCount.toLong()).toList(), commands.map { it.requestId })
      assertTrue(commands.all { it is QueuedDebugCommand.SetButton })

      port.close()
      stages.forEach { assertFailure(it, DebugErrorCode.PORT_CLOSED) }
      assertTrue(port.awaitResultDispatcherTermination(5, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
      port.close()
    }
  }

  private fun capabilities(maxMemoryReadLength: Int = 256): DebugCapabilities =
      DebugCapabilities(true, true, true, true, true, true, true, maxMemoryReadLength)

  private fun advancedCapabilities(): DebugCapabilities =
      DebugCapabilities(
          true,
          true,
          true,
          true,
          true,
          true,
          true,
          256,
          EnumSet.of(DebugBreakpointKind.PROGRAM_COUNTER),
          2,
          EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY),
          16,
          4,
          DebugHistoryCapabilities(
              true,
              true,
              false,
              120,
              DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
          ),
      )

  private fun snapshot(masterTick: Long, frame: Long): DebugSnapshot =
      DebugSnapshot(
          17,
          1,
          masterTick,
          frame,
          0,
          true,
          DebugRegisters(1, 0xb0, 2, 3, 4, 5, 6, 7, 0xfffe, 0x100),
          DebugInterruptState(true, false, 0xe1, 0x01, 0x01),
          DebugTimerState(0, 0, 0, 0, false, 0),
          DebugPpuState(
              true,
              DebugPpuMode.OAM_SEARCH,
              0,
              0,
              0x91,
              0x82,
              0,
              0,
              0,
              0,
              0,
          ),
          DebugApuState(
              true,
              0,
              false,
              false,
              false,
              false,
              0,
              0,
              0x80,
          ),
          DebugMapperState(
              "test",
              -1,
              -1,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
          ),
          DebugExecutionState(DebugCpuState.OPCODE_FETCH, 0, -1, 0, false, false, 0),
      )

  private fun <T> await(stage: CompletionStage<T>): T =
      stage.toCompletableFuture().get(5, TimeUnit.SECONDS)

  private fun <T> assertFailure(
      stage: CompletionStage<DebugResult<T>>,
      expected: DebugErrorCode,
  ) {
    val result = await(stage)
    assertTrue(result.isFailure)
    assertEquals(expected, result.error().code())
  }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
      assertTrue(System.nanoTime() < deadline, "Timed out waiting for debug transport state")
      Thread.yield()
    }
  }
}
