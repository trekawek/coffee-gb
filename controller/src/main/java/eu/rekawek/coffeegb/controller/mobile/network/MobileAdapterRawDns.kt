package eu.rekawek.coffeegb.controller.mobile.network

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

internal enum class MobileAdapterDnsFailure {
  CANCELLED,
  TIMEOUT,
  MALFORMED,
  RESPONSE_FAILURE,
  UNREACHABLE,
}

internal sealed interface MobileAdapterDnsResult {
  class Success(addresses: List<MobileAdapterIpv4Address>) : MobileAdapterDnsResult {
    val addresses: List<MobileAdapterIpv4Address> = addresses.toList()
  }

  class Failure(val failure: MobileAdapterDnsFailure) : MobileAdapterDnsResult
}

internal fun interface MobileAdapterCancellationProbe {
  fun isCurrent(): Boolean
}

/**
 * Bounded raw UDP A-record client used only for an explicitly configured literal resolver.
 *
 * The caller owns the selector and worker thread. No system resolver, guest-provided DNS server,
 * reverse lookup, blocking socket operation, or retry is used.
 */
internal class MobileAdapterRawDnsClient(
    private val transactionId: () -> Int = {
      ThreadLocalRandom.current().nextInt(0x1_0000)
    },
) {
  fun resolve(
      selector: Selector,
      resolver: MobileAdapterDnsResolver,
      canonicalName: String,
      timeoutMillis: Long,
      cancellation: MobileAdapterCancellationProbe,
  ): MobileAdapterDnsResult {
    require(timeoutMillis > 0) { "DNS timeout must be positive" }
    val queryId = transactionId() and 0xffff
    val query = encodeQuery(queryId, canonicalName)
    val deadline = saturatedDeadline(timeoutMillis)
    var channel: DatagramChannel? = null
    return try {
      if (!cancellation.isCurrent()) return MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.CANCELLED)
      channel = DatagramChannel.open()
      channel.configureBlocking(false)
      channel.connect(InetSocketAddress(resolver.address.inetAddress(), resolver.port))
      var key = channel.register(selector, SelectionKey.OP_WRITE)
      val output = ByteBuffer.wrap(query)
      while (output.hasRemaining()) {
        if (!cancellation.isCurrent()) {
          return MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.CANCELLED)
        }
        val written = channel.write(output)
        if (written == 0 && !awaitReady(selector, key, SelectionKey.OP_WRITE, deadline, cancellation)) {
          return timeoutOrCancellation(cancellation)
        }
      }

      key.interestOps(SelectionKey.OP_READ)
      val response = ByteBuffer.allocate(MAX_WIRE_BYTES + 1)
      while (true) {
        if (!cancellation.isCurrent()) {
          return MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.CANCELLED)
        }
        // read() returns zero both when no datagram is ready and when it consumes a legitimate
        // zero-length datagram. receive() preserves that distinction so the latter is classified
        // immediately as a malformed DNS response instead of a timeout.
        val sender = channel.receive(response)
        if (sender != null) {
          if (response.position() > MAX_WIRE_BYTES) {
            return MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.MALFORMED)
          }
          return decodeResponse(response.array(), response.position(), queryId, canonicalName)
        }
        if (!awaitReady(selector, key, SelectionKey.OP_READ, deadline, cancellation)) {
          return timeoutOrCancellation(cancellation)
        }
      }
      @Suppress("UNREACHABLE_CODE")
      MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.MALFORMED)
    } catch (_: IOException) {
      if (cancellation.isCurrent()) {
        MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.UNREACHABLE)
      } else {
        MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.CANCELLED)
      }
    } catch (_: RuntimeException) {
      MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.MALFORMED)
    } finally {
      try {
        channel?.close()
      } catch (_: IOException) {
        // The typed operation result already owns presentation.
      }
    }
  }

  private fun decodeResponse(
      storage: ByteArray,
      length: Int,
      expectedId: Int,
      expectedName: String,
  ): MobileAdapterDnsResult {
    if (length < DNS_HEADER_BYTES || length > MAX_WIRE_BYTES) return malformed()
    return try {
      val reader = DnsReader(storage.copyOf(length))
      val id = reader.u16(0)
      val flags = reader.u16(2)
      val questions = reader.u16(4)
      val answers = reader.u16(6)
      val authority = reader.u16(8)
      val additional = reader.u16(10)
      val responseCode = flags and 0x000f
      val totalRecords = Math.addExact(Math.addExact(answers, authority), additional)
      if (id != expectedId ||
          flags and 0x8000 == 0 ||
          flags and 0x7800 != 0 ||
          flags and 0x0200 != 0 ||
          flags and 0x0040 != 0 ||
          questions != 1 ||
          totalRecords > MAX_RECORDS) {
        return malformed()
      }

      var cursor = DNS_HEADER_BYTES
      val question = reader.name(cursor)
      cursor = question.next
      val questionType = reader.u16(cursor)
      val questionClass = reader.u16(cursor + 2)
      cursor = Math.addExact(cursor, 4)
      if (question.value != expectedName || questionType != TYPE_A || questionClass != CLASS_IN) {
        return malformed()
      }

      val cnames = linkedMapOf<String, String>()
      val addressesByName = linkedMapOf<String, MutableList<MobileAdapterIpv4Address>>()
      repeat(answers) {
        val recordName = reader.name(cursor)
        cursor = recordName.next
        val type = reader.u16(cursor)
        val recordClass = reader.u16(cursor + 2)
        // TTL is intentionally not cached. Every open performs a fresh query.
        reader.u32(cursor + 4)
        val dataLength = reader.u16(cursor + 8)
        val dataStart = Math.addExact(cursor, 10)
        val dataEnd = Math.addExact(dataStart, dataLength)
        reader.requireRange(dataStart, dataLength)
        if (recordClass == CLASS_IN && type == TYPE_A) {
          if (dataLength != 4) return malformed()
          if (recordName.value in cnames) return malformed()
          addressesByName
              .getOrPut(recordName.value) { mutableListOf() }
              .add(MobileAdapterIpv4Address.fromBytes(reader.copy(dataStart, 4)))
        } else if (recordClass == CLASS_IN && type == TYPE_CNAME) {
          val target = reader.name(dataStart)
          if (target.next != dataEnd) return malformed()
          if (recordName.value in addressesByName || recordName.value in cnames) {
            return malformed()
          }
          cnames[recordName.value] = target.value
        }
        cursor = dataEnd
      }
      repeat(Math.addExact(authority, additional)) {
        val recordName = reader.name(cursor)
        cursor = recordName.next
        val dataLength = reader.u16(cursor + 8)
        cursor = Math.addExact(cursor, Math.addExact(10, dataLength))
        reader.requireRange(cursor, 0)
      }
      if (cursor != length) return malformed()
      if (responseCode != 0) return responseFailure()

      var name = expectedName
      var hops = 0
      val seenNames = mutableSetOf<String>()
      while (seenNames.add(name)) {
        val addresses = addressesByName[name]
        if (!addresses.isNullOrEmpty()) {
          val distinct = addresses.distinct().sortedBy(MobileAdapterIpv4Address::unsignedBits)
          if (distinct.size > MAX_ADDRESSES) return malformed()
          return MobileAdapterDnsResult.Success(distinct)
        }
        name = cnames[name] ?: return responseFailure()
        if (++hops > MAX_CNAME_HOPS) return malformed()
      }
      malformed()
    } catch (_: ArithmeticException) {
      malformed()
    } catch (_: IllegalArgumentException) {
      malformed()
    } catch (_: IndexOutOfBoundsException) {
      malformed()
    }
  }

  private fun encodeQuery(id: Int, canonicalName: String): ByteArray {
    val labels = canonicalName.split('.')
    val encodedNameBytes = labels.sumOf { Math.addExact(1, it.length) }
    val size = Math.addExact(DNS_HEADER_BYTES + 4, encodedNameBytes + 1)
    require(size <= MAX_WIRE_BYTES) { "DNS query exceeds the wire limit" }
    val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    output.putShort(id.toShort())
    output.putShort(0x0100.toShort()) // recursion desired; no other flags
    output.putShort(1.toShort())
    output.putShort(0.toShort())
    output.putShort(0.toShort())
    output.putShort(0.toShort())
    for (label in labels) {
      output.put(label.length.toByte())
      for (character in label) output.put(character.code.toByte())
    }
    output.put(0.toByte())
    output.putShort(TYPE_A.toShort())
    output.putShort(CLASS_IN.toShort())
    return output.array()
  }

  private fun awaitReady(
      selector: Selector,
      key: SelectionKey,
      operation: Int,
      deadline: Long,
      cancellation: MobileAdapterCancellationProbe,
  ): Boolean {
    while (cancellation.isCurrent()) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) return false
      val millis =
          minOf(
              MAX_SELECTOR_SLEEP_MILLIS,
              maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining)),
          )
      selector.select(millis)
      val ready = key.isValid && key.readyOps() and operation != 0
      selector.selectedKeys().clear()
      if (ready) return true
    }
    return false
  }

  private fun saturatedDeadline(timeoutMillis: Long): Long {
    val delta = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val now = System.nanoTime()
    return try {
      Math.addExact(now, delta)
    } catch (_: ArithmeticException) {
      Long.MAX_VALUE
    }
  }

  private fun timeoutOrCancellation(
      cancellation: MobileAdapterCancellationProbe
  ): MobileAdapterDnsResult.Failure =
      MobileAdapterDnsResult.Failure(
          if (cancellation.isCurrent()) MobileAdapterDnsFailure.TIMEOUT
          else MobileAdapterDnsFailure.CANCELLED)

  private fun malformed(): MobileAdapterDnsResult.Failure =
      MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.MALFORMED)

  private fun responseFailure(): MobileAdapterDnsResult.Failure =
      MobileAdapterDnsResult.Failure(MobileAdapterDnsFailure.RESPONSE_FAILURE)

  companion object {
    const val MAX_WIRE_BYTES = 512
    const val MAX_RECORDS = 32
    const val MAX_POINTER_JUMPS = 16
    const val MAX_CNAME_HOPS = 4
    const val MAX_ADDRESSES = 8
    private const val DNS_HEADER_BYTES = 12
    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val CLASS_IN = 1
    private const val MAX_SELECTOR_SLEEP_MILLIS = 100L
  }

  private class DnsReader(private val bytes: ByteArray) {
    fun u16(offset: Int): Int {
      requireRange(offset, 2)
      return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    fun u32(offset: Int): Long {
      requireRange(offset, 4)
      return ((bytes[offset].toLong() and 0xff) shl 24) or
          ((bytes[offset + 1].toLong() and 0xff) shl 16) or
          ((bytes[offset + 2].toLong() and 0xff) shl 8) or
          (bytes[offset + 3].toLong() and 0xff)
    }

    fun copy(offset: Int, length: Int): ByteArray {
      requireRange(offset, length)
      return bytes.copyOfRange(offset, offset + length)
    }

    fun requireRange(offset: Int, length: Int) {
      require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
        "DNS field exceeds message bounds"
      }
    }

    fun name(start: Int): DnsName {
      requireRange(start, 1)
      var cursor = start
      var next = -1
      var jumps = 0
      var expandedBytes = 0
      val labels = mutableListOf<String>()
      val visited = mutableSetOf<Int>()
      while (true) {
        requireRange(cursor, 1)
        val length = bytes[cursor].toInt() and 0xff
        when {
          length == 0 -> {
            if (next < 0) next = cursor + 1
            break
          }
          length and 0xc0 == 0xc0 -> {
            requireRange(cursor, 2)
            val pointer = ((length and 0x3f) shl 8) or (bytes[cursor + 1].toInt() and 0xff)
            require(pointer < bytes.size && visited.add(pointer) && ++jumps <= MAX_POINTER_JUMPS) {
              "DNS compression pointer is invalid"
            }
            if (next < 0) next = cursor + 2
            cursor = pointer
          }
          length and 0xc0 != 0 || length > 63 ->
              throw IllegalArgumentException("DNS label length is invalid")
          else -> {
            requireRange(cursor + 1, length)
            val label = buildString(length) {
              repeat(length) {
                val value = bytes[cursor + 1 + it].toInt() and 0xff
                require(value in 0x21..0x7e) { "DNS label is not printable ASCII" }
                append(value.toChar())
              }
            }
            require(isStrictDnsLabel(label)) { "DNS label is not a strict LDH label" }
            labels.add(label)
            expandedBytes = Math.addExact(expandedBytes, Math.addExact(length, 1))
            // A 253-character textual host uses 254 non-root wire bytes because each label has a
            // length octet. The following zero root octet is consumed separately above.
            require(expandedBytes <= 254) { "Expanded DNS name exceeds 254 bytes" }
            cursor = Math.addExact(cursor, Math.addExact(1, length))
          }
        }
      }
      require(labels.isNotEmpty()) { "DNS root name is not a custom-server query" }
      return DnsName(canonicalMobileAdapterHost(labels.joinToString(".")), next)
    }

    private fun isStrictDnsLabel(label: String): Boolean {
      fun isAsciiLetterOrDigit(character: Char): Boolean =
          character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9'
      return label.isNotEmpty() &&
          isAsciiLetterOrDigit(label.first()) &&
          isAsciiLetterOrDigit(label.last()) &&
          label.all { isAsciiLetterOrDigit(it) || it == '-' }
    }
  }

  private data class DnsName(val value: String, val next: Int)
}
