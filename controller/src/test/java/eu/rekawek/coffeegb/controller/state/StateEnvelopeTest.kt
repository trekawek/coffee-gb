package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport.RawSection
import java.util.zip.Deflater
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class StateEnvelopeTest {

  @Test
  fun headerMutationsHaveStableTypedReasons() {
    val baseline = baseline()
    assertMutation(baseline, StateDecodeReason.INVALID_MAGIC) { it[0] = 'X'.code.toByte() }
    assertMutation(baseline, StateDecodeReason.UNSUPPORTED_FORMAT_VERSION) {
      StateCodecTestSupport.writeU16(it, 4, StateCodec.LATEST_FORMAT_VERSION + 1)
    }
    assertMutation(baseline, StateDecodeReason.UNSUPPORTED_FORMAT_VERSION) {
      StateCodecTestSupport.writeU16(it, 6, StateCodec.HEADER_SIZE + 1)
    }
    assertMutation(baseline, StateDecodeReason.UNSUPPORTED_FLAGS) {
      StateCodecTestSupport.writeInt(it, 8, 2)
    }
    assertMutation(baseline, StateDecodeReason.MALFORMED_TAG) { it[12] = 0x7f }
    assertMutation(baseline, StateDecodeReason.UNSUPPORTED_FORMAT_VERSION) { it[13] = 2 }
    assertMutation(baseline, StateDecodeReason.UNSUPPORTED_FLAGS) { it[15] = 1 }
    assertMutation(baseline, StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodecTestSupport.writeInt(it, 16, StateLimits.PORTABLE_MAX_SECTIONS + 1)
    }
    assertMutation(baseline, StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodecTestSupport.writeLong(it, 20, -1)
    }
    assertMutation(baseline, StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodecTestSupport.writeLong(
          it,
          20,
          StateLimits.PORTABLE_MAX_ENCODED_PAYLOAD_BYTES.toLong() + 1,
      )
    }
    assertMutation(baseline, StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodecTestSupport.writeLong(it, 28, -1)
    }
    assertMutation(baseline, StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodecTestSupport.writeLong(
          it,
          28,
          StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES.toLong() + 1,
      )
    }
    assertMutation(baseline, StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodecTestSupport.writeLong(it, 28, StateCodecTestSupport.readLong(it, 28) + 1)
    }
    assertMutation(baseline, StateDecodeReason.CORRUPT_CHECKSUM) { it[36] = (it[36].toInt() xor 1).toByte() }

    listOf(0, 3, 17, StateCodec.HEADER_SIZE - 1, baseline.size - 1).forEach { length ->
      assertReason(StateDecodeReason.TRUNCATED) { StateCodec.decode(baseline.copyOf(length)) }
    }
    assertReason(StateDecodeReason.TRAILING_DATA) {
      StateCodec.decode(baseline + byteArrayOf(0))
    }
  }

  @Test
  fun sectionDirectoryRequiresCanonicalSingletonsAndSkipsOnlyUnknownOptionalSections() {
    val baseline = baseline()
    val known = StateCodecTestSupport.sections(baseline)
    val identity = known.single { it.id == 1 }
    val state = known.single { it.id == 2 }

    assertReason(StateDecodeReason.MISSING_REQUIRED_SECTION) {
      StateCodec.decode(StateCodecTestSupport.rawFile(StateRootKind.SESSION, listOf(state)))
    }
    assertReason(StateDecodeReason.MISSING_REQUIRED_SECTION) {
      StateCodec.decode(StateCodecTestSupport.rawFile(StateRootKind.SESSION, listOf(identity)))
    }
    assertReason(StateDecodeReason.DUPLICATE_SECTION) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(StateRootKind.SESSION, listOf(identity, identity, state)))
    }
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(StateRootKind.SESSION, listOf(state, identity)))
    }
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(id = 0), state),
          ))
    }
    assertReason(StateDecodeReason.UNSUPPORTED_SECTION_VERSION) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(version = 2), state),
          ))
    }
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(flags = 0), state),
          ))
    }
    assertReason(StateDecodeReason.UNSUPPORTED_FLAGS) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(reserved = 1), state),
          ))
    }
    assertReason(StateDecodeReason.UNKNOWN_REQUIRED_SECTION) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity, state, RawSection(4, 99, 1, 0, byteArrayOf(1))),
          ))
    }
    val optional =
        StateCodecTestSupport.rawFile(
            StateRootKind.SESSION,
            listOf(identity, state, RawSection(4, 99, 0, 0, byteArrayOf(1, 2, 3))),
        )
    assertEquals(StateCodec.decode(baseline).root, StateCodec.decode(optional).root)
    assertEquals(listOf(1, 2, 4), StateCodec.inspect(optional).sections.map { it.id })

    assertReason(StateDecodeReason.TRUNCATED) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(
                  identity.copy(
                      declaredLength =
                          identity.body.size.toLong() +
                              StateCodec.SECTION_HEADER_SIZE +
                              state.body.size +
                              1),
                  state,
              ),
          ))
    }
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(declaredLength = -1), state),
          ))
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(
                  identity.copy(
                      declaredLength = StateLimits.PORTABLE_MAX_SECTION_BYTES.toLong() + 1),
                  state,
              ),
          ))
    }

    val withTrailingDecoded = baseline.copyOf(baseline.size + 1)
    StateCodecTestSupport.writeLong(
        withTrailingDecoded,
        20,
        StateCodecTestSupport.readLong(withTrailingDecoded, 20) + 1,
    )
    StateCodecTestSupport.writeLong(
        withTrailingDecoded,
        28,
        StateCodecTestSupport.readLong(withTrailingDecoded, 28) + 1,
    )
    assertReason(StateDecodeReason.TRAILING_DATA) {
      StateCodec.decode(StateCodecTestSupport.withChecksum(withTrailingDecoded))
    }

    val maximumDirectory =
        listOf(identity, state) + (4..65).map { RawSection(it, 1, 0, 0, byteArrayOf()) }
    StateCodec.decode(StateCodecTestSupport.rawFile(StateRootKind.SESSION, maximumDirectory))
  }

  @Test
  fun identityAndPayloadTagsAreValidatedBeforeDtoAdmission() {
    val baseline = baseline()
    val sections = StateCodecTestSupport.sections(baseline)
    val identity = sections[0]
    val state = sections[1]
    fun identityMutation(offset: Int, value: Int, reason: StateDecodeReason) {
      val body = identity.body.clone().also { it[offset] = value.toByte() }
      assertReason(reason) {
        StateCodec.decode(
            StateCodecTestSupport.rawFile(
                StateRootKind.SESSION,
                listOf(identity.copy(body = body), state),
            ))
      }
    }
    identityMutation(8, 2, StateDecodeReason.MALFORMED_TAG)
    identityMutation(43, 2, StateDecodeReason.HARDWARE_PROFILE_MISMATCH)
    identityMutation(44, 99, StateDecodeReason.MALFORMED_ENUM)
    identityMutation(45, 99, StateDecodeReason.MALFORMED_ENUM)
    val profileFlags = identity.body.clone()
    profileFlags[49] = 0x10
    assertReason(StateDecodeReason.UNSUPPORTED_FLAGS) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(body = profileFlags), state),
          ))
    }
    val noncanonicalProfile = identity.body.clone()
    noncanonicalProfile[49] = 1
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity.copy(body = noncanonicalProfile), state),
          ))
    }

    val malformedBoolean = state.body.clone().also { it[3] = 2 }
    assertReason(StateDecodeReason.MALFORMED_TAG) {
      StateCodec.decode(
          StateCodecTestSupport.rawFile(
              StateRootKind.SESSION,
              listOf(identity, state.copy(body = malformedBoolean)),
          ))
    }
  }

  @Test
  fun rawDeflateRejectsCorruptionTruncationMismatchedSizesTrailingStreamsAndBombs() {
    val file =
        StateCodecTestSupport.session().use {
          StateCodec.capture(it)
        }
    val uncompressed = StateCodec.encode(file)
    val sections = StateCodecTestSupport.sections(uncompressed)
    val decoded =
        uncompressed.copyOfRange(StateCodec.HEADER_SIZE, uncompressed.size)
    val compressed = rawDeflate(decoded)

    fun compressedFile(
        payload: ByteArray,
        declared: Long = decoded.size.toLong(),
    ) =
        StateCodecTestSupport.rawFile(
            StateRootKind.SESSION,
            sections,
            envelopeFlags = 1,
            decodedOverride = declared,
            encodedPayloadOverride = payload,
        )

    assertEquals(file, StateCodec.decode(compressedFile(compressed)))
    assertReason(StateDecodeReason.TRUNCATED) {
      StateCodec.decode(compressedFile(compressed.copyOf(compressed.size - 1)))
    }
    assertReason(StateDecodeReason.COMPRESSION_ERROR) {
      // BFINAL=1 with reserved BTYPE=3 is never a legal RFC 1951 block.
      StateCodec.decode(compressedFile(byteArrayOf(0x07)))
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodec.decode(compressedFile(compressed, decoded.size.toLong() - 1))
    }
    assertReason(StateDecodeReason.COMPRESSION_ERROR) {
      StateCodec.decode(compressedFile(compressed, decoded.size.toLong() + 1))
    }
    assertReason(StateDecodeReason.TRAILING_DATA) {
      StateCodec.decode(compressedFile(compressed + byteArrayOf(0)))
    }
    assertReason(StateDecodeReason.TRAILING_DATA) {
      StateCodec.decode(compressedFile(compressed + compressed))
    }

    val dictionary = decoded.copyOfRange(0, minOf(32_768, decoded.size))
    val dictionaryStream = rawDeflate(decoded, dictionary)
    assertReason(StateDecodeReason.COMPRESSION_ERROR) {
      StateCodec.decode(compressedFile(dictionaryStream))
    }

    val bomb = rawDeflate(ByteArray(1024 * 1024))
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      StateCodec.decode(compressedFile(bomb, 64))
    }
  }

  private fun baseline(): ByteArray =
      StateCodecTestSupport.session().use {
        repeat(333) { _ -> it.gameboy.tick() }
        StateCodec.encode(StateCodec.capture(it))
      }

  private fun rawDeflate(input: ByteArray, dictionary: ByteArray? = null): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    return try {
      dictionary?.let(deflater::setDictionary)
      deflater.setInput(input)
      deflater.finish()
      val result = ArrayList<Byte>()
      val buffer = ByteArray(8192)
      while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        repeat(count) { result += buffer[it] }
      }
      result.toByteArray()
    } finally {
      deflater.end()
    }
  }

  private fun assertMutation(
      baseline: ByteArray,
      reason: StateDecodeReason,
      mutation: (ByteArray) -> Unit,
  ) {
    val mutated = baseline.clone().also(mutation)
    assertReason(reason) { StateCodec.decode(mutated) }
  }

  private fun assertReason(reason: StateDecodeReason, block: () -> Unit) {
    assertEquals(reason, assertFailsWith<StateDecodeException>(block = block).reason)
  }

}
