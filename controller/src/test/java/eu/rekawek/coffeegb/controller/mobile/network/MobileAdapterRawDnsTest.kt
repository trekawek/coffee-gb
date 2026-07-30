package eu.rekawek.coffeegb.controller.mobile.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.channels.Selector
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class MobileAdapterRawDnsTest {

  @Test
  fun `multiple A records are deduplicated and sorted deterministically`() {
    val addresses =
        listOf(
            byteArrayOf(127, 0, 0, 3),
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(127, 0, 0, 3),
            byteArrayOf(127, 0, 0, 2),
        )
    OneShotDnsFixture { request -> dnsResponse(request, addresses) }.use { dns ->
      val result = resolve(dns, 500)

      val success = assertIs<MobileAdapterDnsResult.Success>(result)
      assertEquals(
          listOf("127.0.0.1", "127.0.0.2", "127.0.0.3"),
          success.addresses.map(::renderAddress),
      )
    }
  }

  @Test
  fun `maximum textual DNS name and maximum wire datagram are accepted`() {
    val maximumName =
        listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61))
            .joinToString(".")
    OneShotDnsFixture { request -> dnsResponse(request, listOf(IPV4_ONE)) }.use { dns ->
      val success = assertIs<MobileAdapterDnsResult.Success>(resolve(dns, 500, maximumName))

      assertEquals(253, maximumName.length)
      assertContentEquals(IPV4_ONE, success.addresses.single().bytes())
    }

    OneShotDnsFixture(::wireBoundaryResponse).use { dns ->
      val success = assertIs<MobileAdapterDnsResult.Success>(resolve(dns, 500))

      assertContentEquals(IPV4_ONE, success.addresses.single().bytes())
    }
  }

  @Test
  fun `distinct address record and compression limits accept boundary and reject boundary plus one`() {
    listOf(
            MobileAdapterRawDnsClient.MAX_ADDRESSES to true,
            MobileAdapterRawDnsClient.MAX_ADDRESSES + 1 to false,
        )
        .forEach { (count, accepted) ->
          val addresses = (1..count).map { byteArrayOf(127, 0, 0, it.toByte()) }
          OneShotDnsFixture { request -> dnsResponse(request, addresses) }.use { dns ->
            val result = resolve(dns, 500)
            if (accepted) {
              assertEquals(count, assertIs<MobileAdapterDnsResult.Success>(result).addresses.size)
            } else {
              assertEquals(
                  MobileAdapterDnsFailure.MALFORMED,
                  assertIs<MobileAdapterDnsResult.Failure>(result).failure,
              )
            }
          }
        }

    listOf(
            MobileAdapterRawDnsClient.MAX_RECORDS to true,
            MobileAdapterRawDnsClient.MAX_RECORDS + 1 to false,
        )
        .forEach { (count, accepted) ->
          OneShotDnsFixture { request -> recordBoundaryResponse(request, count) }.use { dns ->
            val result = resolve(dns, 500)
            if (accepted) {
              assertContentEquals(
                  IPV4_ONE,
                  assertIs<MobileAdapterDnsResult.Success>(result).addresses.single().bytes(),
              )
            } else {
              assertEquals(
                  MobileAdapterDnsFailure.MALFORMED,
                  assertIs<MobileAdapterDnsResult.Failure>(result).failure,
              )
            }
          }
        }

    listOf(
            MobileAdapterRawDnsClient.MAX_POINTER_JUMPS to true,
            MobileAdapterRawDnsClient.MAX_POINTER_JUMPS + 1 to false,
        )
        .forEach { (jumps, accepted) ->
          OneShotDnsFixture { request -> pointerBoundaryResponse(request, jumps) }.use { dns ->
            val result = resolve(dns, 500)
            if (accepted) {
              assertContentEquals(
                  IPV4_ONE,
                  assertIs<MobileAdapterDnsResult.Success>(result).addresses.single().bytes(),
              )
            } else {
              assertEquals(
                  MobileAdapterDnsFailure.MALFORMED,
                  assertIs<MobileAdapterDnsResult.Failure>(result).failure,
              )
            }
          }
        }
  }

  @Test
  fun `CNAME hop boundary succeeds while boundary plus one and a cycle fail`() {
    OneShotDnsFixture { request -> cnameChainResponse(request, MobileAdapterRawDnsClient.MAX_CNAME_HOPS) }
        .use { dns ->
          val success = assertIs<MobileAdapterDnsResult.Success>(resolve(dns, 500))
          assertContentEquals(IPV4_ONE, success.addresses.single().bytes())
        }

    listOf<(ByteArray) -> ByteArray>(
            { request ->
              cnameChainResponse(request, MobileAdapterRawDnsClient.MAX_CNAME_HOPS + 1)
            },
            ::cnameCycleResponse,
        )
        .forEach { response ->
          OneShotDnsFixture(response).use { dns ->
            val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
            assertEquals(MobileAdapterDnsFailure.MALFORMED, failure.failure)
          }
        }
  }

  @Test
  fun `ambiguous A and CNAME owners and duplicate CNAMEs are malformed in every ordering`() {
    listOf(
            Ambiguity.A_THEN_CNAME,
            Ambiguity.CNAME_THEN_A,
            Ambiguity.DUPLICATE_CNAME,
        )
        .forEach { ambiguity ->
          OneShotDnsFixture { request -> ambiguousOwnerResponse(request, ambiguity) }.use { dns ->
            val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
            assertEquals(MobileAdapterDnsFailure.MALFORMED, failure.failure, ambiguity.name)
          }
        }
  }

  @Test
  fun `dots embedded within one wire label are rejected in questions and record owners`() {
    val responses =
        listOf<(ByteArray) -> ByteArray>(
            { request -> hostileQuestionLabelResponse(request) },
            { request -> hostileOwnerLabelResponse(request) },
        )
    responses.forEach { response ->
      OneShotDnsFixture(response).use { dns ->
        val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
        assertEquals(MobileAdapterDnsFailure.MALFORMED, failure.failure)
      }
    }
  }

  @Test
  fun `valid negative and no-data responses are lookup failures rather than malformed packets`() {
    val responses =
        listOf<(ByteArray) -> ByteArray>(
            { request ->
              dnsMessage(request, answers = 0) { _, _ -> }.also {
                setU16(it, 2, 0x8185) // refused
              }
            },
            { request -> dnsMessage(request, answers = 0) { _, _ -> } },
        )
    responses.forEach { response ->
      OneShotDnsFixture(response).use { dns ->
        val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
        assertEquals(MobileAdapterDnsFailure.RESPONSE_FAILURE, failure.failure)
      }
    }
  }

  @Test
  fun `empty truncated and oversized DNS datagrams are rejected as malformed`() {
    val responses =
        listOf<(ByteArray) -> ByteArray>(
            { _ -> ByteArray(0) },
            { request -> request.copyOf(11) },
            { _ -> ByteArray(MobileAdapterRawDnsClient.MAX_WIRE_BYTES + 1) },
        )
    responses.forEach { response ->
      OneShotDnsFixture(response).use { dns ->
        val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
        assertEquals(MobileAdapterDnsFailure.MALFORMED, failure.failure)
      }
    }
  }

  @Test
  fun `mismatched header question oversized RDLENGTH and trailing data are malformed`() {
    val responses =
        listOf<(ByteArray) -> ByteArray>(
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also {
                it[1] = (it[1].toInt() xor 1).toByte()
              }
            },
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also { setU16(it, 2, 0x0180) }
            },
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also { setU16(it, 2, 0x8980) }
            },
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also {
                setU16(it, 2, 0x81c0) // RFC 1035 reserved Z bit must remain zero.
              }
            },
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also { it[13] = 'x'.code.toByte() }
            },
            { request ->
              dnsResponse(request, listOf(IPV4_ONE)).also {
                setU16(it, request.size + 10, 0xffff)
              }
            },
            { request -> dnsResponse(request, listOf(IPV4_ONE)) + 0.toByte() },
        )
    responses.forEach { response ->
      OneShotDnsFixture(response).use { dns ->
        val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 500))
        assertEquals(MobileAdapterDnsFailure.MALFORMED, failure.failure)
      }
    }
  }

  @Test
  fun `a silent configured resolver reaches the bounded timeout`() {
    OneShotDnsFixture { null }.use { dns ->
      val started = System.nanoTime()
      val failure = assertIs<MobileAdapterDnsResult.Failure>(resolve(dns, 100))

      assertEquals(MobileAdapterDnsFailure.TIMEOUT, failure.failure)
      assertTrue(dns.received.await(1, TimeUnit.SECONDS))
      val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
      assertTrue(elapsedMillis in 50..1_000, "elapsed=$elapsedMillis ms")
    }
  }

  @Test
  fun `a closed loopback resolver port is reported as unreachable`() {
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val closedPort = DatagramSocket(0, loopback).use { it.localPort }
    Selector.open().use { selector ->
      val result =
          MobileAdapterRawDnsClient { TRANSACTION_ID }
              .resolve(
                  selector,
                  resolver(closedPort),
                  TARGET_NAME,
                  1_000,
                  MobileAdapterCancellationProbe { true },
              )

      val failure = assertIs<MobileAdapterDnsResult.Failure>(result)
      assertEquals(MobileAdapterDnsFailure.UNREACHABLE, failure.failure)
    }
  }

  @Test
  fun `cancellation wakes an in-flight DNS read without producing a timeout`() {
    OneShotDnsFixture { null }.use { dns ->
      Selector.open().use { selector ->
        val current = AtomicBoolean(true)
        val cancelled = CountDownLatch(1)
        val canceller =
            thread(isDaemon = true, name = "mobile-adapter-test-dns-cancel") {
              assertTrue(dns.received.await(1, TimeUnit.SECONDS))
              current.set(false)
              selector.wakeup()
              cancelled.countDown()
            }

        val result =
            MobileAdapterRawDnsClient { TRANSACTION_ID }
                .resolve(
                    selector,
                    resolver(dns.port),
                    TARGET_NAME,
                    5_000,
                    MobileAdapterCancellationProbe(current::get),
                )

        val failure = assertIs<MobileAdapterDnsResult.Failure>(result)
        assertEquals(MobileAdapterDnsFailure.CANCELLED, failure.failure)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        canceller.join(1_000)
        assertTrue(!canceller.isAlive)
      }
    }
  }

  private fun resolve(
      fixture: OneShotDnsFixture,
      timeoutMillis: Long,
      canonicalName: String = TARGET_NAME,
  ): MobileAdapterDnsResult =
      Selector.open().use { selector ->
        MobileAdapterRawDnsClient { TRANSACTION_ID }
            .resolve(
                selector,
                resolver(fixture.port),
                canonicalName,
                timeoutMillis,
                MobileAdapterCancellationProbe { true },
            )
      }

  private fun resolver(port: Int): MobileAdapterDnsResolver =
      MobileAdapterDnsResolver(MobileAdapterIpv4Address.parse("127.0.0.1"), port)

  private fun renderAddress(address: MobileAdapterIpv4Address): String =
      address.bytes().joinToString(".") { (it.toInt() and 0xff).toString() }

  private class OneShotDnsFixture(
      private val response: (ByteArray) -> ByteArray?,
  ) : AutoCloseable {
    private val socket =
        DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int = socket.localPort
    val received = CountDownLatch(1)
    private val worker =
        thread(isDaemon = true, name = "mobile-adapter-test-raw-dns") {
          try {
            val incoming = DatagramPacket(ByteArray(513), 513)
            socket.receive(incoming)
            received.countDown()
            response(incoming.data.copyOf(incoming.length))?.let { reply ->
              socket.send(DatagramPacket(reply, reply.size, incoming.socketAddress))
            }
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
    private const val TRANSACTION_ID = 0x1234
    private const val TARGET_NAME = "custom-server.example"
    private val IPV4_ONE = byteArrayOf(127, 0, 0, 1)

    private enum class Ambiguity {
      A_THEN_CNAME,
      CNAME_THEN_A,
      DUPLICATE_CNAME,
    }

    private fun dnsResponse(query: ByteArray, addresses: List<ByteArray>): ByteArray {
      return dnsMessage(query, answers = addresses.size) { dns, _ ->
        addresses.forEach { address -> writeARecord(dns, null, address) }
      }
    }

    private fun wireBoundaryResponse(query: ByteArray): ByteArray {
      val response =
          dnsMessage(query, answers = 1, authority = 1) { dns, output ->
            writeARecord(dns, null, IPV4_ONE)
            writeQuestionPointer(dns)
            dns.writeShort(16)
            dns.writeShort(1)
            dns.writeInt(1)
            val dataLength = MobileAdapterRawDnsClient.MAX_WIRE_BYTES - (output.size() + 2)
            dns.writeShort(dataLength)
            dns.write(ByteArray(dataLength))
          }
      check(response.size == MobileAdapterRawDnsClient.MAX_WIRE_BYTES)
      return response
    }

    private fun recordBoundaryResponse(query: ByteArray, totalRecords: Int): ByteArray {
      require(totalRecords >= 1)
      return dnsMessage(query, answers = 1, authority = totalRecords - 1) { dns, _ ->
        writeARecord(dns, null, IPV4_ONE)
        repeat(totalRecords - 1) { writeEmptyRecord(dns) }
      }
    }

    private fun pointerBoundaryResponse(query: ByteArray, pointerJumps: Int): ByteArray {
      require(pointerJumps >= 1)
      return dnsMessage(query, answers = 2) { dns, output ->
        writeQuestionPointer(dns)
        dns.writeShort(16)
        dns.writeShort(1)
        dns.writeInt(1)
        val chainNodes = pointerJumps - 1
        dns.writeShort(chainNodes * 2)
        val chainStart = output.size()
        repeat(chainNodes) { index ->
          val target = if (index + 1 == chainNodes) 12 else chainStart + (index + 1) * 2
          writePointer(dns, target)
        }

        writePointer(dns, if (chainNodes == 0) 12 else chainStart)
        dns.writeShort(1)
        dns.writeShort(1)
        dns.writeInt(1)
        dns.writeShort(4)
        dns.write(IPV4_ONE)
      }
    }

    private fun cnameChainResponse(query: ByteArray, hops: Int): ByteArray {
      require(hops >= 1)
      val aliases = (1..hops).map { "c$it.example" }
      return dnsMessage(query, answers = hops + 1) { dns, _ ->
        repeat(hops) { index ->
          writeCnameRecord(
              dns,
              if (index == 0) null else aliases[index - 1],
              aliases[index],
          )
        }
        writeARecord(dns, aliases.last(), IPV4_ONE)
      }
    }

    private fun cnameCycleResponse(query: ByteArray): ByteArray =
        dnsMessage(query, answers = 2) { dns, _ ->
          writeCnameRecord(dns, null, "cycle.example")
          writeCnameRecord(dns, "cycle.example", TARGET_NAME)
        }

    private fun ambiguousOwnerResponse(query: ByteArray, ambiguity: Ambiguity): ByteArray =
        dnsMessage(query, answers = 2) { dns, _ ->
          when (ambiguity) {
            Ambiguity.A_THEN_CNAME -> {
              writeARecord(dns, null, IPV4_ONE)
              writeCnameRecord(dns, null, "alias.example")
            }
            Ambiguity.CNAME_THEN_A -> {
              writeCnameRecord(dns, null, "alias.example")
              writeARecord(dns, null, IPV4_ONE)
            }
            Ambiguity.DUPLICATE_CNAME -> {
              writeCnameRecord(dns, null, "first.example")
              writeCnameRecord(dns, null, "second.example")
            }
          }
        }

    private fun hostileQuestionLabelResponse(query: ByteArray): ByteArray =
        dnsResponse(query, listOf(IPV4_ONE)).also { response ->
          val collapsed = TARGET_NAME.toByteArray(StandardCharsets.US_ASCII)
          check(collapsed.size == 21)
          response[12] = collapsed.size.toByte()
          collapsed.copyInto(response, destinationOffset = 13)
          response[34] = 0
        }

    private fun hostileOwnerLabelResponse(query: ByteArray): ByteArray =
        dnsMessage(query, answers = 1) { dns, _ ->
          val collapsed = TARGET_NAME.toByteArray(StandardCharsets.US_ASCII)
          dns.writeByte(collapsed.size)
          dns.write(collapsed)
          dns.writeByte(0)
          dns.writeShort(1)
          dns.writeShort(1)
          dns.writeInt(1)
          dns.writeShort(IPV4_ONE.size)
          dns.write(IPV4_ONE)
        }

    private fun dnsMessage(
        query: ByteArray,
        answers: Int,
        authority: Int = 0,
        additional: Int = 0,
        records: (DataOutputStream, ByteArrayOutputStream) -> Unit,
    ): ByteArray {
      val output = ByteArrayOutputStream()
      DataOutputStream(output).use { dns ->
        dns.writeShort(((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff))
        dns.writeShort(0x8180)
        dns.writeShort(1)
        dns.writeShort(answers)
        dns.writeShort(authority)
        dns.writeShort(additional)
        dns.write(query, 12, query.size - 12)
        records(dns, output)
      }
      return output.toByteArray()
    }

    private fun writeARecord(dns: DataOutputStream, owner: String?, address: ByteArray) {
      require(address.size == 4)
      writeOwner(dns, owner)
      dns.writeShort(1)
      dns.writeShort(1)
      dns.writeInt(1)
      dns.writeShort(4)
      dns.write(address)
    }

    private fun writeCnameRecord(dns: DataOutputStream, owner: String?, target: String) {
      writeOwner(dns, owner)
      dns.writeShort(5)
      dns.writeShort(1)
      dns.writeInt(1)
      val encodedTarget = encodedName(target)
      dns.writeShort(encodedTarget.size)
      dns.write(encodedTarget)
    }

    private fun writeEmptyRecord(dns: DataOutputStream) {
      writeQuestionPointer(dns)
      dns.writeShort(16)
      dns.writeShort(1)
      dns.writeInt(1)
      dns.writeShort(0)
    }

    private fun writeOwner(dns: DataOutputStream, owner: String?) {
      if (owner == null) writeQuestionPointer(dns) else dns.write(encodedName(owner))
    }

    private fun writeQuestionPointer(dns: DataOutputStream) = writePointer(dns, 12)

    private fun writePointer(dns: DataOutputStream, offset: Int) {
      require(offset in 0..0x3fff)
      dns.writeShort(0xc000 or offset)
    }

    private fun encodedName(value: String): ByteArray {
      val output = ByteArrayOutputStream()
      DataOutputStream(output).use { dns ->
        value.split('.').forEach { label ->
          val bytes = label.toByteArray(StandardCharsets.US_ASCII)
          require(bytes.size in 1..63)
          dns.writeByte(bytes.size)
          dns.write(bytes)
        }
        dns.writeByte(0)
      }
      return output.toByteArray()
    }

    private fun setU16(bytes: ByteArray, offset: Int, value: Int) {
      bytes[offset] = (value ushr 8).toByte()
      bytes[offset + 1] = value.toByte()
    }
  }
}
