package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugResult
import java.util.Collections
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
