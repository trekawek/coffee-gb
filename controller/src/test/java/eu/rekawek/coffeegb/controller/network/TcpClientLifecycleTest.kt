package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TcpClientLifecycleTest {

  @Test
  fun `socket connect always receives the production timeout`() {
    val attemptedTimeout = AtomicInteger()
    val socket =
        object : Socket() {
          override fun connect(endpoint: SocketAddress?, timeout: Int) {
            attemptedTimeout.set(timeout)
            throw SocketTimeoutException("secret.example.test at 192.0.2.20")
          }
        }
    val bus = EventBusImpl()
    val terminal = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    bus.register<ConnectionController.ClientDisconnectedFromServerEvent>(terminal::offer)
    try {
      val client =
          TcpClient.forTest(
              host = "secret.example.test:6688",
              eventBus = bus,
              attemptId = 41L,
              socketFactory = { socket },
              addressResolver = { InetAddress.getLoopbackAddress() },
          )
      client.run()

      assertEquals(TcpClient.CONNECT_TIMEOUT_MILLIS, attemptedTimeout.get())
      assertEquals(41L, terminal.poll()?.attemptId)
      assertNull(terminal.poll(), "one attempt must publish exactly one terminal event")
    } finally {
      bus.close()
    }
  }

  @Test
  fun `cancel is terminal before return even when platform resolution ignores interruption`() {
    val resolverEntered = CountDownLatch(1)
    val releaseResolver = CountDownLatch(1)
    val terminal = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    val bus = EventBusImpl()
    bus.register<ConnectionController.ClientDisconnectedFromServerEvent>(terminal::offer)
    val client =
        TcpClient.forTest(
            host = "secret.example.test",
            eventBus = bus,
            attemptId = 42L,
            addressResolver = {
              resolverEntered.countDown()
              var released = false
              while (!released) {
                try {
                  released = releaseResolver.await(25, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                  // Model platform DNS implementations that do not honor interruption.
                }
              }
              InetAddress.getLoopbackAddress()
            },
        )
    val worker = thread(isDaemon = true, name = "blocked-resolver-client-test", block = client::run)
    try {
      assertTrue(resolverEntered.await(1, TimeUnit.SECONDS))

      client.stop()

      assertEquals(
          42L,
          terminal.poll()?.attemptId,
          "the terminal event must be published synchronously before stop returns",
      )
      worker.join(1_000)
      assertFalse(worker.isAlive, "the client worker must not remain blocked on the resolver")
      assertNull(terminal.poll(), "the late resolver result must not publish a second terminal event")
    } finally {
      releaseResolver.countDown()
      worker.join(1_000)
      bus.close()
    }
  }

  @Test
  fun `an uninterruptible platform resolver cannot keep the client attempt past its deadline`() {
    val resolverEntered = CountDownLatch(1)
    val releaseResolver = CountDownLatch(1)
    val terminal = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    val bus = EventBusImpl()
    bus.register<ConnectionController.ClientDisconnectedFromServerEvent>(terminal::offer)
    val client =
        TcpClient.forTest(
            host = "secret.example.test",
            eventBus = bus,
            attemptId = 43L,
            addressResolver = {
              resolverEntered.countDown()
              var released = false
              while (!released) {
                try {
                  released = releaseResolver.await(25, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                  // Model platform DNS implementations that do not honor interruption.
                }
              }
              InetAddress.getLoopbackAddress()
            },
            resolutionTimeoutMillis = 50,
        )
    val worker = thread(isDaemon = true, name = "timed-resolver-client-test", block = client::run)
    try {
      assertTrue(resolverEntered.await(1, TimeUnit.SECONDS))
      assertEquals(43L, terminal.poll(1, TimeUnit.SECONDS)?.attemptId)
      worker.join(1_000)
      assertFalse(worker.isAlive, "the client attempt must terminate at its resolution deadline")
      assertNull(terminal.poll(), "the late resolver result must remain quarantined")
    } finally {
      releaseResolver.countDown()
      worker.join(1_000)
      bus.close()
    }
  }

  @Test
  fun `socket failure summaries never include exception messages or endpoints`() {
    val failures =
        listOf(
            UnknownHostException("secret.example.test"),
            SocketTimeoutException("192.0.2.20:6688 timed out"),
            ConnectException("Connection to 192.0.2.20:6688 refused"),
            IOException("/home/alice/private-rom.gb"),
        )

    val summaries = failures.map(::netplaySocketFailureSummary)

    assertEquals(
        listOf(
            "host resolution failed",
            "connection timed out",
            "connection refused",
            "network I/O failed",
        ),
        summaries,
    )
    val joined = summaries.joinToString(" ")
    assertFalse(joined.contains("secret.example.test"))
    assertFalse(joined.contains("192.0.2.20"))
    assertFalse(joined.contains("alice"))
  }
}
