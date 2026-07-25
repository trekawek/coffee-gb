package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Version-1 portable state codec.
 *
 * Parsing creates detached values only. Live machines are reached exclusively by the explicit
 * decode-and-apply methods after envelope, identity, structure, and target compatibility checks.
 */
object StateCodec {
  const val FORMAT_VERSION = 1
  const val HEADER_SIZE = 68
  const val SECTION_HEADER_SIZE = 16

  private const val CHECKSUM_SHA256 = 1
  private const val SECTION_REQUIRED = 1
  private const val KNOWN_SECTION_FLAGS = SECTION_REQUIRED
  private const val KNOWN_ENVELOPE_FLAGS = 1

  private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte())

  fun encode(file: StateFile, compression: StateCompression = StateCompression.NONE): ByteArray {
    validateFileForEncoding(file)
    val sections = ArrayList<EncodedSection>(3)
    sections +=
        EncodedSection(
            StateIdentitySectionCodec.ID,
            StateIdentitySectionCodec.VERSION,
            required = true,
            StateIdentitySectionCodec.encode(file.identities),
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
    writer.writeU16(FORMAT_VERSION)
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
    return StateFile(identities, root, sections.diagnostics)
  }

  /** Reads bounded envelope/directory/identity metadata without constructing a live emulator. */
  fun inspect(bytes: ByteArray): StateFileInspection {
    val envelope = decodeEnvelope(bytes)
    val sections = parseSections(envelope, decodeState = false)
    val identities =
        sections.identities
            ?: throw StateDecodeException(
                StateDecodeReason.MISSING_REQUIRED_SECTION,
                "StateFile has no identity section",
            )
    return StateFileInspection(
        FORMAT_VERSION,
        envelope.kind,
        envelope.compression,
        envelope.encodedLength.toLong(),
        envelope.decoded.size.toLong(),
        checksumValid = true,
        java.util.Collections.unmodifiableList(ArrayList(identities)),
        java.util.Collections.unmodifiableList(ArrayList(sections.inspections)),
    )
  }

  fun capture(
      configuration: Gameboy.GameboyConfiguration,
      gameboy: Gameboy,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      StateFile(
          listOf(StateIdentityEntry(0, StateIdentity.from(configuration))),
          MachineStateRoot(DetachedStateAdapter.capture(gameboy)),
          diagnostics,
      )

  fun capture(
      session: Session,
      diagnostics: StateDiagnosticMetadata? = null,
  ): StateFile =
      StateFile(
          listOf(StateIdentityEntry(0, StateIdentity.from(session.config))),
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
    val file = decode(bytes)
    val root =
        file.root as? MachineStateRoot
            ?: targetMismatch("StateFile root ${file.root.kind} is not a machine")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, StateIdentity.from(configuration))),
    )
    try {
      DetachedStateAdapter.apply(gameboy, root.machine, probe)
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
    val file = decode(bytes)
    val root =
        file.root as? SessionStateRoot
            ?: targetMismatch("StateFile root ${file.root.kind} is not a session")
    validateTargetIdentities(
        file.identities,
        listOf(StateIdentityEntry(0, StateIdentity.from(session.config))),
    )
    try {
      DetachedStateAdapter.apply(session, root.session, probe)
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
    try {
      controller.restoreDetachedState(root.linked, probe)
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

  private fun decodeEnvelope(bytes: ByteArray): DecodedEnvelope {
    if (bytes.size > StateLimits.PORTABLE_MAX_FILE_BYTES) {
      PortableBounds.limit(
          "Portable file ${bytes.size} exceeds ${StateLimits.PORTABLE_MAX_FILE_BYTES}")
    }
    val reader = PortableReader(bytes)
    repeat(MAGIC.size) { index ->
      if (reader.readByte() != (MAGIC[index].toInt() and 0xff)) {
        throw StateDecodeException(StateDecodeReason.INVALID_MAGIC, "StateFile magic is not CGBS")
      }
    }
    val version = reader.readU16()
    if (version != FORMAT_VERSION) {
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
            StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES,
            "encoded payload",
        )
    val decodedLength =
        requireLength(
            reader.readLong(),
            StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES,
            "decoded payload",
        )
    if (compression == StateCompression.NONE && encodedLength != decodedLength) {
      PortableBounds.malformed("Uncompressed encoded and decoded lengths differ")
    }
    val expectedChecksum = reader.readBytes(RomIdentity.SHA256_BYTES, RomIdentity.SHA256_BYTES)
    val encoded = reader.readBytes(encodedLength, StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES)
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
    return DecodedEnvelope(kind, compression, sectionCount, encodedLength, decoded)
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
          if (version != StateIdentitySectionCodec.VERSION) unsupportedSection(id, version)
          identities = StateIdentitySectionCodec.decode(section)
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
      if (actual.profile != expected.profile) {
        throw StateDecodeException(
            StateDecodeReason.HARDWARE_PROFILE_MISMATCH,
            "Player $player profile ${actual.profile} does not match target ${expected.profile}",
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
}
