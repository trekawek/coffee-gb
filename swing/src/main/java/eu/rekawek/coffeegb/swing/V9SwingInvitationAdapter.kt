package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9Invitation
import eu.rekawek.coffeegb.controller.network.v9.V9InvitationError
import eu.rekawek.coffeegb.controller.network.v9.V9InvitationParseException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

sealed class V9InvitationUiState {
  object Parsing : V9InvitationUiState()
  class Parsed(val invitation: V9Invitation) : V9InvitationUiState(), Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
      if (closed.compareAndSet(false, true)) invitation.close()
    }

    override fun toString(): String = "Parsed([redacted])"
  }
  class InvalidInvitation(val reason: V9InvitationError) : V9InvitationUiState()
  object AwaitingAuthentication : V9InvitationUiState()
  object AuthenticationRejected : V9InvitationUiState()
  object Cancelled : V9InvitationUiState()
}

fun interface V9InvitationClipboard {
  fun copy(value: String)

  companion object {
    val SYSTEM =
        V9InvitationClipboard { value ->
          Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        }
  }
}

/**
 * Minimal Part-1 desktop boundary.
 *
 * Parsing runs away from the EDT, all presentation callbacks run on the EDT, and token-bearing text
 * is passed only to the explicit clipboard operation. This adapter has no title/log/diagnostic
 * storage and does not make protocol v9 reachable from the normal netplay menu.
 */
class V9SwingInvitationAdapter private constructor(
    private val clipboard: V9InvitationClipboard = V9InvitationClipboard.SYSTEM,
    executor: ExecutorService? = null,
    private val parser: (String) -> V9Invitation,
) : Closeable {
  constructor(
      clipboard: V9InvitationClipboard = V9InvitationClipboard.SYSTEM,
      executor: ExecutorService? = null,
  ) : this(clipboard, executor, V9Invitation::parse)

  private val executor =
      executor
          ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "netplay-v9-invitation-parser").also { it.isDaemon = true }
          }
  private val ownedExecutor = if (executor == null) this.executor else null
  private val closed = AtomicBoolean(false)
  private var pending: Future<*>? = null
  private var operation = Any()

  @Synchronized
  fun parseAsync(
      input: String,
      consumer: (V9InvitationUiState) -> Unit,
  ) {
    check(!closed.get()) { "v9 invitation adapter is closed" }
    pending?.cancel(true)
    val current = nextOperation()
    publish(current, consumer, V9InvitationUiState.Parsing)
    pending =
        executor.submit {
          val result =
              try {
                V9InvitationUiState.Parsed(parser(input))
              } catch (e: V9InvitationParseException) {
                V9InvitationUiState.InvalidInvitation(e.reason)
              } catch (_: RuntimeException) {
                V9InvitationUiState.InvalidInvitation(V9InvitationError.INV_AUTHORITY)
              }
          if (Thread.currentThread().isInterrupted || !isCurrent(current)) {
            discard(result)
          } else {
            publish(current, consumer, result)
          }
        }
  }

  fun copy(invitation: V9Invitation) {
    check(SwingUtilities.isEventDispatchThread()) {
      "invitation clipboard access must run on the EDT"
    }
    check(!closed.get()) { "v9 invitation adapter is closed" }
    clipboard.copy(invitation.render())
  }

  @Synchronized
  fun authenticationRejected(consumer: (V9InvitationUiState) -> Unit) {
    publish(operation, consumer, V9InvitationUiState.AuthenticationRejected)
  }

  @Synchronized
  fun awaitingAuthentication(consumer: (V9InvitationUiState) -> Unit) {
    publish(operation, consumer, V9InvitationUiState.AwaitingAuthentication)
  }

  @Synchronized
  fun cancel(consumer: (V9InvitationUiState) -> Unit) {
    pending?.cancel(true)
    pending = null
    val current = nextOperation()
    publish(current, consumer, V9InvitationUiState.Cancelled)
  }

  @Synchronized
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    nextOperation()
    pending?.cancel(true)
    pending = null
    ownedExecutor?.shutdownNow()
  }

  private fun publish(
      publishedOperation: Any,
      consumer: (V9InvitationUiState) -> Unit,
      state: V9InvitationUiState,
  ) {
    SwingUtilities.invokeLater {
      if (isCurrent(publishedOperation)) consumer(state) else discard(state)
    }
  }

  @Synchronized
  private fun nextOperation(): Any {
    operation = Any()
    return operation
  }

  @Synchronized
  private fun isCurrent(expected: Any): Boolean = !closed.get() && operation === expected

  private fun discard(state: V9InvitationUiState) {
    if (state is V9InvitationUiState.Parsed) state.close()
  }

  companion object {
    internal fun forTest(
        clipboard: V9InvitationClipboard,
        executor: ExecutorService,
        parser: (String) -> V9Invitation,
    ): V9SwingInvitationAdapter =
        V9SwingInvitationAdapter(clipboard, executor, parser)

    const val PLAINTEXT_WARNING =
        "Invitation possession does not encrypt plaintext TCP and provides no protection " +
            "against an on-path attacker."
  }
}
