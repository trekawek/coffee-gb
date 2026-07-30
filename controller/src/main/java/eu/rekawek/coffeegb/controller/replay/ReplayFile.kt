package eu.rekawek.coffeegb.controller.replay

import java.util.Collections

/** One positive, reduced rational in the exact replay clock identity. */
data class ReplayClockRatio(
    val numerator: Long,
    val denominator: Long,
) {
  init {
    require(numerator > 0) { "Replay clock numerator must be positive" }
    require(denominator > 0) { "Replay clock denominator must be positive" }
    require(gcd(numerator, denominator) == 1L) { "Replay clock rational must be reduced" }
  }

  private companion object {
    fun gcd(left: Long, right: Long): Long {
      var a = left
      var b = right
      while (b != 0L) {
        val next = a % b
        a = b
        b = next
      }
      return a
    }
  }
}

/** Exact clock-domain identity; no derived or rounded frequency participates in compatibility. */
data class ReplayClockIdentity(
    val ticksPerSecond: ReplayClockRatio,
    val controllerFramesPerSecond: ReplayClockRatio,
)

/**
 * ROM, profile, clock, bootstrap, and behavior identity required before deterministic playback.
 * Byte arrays are owned and every accessor returns a copy.
 */
class ReplayIdentity(
    primaryRomSha256: ByteArray,
    slotRomSha256: ByteArray?,
    val canonicalProfileId: String,
    val clocks: ReplayClockIdentity,
    val bootstrapFlags: Long,
    val behaviorFlags: Long,
    val replaySemanticsVersion: Int = REPLAY_SEMANTICS_VERSION,
    val requiredStateFileVersion: Int = REQUIRED_STATE_FILE_VERSION,
) {
  private val ownedPrimaryRomSha256 = digest(primaryRomSha256, "Primary ROM")
  private val ownedSlotRomSha256 = slotRomSha256?.let { digest(it, "Slot ROM") }

  init {
    require(canonicalProfileId.isNotEmpty()) { "Replay hardware profile ID cannot be empty" }
    require(strictUtf8Length(canonicalProfileId) <= ReplayLimits.MAX_PROFILE_ID_BYTES) {
      "Replay hardware profile ID exceeds ${ReplayLimits.MAX_PROFILE_ID_BYTES} UTF-8 bytes"
    }
    require(replaySemanticsVersion == REPLAY_SEMANTICS_VERSION) {
      "Replay semantics version must be $REPLAY_SEMANTICS_VERSION"
    }
    require(requiredStateFileVersion == REQUIRED_STATE_FILE_VERSION) {
      "Required StateFile version must be $REQUIRED_STATE_FILE_VERSION"
    }
  }

  val primaryRomSha256: ByteArray
    get() = ownedPrimaryRomSha256.clone()

  val slotRomSha256: ByteArray?
    get() = ownedSlotRomSha256?.clone()

  override fun equals(other: Any?): Boolean =
      other is ReplayIdentity &&
          ownedPrimaryRomSha256.contentEquals(other.ownedPrimaryRomSha256) &&
          nullableContentEquals(ownedSlotRomSha256, other.ownedSlotRomSha256) &&
          canonicalProfileId == other.canonicalProfileId &&
          clocks == other.clocks &&
          bootstrapFlags == other.bootstrapFlags &&
          behaviorFlags == other.behaviorFlags &&
          replaySemanticsVersion == other.replaySemanticsVersion &&
          requiredStateFileVersion == other.requiredStateFileVersion

  override fun hashCode(): Int {
    var result = ownedPrimaryRomSha256.contentHashCode()
    result = 31 * result + (ownedSlotRomSha256?.contentHashCode() ?: 0)
    result = 31 * result + canonicalProfileId.hashCode()
    result = 31 * result + clocks.hashCode()
    result = 31 * result + bootstrapFlags.hashCode()
    result = 31 * result + behaviorFlags.hashCode()
    result = 31 * result + replaySemanticsVersion
    result = 31 * result + requiredStateFileVersion
    return result
  }

  override fun toString(): String =
      "ReplayIdentity(primary=${hex(ownedPrimaryRomSha256)}, " +
          "slot=${ownedSlotRomSha256?.let(::hex) ?: "absent"}, " +
          "profile=$canonicalProfileId, clocks=$clocks, bootstrapFlags=$bootstrapFlags, " +
          "behaviorFlags=$behaviorFlags, semantics=$replaySemanticsVersion, " +
          "stateFile=$requiredStateFileVersion)"

  companion object {
    const val REPLAY_SEMANTICS_VERSION = 1
    const val REQUIRED_STATE_FILE_VERSION = 2

    private fun digest(value: ByteArray, label: String): ByteArray {
      require(value.size == ReplayLimits.SHA256_BYTES) {
        "$label SHA-256 must contain exactly ${ReplayLimits.SHA256_BYTES} bytes"
      }
      return value.clone()
    }

    private fun nullableContentEquals(left: ByteArray?, right: ByteArray?): Boolean =
        if (left == null || right == null) left == null && right == null
        else left.contentEquals(right)

    private fun hex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}

enum class ReplayInitialMode(val id: Int) {
  BOOT_REFERENCE(1),
  EMBEDDED_SESSION_STATE(2);

  companion object {
    internal fun fromId(id: Int): ReplayInitialMode =
        entries.firstOrNull { it.id == id }
            ?: throw ReplayDecodeException(
                ReplayDecodeReason.MALFORMED_ENUM,
                "Unknown replay initial mode $id",
            )
  }
}

data class ReplayInitialConditions(
    val mode: ReplayInitialMode,
    val rtcEpochMillis: Long,
    val initialTick: Long = 0,
    val initialFrame: Long = 0,
) {
  init {
    require(initialTick >= 0) { "Replay initial tick cannot be negative" }
    require(initialFrame >= 0) { "Replay initial frame cannot be negative" }
    if (mode == ReplayInitialMode.BOOT_REFERENCE) {
      require(initialTick == 0L && initialFrame == 0L) {
        "Boot-reference replay must begin at tick and frame zero"
      }
    }
  }
}

/** Stable phase tags define where an input mask is installed relative to its executed tick. */
enum class ReplayInputPhase(val id: Int) {
  LEGACY_P1_BEFORE_TICK(1),
  PHYSICAL_JOYPAD_SAMPLE(2);

  companion object {
    internal fun fromId(id: Int): ReplayInputPhase =
        entries.firstOrNull { it.id == id }
            ?: throw ReplayDecodeException(
                ReplayDecodeReason.MALFORMED_ENUM,
                "Unknown replay input phase $id",
            )
  }
}

data class ReplayInputRecord(
    val tick: Long,
    val phase: ReplayInputPhase,
    val player: Int,
    val absoluteMask: Int,
    val changedMask: Int,
) {
  init {
    require(tick in 0..ReplayLimits.MAX_TIMELINE_TICK) {
      "Replay input tick is outside the v1 timeline range"
    }
    require(player in 0..3) { "Replay input player must be in 0..3" }
    require(absoluteMask in 0..0xff) { "Replay input absolute mask must be one byte" }
    require(changedMask in 0..0xff) { "Replay input changed mask must be one byte" }
  }
}

/** Eight independent SHA-256 values stored at every deterministic checkpoint. */
class ReplayStateHashes(
    full: ByteArray,
    cpu: ByteArray,
    memory: ByteArray,
    ppu: ByteArray,
    apu: ByteArray,
    mapper: ByteArray,
    serial: ByteArray,
    input: ByteArray,
) {
  private val owned =
      arrayOf(full, cpu, memory, ppu, apu, mapper, serial, input).mapIndexed { index, value ->
        require(value.size == ReplayLimits.SHA256_BYTES) {
          "Replay ${NAMES[index]} hash must contain exactly ${ReplayLimits.SHA256_BYTES} bytes"
        }
        value.clone()
      }

  val full: ByteArray get() = owned[FULL].clone()
  val cpu: ByteArray get() = owned[CPU].clone()
  val memory: ByteArray get() = owned[MEMORY].clone()
  val ppu: ByteArray get() = owned[PPU].clone()
  val apu: ByteArray get() = owned[APU].clone()
  val mapper: ByteArray get() = owned[MAPPER].clone()
  val serial: ByteArray get() = owned[SERIAL].clone()
  val input: ByteArray get() = owned[INPUT].clone()

  internal fun copyDigests(): List<ByteArray> = owned.map { it.clone() }

  override fun equals(other: Any?): Boolean =
      other is ReplayStateHashes && owned.indices.all { owned[it].contentEquals(other.owned[it]) }

  override fun hashCode(): Int = owned.fold(1) { result, value -> 31 * result + value.contentHashCode() }

  override fun toString(): String =
      NAMES.indices.joinToString(prefix = "ReplayStateHashes(", postfix = ")") { index ->
        "${NAMES[index]}=${owned[index].joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
      }

  private companion object {
    const val FULL = 0
    const val CPU = 1
    const val MEMORY = 2
    const val PPU = 3
    const val APU = 4
    const val MAPPER = 5
    const val SERIAL = 6
    const val INPUT = 7
    val NAMES = arrayOf("full", "CPU", "memory", "PPU", "APU", "mapper", "serial", "input")
  }
}

data class ReplayCheckpoint(
    val tick: Long,
    val frame: Long,
    val hashes: ReplayStateHashes,
) {
  init {
    require(tick in 0..ReplayLimits.MAX_TIMELINE_TICK) {
      "Replay checkpoint tick is outside the v1 timeline range"
    }
    require(frame >= 0) { "Replay checkpoint frame cannot be negative" }
  }
}

data class ReplayMetadata(
    val producerVersion: String? = null,
    val createdAtEpochMillis: Long? = null,
    val note: String? = null,
) {
  init {
    producerVersion?.let {
      require(strictUtf8Length(it) <= ReplayLimits.MAX_PRODUCER_VERSION_BYTES) {
        "Replay producer version exceeds ${ReplayLimits.MAX_PRODUCER_VERSION_BYTES} UTF-8 bytes"
      }
    }
    note?.let {
      require(it.length <= ReplayLimits.MAX_NOTE_CHARS) {
        "Replay note exceeds ${ReplayLimits.MAX_NOTE_CHARS} characters"
      }
      require(strictUtf8Length(it) <= ReplayLimits.MAX_METADATA_BYTES) {
        "Replay note exceeds the metadata byte limit"
      }
    }
  }
}

/** Fully decoded, detached CGBR replay. It owns every supplied collection and byte array. */
class ReplayFile(
    val identity: ReplayIdentity,
    val initialConditions: ReplayInitialConditions,
    inputs: Collection<ReplayInputRecord>,
    checkpoints: Collection<ReplayCheckpoint>,
    val metadata: ReplayMetadata? = null,
    embeddedState: ByteArray? = null,
) {
  init {
    require(inputs.size <= ReplayLimits.MAX_INPUT_RECORDS) {
      "Replay input count exceeds ${ReplayLimits.MAX_INPUT_RECORDS}"
    }
    require(checkpoints.isNotEmpty()) { "Replay requires at least one checkpoint" }
    require(checkpoints.size <= ReplayLimits.MAX_CHECKPOINTS) {
      "Replay checkpoint count exceeds ${ReplayLimits.MAX_CHECKPOINTS}"
    }
  }

  val inputs: List<ReplayInputRecord> = Collections.unmodifiableList(ArrayList(inputs))
  val checkpoints: List<ReplayCheckpoint> = Collections.unmodifiableList(ArrayList(checkpoints))
  private val ownedEmbeddedState = embeddedState?.clone()

  init {
    for (index in 1 until this.inputs.size) {
      require(this.inputs[index - 1].tick <= this.inputs[index].tick) {
        "Replay inputs must have monotonic ticks"
      }
    }
    require(this.inputs.all { it.tick >= initialConditions.initialTick }) {
      "Replay input precedes the initial tick"
    }
    for (index in 1 until this.checkpoints.size) {
      val previous = this.checkpoints[index - 1]
      val current = this.checkpoints[index]
      require(previous.tick < current.tick && previous.frame <= current.frame) {
        "Replay checkpoints must have strictly increasing ticks and monotonic frames"
      }
    }
    require(
        this.checkpoints.all {
          it.tick >= initialConditions.initialTick && it.frame >= initialConditions.initialFrame
        }) {
      "Replay checkpoint precedes the initial conditions"
    }
    require(this.inputs.isEmpty() || this.checkpoints.last().tick >= this.inputs.last().tick) {
      "Final replay checkpoint must include every input tick"
    }
    require(
        (initialConditions.mode == ReplayInitialMode.EMBEDDED_SESSION_STATE) ==
            (ownedEmbeddedState != null)) {
      "Embedded replay mode requires exactly one embedded StateFile"
    }
    require((ownedEmbeddedState?.size ?: 0) <= ReplayLimits.MAX_EMBEDDED_STATE_BYTES) {
      "Embedded StateFile exceeds ${ReplayLimits.MAX_EMBEDDED_STATE_BYTES} bytes"
    }
  }

  val embeddedState: ByteArray?
    get() = ownedEmbeddedState?.clone()

  override fun equals(other: Any?): Boolean =
      other is ReplayFile &&
          identity == other.identity &&
          initialConditions == other.initialConditions &&
          inputs == other.inputs &&
          checkpoints == other.checkpoints &&
          metadata == other.metadata &&
          nullableContentEquals(ownedEmbeddedState, other.ownedEmbeddedState)

  override fun hashCode(): Int {
    var result = identity.hashCode()
    result = 31 * result + initialConditions.hashCode()
    result = 31 * result + inputs.hashCode()
    result = 31 * result + checkpoints.hashCode()
    result = 31 * result + (metadata?.hashCode() ?: 0)
    result = 31 * result + (ownedEmbeddedState?.contentHashCode() ?: 0)
    return result
  }

  private fun nullableContentEquals(left: ByteArray?, right: ByteArray?): Boolean =
      if (left == null || right == null) left == null && right == null
      else left.contentEquals(right)
}

enum class ReplaySectionCompression {
  NONE,
  DEFLATE,
}

data class ReplaySectionInspection(
    val id: Int,
    val version: Int,
    val required: Boolean,
    val compression: ReplaySectionCompression,
    val encodedLength: Long,
    val decodedLength: Long,
)

class ReplayFileInspection(
    val formatVersion: Int,
    val requiredFeatureFlags: Long,
    val optionalFeatureFlags: Long,
    val encodedPayloadLength: Long,
    val decodedSectionBytes: Long,
    val checksumValid: Boolean,
    val identity: ReplayIdentity,
    val initialConditions: ReplayInitialConditions,
    val inputCount: Int,
    val checkpointCount: Int,
    val finalTick: Long,
    val finalFrame: Long,
    val metadata: ReplayMetadata?,
    val hasEmbeddedState: Boolean,
    sections: Collection<ReplaySectionInspection>,
) {
  val sections: List<ReplaySectionInspection> =
      Collections.unmodifiableList(ArrayList(sections))

  fun render(): String = buildString {
    appendLine("magic=CGBR format=$formatVersion checksum=$checksumValid")
    appendLine(
        "required-features=0x${requiredFeatureFlags.toString(16)} " +
            "optional-features=0x${optionalFeatureFlags.toString(16)} " +
            "payload=$encodedPayloadLength decoded-sections=$decodedSectionBytes " +
            "profile=${renderInspectionText(identity.canonicalProfileId)}")
    appendLine(
        "initial=${initialConditions.mode.name} tick=${initialConditions.initialTick} " +
            "frame=${initialConditions.initialFrame} rtc=${initialConditions.rtcEpochMillis}")
    appendLine(
        "inputs=$inputCount checkpoints=$checkpointCount final-tick=$finalTick " +
            "final-frame=$finalFrame embedded-state=$hasEmbeddedState")
    metadata?.let {
      appendLine(
          "producer=${renderInspectionText(it.producerVersion)} " +
              "created=${it.createdAtEpochMillis ?: "absent"} " +
              "note=${renderInspectionText(it.note)}")
    }
    sections.forEach {
      appendLine(
          "section=${it.id} version=${it.version} required=${it.required} " +
              "compression=${it.compression.name} encoded=${it.encodedLength} " +
              "decoded=${it.decodedLength}")
    }
  }
}

private fun strictUtf8Length(value: String): Int {
  var index = 0
  while (index < value.length) {
    val character = value[index]
    when {
      character.isHighSurrogate() -> {
        require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
          "Replay text is not valid Unicode"
        }
        index += 2
      }
      character.isLowSurrogate() ->
          throw IllegalArgumentException("Replay text is not valid Unicode")
      else -> index++
    }
  }
  return value.toByteArray(Charsets.UTF_8).size
}

private fun renderInspectionText(value: String?): String {
  if (value == null) return "absent"
  return buildString {
    append('"')
    value.forEach { character ->
      when (character) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else ->
            if (
                character.isISOControl() ||
                    character == '\u2028' ||
                    character == '\u2029' ||
                    Character.getType(character) == Character.FORMAT.toInt()
            ) {
              append("\\u")
              append(character.code.toString(16).padStart(4, '0'))
            } else {
              append(character)
            }
      }
    }
    append('"')
  }
}
