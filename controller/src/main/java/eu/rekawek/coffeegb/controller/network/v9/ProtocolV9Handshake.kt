package eu.rekawek.coffeegb.controller.network.v9

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

data class V9CapabilityDeclaration(
    val wireId: Int,
    val schemaVersion: Int,
    val required: Boolean,
    val capability: V9Capability?,
)

class V9Hello(
    val role: V9Role,
    nonce: ByteArray,
    capabilities: List<V9CapabilityDeclaration>,
) {
  private val ownedNonce = nonce.copyOf()
  val capabilities: List<V9CapabilityDeclaration> =
      Collections.unmodifiableList(capabilities.toList())

  init {
    require(ownedNonce.size == 32)
    require(this.capabilities.size <= V9Limit.CAPABILITY_COUNT.value)
  }

  fun nonce(): ByteArray = ownedNonce.copyOf()
}

class V9NegotiatedCapabilities(capabilities: Set<V9Capability>) {
  val capabilities: Set<V9Capability> =
      Collections.unmodifiableSet(capabilities.toSet())

  init {
    require(capabilities.containsAll(V9Capability.requiredCapabilities))
  }

  fun decoderPolicy(
      mode: V9LinkMode,
      allowedMessages: Set<V9MessageType>,
      allowUnknownOptional: Boolean = false,
  ): V9DecoderPolicy =
      V9DecoderPolicy(allowedMessages, capabilities, mode, allowUnknownOptional)
}

object V9HelloCodec {
  fun create(
      role: V9Role,
      nonce: ByteArray,
      optionalCapabilities: Set<V9Capability> = emptySet(),
  ): V9Hello {
    require(optionalCapabilities.none { it.required })
    val declarations =
        (V9Capability.requiredCapabilities + optionalCapabilities)
            .sortedBy { it.wireId }
            .map {
              V9CapabilityDeclaration(it.wireId, it.schemaVersion, it.required, it)
            }
    return V9Hello(role, nonce, declarations)
  }

  fun encode(hello: V9Hello): ByteArray {
    val structural = validateHello(hello)
    if (structural != null) throw V9ProtocolException(structural, 0)
    val result =
        ByteBuffer.allocate(38 + hello.capabilities.size * 8).order(ByteOrder.BIG_ENDIAN)
    result.put(hello.role.wireId.toByte())
    result.put(ProtocolV9.MAJOR.toByte())
    result.put(ProtocolV9.MAJOR.toByte())
    result.put(0)
    result.put(hello.nonce())
    result.putShort(hello.capabilities.size.toShort())
    hello.capabilities.forEach {
      result.putShort(it.wireId.toShort())
      result.putShort(it.schemaVersion.toShort())
      result.putInt(if (it.required) 1 else 0)
    }
    return result.array()
  }

  fun decode(payload: ByteArray): V9Hello {
    validate(payload)?.let { throw V9ProtocolException(it, payload.size) }
    val count = u16(payload, 36)
    val declarations = ArrayList<V9CapabilityDeclaration>(count)
    repeat(count) { index ->
      val offset = 38 + index * 8
      val id = u16(payload, offset)
      val capability = V9Capability.fromWireId(id)
      declarations +=
          V9CapabilityDeclaration(
              id,
              u16(payload, offset + 2),
              u32(payload, offset + 4) == 1L,
              capability,
          )
    }
    return V9Hello(
        requireNotNull(V9Role.fromWireId(payload[0].toInt() and 0xff)),
        payload.copyOfRange(4, 36),
        declarations,
    )
  }

  fun negotiate(
      local: V9Hello,
      remote: V9Hello,
      expectedRemoteRole: V9Role,
      mode: V9LinkMode,
  ): V9NegotiatedCapabilities {
    validateHello(local)?.let { throw V9ProtocolException(it, 0) }
    validateHello(remote)?.let { throw V9ProtocolException(it, 0) }
    if (local.role == remote.role || remote.role != expectedRemoteRole) {
      throw V9ProtocolException(V9ErrorCode.CAPABILITY_MISMATCH, 0)
    }
    val localKnown = local.capabilities.mapNotNull { it.capability }.toSet()
    val remoteKnown = remote.capabilities.mapNotNull { it.capability }.toSet()
    val negotiated = localKnown intersect remoteKnown
    if (!negotiated.containsAll(V9Capability.requiredCapabilities) ||
        mode == V9LinkMode.FOUR_PLAYER && V9Capability.FOUR_PLAYER_V1 !in negotiated) {
      throw V9ProtocolException(V9ErrorCode.CAPABILITY_MISMATCH, 0)
    }
    return V9NegotiatedCapabilities(negotiated)
  }

  internal fun validate(payload: ByteArray): V9ErrorCode? {
    if (payload.size < 38) return V9ErrorCode.CAPABILITY_MISMATCH
    if (V9Role.fromWireId(payload[0].toInt() and 0xff) == null ||
        (payload[1].toInt() and 0xff) != ProtocolV9.MAJOR ||
        (payload[2].toInt() and 0xff) != ProtocolV9.MAJOR ||
        payload[3].toInt() != 0) {
      return V9ErrorCode.CAPABILITY_MISMATCH
    }
    val count = u16(payload, 36)
    if (count > V9Limit.CAPABILITY_COUNT.value ||
        payload.size.toLong() != 38L + count.toLong() * 8L) {
      return V9ErrorCode.CAPABILITY_MISMATCH
    }
    var previous = 0
    val seenRequired = mutableSetOf<V9Capability>()
    repeat(count) { index ->
      val offset = 38 + index * 8
      val id = u16(payload, offset)
      val version = u16(payload, offset + 2)
      val flags = u32(payload, offset + 4)
      if (id <= previous || flags !in 0L..1L) return V9ErrorCode.CAPABILITY_MISMATCH
      previous = id
      val known = V9Capability.fromWireId(id)
      if (known == null) {
        if (flags == 1L) return V9ErrorCode.UNKNOWN_REQUIRED_CAPABILITY
      } else {
        if (version != known.schemaVersion ||
            (flags == 1L) != known.required) {
          return V9ErrorCode.CAPABILITY_MISMATCH
        }
        if (known.required) seenRequired += known
      }
    }
    if (seenRequired != V9Capability.requiredCapabilities) {
      return V9ErrorCode.CAPABILITY_MISMATCH
    }
    return null
  }

  private fun validateHello(hello: V9Hello): V9ErrorCode? =
      try {
        validate(encodeUnchecked(hello))
      } catch (_: IllegalArgumentException) {
        V9ErrorCode.CAPABILITY_MISMATCH
      }

  private fun encodeUnchecked(hello: V9Hello): ByteArray {
    if (hello.capabilities.size > V9Limit.CAPABILITY_COUNT.value) {
      throw IllegalArgumentException("too many capabilities")
    }
    val result =
        ByteBuffer.allocate(38 + hello.capabilities.size * 8).order(ByteOrder.BIG_ENDIAN)
            .put(hello.role.wireId.toByte())
            .put(ProtocolV9.MAJOR.toByte())
            .put(ProtocolV9.MAJOR.toByte())
            .put(0)
            .put(hello.nonce())
            .putShort(hello.capabilities.size.toShort())
    hello.capabilities.forEach {
      result.putShort(it.wireId.toShort())
          .putShort(it.schemaVersion.toShort())
          .putInt(if (it.required) 1 else 0)
    }
    return result.array()
  }
}

enum class V9Diagnostic {
  PROTOCOL_MISMATCH,
  HELLO_REJECTED,
  CAPABILITY_MISMATCH,
  TIMEOUT,
  CANCELLED,
  IO_FAILURE,
  QUEUE_FULL,
  CLOSED,
}

data class V9Failure(
    val reason: V9ErrorCode,
    val stage: V9LifecycleState,
    val diagnostic: V9Diagnostic,
)

data class V9ErrorPayload(
    val error: V9ErrorCode,
    val offendingType: Int,
    val offendingSequence: Long,
)

object V9ErrorPayloadCodec {
  fun encode(
      error: V9ErrorCode,
      offendingType: Int = 0,
      offendingSequence: Long = 0,
      diagnostic: String = "",
  ): ByteArray {
    require(offendingType in 0..0xffff)
    require(offendingSequence in 0..ProtocolV9.U32_MAX)
    val text = diagnostic.toByteArray(StandardCharsets.UTF_8)
    if (!isSafeDiagnostic(text, 512)) {
      throw IllegalArgumentException("diagnostic must be sanitized strict UTF-8")
    }
    return ByteBuffer.allocate(12 + text.size).order(ByteOrder.BIG_ENDIAN)
        .putShort(error.wireId.toShort())
        .putShort(offendingType.toShort())
        .putInt(offendingSequence.toInt())
        .putShort(text.size.toShort())
        .putShort(0)
        .put(text)
        .array()
  }

  fun decode(payload: ByteArray): V9ErrorPayload {
    validate(V9Flag.TERMINAL.wireMask, payload)?.let {
      throw V9ProtocolException(it, payload.size)
    }
    return V9ErrorPayload(
        requireNotNull(V9ErrorCode.fromWireId(u16(payload, 0))),
        u16(payload, 2),
        u32(payload, 4),
    )
  }

  internal fun validate(flags: Int, payload: ByteArray): V9ErrorCode? {
    if (flags and V9Flag.TERMINAL.wireMask == 0 || payload.size < 12) {
      return V9ErrorCode.MALFORMED_HEADER
    }
    val length = u16(payload, 8)
    if (V9ErrorCode.fromWireId(u16(payload, 0)) == null ||
        u16(payload, 10) != 0 ||
        payload.size != 12 + length) {
      return V9ErrorCode.MALFORMED_HEADER
    }
    return if (isSafeDiagnostic(payload.copyOfRange(12, payload.size), 512)) null
    else V9ErrorCode.STRICT_UTF8
  }
}

private fun isSafeDiagnostic(bytes: ByteArray, maximum: Int): Boolean {
  if (bytes.size > maximum) return false
  val text = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
  } catch (_: CharacterCodingException) {
    return false
  }
  return text.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }
}
