package eu.rekawek.coffeegb.controller.mobile.network

import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/** Transport selected by one exact custom-server rule. */
enum class MobileAdapterTransportProtocol {
  TCP,
  UDP,
}

/** Stable result of classifying a literal IPv4 destination without DNS or reverse lookup. */
enum class MobileAdapterAddressClass {
  PUBLIC,
  PRIVATE_LOCAL,
  HARD_DENY,
}

/** Typed, presentation-safe destination decision. */
enum class MobileAdapterDestinationDecision {
  ALLOWED,
  NETWORK_CONSENT_REQUIRED,
  PRIVATE_LOCAL_CONSENT_REQUIRED,
  HARD_DENIED,
}

/**
 * Strict dotted-decimal IPv4 value.
 *
 * Parsing deliberately does not use `InetAddress.getByName`: legacy octal, hexadecimal, shortened
 * forms, and ambient DNS are never accepted. Diagnostics redact the value by default.
 */
class MobileAdapterIpv4Address private constructor(private val bits: Int) {
  fun bytes(): ByteArray =
      byteArrayOf(
          (bits ushr 24).toByte(),
          (bits ushr 16).toByte(),
          (bits ushr 8).toByte(),
          bits.toByte(),
      )

  internal fun inetAddress(): Inet4Address = InetAddress.getByAddress(bytes()) as Inet4Address

  internal fun unsignedBits(): Long = bits.toLong() and 0xffff_ffffL

  override fun equals(other: Any?): Boolean =
      this === other || other is MobileAdapterIpv4Address && bits == other.bits

  override fun hashCode(): Int = bits

  override fun toString(): String = "MobileAdapterIpv4Address([redacted])"

  companion object {
    @JvmStatic
    fun parse(value: String): MobileAdapterIpv4Address {
      require(value.isNotEmpty() && value.length <= 15) { "IPv4 text length is invalid" }
      require(value.none { it.isWhitespace() }) { "IPv4 text contains whitespace" }
      val parts = value.split('.', limit = 5)
      require(parts.size == 4) { "IPv4 text must contain four octets" }
      var result = 0
      for (part in parts) {
        require(part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' }) {
          "IPv4 octet is not strict decimal"
        }
        // Leading zeroes are ambiguous with inet_addr(3)'s historical octal syntax.
        require(part.length == 1 || part[0] != '0') { "IPv4 octet has an ambiguous leading zero" }
        var octet = 0
        for (character in part) {
          octet = Math.addExact(Math.multiplyExact(octet, 10), character - '0')
        }
        require(octet <= 255) { "IPv4 octet exceeds 255" }
        result = (result shl 8) or octet
      }
      return MobileAdapterIpv4Address(result)
    }

    internal fun fromBytes(bytes: ByteArray): MobileAdapterIpv4Address {
      require(bytes.size == 4) { "IPv4 address must contain four bytes" }
      var result = 0
      for (byte in bytes) result = (result shl 8) or (byte.toInt() and 0xff)
      return MobileAdapterIpv4Address(result)
    }
  }
}

/** Explicit runtime authorization. It is intentionally separate from persisted destination data. */
class MobileAdapterRuntimeAuthorization(
    val networkConsent: Boolean,
    val privateLocalDevelopment: Boolean,
) {
  override fun equals(other: Any?): Boolean =
      this === other ||
          other is MobileAdapterRuntimeAuthorization &&
              networkConsent == other.networkConsent &&
              privateLocalDevelopment == other.privateLocalDevelopment

  override fun hashCode(): Int = 31 * networkConsent.hashCode() + privateLocalDevelopment.hashCode()

  override fun toString(): String =
      "MobileAdapterRuntimeAuthorization(networkConsent=$networkConsent, " +
          "privateLocalDevelopment=$privateLocalDevelopment)"

  companion object {
    @JvmField val DISABLED = MobileAdapterRuntimeAuthorization(false, false)
  }
}

/** Literal resolver endpoint. No system resolver or guest-supplied DNS address is consulted. */
class MobileAdapterDnsResolver(
    val address: MobileAdapterIpv4Address,
    val port: Int = 53,
) {
  init {
    require(port in 1..65535) { "DNS resolver port must be in 1..65535" }
  }

  override fun equals(other: Any?): Boolean =
      this === other ||
          other is MobileAdapterDnsResolver && address == other.address && port == other.port

  override fun hashCode(): Int = 31 * address.hashCode() + port

  override fun toString(): String = "MobileAdapterDnsResolver(endpoint=[redacted])"
}

/** Name or literal address selected by explicit custom-server configuration. */
class MobileAdapterTransportTarget private constructor(
    internal val canonicalName: String?,
    internal val literalAddress: MobileAdapterIpv4Address?,
) {
  init {
    require((canonicalName == null) != (literalAddress == null)) {
      "Transport target must be exactly one of a name or literal address"
    }
  }

  val requiresDns: Boolean
    get() = canonicalName != null

  override fun equals(other: Any?): Boolean =
      this === other ||
          other is MobileAdapterTransportTarget &&
              canonicalName == other.canonicalName &&
              literalAddress == other.literalAddress

  override fun hashCode(): Int = 31 * (canonicalName?.hashCode() ?: 0) + (literalAddress?.hashCode() ?: 0)

  override fun toString(): String = "MobileAdapterTransportTarget([redacted])"

  companion object {
    @JvmStatic
    fun parse(value: String): MobileAdapterTransportTarget {
      require(value.isNotEmpty()) { "Transport target must not be empty" }
      val numericLooking = value.all { it in '0'..'9' || it == '.' }
      return if (numericLooking) {
        MobileAdapterTransportTarget(null, MobileAdapterIpv4Address.parse(value))
      } else {
        MobileAdapterTransportTarget(canonicalMobileAdapterHost(value), null)
      }
    }
  }
}

/** One exact guest alias/protocol/port capability mapped to one custom transport endpoint. */
class MobileAdapterDestinationRule(
    alias: String,
    val target: MobileAdapterTransportTarget,
    val protocol: MobileAdapterTransportProtocol,
    val guestPort: Int,
    val targetPort: Int = guestPort,
) {
  internal val canonicalAlias: String = canonicalMobileAdapterHost(alias)

  init {
    require(guestPort in 1..65535) { "Guest port must be in 1..65535" }
    require(targetPort in 1..65535) { "Target port must be in 1..65535" }
  }

  override fun equals(other: Any?): Boolean =
      this === other ||
          other is MobileAdapterDestinationRule &&
              canonicalAlias == other.canonicalAlias &&
              target == other.target &&
              protocol == other.protocol &&
              guestPort == other.guestPort &&
              targetPort == other.targetPort

  override fun hashCode(): Int {
    var result = canonicalAlias.hashCode()
    result = 31 * result + target.hashCode()
    result = 31 * result + protocol.hashCode()
    result = 31 * result + guestPort
    return 31 * result + targetPort
  }

  override fun toString(): String =
      "MobileAdapterDestinationRule(alias=[redacted], target=[redacted], protocol=$protocol, " +
          "guestPort=[redacted], targetPort=[redacted])"
}

/**
 * Immutable, default-deny custom-server policy.
 *
 * A guest name is only an alias into this exact table and is never sent to a resolver. All rules
 * sharing an alias must use the same transport target, preventing an ambiguous DNS capability.
 */
class MobileAdapterDestinationPolicy(
    val revision: Long,
    val resolver: MobileAdapterDnsResolver?,
    rules: List<MobileAdapterDestinationRule>,
) {
  private val ownedRules: List<MobileAdapterDestinationRule>
  private val rulesByAlias: Map<String, List<MobileAdapterDestinationRule>>

  init {
    require(revision >= 0) { "Destination policy revision must not be negative" }
    require(rules.size <= MAX_RULES) { "Destination policy exceeds 16 rules" }
    ownedRules = rules.toList()
    rulesByAlias = ownedRules.groupBy { it.canonicalAlias }
    require(ownedRules.distinct().size == ownedRules.size) { "Destination policy has duplicate rules" }
    require(
        ownedRules
            .map { Triple(it.canonicalAlias, it.protocol, it.guestPort) }
            .distinct()
            .size == ownedRules.size) {
      "Destination policy has an ambiguous alias, transport, and guest-port mapping"
    }
    require(ownedRules.none { it.target.requiresDns } || resolver != null) {
      "A literal DNS resolver is required for named transport targets"
    }
    for ((_, aliasRules) in rulesByAlias) {
      require(aliasRules.map { it.target }.distinct().size == 1) {
        "One guest alias cannot map to multiple transport targets"
      }
    }
  }

  fun rules(): List<MobileAdapterDestinationRule> = ownedRules.toList()

  internal fun rulesForCanonicalAlias(alias: String): List<MobileAdapterDestinationRule> =
      rulesByAlias[alias].orEmpty()

  internal fun contains(rule: MobileAdapterDestinationRule): Boolean = rule in ownedRules

  fun decide(
      address: MobileAdapterIpv4Address,
      authorization: MobileAdapterRuntimeAuthorization,
  ): MobileAdapterDestinationDecision {
    val addressClass = classifyMobileAdapterAddress(address)
    if (addressClass == MobileAdapterAddressClass.HARD_DENY) {
      return MobileAdapterDestinationDecision.HARD_DENIED
    }
    if (!authorization.networkConsent) {
      return MobileAdapterDestinationDecision.NETWORK_CONSENT_REQUIRED
    }
    return when (addressClass) {
      MobileAdapterAddressClass.HARD_DENY -> MobileAdapterDestinationDecision.HARD_DENIED
      MobileAdapterAddressClass.PRIVATE_LOCAL ->
          if (authorization.privateLocalDevelopment) {
            MobileAdapterDestinationDecision.ALLOWED
          } else {
            MobileAdapterDestinationDecision.PRIVATE_LOCAL_CONSENT_REQUIRED
          }
      MobileAdapterAddressClass.PUBLIC -> MobileAdapterDestinationDecision.ALLOWED
    }
  }

  override fun toString(): String =
      "MobileAdapterDestinationPolicy(revision=$revision, resolver=[redacted], rules=${ownedRules.size})"

  companion object {
    const val MAX_RULES: Int = 16

    @JvmStatic
    fun offline(revision: Long = 0): MobileAdapterDestinationPolicy =
        MobileAdapterDestinationPolicy(revision, null, emptyList())
  }
}

/** Positive global-unicast classification with an intentionally narrow private-development gate. */
fun classifyMobileAdapterAddress(address: MobileAdapterIpv4Address): MobileAdapterAddressClass {
  val value = address.unsignedBits()
  if (matchesPrefix(value, 10, 8) ||
      matchesPrefix(value, 127, 8) ||
      matchesPrefix(value, 0xac1, 12) ||
      matchesPrefix(value, 0xc0a8, 16)) {
    return MobileAdapterAddressClass.PRIVATE_LOCAL
  }
  if (matchesPrefix(value, 0, 8) ||
      value in 0x6440_0000L..0x647f_ffffL ||
      matchesPrefix(value, 0xa9fe, 16) ||
      matchesPrefix(value, 0xc00000, 24) ||
      matchesPrefix(value, 0xc00002, 24) ||
      matchesPrefix(value, 0xc05863, 24) ||
      value in 0xc612_0000L..0xc613_ffffL ||
      matchesPrefix(value, 0xc63364, 24) ||
      matchesPrefix(value, 0xcb0071, 24) ||
      matchesPrefix(value, 0xe, 4) ||
      matchesPrefix(value, 0xf, 4)) {
    return MobileAdapterAddressClass.HARD_DENY
  }
  return MobileAdapterAddressClass.PUBLIC
}

internal fun canonicalMobileAdapterHost(value: String): String {
  require(value.isNotEmpty() && value.length <= 253) { "Host name length is invalid" }
  require(value == value.trim() && value.none(Char::isISOControl)) {
    "Host name contains whitespace or controls"
  }
  require(value.none { it in "/\\:@?#[]%" }) { "Host name contains a forbidden delimiter" }
  val withoutDot = if (value.endsWith('.')) value.dropLast(1) else value
  require(withoutDot.isNotEmpty() && !withoutDot.endsWith('.')) { "Host name has empty labels" }
  val ascii =
      try {
        IDN.toASCII(withoutDot, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
      } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Host name is not valid IDNA")
      }
  require(ascii.length in 1..253) { "Canonical host name length is invalid" }
  val labels = ascii.split('.')
  require(labels.all { it.length in 1..63 }) { "Host name label length is invalid" }
  return ascii
}

private fun matchesPrefix(value: Long, prefix: Int, bits: Int): Boolean {
  val shift = 32 - bits
  return (value ushr shift) == (prefix.toLong() and ((1L shl bits) - 1))
}
