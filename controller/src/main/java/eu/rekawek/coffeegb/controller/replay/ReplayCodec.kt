package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateRootKind
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Canonical, bounded CGBR v1 codec.
 *
 * The 72-byte big-endian envelope is:
 *
 * ```
 * magic[4], format:u16, header-size:u16, required-features:u64, optional-features:u64,
 * section-count:u32, envelope-flags:u32, payload-length:u64, payload-sha256[32]
 * ```
 *
 * Its checksum covers the complete encoded payload (section headers and section bodies). Every
 * section has a canonical 24-byte header:
 *
 * ```
 * id:u16, version:u16, flags:u32, encoded-length:u64, decoded-length:u64
 * ```
 *
 * Raw DEFLATE is permitted only for the input and checkpoint sections. Unknown optional sections
 * are skipped; unknown required sections are rejected.
 */
object ReplayCodec {
  const val FORMAT_VERSION = 1
  const val HEADER_SIZE = 72
  const val SECTION_HEADER_SIZE = 24

  const val IDENTITY_SECTION_ID = 1
  const val INITIAL_CONDITIONS_SECTION_ID = 2
  const val INPUT_SECTION_ID = 3
  const val CHECKPOINT_SECTION_ID = 4
  const val METADATA_SECTION_ID = 5
  const val EMBEDDED_STATE_SECTION_ID = 6

  private const val SCHEMA_VERSION = 1
  private const val SECTION_REQUIRED = 1L
  private const val SECTION_DEFLATE = 2L
  private const val KNOWN_SECTION_FLAGS = SECTION_REQUIRED or SECTION_DEFLATE
  private const val KNOWN_REQUIRED_FEATURE_FLAGS = 0L
  private const val KNOWN_ENVELOPE_FLAGS = 0L
  private const val INPUT_RECORD_SIZE = 12L
  private const val CHECKPOINT_RECORD_SIZE = 16L + 8L * ReplayLimits.SHA256_BYTES

  private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte())

  fun encode(file: ReplayFile): ByteArray {
    validateEmbeddedStateForEncode(file)

    val sections = ArrayList<EncodedSection>(6)
    sections += requiredSection(IDENTITY_SECTION_ID, encodeIdentity(file.identity))
    sections += requiredSection(INITIAL_CONDITIONS_SECTION_ID, encodeInitial(file.initialConditions))
    sections += compressedRequiredSection(INPUT_SECTION_ID, encodeInputs(file.inputs))
    sections += compressedRequiredSection(CHECKPOINT_SECTION_ID, encodeCheckpoints(file.checkpoints))
    file.metadata?.let {
      sections += optionalSection(METADATA_SECTION_ID, encodeMetadata(it))
    }
    file.embeddedState?.let {
      sections += requiredSection(EMBEDDED_STATE_SECTION_ID, it)
    }

    if (sections.size > ReplayLimits.MAX_SECTIONS) {
      throw ReplayEncodeException("Replay has too many sections")
    }
    val payload = encodeSections(sections)
    sections.fold(0L) { total, section ->
      checkedAddForEncode(
          total,
          section.decodedLength.toLong(),
          ReplayLimits.MAX_TOTAL_DECODED_BYTES.toLong(),
          "Replay decoded section bytes",
      )
    }
    val maximumPayload = ReplayLimits.MAX_FILE_BYTES - HEADER_SIZE
    if (payload.size > maximumPayload) {
      throw ReplayEncodeException(
          "Replay payload ${payload.size} exceeds $maximumPayload bytes")
    }

    val writer = ReplayWriter(ReplayLimits.MAX_FILE_BYTES, HEADER_SIZE + payload.size)
    writer.writeBytes(MAGIC)
    writer.writeU16(FORMAT_VERSION)
    writer.writeU16(HEADER_SIZE)
    writer.writeLong(KNOWN_REQUIRED_FEATURE_FLAGS)
    writer.writeLong(0) // CGBR v1 currently emits no optional feature bits.
    writer.writeU32(sections.size.toLong())
    writer.writeU32(KNOWN_ENVELOPE_FLAGS)
    writer.writeLong(payload.size.toLong())
    writer.writeBytes(sha256(payload))
    writer.writeBytes(payload)
    return writer.toByteArray()
  }

  fun decode(bytes: ByteArray): ReplayFile =
      requireNotNull(decodeContent(bytes, materializeRecords = true).file)

  /** Reads and validates replay metadata without opening a ROM or constructing a live emulator. */
  fun inspect(bytes: ByteArray): ReplayFileInspection {
    val decoded = decodeContent(bytes, materializeRecords = false)
    return ReplayFileInspection(
        FORMAT_VERSION,
        decoded.requiredFeatureFlags,
        decoded.optionalFeatureFlags,
        decoded.encodedPayloadLength.toLong(),
        decoded.decodedSectionBytes,
        checksumValid = true,
        decoded.identity,
        decoded.initialConditions,
        decoded.inputCount,
        decoded.checkpointCount,
        decoded.finalTick,
        decoded.finalFrame,
        decoded.metadata,
        decoded.hasEmbeddedState,
        Collections.unmodifiableList(ArrayList(decoded.inspections)),
    )
  }

  private fun decodeContent(
      bytes: ByteArray,
      materializeRecords: Boolean,
  ): DecodedContent {
    if (bytes.size > ReplayLimits.MAX_FILE_BYTES) {
      limit("Replay file ${bytes.size} exceeds ${ReplayLimits.MAX_FILE_BYTES} bytes")
    }
    val reader = ReplayReader(bytes)
    MAGIC.forEachIndexed { index, expected ->
      if (reader.readByte() != (expected.toInt() and 0xff)) {
        throw ReplayDecodeException(
            ReplayDecodeReason.INVALID_MAGIC,
            "Replay magic is not CGBR (byte $index differs)",
        )
      }
    }
    val version = reader.readU16()
    if (version != FORMAT_VERSION) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_FORMAT_VERSION,
          "Unsupported replay format version $version",
      )
    }
    val headerSize = reader.readU16()
    if (headerSize != HEADER_SIZE) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_FORMAT_VERSION,
          "Unsupported replay header size $headerSize",
      )
    }
    val requiredFeatureFlags = reader.readLong()
    if (requiredFeatureFlags and KNOWN_REQUIRED_FEATURE_FLAGS.inv() != 0L) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_FLAGS,
          "Replay requires unsupported feature flags 0x${requiredFeatureFlags.toString(16)}",
      )
    }
    // Unknown optional feature bits are intentionally inspectable and ignorable in v1.
    val optionalFeatureFlags = reader.readLong()
    val sectionCount =
        requireCount(reader.readU32(), ReplayLimits.MAX_SECTIONS, "Replay section count")
    val envelopeFlags = reader.readU32()
    if (envelopeFlags and KNOWN_ENVELOPE_FLAGS.inv() != 0L) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_FLAGS,
          "Replay has undefined envelope flags 0x${envelopeFlags.toString(16)}",
      )
    }
    val encodedPayloadLength =
        requireLength(
            reader.readLong(),
            ReplayLimits.MAX_FILE_BYTES - HEADER_SIZE,
            "Replay encoded payload",
        )
    val expectedChecksum = reader.readBytes(ReplayLimits.SHA256_BYTES, ReplayLimits.SHA256_BYTES)
    val payload = reader.readBytes(encodedPayloadLength, ReplayLimits.MAX_FILE_BYTES - HEADER_SIZE)
    reader.requireExhausted()
    if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
      throw ReplayDecodeException(
          ReplayDecodeReason.CORRUPT_CHECKSUM,
          "Replay payload checksum does not match",
      )
    }

    val parsed = parseSections(payload, sectionCount, materializeRecords)
    val identity =
        parsed.identity
            ?: missing(IDENTITY_SECTION_ID, "identity")
    val initial =
        parsed.initialConditions
            ?: missing(INITIAL_CONDITIONS_SECTION_ID, "initial conditions")
    val inputs = parsed.inputs ?: missing(INPUT_SECTION_ID, "inputs")
    val checkpoints = parsed.checkpoints ?: missing(CHECKPOINT_SECTION_ID, "checkpoints")
    val shouldEmbed = initial.mode == ReplayInitialMode.EMBEDDED_SESSION_STATE
    if (shouldEmbed != (parsed.embeddedState != null)) {
      throw ReplayDecodeException(
          ReplayDecodeReason.MISSING_REQUIRED_SECTION,
          if (shouldEmbed) {
            "Embedded-session replay has no embedded StateFile section"
          } else {
            "Boot-reference replay contains an embedded StateFile section"
          },
      )
    }
    parsed.embeddedState?.let(::validateEmbeddedStateForDecode)
    validateSectionRelationships(initial, inputs, checkpoints)

    val file =
        if (materializeRecords) {
          try {
            ReplayFile(
                identity,
                initial,
                requireNotNull(inputs.records),
                requireNotNull(checkpoints.records),
                parsed.metadata,
                parsed.embeddedState,
            )
          } catch (failure: IllegalArgumentException) {
            throw ReplayDecodeException(
                ReplayDecodeReason.MALFORMED_STRUCTURE,
                failure.message ?: "Replay sections are not mutually consistent",
                failure,
            )
          }
        } else {
          null
        }
    return DecodedContent(
        file,
        identity,
        initial,
        inputs.count,
        checkpoints.count,
        checkpoints.finalTick,
        checkpoints.finalFrame,
        parsed.metadata,
        parsed.embeddedState != null,
        requiredFeatureFlags,
        optionalFeatureFlags,
        encodedPayloadLength,
        parsed.decodedSectionBytes,
        parsed.inspections,
    )
  }

  private fun parseSections(
      payload: ByteArray,
      sectionCount: Int,
      materializeRecords: Boolean,
  ): ParsedSections {
    val reader = ReplayReader(payload)
    val seen = HashSet<Int>(sectionCount)
    val inspections = ArrayList<ReplaySectionInspection>(sectionCount)
    var previousId = 0
    var actualDecodedBytes = 0L
    var identity: ReplayIdentity? = null
    var initial: ReplayInitialConditions? = null
    var inputs: DecodedInputs? = null
    var checkpoints: DecodedCheckpoints? = null
    var metadata: ReplayMetadata? = null
    var embeddedState: ByteArray? = null

    repeat(sectionCount) {
      val id = reader.readU16()
      if (!seen.add(id)) {
        throw ReplayDecodeException(
            ReplayDecodeReason.DUPLICATE_SECTION,
            "Replay section $id occurs more than once",
        )
      }
      if (id == 0 || id <= previousId) {
        throw ReplayDecodeException(
            ReplayDecodeReason.MALFORMED_STRUCTURE,
            "Replay section IDs are not canonical increasing positive values",
        )
      }
      previousId = id

      val version = reader.readU16()
      val flags = reader.readU32()
      if (flags and KNOWN_SECTION_FLAGS.inv() != 0L) {
        throw ReplayDecodeException(
            ReplayDecodeReason.UNSUPPORTED_FLAGS,
            "Replay section $id has undefined flags 0x${flags.toString(16)}",
        )
      }
      val required = flags and SECTION_REQUIRED != 0L
      val compressed = flags and SECTION_DEFLATE != 0L
      val encodedLength =
          requireLength(
              reader.readLong(),
              ReplayLimits.MAX_SECTION_ENCODED_BYTES,
              "Replay section $id encoded length",
          )
      val decodedLength =
          requireLength(
              reader.readLong(),
              ReplayLimits.MAX_SECTION_DECODED_BYTES,
              "Replay section $id decoded length",
          )
      if (!compressed && encodedLength != decodedLength) {
        malformed("Uncompressed replay section $id has different encoded and decoded lengths")
      }
      actualDecodedBytes =
          checkedAddForDecode(
              actualDecodedBytes,
              decodedLength.toLong(),
              ReplayLimits.MAX_TOTAL_DECODED_BYTES.toLong(),
              "Replay decoded section bytes",
          )
      val encoded = reader.readBytes(encodedLength, ReplayLimits.MAX_SECTION_ENCODED_BYTES)
      val compression =
          if (compressed) ReplaySectionCompression.DEFLATE else ReplaySectionCompression.NONE
      inspections +=
          ReplaySectionInspection(
              id,
              version,
              required,
              compression,
              encodedLength.toLong(),
              decodedLength.toLong(),
          )

      val expectedRequired =
          when (id) {
            IDENTITY_SECTION_ID,
            INITIAL_CONDITIONS_SECTION_ID,
            INPUT_SECTION_ID,
            CHECKPOINT_SECTION_ID,
            EMBEDDED_STATE_SECTION_ID -> true
            METADATA_SECTION_ID -> false
            else -> null
          }
      if (expectedRequired != null && required != expectedRequired) {
        malformed(
            "Replay section $id required flag is $required, expected $expectedRequired")
      }
      if (expectedRequired == null && required) {
        throw ReplayDecodeException(
            ReplayDecodeReason.UNKNOWN_REQUIRED_SECTION,
            "Unknown required replay section $id",
        )
      }
      if (compressed && id != INPUT_SECTION_ID && id != CHECKPOINT_SECTION_ID) {
        throw ReplayDecodeException(
            ReplayDecodeReason.UNSUPPORTED_FLAGS,
            "Replay section $id cannot use DEFLATE in format v1",
        )
      }
      if (expectedRequired == null) return@repeat
      if (version != SCHEMA_VERSION) unsupportedSection(id, version)
      val decoded = if (compressed) inflate(encoded, decodedLength, id) else encoded
      when (id) {
        IDENTITY_SECTION_ID -> identity = decodeIdentity(decoded)
        INITIAL_CONDITIONS_SECTION_ID -> initial = decodeInitial(decoded)
        INPUT_SECTION_ID -> inputs = decodeInputs(decoded, materializeRecords)
        CHECKPOINT_SECTION_ID -> checkpoints = decodeCheckpoints(decoded, materializeRecords)
        METADATA_SECTION_ID -> metadata = decodeMetadata(decoded)
        EMBEDDED_STATE_SECTION_ID -> embeddedState = decoded
      }
    }
    reader.requireExhausted()
    return ParsedSections(
        identity,
        initial,
        inputs,
        checkpoints,
        metadata,
        embeddedState,
        actualDecodedBytes,
        Collections.unmodifiableList(inspections),
    )
  }

  private fun encodeIdentity(identity: ReplayIdentity): ByteArray {
    val writer = ReplayWriter(ReplayLimits.MAX_SECTION_DECODED_BYTES)
    writer.writeU16(SCHEMA_VERSION)
    writer.writeBytes(identity.primaryRomSha256)
    val slot = identity.slotRomSha256
    writer.writeByte(if (slot == null) 0 else 1)
    slot?.let(writer::writeBytes)
    writer.writeU16Utf8(identity.canonicalProfileId, ReplayLimits.MAX_PROFILE_ID_BYTES)
    writer.writeRatio(identity.clocks.ticksPerSecond)
    writer.writeRatio(identity.clocks.controllerFramesPerSecond)
    writer.writeLong(identity.bootstrapFlags)
    writer.writeLong(identity.behaviorFlags)
    writer.writeU16(identity.replaySemanticsVersion)
    writer.writeU16(identity.requiredStateFileVersion)
    return writer.toByteArray()
  }

  private fun decodeIdentity(bytes: ByteArray): ReplayIdentity {
    val reader = ReplayReader(bytes)
    requireBodySchema(reader, IDENTITY_SECTION_ID)
    val primary = reader.readBytes(ReplayLimits.SHA256_BYTES, ReplayLimits.SHA256_BYTES)
    val slot =
        when (val present = reader.readByte()) {
          0 -> null
          1 -> reader.readBytes(ReplayLimits.SHA256_BYTES, ReplayLimits.SHA256_BYTES)
          else -> malformed("Replay slot-ROM presence tag $present is invalid")
        }
    val profile = reader.readU16Utf8(ReplayLimits.MAX_PROFILE_ID_BYTES, "hardware profile ID")
    val ticks = reader.readRatio("ticks-per-second")
    val frames = reader.readRatio("controller-frames-per-second")
    val bootstrapFlags = reader.readLong()
    val behaviorFlags = reader.readLong()
    val semantics = reader.readU16()
    if (semantics != ReplayIdentity.REPLAY_SEMANTICS_VERSION) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_REPLAY_SEMANTICS,
          "Unsupported replay semantics version $semantics",
      )
    }
    val stateVersion = reader.readU16()
    if (stateVersion != ReplayIdentity.REQUIRED_STATE_FILE_VERSION) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_STATE_FILE_VERSION,
          "Unsupported required StateFile version $stateVersion",
      )
    }
    reader.requireExhausted()
    return construct("Replay identity is invalid") {
      ReplayIdentity(
          primary,
          slot,
          profile,
          ReplayClockIdentity(ticks, frames),
          bootstrapFlags,
          behaviorFlags,
          semantics,
          stateVersion,
      )
    }
  }

  private fun encodeInitial(initial: ReplayInitialConditions): ByteArray {
    val writer = ReplayWriter(64)
    writer.writeU16(SCHEMA_VERSION)
    writer.writeU16(initial.mode.id)
    writer.writeLong(initial.rtcEpochMillis)
    writer.writeLong(initial.initialTick)
    writer.writeLong(initial.initialFrame)
    return writer.toByteArray()
  }

  private fun decodeInitial(bytes: ByteArray): ReplayInitialConditions {
    val reader = ReplayReader(bytes)
    requireBodySchema(reader, INITIAL_CONDITIONS_SECTION_ID)
    val mode = ReplayInitialMode.fromId(reader.readU16())
    val rtcEpochMillis = reader.readLong()
    val tick = reader.readLong()
    val frame = reader.readLong()
    reader.requireExhausted()
    return construct("Replay initial conditions are invalid") {
      ReplayInitialConditions(mode, rtcEpochMillis, tick, frame)
    }
  }

  private fun encodeInputs(inputs: List<ReplayInputRecord>): ByteArray {
    if (inputs.size > ReplayLimits.MAX_INPUT_RECORDS) {
      throw ReplayEncodeException("Replay input count exceeds ${ReplayLimits.MAX_INPUT_RECORDS}")
    }
    val expected =
        checkedMultiplyForEncode(
            inputs.size.toLong(),
            INPUT_RECORD_SIZE,
            ReplayLimits.MAX_SECTION_DECODED_BYTES.toLong(),
            "Replay input bytes",
        )
    val writer = ReplayWriter(ReplayLimits.MAX_SECTION_DECODED_BYTES, 6 + expected.toInt())
    writer.writeU16(SCHEMA_VERSION)
    writer.writeU32(inputs.size.toLong())
    inputs.forEach {
      writer.writeLong(it.tick)
      writer.writeByte(it.phase.id)
      writer.writeByte(it.player)
      writer.writeByte(it.absoluteMask)
      writer.writeByte(it.changedMask)
    }
    return writer.toByteArray()
  }

  private fun decodeInputs(
      bytes: ByteArray,
      materializeRecords: Boolean,
  ): DecodedInputs {
    val reader = ReplayReader(bytes)
    requireBodySchema(reader, INPUT_SECTION_ID)
    val count = requireCount(reader.readU32(), ReplayLimits.MAX_INPUT_RECORDS, "Replay input count")
    reader.requireFixedRecords(count, INPUT_RECORD_SIZE, "Replay input records")
    val result = if (materializeRecords) ArrayList<ReplayInputRecord>(count) else null
    var firstTick: Long? = null
    var previousTick = -1L
    repeat(count) { index ->
      val tick = reader.readLong()
      val phase = ReplayInputPhase.fromId(reader.readByte())
      val player = reader.readByte()
      val absoluteMask = reader.readByte()
      val changedMask = reader.readByte()
      if (tick !in 0..ReplayLimits.MAX_TIMELINE_TICK || player !in 0..3) {
        malformed("Replay input record $index is invalid")
      }
      if (index > 0 && tick < previousTick) {
        malformed("Replay inputs must have monotonic ticks")
      }
      if (firstTick == null) firstTick = tick
      previousTick = tick
      result?.add(
          ReplayInputRecord(tick, phase, player, absoluteMask, changedMask),
      )
    }
    reader.requireExhausted()
    return DecodedInputs(
        result?.let { Collections.unmodifiableList(it) },
        count,
        firstTick,
        if (count == 0) null else previousTick,
    )
  }

  private fun validateSectionRelationships(
      initial: ReplayInitialConditions,
      inputs: DecodedInputs,
      checkpoints: DecodedCheckpoints,
  ) {
    if (inputs.firstTick != null && inputs.firstTick < initial.initialTick) {
      malformed("Replay input precedes the initial tick")
    }
    if (checkpoints.firstTick < initial.initialTick ||
        checkpoints.firstFrame < initial.initialFrame) {
      malformed("Replay checkpoint precedes the initial conditions")
    }
    if (inputs.lastTick != null && checkpoints.finalTick < inputs.lastTick) {
      malformed("Final replay checkpoint must include every input tick")
    }
  }

  private fun encodeCheckpoints(checkpoints: List<ReplayCheckpoint>): ByteArray {
    if (checkpoints.isEmpty() || checkpoints.size > ReplayLimits.MAX_CHECKPOINTS) {
      throw ReplayEncodeException(
          "Replay checkpoint count must be in 1..${ReplayLimits.MAX_CHECKPOINTS}")
    }
    val expected =
        checkedMultiplyForEncode(
            checkpoints.size.toLong(),
            CHECKPOINT_RECORD_SIZE,
            ReplayLimits.MAX_SECTION_DECODED_BYTES.toLong(),
            "Replay checkpoint bytes",
        )
    val writer = ReplayWriter(ReplayLimits.MAX_SECTION_DECODED_BYTES, 6 + expected.toInt())
    writer.writeU16(SCHEMA_VERSION)
    writer.writeU32(checkpoints.size.toLong())
    checkpoints.forEach {
      writer.writeLong(it.tick)
      writer.writeLong(it.frame)
      it.hashes.copyDigests().forEach(writer::writeBytes)
    }
    return writer.toByteArray()
  }

  private fun decodeCheckpoints(
      bytes: ByteArray,
      materializeRecords: Boolean,
  ): DecodedCheckpoints {
    val reader = ReplayReader(bytes)
    requireBodySchema(reader, CHECKPOINT_SECTION_ID)
    val count =
        requireCount(
            reader.readU32(),
            ReplayLimits.MAX_CHECKPOINTS,
            "Replay checkpoint count",
        )
    if (count == 0) malformed("Replay must contain at least one checkpoint")
    reader.requireFixedRecords(count, CHECKPOINT_RECORD_SIZE, "Replay checkpoint records")
    val result = if (materializeRecords) ArrayList<ReplayCheckpoint>(count) else null
    var firstTick = -1L
    var firstFrame = -1L
    var previousTick = -1L
    var previousFrame = -1L
    repeat(count) { index ->
      val tick = reader.readLong()
      val frame = reader.readLong()
      if (tick !in 0..ReplayLimits.MAX_TIMELINE_TICK || frame < 0L) {
        malformed("Replay checkpoint $index is invalid")
      }
      if (index > 0 && (tick <= previousTick || frame < previousFrame)) {
        malformed("Replay checkpoints must have strictly increasing ticks and monotonic frames")
      }
      if (index == 0) {
        firstTick = tick
        firstFrame = frame
      }
      if (result == null) {
        reader.skipBytes(8 * ReplayLimits.SHA256_BYTES)
      } else {
        val digests =
            Array(8) {
              reader.readBytes(ReplayLimits.SHA256_BYTES, ReplayLimits.SHA256_BYTES)
            }
        result.add(
            ReplayCheckpoint(
                tick,
                frame,
                ReplayStateHashes(
                    digests[0],
                    digests[1],
                    digests[2],
                    digests[3],
                    digests[4],
                    digests[5],
                    digests[6],
                    digests[7],
                ),
            ),
        )
      }
      previousTick = tick
      previousFrame = frame
    }
    reader.requireExhausted()
    return DecodedCheckpoints(
        result?.let { Collections.unmodifiableList(it) },
        count,
        firstTick,
        firstFrame,
        previousTick,
        previousFrame,
    )
  }

  private fun encodeMetadata(metadata: ReplayMetadata): ByteArray {
    val writer = ReplayWriter(ReplayLimits.MAX_METADATA_BYTES)
    writer.writeU16(SCHEMA_VERSION)
    writer.writeNullableUtf8(metadata.producerVersion, ReplayLimits.MAX_PRODUCER_VERSION_BYTES)
    writer.writeNullableLong(metadata.createdAtEpochMillis)
    writer.writeNullableUtf8(metadata.note, ReplayLimits.MAX_METADATA_BYTES)
    return writer.toByteArray()
  }

  private fun decodeMetadata(bytes: ByteArray): ReplayMetadata {
    if (bytes.size > ReplayLimits.MAX_METADATA_BYTES) {
      limit("Replay metadata exceeds ${ReplayLimits.MAX_METADATA_BYTES} bytes")
    }
    val reader = ReplayReader(bytes)
    requireBodySchema(reader, METADATA_SECTION_ID)
    val producer =
        reader.readNullableUtf8(
            ReplayLimits.MAX_PRODUCER_VERSION_BYTES,
            "producer version",
        )
    val created = reader.readNullableLong("creation time")
    val note = reader.readNullableUtf8(ReplayLimits.MAX_METADATA_BYTES, "note")
    reader.requireExhausted()
    if (note != null && note.length > ReplayLimits.MAX_NOTE_CHARS) {
      limit("Replay note exceeds ${ReplayLimits.MAX_NOTE_CHARS} characters")
    }
    return construct("Replay metadata is invalid") { ReplayMetadata(producer, created, note) }
  }

  private fun encodeSections(sections: List<EncodedSection>): ByteArray {
    val writer = ReplayWriter(ReplayLimits.MAX_FILE_BYTES - HEADER_SIZE)
    var previousId = 0
    sections.forEach { section ->
      if (section.id <= previousId) {
        throw ReplayEncodeException("Replay sections are not in canonical order")
      }
      previousId = section.id
      if (section.encoded.size > ReplayLimits.MAX_SECTION_ENCODED_BYTES ||
          section.decodedLength > ReplayLimits.MAX_SECTION_DECODED_BYTES) {
        throw ReplayEncodeException("Replay section ${section.id} exceeds its size limit")
      }
      writer.writeU16(section.id)
      writer.writeU16(SCHEMA_VERSION)
      writer.writeU32(section.flags)
      writer.writeLong(section.encoded.size.toLong())
      writer.writeLong(section.decodedLength.toLong())
      writer.writeBytes(section.encoded)
    }
    return writer.toByteArray()
  }

  private fun requiredSection(id: Int, decoded: ByteArray): EncodedSection =
      EncodedSection(id, SECTION_REQUIRED, decoded.clone(), decoded.size)

  private fun optionalSection(id: Int, decoded: ByteArray): EncodedSection =
      EncodedSection(id, 0, decoded.clone(), decoded.size)

  private fun compressedRequiredSection(id: Int, decoded: ByteArray): EncodedSection {
    val compressed = deflate(decoded)
    return if (compressed.size < decoded.size) {
      EncodedSection(id, SECTION_REQUIRED or SECTION_DEFLATE, compressed, decoded.size)
    } else {
      EncodedSection(id, SECTION_REQUIRED, decoded, decoded.size)
    }
  }

  private fun deflate(decoded: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    return try {
      deflater.setInput(decoded)
      deflater.finish()
      val writer = ReplayWriter(ReplayLimits.MAX_SECTION_ENCODED_BYTES)
      val scratch = ByteArray(8192)
      while (!deflater.finished()) {
        val count = deflater.deflate(scratch, 0, scratch.size, Deflater.NO_FLUSH)
        if (count == 0 && !deflater.finished()) {
          throw ReplayEncodeException("Raw DEFLATE encoder made no progress")
        }
        writer.writeBytes(scratch, 0, count)
      }
      writer.toByteArray()
    } finally {
      deflater.end()
    }
  }

  private fun inflate(encoded: ByteArray, declaredLength: Int, sectionId: Int): ByteArray {
    val inflater = Inflater(true)
    return try {
      inflater.setInput(encoded)
      val writer = ReplayWriter(declaredLength, declaredLength)
      val scratch = ByteArray(8192)
      while (!inflater.finished()) {
        val count =
            try {
              inflater.inflate(scratch)
            } catch (failure: DataFormatException) {
              throw ReplayDecodeException(
                  ReplayDecodeReason.COMPRESSION_ERROR,
                  "Replay section $sectionId has corrupt raw DEFLATE data",
                  failure,
              )
            }
        if (count > 0) {
          if (count > declaredLength - writer.count) {
            limit("Replay section $sectionId DEFLATE output exceeds its declaration")
          }
          try {
            writer.writeBytes(scratch, 0, count)
          } catch (failure: ReplayEncodeException) {
            limit(
                failure.message
                    ?: "Replay section $sectionId DEFLATE output exceeds its declaration")
          }
        } else if (inflater.needsDictionary()) {
          throw ReplayDecodeException(
              ReplayDecodeReason.COMPRESSION_ERROR,
              "Replay section $sectionId requests a DEFLATE dictionary",
          )
        } else if (inflater.needsInput()) {
          throw ReplayDecodeException(
              ReplayDecodeReason.TRUNCATED,
              "Replay section $sectionId DEFLATE stream is truncated",
          )
        } else {
          throw ReplayDecodeException(
              ReplayDecodeReason.COMPRESSION_ERROR,
              "Replay section $sectionId DEFLATE decoder made no progress",
          )
        }
      }
      if (inflater.remaining != 0) {
        throw ReplayDecodeException(
            ReplayDecodeReason.TRAILING_DATA,
            "Replay section $sectionId DEFLATE stream has trailing data",
        )
      }
      if (writer.count != declaredLength) {
        throw ReplayDecodeException(
            ReplayDecodeReason.COMPRESSION_ERROR,
            "Replay section $sectionId decoded to ${writer.count}, expected $declaredLength",
        )
      }
      writer.toByteArray()
    } finally {
      inflater.end()
    }
  }

  private fun validateEmbeddedStateForEncode(file: ReplayFile) {
    file.embeddedState?.let { bytes ->
      try {
        validateEmbeddedStateInspection(bytes)
      } catch (failure: ReplayDecodeException) {
        throw ReplayEncodeException(failure.message ?: "Embedded StateFile is invalid", failure)
      }
    }
  }

  private fun validateEmbeddedStateForDecode(bytes: ByteArray) {
    try {
      validateEmbeddedStateInspection(bytes)
    } catch (failure: ReplayDecodeException) {
      throw failure
    } catch (failure: Exception) {
      throw ReplayDecodeException(
          ReplayDecodeReason.INVALID_EMBEDDED_STATE,
          "Embedded replay StateFile is invalid",
          failure,
      )
    }
  }

  private fun validateEmbeddedStateInspection(bytes: ByteArray) {
    if (bytes.size > ReplayLimits.MAX_EMBEDDED_STATE_BYTES) {
      limit("Embedded StateFile exceeds ${ReplayLimits.MAX_EMBEDDED_STATE_BYTES} bytes")
    }
    val inspection =
        try {
          StateCodec.inspectNetworkState(bytes)
        } catch (failure: StateDecodeException) {
          throw ReplayDecodeException(
              ReplayDecodeReason.INVALID_EMBEDDED_STATE,
              "Embedded replay StateFile is invalid: ${failure.message}",
              failure,
          )
        }
    if (inspection.formatVersion != ReplayIdentity.REQUIRED_STATE_FILE_VERSION) {
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_STATE_FILE_VERSION,
          "Embedded replay state must be StateFile v${ReplayIdentity.REQUIRED_STATE_FILE_VERSION}",
      )
    }
    if (inspection.rootKind != StateRootKind.SESSION) {
      throw ReplayDecodeException(
          ReplayDecodeReason.INVALID_EMBEDDED_STATE,
          "Embedded replay state must have a SESSION root",
      )
    }
  }

  private fun requireBodySchema(reader: ReplayReader, sectionId: Int) {
    val version = reader.readU16()
    if (version != SCHEMA_VERSION) unsupportedSection(sectionId, version)
  }

  private fun requireCount(value: Long, maximum: Int, label: String): Int {
    if (value > maximum.toLong()) limit("$label $value exceeds $maximum")
    return value.toInt()
  }

  private fun requireLength(value: Long, maximum: Int, label: String): Int {
    if (value < 0) malformed("$label is negative")
    if (value > maximum.toLong() || value > Int.MAX_VALUE.toLong()) {
      limit("$label $value exceeds $maximum")
    }
    return value.toInt()
  }

  private fun checkedAddForDecode(left: Long, right: Long, maximum: Long, label: String): Long {
    val result =
        try {
          Math.addExact(left, right)
        } catch (_: ArithmeticException) {
          limit("$label overflows")
        }
    if (result > maximum) limit("$label $result exceeds $maximum")
    return result
  }

  private fun checkedAddForEncode(left: Long, right: Long, maximum: Long, label: String): Long {
    val result =
        try {
          Math.addExact(left, right)
        } catch (_: ArithmeticException) {
          throw ReplayEncodeException("$label overflows")
        }
    if (result > maximum) throw ReplayEncodeException("$label $result exceeds $maximum")
    return result
  }

  private fun checkedMultiplyForEncode(
      left: Long,
      right: Long,
      maximum: Long,
      label: String,
  ): Long {
    val result =
        try {
          Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
          throw ReplayEncodeException("$label overflows")
        }
    if (result > maximum) throw ReplayEncodeException("$label $result exceeds $maximum")
    return result
  }

  private inline fun <T> construct(label: String, block: () -> T): T =
      try {
        block()
      } catch (failure: IllegalArgumentException) {
        throw ReplayDecodeException(
            ReplayDecodeReason.MALFORMED_STRUCTURE,
            "$label: ${failure.message}",
            failure,
        )
      }

  private fun unsupportedSection(id: Int, version: Int): Nothing =
      throw ReplayDecodeException(
          ReplayDecodeReason.UNSUPPORTED_SECTION_VERSION,
          "Unsupported version $version for replay section $id",
      )

  private fun missing(id: Int, name: String): Nothing =
      throw ReplayDecodeException(
          ReplayDecodeReason.MISSING_REQUIRED_SECTION,
          "Replay has no $name section ($id)",
      )

  private fun limit(message: String): Nothing =
      throw ReplayDecodeException(ReplayDecodeReason.LIMIT_EXCEEDED, message)

  private fun malformed(message: String): Nothing =
      throw ReplayDecodeException(ReplayDecodeReason.MALFORMED_STRUCTURE, message)

  private fun sha256(bytes: ByteArray): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(bytes)

  private data class EncodedSection(
      val id: Int,
      val flags: Long,
      val encoded: ByteArray,
      val decodedLength: Int,
  )

  private data class ParsedSections(
      val identity: ReplayIdentity?,
      val initialConditions: ReplayInitialConditions?,
      val inputs: DecodedInputs?,
      val checkpoints: DecodedCheckpoints?,
      val metadata: ReplayMetadata?,
      val embeddedState: ByteArray?,
      val decodedSectionBytes: Long,
      val inspections: List<ReplaySectionInspection>,
  )

  private data class DecodedInputs(
      val records: List<ReplayInputRecord>?,
      val count: Int,
      val firstTick: Long?,
      val lastTick: Long?,
  )

  private data class DecodedCheckpoints(
      val records: List<ReplayCheckpoint>?,
      val count: Int,
      val firstTick: Long,
      val firstFrame: Long,
      val finalTick: Long,
      val finalFrame: Long,
  )

  private data class DecodedContent(
      val file: ReplayFile?,
      val identity: ReplayIdentity,
      val initialConditions: ReplayInitialConditions,
      val inputCount: Int,
      val checkpointCount: Int,
      val finalTick: Long,
      val finalFrame: Long,
      val metadata: ReplayMetadata?,
      val hasEmbeddedState: Boolean,
      val requiredFeatureFlags: Long,
      val optionalFeatureFlags: Long,
      val encodedPayloadLength: Int,
      val decodedSectionBytes: Long,
      val inspections: List<ReplaySectionInspection>,
  )
}

private class ReplayWriter(
    private val maximum: Int,
    initialCapacity: Int = 256,
) {
  private var buffer = ByteArray(minOf(maximum, maxOf(0, initialCapacity)))
  private var size = 0

  val count: Int
    get() = size

  fun writeByte(value: Int) {
    if (value !in 0..0xff) throw ReplayEncodeException("Replay byte value $value is invalid")
    ensure(1)
    buffer[size++] = value.toByte()
  }

  fun writeU16(value: Int) {
    if (value !in 0..0xffff) {
      throw ReplayEncodeException("Replay unsigned 16-bit value $value is invalid")
    }
    ensure(2)
    buffer[size++] = (value ushr 8).toByte()
    buffer[size++] = value.toByte()
  }

  fun writeU32(value: Long) {
    if (value !in 0..0xffff_ffffL) {
      throw ReplayEncodeException("Replay unsigned 32-bit value $value is invalid")
    }
    ensure(4)
    for (shift in 24 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
  }

  fun writeLong(value: Long) {
    ensure(8)
    for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
  }

  fun writeRatio(value: ReplayClockRatio) {
    writeLong(value.numerator)
    writeLong(value.denominator)
  }

  fun writeBytes(value: ByteArray) = writeBytes(value, 0, value.size)

  fun writeBytes(value: ByteArray, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > value.size - length) {
      throw ReplayEncodeException("Replay byte slice is invalid")
    }
    ensure(length)
    value.copyInto(buffer, size, offset, offset + length)
    size += length
  }

  fun writeU16Utf8(value: String, maximumBytes: Int) {
    val encoded = strictUtf8(value, maximumBytes)
    writeU16(encoded.size)
    writeBytes(encoded)
  }

  fun writeNullableUtf8(value: String?, maximumBytes: Int) {
    writeByte(if (value == null) 0 else 1)
    if (value != null) {
      val encoded = strictUtf8(value, maximumBytes)
      writeU32(encoded.size.toLong())
      writeBytes(encoded)
    }
  }

  fun writeNullableLong(value: Long?) {
    writeByte(if (value == null) 0 else 1)
    value?.let(::writeLong)
  }

  fun toByteArray(): ByteArray = buffer.copyOf(size)

  private fun strictUtf8(value: String, maximumBytes: Int): ByteArray {
    val encoded =
        try {
          val buffer =
              StandardCharsets.UTF_8
                  .newEncoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .encode(CharBuffer.wrap(value))
          ByteArray(buffer.remaining()).also(buffer::get)
        } catch (failure: Exception) {
          throw ReplayEncodeException("Replay text is not valid Unicode", failure)
        }
    if (encoded.size > maximumBytes) {
      throw ReplayEncodeException("Replay UTF-8 text exceeds $maximumBytes bytes")
    }
    return encoded
  }

  private fun ensure(additional: Int) {
    if (additional < 0) throw ReplayEncodeException("Replay writer request is negative")
    val required =
        try {
          Math.addExact(size, additional)
        } catch (_: ArithmeticException) {
          throw ReplayEncodeException("Replay writer size overflows")
        }
    if (required > maximum) {
      throw ReplayEncodeException("Replay writer size $required exceeds $maximum")
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
        throw ReplayEncodeException("Replay writer cannot grow to $required")
      }
    }
    buffer = buffer.copyOf(capacity)
  }
}

private class ReplayReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
) {
  val remaining: Int
    get() = bytes.size - position

  fun readByte(): Int {
    requireRemaining(1)
    return bytes[position++].toInt() and 0xff
  }

  fun readU16(): Int = (readByte() shl 8) or readByte()

  fun readU32(): Long {
    var value = 0L
    repeat(4) { value = (value shl 8) or readByte().toLong() }
    return value
  }

  fun readLong(): Long {
    var value = 0L
    repeat(8) { value = (value shl 8) or readByte().toLong() }
    return value
  }

  fun readRatio(label: String): ReplayClockRatio {
    val numerator = readLong()
    val denominator = readLong()
    return try {
      ReplayClockRatio(numerator, denominator)
    } catch (failure: IllegalArgumentException) {
      throw ReplayDecodeException(
          ReplayDecodeReason.MALFORMED_STRUCTURE,
          "Replay $label clock rational is invalid: ${failure.message}",
          failure,
      )
    }
  }

  fun readBytes(length: Int, maximum: Int): ByteArray {
    if (length < 0) {
      throw ReplayDecodeException(
          ReplayDecodeReason.MALFORMED_STRUCTURE,
          "Replay byte length is negative",
      )
    }
    if (length > maximum) {
      throw ReplayDecodeException(
          ReplayDecodeReason.LIMIT_EXCEEDED,
          "Replay byte length $length exceeds $maximum",
      )
    }
    requireRemaining(length)
    return bytes.copyOfRange(position, position + length).also { position += length }
  }

  fun skipBytes(length: Int) {
    requireRemaining(length)
    position += length
  }

  fun readU16Utf8(maximumBytes: Int, label: String): String =
      decodeUtf8(readBytes(readU16(), maximumBytes), label)

  fun readNullableUtf8(maximumBytes: Int, label: String): String? =
      when (val present = readByte()) {
        0 -> null
        1 -> {
          val length = readU32()
          if (length > maximumBytes.toLong()) {
            throw ReplayDecodeException(
                ReplayDecodeReason.LIMIT_EXCEEDED,
                "Replay $label UTF-8 length $length exceeds $maximumBytes",
            )
          }
          decodeUtf8(readBytes(length.toInt(), maximumBytes), label)
        }
        else ->
            throw ReplayDecodeException(
                ReplayDecodeReason.MALFORMED_STRUCTURE,
                "Replay $label presence tag $present is invalid",
            )
      }

  fun readNullableLong(label: String): Long? =
      when (val present = readByte()) {
        0 -> null
        1 -> readLong()
        else ->
            throw ReplayDecodeException(
                ReplayDecodeReason.MALFORMED_STRUCTURE,
                "Replay $label presence tag $present is invalid",
            )
      }

  fun requireFixedRecords(count: Int, width: Long, label: String) {
    val required =
        try {
          Math.multiplyExact(count.toLong(), width)
        } catch (_: ArithmeticException) {
          throw ReplayDecodeException(ReplayDecodeReason.LIMIT_EXCEEDED, "$label overflow")
        }
    if (required > remaining.toLong()) {
      throw ReplayDecodeException(
          ReplayDecodeReason.TRUNCATED,
          "$label need $required bytes but only $remaining remain",
      )
    }
  }

  fun requireExhausted() {
    if (remaining != 0) {
      throw ReplayDecodeException(
          ReplayDecodeReason.TRAILING_DATA,
          "Replay structure has $remaining trailing bytes",
      )
    }
  }

  private fun decodeUtf8(encoded: ByteArray, label: String): String =
      try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
      } catch (failure: Exception) {
        throw ReplayDecodeException(
            ReplayDecodeReason.MALFORMED_UTF8,
            "Replay $label is not strict UTF-8",
            failure,
        )
      }

  private fun requireRemaining(length: Int) {
    if (length < 0 || length > remaining) {
      throw ReplayDecodeException(
          ReplayDecodeReason.TRUNCATED,
          "Replay input needs $length bytes but only $remaining remain",
      )
    }
  }
}
