package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.state.LinkedSessionStateRoot
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.state.StateBootstrapMode
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateDecodeReason
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateRootKind
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Stable CHECKPOINT-v1 kind tags. Declaration order is never a wire identity. */
enum class V9CheckpointKind(val wireId: Int, val rootKind: StateRootKind) {
  MACHINE(0, StateRootKind.MACHINE),
  SESSION(1, StateRootKind.SESSION),
  LINKED_SESSION(2, StateRootKind.LINKED_SESSION);

  companion object {
    fun fromWireId(value: Int): V9CheckpointKind? = entries.firstOrNull { it.wireId == value }
  }
}

data class V9CheckpointMetadata(
    val kind: V9CheckpointKind,
    val slotMask: Int,
    val ownerPlayer: Int,
    /** Raw unsigned-u64 wire bits. */
    val frame: Long,
    val proposalId: Long,
)

internal class V9CheckpointDeclaration(
    val metadata: V9CheckpointMetadata,
    val stateLength: Int,
    stateDigest: ByteArray,
) {
  private val digest = stateDigest.copyOf()
  fun stateDigest(): ByteArray = digest.copyOf()
  internal fun digestView(): ByteArray = digest
}

/** Exact CHECKPOINT payload codec. It does not decode or reconstruct the StateFile graph. */
object V9CheckpointCodec {
  const val PREFIX_BYTES = 20
  const val MINIMUM_STATEFILE_BYTES = 68

  fun encode(metadata: V9CheckpointMetadata, stateFile: ByteArray): ByteArray {
    validateMetadata(metadata)
    require(stateFile.size in MINIMUM_STATEFILE_BYTES..V9Limit.STATEFILE_ENCODED_BYTES.value.toInt())
    val size = Math.addExact(PREFIX_BYTES, stateFile.size)
    return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        .put(metadata.kind.wireId.toByte())
        .put(metadata.slotMask.toByte())
        .put(metadata.ownerPlayer.toByte())
        .put(0)
        .putLong(metadata.frame)
        .putInt(stateFile.size)
        .putInt(metadata.proposalId.toInt())
        .put(stateFile)
        .array()
  }

  internal fun decodeDeclaration(payload: ByteArray): V9CheckpointDeclaration {
    if (payload.size < PREFIX_BYTES) malformed()
    val kind = V9CheckpointKind.fromWireId(u8(payload, 0)) ?: topology()
    val metadata =
        V9CheckpointMetadata(
            kind,
            u8(payload, 1),
            u8(payload, 2),
            ByteBuffer.wrap(payload, 4, 8).order(ByteOrder.BIG_ENDIAN).long,
            u32(payload, 16),
        )
    if (payload[3].toInt() != 0) malformed()
    validateMetadata(metadata)
    val stateLength = u32(payload, 12)
    if (stateLength !in
            MINIMUM_STATEFILE_BYTES.toLong()..V9Limit.STATEFILE_ENCODED_BYTES.value ||
        stateLength != payload.size.toLong() - PREFIX_BYTES ||
        stateLength > Int.MAX_VALUE) {
      malformed()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(payload, PREFIX_BYTES, stateLength.toInt())
    return V9CheckpointDeclaration(metadata, stateLength.toInt(), digest.digest())
  }

  internal fun copyStateFile(payload: ByteArray, declaration: V9CheckpointDeclaration): ByteArray {
    if (payload.size != Math.addExact(PREFIX_BYTES, declaration.stateLength)) malformed()
    return payload.copyOfRange(PREFIX_BYTES, payload.size)
  }

  private fun validateMetadata(value: V9CheckpointMetadata) {
    if (value.slotMask !in 1..0x0f ||
        value.ownerPlayer !in 0..3 ||
        value.slotMask and (1 shl value.ownerPlayer) == 0 ||
        value.proposalId !in 1..ProtocolV9.U32_MAX) {
      topology()
    }
  }

  private fun malformed(): Nothing =
      throw V9ProtocolException(V9ErrorCode.STATEFILE_MALFORMED, 0)

  private fun topology(): Nothing =
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
}

/** Immutable authorization derived from the exact two-manifest consent pair. */
internal class V9CheckpointAuthorization(
    val boundary: V9ManifestPairingBoundary,
    val proposal: V9TransferProposal,
) {
  init {
    require(proposal.transferClass == V9TransferClass.CHECKPOINT)
    require(proposal.asset == V9TransferAsset.CHECKPOINT)
    require(proposal.ownerPlayer == 0xff)
    require(proposal.expectedSize == 0L)
    require(proposal.expectedSha256().isZero())
  }
}

/**
 * Directional, manifest-bound checkpoint grant. [preflight] never consumes a use. Only a fully
 * decoded, target-prepared checkpoint may commit its returned ticket.
 */
internal class V9CheckpointGrant(
    private val authorization: V9CheckpointAuthorization,
) : Closeable {
  private val lock = Any()
  private var used = 0
  private var lastFrame: Long? = null
  private var inFlight = false
  private var closed = false

  fun preflight(
      declaration: V9CheckpointDeclaration,
      wireSource: Int,
      wireTarget: Int,
      channel: Long,
  ): Ticket {
    synchronized(lock) {
      val boundary = authorization.boundary
      val proposal = authorization.proposal
      val metadata = declaration.metadata
      val exactMask = if (boundary.mode == V9LinkMode.NORMAL) 0x03 else 0x0f
      val legalRoot =
          if (boundary.mode == V9LinkMode.NORMAL) {
            metadata.kind in setOf(V9CheckpointKind.MACHINE, V9CheckpointKind.SESSION) &&
                Integer.bitCount(metadata.slotMask) == 1 &&
                metadata.slotMask and exactMask == metadata.slotMask &&
                metadata.ownerPlayer == wireSource &&
                channel == metadata.ownerPlayer.toLong() + 1
          } else {
            metadata.kind == V9CheckpointKind.LINKED_SESSION &&
                metadata.slotMask == 0x0f &&
                channel == ProtocolV9.GROUP_CHANNEL
          }
      if (closed || inFlight || used >= V9Limit.CHECKPOINTS_PER_DIRECTIONAL_GRANT.value ||
          setOf(wireSource, wireTarget) != setOf(0, boundary.authenticatedGuest) ||
          wireSource != proposal.sourcePlayer || wireTarget != proposal.targetPlayer ||
          metadata.proposalId != proposal.proposalId ||
          boundary.serverManifest.rosterMask != exactMask ||
          boundary.clientManifest.rosterMask != exactMask ||
          boundary.serverManifest.rosterGeneration != boundary.clientManifest.rosterGeneration ||
          boundary.serverManifest.rosterCommitment() != boundary.clientManifest.rosterCommitment() ||
          !legalRoot ||
          lastFrame?.let { java.lang.Long.compareUnsigned(metadata.frame, it) <= 0 } == true) {
        throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      }
      inFlight = true
      return Ticket(this, declaration)
    }
  }

  internal fun used(): Int = synchronized(lock) { used }

  private fun release(ticket: Ticket, commit: Boolean) {
    synchronized(lock) {
      if (!ticket.active.compareAndSet(true, false)) return
      inFlight = false
      if (commit && !closed) {
        used = Math.addExact(used, 1)
        lastFrame = ticket.declaration.metadata.frame
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      closed = true
      inFlight = false
    }
  }

  class Ticket internal constructor(
      private val grant: V9CheckpointGrant,
      internal val declaration: V9CheckpointDeclaration,
  ) : Closeable {
    internal val active = AtomicBoolean(true)
    fun commit() = grant.release(this, true)
    override fun close() = grant.release(this, false)
  }
}

class V9ValidatedCheckpoint internal constructor(
    val metadata: V9CheckpointMetadata,
    val stateFile: StateFile,
    stateDigest: ByteArray,
) {
  private val digest = stateDigest.copyOf()
  fun stateDigest(): ByteArray = digest.copyOf()

  override fun toString(): String =
      "V9ValidatedCheckpoint(kind=${metadata.kind}, mask=${metadata.slotMask}, " +
          "owner=${metadata.ownerPlayer}, frame=${java.lang.Long.toUnsignedString(metadata.frame)}, " +
          "content=[redacted])"
}

fun interface V9CheckpointPrepareCompletion {
  /** A non-null transaction means all target-dependent preparation completed without mutation. */
  fun complete(prepared: V9PreparedCheckpoint?, failure: V9ErrorCode?)
}

interface V9PreparedCheckpoint : Closeable {
  /** Schedules the already-prepared transaction's one atomic live commit at the frame safe point. */
  fun commit(completion: V9CheckpointCommitCompletion)
}

fun interface V9CheckpointCommitCompletion {
  /** Null means the prepared transaction committed atomically. */
  fun complete(failure: V9ErrorCode?)
}

fun interface V9GameplayCompletion {
  /** Null means the event was accepted and applied at the controller's frame safe point. */
  fun complete(failure: V9ErrorCode?)
}

interface V9CheckpointTarget {
  /** Canonical roster player identities. Normal targets may contain players 0 and 1. */
  fun expectedIdentities(): List<StateIdentityEntry>

  /** Must arrange target-dependent preparation at the owning emulation frame safe point. */
  fun prepare(checkpoint: V9ValidatedCheckpoint, completion: V9CheckpointPrepareCompletion)
}

fun interface V9CheckpointProvider {
  /** Returns one direct, complete CGBS StateFile. Ownership transfers to the foundation. */
  fun capture(request: V9CheckpointRequest): ByteArray
}

data class V9CheckpointRequest(
    val kind: V9CheckpointKind,
    val slotMask: Int,
    val ownerPlayer: Int,
    val frame: Long,
)

enum class V9RuntimeMessageKind { INPUT, RESET, STOP }

data class V9InputState(
    val frame: Long,
    val player: Int,
    val buttonMask: Int,
    val intraFrameOrder: Int,
)

data class V9RuntimeControl(
    val kind: V9RuntimeMessageKind,
    val frame: Long,
    val player: Int,
)

interface V9GameplayTarget {
  fun input(value: V9InputState, completion: V9GameplayCompletion)
  fun control(value: V9RuntimeControl, completion: V9GameplayCompletion)
  fun disconnected(player: Int) {}
}

object V9GameplayCodec {
  const val PAYLOAD_BYTES = 16

  fun encodeInput(value: V9InputState): ByteArray {
    validateFramePlayer(value.frame, value.player)
    require(value.buttonMask in 0..0xff)
    require(value.intraFrameOrder in 0..0xffff)
    return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
        .putLong(value.frame)
        .put(value.player.toByte())
        .put(value.buttonMask.toByte())
        .putShort(value.intraFrameOrder.toShort())
        .putInt(0)
        .array()
  }

  fun decodeInput(payload: ByteArray, channel: Long): V9InputState {
    if (payload.size != PAYLOAD_BYTES || payload.sliceArray(12..15).any { it.toInt() != 0 }) {
      malformed()
    }
    val value =
        V9InputState(
            ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.BIG_ENDIAN).long,
            u8(payload, 8),
            u8(payload, 9),
            u16(payload, 10),
        )
    validateFramePlayer(value.frame, value.player)
    if (channel != value.player.toLong() + 1) topology()
    return value
  }

  fun encodeControl(value: V9RuntimeControl): ByteArray {
    require(value.kind != V9RuntimeMessageKind.INPUT)
    validateFramePlayer(value.frame, value.player)
    return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
        .putLong(value.frame).put(value.player.toByte()).put(ByteArray(7)).array()
  }

  fun decodeControl(
      type: V9MessageType,
      payload: ByteArray,
      channel: Long,
  ): V9RuntimeControl {
    if (type !in setOf(V9MessageType.RESET, V9MessageType.STOP) ||
        payload.size != PAYLOAD_BYTES ||
        payload.sliceArray(9..15).any { it.toInt() != 0 }) {
      malformed()
    }
    val value =
        V9RuntimeControl(
            if (type == V9MessageType.RESET) V9RuntimeMessageKind.RESET
            else V9RuntimeMessageKind.STOP,
            ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.BIG_ENDIAN).long,
            u8(payload, 8),
        )
    validateFramePlayer(value.frame, value.player)
    if (channel != value.player.toLong() + 1) topology()
    return value
  }

  private fun validateFramePlayer(frame: Long, player: Int) {
    if (frame < 0 || player !in 0..3) topology()
  }

  private fun malformed(): Nothing =
      throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, 0)
  private fun topology(): Nothing =
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
}

class V9GuestPlayPlan(
    val checkpointTarget: V9CheckpointTarget,
    val gameplayTarget: V9GameplayTarget,
    val checkpointProvider: V9CheckpointProvider? = null,
    val initialKind: V9CheckpointKind,
    val initialOwnerPlayer: Int,
    val initialFrame: Long,
    val sessionIds: V9SessionIdSource = V9SessionIdSource.SECURE,
    val fourPlayerCoordinator: V9FourPlayerCoordinator? = null,
) {
  init {
    require(initialOwnerPlayer in 0..3)
    require(initialFrame >= 0)
  }
}

class V9PlayPlan private constructor(
    val role: V9Role,
    val mode: V9LinkMode,
    plansByGuest: Map<Int, V9GuestPlayPlan>,
) {
  private val plans = Collections.unmodifiableMap(plansByGuest.toMap())

  init {
    require(plans.isNotEmpty())
    require(plans.keys.all { if (mode == V9LinkMode.NORMAL) it == 1 else it in 1..3 })
    if (role == V9Role.CLIENT) require(plans.size == 1)
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      require(plans.keys == setOf(1, 2, 3))
      val coordinator = plans.values.first().fourPlayerCoordinator
      require(coordinator != null && plans.values.all { it.fourPlayerCoordinator === coordinator })
    }
    val requiredInitialKind =
        if (mode == V9LinkMode.NORMAL) V9CheckpointKind.MACHINE
        else V9CheckpointKind.LINKED_SESSION
    require(plans.values.all { it.initialKind == requiredInitialKind }) {
      "v9 initial checkpoint root does not match link mode"
    }
  }

  internal fun forGuest(connectionRole: V9Role, guest: Int): V9GuestPlayPlan? =
      if (connectionRole == role) plans[guest] else null

  companion object {
    fun server(mode: V9LinkMode, plansByGuest: Map<Int, V9GuestPlayPlan>) =
        V9PlayPlan(V9Role.SERVER, mode, plansByGuest)
    fun client(mode: V9LinkMode, guest: Int, plan: V9GuestPlayPlan) =
        V9PlayPlan(V9Role.CLIENT, mode, mapOf(guest to plan))
  }
}

fun interface V9SessionIdSource {
  fun nextId(): Long

  companion object {
    val SECURE = V9SessionIdSource {
      var value: Long
      do value = SecureRandom().nextLong() while (value == 0L)
      value
    }
  }
}

data class V9ActiveBoundary(
    val role: V9Role,
    val mode: V9LinkMode,
    val authenticatedGuest: Int,
    val sessionId: Long,
    val initialFrame: Long,
)

/** Server-side exact-roster barrier shared by the three isolated four-player TCP sessions. */
class V9FourPlayerCoordinator {
  private val lock = Any()
  private val prepared = mutableMapOf<Int, Prepared>()
  private val ready = mutableMapOf<Int, () -> Unit>()
  private val started = mutableSetOf<Int>()
  private val activated = mutableSetOf<Int>()
  private var committedDigest: ByteArray? = null
  private var committedFrame: Long? = null

  fun prepared(guest: Int, frame: Long, digest: ByteArray, start: () -> Unit): Closeable {
    require(guest in 1..3 && digest.size == 32)
    val callbacks: List<() -> Unit>
    synchronized(lock) {
      if (prepared.containsKey(guest)) throw IllegalStateException("v9 guest is already prepared")
      committedDigest?.let {
        if (!MessageDigest.isEqual(it, digest) || committedFrame != frame) {
          throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
        }
      }
      prepared[guest] = Prepared(frame, digest.copyOf(), start)
      if (committedDigest != null) {
        started.add(guest)
        callbacks = listOf(start)
      } else if (prepared.keys == setOf(1, 2, 3)) {
        val first = prepared.getValue(1)
        if (prepared.values.any {
              it.frame != first.frame || !MessageDigest.isEqual(it.digest, first.digest)
            }) {
          prepared.remove(guest)?.digest?.fill(0)
          throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
        }
        committedDigest = first.digest.copyOf()
        committedFrame = first.frame
        callbacks = prepared.toSortedMap().filterKeys(started::add).values.map { it.start }
      } else callbacks = emptyList()
    }
    callbacks.forEach { it() }
    val active = AtomicBoolean(true)
    return Closeable {
      if (active.compareAndSet(true, false)) synchronized(lock) {
        prepared.remove(guest)?.digest?.fill(0)
        ready.remove(guest)
        started.remove(guest)
        activated.remove(guest)
      }
    }
  }

  fun ready(guest: Int, activate: () -> Unit) {
    val callbacks: List<() -> Unit>
    synchronized(lock) {
      if (guest !in prepared || ready.putIfAbsent(guest, activate) != null) {
        throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
      }
      callbacks =
          if (activated.isNotEmpty()) {
            activated.add(guest)
            listOf(activate)
          } else if (ready.keys == setOf(1, 2, 3)) {
            ready.toSortedMap().filterKeys(activated::add).values.toList()
          } else emptyList()
    }
    callbacks.forEach { it() }
  }

  internal fun candidateCount(): Int = synchronized(lock) { prepared.size }

  private class Prepared(val frame: Long, val digest: ByteArray, val start: () -> Unit)
}

internal object V9CheckpointStateValidation {
  fun validateManifestIdentities(
      authorization: V9CheckpointAuthorization,
      expectedRosterIdentities: List<StateIdentityEntry>,
  ) {
    val boundary = authorization.boundary
    val expectedPlayers =
        if (boundary.mode == V9LinkMode.NORMAL) setOf(0, 1) else setOf(0, 1, 2, 3)
    if (expectedRosterIdentities.map { it.player }.toSet() != expectedPlayers ||
        expectedRosterIdentities.any { it.identity == null }) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    val server = boundary.serverManifest.entries.associateBy { it.player }
    val client = boundary.clientManifest.entries.associateBy { it.player }
    val authoritative =
        if (authorization.proposal.sourcePlayer == 0) server else client
    for (entry in expectedRosterIdentities) {
      val identity = checkNotNull(entry.identity)
      val left = server[entry.player]
          ?: throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
      val right = client[entry.player]
          ?: throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
      if (!matchesProfile(identity, left) || !matchesProfile(identity, right)) {
        throw V9ProtocolException(V9ErrorCode.PROFILE_MISMATCH, 0)
      }
      if (!matchesRom(identity, authoritative.getValue(entry.player))) {
        throw V9ProtocolException(V9ErrorCode.ROM_MISMATCH, 0)
      }
    }
  }

  fun decodeAndValidate(
      bytes: ByteArray,
      declaration: V9CheckpointDeclaration,
      expectedRosterIdentities: List<StateIdentityEntry>,
  ): StateFile {
    if (bytes.size < V9CheckpointCodec.MINIMUM_STATEFILE_BYTES ||
        !bytes.copyOfRange(0, minOf(4, bytes.size))
            .contentEquals(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte()))) {
      throw V9ProtocolException(V9ErrorCode.STATEFILE_VERSION, 0)
    }
    if (u16(bytes, 4) != StateCodec.LATEST_FORMAT_VERSION) {
      throw V9ProtocolException(V9ErrorCode.STATEFILE_VERSION, 0)
    }
    val inspection =
        try {
          StateCodec.inspectNetworkState(bytes)
        } catch (failure: StateDecodeException) {
          throw V9ProtocolException(map(failure.reason), 0)
        }
    if (inspection.formatVersion != StateCodec.LATEST_FORMAT_VERSION) {
      throw V9ProtocolException(V9ErrorCode.STATEFILE_VERSION, 0)
    }
    if (inspection.encodedPayloadLength > StateLimits.NETPLAY_STATE_FILE_BYTES ||
        inspection.decodedPayloadLength > StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES) {
      throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 0)
    }
    if (inspection.rootKind != declaration.metadata.kind.rootKind) {
      throw V9ProtocolException(V9ErrorCode.ROOT_KIND_MISMATCH, 0)
    }
    val expectedIdentities =
        if (declaration.metadata.kind == V9CheckpointKind.LINKED_SESSION) {
          expectedRosterIdentities
        } else {
          val identity =
              expectedRosterIdentities.singleOrNull {
                it.player == declaration.metadata.ownerPlayer && it.identity != null
              }?.identity ?: throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
          listOf(StateIdentityEntry(0, identity))
        }
    val expectedMask =
        if (declaration.metadata.kind == V9CheckpointKind.LINKED_SESSION) {
          expectedIdentities.fold(0) { mask, entry ->
            if (entry.identity == null) mask else mask or (1 shl entry.player)
          }
        } else {
          1 shl declaration.metadata.ownerPlayer
        }
    if (expectedMask != declaration.metadata.slotMask) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    val file =
        try {
          StateCodec.decodeNetworkState(bytes)
        } catch (failure: StateDecodeException) {
          throw V9ProtocolException(map(failure.reason), 0)
        }
    try {
      StateCodec.validateForTarget(file, declaration.metadata.kind.rootKind, expectedIdentities)
    } catch (failure: StateDecodeException) {
      throw V9ProtocolException(map(failure.reason), 0)
    }
    if (file.root is LinkedSessionStateRoot && file.root.linked.frame != declaration.metadata.frame) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    return file
  }

  private fun matchesProfile(identity: eu.rekawek.coffeegb.controller.state.MachineIdentity,
      entry: V9ManifestEntry): Boolean {
    val profile = identity.profile
    val bootstrap =
        when (profile.bootstrapMode) {
          StateBootstrapMode.NORMAL -> V9ManifestBootstrap.NORMAL
          StateBootstrapMode.FAST_FORWARD -> V9ManifestBootstrap.FAST_FORWARD
          StateBootstrapMode.SKIP -> V9ManifestBootstrap.SKIP
        }
    val accessoryFlags =
        (if (profile.mealybugDmgBlob) 1 else 0) or
            (if (profile.codeBreakerRumble) 2 else 0) or
            (if (profile.displaySgbBorder) 4 else 0)
    return entry.profileId == profile.canonicalProfileId &&
        entry.bootstrap == bootstrap && entry.accessoryFlags == accessoryFlags
  }

  private fun matchesRom(
      identity: eu.rekawek.coffeegb.controller.state.MachineIdentity,
      authoritative: V9ManifestEntry,
  ): Boolean {
    val primary = identity.primaryRom.copyBytes()
    val slot = identity.slotRom?.copyBytes()
    try {
      val primaryMatches = authoritative.primaryRomPresent &&
          MessageDigest.isEqual(authoritative.primaryDigestView(), primary)
      val slotMatches =
          if (slot == null) !authoritative.slotRomPresent
          else authoritative.slotRomPresent &&
              MessageDigest.isEqual(authoritative.slotDigestView(), slot)
      return primaryMatches && slotMatches
    } finally {
      primary.fill(0)
      slot?.fill(0)
    }
  }

  private fun map(reason: StateDecodeReason): V9ErrorCode = when (reason) {
    StateDecodeReason.UNSUPPORTED_FORMAT_VERSION,
    StateDecodeReason.UNSUPPORTED_SECTION_VERSION -> V9ErrorCode.STATEFILE_VERSION
    StateDecodeReason.CORRUPT_CHECKSUM -> V9ErrorCode.CHECKSUM_MISMATCH
    StateDecodeReason.ROM_MISMATCH,
    StateDecodeReason.SLOT_ROM_MISMATCH -> V9ErrorCode.ROM_MISMATCH
    StateDecodeReason.HARDWARE_PROFILE_MISMATCH -> V9ErrorCode.PROFILE_MISMATCH
    StateDecodeReason.TRAILING_DATA -> V9ErrorCode.TRAILING_DATA
    StateDecodeReason.LIMIT_EXCEEDED -> V9ErrorCode.LIMIT_EXCEEDED
    StateDecodeReason.TARGET_STATE_MISMATCH -> V9ErrorCode.TOPOLOGY_MISMATCH
    else -> V9ErrorCode.STATEFILE_MALFORMED
  }
}

private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
