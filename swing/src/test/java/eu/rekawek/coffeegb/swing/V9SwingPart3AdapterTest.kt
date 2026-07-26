package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9ConsentDecision
import eu.rekawek.coffeegb.controller.network.v9.V9ConsentItem
import eu.rekawek.coffeegb.controller.network.v9.V9ConsentItemProgress
import eu.rekawek.coffeegb.controller.network.v9.V9ConsentItemState
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState
import eu.rekawek.coffeegb.controller.network.v9.V9Part3Progress
import eu.rekawek.coffeegb.controller.network.v9.V9Part3ProgressListener
import eu.rekawek.coffeegb.controller.network.v9.V9TransferAsset
import eu.rekawek.coffeegb.controller.network.v9.V9TransferClass
import java.io.Closeable
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class V9SwingPart3AdapterTest {

  @Test
  fun progressAndDecisionsAreEdtSafeSanitizedAndNonBlocking() {
    val controller = FakeController()
    val executor = ImmediateExecutorService()
    val adapter = V9SwingPart3Adapter.forTest(controller, executor)
    val delivered = CountDownLatch(1)
    val onEdt = AtomicBoolean()
    var state: V9Part3UiState? = null
    adapter.observe {
      onEdt.set(SwingUtilities.isEventDispatchThread())
      state = it
      delivered.countDown()
    }
    controller.publish(progress())
    assertTrue(delivered.await(2, TimeUnit.SECONDS))
    assertTrue(onEdt.get())
    val value = assertIs<V9Part3UiState.Progress>(state).value
    assertEquals(41, value.items.single().item.proposalId)
    assertFalse(value.toString().contains("digest", ignoreCase = true))
    assertFalse(value.toString().contains("path", ignoreCase = true))
    assertFalse(value.toString().contains("32768"))

    val decisionDelivered = CountDownLatch(1)
    adapter.decide(41, V9ConsentDecision.APPROVE) { decisionDelivered.countDown() }
    assertEquals(listOf(41L to V9ConsentDecision.APPROVE), controller.decisions)
    assertFalse(controller.decisionOnEdt)
    adapter.close()
    waitUntil { controller.closed.get() }
    assertTrue(V9SwingPart3Adapter.PLAINTEXT_WARNING.contains("does not encrypt"))
  }

  @Test
  fun queuedProgressIsSuppressedByNewObserverCancelAndClose() {
    val gate = EdtGate.block()
    val controller = FakeController()
    val executor = ImmediateExecutorService()
    val adapter = V9SwingPart3Adapter.forTest(controller, executor)
    val old = mutableListOf<V9Part3UiState>()
    val current = mutableListOf<V9Part3UiState>()
    try {
      adapter.observe { old += it }
      controller.publish(progress())
      adapter.observe { current += it }
      controller.publish(progress(transferred = 7))
    } finally {
      gate.releaseAndDrain()
    }
    assertTrue(old.isEmpty())
    assertEquals(1, current.size)
    assertEquals(
        7,
        assertIs<V9Part3UiState.Progress>(current.single())
            .value.items.single().transferredBytes,
    )

    val cancelGate = EdtGate.block()
    val cancelled = mutableListOf<V9Part3UiState>()
    try {
      controller.publish(progress(transferred = 8))
      adapter.cancel { cancelled += it }
    } finally {
      cancelGate.releaseAndDrain()
    }
    assertEquals(1, cancelled.size)
    assertIs<V9Part3UiState.Cancelled>(cancelled.single())
    waitUntil { controller.closed.get() }

    val closedController = FakeController()
    val closedAdapter = V9SwingPart3Adapter.forTest(closedController, executor)
    val closeGate = EdtGate.block()
    val afterClose = mutableListOf<V9Part3UiState>()
    try {
      closedAdapter.observe { afterClose += it }
      closedController.publish(progress())
      closedAdapter.close()
    } finally {
      closeGate.releaseAndDrain()
    }
    assertTrue(afterClose.isEmpty())
    waitUntil { closedController.closed.get() }
  }

  @Test
  fun sanitizedDecisionFailureAndRepeatedCloseRemainStable() {
    val controller = FakeController(failDecision = true)
    val adapter = V9SwingPart3Adapter.forTest(controller, ImmediateExecutorService())
    val delivered = CountDownLatch(1)
    var state: V9Part3UiState? = null
    adapter.observe {}
    adapter.decide(41, V9ConsentDecision.REJECT) {
      state = it
      delivered.countDown()
    }
    assertTrue(delivered.await(2, TimeUnit.SECONDS))
    assertEquals(
        V9ErrorCode.CONSENT_REJECTED,
        assertIs<V9Part3UiState.Failed>(state).reason,
    )
    adapter.close()
    adapter.close()
    waitUntil { controller.closeCount.get() == 1 }
  }

  private fun progress(transferred: Long = 0): V9Part3Progress =
      V9Part3Progress(
          V9LifecycleState.EXCHANGE_CONSENT,
          listOf(
              V9ConsentItemProgress(
                  V9ConsentItem(
                      41,
                      V9TransferClass.ROM,
                      V9TransferAsset.PRIMARY_ROM,
                      1,
                      0,
                      1,
                      32_768,
                  ),
                  V9ConsentItemState.WAITING_FOR_LOCAL_DECISION,
                  transferred,
              ),
          ),
          false,
          null,
      )

  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!condition()) {
      if (System.nanoTime() >= deadline) throw AssertionError("condition did not become true")
      Thread.yield()
    }
  }

  private class FakeController(
      private val failDecision: Boolean = false,
  ) : V9Part3UiController {
    private var listener: V9Part3ProgressListener? = null
    val decisions = mutableListOf<Pair<Long, V9ConsentDecision>>()
    var decisionOnEdt = false
    val closed = AtomicBoolean(false)
    val closeCount = AtomicInteger()

    override fun addProgressListener(listener: V9Part3ProgressListener): Closeable {
      this.listener = listener
      return Closeable {
        if (this.listener === listener) this.listener = null
      }
    }

    override fun submitConsent(proposalId: Long, decision: V9ConsentDecision) {
      decisionOnEdt = SwingUtilities.isEventDispatchThread()
      if (failDecision) throw IllegalStateException("private detail")
      decisions += proposalId to decision
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) closeCount.incrementAndGet()
    }

    fun publish(value: V9Part3Progress) {
      listener?.onProgress(value)
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
}
