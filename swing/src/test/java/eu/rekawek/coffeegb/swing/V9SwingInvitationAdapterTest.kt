package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9Invitation
import eu.rekawek.coffeegb.controller.network.v9.V9InvitationError
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    val awaiting = CountDownLatch(1)
    adapter.awaitingAuthentication {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.AwaitingAuthentication>(it)
      awaiting.countDown()
    }
    assertTrue(awaiting.await(3, TimeUnit.SECONDS))

    val rejected = CountDownLatch(1)
    adapter.authenticationRejected {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.AuthenticationRejected>(it)
      rejected.countDown()
    }
    assertTrue(rejected.await(3, TimeUnit.SECONDS))

    val cancelled = CountDownLatch(1)
    adapter.cancel {
      assertTrue(SwingUtilities.isEventDispatchThread())
      assertIs<V9InvitationUiState.Cancelled>(it)
      cancelled.countDown()
    }
    assertTrue(cancelled.await(3, TimeUnit.SECONDS))
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

  @Test
  fun cancelSuppressesEveryQueuedOlderStateAndDestroysDiscardedSecret() {
    val gate = EdtGate.block()
    val executor = ImmediateExecutorService()
    val delivered = mutableListOf<V9InvitationUiState>()
    val invalidDelivered = mutableListOf<V9InvitationUiState>()
    lateinit var discardedInvitation: V9Invitation
    val adapter =
        V9SwingInvitationAdapter.forTest(
            V9InvitationClipboard { error("not copied") },
            executor,
        ) {
          V9Invitation.parse(it).also { value -> discardedInvitation = value }
        }
    val invalidAdapter = V9SwingInvitationAdapter(executor = executor)
    try {
      adapter.parseAsync(canonical) { delivered += it }
      adapter.awaitingAuthentication { delivered += it }
      adapter.authenticationRejected { delivered += it }
      adapter.cancel { delivered += it }

      invalidAdapter.parseAsync("not-an-invitation") { invalidDelivered += it }
      invalidAdapter.cancel { invalidDelivered += it }
    } finally {
      gate.releaseAndDrain()
    }

    assertEquals(1, delivered.size)
    assertIs<V9InvitationUiState.Cancelled>(delivered.single())
    assertEquals(1, invalidDelivered.size)
    assertIs<V9InvitationUiState.Cancelled>(invalidDelivered.single())
    assertFailsWith<IllegalStateException> { discardedInvitation.render() }
    adapter.close()
    invalidAdapter.close()
  }

  @Test
  fun newerParseSuppressesQueuedOlderResultWithoutClosingDeliveredResult() {
    val gate = EdtGate.block()
    val executor = ImmediateExecutorService()
    val oldDelivered = mutableListOf<V9InvitationUiState>()
    val currentDelivered = mutableListOf<V9InvitationUiState>()
    val parsed = mutableListOf<V9Invitation>()
    val adapter =
        V9SwingInvitationAdapter.forTest(
            V9InvitationClipboard { error("not copied") },
            executor,
        ) {
          V9Invitation.parse(it).also(parsed::add)
        }
    val newer = canonical.replace("play.example", "new.example")
    try {
      adapter.parseAsync(canonical) { oldDelivered += it }
      adapter.parseAsync(newer) { currentDelivered += it }
    } finally {
      gate.releaseAndDrain()
    }

    assertTrue(oldDelivered.isEmpty())
    assertEquals(2, currentDelivered.size)
    assertIs<V9InvitationUiState.Parsing>(currentDelivered[0])
    val delivered = assertIs<V9InvitationUiState.Parsed>(currentDelivered[1])
    assertEquals(2, parsed.size)
    assertFailsWith<IllegalStateException> { parsed[0].render() }
    assertEquals(newer, parsed[1].render())
    assertEquals(newer, delivered.invitation.render())
    delivered.close()
    adapter.close()
  }

  @Test
  fun closeSuppressesAlreadyQueuedResultAndDestroysItsSecret() {
    val gate = EdtGate.block()
    val executor = ImmediateExecutorService()
    val delivered = mutableListOf<V9InvitationUiState>()
    lateinit var discardedInvitation: V9Invitation
    val adapter =
        V9SwingInvitationAdapter.forTest(
            V9InvitationClipboard { error("not copied") },
            executor,
        ) {
          V9Invitation.parse(it).also { value -> discardedInvitation = value }
        }
    try {
      adapter.parseAsync(canonical) { delivered += it }
      adapter.close()
    } finally {
      gate.releaseAndDrain()
    }

    assertTrue(delivered.isEmpty())
    assertFailsWith<IllegalStateException> { discardedInvitation.render() }
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

  private class EdtGate private constructor(
      private val release: CountDownLatch,
  ) {
    fun releaseAndDrain() {
      release.countDown()
      SwingUtilities.invokeAndWait {}
    }

    companion object {
      fun block(): EdtGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SwingUtilities.invokeLater {
          entered.countDown()
          release.await()
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        return EdtGate(release)
      }
    }
  }

  private class ImmediateExecutorService : AbstractExecutorService() {
    private val stopped = AtomicBoolean(false)

    override fun execute(command: Runnable) {
      check(!stopped.get())
      command.run()
    }

    override fun shutdown() {
      stopped.set(true)
    }

    override fun shutdownNow(): MutableList<Runnable> {
      stopped.set(true)
      return mutableListOf()
    }

    override fun isShutdown(): Boolean = stopped.get()

    override fun isTerminated(): Boolean = stopped.get()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped.get()
  }
}
