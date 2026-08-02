package eu.rekawek.coffeegb.controller.mobile.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** Versioned, deterministic and allocation-bounded owner-only configuration representation. */
internal object MobileAdapterConfigurationCodec {
  const val LEGACY_FORMAT_VERSION: Int = 1
  const val VERSION_2_FORMAT_VERSION: Int = 2
  const val FORMAT_VERSION: Int = 3

  const val LEGACY_ENCODED_SIZE: Int = 300
  const val MIN_ENCODED_SIZE: Int = LEGACY_ENCODED_SIZE
  const val VERSION_2_MAX_ENCODED_SIZE: Int = 640
  const val MAX_ENCODED_SIZE: Int =
      VERSION_2_MAX_ENCODED_SIZE +
          1 +
          MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES *
              (1 + MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES)

  private const val LEGACY_LENGTH_FIELD_SIZE = 2
  private const val DIGEST_SIZE = 32
  private const val CUSTOM_SERVER_FLAG = 0x01
  private const val V2_KNOWN_FLAGS = CUSTOM_SERVER_FLAG
  private const val IPV4_BYTES = 4
  private const val PORT_MAPPING_BYTES = 5

  private val MAGIC = "CGBMACFG".toByteArray(StandardCharsets.US_ASCII)
  private val LEGACY_BODY_SIZE =
      MAGIC.size +
          1 +
          1 +
          LEGACY_LENGTH_FIELD_SIZE +
          MobileAdapterConfiguration.CONFIGURATION_SIZE
  private const val V2_FIXED_BODY_SIZE =
      8 + 1 + 1 + MobileAdapterConfiguration.CONFIGURATION_SIZE + 1 + IPV4_BYTES + 2 + 1 + 1
  private const val V2_MIN_ENCODED_SIZE = V2_FIXED_BODY_SIZE + DIGEST_SIZE
  private const val V3_FIXED_BODY_SIZE = V2_FIXED_BODY_SIZE + 1
  private const val V3_MIN_ENCODED_SIZE = V3_FIXED_BODY_SIZE + DIGEST_SIZE

  /** Writes the current format. Runtime network consent and private/local gates are never encoded. */
  fun encode(configuration: MobileAdapterConfiguration): ByteArray =
      encodeCustomFormat(configuration, FORMAT_VERSION, includeAdditionalAliases = true)

  /** Exact version-2 fixture writer retained for migration and byte-compatibility tests. */
  fun encodeVersion2(configuration: MobileAdapterConfiguration): ByteArray {
    val custom = configuration.networkPolicy as? MobileAdapterNetworkPolicy.CustomServer
    require(custom?.additionalDnsQueryNames.isNullOrEmpty()) {
      "Version 2 cannot represent additional DNS query names"
    }
    return encodeCustomFormat(
        configuration,
        VERSION_2_FORMAT_VERSION,
        includeAdditionalAliases = false,
    )
  }

  private fun encodeCustomFormat(
      configuration: MobileAdapterConfiguration,
      version: Int,
      includeAdditionalAliases: Boolean,
  ): ByteArray {
    val custom = configuration.networkPolicy as? MobileAdapterNetworkPolicy.CustomServer
    val queryName =
        custom?.dnsQueryName?.toByteArray(StandardCharsets.US_ASCII) ?: EMPTY_BYTES
    val additionalAliases =
        if (includeAdditionalAliases) {
          custom?.additionalDnsQueryNames
              ?.map { it.toByteArray(StandardCharsets.US_ASCII) }
              .orEmpty()
        } else {
          emptyList()
        }
    val mappings = custom?.portMappings ?: emptyList()
    val mappingBytes = Math.multiplyExact(mappings.size, PORT_MAPPING_BYTES)
    val additionalAliasBytes = additionalAliases.sumOf { 1 + it.size }
    val fixedBodySize = if (includeAdditionalAliases) V3_FIXED_BODY_SIZE else V2_FIXED_BODY_SIZE
    val bodySize =
        Math.addExact(
            fixedBodySize,
            Math.addExact(queryName.size, Math.addExact(additionalAliasBytes, mappingBytes)),
        )
    val encoded = ByteArray(Math.addExact(bodySize, DIGEST_SIZE))
    check(encoded.size <= if (includeAdditionalAliases) MAX_ENCODED_SIZE else VERSION_2_MAX_ENCODED_SIZE)

    val output = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
    output.put(MAGIC)
    output.put(version.toByte())
    output.put(configuration.deviceId.toByte())
    output.put(configuration.configurationBytes())
    output.put(if (custom == null) 0 else CUSTOM_SERVER_FLAG.toByte())
    output.put(custom?.resolverAddressBytes() ?: ZERO_IPV4)
    output.putShort((custom?.resolverPort ?: 0).toShort())
    output.put(queryName.size.toByte())
    output.put(queryName)
    if (includeAdditionalAliases) {
      output.put(additionalAliases.size.toByte())
      additionalAliases.forEach { alias ->
        output.put(alias.size.toByte())
        output.put(alias)
      }
    }
    output.put(mappings.size.toByte())
    mappings.forEach { mapping ->
      output.put(mapping.transport.wireId.toByte())
      output.putShort(mapping.guestPort.toShort())
      output.putShort(mapping.targetPort.toShort())
    }
    check(output.position() == bodySize)
    output.put(digest(encoded, bodySize))
    check(output.position() == encoded.size)
    return encoded
  }

  /** Exact Phase-1 fixture writer retained for migration and byte-compatibility tests. */
  fun encodeVersion1(configuration: MobileAdapterConfiguration): ByteArray {
    require(configuration.networkPolicy == MobileAdapterNetworkPolicy.Offline) {
      "Version 1 cannot represent a custom-server policy"
    }
    val encoded = ByteArray(LEGACY_ENCODED_SIZE)
    val output = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
    output.put(MAGIC)
    output.put(LEGACY_FORMAT_VERSION.toByte())
    output.put(configuration.deviceId.toByte())
    output.putShort(MobileAdapterConfiguration.CONFIGURATION_SIZE.toShort())
    output.put(configuration.configurationBytes())
    check(output.position() == LEGACY_BODY_SIZE)
    output.put(digest(encoded, LEGACY_BODY_SIZE))
    check(output.position() == LEGACY_ENCODED_SIZE)
    return encoded
  }

  @Throws(IOException::class)
  fun decode(encoded: ByteArray): MobileAdapterConfiguration {
    if (encoded.size !in MIN_ENCODED_SIZE..MAX_ENCODED_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    requireMagic(encoded)
    return when (encoded[MAGIC.size].toInt() and 0xff) {
      LEGACY_FORMAT_VERSION -> decodeVersion1(encoded)
      VERSION_2_FORMAT_VERSION -> decodeVersion2(encoded)
      FORMAT_VERSION -> decodeVersion3(encoded)
      else -> reject(MobileAdapterConfigurationError.UNSUPPORTED_VERSION)
    }
  }

  private fun decodeVersion1(encoded: ByteArray): MobileAdapterConfiguration {
    if (encoded.size != LEGACY_ENCODED_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    verifyDigest(encoded, LEGACY_BODY_SIZE)
    val input = ByteBuffer.wrap(encoded, 0, LEGACY_BODY_SIZE).order(ByteOrder.BIG_ENDIAN)
    skipMagicAndVersion(input)
    val deviceId = requireDeviceId(input.get().toInt() and 0xff)
    val configurationSize = input.short.toInt() and 0xffff
    if (configurationSize != MobileAdapterConfiguration.CONFIGURATION_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val configuration = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE)
    input.get(configuration)
    if (input.hasRemaining()) reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    return MobileAdapterConfiguration(
        deviceId,
        configuration,
        MobileAdapterNetworkPolicy.Offline,
    )
  }

  private fun decodeVersion2(encoded: ByteArray): MobileAdapterConfiguration {
    if (encoded.size !in V2_MIN_ENCODED_SIZE..VERSION_2_MAX_ENCODED_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    return decodeCustomFormat(encoded, includeAdditionalAliases = false)
  }

  private fun decodeVersion3(encoded: ByteArray): MobileAdapterConfiguration {
    if (encoded.size !in V3_MIN_ENCODED_SIZE..MAX_ENCODED_SIZE) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    return decodeCustomFormat(encoded, includeAdditionalAliases = true)
  }

  private fun decodeCustomFormat(
      encoded: ByteArray,
      includeAdditionalAliases: Boolean,
  ): MobileAdapterConfiguration {
    val bodySize = encoded.size - DIGEST_SIZE
    verifyDigest(encoded, bodySize)
    val input = ByteBuffer.wrap(encoded, 0, bodySize).order(ByteOrder.BIG_ENDIAN)
    skipMagicAndVersion(input)
    val deviceId = requireDeviceId(input.get().toInt() and 0xff)
    val configuration = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE)
    input.get(configuration)

    val flags = input.get().toInt() and 0xff
    if ((flags and V2_KNOWN_FLAGS.inv()) != 0) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val resolver = ByteArray(IPV4_BYTES)
    input.get(resolver)
    val resolverPort = input.short.toInt() and 0xffff
    val queryNameLength = input.get().toInt() and 0xff
    if (queryNameLength > MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES ||
        input.remaining() < queryNameLength + if (includeAdditionalAliases) 2 else 1) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val queryNameBytes = ByteArray(queryNameLength)
    input.get(queryNameBytes)
    if (queryNameBytes.any { it.toInt() !in 0..0x7f }) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val queryName = String(queryNameBytes, StandardCharsets.US_ASCII)

    val additionalAliases =
        if (includeAdditionalAliases) {
          val aliasCount = input.get().toInt() and 0xff
          if (aliasCount > MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES) {
            reject(MobileAdapterConfigurationError.MALFORMED_FILE)
          }
          buildList(aliasCount) {
            repeat(aliasCount) {
              if (input.remaining() < 2) {
                reject(MobileAdapterConfigurationError.MALFORMED_FILE)
              }
              val aliasLength = input.get().toInt() and 0xff
              if (aliasLength !in 1..MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES ||
                  input.remaining() < aliasLength + 1) {
                reject(MobileAdapterConfigurationError.MALFORMED_FILE)
              }
              val aliasBytes = ByteArray(aliasLength)
              input.get(aliasBytes)
              if (aliasBytes.any { it.toInt() !in 0..0x7f }) {
                reject(MobileAdapterConfigurationError.MALFORMED_FILE)
              }
              add(String(aliasBytes, StandardCharsets.US_ASCII))
            }
          }
        } else {
          emptyList()
        }

    val mappingCount = input.get().toInt() and 0xff
    if (mappingCount > MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS ||
        input.remaining() != Math.multiplyExact(mappingCount, PORT_MAPPING_BYTES)) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val mappings =
        buildList(mappingCount) {
          repeat(mappingCount) {
            val transport =
                MobileAdapterTransport.fromWireId(input.get().toInt() and 0xff)
                    ?: reject(MobileAdapterConfigurationError.MALFORMED_FILE)
            val guestPort = input.short.toInt() and 0xffff
            val targetPort = input.short.toInt() and 0xffff
            try {
              add(MobileAdapterPortMapping(transport, guestPort, targetPort))
            } catch (_: IllegalArgumentException) {
              reject(MobileAdapterConfigurationError.MALFORMED_FILE)
            }
          }
        }
    if (input.hasRemaining()) reject(MobileAdapterConfigurationError.MALFORMED_FILE)

    val custom = (flags and CUSTOM_SERVER_FLAG) != 0
    val policy =
        if (!custom) {
          if (resolver.any { it != 0.toByte() } ||
              resolverPort != 0 ||
              queryName.isNotEmpty() ||
              additionalAliases.isNotEmpty() ||
              mappings.isNotEmpty()) {
            reject(MobileAdapterConfigurationError.MALFORMED_FILE)
          }
          MobileAdapterNetworkPolicy.Offline
        } else {
          try {
            MobileAdapterNetworkPolicy.CustomServer(
                queryName,
                resolver.joinToString(".") { (it.toInt() and 0xff).toString() },
                resolverPort,
                mappings,
                additionalAliases,
            )
          } catch (_: IllegalArgumentException) {
            reject(MobileAdapterConfigurationError.MALFORMED_FILE)
          }
        }
    return MobileAdapterConfiguration(deviceId, configuration, policy)
  }

  private fun requireMagic(encoded: ByteArray) {
    for (index in MAGIC.indices) {
      if (encoded[index] != MAGIC[index]) {
        reject(MobileAdapterConfigurationError.MALFORMED_FILE)
      }
    }
  }

  private fun skipMagicAndVersion(input: ByteBuffer) {
    input.position(MAGIC.size + 1)
  }

  private fun requireDeviceId(deviceId: Int): Int {
    if (deviceId !in MobileAdapterConfiguration.MIN_DEVICE_ID..MobileAdapterConfiguration.MAX_DEVICE_ID) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    return deviceId
  }

  private fun verifyDigest(encoded: ByteArray, bodySize: Int) {
    if (bodySize < 0 || bodySize + DIGEST_SIZE != encoded.size) {
      reject(MobileAdapterConfigurationError.MALFORMED_FILE)
    }
    val expectedDigest = digest(encoded, bodySize)
    val storedDigest = encoded.copyOfRange(bodySize, encoded.size)
    if (!MessageDigest.isEqual(expectedDigest, storedDigest)) {
      reject(MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED)
    }
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
    check(LEGACY_BODY_SIZE + DIGEST_SIZE == LEGACY_ENCODED_SIZE)
    check(
        V2_FIXED_BODY_SIZE +
            MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES +
            MobileAdapterNetworkPolicy.CustomServer.MAX_PORT_MAPPINGS * PORT_MAPPING_BYTES +
            DIGEST_SIZE == VERSION_2_MAX_ENCODED_SIZE)
    check(
        VERSION_2_MAX_ENCODED_SIZE +
            1 +
            MobileAdapterNetworkPolicy.CustomServer.MAX_ADDITIONAL_DNS_QUERY_NAMES *
                (1 + MobileAdapterNetworkPolicy.CustomServer.MAX_DNS_QUERY_NAME_BYTES) ==
            MAX_ENCODED_SIZE)
  }

  private val EMPTY_BYTES = ByteArray(0)
  private val ZERO_IPV4 = ByteArray(IPV4_BYTES)
}

internal class MobileAdapterConfigurationFormatException(
    val error: MobileAdapterConfigurationError,
) : IOException(error.userMessage)
