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
    override fun close() {
      invitation.close()
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
class V9SwingInvitationAdapter(
    private val clipboard: V9InvitationClipboard = V9InvitationClipboard.SYSTEM,
    executor: ExecutorService? = null,
) : Closeable {
  private val executor =
      executor
          ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "netplay-v9-invitation-parser").also { it.isDaemon = true }
          }
  private val ownedExecutor = if (executor == null) this.executor else null
  private val closed = AtomicBoolean(false)
  private var pending: Future<*>? = null

  @Synchronized
  fun parseAsync(
      input: String,
      consumer: (V9InvitationUiState) -> Unit,
  ) {
    check(!closed.get()) { "v9 invitation adapter is closed" }
    pending?.cancel(true)
    publish(consumer, V9InvitationUiState.Parsing)
    pending =
        executor.submit {
          val result =
              try {
                V9InvitationUiState.Parsed(V9Invitation.parse(input))
              } catch (e: V9InvitationParseException) {
                V9InvitationUiState.InvalidInvitation(e.reason)
              } catch (_: RuntimeException) {
                V9InvitationUiState.InvalidInvitation(V9InvitationError.INV_AUTHORITY)
              }
          if (!Thread.currentThread().isInterrupted && !closed.get()) {
            publish(consumer, result)
          } else {
            discard(result)
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

  fun authenticationRejected(consumer: (V9InvitationUiState) -> Unit) {
    publish(consumer, V9InvitationUiState.AuthenticationRejected)
  }

  fun awaitingAuthentication(consumer: (V9InvitationUiState) -> Unit) {
    publish(consumer, V9InvitationUiState.AwaitingAuthentication)
  }

  @Synchronized
  fun cancel(consumer: (V9InvitationUiState) -> Unit) {
    pending?.cancel(true)
    pending = null
    publish(consumer, V9InvitationUiState.Cancelled)
  }

  @Synchronized
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    pending?.cancel(true)
    pending = null
    ownedExecutor?.shutdownNow()
  }

  private fun publish(
      consumer: (V9InvitationUiState) -> Unit,
      state: V9InvitationUiState,
  ) {
    SwingUtilities.invokeLater {
      if (!closed.get()) consumer(state) else discard(state)
    }
  }

  private fun discard(state: V9InvitationUiState) {
    if (state is V9InvitationUiState.Parsed) state.close()
  }

  companion object {
    const val PLAINTEXT_WARNING =
        "Invitation possession does not encrypt plaintext TCP and provides no protection " +
            "against an on-path attacker."
  }
}
