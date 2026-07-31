package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ConnectionControllerAttemptTest {

  @Test
  fun `a superseded client worker cannot publish its late terminal event`() {
    val firstListener = ServerSocket(0)
    val firstPeer = CompletableFuture<Socket>()
    Thread(
            { firstPeer.complete(firstListener.accept()) },
            "superseded-netplay-client-peer",
        )
        .apply {
          isDaemon = true
          start()
        }

    val unavailablePort = ServerSocket(0).use { it.localPort }
    val bus = EventBusImpl()
    ConnectionController(bus)
    val disconnected = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    bus.register<ConnectionController.ClientDisconnectedFromServerEvent>(disconnected::offer)

    var heldPeer: Socket? = null
    try {
      bus.post(ConnectionController.StartClientEvent("127.0.0.1:${firstListener.localPort}", 11L))
      heldPeer = firstPeer.get(5, TimeUnit.SECONDS)

      bus.post(ConnectionController.StartClientEvent("127.0.0.1:$unavailablePort", 12L))
      heldPeer.close()

      val observed = mutableListOf<ConnectionController.ClientDisconnectedFromServerEvent>()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (observed.none { it.attemptId == 12L } && System.nanoTime() < deadline) {
        disconnected.poll(250, TimeUnit.MILLISECONDS)?.let(observed::add)
      }
      assertEquals(12L, observed.firstOrNull { it.attemptId == 12L }?.attemptId)
      val graceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
      while (System.nanoTime() < graceDeadline) {
        disconnected.poll(100, TimeUnit.MILLISECONDS)?.let(observed::add)
      }
      assertTrue(
          observed.none { it.attemptId == 11L },
          "the retired worker's terminal callback must be filtered",
      )
    } finally {
      heldPeer?.close()
      firstListener.close()
      bus.close()
    }
  }

  @Test
  fun `only an attempt scoped client stop cancels the active generation`() {
    val listener = ServerSocket(0)
    val peer = CompletableFuture<Socket>()
    Thread(
            { peer.complete(listener.accept()) },
            "attempt-scoped-netplay-client-peer",
        )
        .apply {
          isDaemon = true
          start()
        }

    val bus = EventBusImpl()
    ConnectionController(bus)
    val disconnected = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    bus.register<ConnectionController.ClientDisconnectedFromServerEvent>(disconnected::offer)

    var heldPeer: Socket? = null
    try {
      bus.post(ConnectionController.StartClientEvent("127.0.0.1:${listener.localPort}", 21L))
      heldPeer = peer.get(5, TimeUnit.SECONDS)

      bus.post(ConnectionController.StopClientEvent(22L))
      assertNull(disconnected.poll(250, TimeUnit.MILLISECONDS))

      bus.post(ConnectionController.StopClientEvent(21L))
      assertEquals(
          21L,
          disconnected.poll()?.attemptId,
          "the active attempt must publish its terminal event before StopClient dispatch returns",
      )
    } finally {
      heldPeer?.close()
      listener.close()
      bus.close()
    }
  }
}
