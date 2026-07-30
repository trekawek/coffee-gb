package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.core.debug.DebugButton
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
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded multi-producer transport between debug clients and one emulation-thread owner.
 *
 * This class deliberately knows nothing about a Gameboy or controller session. Callers only add
 * immutable commands; the emulation owner polls and completes those commands at its safe points.
 * Capacity covers the whole lifetime of an admitted request, including a result whose client
 * continuation is still running. A process-wide bounded completion service prevents blocked
 * clients and repeated session replacement from creating an unbounded callback/thread backlog.
 */
internal class QueuedDebugPort(
    private val generation: Long,
    private val debugCapabilities: DebugCapabilities,
    private val maxOutstandingRequests: Int = DEFAULT_CAPACITY,
) : DebugPort {
  private val capacity = validateCapacity(maxOutstandingRequests)
  private val lock = ReentrantLock()
  private val commands = ArrayDeque<QueuedDebugCommand<*>>(capacity)
  private val outstanding = LinkedHashMap<Long, PendingRequest<*>>(capacity)
  private val terminalDrained = CountDownLatch(1)

  // Guarded by lock. IDs describe admission order, and therefore queue order, without gaps.
  private var nextRequestId = 1L
  private var terminalError: DebugError? = null

  init {
    require(generation >= 0) { "Debug session generation must not be negative" }
  }

  override fun sessionGeneration(): Long = generation

  override fun capabilities(): DebugCapabilities = debugCapabilities

  override fun pause(): CompletionStage<DebugResult<DebugSnapshot>> {
    val validation =
        unsupportedUnless(
            debugCapabilities.pauseResume(),
            "Pause and resume are unavailable for this session",
        )
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.Pause(requestId, generation, completion)
    }
  }

  override fun resume(): CompletionStage<DebugResult<DebugSnapshot>> {
    val validation =
        unsupportedUnless(
            debugCapabilities.pauseResume(),
            "Pause and resume are unavailable for this session",
        )
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.Resume(requestId, generation, completion)
    }
  }

  override fun snapshot(): CompletionStage<DebugResult<DebugSnapshot>> {
    val validation =
        unsupportedUnless(
            debugCapabilities.snapshot(),
            "Snapshots are unavailable for this session",
        )
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.Snapshot(requestId, generation, completion)
    }
  }

  override fun step(kind: DebugStepKind?): CompletionStage<DebugResult<DebugStepResult>> {
    val validation =
        when {
          kind == null -> DebugError(DebugErrorCode.INVALID_ARGUMENT, "Step kind is required")
          !debugCapabilities.supports(kind) ->
              DebugError(DebugErrorCode.UNSUPPORTED_STEP, "Requested step kind is unavailable")
          else -> null
        }
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.Step(requestId, generation, requireNotNull(kind), completion)
    }
  }

  override fun readMemory(
      request: DebugMemoryRequest?
  ): CompletionStage<DebugResult<DebugMemoryBlock>> {
    val validation =
        when {
          request == null ->
              DebugError(DebugErrorCode.INVALID_ARGUMENT, "Memory request is required")
          !debugCapabilities.memoryRead() ->
              DebugError(
                  DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
                  "Memory reads are unavailable for this session",
              )
          request.length() > debugCapabilities.maxMemoryReadLength() ->
              DebugError(
                  DebugErrorCode.INVALID_ARGUMENT,
                  "Memory request exceeds the negotiated length limit",
              )
          else -> null
        }
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.ReadMemory(requestId, generation, requireNotNull(request), completion)
    }
  }

  override fun setButton(
      button: DebugButton?,
      pressed: Boolean,
  ): CompletionStage<DebugResult<Void>> {
    val validation =
        when {
          button == null -> DebugError(DebugErrorCode.INVALID_ARGUMENT, "Button is required")
          !debugCapabilities.buttonInput() ->
              DebugError(
                  DebugErrorCode.UNSUPPORTED_TOPOLOGY,
                  "Debug input is unavailable for this session",
              )
          else -> null
        }
    return submit(validation) { requestId, completion ->
      QueuedDebugCommand.SetButton(
          requestId,
          generation,
          requireNotNull(button),
          pressed,
          completion,
      )
    }
  }

  override fun isClosed(): Boolean = lock.withLock { terminalError != null }

  /** Returns true without allocating when the owner has work waiting at its next safe point. */
  internal fun hasPendingCommands(): Boolean = lock.withLock { commands.isNotEmpty() }

  /** Removes the oldest admitted command. This must only be called by the emulation owner. */
  internal fun pollCommand(): QueuedDebugCommand<*>? =
      lock.withLock { if (commands.isEmpty()) null else commands.removeFirst() }

  /**
   * Removes up to [maxCommands] in admission order. This must only be called by the emulation
   * owner. A removed command remains part of the bounded outstanding set until it is completed.
   */
  internal fun drainCommands(maxCommands: Int): List<QueuedDebugCommand<*>> {
    require(maxCommands >= 0) { "Maximum command count must not be negative" }
    if (maxCommands == 0) return emptyList()
    return lock.withLock {
      val count = minOf(maxCommands, commands.size)
      if (count == 0) return@withLock emptyList()
      ArrayList<QueuedDebugCommand<*>>(count).also { drained ->
        repeat(count) { drained += commands.removeFirst() }
      }
    }
  }

  /** Revokes this generation while preserving a distinct failure from an ordinary client close. */
  internal fun invalidateForSessionReplacement() {
    terminate(
        DebugError(
            DebugErrorCode.SESSION_REPLACED,
            "The debug session was replaced",
        )
    )
  }

  override fun close() {
    terminate(DebugError(DebugErrorCode.PORT_CLOSED, "The debug port is closed"))
  }

  /** Test hook: waits for this terminal port's admitted callbacks, not the shared service. */
  internal fun awaitResultDispatcherTermination(timeout: Long, unit: TimeUnit): Boolean =
      terminalDrained.await(timeout, unit)

  internal fun outstandingRequestCount(): Int = lock.withLock { outstanding.size }

  private fun <T> submit(
      validationError: DebugError?,
      commandFactory:
          (requestId: Long, completion: (DebugResult<T>) -> Boolean) -> QueuedDebugCommand<T>,
  ): CompletionStage<DebugResult<T>> {
    lock.withLock {
      terminalError?.let { return rejectedStage(it) }
      validationError?.let { return rejectedStage(it) }
      if (outstanding.size >= capacity) {
        return rejectedStage(
            DebugError(DebugErrorCode.QUEUE_FULL, "The debug command queue is full")
        )
      }
      if (!DebugResultDispatcher.tryAcquire()) {
        return rejectedStage(
            DebugError(DebugErrorCode.QUEUE_FULL, "The debug result service is full")
        )
      }
      if (nextRequestId == Long.MAX_VALUE) {
        DebugResultDispatcher.release()
        return rejectedStage(
            DebugError(DebugErrorCode.INTERNAL_ERROR, "Debug request identifiers are exhausted")
        )
      }

      val requestId = nextRequestId++
      val future = CompletableFuture<DebugResult<T>>()
      val pending = PendingRequest(requestId, future)
      try {
        val command = commandFactory(requestId) { result -> scheduleCompletion(pending, result) }
        outstanding[requestId] = pending
        commands.addLast(command)
        return future.minimalCompletionStage()
      } catch (failure: Throwable) {
        DebugResultDispatcher.release()
        throw failure
      }
    }
  }

  private fun <T> scheduleCompletion(
      pending: PendingRequest<T>,
      result: DebugResult<T>,
  ): Boolean =
      lock.withLock {
        if (outstanding[pending.requestId] !== pending || pending.scheduled) {
          return@withLock false
        }
        scheduleCompletionLocked(pending, result)
        true
      }

  private fun <T> scheduleCompletionLocked(
      pending: PendingRequest<T>,
      result: DebugResult<T>,
  ) {
    pending.scheduled = true
    DebugResultDispatcher.dispatch { deliver(pending, result) }
  }

  private fun <T> deliver(
      pending: PendingRequest<T>,
      result: DebugResult<T>,
  ) {
    try {
      // Non-async CompletionStage continuations registered by clients run here, never on the
      // emulation owner. Keep this request admitted until those continuations have returned.
      pending.future.complete(result)
    } finally {
      lock.withLock {
        outstanding.remove(pending.requestId, pending)
        if (terminalError != null && outstanding.isEmpty()) {
          terminalDrained.countDown()
        }
      }
      DebugResultDispatcher.release()
    }
  }

  private fun terminate(error: DebugError) {
    lock.withLock {
      if (terminalError != null) return
      terminalError = error
      commands.clear()
      outstanding.values.forEach { pending ->
        if (!pending.scheduled) scheduleFailureLocked(pending, error)
      }
      if (outstanding.isEmpty()) {
        terminalDrained.countDown()
      }
    }
  }

  private fun scheduleFailureLocked(pending: PendingRequest<*>, error: DebugError) {
    scheduleFailureTyped(pending, error)
  }

  private fun <T> scheduleFailureTyped(pending: PendingRequest<T>, error: DebugError) {
    scheduleCompletionLocked(pending, DebugResult.failure(error))
  }

  private fun unsupportedUnless(supported: Boolean, message: String): DebugError? =
      if (supported) null else DebugError(DebugErrorCode.UNSUPPORTED_TOPOLOGY, message)

  private fun <T> rejectedStage(error: DebugError): CompletionStage<DebugResult<T>> =
      CompletableFuture.completedFuture(DebugResult.failure<T>(error)).minimalCompletionStage()

  private class PendingRequest<T>(
      val requestId: Long,
      val future: CompletableFuture<DebugResult<T>>,
      var scheduled: Boolean = false,
  )

  private companion object {
    const val DEFAULT_CAPACITY = 64
    const val MAX_OUTSTANDING_REQUESTS = DebugResultDispatcher.CAPACITY

    fun validateCapacity(value: Int): Int {
      require(value in 1..MAX_OUTSTANDING_REQUESTS) {
        "Debug outstanding-request capacity must be between 1 and $MAX_OUTSTANDING_REQUESTS"
      }
      return value
    }
  }
}

/**
 * Lifecycle-independent completion isolation shared by all debug generations.
 *
 * A permit is acquired before command admission and retained until synchronous continuations have
 * returned. The equally sized lazy daemon pool means one continuation may wait for another
 * admitted result without starving that result's delivery. Permanently blocked clients consume a
 * bounded process-wide permit instead of leaking one dispatcher thread per replaced session.
 */
internal object DebugResultDispatcher {
  const val CAPACITY = 256

  private val permits = Semaphore(CAPACITY)
  private val nextThreadId = AtomicLong()
  private val executor =
      ThreadPoolExecutor(
              CAPACITY,
              CAPACITY,
              30,
              TimeUnit.SECONDS,
              ArrayBlockingQueue(CAPACITY),
              { runnable ->
                Thread(
                        runnable,
                        "coffee-gb-debug-results-${nextThreadId.incrementAndGet()}",
                    )
                    .apply { isDaemon = true }
              },
          )
          .apply { allowCoreThreadTimeOut(true) }

  fun tryAcquire(): Boolean = permits.tryAcquire()

  fun release() {
    permits.release()
  }

  fun dispatch(task: () -> Unit) {
    executor.execute(task)
  }
}

/** Immutable owner-side command envelopes emitted by [QueuedDebugPort]. */
internal sealed class QueuedDebugCommand<T> protected constructor(
    val requestId: Long,
    val sessionGeneration: Long,
    private val completion: (DebugResult<T>) -> Boolean,
) {
  /** Returns false if close, replacement, or another owner completion already won the race. */
  fun complete(result: DebugResult<T>): Boolean = completion(result)

  fun fail(code: DebugErrorCode, message: String): Boolean =
      complete(DebugResult.failure(code, message))

  class Pause internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      completion: (DebugResult<DebugSnapshot>) -> Boolean,
  ) : QueuedDebugCommand<DebugSnapshot>(requestId, sessionGeneration, completion)

  class Resume internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      completion: (DebugResult<DebugSnapshot>) -> Boolean,
  ) : QueuedDebugCommand<DebugSnapshot>(requestId, sessionGeneration, completion)

  class Snapshot internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      completion: (DebugResult<DebugSnapshot>) -> Boolean,
  ) : QueuedDebugCommand<DebugSnapshot>(requestId, sessionGeneration, completion)

  class Step internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      val kind: DebugStepKind,
      completion: (DebugResult<DebugStepResult>) -> Boolean,
  ) : QueuedDebugCommand<DebugStepResult>(requestId, sessionGeneration, completion)

  class ReadMemory internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      val request: DebugMemoryRequest,
      completion: (DebugResult<DebugMemoryBlock>) -> Boolean,
  ) : QueuedDebugCommand<DebugMemoryBlock>(requestId, sessionGeneration, completion)

  class SetButton internal constructor(
      requestId: Long,
      sessionGeneration: Long,
      val button: DebugButton,
      val pressed: Boolean,
      completion: (DebugResult<Void>) -> Boolean,
  ) : QueuedDebugCommand<Void>(requestId, sessionGeneration, completion)
}
