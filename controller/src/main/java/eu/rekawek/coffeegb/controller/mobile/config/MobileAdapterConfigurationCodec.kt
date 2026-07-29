package eu.rekawek.coffeegb.controller.mobile.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** Fixed-size, deterministic and allocation-bounded on-disk representation. */
internal object MobileAdapterConfigurationCodec {
  const val FORMAT_VERSION: Int = 1
  const val ENCODED_SIZE: Int = 300

  private const val LENGTH_FIELD_SIZE = 2
  private const val DIGEST_SIZE = 32
  private val MAGIC = "CGBMACFG".toByteArray(StandardCharsets.US_ASCII)
  private val BODY_SIZE =
      MAGIC.size + 1 + 1 + LENGTH_FIELD_SIZE + MobileAdapterConfiguration.CONFIGURATION_SIZE

  fun encode(configuration: MobileAdapterConfiguration): ByteArray {
    val encoded = ByteArray(ENCODED_SIZE)
    val body = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
    body.put(MAGIC)
    body.put(FORMAT_VERSION.toByte())
    body.put(configuration.deviceId.toByte())
    body.putShort(MobileAdapterConfiguration.CONFIGURATION_SIZE.toShort())
    body.put(configuration.configurationBytes())
    check(body.position() == BODY_SIZE)
    body.put(digest(encoded, BODY_SIZE))
    check(body.position() == ENCODED_SIZE)
    return encoded
  }

  @Throws(IOException::class)
  fun decode(encoded: ByteArray): MobileAdapterConfiguration {
    if (encoded.size != ENCODED_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
    for (expected in MAGIC) {
      if (input.get() != expected) {
        reject(MobileAdapterConfigurationError.MALFORMED_FILE)
      }
    }
    val version = input.get().toInt() and 0xff
    if (version != FORMAT_VERSION) {
      reject(MobileAdapterConfigurationError.UNSUPPORTED_VERSION)
    }
    val deviceId = input.get().toInt() and 0xff
    if (deviceId !in MobileAdapterConfiguration.MIN_DEVICE_ID..MobileAdapterConfiguration.MAX_DEVICE_ID) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val configurationSize = input.short.toInt() and 0xffff
    if (configurationSize != MobileAdapterConfiguration.CONFIGURATION_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }

    val expectedDigest = digest(encoded, BODY_SIZE)
    val storedDigest = encoded.copyOfRange(BODY_SIZE, ENCODED_SIZE)
    if (!MessageDigest.isEqual(expectedDigest, storedDigest)) {
      reject(MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED)
    }

    val configuration = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE)
    input.get(configuration)
    check(input.position() == BODY_SIZE)
    return MobileAdapterConfiguration(deviceId, configuration)
  }

  private fun digest(bytes: ByteArray, length: Int): ByteArray =
      try {
        MessageDigest.getInstance("SHA-256").run {
          update(bytes, 0, length)
          digest()
        }
      } catch (impossible: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is required by the Java runtime", impossible)
      }

  private fun reject(error: MobileAdapterConfigurationError): Nothing =
      throw MobileAdapterConfigurationFormatException(error)

  init {
    check(MAGIC.size == 8)
    check(BODY_SIZE + DIGEST_SIZE == ENCODED_SIZE)
  }
}

internal class MobileAdapterConfigurationFormatException(
    val error: MobileAdapterConfigurationError,
) : IOException(error.userMessage)
