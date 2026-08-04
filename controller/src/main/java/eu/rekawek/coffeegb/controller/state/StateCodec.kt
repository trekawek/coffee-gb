package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Versioned portable state codec. V2 adds an explicit canonical hardware-profile ID while
 * preserving every v1 envelope/payload byte for the released profiles.
 *
 * Parsing creates detached values only. Live machines are reached exclusively by the explicit
 * decode-and-apply methods after envelope, identity, structure, and target compatibility checks.
 */
object StateCodec {
  const val V1_FORMAT_VERSION = 1
  const val LATEST_FORMAT_VERSION = 2
  /** Protocol v8 remains frozen to the released v1 format. */
  const val PROTOCOL_V8_FORMAT_VERSION = V1_FORMAT_VERSION
  /** @deprecated This historical name meant the only format at the time; use a scoped constant. */
  @Deprecated("Use V1_FORMAT_VERSION, LATEST_FORMAT_VERSION, or PROTOCOL_V8_FORMAT_VERSION")
  const val FORMAT_VERSION = V1_FORMAT_VERSION
  const val HEADER_SIZE = 68
  const val SECTION_HEADER_SIZE = 16

  private const val CHECKSUM_SHA256 = 1
  private const val SECTION_REQUIRED = 1
  private const val KNOWN_SECTION_FLAGS = SECTION_REQUIRED
  private const val KNOWN_ENVELOPE_FLAGS = 1

  private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())

  fun encode(file: StateFile, compression: StateCompression = StateCompression.NONE): ByteArray {
    validateFileForEncoding(file)
    val formatVersion = file.formatVersion
    val sections = ArrayList<EncodedSection>(3)
    sections +=
        EncodedSection(
            StateIdentitySectionCodec.ID,
            formatVersion,
            required = true,
            StateIdentitySectionCodec.encode(file.identities, formatVersion),
        )
    sections +=
        EncodedSection(
            StatePayloadSectionCodec.ID,
            StatePayloadSectionCodec.VERSION,
            required = true,
            StatePayloadSectionCodec.encode(file.root),
        )
    file.diagnostics?.let {
      sections +=
          EncodedSection(
              StateDiagnosticSectionCodec.ID,
              StateDiagnosticSectionCodec.VERSION,
              required = false,
              StateDiagnosticSectionCodec.encode(it),
          )
    }
    val decoded = encodeSections(sections)
    val encoded =
        when (compression) {
          StateCompression.NONE -> decoded
          StateCompression.DEFLATE -> deflate(decoded)
        }
    if (encoded.size > StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES) {
      throw StateEncodeException(
          "Encoded portable payload ${encoded.size} exceeds " +
              StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES)
    }
    val checksum = sha256(encoded)
    val writer = PortableWriter(StateLimits.PORTABLE_MAX_FILE_BYTES, HEADER_SIZE + encoded.size)
    writer.writeBytes(MAGIC)
    writer.writeU16(formatVersion)
    writer.writeU16(HEADER_SIZE)
    writer.writeU32(compression.flag.toLong())
    writer.writeByte(file.root.kind.id)
    writer.writeByte(CHECKSUM_SHA256)
    writer.writeU16(0)
    writer.writeU32(sections.size.toLong())
    writer.writeLong(encoded.size.toLong())
    writer.writeLong(decoded.size.toLong())
    writer.writeBytes(checksum)
    writer.writeBytes(encoded)
    return writer.toByteArray()
  }

  fun decode(bytes: ByteArray): StateFile {
    val envelope = decodeEnvelope(bytes)
    return decode(envelope)
  }

  /** Protocol-v9 direct-StateFile boundary; applies network limits before inflate/allocation. */
  internal fun decodeNetworkState(bytes: ByteArray): StateFile {
    val envelope =
        decodeEnvelope(
            bytes,
            StateLimits.NETPLAY_STATE_FILE_BYTES,
            StateLimits.NETPLAY_STATE_FILE_BYTES,
            StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES,
        )
    return decode(envelope)
  }

  private fun decode(envelope: DecodedEnvelope): StateFile {
    val sections = parseSections(envelope, decodeState = true)
    val identities =
        sections.identities
            ?: throw StateDecodeException(
                StateDecodeReason.MISSING_REQUIRED_SECTION,
                "StateFile has no identity section",
            )
    val root =
        sections.root
            ?: throw StateDecodeException(
                StateDecodeReason.MISSING_REQUIRED_SECTION,
                "StateFile has no state section",
            )
    validateDecodedFile(identities, root)
    return StateFile(identities, root, sections.diagnostics, envelope.version)
  }

  /** Reads bounded envelope/directory/identity metadata without constructing a live emulator. */
  fun inspect(bytes: ByteArray): StateFileInspection {
    val envelope = decodeEnvelope(bytes)
    return inspect(envelope)
  }

  /** Protocol-v9 inspection with the same pre-inflate network trust limits as decode. */
  internal fun inspectNetworkState(bytes: ByteArray): StateFileInspection {
    val envelope =
        decodeEnvelope(
            bytes,
            StateLimits.NETPLAY_STATE_FILE_BYTES,
            StateLimits.NETPLAY_STATE_FILE_BYTES,
            StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES,
        )
    return inspect(envelope)
  }

  private fun inspect(envelope: DecodedEnvelope): StateFileInspection {
    val sections = parseSections(envelope, decodeState = false)
    val identities =
        sections.identities
            ?: throw StateDecodeException(
                StateDecodeReason.MISSING_REQUIRED_SECTION,
                "StateFile has no identity section",
            )
    return StateFileInspection(
        envelope.version,
        envelope.kind,
        envelope.compression,
        envelope.encodedLength.toLong(),
        envelope.decoded.size.toLong(),
        checksumValid = true,
        java.util.Collections.unmodifiableList(ArrayList(identities)),
        java.util.Collections.unmodifiableList(ArrayList(sections.inspections)),
        sections.diagnostics,
    )
  }

  fun capture(
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      capture(configuration, gameboy, StateIdentity.from(configuration), diagnostics)

  internal fun capture(
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      identity: MachineIdentity,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      StateFile(
          listOf(StateIdentityEntry(0, identity)),
          MachineStateRoot(DetachedStateAdapter.capture(gameboy)),
          diagnostics,
      )

  fun capture(
      session: Session,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile = capture(session, StateIdentity.from(session.config), diagnostics)

  internal fun capture(
      session: Session,
      identity: MachineIdentity,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      StateFile(
          listOf(StateIdentityEntry(0, identity)),
          SessionStateRoot(session.captureDetachedState()),
          diagnostics,
      )

  fun capture(
      controller: LinkedController,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      StateFile(
          controller.capturePortableIdentities(),
          LinkedSessionStateRoot(controller.captureDetachedState()),
          diagnostics,
      )

  /**
   * Protocol-v9 capture contract. V9 always carries explicit canonical profile identity in
   * StateFile v2, including profiles whose local/default capture remains byte-compatible v1.
   */
  fun version2(file: StateFile): StateFile =
      if (file.formatVersion == LATEST_FORMAT_VERSION) file
      else StateFile(file.identities, file.root, file.diagnostics, LATEST_FORMAT_VERSION)

  fun captureVersion2(
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile = version2(capture(configuration, gameboy, diagnostics))

  fun captureVersion2(
      session: Session,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile = version2(capture(session, diagnostics))

  fun captureVersion2(
      controller: LinkedController,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile = version2(capture(controller, diagnostics))

  /**
   * Validates an already-decoded detached file against an expected network root and target
   * identities. This performs no live-state reconstruction and cannot mutate a machine.
   */
  internal fun validateForTarget(
      file: StateFile,
      expectedRoot: StateRootKind,
      targetIdentities: List<StateIdentityEntry>,
  ) {
    if (file.root.kind != expectedRoot) {
      targetMismatch("StateFile root ${file.root.kind} is not $expectedRoot")
    }
    validateTargetIdentities(file.identities, targetIdentities)
  }

  /**
   * Classifies an already-decoded detached file against an expected target without touching live
   * emulator state.
   */
  fun classifyCompatibility(
      file: StateFile,
      expectedRoot: StateRootKind,
      targetIdentities: List<StateIdentityEntry>,
  ): StateCompatibilityResult =
      try {
        validateForTarget(file, expectedRoot, targetIdentities)
        StateCompatibilityResult(StateCompatibilityStatus.COMPATIBLE, null, null)
      } catch (failure: StateDecodeException) {
        val status =
            when (failure.reason) {
              StateDecodeReason.TARGET_STATE_MISMATCH -> StateCompatibilityStatus.ROOT_MISMATCH
              StateDecodeReason.ROM_MISMATCH -> StateCompatibilityStatus.ROM_MISMATCH
              StateDecodeReason.SLOT_ROM_MISMATCH -> StateCompatibilityStatus.SLOT_ROM_MISMATCH
              StateDecodeReason.HARDWARE_PROFILE_MISMATCH ->
                  StateCompatibilityStatus.HARDWARE_PROFILE_MISMATCH
              else -> StateCompatibilityStatus.INCOMPATIBLE
            }
        StateCompatibilityResult(status, failure.reason, failure.message)
      }

  /** Convenience classifier for a standalone machine target. */
  fun classifyCompatibility(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
  ): StateCompatibilityResult =
      classifyCompatibility(
          file,
          StateRootKind.MACHINE,
          listOf(StateIdentityEntry(0, StateIdentity.from(configuration))),
      )

  fun decodeAndApply(
      bytes: ByteArray,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
  ) {
    decodeAndApply(bytes, configuration, gameboy, null)
  }

  internal fun decodeAndApply(
      bytes: ByteArray,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      probe: ((ApplyStage) -> Unit)?,
  ) {
    applyDecoded(decode(bytes), configuration, gameboy, probe)
  }

  /** Applies a fully decoded detached machine StateFile after target compatibility validation. */
  fun applyDecoded(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
  ) {
    applyDecoded(file, configuration, gameboy, StateIdentity.from(configuration), null)
  }

  internal fun applyDecoded(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      identity: MachineIdentity,
  ) {
    applyDecoded(file, configuration, gameboy, identity, null)
  }

  /** Runs the complete target-dependent machine preflight without mutating the live machine. */
  internal fun validateDecodedForApply(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      identity: MachineIdentity,
  ) {
    file.root as? MachineStateRoot
        ?: targetMismatch("StateFile root ${file.root.kind} is not a machine")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, identity)),
    )
    val compatibleRoot = preparePortableRootForApply(file) as MachineStateRoot
    try {
      DetachedStateAdapter.prepare(gameboy, compatibleRoot.machine)
    } catch (failure: StateApplyException) {
      throw StateDecodeException(
          StateDecodeReason.TARGET_STATE_MISMATCH,
          "Portable machine state is incompatible with the target",
          failure,
      )
    }
  }

  internal fun applyDecoded(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      probe: ((ApplyStage) -> Unit)?,
  ) {
    applyDecoded(file, configuration, gameboy, StateIdentity.from(configuration), probe)
  }

  private fun applyDecoded(
      file: StateFile,
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      identity: MachineIdentity,
      probe: ((ApplyStage) -> Unit)?,
  ) {
    val root =
        file.root as? MachineStateRoot
            ?: targetMismatch("StateFile root ${file.root.kind} is not a machine")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, identity)),
    )
    val compatibleRoot = preparePortableRootForApply(file) as MachineStateRoot
    try {
      DetachedStateAdapter.apply(gameboy, compatibleRoot.machine, probe)
    } catch (failure: StateApplyException) {
      throw StateDecodeException(
          StateDecodeReason.TARGET_STATE_MISMATCH,
          "Portable machine state is incompatible with the target",
          failure,
      )
    }
  }

  fun decodeAndApply(bytes: ByteArray, session: Session) {
    decodeAndApply(bytes, session, null)
  }

  internal fun decodeAndApply(
      bytes: ByteArray,
      session: Session,
      probe: ((ApplyStage) -> Unit)?,
  ) {
    applyDecoded(decode(bytes), session, StateIdentity.from(session.config), probe)
  }

  internal fun applyDecoded(
      file: StateFile,
      session: Session,
      identity: MachineIdentity,
  ) {
    applyDecoded(file, session, identity, null)
  }

  /** Runs the complete target-dependent session preflight without mutating the live session. */
  internal fun validateDecodedForApply(
      file: StateFile,
      session: Session,
      identity: MachineIdentity,
  ) {
    file.root as? SessionStateRoot
        ?: targetMismatch("StateFile root ${file.root.kind} is not a session")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, identity)),
    )
    val compatibleRoot = preparePortableRootForApply(file) as SessionStateRoot
    try {
      DetachedStateAdapter.prepare(session, compatibleRoot.session)
    } catch (failure: StateApplyException) {
      throw StateDecodeException(
          StateDecodeReason.TARGET_STATE_MISMATCH,
          "Portable session state is incompatible with the target",
          failure,
      )
    }
  }

  private fun applyDecoded(
      file: StateFile,
      session: Session,
      identity: MachineIdentity,
      probe: ((ApplyStage) -> Unit)?,
  ) {
    val root =
        file.root as? SessionStateRoot
            ?: targetMismatch("StateFile root ${file.root.kind} is not a session")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, identity)),
    )
    val compatibleRoot = preparePortableRootForApply(file) as SessionStateRoot
    try {
      DetachedStateAdapter.apply(session, compatibleRoot.session, probe)
    } catch (failure: StateApplyException) {
      throw StateDecodeException(
          StateDecodeReason.TARGET_STATE_MISMATCH,
          "Portable session state is incompatible with the target",
          failure,
      )
    }
  }

  fun decodeAndApply(bytes: ByteArray, controller: LinkedController) {
    decodeAndApply(bytes, controller, null)
  }

  internal fun decodeAndApply(
      bytes: ByteArray,
      controller: LinkedController,
      probe: ((Int, ApplyStage) -> Unit)?,
  ) {
    val file = decode(bytes)
    val root =
        file.root as? LinkedSessionStateRoot
            ?: targetMismatch("StateFile root ${file.root.kind} is not a linked session")
    validateTargetIdentities(file.identities, controller.capturePortableIdentities())
    val compatibleRoot = preparePortableRootForApply(file) as LinkedSessionStateRoot
    try {
      controller.restoreDetachedState(compatibleRoot.linked, probe)
    } catch (failure: StateApplyException) {
      throw StateDecodeException(
          StateDecodeReason.TARGET_STATE_MISMATCH,
          "Portable linked state is incompatible with the target",
          failure,
      )
    }
  }

  private fun encodeSections(sections: List<EncodedSection>): ByteArray {
    if (sections.size > StateLimits.PORTABLE_MAX_SECTIONS) {
      throw StateEncodeException("Portable state has too many sections")
    }
    val writer = PortableWriter(StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES)
    var previous = 0
    sections.forEach { section ->
      if (section.id <= previous) throw StateEncodeException("Sections are not in canonical order")
      previous = section.id
      if (section.bytes.size > StateLimits.PORTABLE_MAX_SECTION_BYTES) {
        throw StateEncodeException("Portable section ${section.id} exceeds its size limit")
      }
      writer.writeU16(section.id)
      writer.writeU16(section.version)
      writer.writeU16(if (section.required) SECTION_REQUIRED else 0)
      writer.writeU16(0)
      writer.writeLong(section.bytes.size.toLong())
      writer.writeBytes(section.bytes)
    }
    return writer.toByteArray()
  }

  private fun decodeEnvelope(
      bytes: ByteArray,
      maximumFileBytes: Int = StateLimits.PORTABLE_MAX_FILE_BYTES,
      maximumEncodedBytes: Int = StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES,
      maximumDecodedBytes: Int = StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES,
  ): DecodedEnvelope {
    if (bytes.size > maximumFileBytes) {
      PortableBounds.limit(
          "Portable file ${bytes.size} exceeds $maximumFileBytes")
    }
    val reader = PortableReader(bytes)
    repeat(MAGIC.size) { index ->
      if (reader.readByte() != (MAGIC[index].toInt() and 0xff)) {
        throw StateDecodeException(StateDecodeReason.INVALID_MAGIC, "StateFile magic is not CGBS")
      }
    }
    val version = reader.readU16()
    if (version !in V1_FORMAT_VERSION..LATEST_FORMAT_VERSION) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_FORMAT_VERSION,
          "Unsupported StateFile version $version",
      )
    }
    val headerSize = reader.readU16()
    if (headerSize != HEADER_SIZE) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_FORMAT_VERSION,
          "Unsupported StateFile header size $headerSize",
      )
    }
    val flags = reader.readU32()
    if (flags and KNOWN_ENVELOPE_FLAGS.toLong().inv() != 0L) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_FLAGS,
          "StateFile has undefined flags 0x${flags.toString(16)}",
      )
    }
    val compression =
        if (flags and StateCompression.DEFLATE.flag.toLong() != 0L) {
          StateCompression.DEFLATE
        } else {
          StateCompression.NONE
        }
    val kind = StateRootKind.fromId(reader.readByte())
    val checksumAlgorithm = reader.readByte()
    if (checksumAlgorithm != CHECKSUM_SHA256) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_FORMAT_VERSION,
          "Unsupported StateFile checksum algorithm $checksumAlgorithm",
      )
    }
    if (reader.readU16() != 0) {
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_FLAGS,
          "StateFile reserved header bits are nonzero",
      )
    }
    val sectionCount =
        PortableBounds.requireCount(
            reader.readU32(),
            StateLimits.PORTABLE_MAX_SECTIONS.toLong(),
            "Portable section count",
        )
    val encodedLength =
        requireLength(
            reader.readLong(),
            maximumEncodedBytes,
            "encoded payload",
        )
    val decodedLength =
        requireLength(
            reader.readLong(),
            maximumDecodedBytes,
            "decoded payload",
        )
    if (compression == StateCompression.NONE && encodedLength != decodedLength) {
      PortableBounds.malformed("Uncompressed encoded and decoded lengths differ")
    }
    val expectedChecksum = reader.readBytes(RomIdentity.SHA256_BYTES, RomIdentity.SHA256_BYTES)
    val encoded = reader.readBytes(encodedLength, maximumEncodedBytes)
    reader.requireExhausted()
    if (!MessageDigest.isEqual(expectedChecksum, sha256(encoded))) {
      throw StateDecodeException(
          StateDecodeReason.CORRUPT_CHECKSUM,
          "StateFile encoded-payload checksum does not match",
      )
    }
    val decoded =
        when (compression) {
          StateCompression.NONE -> encoded
          StateCompression.DEFLATE -> inflate(encoded, decodedLength)
        }
    if (decoded.size != decodedLength) {
      throw StateDecodeException(
          StateDecodeReason.COMPRESSION_ERROR,
          "Decoded payload size ${decoded.size} differs from declared $decodedLength",
      )
    }
    return DecodedEnvelope(version, kind, compression, sectionCount, encodedLength, decoded)
  }

  private fun parseSections(
      envelope: DecodedEnvelope,
      decodeState: Boolean,
  ): DecodedSections {
    val reader = PortableReader(envelope.decoded)
    var identities: List<StateIdentityEntry>? = null
    var root: StateFileRoot? = null
    var diagnostics: StateDiagnosticMetadata? = null
    var previousId = 0
    val seenIds = HashSet<Int>(envelope.sectionCount)
    val inspections = ArrayList<StateSectionInspection>(envelope.sectionCount)
    repeat(envelope.sectionCount) {
      val id = reader.readU16()
      if (!seenIds.add(id)) {
        throw StateDecodeException(
            StateDecodeReason.DUPLICATE_SECTION,
            "Section $id occurs more than once",
        )
      }
      if (id == 0 || id <= previousId) {
        throw StateDecodeException(
            StateDecodeReason.MALFORMED_STRUCTURE,
            "Section IDs are not unique canonical positive values",
        )
      }
      previousId = id
      val version = reader.readU16()
      val flags = reader.readU16()
      if (flags and KNOWN_SECTION_FLAGS.inv() != 0) {
        throw StateDecodeException(
            StateDecodeReason.UNSUPPORTED_FLAGS,
            "Section $id has undefined flags 0x${flags.toString(16)}",
        )
      }
      if (reader.readU16() != 0) {
        throw StateDecodeException(
            StateDecodeReason.UNSUPPORTED_FLAGS,
            "Section $id has nonzero reserved bits",
        )
      }
      val length =
          requireLength(reader.readLong(), StateLimits.PORTABLE_MAX_SECTION_BYTES, "section $id")
      val required = flags and SECTION_REQUIRED != 0
      inspections += StateSectionInspection(id, version, required, length.toLong())
      val section = reader.subReader(length)
      when (id) {
        StateIdentitySectionCodec.ID -> {
          if (!required) malformedRequiredFlag(id, true)
          if (version != envelope.version) unsupportedSection(id, version)
          identities = StateIdentitySectionCodec.decode(section, version)
        }
        StatePayloadSectionCodec.ID -> {
          if (!required) malformedRequiredFlag(id, true)
          if (version != StatePayloadSectionCodec.VERSION) unsupportedSection(id, version)
          if (decodeState) {
            root = StatePayloadSectionCodec.decode(envelope.kind, section)
          } else {
            section.subReader(section.remaining)
          }
        }
        StateDiagnosticSectionCodec.ID -> {
          if (required) malformedRequiredFlag(id, false)
          if (version != StateDiagnosticSectionCodec.VERSION) unsupportedSection(id, version)
          diagnostics = StateDiagnosticSectionCodec.decode(section)
        }
        else -> {
          if (required) {
            throw StateDecodeException(
                StateDecodeReason.UNKNOWN_REQUIRED_SECTION,
                "Unknown required section $id",
            )
          }
          section.subReader(section.remaining)
        }
      }
      section.requireExhausted()
    }
    reader.requireExhausted()
    if (identities == null) {
      throw StateDecodeException(
          StateDecodeReason.MISSING_REQUIRED_SECTION,
          "StateFile has no identity section",
      )
    }
    if (inspections.none { it.id == StatePayloadSectionCodec.ID }) {
      throw StateDecodeException(
          StateDecodeReason.MISSING_REQUIRED_SECTION,
          "StateFile has no state section",
      )
    }
    return DecodedSections(identities, root, diagnostics, inspections)
  }

  private fun validateFileForEncoding(file: StateFile) {
    try {
      if (file.formatVersion == V1_FORMAT_VERSION &&
          file.identities.any {
            it.identity?.profile?.explicitProfileId != null
          }) {
        throw StateDecodeException(
            StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
            "StateFile v1 cannot encode an explicit hardware profile",
        )
      }
      validateFileShape(file.identities, file.root)
    } catch (failure: StateDecodeException) {
      throw StateEncodeException(failure.message ?: "Portable state is invalid", failure)
    }
  }

  private fun validateDecodedFile(
      identities: List<StateIdentityEntry>,
      root: StateFileRoot,
  ) {
    validateFileShape(identities, root)
  }

  private fun validateFileShape(
      identities: List<StateIdentityEntry>,
      root: StateFileRoot,
  ) {
    when (root) {
      is MachineStateRoot -> {
        requireSingleIdentity(identities)
        requireIdentityHardware(identities[0], root.machine)
      }
      is SessionStateRoot -> {
        requireSingleIdentity(identities)
        requireIdentityHardware(identities[0], root.session.machine)
      }
      is LinkedSessionStateRoot -> {
        if (identities.size != StateLimits.PORTABLE_MAX_LINKED_PLAYERS ||
            identities.indices.any { identities[it].player != it }) {
          PortableBounds.malformed("Linked StateFile identities are not canonical")
        }
        if (root.linked.players.size != StateLimits.PORTABLE_MAX_LINKED_PLAYERS ||
            root.linked.players.indices.any { root.linked.players[it].player != it }) {
          PortableBounds.malformed("Linked StateFile players are not canonical")
        }
        identities.indices.forEach { player ->
          val session = root.linked.players[player].session
          val identity = identities[player].identity
          if ((session == null) != (identity == null)) {
            PortableBounds.malformed("Linked player $player identity presence does not match state")
          }
          if (session != null) requireIdentityHardware(identities[player], session.machine)
        }
      }
    }
  }

  private fun requireSingleIdentity(identities: List<StateIdentityEntry>) {
    if (identities.size != 1 || identities[0].player != 0 || identities[0].identity == null) {
      PortableBounds.malformed("Machine/session StateFile requires player-zero identity")
    }
  }

  private fun requireIdentityHardware(entry: StateIdentityEntry, machine: MachineState) {
    val identity = entry.identity ?: PortableBounds.malformed("Active machine identity is absent")
    if (identity.profile.hardware != machine.hardware) {
      throw StateDecodeException(
          StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
          "Identity hardware ${identity.profile.hardware} differs from state ${machine.hardware}",
      )
    }
    if ((identity.slotRom != null) != datelSlotPresent(machine.root)) {
      throw StateDecodeException(
          StateDecodeReason.SLOT_ROM_MISMATCH,
          "Slot-ROM identity presence does not match the detached Datel mapper tree",
      )
    }
    if (machine.rtcRuntime.slot != null && identity.slotRom == null) {
      PortableBounds.malformed("Slot RTC runtime exists without a slot-ROM identity")
    }
  }

  private fun datelSlotPresent(root: StateValue): Boolean {
    val gameboy = root as? RecordState ?: PortableBounds.malformed("Machine root is not a record")
    val cartridge =
        gameboy.recordField("cartridgeMemento") as? RecordState
            ?: PortableBounds.malformed("Gameboy cartridge state is not a record")
    val cartridgeType =
        registeredRecordId(
            "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeState")
    if (cartridge.typeId != cartridgeType) {
      PortableBounds.malformed("Gameboy cartridge state has type ${cartridge.typeId}")
    }
    val mapper =
        cartridge.recordField("memoryControllerMemento") as? RecordState
            ?: PortableBounds.malformed("Cartridge mapper state is not a record")
    val datelType =
        registeredRecordId(
            "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelState")
    if (mapper.typeId != datelType) return false
    return mapper.recordField("slotMemento") !== NullState
  }

  private fun RecordState.recordField(name: String): StateValue =
      fields.singleOrNull { it.name == name }?.value
          ?: PortableBounds.malformed("Record type $typeId has no $name field")

  private fun registeredRecordId(className: String): Int =
      StateTypeRegistry.recordClassNames.indexOf(className).plus(1).also {
        if (it == 0) error("Portable record registry has no $className")
      }

  /**
   * Converts the one historical format/profile combination whose payload scalar changed domain.
   *
   * StateFile v1 canonical SGB files predate exact rational clocks. Their MBC3 phase is a fraction
   * with denominator 4,194,304. V2 SGB/SGB2 phases are numerator-domain values for the explicit
   * profile. Decode and inspect retain the historical v1 tree byte-for-byte; only this detached,
   * target-aware preparation creates a converted tree before the first live mutation.
   */
  private fun preparePortableRootForApply(file: StateFile): StateFileRoot {
    if (file.formatVersion != V1_FORMAT_VERSION) return file.root
    val legacySgbPlayers =
        file.identities
            .filter {
              it.identity?.profile?.canonicalProfileId == HardwareProfileRegistry.SGB.id()
            }
            .mapTo(mutableSetOf()) { it.player }
    if (legacySgbPlayers.isEmpty()) return file.root

    return when (val root = file.root) {
      is MachineStateRoot ->
          MachineStateRoot(
              if (0 in legacySgbPlayers) convertLegacyV1SgbRtcPhase(root.machine)
              else root.machine)
      is SessionStateRoot ->
          SessionStateRoot(
              if (0 in legacySgbPlayers) convertLegacyV1SgbRtcPhase(root.session)
              else root.session)
      is LinkedSessionStateRoot ->
          LinkedSessionStateRoot(
              LinkedSessionState(
                  root.linked.frame,
                  root.linked.localPlayer,
                  root.linked.topology,
                  root.linked.players.map { player ->
                    LinkedPlayerState(
                        player.player,
                        player.session?.let { session ->
                          if (player.player in legacySgbPlayers) {
                            convertLegacyV1SgbRtcPhase(session)
                          } else {
                            session
                          }
                        },
                    )
                  },
              ))
    }
  }

  private fun convertLegacyV1SgbRtcPhase(session: SessionState): SessionState =
      SessionState(
          convertLegacyV1SgbRtcPhase(session.machine),
          session.serialPeripheral,
          session.serialState,
          session.serialRuntime,
          session.heldButtons,
      )

  private fun convertLegacyV1SgbRtcPhase(machine: MachineState): MachineState =
      MachineState(
          convertLegacyV1SgbRtcPhase(machine.root) as RecordState,
          machine.rtcRuntime,
          machine.hardware,
          machine.dmgFifoRuntime,
      )

  private fun convertLegacyV1SgbRtcPhase(value: StateValue): StateValue =
      when (value) {
        is RecordState -> {
          if (value.typeId == rtcStateTypeId) {
            var foundPhase = false
            val fields =
                value.fields.map { field ->
                  if (field.name == RTC_PHASE_FIELD) {
                    if (foundPhase) malformedLegacyRtc("contains duplicate RTC phase fields")
                    foundPhase = true
                    val phase =
                        (field.value as? Int64State)?.value
                            ?: malformedLegacyRtc("has a non-integer RTC phase")
                    StateField(field.name, Int64State(convertLegacyV1SgbRtcPhase(phase)))
                  } else {
                    StateField(field.name, convertLegacyV1SgbRtcPhase(field.value))
                  }
                }
            if (!foundPhase) malformedLegacyRtc("has no RTC phase field")
            RecordState(value.typeId, fields)
          } else {
            RecordState(
                value.typeId,
                value.fields.map { field ->
                  StateField(field.name, convertLegacyV1SgbRtcPhase(field.value))
                },
            )
          }
        }
        is ObjectArrayState -> ObjectArrayState(value.values.map(::convertLegacyV1SgbRtcPhase))
        is ListState -> ListState(value.values.map(::convertLegacyV1SgbRtcPhase))
        is Int32MapState ->
            Int32MapState(
                value.entries.map {
                  Int32MapEntry(it.key, convertLegacyV1SgbRtcPhase(it.value))
                })
        else -> value
      }

  /** Nearest, with exact half values rounded upward; absolute error is at most half a v2 unit. */
  private fun convertLegacyV1SgbRtcPhase(phase: Long): Long {
    if (phase !in 0 until LEGACY_V1_SGB_RTC_PHASE_LIMIT) {
      malformedLegacyRtc(
          "phase $phase is outside 0..${LEGACY_V1_SGB_RTC_PHASE_LIMIT - 1}")
    }
    val destinationLimit = HardwareProfileRegistry.SGB.clockSpec().secondPhaseLimit().toLong()
    return BigInteger.valueOf(phase)
        .multiply(BigInteger.valueOf(destinationLimit))
        .add(BigInteger.valueOf(LEGACY_V1_SGB_RTC_PHASE_LIMIT / 2))
        .divide(BigInteger.valueOf(LEGACY_V1_SGB_RTC_PHASE_LIMIT))
        .longValueExactForAndroid()
  }

  /** Android API 26 lacks BigInteger.longValueExact(), used by the JVM implementation. */
  private fun BigInteger.longValueExactForAndroid(): Long {
    if (compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 ||
        compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
      throw ArithmeticException("BigInteger does not fit in a Long")
    }
    return toLong()
  }

  private fun malformedLegacyRtc(message: String): Nothing =
      throw StateDecodeException(
          StateDecodeReason.MALFORMED_STRUCTURE,
          "StateFile v1 SGB $message",
      )

  private val rtcStateTypeId by lazy {
    registeredRecordId(RTC_STATE_CLASS)
  }

  private fun validateTargetIdentities(
      file: List<StateIdentityEntry>,
      target: List<StateIdentityEntry>,
  ) {
    if (file.size != target.size ||
        file.indices.any { file[it].player != target[it].player } ||
        file.indices.any { (file[it].identity == null) != (target[it].identity == null) }) {
      throw StateDecodeException(
          StateDecodeReason.ROM_MISMATCH,
          "StateFile active-player identity shape does not match the target",
      )
    }
    file.indices.forEach { player ->
      val actual = file[player].identity ?: return@forEach
      val expected = checkNotNull(target[player].identity)
      if (actual.primaryRom != expected.primaryRom) {
        throw StateDecodeException(
            StateDecodeReason.ROM_MISMATCH,
            "Player $player ROM ${actual.primaryRom} does not match target ${expected.primaryRom}",
        )
      }
      if (actual.slotRom != expected.slotRom) {
        throw StateDecodeException(
            StateDecodeReason.SLOT_ROM_MISMATCH,
            "Player $player slot ROM ${actual.slotRom ?: "absent"} does not match target " +
                (expected.slotRom ?: "absent"),
        )
      }
      if (!actual.profile.isCompatibleWith(expected.profile)) {
        throw StateDecodeException(
            StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
            "Player $player profile ${actual.profile.canonicalProfileId} ${actual.profile} " +
                "does not match target ${expected.profile.canonicalProfileId} ${expected.profile}",
        )
      }
    }
  }

  private fun deflate(decoded: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    return try {
      deflater.setInput(decoded)
      deflater.finish()
      val output = PortableWriter(StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES)
      val scratch = ByteArray(8192)
      while (!deflater.finished()) {
        val count = deflater.deflate(scratch, 0, scratch.size, Deflater.NO_FLUSH)
        if (count == 0 && !deflater.finished()) {
          throw StateEncodeException("Raw DEFLATE encoder made no progress")
        }
        output.writeBytes(scratch, 0, count)
      }
      output.toByteArray()
    } finally {
      deflater.end()
    }
  }

  private fun inflate(encoded: ByteArray, declaredLength: Int): ByteArray {
    val inflater = Inflater(true)
    return try {
      inflater.setInput(encoded)
      val output = PortableWriter(declaredLength)
      val scratch = ByteArray(8192)
      while (!inflater.finished()) {
        val count =
            try {
              inflater.inflate(scratch)
            } catch (failure: DataFormatException) {
              throw StateDecodeException(
                  StateDecodeReason.COMPRESSION_ERROR,
                  "Raw DEFLATE payload is corrupt",
                  failure,
              )
            }
        if (count > 0) {
          if (count > declaredLength - output.count) {
            PortableBounds.limit("DEFLATE output exceeds its declared decoded length")
          }
          try {
            output.writeBytes(scratch, 0, count)
          } catch (failure: StateEncodeException) {
            PortableBounds.limit(
                failure.message ?: "DEFLATE output exceeds its decoded limit")
          }
        } else if (inflater.needsDictionary()) {
          throw StateDecodeException(
              StateDecodeReason.COMPRESSION_ERROR,
              "Raw DEFLATE payload requests a preset dictionary",
          )
        } else if (inflater.needsInput()) {
          throw StateDecodeException(
              StateDecodeReason.TRUNCATED,
              "Raw DEFLATE payload ends before the stream finishes",
          )
        } else {
          throw StateDecodeException(
              StateDecodeReason.COMPRESSION_ERROR,
              "Raw DEFLATE decoder made no progress",
          )
        }
      }
      if (inflater.remaining != 0) {
        throw StateDecodeException(
            StateDecodeReason.TRAILING_DATA,
            "Raw DEFLATE payload has ${inflater.remaining} trailing bytes",
        )
      }
      if (output.count != declaredLength) {
        throw StateDecodeException(
            StateDecodeReason.COMPRESSION_ERROR,
            "Raw DEFLATE output ${output.count} differs from declared $declaredLength",
        )
      }
      output.toByteArray()
    } finally {
      inflater.end()
    }
  }

  private fun requireLength(value: Long, maximum: Int, label: String): Int {
    if (value < 0) PortableBounds.malformed("Portable $label length is negative")
    return PortableBounds.requireCount(value, maximum.toLong(), "Portable $label length")
  }

  private fun sha256(bytes: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(bytes)

  private fun unsupportedSection(id: Int, version: Int): Nothing =
      throw StateDecodeException(
          StateDecodeReason.UNSUPPORTED_SECTION_VERSION,
          "Unsupported version $version for section $id",
      )

  private fun malformedRequiredFlag(id: Int, expected: Boolean): Nothing =
      throw StateDecodeException(
          StateDecodeReason.MALFORMED_STRUCTURE,
          "Section $id required flag is ${!expected}, expected $expected",
      )

  private fun targetMismatch(message: String): Nothing =
      throw StateDecodeException(StateDecodeReason.TARGET_STATE_MISMATCH, message)

  private data class EncodedSection(
      val id: Int,
      val version: Int,
      val required: Boolean,
      val bytes: ByteArray,
  )

  private data class DecodedEnvelope(
      val version: Int,
      val kind: StateRootKind,
      val compression: StateCompression,
      val sectionCount: Int,
      val encodedLength: Int,
      val decoded: ByteArray,
  )

  private data class DecodedSections(
      val identities: List<StateIdentityEntry>?,
      val root: StateFileRoot?,
      val diagnostics: StateDiagnosticMetadata?,
      val inspections: List<StateSectionInspection>,
  )

  private const val LEGACY_V1_SGB_RTC_PHASE_LIMIT = 4_194_304L
  private const val RTC_PHASE_FIELD = "subSecondTicks"
  private const val RTC_STATE_CLASS =
      "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockState"
}
