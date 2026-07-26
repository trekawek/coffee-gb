package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9Invitation
import eu.rekawek.coffeegb.controller.network.v9.V9InvitationError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class V9SwingInvitationAdapterTest {
  private val canonical =
      "coffeegb://play.example:6688/join?v=9&mode=normal&slot=1" +
          "&exp=2000000000&token=AAECAwQFBgcICQoLDA0ODw"

  @Test
  fun parseProgressSuccessInvalidCancellationAndRejectionAreEdtSafeAndRedacted() {
    val adapter = V9SwingInvitationAdapter(V9InvitationClipboard { error("not copied") })
    val states = mutableListOf<V9InvitationUiState>()
    val onEdt = AtomicBoolean(true)
    val parsed = CountDownLatch(2)
    adapter.parseAsync(canonical) {
      onEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
      synchronized(states) { states += it }
      parsed.countDown()
    }
    assertTrue(parsed.await(3, TimeUnit.SECONDS))
    assertTrue(onEdt.get())
    val success = synchronized(states) { states.filterIsInstance<V9InvitationUiState.Parsed>().single() }
    assertEquals(canonical, success.invitation.render())
    assertEquals("Parsed([redacted])", success.toString())
    assertFalse(states.toString().contains("AAECAw"))
    success.close()
    assertFailsOffEdt { success.invitation.render() }

    val invalid = CountDownLatch(2)
    adapter.parseAsync("not-an-invitation") {
      synchronized(states) { states += it }
      invalid.countDown()
    }
    assertTrue(invalid.await(3, TimeUnit.SECONDS))
    assertEquals(
        V9InvitationError.INV_SCHEME,
        synchronized(states) {
          states.filterIsInstance<V9InvitationUiState.InvalidInvitation>().last().reason
        },
    )

    val terminal = CountDownLatch(3)
    adapter.awaitingAuthentication {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.AwaitingAuthentication>(it)
      terminal.countDown()
    }
    adapter.authenticationRejected {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.AuthenticationRejected>(it)
      terminal.countDown()
    }
    adapter.cancel {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.Cancelled>(it)
      terminal.countDown()
    }
    assertTrue(terminal.await(3, TimeUnit.SECONDS))
    adapter.close()
  }

  @Test
  fun clipboardDisclosureIsExplicitEdtOnlyAndWarningDoesNotClaimEncryption() {
    var copied: String? = null
    val adapter = V9SwingInvitationAdapter(V9InvitationClipboard { copied = it })
    val invitation = V9Invitation.parse(canonical)
    SwingUtilities.invokeAndWait { adapter.copy(invitation) }
    assertEquals(canonical, copied)
    assertFailsOffEdt { adapter.copy(invitation) }

    val warning = V9SwingInvitationAdapter.PLAINTEXT_WARNING
    assertTrue(warning.contains("does not encrypt plaintext TCP"))
    assertTrue(warning.contains("no protection"))
    assertFalse(warning.contains("secure", ignoreCase = true))
    invitation.close()
    adapter.close()
  }

  private fun assertFailsOffEdt(block: () -> Unit) {
    var failed = false
    val thread =
        Thread {
          try {
            block()
          } catch (_: IllegalStateException) {
            failed = true
          }
        }
    thread.start()
    thread.join()
    assertTrue(failed)
  }
}
