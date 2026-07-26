package eu.rekawek.coffeegb.controller.network.v9

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

class V9ProtocolException(
    val reason: V9ErrorCode,
    val decisiveBytes: Int,
    message: String = reason.wireName,
) : Exception(message)

data class V9QueueSnapshot(
    val frames: Long,
    val wireBytes: Long,
    val decodedBytes: Long,
)

/** Session-wide retained-frame budget. A reservation must be closed exactly once. */
class V9QueueBudget(
    private val maximumFrames: Long = V9Limit.QUEUED_FRAMES.value,
    private val maximumWireBytes: Long = V9Limit.QUEUED_WIRE_BYTES.value,
    private val maximumDecodedBytes: Long = V9Limit.SESSION_DECODED_AGGREGATE_BYTES.value,
    initialFrames: Long = 0,
    initialWireBytes: Long = 0,
    initialDecodedBytes: Long = 0,
) {
  private var frames = initialFrames
  private var wireBytes = initialWireBytes
  private var decodedBytes = initialDecodedBytes

  init {
    require(initialFrames >= 0 && initialWireBytes >= 0 && initialDecodedBytes >= 0)
    require(initialFrames <= maximumFrames)
    require(initialWireBytes <= maximumWireBytes)
    require(initialDecodedBytes <= maximumDecodedBytes)
  }

  @Synchronized
  fun canReserve(additionalWireBytes: Long, additionalDecodedBytes: Long): Boolean =
      admissionError(additionalWireBytes, additionalDecodedBytes) == null

  @Synchronized
  fun admissionError(
      additionalWireBytes: Long,
      additionalDecodedBytes: Long,
  ): V9ErrorCode? =
      evaluateAdmission(
          V9QueueSnapshot(frames, wireBytes, decodedBytes),
          V9QueueSnapshot(1, additionalWireBytes, additionalDecodedBytes),
          maximumFrames,
          maximumWireBytes,
          maximumDecodedBytes,
      )

  @Synchronized
  fun reserve(additionalWireBytes: Long, additionalDecodedBytes: Long): Reservation? {
    if (admissionError(additionalWireBytes, additionalDecodedBytes) != null) return null
    frames = Math.addExact(frames, 1)
    wireBytes = Math.addExact(wireBytes, additionalWireBytes)
    decodedBytes = Math.addExact(decodedBytes, additionalDecodedBytes)
    return Reservation(this, additionalWireBytes, additionalDecodedBytes)
  }

  @Synchronized
  fun snapshot(): V9QueueSnapshot = V9QueueSnapshot(frames, wireBytes, decodedBytes)

  @Synchronized
  private fun release(wire: Long, decoded: Long) {
    frames = Math.subtractExact(frames, 1)
    wireBytes = Math.subtractExact(wireBytes, wire)
    decodedBytes = Math.subtractExact(decodedBytes, decoded)
  }

  class Reservation internal constructor(
      private val owner: V9QueueBudget,
      val wireBytes: Long,
      val decodedBytes: Long,
  ) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
      if (!closed) {
        closed = true
        owner.release(wireBytes, decodedBytes)
      }
    }
  }

  companion object {
    fun evaluateAdmission(
        current: V9QueueSnapshot,
        additional: V9QueueSnapshot,
        maximumFrames: Long = V9Limit.QUEUED_FRAMES.value,
        maximumWireBytes: Long = V9Limit.QUEUED_WIRE_BYTES.value,
        maximumDecodedBytes: Long = V9Limit.SESSION_DECODED_AGGREGATE_BYTES.value,
    ): V9ErrorCode? {
      if (listOf(
              current.frames,
              current.wireBytes,
              current.decodedBytes,
              additional.frames,
              additional.wireBytes,
              additional.decodedBytes,
          ).any { it < 0 }) {
        return V9ErrorCode.MALFORMED_HEADER
      }
      val nextFrames: Long
      val nextWire: Long
      val nextDecoded: Long
      try {
        nextFrames = Math.addExact(current.frames, additional.frames)
        nextWire = Math.addExact(current.wireBytes, additional.wireBytes)
        nextDecoded = Math.addExact(current.decodedBytes, additional.decodedBytes)
      } catch (_: ArithmeticException) {
        return V9ErrorCode.LIMIT_EXCEEDED
      }
      if (nextFrames > maximumFrames || nextWire > maximumWireBytes) {
        return V9ErrorCode.QUEUE_OVERFLOW
      }
      if (nextDecoded > maximumDecodedBytes) return V9ErrorCode.LIMIT_EXCEEDED
      return null
    }
  }
}

class V9FrameHeader(
    val typeId: Int,
    val type: V9MessageType?,
    val flags: Int,
    val sequence: Long,
    val correlation: Long,
    val encodedLength: Long,
    val decodedLength: Long,
    val channel: Long,
    digest: ByteArray,
) {
  private val ownedDigest = digest.copyOf()

  fun digest(): ByteArray = ownedDigest.copyOf()
}

class V9Frame internal constructor(
    val header: V9FrameHeader,
    payload: ByteArray,
    private val reservation: V9QueueBudget.Reservation? = null,
) : Closeable {
  // The incremental decoder transfers sole ownership of this already-bounded array.
  private val ownedPayload = payload

  fun payload(): ByteArray = ownedPayload.copyOf()

  internal fun payloadView(): ByteArray = ownedPayload

  override fun close() {
    reservation?.close()
  }
}

data class V9DecodeBatch(
    val frames: List<V9Frame>,
    val needsMore: Boolean,
    val failure: V9ProtocolException?,
    val consumedBytes: Long,
    val retainedBytes: Int,
    val payloadAllocations: Int,
    val payloadReservations: Int,
    val directionExhausted: Boolean,
)

class V9DecoderPolicy(
    allowedMessages: Set<V9MessageType> =
        setOf(V9MessageType.HELLO, V9MessageType.ERROR),
    negotiatedCapabilities: Set<V9Capability> = V9Capability.entries.toSet(),
    val linkMode: V9LinkMode = V9LinkMode.NORMAL,
    val allowUnknownOptional: Boolean = false,
) {
  val allowedMessages: Set<V9MessageType> =
      Collections.unmodifiableSet(allowedMessages.toSet())
  val negotiatedCapabilities: Set<V9Capability> =
      Collections.unmodifiableSet(negotiatedCapabilities.toSet())
}

/**
 * Incremental one-frame-at-a-time decoder.
 *
 * It retains one fixed header and at most one declared payload. Header precedence is evaluated at
 * the frozen decisive byte and the queue budget is checked before the payload allocation.
 */
class V9IncrementalDecoder(
    initialSequence: Long = 0,
    private val budget: V9QueueBudget = V9QueueBudget(),
    private val policy: V9DecoderPolicy = V9DecoderPolicy(),
) {
  private val headerBytes = ByteArray(ProtocolV9.HEADER_BYTES)
  private var headerCount = 0
  private var header: V9FrameHeader? = null
  private var encodedPayload: ByteArray? = null
  private var payloadCount = 0
  private var expectedSequence = validateInitialSequence(initialSequence)
  private var terminal = false
  private var failure: V9ProtocolException? = null
  private var consumed = 0L
  private var allocations = 0
  private var reservations = 0
  private var completedFrames = 0
  private var currentReservation: V9QueueBudget.Reservation? = null

  fun feed(bytes: ByteArray): V9DecodeBatch = feed(bytes, 0, bytes.size)

  fun feed(bytes: ByteArray, offset: Int, length: Int): V9DecodeBatch {
    require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
    val completed = mutableListOf<V9Frame>()
    var input = offset
    val end = offset + length
    while (input < end && failure == null) {
      if (terminal) {
        consumed = Math.addExact(consumed, 1)
        fail(V9ErrorCode.TRAILING_DATA, consumed.toIntBounded())
        break
      }
      if (headerCount < ProtocolV9.HEADER_BYTES) {
        headerBytes[headerCount++] = bytes[input++]
        consumed = Math.addExact(consumed, 1)
        val early = headerFailure(headerBytes, headerCount)
        if (early != null) {
          fail(early, consumed.toIntBounded())
          break
        }
        if (headerCount == ProtocolV9.HEADER_BYTES) {
          val parsed = parseHeader(headerBytes)
          val wireBytes = checkedWireBytes(parsed.encodedLength)
              ?: return failed(completed, V9ErrorCode.LIMIT_EXCEEDED, 32)
          val reservation = budget.reserve(wireBytes, parsed.decodedLength)
              ?: return failed(completed, V9ErrorCode.QUEUE_OVERFLOW, 32)
          currentReservation = reservation
          reservations++
          header = parsed
          encodedPayload = ByteArray(parsed.encodedLength.toInt())
          allocations++
          payloadCount = 0
          if (parsed.encodedLength == 0L) {
            completeFrame(completed)
          }
        }
        continue
      }

      val payload = checkNotNull(encodedPayload)
      val copy = minOf(end - input, payload.size - payloadCount)
      System.arraycopy(bytes, input, payload, payloadCount, copy)
      input += copy
      payloadCount += copy
      consumed = Math.addExact(consumed, copy.toLong())
      if (payloadCount == payload.size) {
        completeFrame(completed)
      }
    }
    return snapshot(completed)
  }

  fun finish(): V9DecodeBatch {
    if (failure == null && (headerCount != 0 || header != null)) {
      fail(V9ErrorCode.TRUNCATED, consumed.toIntBounded())
    } else if (failure == null && completedFrames == 0) {
      fail(V9ErrorCode.TRUNCATED, consumed.toIntBounded())
    } else if (failure == null && !terminal) {
      fail(V9ErrorCode.UNEXPECTED_EOF, consumed.toIntBounded())
    }
    return snapshot(emptyList())
  }

  fun snapshot(): V9DecodeBatch = snapshot(emptyList())

  fun queueSnapshot(): V9QueueSnapshot = budget.snapshot()

  private fun completeFrame(completed: MutableList<V9Frame>) {
    val currentHeader = checkNotNull(header)
    val encoded = checkNotNull(encodedPayload)
    val decoded = if (currentHeader.flags has V9Flag.DEFLATE) {
      inflateExact(encoded, currentHeader.decodedLength.toInt())
          ?: return fail(V9ErrorCode.DECOMPRESSION_FAILED, consumed.toIntBounded())
    } else {
      encoded
    }
    if (!MessageDigest.isEqual(currentHeader.digest(), sha256(decoded))) {
      return fail(V9ErrorCode.CHECKSUM_MISMATCH, consumed.toIntBounded())
    }

    val payloadFailure = validateFoundationPayload(currentHeader, decoded)
    if (payloadFailure != null) {
      return fail(payloadFailure, consumed.toIntBounded())
    }
    if (currentHeader.type == null) {
      currentReservation?.close()
    } else {
      completed += V9Frame(currentHeader, decoded, currentReservation)
    }
    completedFrames++
    currentReservation = null
    if (expectedSequence == ProtocolV9.LAST_SEQUENCE) {
      expectedSequence = ProtocolV9.EXHAUSTED_SEQUENCE
    } else {
      expectedSequence = Math.addExact(expectedSequence, 1)
    }
    terminal = currentHeader.flags has V9Flag.TERMINAL
    resetFrame()
  }

  private fun validateFoundationPayload(
      header: V9FrameHeader,
      payload: ByteArray,
  ): V9ErrorCode? = when (header.type) {
    V9MessageType.HELLO -> V9HelloCodec.validate(payload)
    // AUTH has an exact header length. Slot/reserved/proof validation deliberately runs in the
    // authenticated lifecycle handler so every malformed proof receives the same AUTH_RESULT.
    V9MessageType.AUTH -> null
    V9MessageType.AUTH_RESULT -> V9AuthCodec.validateAuthResult(payload, header.flags)
    V9MessageType.CANCEL,
    V9MessageType.GOODBYE -> V9TerminalPayloadCodec.validate(payload)
    V9MessageType.ERROR -> V9ErrorPayloadCodec.validate(header.flags, payload)
    else -> null
  }

  private fun headerFailure(bytes: ByteArray, count: Int): V9ErrorCode? {
    if (count >= 4 && !bytes.copyOfRange(0, 4).contentEquals(ProtocolV9.MAGIC)) {
      return V9ErrorCode.UNSUPPORTED_PROTOCOL
    }
    if (count >= 6 &&
        ((bytes[4].toInt() and 0xff) != ProtocolV9.MAJOR ||
            (bytes[5].toInt() and 0xff) != ProtocolV9.MINOR)) {
      return V9ErrorCode.UNSUPPORTED_PROTOCOL
    }
    if (count >= 8 && u16(bytes, 6) != ProtocolV9.HEADER_BYTES) {
      return V9ErrorCode.MALFORMED_HEADER
    }
    if (count < 12) return null

    val type = V9MessageType.fromWireId(u16(bytes, 8))
    val flags = u16(bytes, 10)
    if (type == null && flags != V9Flag.OPTIONAL.wireMask) {
      return V9ErrorCode.UNKNOWN_REQUIRED_TYPE
    }
    if (type != null) {
      val spec = type.spec
      if (flags and V9Flag.KNOWN_MASK.inv() != 0 ||
          flags and spec.allowedFlags.inv() != 0 ||
          flags and spec.requiredFlags != spec.requiredFlags ||
          flags has V9Flag.DEFLATE && spec.compression != V9Compression.RAW_DEFLATE) {
        return V9ErrorCode.UNKNOWN_REQUIRED_FLAG
      }
    }
    if (count < 32) return null

    val sequence = u32(bytes, 12)
    val correlation = u32(bytes, 16)
    val encodedLength = u32(bytes, 20)
    val decodedLength = u32(bytes, 24)
    val channel = u32(bytes, 28)
    if (type == null) {
      if (encodedLength != decodedLength ||
          encodedLength > V9Limit.UNKNOWN_OPTIONAL_BYTES.value ||
          channel != ProtocolV9.CONTROL_CHANNEL ||
          correlation != 0L) {
        return V9ErrorCode.UNKNOWN_REQUIRED_TYPE
      }
    } else if (!validChannel(type.spec.channelKind, channel)) {
      return V9ErrorCode.MALFORMED_HEADER
    }
    if (expectedSequence == ProtocolV9.EXHAUSTED_SEQUENCE || sequence != expectedSequence) {
      return V9ErrorCode.SEQUENCE_ERROR
    }
    if (flags has V9Flag.RESPONSE && correlation == 0L ||
        !(flags has V9Flag.RESPONSE) && correlation != 0L) {
      return V9ErrorCode.CORRELATION_ERROR
    }
    if (!(flags has V9Flag.DEFLATE) && encodedLength != decodedLength) {
      return V9ErrorCode.MALFORMED_HEADER
    }
    if (type != null &&
        (encodedLength > type.spec.maximumEncodedBytes ||
            decodedLength > type.spec.maximumDecodedBytes ||
            decodedLength < type.spec.minimumDecodedBytes)) {
      return V9ErrorCode.LIMIT_EXCEEDED
    }
    if (encodedLength > Int.MAX_VALUE || decodedLength > Int.MAX_VALUE) {
      return V9ErrorCode.LIMIT_EXCEEDED
    }
    val wireBytes = checkedWireBytes(encodedLength) ?: return V9ErrorCode.LIMIT_EXCEEDED
    budget.admissionError(wireBytes, decodedLength)?.let { return it }
    if (type != null) {
      capabilityFailure(type, flags, policy)?.let { return it }
      if (type !in policy.allowedMessages) return V9ErrorCode.UNEXPECTED_MESSAGE
    } else if (!policy.allowUnknownOptional) {
      return V9ErrorCode.UNEXPECTED_MESSAGE
    }
    return null
  }

  private fun failed(
      completed: List<V9Frame>,
      reason: V9ErrorCode,
      decisiveBytes: Int,
  ): V9DecodeBatch {
    fail(reason, decisiveBytes)
    return snapshot(completed)
  }

  private fun fail(reason: V9ErrorCode, decisiveBytes: Int) {
    currentReservation?.close()
    currentReservation = null
    failure = V9ProtocolException(reason, decisiveBytes)
    terminal = true
    resetFrame()
  }

  private fun resetFrame() {
    headerCount = 0
    header = null
    encodedPayload = null
    payloadCount = 0
  }

  private fun snapshot(completed: List<V9Frame>): V9DecodeBatch =
      V9DecodeBatch(
          completed.toList(),
          failure == null && !terminal,
          failure,
          consumed,
          headerCount + payloadCount,
          allocations,
          reservations,
          expectedSequence == ProtocolV9.EXHAUSTED_SEQUENCE,
      )

  private fun validateInitialSequence(sequence: Long): Long {
    require(sequence in 0..ProtocolV9.EXHAUSTED_SEQUENCE)
    return sequence
  }
}

data class V9OutboundFrame(
    val type: V9MessageType,
    val flags: Int,
    val sequence: Long,
    val correlation: Long,
    val channel: Long,
    val payload: ByteArray,
)

object V9FrameEncoder {
  fun encode(
      frame: V9OutboundFrame,
      policy: V9DecoderPolicy = V9DecoderPolicy(),
  ): ByteArray {
    require(frame.sequence in 0..ProtocolV9.LAST_SEQUENCE) { "v9 sequence is exhausted" }
    require(frame.correlation in 0..ProtocolV9.U32_MAX)
    require(frame.channel in 0..ProtocolV9.U32_MAX)
    validateFlags(frame.type, frame.flags)?.let { throw V9ProtocolException(it, 12) }
    if (!validChannel(frame.type.spec.channelKind, frame.channel)) {
      throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, 32)
    }
    if (frame.flags has V9Flag.RESPONSE && frame.correlation == 0L ||
        !(frame.flags has V9Flag.RESPONSE) && frame.correlation != 0L) {
      throw V9ProtocolException(V9ErrorCode.CORRELATION_ERROR, 32)
    }
    capabilityFailure(frame.type, frame.flags, policy)?.let {
      throw V9ProtocolException(it, 32)
    }
    if (frame.type !in policy.allowedMessages) {
      throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 32)
    }

    val decodedBytes = frame.payload.size.toLong()
    if (decodedBytes !in
        frame.type.spec.minimumDecodedBytes..frame.type.spec.maximumDecodedBytes) {
      throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 32)
    }
    val decoded = frame.payload.copyOf()
    val encoded =
        if (frame.flags has V9Flag.DEFLATE) deflateRaw(decoded) else decoded
    if (encoded.size.toLong() > frame.type.spec.maximumEncodedBytes) {
      throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 32)
    }
    val size = try {
      Math.addExact(ProtocolV9.HEADER_BYTES, encoded.size)
    } catch (_: ArithmeticException) {
      throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 32)
    }
    val result = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    result.put(ProtocolV9.MAGIC)
    result.put(ProtocolV9.MAJOR.toByte())
    result.put(ProtocolV9.MINOR.toByte())
    result.putShort(ProtocolV9.HEADER_BYTES.toShort())
    result.putShort(frame.type.wireId.toShort())
    result.putShort(frame.flags.toShort())
    result.putInt(frame.sequence.toInt())
    result.putInt(frame.correlation.toInt())
    result.putInt(encoded.size)
    result.putInt(decoded.size)
    result.putInt(frame.channel.toInt())
    result.put(sha256(decoded))
    result.put(encoded)
    return result.array()
  }
}

private fun validateFlags(type: V9MessageType, flags: Int): V9ErrorCode? {
  val spec = type.spec
  return if (flags and V9Flag.KNOWN_MASK.inv() != 0 ||
      flags and spec.allowedFlags.inv() != 0 ||
      flags and spec.requiredFlags != spec.requiredFlags ||
      flags has V9Flag.DEFLATE && spec.compression != V9Compression.RAW_DEFLATE) {
    V9ErrorCode.UNKNOWN_REQUIRED_FLAG
  } else {
    null
  }
}

internal fun capabilityFailure(
    type: V9MessageType,
    flags: Int,
    policy: V9DecoderPolicy,
): V9ErrorCode? {
  val capabilities = policy.negotiatedCapabilities
  if (policy.linkMode == V9LinkMode.FOUR_PLAYER &&
      V9Capability.FOUR_PLAYER_V1 !in capabilities) {
    return V9ErrorCode.CAPABILITY_MISMATCH
  }
  val requiredClass = when (type) {
    V9MessageType.ROM_BEGIN, V9MessageType.ROM_CHUNK, V9MessageType.ROM_END ->
      V9Capability.ROM_TRANSFER_V1
    V9MessageType.BATTERY_BEGIN, V9MessageType.BATTERY_CHUNK, V9MessageType.BATTERY_END ->
      V9Capability.BATTERY_TRANSFER_V1
    V9MessageType.PING, V9MessageType.PONG -> V9Capability.PING_V1
    else -> null
  }
  if (requiredClass != null && requiredClass !in capabilities) {
    return V9ErrorCode.CAPABILITY_MISMATCH
  }
  if (flags has V9Flag.DEFLATE && V9Capability.RAW_DEFLATE_V1 !in capabilities) {
    return V9ErrorCode.CAPABILITY_MISMATCH
  }
  return null
}

internal fun validChannel(kind: V9ChannelKind, channel: Long): Boolean = when (kind) {
  V9ChannelKind.CONTROL -> channel == ProtocolV9.CONTROL_CHANNEL
  V9ChannelKind.PLAYER -> channel in 1L..4L
  V9ChannelKind.GROUP_OR_PLAYER ->
    channel == ProtocolV9.GROUP_CHANNEL || channel in 1L..4L
}

private fun parseHeader(bytes: ByteArray): V9FrameHeader =
    V9FrameHeader(
        u16(bytes, 8),
        V9MessageType.fromWireId(u16(bytes, 8)),
        u16(bytes, 10),
        u32(bytes, 12),
        u32(bytes, 16),
        u32(bytes, 20),
        u32(bytes, 24),
        u32(bytes, 28),
        bytes.copyOfRange(32, 64),
    )

internal fun u16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or
        (bytes[offset + 1].toInt() and 0xff)

internal fun u32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xff) shl 24) or
        ((bytes[offset + 1].toLong() and 0xff) shl 16) or
        ((bytes[offset + 2].toLong() and 0xff) shl 8) or
        (bytes[offset + 3].toLong() and 0xff)

private fun checkedWireBytes(encoded: Long): Long? = try {
  Math.addExact(ProtocolV9.HEADER_BYTES.toLong(), encoded)
} catch (_: ArithmeticException) {
  null
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun deflateRaw(decoded: ByteArray): ByteArray {
  val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
  return try {
    deflater.setInput(decoded)
    deflater.finish()
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(8_192)
    while (!deflater.finished()) {
      val count = deflater.deflate(chunk)
      if (count <= 0) throw V9ProtocolException(V9ErrorCode.DECOMPRESSION_FAILED, 32)
      output.write(chunk, 0, count)
    }
    output.toByteArray()
  } finally {
    deflater.end()
  }
}

private fun inflateExact(encoded: ByteArray, decodedLength: Int): ByteArray? {
  val inflater = Inflater(true)
  return try {
    inflater.setInput(encoded)
    val output = ByteArray(decodedLength)
    var offset = 0
    while (offset < output.size) {
      val count = inflater.inflate(output, offset, output.size - offset)
      if (count > 0) {
        offset += count
      } else if (inflater.needsDictionary() || inflater.needsInput() || inflater.finished()) {
        break
      } else {
        return null
      }
    }
    if (offset != output.size) return null
    if (!inflater.finished()) {
      val overflow = ByteArray(1)
      if (inflater.inflate(overflow) != 0) return null
    }
    if (!inflater.finished() || inflater.needsDictionary() || inflater.remaining != 0) null
    else output
  } catch (_: DataFormatException) {
    null
  } finally {
    inflater.end()
  }
}

private infix fun Int.has(flag: V9Flag): Boolean = this and flag.wireMask != 0

private fun Long.toIntBounded(): Int =
    if (this > Int.MAX_VALUE) Int.MAX_VALUE else toInt()
