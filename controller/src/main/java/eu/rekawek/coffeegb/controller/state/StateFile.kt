package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.HardwareProfile as CoreHardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** Stable v1 envelope root tags. Their numeric IDs, not enum ordinals, are serialized. */
enum class StateRootKind(val id: Int) {
  MACHINE(1),
  SESSION(2),
  LINKED_SESSION(3);

  companion object {
    internal fun fromId(id: Int): StateRootKind =
        entries.firstOrNull { it.id == id }
            ?: throw StateDecodeException(
                StateDecodeReason.MALFORMED_TAG,
                "Unknown StateFile root kind $id",
            )
  }
}

enum class StateCompression(val flag: Int) {
  NONE(0),
  DEFLATE(1);
}

/** Stable machine bootstrap tags, independent of the core enum's declaration order. */
enum class StateBootstrapMode(val id: Int) {
  NORMAL(1),
  FAST_FORWARD(2),
  SKIP(3);

  companion object {
    internal fun fromId(id: Int): StateBootstrapMode =
        entries.firstOrNull { it.id == id }
            ?: throw StateDecodeException(
                StateDecodeReason.MALFORMED_ENUM,
                "Unknown bootstrap mode $id",
            )

    internal fun fromCore(value: Gameboy.BootstrapMode): StateBootstrapMode =
        when (value) {
          Gameboy.BootstrapMode.NORMAL -> NORMAL
          Gameboy.BootstrapMode.FAST_FORWARD -> FAST_FORWARD
          Gameboy.BootstrapMode.SKIP -> SKIP
        }
  }
}

enum class StateDecodeReason {
  INVALID_MAGIC,
  UNSUPPORTED_FORMAT_VERSION,
  UNSUPPORTED_SECTION_VERSION,
  UNSUPPORTED_FLAGS,
  ROM_MISMATCH,
  SLOT_ROM_MISMATCH,
  HARDWARE_PROFILE_MISMATCH,
  CORRUPT_CHECKSUM,
  TRUNCATED,
  LIMIT_EXCEEDED,
  MALFORMED_STRUCTURE,
  MALFORMED_TAG,
  MALFORMED_ENUM,
  MALFORMED_UTF8,
  MISSING_REQUIRED_SECTION,
  DUPLICATE_SECTION,
  UNKNOWN_REQUIRED_SECTION,
  TRAILING_DATA,
  COMPRESSION_ERROR,
  TARGET_STATE_MISMATCH,
}

class StateDecodeException(
    val reason: StateDecodeReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class StateEncodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Immutable 32-byte SHA-256 value. */
class RomIdentity(bytes: ByteArray) {
  init {
    require(bytes.size == SHA256_BYTES) { "ROM identity must contain exactly 32 bytes" }
  }

  private val owned = bytes.clone()

  fun copyBytes(): ByteArray = owned.clone()

  fun hex(): String = owned.joinToString("") { "%02x".format(it.toInt() and 0xff) }

  override fun equals(other: Any?): Boolean =
      other is RomIdentity && owned.contentEquals(other.owned)

  override fun hashCode(): Int = owned.contentHashCode()

  override fun toString(): String = hex()

  companion object {
    const val SHA256_BYTES = 32

    fun parseHex(value: String): RomIdentity {
      require(value.length == SHA256_BYTES * 2) { "SHA-256 text must contain 64 hex digits" }
      return RomIdentity(
          ByteArray(SHA256_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
          })
    }
  }
}

/** Stable portable behavior profile. Diagnostic build versions are intentionally not part of it. */
data class HardwareProfile(
    val version: Int,
    val hardware: MachineHardwareState,
    val bootstrapMode: StateBootstrapMode,
    val cgb0Revision: Boolean,
    val mealybugDmgBlob: Boolean,
    val codeBreakerRumble: Boolean,
    val displaySgbBorder: Boolean,
    /** Null only for the released v1 identity schema, whose coarse tag derives the canonical ID. */
    val explicitProfileId: String? = null,
) {
  init {
    require(version == VERSION) { "Unsupported hardware profile version $version" }
  }

  /** Stable Phase-3 identity derived without changing the StateFile-v1 byte layout. */
  val canonicalProfileId: String
    get() =
        explicitProfileId
            ?: when (hardware) {
              MachineHardwareState.DMG -> HardwareProfileRegistry.DMG.id()
              MachineHardwareState.CGB ->
                  HardwareProfileRegistry.cgbRevision(cgb0Revision).id()
              MachineHardwareState.SGB -> HardwareProfileRegistry.SGB.id()
            }

  fun isCompatibleWith(other: HardwareProfile): Boolean =
      canonicalProfileId == other.canonicalProfileId &&
          bootstrapMode == other.bootstrapMode &&
          mealybugDmgBlob == other.mealybugDmgBlob &&
          codeBreakerRumble == other.codeBreakerRumble &&
          displaySgbBorder == other.displaySgbBorder

  companion object {
    const val VERSION = 1
  }
}

data class MachineIdentity(
    val primaryRom: RomIdentity,
    val slotRom: RomIdentity?,
    val profile: HardwareProfile,
)

data class StateIdentityEntry(val player: Int, val identity: MachineIdentity?)

/** Version selection shared by local state capture and the frozen protocol-v8 boundary. */
object StateProfilePolicy {
  fun requiresExplicitIdentity(profile: CoreHardwareProfile): Boolean {
    val registered = HardwareProfileRegistry.requireRegistered(profile)
    return registered.family() == CoreHardwareProfile.Family.SGB ||
        registered == HardwareProfileRegistry.MGB
  }

  fun protocolV8Representable(profile: CoreHardwareProfile): Boolean =
      !requiresExplicitIdentity(profile)
}

sealed interface StateFileRoot {
  val kind: StateRootKind
}

data class MachineStateRoot(val machine: MachineState) : StateFileRoot {
  override val kind = StateRootKind.MACHINE
}

data class SessionStateRoot(val session: SessionState) : StateFileRoot {
  override val kind = StateRootKind.SESSION
}

data class LinkedSessionStateRoot(val linked: LinkedSessionState) : StateFileRoot {
  override val kind = StateRootKind.LINKED_SESSION
}

data class StateDiagnosticMetadata(
    val coreVersion: String,
    val buildId: String,
)

/** Fully decoded StateFile. It contains detached DTOs only and cannot mutate an emulator. */
class StateFile(
    identities: Collection<StateIdentityEntry>,
    val root: StateFileRoot,
    val diagnostics: StateDiagnosticMetadata? = null,
    val formatVersion: Int =
        if (identities.any {
          it.identity?.profile?.explicitProfileId != null
        }) 2 else 1,
) {
  init {
    require(identities.size <= StateLimits.PORTABLE_MAX_LINKED_PLAYERS) {
      "Portable state identity count exceeds ${StateLimits.PORTABLE_MAX_LINKED_PLAYERS}"
    }
    require(formatVersion == 1 || formatVersion == 2) {
      "Unsupported portable format version $formatVersion"
    }
  }

  val identities: List<StateIdentityEntry> =
      Collections.unmodifiableList(ArrayList(identities))

  override fun equals(other: Any?): Boolean =
      other is StateFile &&
          formatVersion == other.formatVersion &&
          identities == other.identities &&
          root == other.root &&
          diagnostics == other.diagnostics

  override fun hashCode(): Int = arrayOf(formatVersion, identities, root, diagnostics).contentHashCode()
}

data class StateSectionInspection(
    val id: Int,
    val version: Int,
    val required: Boolean,
    val encodedLength: Long,
)

data class StateFileInspection(
    val formatVersion: Int,
    val rootKind: StateRootKind,
    val compression: StateCompression,
    val encodedPayloadLength: Long,
    val decodedPayloadLength: Long,
    val checksumValid: Boolean,
    val identities: List<StateIdentityEntry>,
    val sections: List<StateSectionInspection>,
    val diagnostics: StateDiagnosticMetadata?,
) {
  fun render(): String = buildString {
    appendLine("magic=CGBS format=$formatVersion root=${rootKind.name}")
    appendLine(
        "compression=${compression.name} encoded=$encodedPayloadLength decoded=$decodedPayloadLength checksum=$checksumValid")
    identities.forEach { entry ->
      val identity = entry.identity
      if (identity == null) {
        appendLine("player=${entry.player} absent")
      } else {
        appendLine(
            "player=${entry.player} rom=${identity.primaryRom.hex()} slot=${identity.slotRom?.hex() ?: "absent"} " +
                "profile=${identity.profile.canonicalProfileId} details=${identity.profile}")
      }
    }
    diagnostics?.let {
      appendLine("core=${it.coreVersion} build=${it.buildId}")
    }
    sections.forEach {
      appendLine(
          "section=${it.id} version=${it.version} required=${it.required} length=${it.encodedLength}")
    }
  }
}

/** Pure, pre-mutation compatibility classification for a decoded detached StateFile. */
enum class StateCompatibilityStatus {
  COMPATIBLE,
  ROOT_MISMATCH,
  ROM_MISMATCH,
  SLOT_ROM_MISMATCH,
  HARDWARE_PROFILE_MISMATCH,
  INCOMPATIBLE,
}

data class StateCompatibilityResult(
    val status: StateCompatibilityStatus,
    val reason: StateDecodeReason?,
    val detail: String?,
) {
  val isCompatible: Boolean
    get() = status == StateCompatibilityStatus.COMPATIBLE
}

/** ROM/profile identity helpers shared by capture and pre-apply validation. */
object StateIdentity {
  fun from(configuration: Gameboy.GameboyConfiguration): MachineIdentity =
      configuration.hardwareProfile.let { resolvedProfile ->
        val hardware =
            when (resolvedProfile.family()) {
              CoreHardwareProfile.Family.DMG -> MachineHardwareState.DMG
              CoreHardwareProfile.Family.CGB -> MachineHardwareState.CGB
              CoreHardwareProfile.Family.SGB -> MachineHardwareState.SGB
            }
        val datel =
            configuration.rom.cartridgeProperties.mapper == CartridgeProperties.Mapper.DATEL
        MachineIdentity(
            hash(configuration.rom),
            if (datel) configuration.slotRom?.let(::hash) else null,
            HardwareProfile(
                HardwareProfile.VERSION,
                hardware,
                StateBootstrapMode.fromCore(configuration.bootstrapMode),
                resolvedProfile == HardwareProfileRegistry.CGB0,
                hardware != MachineHardwareState.CGB && configuration.isMealybugDmgBlob,
                configuration.isCodeBreakerRumble,
                hardware == MachineHardwareState.SGB && configuration.isDisplaySgbBorder,
                // The released v1 SGB payload used the legacy 4,194,304-unit RTC phase domain,
                // while coarse v1 DMG always means canonical dmg. Exact SGB/SGB2 and MGB captures
                // therefore require v2's explicit identity.
                resolvedProfile.id().takeIf {
                  StateProfilePolicy.requiresExplicitIdentity(resolvedProfile)
                },
            ),
        )
      }

  /** Hashes the exact bytes visible to the running cartridge after loader normalization. */
  fun hash(rom: Rom): RomIdentity {
    val digest = MessageDigest.getInstance("SHA-256")
    rom.rom.forEach { digest.update((it and 0xff).toByte()) }
    return RomIdentity(digest.digest())
  }
}

internal object PortableBounds {
  fun checkedAdd(left: Long, right: Long, maximum: Long, label: String): Long {
    if (left < 0 || right < 0) limit("$label is negative")
    val result =
        try {
          Math.addExact(left, right)
        } catch (_: ArithmeticException) {
          limit("$label overflows")
        }
    if (result > maximum) limit("$label $result exceeds $maximum")
    return result
  }

  fun checkedMultiply(left: Long, right: Long, maximum: Long, label: String): Long {
    if (left < 0 || right < 0) limit("$label is negative")
    val result =
        try {
          Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
          limit("$label overflows")
        }
    if (result > maximum) limit("$label $result exceeds $maximum")
    return result
  }

  fun checkedSubtract(left: Long, right: Long, label: String): Long {
    val result =
        try {
          Math.subtractExact(left, right)
        } catch (_: ArithmeticException) {
          malformed("$label overflows")
        }
    if (result < 0) malformed("$label underflows")
    return result
  }

  fun requireCount(value: Long, maximum: Long, label: String): Int {
    if (value < 0 || value > maximum || value > Int.MAX_VALUE) {
      limit("$label $value exceeds $maximum")
    }
    return value.toInt()
  }

  fun arrayBytes(elements: Long, width: Long): Int {
    if (elements > StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS) {
      limit(
          "Portable array length $elements exceeds ${StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS}")
    }
    return checkedMultiply(
            elements,
            width,
            StateLimits.PORTABLE_MAX_ARRAY_BYTES.toLong(),
            "Portable array bytes",
        )
        .toInt()
  }

  fun limit(message: String): Nothing =
      throw StateDecodeException(StateDecodeReason.LIMIT_EXCEEDED, message)

  fun malformed(message: String): Nothing =
      throw StateDecodeException(StateDecodeReason.MALFORMED_STRUCTURE, message)
}

internal class PortableWriter(
    private val maximum: Int,
    initialCapacity: Int = 256,
) {
  private var buffer =
      ByteArray(
          minOf(
              maximum,
              PortableBounds.requireCount(
                  initialCapacity.toLong(),
                  maximum.toLong(),
                  "Portable writer capacity",
              ),
          ))
  private var size = 0

  val count: Int get() = size

  fun writeByte(value: Int) {
    ensure(1)
    buffer[size++] = value.toByte()
  }

  fun writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

  fun writeU16(value: Int) {
    if (value !in 0..0xffff) throw StateEncodeException("Unsigned 16-bit value $value is invalid")
    ensure(2)
    buffer[size++] = (value ushr 8).toByte()
    buffer[size++] = value.toByte()
  }

  fun writeInt(value: Int) {
    ensure(4)
    buffer[size++] = (value ushr 24).toByte()
    buffer[size++] = (value ushr 16).toByte()
    buffer[size++] = (value ushr 8).toByte()
    buffer[size++] = value.toByte()
  }

  fun writeU32(value: Long) {
    if (value !in 0..0xffff_ffffL) {
      throw StateEncodeException("Unsigned 32-bit value $value is invalid")
    }
    writeInt(value.toInt())
  }

  fun writeLong(value: Long) {
    ensure(8)
    for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
  }

  fun writeBytes(value: ByteArray) {
    writeBytes(value, 0, value.size)
  }

  fun writeBytes(value: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > value.size - length) {
      throw StateEncodeException("Portable byte slice is invalid")
    }
    ensure(length)
    value.copyInto(buffer, size, offset, offset + length)
    size += length
  }

  fun writeString(value: String) {
    if (value.length > StateLimits.PORTABLE_MAX_STRING_CHARS) {
      throw StateEncodeException("Portable string is too long")
    }
    try {
      Math.multiplyExact(value.length.toLong(), 3L)
    } catch (_: ArithmeticException) {
      throw StateEncodeException("Portable string byte bound overflows")
    }.also {
      if (it > StateLimits.PORTABLE_MAX_STRING_BYTES) {
        throw StateEncodeException("Portable string encoding can exceed its byte limit")
      }
    }
    val encoded =
        try {
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value))
        } catch (failure: Exception) {
          throw StateEncodeException("Portable string is not valid Unicode", failure)
        }
    val bytes = ByteArray(encoded.remaining())
    encoded.get(bytes)
    if (bytes.size > StateLimits.PORTABLE_MAX_STRING_BYTES) {
      throw StateEncodeException("Portable string encoding is too long")
    }
    writeU32(bytes.size.toLong())
    writeBytes(bytes)
  }

  fun toByteArray(): ByteArray {
    if (size > maximum) throw StateEncodeException("Portable writer exceeded its bound")
    return buffer.copyOf(size)
  }

  private fun ensure(additional: Int) {
    if (additional < 0) throw StateEncodeException("Portable writer request is negative")
    val required =
        try {
          Math.addExact(size, additional)
        } catch (_: ArithmeticException) {
          throw StateEncodeException("Portable writer size overflows")
        }
    if (required > maximum) {
      throw StateEncodeException("Portable writer size $required exceeds $maximum")
    }
    if (required <= buffer.size) return
    var capacity = maxOf(1, buffer.size)
    while (capacity < required) {
      capacity =
          try {
            minOf(maximum, Math.multiplyExact(capacity, 2))
          } catch (_: ArithmeticException) {
            maximum
          }
      if (capacity < required && capacity == maximum) {
        throw StateEncodeException("Portable writer cannot grow to $required")
      }
    }
    buffer = buffer.copyOf(capacity)
  }
}

internal class PortableReader private constructor(
    private val bytes: ByteArray,
    private var position: Int,
    private val end: Int,
) {
  constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

  val remaining: Int get() = end - position
  val consumed: Int get() = position

  fun readByte(): Int {
    requireRemaining(1)
    return bytes[position++].toInt() and 0xff
  }

  fun readBoolean(): Boolean =
      when (val value = readByte()) {
        0 -> false
        1 -> true
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_TAG,
                "Invalid boolean byte $value",
            )
      }

  fun readU16(): Int = (readByte() shl 8) or readByte()

  fun readInt(): Int =
      (readByte() shl 24) or
          (readByte() shl 16) or
          (readByte() shl 8) or
          readByte()

  fun readU32(): Long = readInt().toLong() and 0xffff_ffffL

  fun readLong(): Long {
    var value = 0L
    repeat(8) { value = (value shl 8) or readByte().toLong() }
    return value
  }

  fun readBytes(length: Int, maximum: Int = StateLimits.PORTABLE_MAX_SECTION_BYTES): ByteArray {
    if (length < 0 || length > maximum) PortableBounds.limit("Portable byte length $length exceeds $maximum")
    requireRemaining(length)
    val result = ByteArray(length)
    bytes.copyInto(result, 0, position, position + length)
    position += length
    return result
  }

  fun readString(): String {
    val length =
        PortableBounds.requireCount(
            readU32(),
            StateLimits.PORTABLE_MAX_STRING_BYTES.toLong(),
            "Portable UTF-8 byte length",
        )
    requireRemaining(length)
    val decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes, position, length)
    // Decode into an explicitly bounded buffer. The UTF-8 byte limit alone cannot prove the
    // resulting UTF-16 character count.
    val decoded = CharBuffer.allocate(minOf(length, StateLimits.PORTABLE_MAX_STRING_CHARS))
    val result =
        try {
          val decodeResult = decoder.decode(input, decoded, true)
          if (decodeResult.isOverflow) {
            PortableBounds.limit("Portable string character count exceeds its limit")
          }
          if (decodeResult.isError) decodeResult.throwException()
          val flushResult = decoder.flush(decoded)
          if (flushResult.isOverflow) {
            PortableBounds.limit("Portable string character count exceeds its limit")
          }
          if (flushResult.isError) flushResult.throwException()
          decoded.flip()
          decoded.toString()
        } catch (failure: StateDecodeException) {
          throw failure
        } catch (failure: Exception) {
          throw StateDecodeException(
              StateDecodeReason.MALFORMED_UTF8,
              "Portable string is not strict UTF-8",
              failure,
          )
        }
    position += length
    if (result.length > StateLimits.PORTABLE_MAX_STRING_CHARS) {
      PortableBounds.limit("Portable string character count exceeds its limit")
    }
    return result
  }

  fun subReader(length: Int): PortableReader {
    if (length < 0) PortableBounds.malformed("Portable subsection length is negative")
    requireRemaining(length)
    val child = PortableReader(bytes, position, position + length)
    position += length
    return child
  }

  fun requireExhausted(reason: StateDecodeReason = StateDecodeReason.TRAILING_DATA) {
    if (remaining != 0) {
      throw StateDecodeException(reason, "Portable structure has $remaining trailing bytes")
    }
  }

  private fun requireRemaining(length: Int) {
    if (length < 0 || length > remaining) {
      throw StateDecodeException(
          StateDecodeReason.TRUNCATED,
          "Portable input needs $length bytes but only $remaining remain",
      )
    }
  }
}
