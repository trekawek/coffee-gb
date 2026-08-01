package eu.rekawek.coffeegb.controller.mobile.network

import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendCompletion
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendGeneration
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendRequest
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendStatus
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.OfferResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterNetworkBackendTest {

  @Test
  fun `offline default denies DNS without invoking host IO and status is redacted`() {
    MobileAdapterNetworkBackend().use { backend ->
      val completion = submit(backend, 1, DNS_QUERY, "private-host.example".toByteArray())

      assertEquals(BackendStatus.LOOKUP_FAILED, completion.status())
      assertTrue(completion.payload().isEmpty())
      assertEquals(
          OfferResult.BYTE_LIMIT,
          backend.offer(
              backend.generation(),
              BackendRequest(
                  2,
                  DNS_QUERY,
                  ByteArray(MobileAdapterNetworkBackend.MAX_COMMAND_PAYLOAD_BYTES + 1),
              ),
          ),
      )
      assertEquals(
          BackendStatus.COMMUNICATION_FAILED,
          submit(backend, 3, 0x3f, ByteArray(0)).status(),
      )
      val statuses = drainStatuses(backend)
      assertTrue(statuses.any { it.error == MobileAdapterNetworkError.DESTINATION_DENIED })
      val rendered = statuses.joinToString() + backend.attachmentIdentity
      assertFalse(rendered.contains("private-host"))
      assertFalse(rendered.contains("example"))
    }
  }

  @Test
  fun `literal loopback TCP rule supports bounded open fragmented transfer and close`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 1, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-tcp") {
        try {
          server.accept().use { socket ->
            val request = socket.getInputStream().readNBytes(4)
            if (request.contentEquals("ping".toByteArray())) {
              socket.getOutputStream().write("po".toByteArray())
              socket.getOutputStream().flush()
              Thread.sleep(15)
              socket.getOutputStream().write("ng".toByteArray())
              socket.getOutputStream().flush()
            }
          }
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertContentEquals(byteArrayOf(127, 0, 0, 1), submit(backend, 1, DNS_QUERY, alias()).payload())
        val opened = submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
        assertEquals(BackendStatus.SUCCESS, opened.status())
        assertContentEquals(byteArrayOf(0), opened.payload())

        val received = ByteArrayOutputStream()
        var requestId = 3L
        while (received.size() < 4) {
          val body = if (requestId == 3L) "ping".toByteArray() else ByteArray(0)
          val transfer = submit(backend, requestId++, TRANSFER, byteArrayOf(0) + body)
          assertEquals(BackendStatus.SUCCESS, transfer.status())
          assertEquals(0, transfer.payload().first().toInt())
          received.write(transfer.payload(), 1, transfer.payload().size - 1)
        }
        assertContentEquals("pong".toByteArray(), received.toByteArray())

        val closed = submit(backend, requestId, TCP_CLOSE, byteArrayOf(0))
        assertEquals(BackendStatus.SUCCESS, closed.status())
        assertFalse(backend.hasExternalWork())
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `literal loopback UDP rule pins peer and enforces transfer boundary`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      val received = LinkedBlockingQueue<ByteArray>()
      thread(isDaemon = true, name = "mobile-adapter-test-udp") {
        try {
          val incoming = DatagramPacket(ByteArray(512), 512)
          server.receive(incoming)
          val response = incoming.data.copyOf(incoming.length)
          received.add(response)
          server.send(DatagramPacket(response, response.size, incoming.socketAddress))
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        val opened = submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53))
        assertEquals(BackendStatus.SUCCESS, opened.status())
        val boundary = ByteArray(MobileAdapterNetworkBackend.MAX_TRANSFER_BODY_BYTES) { it.toByte() }
        val transfer = submit(backend, 2, TRANSFER, byteArrayOf(0) + boundary)
        assertEquals(BackendStatus.SUCCESS, transfer.status())
        assertContentEquals(byteArrayOf(0) + boundary, transfer.payload())
        assertContentEquals(boundary, received.poll(2, TimeUnit.SECONDS))
        assertEquals(
            OfferResult.BYTE_LIMIT,
            backend.offer(
                backend.generation(),
                BackendRequest(3, TRANSFER, byteArrayOf(0) + ByteArray(boundary.size + 1)),
            ),
        )
        assertTrue(
            drainStatuses(backend).any {
              it.error == MobileAdapterNetworkError.TRANSFER_LIMIT &&
                  it.activeConnections == 1
            })
        assertEquals(BackendStatus.SUCCESS, submit(backend, 4, UDP_CLOSE, byteArrayOf(0)).status())
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `zero-length UDP response is a successful empty transfer rather than a timeout`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-empty-udp") {
        try {
          val incoming = DatagramPacket(ByteArray(16), 16)
          server.receive(incoming)
          server.send(DatagramPacket(ByteArray(0), 0, incoming.socketAddress))
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53)).status(),
        )
        val transfer = submit(backend, 2, TRANSFER, byteArrayOf(0, 1))
        assertEquals(BackendStatus.SUCCESS, transfer.status())
        assertContentEquals(byteArrayOf(0), transfer.payload())
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 3, UDP_CLOSE, byteArrayOf(0)).status(),
        )
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `oversized UDP response is rejected while preserving the consumed datagram slot`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-oversized-udp") {
        try {
          val incoming = DatagramPacket(ByteArray(16), 16)
          server.receive(incoming)
          val oversized = ByteArray(MobileAdapterNetworkBackend.MAX_TRANSFER_BODY_BYTES + 1)
          server.send(DatagramPacket(oversized, oversized.size, incoming.socketAddress))
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53)).status(),
        )
        val transfer = submit(backend, 2, TRANSFER, byteArrayOf(0, 1))
        assertEquals(BackendStatus.COMMUNICATION_FAILED, transfer.status())
        assertTrue(transfer.payload().isEmpty())
        assertTrue(
            drainStatuses(backend).any {
              it.error == MobileAdapterNetworkError.TRANSFER_LIMIT &&
                  it.slot == 0 &&
                  it.activeConnections == 1
            })
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 3, UDP_CLOSE, byteArrayOf(0)).status(),
        )
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `two connections are admitted and a third is rejected without opening a socket`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        val first = submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53))
        assertEquals(BackendStatus.SUCCESS, first.status())
        assertContentEquals(byteArrayOf(0), first.payload())
        val second = submit(backend, 2, UDP_OPEN, openPayload(127, 0, 0, 1, 53))
        assertEquals(BackendStatus.SUCCESS, second.status())
        assertContentEquals(byteArrayOf(1), second.payload())

        val rejected = submit(backend, 3, UDP_OPEN, openPayload(127, 0, 0, 1, 53))
        assertEquals(BackendStatus.CONNECTION_LIMIT, rejected.status())
        assertTrue(
            drainStatuses(backend).any {
              it.error == MobileAdapterNetworkError.CONNECTION_LIMIT &&
                  it.phase == MobileAdapterNetworkPhase.CONNECTED &&
                  it.activeConnections == 2
            })

        assertEquals(BackendStatus.SUCCESS, submit(backend, 4, UDP_CLOSE, byteArrayOf(0)).status())
        assertEquals(BackendStatus.SUCCESS, submit(backend, 5, UDP_CLOSE, byteArrayOf(1)).status())
        assertFalse(backend.hasExternalWork())
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `loopback TCP refusal is typed and does not retain a connection`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val refusedPort = ServerSocket(0, 1, loopback).use { it.localPort }
    val backend =
        MobileAdapterNetworkBackend(
            literalPolicy(MobileAdapterTransportProtocol.TCP, 80, refusedPort),
            MobileAdapterRuntimeAuthorization(true, true),
        )
    try {
      val refused = submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
      assertEquals(BackendStatus.CONNECTION_FAILED, refused.status())
      assertTrue(drainStatuses(backend).any { it.error == MobileAdapterNetworkError.CONNECTION_REFUSED })
      assertFalse(backend.hasExternalWork())
    } finally {
      backend.close()
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `TCP peer close is delivered once and releases the connection`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 1, loopback).use { server ->
      val peerClosed = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-peer-close") {
        server.accept().use {}
        peerClosed.countDown()
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).status(),
        )
        assertTrue(peerClosed.await(2, TimeUnit.SECONDS))

        val transfer = submit(backend, 2, TRANSFER, byteArrayOf(0))
        assertEquals(BackendStatus.REMOTE_CLOSED, transfer.status())
        assertTrue(drainStatuses(backend).any { it.error == MobileAdapterNetworkError.REMOTE_CLOSED })
        assertFalse(backend.hasExternalWork())
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `large TCP response is delivered across bounded transfers`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 1, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      val response = ByteArray(MobileAdapterNetworkBackend.MAX_TRANSFER_BODY_BYTES + 1) { it.toByte() }
      thread(isDaemon = true, name = "mobile-adapter-test-chunked-tcp") {
        try {
          server.accept().use { socket ->
            socket.getInputStream().readNBytes(1)
            socket.getOutputStream().write(response)
            socket.getOutputStream().flush()
          }
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).status(),
        )
        val received = ByteArrayOutputStream()
        var requestId = 2L
        while (received.size() < response.size) {
          val body = if (requestId == 2L) byteArrayOf(1) else ByteArray(0)
          val transfer = submit(backend, requestId++, TRANSFER, byteArrayOf(0) + body)
          assertEquals(BackendStatus.SUCCESS, transfer.status())
          assertEquals(0, transfer.payload().first().toInt())
          assertTrue(transfer.payload().size <= MobileAdapterNetworkBackend.MAX_COMMAND_PAYLOAD_BYTES)
          received.write(transfer.payload(), 1, transfer.payload().size - 1)
        }
        assertContentEquals(response, received.toByteArray())
        assertEquals(
            BackendStatus.REMOTE_CLOSED,
            submit(backend, requestId, TRANSFER, byteArrayOf(0)).status(),
        )
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `one TCP peer close identifies its slot and leaves the second slot usable`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 2, loopback).use { server ->
      val bothAccepted = CountDownLatch(1)
      val closeFirst = CountDownLatch(1)
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-two-tcp") {
        try {
          server.accept().use { first ->
            server.accept().use { second ->
              bothAccepted.countDown()
              closeFirst.await(2, TimeUnit.SECONDS)
              first.close()
              val request = second.getInputStream().readNBytes(4)
              if (request.contentEquals("ping".toByteArray())) {
                second.getOutputStream().write("pong".toByteArray())
                second.getOutputStream().flush()
              }
            }
          }
        } finally {
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertContentEquals(
            byteArrayOf(0),
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).payload(),
        )
        assertContentEquals(
            byteArrayOf(1),
            submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).payload(),
        )
        assertTrue(bothAccepted.await(2, TimeUnit.SECONDS))
        drainStatuses(backend)
        closeFirst.countDown()

        assertEquals(
            BackendStatus.REMOTE_CLOSED,
            submit(backend, 3, TRANSFER, byteArrayOf(0)).status(),
        )
        val closed =
            drainStatuses(backend).last {
              it.error == MobileAdapterNetworkError.REMOTE_CLOSED
            }
        assertEquals(0, closed.slot)
        assertEquals(1, closed.activeConnections)

        val surviving = submit(backend, 4, TRANSFER, byteArrayOf(1) + "ping".toByteArray())
        assertEquals(BackendStatus.SUCCESS, surviving.status())
        assertContentEquals(byteArrayOf(1) + "pong".toByteArray(), surviving.payload())
        assertEquals(BackendStatus.SUCCESS, submit(backend, 5, TCP_CLOSE, byteArrayOf(1)).status())
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
        assertFalse(backend.hasExternalWork())
      } finally {
        closeFirst.countDown()
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `TCP final payload and EOF are delivered before one remote-close tombstone`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 2, loopback).use { server ->
      val firstClosed = CountDownLatch(1)
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-final-eof") {
        try {
          server.accept().use { first ->
            first.getOutputStream().write("final".toByteArray())
            first.getOutputStream().flush()
          }
          firstClosed.countDown()
          server.accept().use { second ->
            if (second.getInputStream().readNBytes(4).contentEquals("ping".toByteArray())) {
              second.getOutputStream().write("pong".toByteArray())
              second.getOutputStream().flush()
            }
          }
        } finally {
          firstClosed.countDown()
          serverDone.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).status(),
        )
        assertTrue(firstClosed.await(2, TimeUnit.SECONDS))
        drainStatuses(backend)

        val finalPayload = submit(backend, 2, TRANSFER, byteArrayOf(0))
        assertEquals(BackendStatus.SUCCESS, finalPayload.status())
        assertContentEquals(byteArrayOf(0) + "final".toByteArray(), finalPayload.payload())
        val eofStatus =
            drainStatuses(backend).last {
              it.error == MobileAdapterNetworkError.REMOTE_CLOSED
            }
        assertEquals(0, eofStatus.slot)
        assertEquals(0, eofStatus.activeConnections)

        assertEquals(
            BackendStatus.REMOTE_CLOSED,
            submit(backend, 3, TRANSFER, byteArrayOf(0)).status(),
        )
        assertContentEquals(
            byteArrayOf(0),
            submit(backend, 4, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).payload(),
        )
        val reopened = submit(backend, 5, TRANSFER, byteArrayOf(0) + "ping".toByteArray())
        assertEquals(BackendStatus.SUCCESS, reopened.status())
        assertContentEquals(byteArrayOf(0) + "pong".toByteArray(), reopened.payload())
        assertEquals(BackendStatus.SUCCESS, submit(backend, 6, TCP_CLOSE, byteArrayOf(0)).status())
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `cancellation during a TCP read drops the stale response and closes the peer`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 1, loopback).use { server ->
      val accepted = CountDownLatch(1)
      val peerObservedClose = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-cancel-read") {
        server.accept().use { socket ->
          accepted.countDown()
          if (socket.getInputStream().read() < 0) peerObservedClose.countDown()
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).status(),
        )
        assertTrue(accepted.await(2, TimeUnit.SECONDS))
        val generation = backend.generation()
        assertEquals(
            OfferResult.ACCEPTED,
            backend.offer(generation, BackendRequest(2, TRANSFER, byteArrayOf(0))),
        )
        awaitStatus(backend) { it.phase == MobileAdapterNetworkPhase.TRANSFERRING }

        backend.cancelAll()

        assertNull(backend.poll(generation))
        assertEquals(0, backend.occupiedRequestSlots())
        val newGenerationTransfer = submit(backend, 3, TRANSFER, byteArrayOf(0) + "leak".toByteArray())
        assertEquals(BackendStatus.INVALID_CONNECTION, newGenerationTransfer.status())
        assertTrue(peerObservedClose.await(2, TimeUnit.SECONDS))
        awaitStatus(backend) {
          it.phase == MobileAdapterNetworkPhase.IDLE &&
              it.error == MobileAdapterNetworkError.NONE
        }
        awaitCondition { !backend.hasExternalWork() }
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `silent TCP read returns empty success and preserves its slot`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    ServerSocket(0, 1, loopback).use { server ->
      val requestReceived = CountDownLatch(1)
      val releasePeer = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-tcp-timeout") {
        server.accept().use { socket ->
          socket.getInputStream().readNBytes(4)
          requestReceived.countDown()
          releasePeer.await(3, TimeUnit.SECONDS)
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.TCP, 80, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, TCP_OPEN, openPayload(127, 0, 0, 1, 80)).status(),
        )
        val pending = submit(backend, 2, TRANSFER, byteArrayOf(0) + "ping".toByteArray())
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
        assertEquals(BackendStatus.SUCCESS, pending.status())
        assertContentEquals(byteArrayOf(0), pending.payload())
        assertEquals(BackendStatus.SUCCESS, submit(backend, 3, TCP_CLOSE, byteArrayOf(0)).status())
      } finally {
        releasePeer.countDown()
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `silent UDP read returns typed timeout and releases its slot`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val requestReceived = CountDownLatch(1)
      val releasePeer = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-udp-timeout") {
        try {
          server.receive(DatagramPacket(ByteArray(512), 512))
          requestReceived.countDown()
          releasePeer.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
          // Fixture close owns termination.
        }
      }
      val backend =
          MobileAdapterNetworkBackend(
              literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
              MobileAdapterRuntimeAuthorization(true, true),
          )
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53)).status(),
        )
        val timedOut = submit(backend, 2, TRANSFER, byteArrayOf(0) + "ping".toByteArray())
        assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
        assertEquals(BackendStatus.REMOTE_CLOSED, timedOut.status())
        assertTrue(
            drainStatuses(backend).any {
              it.error == MobileAdapterNetworkError.TIMEOUT &&
                  it.slot == 0 &&
                  it.activeConnections == 0
            })
        assertFalse(backend.hasExternalWork())
      } finally {
        releasePeer.countDown()
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `completed responses retain bounded slots and are delivered in request order`() {
    val backend =
        MobileAdapterNetworkBackend(
            literalPolicy(MobileAdapterTransportProtocol.TCP, 80, 9),
            MobileAdapterRuntimeAuthorization(true, true),
        )
    try {
      val generation = backend.generation()
      repeat(8) { id ->
        assertEquals(
            OfferResult.ACCEPTED,
            backend.offer(generation, BackendRequest(id.toLong(), DNS_QUERY, alias())),
        )
      }
      assertEquals(
          OfferResult.REQUEST_LIMIT,
          backend.offer(generation, BackendRequest(8, DNS_QUERY, alias())),
      )
      assertTrue(backend.hasExternalWork())

      val first = awaitCompletion(backend, generation)
      assertEquals(0, first.requestId())
      assertContentEquals(byteArrayOf(127, 0, 0, 1), first.payload())
      assertEquals(
          OfferResult.ACCEPTED,
          backend.offer(generation, BackendRequest(8, DNS_QUERY, alias())),
      )

      val remainder = (1L..8L).map { awaitCompletion(backend, generation) }
      assertEquals((1L..8L).toList(), remainder.map(BackendCompletion::requestId))
      remainder.forEach { assertContentEquals(byteArrayOf(127, 0, 0, 1), it.payload()) }
      assertEquals(0, backend.occupiedRequestSlots())
      assertEquals(0, backend.bufferedBytes())
      assertFalse(backend.hasExternalWork())
    } finally {
      backend.close()
      assertTrue(backend.awaitTermination(2_000))
    }
  }

  @Test
  fun `raw DNS resolves only configured transport name and rebinding blocks socket open`() {
    DnsFixture(
            listOf(
                listOf(byteArrayOf(127, 0, 0, 1)),
                listOf(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())),
            ))
        .use { dns ->
          val target = MobileAdapterTransportTarget.parse("custom-server.example")
          val policy =
              MobileAdapterDestinationPolicy(
                  1,
                  MobileAdapterDnsResolver(
                      MobileAdapterIpv4Address.parse("127.0.0.1"),
                      dns.port,
                  ),
                  listOf(
                      MobileAdapterDestinationRule(
                          "historical-game.example",
                          target,
                          MobileAdapterTransportProtocol.TCP,
                          80,
                          9,
                      )),
              )
          val backend =
              MobileAdapterNetworkBackend(
                  policy,
                  MobileAdapterRuntimeAuthorization(true, true),
              )
          try {
            val lookup = submit(backend, 1, DNS_QUERY, "historical-game.example\u0000".toByteArray())
            assertEquals(BackendStatus.SUCCESS, lookup.status())
            assertContentEquals(byteArrayOf(127, 0, 0, 1), lookup.payload())

            val opened = submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
            assertEquals(BackendStatus.CONNECTION_FAILED, opened.status())
            assertEquals(
                listOf("custom-server.example", "custom-server.example"),
                listOf(assertNotNull(dns.queries.poll(2, TimeUnit.SECONDS)), assertNotNull(dns.queries.poll(2, TimeUnit.SECONDS))),
            )
            assertFalse(drainStatuses(backend).joinToString().contains("historical-game"))
          } finally {
            backend.close()
            assertTrue(backend.awaitTermination(2_000))
          }
        }
  }

  @Test
  fun `fresh allowed DNS answer must still contain the requested address before socket open`() {
    DnsFixture(
            listOf(
                listOf(byteArrayOf(127, 0, 0, 1)),
                listOf(byteArrayOf(127, 0, 0, 2)),
            ))
        .use { dns ->
          val target = MobileAdapterTransportTarget.parse("custom-server.example")
          val policy =
              MobileAdapterDestinationPolicy(
                  1,
                  MobileAdapterDnsResolver(
                      MobileAdapterIpv4Address.parse("127.0.0.1"),
                      dns.port,
                  ),
                  listOf(
                      MobileAdapterDestinationRule(
                          "guest.example",
                          target,
                          MobileAdapterTransportProtocol.TCP,
                          80,
                          9,
                      )),
              )
          val backend =
              MobileAdapterNetworkBackend(
                  policy,
                  MobileAdapterRuntimeAuthorization(true, true),
              )
          try {
            assertContentEquals(
                byteArrayOf(127, 0, 0, 1),
                submit(backend, 1, DNS_QUERY, alias("guest.example")).payload(),
            )
            val opened = submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
            assertEquals(BackendStatus.CONNECTION_FAILED, opened.status())
            assertTrue(
                drainStatuses(backend).any {
                  it.error == MobileAdapterNetworkError.DESTINATION_DENIED
                })
            assertEquals(
                listOf("custom-server.example", "custom-server.example"),
                listOf(
                    dns.queries.poll(2, TimeUnit.SECONDS),
                    dns.queries.poll(2, TimeUnit.SECONDS),
                ),
            )
            assertFalse(backend.hasExternalWork())
          } finally {
            backend.close()
            assertTrue(backend.awaitTermination(2_000))
          }
        }
  }

  @Test
  fun `all allowed multi-address answers admit a non-first address after fresh validation`() {
    // Keep the address that is actually opened on the universally bindable IPv4 loopback.
    // The resolver sorts 10.0.0.1 first, so 127.0.0.1 still exercises a non-first answer.
    val firstAddress = byteArrayOf(10, 0, 0, 1)
    val secondAddress = byteArrayOf(127, 0, 0, 1)
    val secondLoopback = InetAddress.getByAddress(secondAddress)
    ServerSocket(0, 1, secondLoopback).use { server ->
      val accepted = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-multi-address") {
        try {
          server.accept().use { accepted.countDown() }
        } catch (_: Exception) {
          // Fixture close owns termination.
        }
      }
      DnsFixture(
              listOf(
                  listOf(secondAddress, firstAddress),
                  listOf(secondAddress, firstAddress),
              ))
          .use { dns ->
            val target = MobileAdapterTransportTarget.parse("multi-address.example")
            val policy =
                MobileAdapterDestinationPolicy(
                    1,
                    MobileAdapterDnsResolver(
                        MobileAdapterIpv4Address.parse("127.0.0.1"),
                        dns.port,
                    ),
                    listOf(
                        MobileAdapterDestinationRule(
                            "guest.example",
                            target,
                            MobileAdapterTransportProtocol.TCP,
                            80,
                            server.localPort,
                        )),
                )
            val backend =
                MobileAdapterNetworkBackend(
                    policy,
                    MobileAdapterRuntimeAuthorization(true, true),
                )
            try {
              val lookup = submit(backend, 1, DNS_QUERY, alias("guest.example"))
              assertEquals(BackendStatus.SUCCESS, lookup.status())
              assertContentEquals(firstAddress, lookup.payload())

              val opened = submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
              assertEquals(BackendStatus.SUCCESS, opened.status())
              assertContentEquals(byteArrayOf(0), opened.payload())
              assertTrue(accepted.await(2, TimeUnit.SECONDS))
              assertEquals(
                  listOf("multi-address.example", "multi-address.example"),
                  listOf(
                      dns.queries.poll(2, TimeUnit.SECONDS),
                      dns.queries.poll(2, TimeUnit.SECONDS),
                  ),
              )
              assertEquals(
                  BackendStatus.SUCCESS,
                  submit(backend, 3, TCP_CLOSE, byteArrayOf(0)).status(),
              )
            } finally {
              backend.close()
              assertTrue(backend.awaitTermination(2_000))
            }
          }
    }
  }

  @Test
  fun `mixed allowed and denied fresh answers fail closed without opening a socket`() {
    val loopback = byteArrayOf(127, 0, 0, 1)
    val otherAllowed = byteArrayOf(127, 0, 0, 2)
    val hardDenied = byteArrayOf(169.toByte(), 254.toByte(), 1, 1)
    ServerSocket(0, 1, InetAddress.getByAddress(loopback)).use { server ->
      server.soTimeout = 250
      DnsFixture(
              listOf(
                  listOf(loopback, otherAllowed),
                  listOf(loopback, hardDenied),
              ))
          .use { dns ->
            val target = MobileAdapterTransportTarget.parse("mixed-address.example")
            val policy =
                MobileAdapterDestinationPolicy(
                    1,
                    MobileAdapterDnsResolver(
                        MobileAdapterIpv4Address.parse("127.0.0.1"),
                        dns.port,
                    ),
                    listOf(
                        MobileAdapterDestinationRule(
                            "guest.example",
                            target,
                            MobileAdapterTransportProtocol.TCP,
                            80,
                            server.localPort,
                        )),
                )
            val backend =
                MobileAdapterNetworkBackend(
                    policy,
                    MobileAdapterRuntimeAuthorization(true, true),
                )
            try {
              val lookup = submit(backend, 1, DNS_QUERY, alias("guest.example"))
              assertEquals(BackendStatus.SUCCESS, lookup.status())
              assertContentEquals(loopback, lookup.payload())

              val opened = submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 80))
              assertEquals(BackendStatus.CONNECTION_FAILED, opened.status())
              assertTrue(
                  drainStatuses(backend).any {
                    it.error == MobileAdapterNetworkError.DESTINATION_DENIED
                  })
              assertEquals(
                  listOf("mixed-address.example", "mixed-address.example"),
                  listOf(
                      dns.queries.poll(2, TimeUnit.SECONDS),
                      dns.queries.poll(2, TimeUnit.SECONDS),
                  ),
              )
              assertFailsWith<SocketTimeoutException> { server.accept() }
              assertFalse(backend.hasExternalWork())
            } finally {
              backend.close()
              assertTrue(backend.awaitTermination(2_000))
            }
          }
    }
  }

  @Test
  fun `DNS capability is scoped to the exact guest alias`() {
    DnsFixture(listOf(listOf(byteArrayOf(127, 0, 0, 1)))).use { dns ->
      val target = MobileAdapterTransportTarget.parse("shared-transport.example")
      val policy =
          MobileAdapterDestinationPolicy(
              1,
              MobileAdapterDnsResolver(MobileAdapterIpv4Address.parse("127.0.0.1"), dns.port),
              listOf(
                  MobileAdapterDestinationRule(
                      "first-guest.example",
                      target,
                      MobileAdapterTransportProtocol.TCP,
                      80,
                      9,
                  ),
                  MobileAdapterDestinationRule(
                      "second-guest.example",
                      target,
                      MobileAdapterTransportProtocol.TCP,
                      81,
                      9,
                  ),
              ),
          )
      val backend =
          MobileAdapterNetworkBackend(policy, MobileAdapterRuntimeAuthorization(true, true))
      try {
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, DNS_QUERY, alias("first-guest.example")).status(),
        )
        assertEquals(
            BackendStatus.CONNECTION_FAILED,
            submit(backend, 2, TCP_OPEN, openPayload(127, 0, 0, 1, 81)).status(),
        )
        assertEquals("shared-transport.example", dns.queries.poll(2, TimeUnit.SECONDS))
        assertNull(dns.queries.poll(50, TimeUnit.MILLISECONDS))
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
      }
    }
  }

  @Test
  fun `bounded request ownership rejects ninth item and cancellation is generation atomic`() {
    SilentDnsFixture().use { dns ->
      val target = MobileAdapterTransportTarget.parse("custom-server.example")
      val policy =
          MobileAdapterDestinationPolicy(
              1,
              MobileAdapterDnsResolver(MobileAdapterIpv4Address.parse("127.0.0.1"), dns.port),
              listOf(
                  MobileAdapterDestinationRule(
                      "guest.example",
                      target,
                      MobileAdapterTransportProtocol.TCP,
                      80,
                  )),
          )
      val backend =
          MobileAdapterNetworkBackend(policy, MobileAdapterRuntimeAuthorization(true, true))
      try {
        val generation = backend.generation()
        assertEquals(OfferResult.ACCEPTED, backend.offer(generation, BackendRequest(0, DNS_QUERY, alias("guest.example"))))
        assertTrue(dns.received.await(2, TimeUnit.SECONDS))
        for (id in 1L..7L) {
          assertEquals(
              OfferResult.ACCEPTED,
              backend.offer(generation, BackendRequest(id, DNS_QUERY, alias("guest.example"))),
          )
        }
        assertEquals(
            OfferResult.REQUEST_LIMIT,
            backend.offer(generation, BackendRequest(8, DNS_QUERY, alias("guest.example"))),
        )
        assertEquals(8, backend.occupiedRequestSlots())

        backend.cancelAll()
        assertEquals(0, backend.occupiedRequestSlots())
        assertEquals(0, backend.bufferedBytes())
        assertNull(backend.poll(generation))
        assertEquals(
            OfferResult.STALE_GENERATION,
            backend.offer(generation, BackendRequest(9, DNS_QUERY, alias("guest.example"))),
        )
      } finally {
        backend.close()
        assertTrue(backend.awaitTermination(2_000))
        assertFalse(backend.hasExternalWork())
      }
    }
  }

  @Test
  fun `ten real UDP backend lifecycles terminate owner loops and sockets without retained work`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DatagramSocket(0, loopback).use { server ->
      val serverDone = CountDownLatch(1)
      thread(isDaemon = true, name = "mobile-adapter-test-ten-udp-cycles") {
        try {
          repeat(10) {
            val incoming = DatagramPacket(ByteArray(512), 512)
            server.receive(incoming)
            server.send(
                DatagramPacket(
                    incoming.data,
                    incoming.length,
                    incoming.socketAddress,
                ))
          }
        } finally {
          serverDone.countDown()
        }
      }
      repeat(10) { cycle ->
        val backend =
            MobileAdapterNetworkBackend(
                literalPolicy(MobileAdapterTransportProtocol.UDP, 53, server.localPort),
                MobileAdapterRuntimeAuthorization(true, true),
            )
        assertEquals(
            BackendStatus.SUCCESS,
            submit(backend, 1, UDP_OPEN, openPayload(127, 0, 0, 1, 53)).status(),
        )
        val body = byteArrayOf(cycle.toByte())
        assertContentEquals(
            byteArrayOf(0) + body,
            submit(backend, 2, TRANSFER, byteArrayOf(0) + body).payload(),
        )
        assertEquals(BackendStatus.SUCCESS, submit(backend, 3, UDP_CLOSE, byteArrayOf(0)).status())
        backend.close()
        backend.close()
        assertTrue(backend.awaitTermination(2_000), "cycle $cycle")
        backend.cancelAll()
        assertFalse(backend.hasExternalWork(), "cycle $cycle")
        val terminal = drainStatuses(backend).lastOrNull()
        assertEquals(MobileAdapterNetworkPhase.CLOSED, terminal?.phase)
      }
      assertTrue(serverDone.await(2, TimeUnit.SECONDS))
    }
    awaitCondition {
      Thread.getAllStackTraces().keys.none {
        it.isAlive && it.name.startsWith("mobile-adapter-network-")
      }
    }
  }

  @Test
  fun `concurrent cancellation and close never regress status ownership or overwrite terminal close`() {
    repeat(10) { cycle ->
      val backend = MobileAdapterNetworkBackend()
      val observed = Collections.synchronizedList(mutableListOf<MobileAdapterNetworkStatus>())
      val collector =
          thread(isDaemon = true, name = "mobile-adapter-test-status-collector-$cycle") {
            while (true) {
              val status = backend.pollStatus()
              if (status != null) {
                observed += status
              } else if (backend.isTerminated()) {
                while (true) observed += backend.pollStatus() ?: break
                break
              } else {
                Thread.yield()
              }
            }
          }
      val start = CountDownLatch(1)
      val cancellers =
          List(4) { worker ->
            thread(isDaemon = true, name = "mobile-adapter-test-cancel-$cycle-$worker") {
              start.await()
              repeat(100) { backend.cancelAll() }
            }
          }
      val closer =
          thread(isDaemon = true, name = "mobile-adapter-test-close-$cycle") {
            start.await()
            backend.close()
          }
      start.countDown()
      cancellers.forEach { it.join(2_000) }
      closer.join(2_000)
      backend.close()
      assertTrue(backend.awaitTermination(2_000), "cycle $cycle")
      backend.cancelAll()
      collector.join(2_000)
      assertFalse(collector.isAlive)
      assertTrue(observed.isNotEmpty())
      observed.zipWithNext().forEach { (before, after) ->
        assertTrue(
            before.ownershipVersion <= after.ownershipVersion,
            "status ownership regressed in cycle $cycle: $before then $after",
        )
      }
      assertEquals(MobileAdapterNetworkPhase.CLOSED, observed.last().phase)
    }
  }

  private fun literalPolicy(
      protocol: MobileAdapterTransportProtocol,
      guestPort: Int,
      targetPort: Int,
  ): MobileAdapterDestinationPolicy =
      MobileAdapterDestinationPolicy(
          1,
          null,
          listOf(
              MobileAdapterDestinationRule(
                  "game.service",
                  MobileAdapterTransportTarget.parse("127.0.0.1"),
                  protocol,
                  guestPort,
                  targetPort,
              )),
      )

  private fun submit(
      backend: MobileAdapterNetworkBackend,
      requestId: Long,
      command: Int,
      payload: ByteArray,
  ): BackendCompletion {
    val generation = backend.generation()
    assertEquals(OfferResult.ACCEPTED, backend.offer(generation, BackendRequest(requestId, command, payload)))
    return awaitCompletion(backend, generation)
  }

  private fun awaitCompletion(
      backend: MobileAdapterNetworkBackend,
      generation: BackendGeneration,
  ): BackendCompletion {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
    while (System.nanoTime() < deadline) {
      backend.poll(generation)?.let { return it }
      Thread.sleep(2)
    }
    throw AssertionError("backend completion timed out")
  }

  private fun awaitStatus(
      backend: MobileAdapterNetworkBackend,
      predicate: (MobileAdapterNetworkStatus) -> Boolean,
  ): MobileAdapterNetworkStatus {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (System.nanoTime() < deadline) {
      backend.pollStatus()?.let { if (predicate(it)) return it }
      Thread.sleep(2)
    }
    throw AssertionError("backend status timed out")
  }

  private fun alias(value: String = "game.service"): ByteArray =
      value.toByteArray(StandardCharsets.US_ASCII)

  private fun openPayload(a: Int, b: Int, c: Int, d: Int, port: Int): ByteArray =
      byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte(), (port ushr 8).toByte(), port.toByte())

  private fun drainStatuses(backend: MobileAdapterNetworkBackend): List<MobileAdapterNetworkStatus> =
      buildList {
        while (true) add(backend.pollStatus() ?: break)
      }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!condition()) {
      if (System.nanoTime() >= deadline) throw AssertionError("condition timed out")
      Thread.sleep(5)
    }
  }

  private class DnsFixture(private val answersByQuery: List<List<ByteArray>>) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val queries = LinkedBlockingQueue<String>()
    val port: Int = socket.localPort
    private val worker =
        thread(isDaemon = true, name = "mobile-adapter-test-dns") {
          try {
            answersByQuery.forEach { addresses ->
              val request = DatagramPacket(ByteArray(513), 513)
              socket.receive(request)
              queries.add(parseQueryName(request.data, request.length))
              val response = dnsResponse(request.data.copyOf(request.length), addresses)
              socket.send(DatagramPacket(response, response.size, request.socketAddress))
            }
          } catch (_: Exception) {
            // Fixture close owns termination.
          }
        }

    override fun close() {
      socket.close()
      worker.join(2_000)
    }
  }

  private class SilentDnsFixture : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int = socket.localPort
    val received = CountDownLatch(1)
    private val worker =
        thread(isDaemon = true, name = "mobile-adapter-test-silent-dns") {
          try {
            socket.receive(DatagramPacket(ByteArray(513), 513))
            received.countDown()
            while (!socket.isClosed) Thread.sleep(10)
          } catch (_: Exception) {
            // Fixture close owns termination.
          }
        }

    override fun close() {
      socket.close()
      worker.interrupt()
      worker.join(2_000)
    }
  }

  companion object {
    private const val TRANSFER = 0x15
    private const val TCP_OPEN = 0x23
    private const val TCP_CLOSE = 0x24
    private const val UDP_OPEN = 0x25
    private const val UDP_CLOSE = 0x26
    private const val DNS_QUERY = 0x28

    private fun parseQueryName(bytes: ByteArray, length: Int): String {
      var offset = 12
      val labels = mutableListOf<String>()
      while (offset < length) {
        val labelLength = bytes[offset++].toInt() and 0xff
        if (labelLength == 0) break
        labels.add(String(bytes, offset, labelLength, StandardCharsets.US_ASCII))
        offset += labelLength
      }
      return labels.joinToString(".")
    }

    private fun dnsResponse(query: ByteArray, addresses: List<ByteArray>): ByteArray {
      require(addresses.isNotEmpty())
      addresses.forEach { require(it.size == 4) }
      var questionEnd = 12
      while (query[questionEnd].toInt() != 0) {
        questionEnd += 1 + (query[questionEnd].toInt() and 0xff)
      }
      questionEnd += 5
      val output = ByteArrayOutputStream()
      DataOutputStream(output).use { dns ->
        dns.writeShort(((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff))
        dns.writeShort(0x8180)
        dns.writeShort(1)
        dns.writeShort(addresses.size)
        dns.writeShort(0)
        dns.writeShort(0)
        dns.write(query, 12, questionEnd - 12)
        addresses.forEach { address ->
          dns.writeShort(0xc00c)
          dns.writeShort(1)
          dns.writeShort(1)
          dns.writeInt(1)
          dns.writeShort(4)
          dns.write(address)
        }
      }
      return output.toByteArray()
    }
  }
}
