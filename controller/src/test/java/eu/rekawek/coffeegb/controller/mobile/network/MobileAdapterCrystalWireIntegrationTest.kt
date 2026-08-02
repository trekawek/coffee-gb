package eu.rekawek.coffeegb.controller.mobile.network

import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Synthetic full wire-to-owner-loop fixture for the command shape modeled for Crystal. */
class MobileAdapterCrystalWireIntegrationTest {

  @Test
  fun `synthetic crystal-shaped sequence drains hello response and consumes remote close`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    DnsFixture(loopback).use { dns ->
      ServerSocket(0, 1, loopback).use { server ->
        val expectedRequest =
            "GET /mobile-adapter/fixture/index.txt HTTP/1.0\r\n" +
                "Host: crystal.service.test\r\n\r\n"
        val body = "<p>hello world</p>\n".ascii()
        val expectedResponse =
            ("HTTP/1.0 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII) + body
        val serverFailure = AtomicReference<Throwable?>()
        val serverDone = CountDownLatch(1)
        val receivedRequest = LinkedBlockingQueue<ByteArray>()
        thread(isDaemon = true, name = "mobile-adapter-http-fixture") {
          try {
            server.accept().use { socket ->
              val request = readHttpRequest(socket.getInputStream())
              receivedRequest.add(request)
              socket.getOutputStream().write(expectedResponse)
              socket.getOutputStream().flush()
            }
          } catch (failure: Throwable) {
            serverFailure.set(failure)
          } finally {
            serverDone.countDown()
          }
        }

        val policy =
            MobileAdapterDestinationPolicy(
                1,
                MobileAdapterDnsResolver(MobileAdapterIpv4Address.parse("127.0.0.1"), dns.port),
                listOf(
                    MobileAdapterDestinationRule(
                        "crystal.service.test",
                        MobileAdapterTransportTarget.parse("http.fixture.test"),
                        MobileAdapterTransportProtocol.TCP,
                        8080,
                        server.localPort,
                    )),
            )
        val backend =
            MobileAdapterNetworkBackend(
                policy,
                MobileAdapterRuntimeAuthorization(true, true),
            )
        try {
          val endpoint =
              MobileAdapterSerialEndpoint(
                  ClockSpec.LEGACY,
                  DEVICE_ID,
                  configuration(),
                  backend.port,
              )

          assertResponse(endpoint, 0x10, "NINTENDO".ascii(), 0x90, "NINTENDO".ascii())
          assertResponse(endpoint, 0x17, EMPTY_BYTES, 0x97, byteArrayOf(0, 0x4d, 0))
          assertResponse(endpoint, 0x12, byteArrayOf(0, '#'.code.toByte(), '9'.code.toByte(),
              '6'.code.toByte(), '7'.code.toByte(), '7'.code.toByte()), 0x92, EMPTY_BYTES)
          assertResponse(
              endpoint,
              0x21,
              byteArrayOf(0, 0, 127, 0, 0, 1, 127, 0, 0, 1),
              0xa1,
              byteArrayOf(127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0),
          )
          assertResponse(endpoint, 0x17, EMPTY_BYTES, 0x97, byteArrayOf(4, 0x4d, 0))
          assertResponse(
              endpoint,
              0x28,
              "crystal.service.test".ascii(),
              0xa8,
              byteArrayOf(127, 0, 0, 1),
          )
          assertResponse(
              endpoint,
              0x23,
              byteArrayOf(127, 0, 0, 1, 0x1f, 0x90.toByte()),
              0xa3,
              byteArrayOf(0),
          )

          val receivedResponse = ByteArrayOutputStream()
          var requestBody = expectedRequest.ascii()
          while (receivedResponse.size() < expectedResponse.size) {
            val transfer = transact(endpoint, 0x15, byteArrayOf(0) + requestBody)
            assertEquals(0x95, transfer.command)
            assertTrue(transfer.data.isNotEmpty())
            assertEquals(0, transfer.data[0].toInt())
            receivedResponse.write(transfer.data, 1, transfer.data.size - 1)
            requestBody = EMPTY_BYTES
          }
          assertContentEquals(expectedResponse, receivedResponse.toByteArray())
          assertContentEquals(
              expectedRequest.ascii(),
              assertNotNull(receivedRequest.poll(2, TimeUnit.SECONDS)),
          )

          assertResponse(endpoint, 0x15, byteArrayOf(0), 0x9f, EMPTY_BYTES)
          assertFalse(backend.hasExternalWork())
          assertResponse(endpoint, 0x22, EMPTY_BYTES, 0xa2, EMPTY_BYTES)
          assertResponse(endpoint, 0x13, EMPTY_BYTES, 0x93, EMPTY_BYTES)
          assertResponse(endpoint, 0x11, EMPTY_BYTES, 0x91, EMPTY_BYTES)

          assertEquals(listOf("http.fixture.test", "http.fixture.test"), dns.queries.toList())
          assertTrue(serverDone.await(2, TimeUnit.SECONDS))
          assertNull(serverFailure.get())
        } finally {
          backend.close()
          assertTrue(backend.awaitTermination(2_000))
        }
      }
    }
  }

  private fun assertResponse(
      endpoint: MobileAdapterSerialEndpoint,
      requestCommand: Int,
      requestData: ByteArray,
      responseCommand: Int,
      responseData: ByteArray,
  ) {
    val response = transact(endpoint, requestCommand, requestData)
    assertEquals(responseCommand, response.command)
    assertContentEquals(responseData, response.data)
  }

  private fun transact(
      endpoint: MobileAdapterSerialEndpoint,
      command: Int,
      data: ByteArray,
  ): Response {
    for (requestByte in packet(command, data)) {
      assertEquals(IDLE_BYTE, exchange(endpoint, requestByte.toInt() and 0xff))
    }
    assertEquals(DEVICE_ID or 0x80, exchange(endpoint, 0x80))
    assertEquals(command xor 0x80, exchange(endpoint, 0))

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
    var first = IDLE_BYTE
    while (first == IDLE_BYTE && System.nanoTime() < deadline) {
      first = exchange(endpoint, POLL_BYTE)
      if (first == IDLE_BYTE) Thread.sleep(2)
    }
    assertEquals(0x99, first, "response poll timed out for command ${command.toString(16)}")

    val header = ByteArray(6)
    header[0] = first.toByte()
    for (index in 1 until header.size) header[index] = exchange(endpoint, POLL_BYTE).toByte()
    assertEquals(0x66, header[1].toInt() and 0xff)
    val responseCommand = header[2].toInt() and 0xff
    assertEquals(0, header[3].toInt() and 0xff)
    val length = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
    val responsePacket = ByteArray(length + 8)
    header.copyInto(responsePacket)
    for (index in header.size until responsePacket.size) {
      responsePacket[index] = exchange(endpoint, POLL_BYTE).toByte()
    }
    assertContentEquals(packet(responseCommand, responsePacket.copyOfRange(6, 6 + length)), responsePacket)

    assertEquals(DEVICE_ID or 0x80, exchange(endpoint, 0x80))
    assertEquals(0, exchange(endpoint, responseCommand xor 0x80))
    return Response(responseCommand, responsePacket.copyOfRange(6, 6 + length))
  }

  private fun exchange(endpoint: MobileAdapterSerialEndpoint, outgoing: Int): Int {
    endpoint.setSb(outgoing)
    endpoint.startSending()
    var incoming = 0
    repeat(8) { incoming = incoming shl 1 or endpoint.sendBit() }
    return incoming
  }

  private fun packet(command: Int, data: ByteArray): ByteArray {
    val result = ByteArray(data.size + 8)
    result[0] = 0x99.toByte()
    result[1] = 0x66
    result[2] = command.toByte()
    result[4] = (data.size ushr 8).toByte()
    result[5] = data.size.toByte()
    data.copyInto(result, 6)
    var checksum = 0
    for (index in 2 until 6 + data.size) checksum = checksum + (result[index].toInt() and 0xff) and 0xffff
    result[6 + data.size] = (checksum ushr 8).toByte()
    result[7 + data.size] = checksum.toByte()
    return result
  }

  private fun configuration(): ByteArray =
      ByteArray(256).also {
        it[0] = 0x4d
        it[1] = 0x41
        it[2] = 0x81.toByte()
      }

  private class DnsFixture(private val answer: InetAddress) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int = socket.localPort
    val queries = LinkedBlockingQueue<String>()
    private val worker =
        thread(isDaemon = true, name = "mobile-adapter-crystal-dns-fixture") {
          try {
            repeat(2) {
              val request = DatagramPacket(ByteArray(513), 513)
              socket.receive(request)
              queries.add(parseQueryName(request.data, request.length))
              val response = dnsResponse(request.data.copyOf(request.length), answer.address)
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

  private data class Response(val command: Int, val data: ByteArray)

  companion object {
    private const val DEVICE_ID = 0x08
    private const val IDLE_BYTE = 0xd2
    private const val POLL_BYTE = 0x4b
    private val EMPTY_BYTES = ByteArray(0)

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun readHttpRequest(input: java.io.InputStream): ByteArray {
      val output = ByteArrayOutputStream()
      var suffix = 0
      while (suffix != 0x0d0a0d0a) {
        val value = input.read()
        if (value < 0) break
        output.write(value)
        suffix = suffix shl 8 or value
      }
      return output.toByteArray()
    }

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

    private fun dnsResponse(query: ByteArray, answer: ByteArray): ByteArray {
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
        dns.writeShort(1)
        dns.writeShort(0)
        dns.writeShort(0)
        dns.write(query, 12, questionEnd - 12)
        dns.writeShort(0xc00c)
        dns.writeShort(1)
        dns.writeShort(1)
        dns.writeInt(1)
        dns.writeShort(answer.size)
        dns.write(answer)
      }
      return output.toByteArray()
    }
  }
}
