package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** Stable MANIFEST-v1 mode values. [wireId] rather than enum order is the wire identity. */
enum class V9ManifestMode(val wireId: Int, val rosterMask: Int, val entryCount: Int) {
  NORMAL(1, 0x03, 2),
  FOUR_PLAYER(2, 0x0f, 4);

  fun linkMode(): V9LinkMode =
      if (this == NORMAL) V9LinkMode.NORMAL else V9LinkMode.FOUR_PLAYER

  companion object {
    fun fromWireId(value: Int): V9ManifestMode? = entries.firstOrNull { it.wireId == value }

    fun fromLinkMode(value: V9LinkMode): V9ManifestMode =
        if (value == V9LinkMode.NORMAL) NORMAL else FOUR_PLAYER
  }
}

enum class V9ManifestBootstrap(val wireId: Int) {
  NORMAL(1),
  FAST_FORWARD(2),
  SKIP(3);

  companion object {
    fun fromWireId(value: Int): V9ManifestBootstrap? =
        entries.firstOrNull { it.wireId == value }
  }
}

enum class V9MapperFamily(val wireId: Int, val wireName: String) {
  ROM_ONLY(0x01, "ROM_ONLY"),
  MBC1(0x02, "MBC1"),
  MBC2(0x03, "MBC2"),
  MBC3(0x04, "MBC3"),
  MBC5(0x05, "MBC5"),
  MBC6(0x06, "MBC6"),
  MBC7(0x07, "MBC7"),
  MMM01(0x08, "MMM01"),
  CAMERA(0x09, "CAMERA"),
  HUC1(0x0a, "HUC1"),
  HUC3(0x0b, "HUC3"),
  TAMA5(0x0c, "TAMA5"),
  M161(0x0d, "M161"),
  DATEL(0x0e, "DATEL"),
  UNLICENSED(0x0f, "UNLICENSED"),
  UNKNOWN_KNOWN_HEADER(0x10, "UNKNOWN_KNOWN_HEADER");

  companion object {
    fun fromWireId(value: Int): V9MapperFamily? = entries.firstOrNull { it.wireId == value }
  }
}

enum class V9ManifestSeverity(val wireId: Int) {
  FATAL(1),
  WARNING_REQUIRES_APPROVAL(2),
  INFORMATIONAL(3);

  companion object {
    fun fromWireId(value: Int): V9ManifestSeverity? =
        entries.firstOrNull { it.wireId == value }
  }
}

enum class V9ManifestDifferenceCode(
    val wireId: Int,
    val wireName: String,
    val severity: V9ManifestSeverity,
    val approvalAllowed: Boolean,
) {
  PROTOCOL_CONTEXT(0x0001, "PROTOCOL_CONTEXT", V9ManifestSeverity.FATAL, false),
  STATE_CONTEXT(0x0002, "STATE_CONTEXT", V9ManifestSeverity.FATAL, false),
  PROFILE_IDENTITY(0x0003, "PROFILE_IDENTITY", V9ManifestSeverity.FATAL, false),
  ROSTER_IDENTITY(0x0004, "ROSTER_IDENTITY", V9ManifestSeverity.FATAL, false),
  PRIMARY_ROM_MISSING(
      0x0005,
      "PRIMARY_ROM_MISSING",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  ),
  PRIMARY_ROM_DIFFERENT(
      0x0006,
      "PRIMARY_ROM_DIFFERENT",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  ),
  SLOT_ROM_MISSING(
      0x0007,
      "SLOT_ROM_MISSING",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  ),
  SLOT_ROM_DIFFERENT(
      0x0008,
      "SLOT_ROM_DIFFERENT",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  ),
  BATTERY_OPTIONAL(0x0009, "BATTERY_OPTIONAL", V9ManifestSeverity.INFORMATIONAL, true),
  MATCH(0x000a, "MATCH", V9ManifestSeverity.INFORMATIONAL, false),
  BATTERY_TRANSFER(
      0x000b,
      "BATTERY_TRANSFER",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  ),
  CHECKPOINT_SYNC(
      0x000c,
      "CHECKPOINT_SYNC",
      V9ManifestSeverity.WARNING_REQUIRES_APPROVAL,
      true,
  );

  companion object {
    fun fromWireId(value: Int): V9ManifestDifferenceCode? =
        entries.firstOrNull { it.wireId == value }
  }
}

enum class V9TransferAction(val wireId: Int) {
  OFFER_BY_SOURCE(1),
  REQUEST_BY_TARGET(2);

  companion object {
    fun fromWireId(value: Int): V9TransferAction? = entries.firstOrNull { it.wireId == value }
  }
}

enum class V9TransferClass(val wireId: Int) {
  ROM(1),
  BATTERY(2),
  CHECKPOINT(3);

  companion object {
    fun fromWireId(value: Int): V9TransferClass? = entries.firstOrNull { it.wireId == value }
  }
}

enum class V9TransferAsset(val wireId: Int, val transferClass: V9TransferClass) {
  PRIMARY_ROM(1, V9TransferClass.ROM),
  SLOT_ROM(2, V9TransferClass.ROM),
  BATTERY(3, V9TransferClass.BATTERY),
  CHECKPOINT(4, V9TransferClass.CHECKPOINT);

  companion object {
    fun fromWireId(value: Int): V9TransferAsset? = entries.firstOrNull { it.wireId == value }
  }
}

/** A fixed-size digest with detached ownership and deliberately redacted diagnostics. */
class V9ManifestDigest(bytes: ByteArray) {
  private val owned = bytes.copyOf()

  init {
    require(owned.size == SHA256_BYTES)
  }

  fun bytes(): ByteArray = owned.copyOf()

  internal fun view(): ByteArray = owned

  fun isZero(): Boolean = owned.all { it.toInt() == 0 }

  override fun equals(other: Any?): Boolean =
      other is V9ManifestDigest && MessageDigest.isEqual(owned, other.owned)

  override fun hashCode(): Int = owned.contentHashCode()

  override fun toString(): String = "V9ManifestDigest([redacted])"

  companion object {
    const val SHA256_BYTES = 32

    fun zero(): V9ManifestDigest = V9ManifestDigest(ByteArray(SHA256_BYTES))

    fun sha256(bytes: ByteArray): V9ManifestDigest =
        V9ManifestDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
  }
}

class V9ManifestEntry(
    val player: Int,
    val primaryRomPresent: Boolean,
    val slotRomPresent: Boolean,
    val batteryPresent: Boolean,
    val bootstrap: V9ManifestBootstrap,
    val accessoryFlags: Int,
    val profileId: String,
    val internalTitle: String,
    val cartridgeType: Int,
    val mapperFamily: V9MapperFamily,
    val primaryRomLength: Long,
    val slotRomLength: Long,
    primaryRomSha256: V9ManifestDigest,
    slotRomSha256: V9ManifestDigest,
    bootRomSha256: V9ManifestDigest,
    patchSetSha256: V9ManifestDigest,
) {
  private val primaryDigest = V9ManifestDigest(primaryRomSha256.bytes())
  private val slotDigest = V9ManifestDigest(slotRomSha256.bytes())
  private val bootDigest = V9ManifestDigest(bootRomSha256.bytes())
  private val patchDigest = V9ManifestDigest(patchSetSha256.bytes())

  fun primaryRomSha256(): V9ManifestDigest = V9ManifestDigest(primaryDigest.bytes())

  fun slotRomSha256(): V9ManifestDigest = V9ManifestDigest(slotDigest.bytes())

  fun bootRomSha256(): V9ManifestDigest = V9ManifestDigest(bootDigest.bytes())

  fun patchSetSha256(): V9ManifestDigest = V9ManifestDigest(patchDigest.bytes())

  internal fun primaryDigestView(): ByteArray = primaryDigest.view()

  internal fun slotDigestView(): ByteArray = slotDigest.view()

  internal fun bootDigestView(): ByteArray = bootDigest.view()

  internal fun patchDigestView(): ByteArray = patchDigest.view()

  internal fun contentFlags(): Int =
      (if (primaryRomPresent) 1 else 0) or
          (if (slotRomPresent) 2 else 0) or
          (if (batteryPresent) 4 else 0)

  override fun toString(): String =
      "V9ManifestEntry(player=$player, profile=$profileId, content=[redacted])"
}

data class V9ManifestDifference(
    val code: V9ManifestDifferenceCode,
    val player: Int,
    val proposalId: Long = 0,
) {
  val severity: V9ManifestSeverity get() = code.severity
}

class V9TransferProposal(
    val proposalId: Long,
    val action: V9TransferAction,
    val transferClass: V9TransferClass,
    val asset: V9TransferAsset,
    val ownerPlayer: Int,
    val sourcePlayer: Int,
    val targetPlayer: Int,
    val expectedSize: Long,
    expectedSha256: V9ManifestDigest,
) {
  private val expectedDigest = V9ManifestDigest(expectedSha256.bytes())

  fun expectedSha256(): V9ManifestDigest = V9ManifestDigest(expectedDigest.bytes())

  internal fun expectedDigestView(): ByteArray = expectedDigest.view()

  override fun toString(): String =
      "V9TransferProposal(id=$proposalId, class=$transferClass, asset=$asset, content=[redacted])"
}

class V9Manifest(
    val mode: V9ManifestMode,
    val senderPlayer: Int,
    val rosterGeneration: Long,
    rosterCommitment: V9ManifestDigest,
    entries: List<V9ManifestEntry>,
    differences: List<V9ManifestDifference>,
    proposals: List<V9TransferProposal>,
) {
  private val rosterDigest = V9ManifestDigest(rosterCommitment.bytes())
  val entries: List<V9ManifestEntry> =
      Collections.unmodifiableList(entries.toList())
  val differences: List<V9ManifestDifference> =
      Collections.unmodifiableList(differences.toList())
  val proposals: List<V9TransferProposal> =
      Collections.unmodifiableList(proposals.toList())

  val rosterMask: Int get() = mode.rosterMask

  fun rosterCommitment(): V9ManifestDigest = V9ManifestDigest(rosterDigest.bytes())

  internal fun rosterDigestView(): ByteArray = rosterDigest.view()

  override fun toString(): String =
      "V9Manifest(mode=$mode, sender=$senderPlayer, entries=${entries.size}, " +
          "differences=${differences.size}, proposals=${proposals.size}, content=[redacted])"
}

/**
 * The complete immutable information needed to validate one MANIFEST direction.
 *
 * [wireSource] is the endpoint that actually supplied the frame; it is never inferred from the
 * untrusted sender field.
 */
class V9ManifestValidationContext(
    val mode: V9LinkMode,
    val authenticatedGuest: Int,
    val wireSource: Int,
    val wireTarget: Int,
    val rosterGeneration: Long,
    rosterCommitment: V9ManifestDigest,
    negotiatedCapabilities: Set<V9Capability>,
) {
  private val rosterDigest = V9ManifestDigest(rosterCommitment.bytes())
  val negotiatedCapabilities: Set<V9Capability> =
      Collections.unmodifiableSet(negotiatedCapabilities.toSet())
  val rosterMask: Int = V9ManifestMode.fromLinkMode(mode).rosterMask

  init {
    require(authenticatedGuest in 1..3)
    require(mode != V9LinkMode.NORMAL || authenticatedGuest == 1)
    require(setOf(wireSource, wireTarget) == setOf(0, authenticatedGuest))
    require(rosterGeneration in 1..ProtocolV9.U32_MAX)
  }

  fun rosterCommitment(): V9ManifestDigest = V9ManifestDigest(rosterDigest.bytes())

  internal fun rosterDigestView(): ByteArray = rosterDigest.view()
}

/** A caller-prepared, bounded manifest set. It contains metadata only, never private asset bytes. */
class V9ManifestPlan private constructor(
    val role: V9Role,
    val mode: V9LinkMode,
    manifestsByGuest: Map<Int, V9Manifest>,
) {
  private val manifests = Collections.unmodifiableMap(manifestsByGuest.toMap())

  init {
    require(manifests.isNotEmpty())
    require(manifests.keys.all { validGuest(mode, it) })
    if (role == V9Role.CLIENT) require(manifests.size == 1)
    val expectedMode = V9ManifestMode.fromLinkMode(mode)
    manifests.forEach { (guest, manifest) ->
      require(manifest.mode == expectedMode)
      require(
          manifest.senderPlayer ==
              if (role == V9Role.SERVER) 0 else guest,
      )
    }
    val reference = manifests.values.first()
    require(
        manifests.values.all {
          it.rosterGeneration == reference.rosterGeneration &&
              MessageDigest.isEqual(
                  it.rosterDigestView(),
                  reference.rosterDigestView(),
              )
        },
    ) {
      "v9 manifest plan must use one committed roster generation"
    }
  }

  fun configuredGuests(): Set<Int> =
      Collections.unmodifiableSet(manifests.keys.toSet())

  internal fun manifestFor(connectionRole: V9Role, guest: Int): V9Manifest? {
    if (connectionRole != role) return null
    return manifests[guest]
  }

  companion object {
    fun server(
        mode: V9LinkMode,
        manifestsByGuest: Map<Int, V9Manifest>,
    ): V9ManifestPlan = V9ManifestPlan(V9Role.SERVER, mode, manifestsByGuest)

    fun client(
        mode: V9LinkMode,
        guest: Int,
        manifest: V9Manifest,
    ): V9ManifestPlan = V9ManifestPlan(V9Role.CLIENT, mode, mapOf(guest to manifest))

    private fun validGuest(mode: V9LinkMode, guest: Int): Boolean =
        if (mode == V9LinkMode.NORMAL) guest == 1 else guest in 1..3
  }
}

class V9ManifestPairingBoundary(
    val role: V9Role,
    val mode: V9LinkMode,
    val authenticatedGuest: Int,
    val state: V9LifecycleState,
    val serverManifest: V9Manifest,
    val clientManifest: V9Manifest,
    serverPayloadSha256: V9ManifestDigest,
    clientPayloadSha256: V9ManifestDigest,
    differences: List<V9ManifestDifference>,
    proposals: List<V9TransferProposal>,
) {
  private val serverDigest = V9ManifestDigest(serverPayloadSha256.bytes())
  private val clientDigest = V9ManifestDigest(clientPayloadSha256.bytes())
  val differences: List<V9ManifestDifference> =
      Collections.unmodifiableList(differences.toList())
  val proposals: List<V9TransferProposal> =
      Collections.unmodifiableList(proposals.toList())

  fun serverPayloadSha256(): V9ManifestDigest = V9ManifestDigest(serverDigest.bytes())

  fun clientPayloadSha256(): V9ManifestDigest = V9ManifestDigest(clientDigest.bytes())

  override fun toString(): String =
      "V9ManifestPairingBoundary(role=$role, mode=$mode, guest=$authenticatedGuest, " +
          "state=$state, differences=${differences.size}, proposals=${proposals.size}, " +
          "content=[redacted])"
}

sealed class V9ManifestComparisonResult {
  class Compatible(
      differences: List<V9ManifestDifference>,
      proposals: List<V9TransferProposal>,
  ) : V9ManifestComparisonResult() {
    val differences: List<V9ManifestDifference> =
        Collections.unmodifiableList(differences.toList())
    val proposals: List<V9TransferProposal> =
        Collections.unmodifiableList(proposals.toList())
  }

  class Rejected(val reason: V9ErrorCode) : V9ManifestComparisonResult()
}

/** Exact MANIFEST-v1 codec and pre-consent semantic validator. */
object V9ManifestCodec {
  private const val HEADER_BYTES = 52
  private const val ENTRY_FIXED_BYTES = 144
  private const val DIFF_BYTES = 12
  private const val PROPOSAL_BYTES = 48
  private const val SCHEMA_VERSION = 1
  private const val STATEFILE_VERSION = 2
  private const val COMPATIBILITY_LEVEL = 1
  private const val GROUP_PLAYER = 0xff
  private const val ACCESSORY_MASK = 0x07
  private const val CONTENT_MASK = 0x07
  private const val WARNING_DISPOSITION = 2
  private val ROSTER_LABEL =
      "CoffeeGB-v9-roster-v2".toByteArray(StandardCharsets.US_ASCII)
  private val PROFILE_PATTERN = Regex("[a-z][a-z0-9-]{0,31}")

  fun rosterCommitment(
      mode: V9LinkMode,
      rosterGeneration: Long,
  ): V9ManifestDigest {
    require(rosterGeneration in 1..ProtocolV9.U32_MAX)
    val manifestMode = V9ManifestMode.fromLinkMode(mode)
    val bytes =
        ByteBuffer.allocate(ROSTER_LABEL.size + 2 + 4 + manifestMode.entryCount)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ROSTER_LABEL)
            .put(manifestMode.wireId.toByte())
            .put(manifestMode.rosterMask.toByte())
            .putInt(rosterGeneration.toInt())
            .apply {
              repeat(manifestMode.entryCount) { put(it.toByte()) }
            }
            .array()
    return V9ManifestDigest.sha256(bytes)
  }

  fun encode(
      manifest: V9Manifest,
      context: V9ManifestValidationContext,
  ): ByteArray {
    validate(manifest, context)
    val profileTitleBytes =
        manifest.entries.map {
          it.profileId.toByteArray(StandardCharsets.US_ASCII) to
              it.internalTitle.toByteArray(StandardCharsets.US_ASCII)
        }
    var length = HEADER_BYTES
    profileTitleBytes.forEach { (profile, title) ->
      length = checkedAdd(length, checkedAdd(ENTRY_FIXED_BYTES, checkedAdd(profile.size, title.size)))
    }
    length = checkedAdd(length, checkedMultiply(manifest.differences.size, DIFF_BYTES))
    length = checkedAdd(length, checkedMultiply(manifest.proposals.size, PROPOSAL_BYTES))
    if (length !in
        V9MessageType.MANIFEST.spec.minimumDecodedBytes.toInt()..
            V9MessageType.MANIFEST.spec.maximumDecodedBytes.toInt()) {
      mismatch()
    }
    val output = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
    output.putShort(SCHEMA_VERSION.toShort())
    output.put(manifest.mode.wireId.toByte())
    output.put(manifest.senderPlayer.toByte())
    output.put(manifest.entries.size.toByte())
    output.put(manifest.proposals.size.toByte())
    output.put(manifest.differences.size.toByte())
    output.put(0)
    output.put(ProtocolV9.MAJOR.toByte())
    output.put(STATEFILE_VERSION.toByte())
    output.putShort(COMPATIBILITY_LEVEL.toShort())
    output.putShort(COMPATIBILITY_LEVEL.toShort())
    output.put(manifest.rosterMask.toByte())
    output.put(0)
    output.putInt(manifest.rosterGeneration.toInt())
    output.put(manifest.rosterDigestView())
    manifest.entries.forEachIndexed { index, entry ->
      val (profile, title) = profileTitleBytes[index]
      output.put(entry.player.toByte())
      output.put(entry.contentFlags().toByte())
      output.put(entry.bootstrap.wireId.toByte())
      output.put(entry.accessoryFlags.toByte())
      output.put(profile.size.toByte())
      output.put(title.size.toByte())
      output.put(entry.cartridgeType.toByte())
      output.put(entry.mapperFamily.wireId.toByte())
      output.putInt(entry.primaryRomLength.toInt())
      output.putInt(entry.slotRomLength.toInt())
      output.put(entry.primaryDigestView())
      output.put(entry.slotDigestView())
      output.put(entry.bootDigestView())
      output.put(entry.patchDigestView())
      output.put(profile)
      output.put(title)
    }
    manifest.differences.forEach { difference ->
      output.putShort(difference.code.wireId.toShort())
      output.put(difference.severity.wireId.toByte())
      output.put(difference.player.toByte())
      output.putInt(difference.proposalId.toInt())
      output.putInt(0)
    }
    manifest.proposals.forEach { proposal ->
      output.putInt(proposal.proposalId.toInt())
      output.put(proposal.action.wireId.toByte())
      output.put(proposal.transferClass.wireId.toByte())
      output.put(proposal.asset.wireId.toByte())
      output.put(proposal.ownerPlayer.toByte())
      output.put(proposal.sourcePlayer.toByte())
      output.put(proposal.targetPlayer.toByte())
      output.put(WARNING_DISPOSITION.toByte())
      output.put(0)
      output.putInt(proposal.expectedSize.toInt())
      output.put(proposal.expectedDigestView())
    }
    return output.array()
  }

  fun decode(
      payload: ByteArray,
      context: V9ManifestValidationContext,
  ): V9Manifest {
    if (payload.size !in
        V9MessageType.MANIFEST.spec.minimumDecodedBytes.toInt()..
            V9MessageType.MANIFEST.spec.maximumDecodedBytes.toInt()) {
      mismatch(payload.size)
    }
    if (u16(payload, 0) != SCHEMA_VERSION) mismatch(payload.size)
    val mode = V9ManifestMode.fromWireId(u8(payload, 2)) ?: mismatch(payload.size)
    val sender = u8(payload, 3)
    val entryCount = u8(payload, 4)
    val proposalCount = u8(payload, 5)
    val differenceCount = u8(payload, 6)
    if (entryCount != mode.entryCount ||
        proposalCount > V9Limit.MANIFEST_PROPOSALS.value ||
        differenceCount > V9Limit.MANIFEST_DIFFS.value ||
        u8(payload, 7) != 0 ||
        u8(payload, 8) != ProtocolV9.MAJOR ||
        u8(payload, 9) != STATEFILE_VERSION ||
        u16(payload, 10) != COMPATIBILITY_LEVEL ||
        u16(payload, 12) != COMPATIBILITY_LEVEL ||
        u8(payload, 14) != mode.rosterMask ||
        u8(payload, 15) != 0) {
      mismatch(payload.size)
    }
    val generation = u32(payload, 16)
    if (generation == 0L) mismatch(payload.size)
    val rosterDigest = V9ManifestDigest(payload.copyOfRange(20, 52))
    var offset = HEADER_BYTES
    val entries = ArrayList<V9ManifestEntry>(entryCount)
    repeat(entryCount) {
      if (payload.size - offset < ENTRY_FIXED_BYTES) mismatch(payload.size)
      val profileLength = u8(payload, offset + 4)
      val titleLength = u8(payload, offset + 5)
      if (profileLength !in 1..V9Limit.PROFILE_ID_BYTES.value.toInt() ||
          titleLength !in 0..16) {
        mismatch(payload.size)
      }
      val entryLength =
          checkedAdd(ENTRY_FIXED_BYTES, checkedAdd(profileLength, titleLength), payload.size)
      if (entryLength > payload.size - offset) mismatch(payload.size)
      val contentFlags = u8(payload, offset + 1)
      if (contentFlags and CONTENT_MASK.inv() != 0) mismatch(payload.size)
      val bootstrap =
          V9ManifestBootstrap.fromWireId(u8(payload, offset + 2))
              ?: mismatch(payload.size)
      val mapper =
          V9MapperFamily.fromWireId(u8(payload, offset + 7))
              ?: mismatch(payload.size)
      val profileOffset = offset + ENTRY_FIXED_BYTES
      val titleOffset = profileOffset + profileLength
      if (!strictProfile(payload, profileOffset, profileLength) ||
          !strictTitle(payload, titleOffset, titleLength)) {
        mismatch(payload.size)
      }
      val profile =
          String(payload, profileOffset, profileLength, StandardCharsets.US_ASCII)
      try {
        HardwareProfileRegistry.resolve(profile)
      } catch (_: IllegalArgumentException) {
        mismatch(payload.size)
      }
      entries +=
          V9ManifestEntry(
              player = u8(payload, offset),
              primaryRomPresent = contentFlags and 1 != 0,
              slotRomPresent = contentFlags and 2 != 0,
              batteryPresent = contentFlags and 4 != 0,
              bootstrap = bootstrap,
              accessoryFlags = u8(payload, offset + 3),
              profileId = profile,
              internalTitle =
                  String(payload, titleOffset, titleLength, StandardCharsets.US_ASCII),
              cartridgeType = u8(payload, offset + 6),
              mapperFamily = mapper,
              primaryRomLength = u32(payload, offset + 8),
              slotRomLength = u32(payload, offset + 12),
              primaryRomSha256 =
                  V9ManifestDigest(payload.copyOfRange(offset + 16, offset + 48)),
              slotRomSha256 =
                  V9ManifestDigest(payload.copyOfRange(offset + 48, offset + 80)),
              bootRomSha256 =
                  V9ManifestDigest(payload.copyOfRange(offset + 80, offset + 112)),
              patchSetSha256 =
                  V9ManifestDigest(payload.copyOfRange(offset + 112, offset + 144)),
          )
      offset += entryLength
    }
    val differences = ArrayList<V9ManifestDifference>(differenceCount)
    repeat(differenceCount) {
      if (payload.size - offset < DIFF_BYTES) mismatch(payload.size)
      val code =
          V9ManifestDifferenceCode.fromWireId(u16(payload, offset))
              ?: mismatch(payload.size)
      val severity =
          V9ManifestSeverity.fromWireId(u8(payload, offset + 2))
              ?: mismatch(payload.size)
      if (severity != code.severity || u32(payload, offset + 8) != 0L) {
        mismatch(payload.size)
      }
      differences +=
          V9ManifestDifference(code, u8(payload, offset + 3), u32(payload, offset + 4))
      offset += DIFF_BYTES
    }
    val proposals = ArrayList<V9TransferProposal>(proposalCount)
    repeat(proposalCount) {
      if (payload.size - offset < PROPOSAL_BYTES) mismatch(payload.size)
      val action =
          V9TransferAction.fromWireId(u8(payload, offset + 4))
              ?: mismatch(payload.size)
      val transferClass =
          V9TransferClass.fromWireId(u8(payload, offset + 5))
              ?: mismatch(payload.size)
      val asset =
          V9TransferAsset.fromWireId(u8(payload, offset + 6))
              ?: mismatch(payload.size)
      if (u8(payload, offset + 10) != WARNING_DISPOSITION ||
          u8(payload, offset + 11) != 0) {
        mismatch(payload.size)
      }
      proposals +=
          V9TransferProposal(
              proposalId = u32(payload, offset),
              action = action,
              transferClass = transferClass,
              asset = asset,
              ownerPlayer = u8(payload, offset + 7),
              sourcePlayer = u8(payload, offset + 8),
              targetPlayer = u8(payload, offset + 9),
              expectedSize = u32(payload, offset + 12),
              expectedSha256 =
                  V9ManifestDigest(payload.copyOfRange(offset + 16, offset + 48)),
          )
      offset += PROPOSAL_BYTES
    }
    if (offset != payload.size) mismatch(payload.size)
    val manifest =
        V9Manifest(
            mode,
            sender,
            generation,
            rosterDigest,
            entries,
            differences,
            proposals,
        )
    validate(manifest, context)
    return manifest
  }

  fun validate(
      manifest: V9Manifest,
      context: V9ManifestValidationContext,
  ) {
    val expectedMode = V9ManifestMode.fromLinkMode(context.mode)
    if (manifest.mode != expectedMode ||
        manifest.senderPlayer != context.wireSource ||
        manifest.rosterMask != context.rosterMask ||
        manifest.rosterGeneration != context.rosterGeneration ||
        !MessageDigest.isEqual(manifest.rosterDigestView(), context.rosterDigestView()) ||
        !MessageDigest.isEqual(
            manifest.rosterDigestView(),
            rosterCommitment(context.mode, context.rosterGeneration).view(),
        )) {
      mismatch()
    }
    if (manifest.entries.size != expectedMode.entryCount ||
        manifest.entries.size > V9Limit.MANIFEST_ENTRIES.value ||
        manifest.proposals.size > V9Limit.MANIFEST_PROPOSALS.value ||
        manifest.differences.size > V9Limit.MANIFEST_DIFFS.value) {
      mismatch()
    }
    if (context.mode == V9LinkMode.FOUR_PLAYER &&
        V9Capability.FOUR_PLAYER_V1 !in context.negotiatedCapabilities) {
      mismatch()
    }
    val expectedPlayers = (0 until expectedMode.entryCount).toList()
    if (manifest.entries.map { it.player } != expectedPlayers) mismatch()
    manifest.entries.forEach(::validateEntry)

    val differenceKeys = mutableSetOf<Pair<V9ManifestDifferenceCode, Int>>()
    val warningIds = mutableSetOf<Long>()
    manifest.differences.forEach { difference ->
      if (difference.player !in expectedPlayers ||
          !differenceKeys.add(difference.code to difference.player) ||
          difference.severity != difference.code.severity ||
          difference.severity == V9ManifestSeverity.WARNING_REQUIRES_APPROVAL &&
              (difference.proposalId == 0L || !warningIds.add(difference.proposalId)) ||
          difference.severity != V9ManifestSeverity.WARNING_REQUIRES_APPROVAL &&
              difference.proposalId != 0L) {
        mismatch()
      }
    }
    manifest.differences.groupBy { it.player }.values.forEach { playerDifferences ->
      if (playerDifferences.any { it.code == V9ManifestDifferenceCode.MATCH } &&
          playerDifferences.size != 1) {
        mismatch()
      }
    }

    val proposalIds = mutableSetOf<Long>()
    val proposalKeys = mutableSetOf<Triple<V9TransferClass, V9TransferAsset, Int>>()
    val classCounts = mutableMapOf<V9TransferClass, Int>()
    val entryByPlayer = manifest.entries.associateBy { it.player }
    manifest.proposals.forEach { proposal ->
      if (proposal.proposalId !in 1..ProtocolV9.U32_MAX ||
          !proposalIds.add(proposal.proposalId) ||
          proposal.transferClass != proposal.asset.transferClass ||
          proposal.sourcePlayer !in expectedPlayers ||
          proposal.targetPlayer !in expectedPlayers ||
          proposal.sourcePlayer == proposal.targetPlayer ||
          setOf(proposal.sourcePlayer, proposal.targetPlayer) !=
              setOf(0, context.authenticatedGuest) ||
          !proposalKeys.add(
              Triple(proposal.transferClass, proposal.asset, proposal.ownerPlayer),
          ) ||
          proposal.action == V9TransferAction.OFFER_BY_SOURCE &&
              manifest.senderPlayer != proposal.sourcePlayer ||
          proposal.action == V9TransferAction.REQUEST_BY_TARGET &&
              manifest.senderPlayer != proposal.targetPlayer) {
        mismatch()
      }
      val count = Math.addExact(classCounts.getOrDefault(proposal.transferClass, 0), 1)
      classCounts[proposal.transferClass] = count
      if (proposal.transferClass == V9TransferClass.ROM && count > 2 ||
          proposal.transferClass != V9TransferClass.ROM && count > 1) {
        mismatch()
      }
      validateProposal(proposal, entryByPlayer, context)
    }
    if (warningIds != proposalIds) mismatch()
    val proposalsById = manifest.proposals.associateBy { it.proposalId }
    manifest.differences
        .filter { it.severity == V9ManifestSeverity.WARNING_REQUIRES_APPROVAL }
        .forEach { difference ->
          val proposal = proposalsById[difference.proposalId] ?: mismatch()
          val expectedAsset =
              when (difference.code) {
                V9ManifestDifferenceCode.PRIMARY_ROM_MISSING,
                V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT ->
                  V9TransferAsset.PRIMARY_ROM
                V9ManifestDifferenceCode.SLOT_ROM_MISSING,
                V9ManifestDifferenceCode.SLOT_ROM_DIFFERENT ->
                  V9TransferAsset.SLOT_ROM
                V9ManifestDifferenceCode.BATTERY_TRANSFER ->
                  V9TransferAsset.BATTERY
                V9ManifestDifferenceCode.CHECKPOINT_SYNC ->
                  V9TransferAsset.CHECKPOINT
                else -> mismatch()
              }
          if (proposal.asset != expectedAsset ||
              expectedAsset != V9TransferAsset.CHECKPOINT &&
                  proposal.ownerPlayer != difference.player ||
              expectedAsset == V9TransferAsset.CHECKPOINT &&
                  (proposal.ownerPlayer != GROUP_PLAYER ||
                      difference.player != context.authenticatedGuest)) {
            mismatch()
          }
        }
  }

  private fun validateEntry(entry: V9ManifestEntry) {
    if (entry.player !in 0..3 ||
        entry.accessoryFlags and ACCESSORY_MASK.inv() != 0 ||
        entry.profileId.length !in 1..V9Limit.PROFILE_ID_BYTES.value.toInt() ||
        !PROFILE_PATTERN.matches(entry.profileId) ||
        entry.internalTitle.length > 16 ||
        entry.internalTitle.any { it.code !in 0x20..0x7e } ||
        entry.cartridgeType !in 0..0xff) {
      mismatch()
    }
    try {
      HardwareProfileRegistry.resolve(entry.profileId)
    } catch (_: IllegalArgumentException) {
      mismatch()
    }
    validateContentPresence(
        entry.primaryRomPresent,
        entry.primaryRomLength,
        entry.primaryDigestView(),
    )
    validateContentPresence(
        entry.slotRomPresent,
        entry.slotRomLength,
        entry.slotDigestView(),
    )
    if (entry.bootstrap == V9ManifestBootstrap.SKIP && !entry.bootRomSha256().isZero() ||
        entry.bootstrap != V9ManifestBootstrap.SKIP && entry.bootRomSha256().isZero()) {
      mismatch()
    }
    if (entry.contentFlags() and CONTENT_MASK.inv() != 0) mismatch()
  }

  private fun validateContentPresence(present: Boolean, length: Long, digest: ByteArray) {
    if (present) {
      if (length !in 1..V9Limit.ROM_BYTES.value || digest.all { it.toInt() == 0 }) {
        mismatch()
      }
    } else if (length != 0L || digest.any { it.toInt() != 0 }) {
      mismatch()
    }
  }

  private fun validateProposal(
      proposal: V9TransferProposal,
      entryByPlayer: Map<Int, V9ManifestEntry>,
      context: V9ManifestValidationContext,
  ) {
    when (proposal.asset) {
      V9TransferAsset.PRIMARY_ROM,
      V9TransferAsset.SLOT_ROM -> {
        if (V9Capability.ROM_TRANSFER_V1 !in context.negotiatedCapabilities ||
            proposal.ownerPlayer !in entryByPlayer ||
            proposal.expectedSize !in 1..V9Limit.ROM_BYTES.value ||
            proposal.expectedSha256().isZero()) {
          mismatch()
        }
        val owner = requireNotNull(entryByPlayer[proposal.ownerPlayer])
        val present =
            if (proposal.asset == V9TransferAsset.PRIMARY_ROM) {
              owner.primaryRomPresent
            } else {
              owner.slotRomPresent
            }
        val size =
            if (proposal.asset == V9TransferAsset.PRIMARY_ROM) {
              owner.primaryRomLength
            } else {
              owner.slotRomLength
            }
        val digest =
            if (proposal.asset == V9TransferAsset.PRIMARY_ROM) {
              owner.primaryDigestView()
            } else {
              owner.slotDigestView()
            }
        if (!present ||
            proposal.expectedSize != size ||
            !MessageDigest.isEqual(proposal.expectedDigestView(), digest)) {
          mismatch()
        }
      }
      V9TransferAsset.BATTERY -> {
        val owner = entryByPlayer[proposal.ownerPlayer]
        if (V9Capability.BATTERY_TRANSFER_V1 !in context.negotiatedCapabilities ||
            owner == null ||
            !owner.batteryPresent ||
            proposal.expectedSize !in 1..V9Limit.BATTERY_BYTES.value ||
            proposal.expectedSha256().isZero()) {
          mismatch()
        }
      }
      V9TransferAsset.CHECKPOINT -> {
        if (proposal.ownerPlayer != GROUP_PLAYER ||
            proposal.expectedSize != 0L ||
            !proposal.expectedSha256().isZero() ||
            V9Capability.STATEFILE_V2 !in context.negotiatedCapabilities) {
          mismatch()
        }
      }
    }
    if (context.mode == V9LinkMode.FOUR_PLAYER &&
        V9Capability.FOUR_PLAYER_V1 !in context.negotiatedCapabilities) {
      mismatch()
    }
  }

  private fun strictProfile(bytes: ByteArray, offset: Int, length: Int): Boolean =
      length > 0 &&
          length.toLong() <= V9Limit.PROFILE_ID_BYTES.value &&
          (offset until offset + length).all {
            val value = bytes[it].toInt() and 0xff
            value in 'a'.code..'z'.code ||
                value in '0'.code..'9'.code ||
                value == '-'.code
          } &&
          bytes[offset].toInt() and 0xff in 'a'.code..'z'.code

  private fun strictTitle(bytes: ByteArray, offset: Int, length: Int): Boolean =
      length <= 16 &&
          (offset until offset + length).all {
            bytes[it].toInt() and 0xff in 0x20..0x7e
          }

  private fun checkedAdd(left: Int, right: Int, decisive: Int = 0): Int =
      try {
        Math.addExact(left, right)
      } catch (_: ArithmeticException) {
        mismatch(decisive)
      }

  private fun checkedMultiply(left: Int, right: Int): Int =
      try {
        Math.multiplyExact(left, right)
      } catch (_: ArithmeticException) {
        mismatch()
      }

  private fun u8(bytes: ByteArray, offset: Int): Int =
      bytes[offset].toInt() and 0xff

  private fun u16(bytes: ByteArray, offset: Int): Int =
      (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

  private fun u32(bytes: ByteArray, offset: Int): Long =
      (u8(bytes, offset).toLong() shl 24) or
          (u8(bytes, offset + 1).toLong() shl 16) or
          (u8(bytes, offset + 2).toLong() shl 8) or
          u8(bytes, offset + 3).toLong()

  private fun mismatch(decisive: Int = 0): Nothing =
      throw V9ProtocolException(V9ErrorCode.MANIFEST_MISMATCH, decisive)
}

/** Exact pair comparison. It never authorizes a transfer; proposals remain pending consent. */
object V9ManifestCompatibility {
  fun compare(
      server: V9Manifest,
      client: V9Manifest,
      authenticatedGuest: Int,
      protocolCapabilityCompatible: Boolean = true,
  ): V9ManifestComparisonResult {
    if (authenticatedGuest !in 1..3) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
    }
    if (!protocolCapabilityCompatible) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.CAPABILITY_MISMATCH)
    }
    if (server.mode != client.mode ||
        server.rosterMask != client.rosterMask ||
        server.rosterGeneration != client.rosterGeneration ||
        !MessageDigest.isEqual(server.rosterDigestView(), client.rosterDigestView()) ||
        server.entries.map { it.player } != client.entries.map { it.player }) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
    }

    val differences = server.differences + client.differences
    val proposals = server.proposals + client.proposals
    if (differences.map { it.code to it.player }.toSet().size != differences.size ||
        proposals.map { it.proposalId }.toSet().size != proposals.size ||
        proposals.map { Triple(it.transferClass, it.asset, it.ownerPlayer) }.toSet().size !=
            proposals.size) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
    }
    val declaredByPlayer = differences.groupBy { it.player }
    val proposalsByAssetOwner =
        proposals.associateBy { it.asset to it.ownerPlayer }
    val serverByPlayer = server.entries.associateBy { it.player }
    val clientByPlayer = client.entries.associateBy { it.player }

    for (player in serverByPlayer.keys.sorted()) {
      val left = requireNotNull(serverByPlayer[player])
      val right =
          clientByPlayer[player]
              ?: return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
      val actual =
          expectedDifferences(
              left,
              right,
              proposalsByAssetOwner,
              player,
              authenticatedGuest,
          )
      val declared = declaredByPlayer[player].orEmpty()
      if (actual.isEmpty()) {
        if (declared.any { it.code != V9ManifestDifferenceCode.MATCH } ||
            declared.count { it.code == V9ManifestDifferenceCode.MATCH } > 1) {
          return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
        }
      } else {
        if (declared.any { it.code == V9ManifestDifferenceCode.MATCH } ||
            declared.map { it.code }.toSet() != actual) {
          return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
        }
      }
    }
    if (declaredByPlayer.keys.any { it !in serverByPlayer.keys }) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
    }
    if (differences.any { it.severity == V9ManifestSeverity.FATAL }) {
      return V9ManifestComparisonResult.Rejected(V9ErrorCode.MANIFEST_MISMATCH)
    }
    return V9ManifestComparisonResult.Compatible(differences, proposals)
  }

  private fun expectedDifferences(
      left: V9ManifestEntry,
      right: V9ManifestEntry,
      proposals: Map<Pair<V9TransferAsset, Int>, V9TransferProposal>,
      player: Int,
      authenticatedGuest: Int,
  ): Set<V9ManifestDifferenceCode> {
    val result = linkedSetOf<V9ManifestDifferenceCode>()
    if (left.profileId != right.profileId ||
        left.bootstrap != right.bootstrap ||
        left.accessoryFlags != right.accessoryFlags ||
        left.cartridgeType != right.cartridgeType ||
        left.mapperFamily != right.mapperFamily ||
        !MessageDigest.isEqual(left.bootDigestView(), right.bootDigestView()) ||
        !MessageDigest.isEqual(left.patchDigestView(), right.patchDigestView())) {
      result += V9ManifestDifferenceCode.PROFILE_IDENTITY
    }
    when {
      left.primaryRomPresent != right.primaryRomPresent ->
        result += V9ManifestDifferenceCode.PRIMARY_ROM_MISSING
      left.primaryRomPresent &&
          (left.primaryRomLength != right.primaryRomLength ||
              !MessageDigest.isEqual(left.primaryDigestView(), right.primaryDigestView())) ->
        result += V9ManifestDifferenceCode.PRIMARY_ROM_DIFFERENT
    }
    when {
      left.slotRomPresent != right.slotRomPresent ->
        result += V9ManifestDifferenceCode.SLOT_ROM_MISSING
      left.slotRomPresent &&
          (left.slotRomLength != right.slotRomLength ||
              !MessageDigest.isEqual(left.slotDigestView(), right.slotDigestView())) ->
        result += V9ManifestDifferenceCode.SLOT_ROM_DIFFERENT
    }
    if (proposals.containsKey(V9TransferAsset.BATTERY to player)) {
      result += V9ManifestDifferenceCode.BATTERY_TRANSFER
    } else if (left.batteryPresent || right.batteryPresent) {
      result += V9ManifestDifferenceCode.BATTERY_OPTIONAL
    }
    if (player == authenticatedGuest &&
        proposals.containsKey(V9TransferAsset.CHECKPOINT to 0xff)) {
      result += V9ManifestDifferenceCode.CHECKPOINT_SYNC
    }
    return result
  }
}
