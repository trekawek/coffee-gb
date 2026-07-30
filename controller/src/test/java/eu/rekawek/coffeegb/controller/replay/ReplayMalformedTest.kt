package eu.rekawek.coffeegb.controller.replay

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ReplayMalformedTest {
  @Test
  fun inspectAndDecodeRejectTheUnrepresentableTerminalTick() {
    val validTick = 0x0102_0304_0506_0708L
    val digests =
        Array(8) { seed ->
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(byteArrayOf(seed.toByte()))
        }
    val hashes =
        ReplayStateHashes(
            digests[0],
            digests[1],
            digests[2],
            digests[3],
            digests[4],
            digests[5],
            digests[6],
            digests[7],
        )
    val encoded =
        ReplayCodec.encode(
            ReplayTestFixture.file(
                inputs =
                    listOf(
                        ReplayInputRecord(
                            validTick,
                            ReplayInputPhase.LEGACY_P1_BEFORE_TICK,
                            0,
                            0xa5,
                            0xa5,
                        ),
                    ),
                checkpoints = listOf(ReplayCheckpoint(validTick, 0, hashes)),
            ),
        )
    val offsets = ReplayTestFixture.sectionOffsets(encoded)
    val inputOffset = offsets.single { ReplayTestFixture.readU16(encoded, it) == ReplayCodec.INPUT_SECTION_ID }
    val checkpointOffset =
        offsets.single { ReplayTestFixture.readU16(encoded, it) == ReplayCodec.CHECKPOINT_SECTION_ID }
    assertEquals(1, ReplayTestFixture.readInt(encoded, inputOffset + 4))
    assertEquals(1, ReplayTestFixture.readInt(encoded, checkpointOffset + 4))

    val invalidInput = encoded.clone()
    ReplayTestFixture.writeLong(
        invalidInput,
        inputOffset + ReplayCodec.SECTION_HEADER_SIZE + 6,
        Long.MAX_VALUE,
    )
    assertReasonForDecodeAndInspect(
        ReplayDecodeReason.MALFORMED_STRUCTURE,
        ReplayTestFixture.withChecksum(invalidInput),
    )

    val invalidCheckpoint = encoded.clone()
    ReplayTestFixture.writeLong(
        invalidCheckpoint,
        checkpointOffset + ReplayCodec.SECTION_HEADER_SIZE + 6,
        Long.MAX_VALUE,
    )
    assertReasonForDecodeAndInspect(
        ReplayDecodeReason.MALFORMED_STRUCTURE,
        ReplayTestFixture.withChecksum(invalidCheckpoint),
    )
  }

  @Test
  fun rejectsInvalidEnvelopeFieldsWithTypedReasons() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())

    assertReason(ReplayDecodeReason.INVALID_MAGIC, encoded.clone().also { it[0] = 'X'.code.toByte() })
    assertReason(
        ReplayDecodeReason.UNSUPPORTED_FORMAT_VERSION,
        encoded.clone().also { ReplayTestFixture.writeU16(it, 4, 2) },
    )
    assertReason(
        ReplayDecodeReason.UNSUPPORTED_FLAGS,
        encoded.clone().also { ReplayTestFixture.writeLong(it, 8, 1) },
    )
    assertReason(
        ReplayDecodeReason.UNSUPPORTED_FLAGS,
        encoded.clone().also { ReplayTestFixture.writeInt(it, 28, 1) },
    )
    assertReason(
        ReplayDecodeReason.LIMIT_EXCEEDED,
        encoded.clone().also {
          ReplayTestFixture.writeLong(it, 32, ReplayLimits.MAX_FILE_BYTES.toLong())
        },
    )
    assertReason(ReplayDecodeReason.TRUNCATED, encoded.copyOf(encoded.size - 1))
    assertReason(
        ReplayDecodeReason.CORRUPT_CHECKSUM,
        encoded.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
    )
  }

  @Test
  fun rejectsDuplicateAndUnknownRequiredSections() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())
    val offsets = ReplayTestFixture.sectionOffsets(encoded)

    val duplicate = encoded.clone()
    ReplayTestFixture.writeU16(duplicate, offsets[1], ReplayCodec.IDENTITY_SECTION_ID)
    assertReason(ReplayDecodeReason.DUPLICATE_SECTION, ReplayTestFixture.withChecksum(duplicate))

    val unknownRequired = encoded.clone()
    ReplayTestFixture.writeU16(unknownRequired, offsets.last(), 7)
    ReplayTestFixture.writeInt(unknownRequired, offsets.last() + 4, 1)
    assertReason(
        ReplayDecodeReason.UNKNOWN_REQUIRED_SECTION,
        ReplayTestFixture.withChecksum(unknownRequired),
    )
  }

  @Test
  fun skipsAnUnknownOptionalSectionButRetainsStrictCanonicalOrdering() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())
    val metadataOffset = ReplayTestFixture.sectionOffsets(encoded).last()
    val unknownOptional = encoded.clone()
    ReplayTestFixture.writeU16(unknownOptional, metadataOffset, 7)
    ReplayTestFixture.writeInt(unknownOptional, metadataOffset + 4, 0)

    val decoded = ReplayCodec.decode(ReplayTestFixture.withChecksum(unknownOptional))

    assertEquals(null, decoded.metadata)
    assertEquals(ReplayTestFixture.file().inputs, decoded.inputs)

    val compressedUnknown = unknownOptional.clone()
    ReplayTestFixture.writeInt(compressedUnknown, metadataOffset + 4, 2)
    assertReasonForDecodeAndInspect(
        ReplayDecodeReason.UNSUPPORTED_FLAGS,
        ReplayTestFixture.withChecksum(compressedUnknown),
    )
  }

  @Test
  fun strictUtf8AndSectionFlagsAreValidatedBeforeModelConstruction() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())
    val identityOffset = ReplayTestFixture.sectionOffsets(encoded).first()
    val identityBody = identityOffset + ReplayCodec.SECTION_HEADER_SIZE
    // schema u16 + primary digest + slot-presence u8 + profile length u16
    val profileFirstByte = identityBody + 2 + ReplayLimits.SHA256_BYTES + 1 + 2
    val malformedUtf8 = encoded.clone().also { it[profileFirstByte] = 0xc0.toByte() }
    assertReason(ReplayDecodeReason.MALFORMED_UTF8, ReplayTestFixture.withChecksum(malformedUtf8))

    val badFlags = encoded.clone()
    ReplayTestFixture.writeInt(badFlags, identityOffset + 4, 4)
    assertReason(ReplayDecodeReason.UNSUPPORTED_FLAGS, ReplayTestFixture.withChecksum(badFlags))
  }

  @Test
  fun embeddedModeRequiresAValidSessionStateFileV2() {
    val encoded = ReplayCodec.encode(ReplayTestFixture.file())
    val initialOffset = ReplayTestFixture.sectionOffsets(encoded)[1]
    val initialBody = initialOffset + ReplayCodec.SECTION_HEADER_SIZE
    val embeddedWithoutSection = encoded.clone()
    ReplayTestFixture.writeU16(
        embeddedWithoutSection,
        initialBody + 2,
        ReplayInitialMode.EMBEDDED_SESSION_STATE.id,
    )
    assertReason(
        ReplayDecodeReason.MISSING_REQUIRED_SECTION,
        ReplayTestFixture.withChecksum(embeddedWithoutSection),
    )

    assertFailsWith<IllegalArgumentException> {
      ReplayTestFixture.file(
          initial =
              ReplayInitialConditions(
                  ReplayInitialMode.EMBEDDED_SESSION_STATE,
                  0,
              ),
          embeddedState = null,
      )
    }

    val invalidEmbedded =
        ReplayFile(
            ReplayTestFixture.identity(),
            ReplayInitialConditions(ReplayInitialMode.EMBEDDED_SESSION_STATE, 0),
            emptyList(),
            listOf(ReplayCheckpoint(0, 0, ReplayTestFixture.hashes())),
            embeddedState = byteArrayOf(1, 2, 3),
        )
    assertFailsWith<ReplayEncodeException> { ReplayCodec.encode(invalidEmbedded) }
  }

  @Test
  fun corruptCompressedInputCannotEscapeItsDeclaredBound() {
    val inputs =
        List(500) {
          ReplayInputRecord(1, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 1, 1)
        }
    val encoded =
        ReplayCodec.encode(
            ReplayTestFixture.file(
                inputs = inputs,
                checkpoints = listOf(ReplayCheckpoint(1, 0, ReplayTestFixture.hashes())),
            ))
    val inputOffset =
        ReplayTestFixture.sectionOffsets(encoded).single {
          ReplayTestFixture.readU16(encoded, it) == ReplayCodec.INPUT_SECTION_ID
        }
    assertEquals(3, ReplayTestFixture.readInt(encoded, inputOffset + 4))
    val corrupt = encoded.clone()
    corrupt[inputOffset + ReplayCodec.SECTION_HEADER_SIZE] = 0xff.toByte()
    corrupt[inputOffset + ReplayCodec.SECTION_HEADER_SIZE + 1] = 0xff.toByte()

    val failure =
        assertFailsWith<ReplayDecodeException> {
          ReplayCodec.decode(ReplayTestFixture.withChecksum(corrupt))
        }
    assertTrue(
        failure.reason == ReplayDecodeReason.COMPRESSION_ERROR ||
            failure.reason == ReplayDecodeReason.TRUNCATED)
  }

  private fun assertReason(reason: ReplayDecodeReason, bytes: ByteArray) {
    val failure = assertFailsWith<ReplayDecodeException> { ReplayCodec.decode(bytes) }
    assertEquals(reason, failure.reason)
  }

  private fun assertReasonForDecodeAndInspect(reason: ReplayDecodeReason, bytes: ByteArray) {
    assertReason(reason, bytes)
    val inspectionFailure = assertFailsWith<ReplayDecodeException> { ReplayCodec.inspect(bytes) }
    assertEquals(reason, inspectionFailure.reason)
  }
}
