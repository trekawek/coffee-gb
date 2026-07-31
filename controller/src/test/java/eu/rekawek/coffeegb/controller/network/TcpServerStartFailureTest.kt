package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class TcpServerStartFailureTest {

  @Test
  fun `occupied listening port publishes a typed terminal failure`() {
    val attemptId = 42L
    ServerSocket(0).use { occupied ->
      val bus = EventBusImpl()
      val failures = LinkedBlockingQueue<ConnectionController.ServerStartFailedEvent>()
      bus.register<ConnectionController.ServerStartFailedEvent>(failures::offer)
      val worker =
          Thread(
              TcpServer(bus, occupied.localPort, attemptId = attemptId),
              "occupied-netplay-port-test",
          )
      try {
        worker.start()
        val failure = assertNotNull(failures.poll(5, TimeUnit.SECONDS))
        assertEquals(ConnectionController.ServerStartFailure.PORT_UNAVAILABLE, failure.failure)
        assertEquals(occupied.localPort, failure.port)
        assertEquals(attemptId, failure.attemptId)
        worker.join(5_000)
        assertEquals(false, worker.isAlive)
      } finally {
        bus.close()
      }
    }
  }
}
