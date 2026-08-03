package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.network.v9.V9Diagnostic
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9Lifecycle
import eu.rekawek.coffeegb.controller.network.v9.V9LifecycleState
import eu.rekawek.coffeegb.controller.network.v9.V9Role
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class V9SwingLifecycleAdapterTest {

  @Test
  fun everyLifecycleCallbackIsMarshalledToTheEdtAndCloseStopsDelivery() {
    val lifecycle = V9Lifecycle(V9Role.CLIENT)
    val callbackCount = AtomicInteger()
    val allOnEdt = AtomicBoolean(true)
    val delivered = CountDownLatch(2)
    val adapter =
        V9SwingLifecycleAdapter(lifecycle) { snapshot ->
          allOnEdt.compareAndSet(true, SwingUtilities.isEventDispatchThread())
          callbackCount.incrementAndGet()
          if (snapshot.state in
              setOf(V9LifecycleState.WAIT_SERVER_HELLO, V9LifecycleState.CLOSED)) {
            delivered.countDown()
          }
        }
    lifecycle.fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
    assertTrue(delivered.await(3, TimeUnit.SECONDS))
    assertTrue(allOnEdt.get())
    assertEquals(2, callbackCount.get())

    adapter.close()
    lifecycle.closeNormally()
    SwingUtilities.invokeAndWait {}
    assertEquals(2, callbackCount.get())
  }

}
