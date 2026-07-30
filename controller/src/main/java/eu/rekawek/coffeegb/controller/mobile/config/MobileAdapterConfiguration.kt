package eu.rekawek.coffeegb.controller.mobile.config

import java.util.Collections
import java.util.Locale

/**
 * Immutable inputs used to construct a Mobile Adapter serial endpoint.
 *
 * The configuration address space may contain private dial or account data, so its bytes are
 * detached at both API boundaries and are deliberately omitted from diagnostics.
 */
class MobileAdapterConfiguration(
    val deviceId: Int,
    configurationBytes: ByteArray,
    val networkPolicy: MobileAdapterNetworkPolicy = MobileAdapterNetworkPolicy.Offline,
) {
  private val ownedConfiguration: ByteArray

  init {
    require(deviceId in MIN_DEVICE_ID..MAX_DEVICE_ID) {
      "Mobile Adapter device ID must be in 0..127"
    }
    require(configurationBytes.size == CONFIGURATION_SIZE) {
      "Mobile Adapter configuration must contain exactly 256 bytes"
    }
    ownedConfiguration = configurationBytes.clone()
  }

  /** Returns a detached copy suitable for `MobileAdapterSerialEndpoint` construction. */
  fun configurationBytes(): ByteArray = ownedConfiguration.clone()

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is MobileAdapterConfiguration &&
              deviceId == other.deviceId &&
              ownedConfiguration.contentEquals(other.ownedConfiguration) &&
              networkPolicy == other.networkPolicy)

  override fun hashCode(): Int =
      31 * (31 * deviceId + ownedConfiguration.contentHashCode()) + networkPolicy.hashCode()

  override fun toString(): String =
      "MobileAdapterConfiguration(deviceId=$deviceId, configuration=[redacted], " +
          "networkPolicy=${networkPolicy.redactedDescription()})"

  companion object {
    const val CONFIGURATION_SIZE: Int = 256
    const val MIN_DEVICE_ID: Int = 0
    const val MAX_DEVICE_ID: Int = 127
    const val SYNTHETIC_DEVICE_ID: Int = 0x08

    /**
     * Clean-room deterministic configuration used when no valid persisted file exists.
     *
     * These bytes match the synthetic Phase #346 transcript fixture: `MA`, status `81`, zeroed
     * space, and the documented boundary pattern in the second 128-byte page. They contain no
     * server, account, telephone, credential, or captured device data.
     */
    fun syntheticFallback(): MobileAdapterConfiguration {
      val configuration = ByteArray(CONFIGURATION_SIZE)
      configuration[0] = 0x4d
      configuration[1] = 0x41
      configuration[2] = 0x81.toByte()
      for (index in 0 until 128) {
        configuration[128 + index] = index.toByte()
      }
      return MobileAdapterConfiguration(SYNTHETIC_DEVICE_ID, configuration)
    }
  }
}

/** Persisted custom-server policy. Runtime network consent is deliberately not represented here. */
enum class MobileAdapterNetworkMode {
  OFFLINE,
  CUSTOM_SERVER,
}

/** The two outbound transports admitted by the bounded custom-server mapping table. */
enum class MobileAdapterTransport(internal val wireId: Int) {
  TCP(1),
  UDP(2);

  internal companion object {
    fun fromWireId(id: Int): MobileAdapterTransport? = entries.singleOrNull { it.wireId == id }
  }
}

/** One deterministic guest-to-custom-server port route. */
class MobileAdapterPortMapping(
    val transport: MobileAdapterTransport,
    val guestPort: Int,
    val targetPort: Int,
) {
  init {
    require(guestPort in MIN_PORT..MAX_PORT) { "Guest port must be in 1..65535" }
    require(targetPort in MIN_PORT..MAX_PORT) { "Target port must be in 1..65535" }
  }

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is MobileAdapterPortMapping &&
              transport == other.transport &&
              guestPort == other.guestPort &&
              targetPort == other.targetPort)

  override fun hashCode(): Int =
      31 * (31 * transport.hashCode() + guestPort) + targetPort

  override fun toString(): String = "MobileAdapterPortMapping([redacted])"

  internal fun sortKey(): Long =
      (transport.wireId.toLong() shl 32) or guestPort.toLong()

  companion object {
    const val MIN_PORT = 1
    const val MAX_PORT = 65_535
  }
}

/**
 * Immutable, owner-only persisted routing policy.
 *
 * A custom policy is configuration, not authority. Enabling outbound I/O and allowing
 * private/local destinations are separate runtime-only gates which default to false on every
 * application start. Neither gate is serialized by the configuration codec.
 */
sealed interface MobileAdapterNetworkPolicy {
  val mode: MobileAdapterNetworkMode

  data object Offline : MobileAdapterNetworkPolicy {
    override val mode = MobileAdapterNetworkMode.OFFLINE
  }

  class CustomServer(
      dnsQueryName: String,
      resolverIpv4Address: String,
      val resolverPort: Int,
      portMappings: Collection<MobileAdapterPortMapping> = emptyList(),
  ) : MobileAdapterNetworkPolicy {
    override val mode = MobileAdapterNetworkMode.CUSTOM_SERVER

    val dnsQueryName: String = normalizeDnsQueryName(dnsQueryName)

    val resolverIpv4Address: String = normalizeIpv4Address(resolverIpv4Address)

    val portMappings: List<MobileAdapterPortMapping>

    init {
      require(resolverPort in MobileAdapterPortMapping.MIN_PORT..MobileAdapterPortMapping.MAX_PORT) {
        "Resolver port must be in 1..65535"
      }
      val boundedMappings = ArrayList<MobileAdapterPortMapping>(MAX_PORT_MAPPINGS)
      portMappings.forEach { mapping ->
        require(boundedMappings.size < MAX_PORT_MAPPINGS) {
          "A Mobile Adapter custom server supports at most $MAX_PORT_MAPPINGS port mappings"
        }
        boundedMappings.add(mapping)
      }
      val sorted = boundedMappings.sortedBy(MobileAdapterPortMapping::sortKey)
      require(sorted.map { it.transport to it.guestPort }.distinct().size == sorted.size) {
        "A transport and guest port may have only one target mapping"
      }
      this.portMappings = Collections.unmodifiableList(ArrayList(sorted))
    }

    internal fun resolverAddressBytes(): ByteArray {
      val parts = resolverIpv4Address.split('.')
      return ByteArray(parts.size) { index -> parts[index].toInt().toByte() }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CustomServer &&
                dnsQueryName == other.dnsQueryName &&
                resolverIpv4Address == other.resolverIpv4Address &&
                resolverPort == other.resolverPort &&
                portMappings == other.portMappings)

    override fun hashCode(): Int {
      var result = dnsQueryName.hashCode()
      result = 31 * result + resolverIpv4Address.hashCode()
      result = 31 * result + resolverPort
      result = 31 * result + portMappings.hashCode()
      return result
    }

    override fun toString(): String =
        "MobileAdapterNetworkPolicy.CustomServer(" +
            "dnsQueryName=[redacted], resolverIpv4Address=[redacted], " +
            "resolverPort=[redacted], portMappings=${portMappings.size})"

    companion object {
      const val MAX_DNS_QUERY_NAME_BYTES = 253
      const val MAX_DNS_LABEL_BYTES = 63
      const val MAX_PORT_MAPPINGS = 16

      private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

      private fun normalizeDnsQueryName(value: String): String {
        require(value.isNotEmpty() && value.length <= MAX_DNS_QUERY_NAME_BYTES) {
          "DNS query name must contain 1..$MAX_DNS_QUERY_NAME_BYTES ASCII bytes"
        }
        require(value.all { it.code in 0x21..0x7e }) {
          "DNS query name must contain printable ASCII only"
        }
        require(value.any { it !in '0'..'9' && it != '.' }) {
          "DNS query name must not be a numeric-looking literal address"
        }
        val normalized = value.lowercase(Locale.ROOT)
        val labels = normalized.split('.')
        require(labels.all { it.length in 1..MAX_DNS_LABEL_BYTES && DNS_LABEL.matches(it) }) {
          "DNS query name contains an invalid label"
        }
        return normalized
      }

      private fun normalizeIpv4Address(value: String): String {
        require(value.length in MIN_IPV4_TEXT_LENGTH..MAX_IPV4_TEXT_LENGTH &&
            value.all { it == '.' || it in '0'..'9' }) {
          "Resolver address must be a literal dotted-decimal IPv4 address"
        }
        val parts = value.split('.')
        require(parts.size == 4) {
          "Resolver address must contain four IPv4 octets"
        }
        val octets =
            parts.map { part ->
              require(part == "0" || (part.length in 1..3 && part[0] != '0')) {
                "Resolver IPv4 octets must use canonical decimal notation"
              }
              part.toIntOrNull()?.takeIf { it in 0..255 }
                  ?: throw IllegalArgumentException("Resolver IPv4 octet is outside 0..255")
            }
        return octets.joinToString(".")
      }

      private const val MIN_IPV4_TEXT_LENGTH = 7
      private const val MAX_IPV4_TEXT_LENGTH = 15
    }
  }

  fun redactedDescription(): String =
      when (this) {
        Offline -> "OFFLINE"
        is CustomServer -> "CUSTOM_SERVER([redacted])"
      }
}
