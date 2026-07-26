package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9ConsentDecision
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9FoundationConnection
import eu.rekawek.coffeegb.controller.network.v9.V9Part3Progress
import eu.rekawek.coffeegb.controller.network.v9.V9Part3ProgressListener
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

sealed class V9Part3UiState {
  class Progress(val value: V9Part3Progress) : V9Part3UiState()
  class Failed(val reason: V9ErrorCode) : V9Part3UiState()
  object Cancelled : V9Part3UiState()
}

/**
 * EDT-only presentation boundary for opt-in v9 consent and private-transfer progress.
 *
 * It carries only sanitized [V9Part3Progress] metadata. Source providers and completed private
 * candidates remain controller-owned and never cross this adapter. Decisions and cancellation run
 * off the EDT. Replacing an observer, cancelling, or closing invalidates callbacks already queued
 * for an older operation.
 */
class V9SwingPart3Adapter private constructor(
    private val controller: V9Part3UiController,
    executor: ExecutorService? = null,
) : Closeable {
  constructor(
      connection: V9FoundationConnection,
      executor: ExecutorService? = null,
  ) : this(V9FoundationPart3UiController(connection), executor)

  private val executor =
      executor
          ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "netplay-v9-consent-ui").also { it.isDaemon = true }
          }
  private val ownedExecutor = if (executor == null) this.executor else null
  private val closed = AtomicBoolean(false)
  private var operation = Any()
  private var subscription: Closeable? = null
  private var pending: Future<*>? = null

  @Synchronized
  fun observe(consumer: (V9Part3UiState) -> Unit) {
    check(!closed.get()) { "v9 consent adapter is closed" }
    subscription?.close()
    val current = nextOperation()
    subscription =
        controller.addProgressListener(
            V9Part3ProgressListener { progress ->
              val state =
                  progress.failure?.let(V9Part3UiState::Failed)
                      ?: V9Part3UiState.Progress(progress)
              publish(current, consumer, state)
            },
        )
  }

  @Synchronized
  fun decide(
      proposalId: Long,
      decision: V9ConsentDecision,
      consumer: (V9Part3UiState) -> Unit,
  ) {
    check(!closed.get()) { "v9 consent adapter is closed" }
    val current = operation
    pending =
        executor.submit {
          try {
            if (isCurrent(current)) controller.submitConsent(proposalId, decision)
          } catch (_: RuntimeException) {
            publish(current, consumer, V9Part3UiState.Failed(V9ErrorCode.CONSENT_REJECTED))
          }
        }
  }

  @Synchronized
  fun cancel(consumer: (V9Part3UiState) -> Unit) {
    if (closed.get()) return
    pending?.cancel(true)
    pending = null
    subscription?.close()
    subscription = null
    val current = nextOperation()
    publish(current, consumer, V9Part3UiState.Cancelled)
    executeClose()
  }

  @Synchronized
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    nextOperation()
    pending?.cancel(true)
    pending = null
    subscription?.close()
    subscription = null
    executeClose()
    ownedExecutor?.shutdown()
  }

  private fun publish(
      expected: Any,
      consumer: (V9Part3UiState) -> Unit,
      state: V9Part3UiState,
  ) {
    SwingUtilities.invokeLater {
      if (isCurrent(expected)) consumer(state)
    }
  }

  @Synchronized
  private fun nextOperation(): Any {
    operation = Any()
    return operation
  }

  @Synchronized
  private fun isCurrent(expected: Any): Boolean =
      !closed.get() && operation === expected

  private fun executeClose() {
    try {
      executor.execute(controller::close)
    } catch (_: RuntimeException) {
      Thread(controller::close, "netplay-v9-consent-close").also {
        it.isDaemon = true
        it.start()
      }
    }
  }

  companion object {
    internal fun forTest(
        controller: V9Part3UiController,
        executor: ExecutorService,
    ): V9SwingPart3Adapter = V9SwingPart3Adapter(controller, executor)

    const val PLAINTEXT_WARNING =
        "Consent does not encrypt plaintext TCP and provides no protection against an on-path " +
            "attacker. Approve only the named item and direction you expect."
  }
}

internal interface V9Part3UiController {
  fun addProgressListener(listener: V9Part3ProgressListener): Closeable

  fun submitConsent(proposalId: Long, decision: V9ConsentDecision)

  fun close()
}

private class V9FoundationPart3UiController(
    private val connection: V9FoundationConnection,
) : V9Part3UiController {
  override fun addProgressListener(listener: V9Part3ProgressListener): Closeable =
      connection.addPart3ProgressListener(listener)

  override fun submitConsent(proposalId: Long, decision: V9ConsentDecision) =
      connection.submitConsent(proposalId, decision)

  override fun close() = connection.close()
}
