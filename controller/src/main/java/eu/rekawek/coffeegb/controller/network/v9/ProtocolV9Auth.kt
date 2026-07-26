package eu.rekawek.coffeegb.controller.network.v9

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class V9Auth(
    val slot: Int,
    proof: ByteArray,
) {
  private val proof = proof.copyOf()

  init {
    require(slot in 1..3)
    require(this.proof.size == 32)
  }

  fun proof(): ByteArray = proof.copyOf()

  override fun toString(): String = "V9Auth(slot=$slot, proof=[redacted])"
}

enum class V9AuthStatus(val wireId: Int) {
  ACCEPTED(0),
  REJECTED(1);

  companion object {
    fun fromWireId(value: Int): V9AuthStatus? = entries.firstOrNull { it.wireId == value }
  }
}

class V9AuthResult(val status: V9AuthStatus)

/** Exact frozen 36-byte AUTH and 4-byte AUTH_RESULT payload contract. */
object V9AuthCodec {
  private val LABEL = "CoffeeGB-v9".toByteArray(StandardCharsets.US_ASCII)

  fun encode(auth: V9Auth): ByteArray =
      ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN)
          .put(auth.slot.toByte())
          .put(0)
          .put(0)
          .put(0)
          .put(auth.proof())
          .array()

  fun decode(payload: ByteArray, mode: V9LinkMode): V9Auth {
    validateAuth(payload, mode)?.let { throw V9ProtocolException(it, payload.size) }
    return V9Auth(payload[0].toInt() and 0xff, payload.copyOfRange(4, 36))
  }

  fun encode(result: V9AuthResult): ByteArray =
      ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
          .putShort(result.status.wireId.toShort())
          .putShort(0)
          .array()

  fun decodeResult(payload: ByteArray, flags: Int): V9AuthResult {
    validateAuthResult(payload, flags)?.let {
      throw V9ProtocolException(it, payload.size)
    }
    return V9AuthResult(requireNotNull(V9AuthStatus.fromWireId(u16(payload, 0))))
  }

  fun proof(
      token: ByteArray,
      serverNonce: ByteArray,
      clientNonce: ByteArray,
      slot: Int,
  ): ByteArray {
    require(token.size == V9Limit.INVITATION_TOKEN_BYTES.value.toInt())
    require(serverNonce.size == 32)
    require(clientNonce.size == 32)
    require(slot in 1..3)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(token, "HmacSHA256"))
    mac.update(LABEL)
    mac.update(serverNonce)
    mac.update(clientNonce)
    mac.update(slot.toByte())
    return mac.doFinal()
  }

  internal fun validateAuth(payload: ByteArray, mode: V9LinkMode): V9ErrorCode? {
    if (payload.size != 36 ||
        payload[1].toInt() != 0 ||
        payload[2].toInt() != 0 ||
        payload[3].toInt() != 0) {
      return V9ErrorCode.AUTH_FAILED
    }
    val slot = payload[0].toInt() and 0xff
    if (mode == V9LinkMode.NORMAL && slot != 1 ||
        mode == V9LinkMode.FOUR_PLAYER && slot !in 1..3) {
      return V9ErrorCode.AUTH_FAILED
    }
    return null
  }

  internal fun validateAuthResult(payload: ByteArray, flags: Int): V9ErrorCode? {
    if (payload.size != 4 || u16(payload, 2) != 0) return V9ErrorCode.AUTH_FAILED
    val status = V9AuthStatus.fromWireId(u16(payload, 0)) ?: return V9ErrorCode.AUTH_FAILED
    val expected =
        if (status == V9AuthStatus.ACCEPTED) {
          V9Flag.RESPONSE.wireMask
        } else {
          V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask
        }
    return if (flags == expected) null else V9ErrorCode.AUTH_FAILED
  }
}

/**
 * Client-side one-shot proof source. Token material never appears in public accessors or text.
 */
class V9ClientInvitation internal constructor(
    val mode: V9LinkMode,
    val slot: Int,
    private val secret: V9InvitationSecretLease,
) : AutoCloseable {
  private var closed = false

  @Synchronized
  internal fun createAuth(serverNonce: ByteArray, clientNonce: ByteArray): V9Auth {
    check(!closed) { "v9 invitation proof source is closed" }
    return secret.use { token ->
      V9Auth(slot, V9AuthCodec.proof(token, serverNonce, clientNonce, slot))
    }
  }

  @Synchronized
  override fun close() {
    if (!closed) {
      closed = true
      secret.close()
    }
  }

  internal fun isSecretAvailable(): Boolean =
      synchronized(this) { !closed && secret.isAvailable() }

  override fun toString(): String = "V9ClientInvitation([redacted])"
}

fun V9Invitation.forClientAuthentication(): V9ClientInvitation =
    V9ClientInvitation(mode, slot, transferSecret())
