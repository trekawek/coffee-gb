package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class ReplayCodecTest {
  @Test
  fun bootReferenceRoundTripIsCanonicalAndStructural() {
    val replay = ReplayTestFixture.file()

    val first = ReplayCodec.encode(replay)
    val second = ReplayCodec.encode(replay)
    val decoded = ReplayCodec.decode(first)

    assertContentEquals(first, second)
    assertEquals(replay, decoded)
    assertContentEquals(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte()), first.copyOf(4))
    assertEquals(ReplayCodec.HEADER_SIZE, ReplayTestFixture.readU16(first, 6))
    assertEquals(
        (first.size - ReplayCodec.HEADER_SIZE).toLong(),
        ReplayTestFixture.readLong(first, 32),
    )
    assertEquals(listOf(1, 2, 3, 4, 5), ReplayCodec.inspect(first).sections.map { it.id })
    assertEquals(
        ReplaySectionCompression.DEFLATE,
        ReplayCodec.inspect(first).sections.single { it.id == ReplayCodec.CHECKPOINT_SECTION_ID }.compression,
    )
  }

  @Test
  fun byteBearingModelsOwnInputsAndUseContentEquality() {
    val primary = ReplayTestFixture.digest(1)
    val slot = ReplayTestFixture.digest(2)
    val full = ReplayTestFixture.digest(3)
    val identity = ReplayTestFixture.identity(primary, slot)
    val hashes = ReplayTestFixture.hashes(full)
    val replay =
        ReplayFile(
            identity,
            ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 1234),
            emptyList(),
            listOf(ReplayCheckpoint(0, 0, hashes)),
        )
    val encoded = ReplayCodec.encode(replay)

    primary.fill(0)
    slot.fill(0)
    full.fill(0)
    identity.primaryRomSha256.fill(0)
    hashes.full.fill(0)

    assertEquals(ReplayTestFixture.identity(ReplayTestFixture.digest(1), ReplayTestFixture.digest(2)), identity)
    assertContentEquals(ReplayTestFixture.digest(3), hashes.full)
    assertEquals(replay, ReplayCodec.decode(encoded))
  }

  @Test
  fun embeddedModeCarriesOneBoundedSessionStateFileV2WithoutOuterCompression() {
    val state = ReplayTestFixture.embeddedSessionState()
    val replay =
        ReplayTestFixture.file(
            initial =
                ReplayInitialConditions(
                    ReplayInitialMode.EMBEDDED_SESSION_STATE,
                    rtcEpochMillis = 987_654_321,
                    initialTick = 100,
                    initialFrame = 2,
                ),
            inputs = listOf(ReplayInputRecord(101, ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE, 0, 1, 1)),
            checkpoints = listOf(ReplayCheckpoint(200, 3, ReplayTestFixture.hashes())),
            embeddedState = state,
        )

    val encoded = ReplayCodec.encode(replay)
    val decoded = ReplayCodec.decode(encoded)
    val embeddedSection =
        ReplayCodec.inspect(encoded).sections.single { it.id == ReplayCodec.EMBEDDED_STATE_SECTION_ID }

    assertEquals(replay, decoded)
    assertContentEquals(state, decoded.embeddedState)
    assertEquals(ReplaySectionCompression.NONE, embeddedSection.compression)
    assertEquals(state.size.toLong(), embeddedSection.encodedLength)
  }

  @Test
  fun modelRejectsNonCanonicalClockAndTerminalRanges() {
    assertFailsWith<IllegalArgumentException> { ReplayClockRatio(4, 2) }
    assertFailsWith<IllegalArgumentException> {
      ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 0, initialTick = 1)
    }
    assertFailsWith<IllegalArgumentException> {
      ReplayFile(
          ReplayTestFixture.identity(),
          ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 0),
          emptyList(),
          emptyList(),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReplayFile(
          ReplayTestFixture.identity(),
          ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 0),
          listOf(ReplayInputRecord(5, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 1, 1)),
          listOf(ReplayCheckpoint(4, 0, ReplayTestFixture.hashes())),
      )
    }
  }

  @Test
  fun sameTickLegacyInputOrderIsPreserved() {
    val records =
        listOf(
            ReplayInputRecord(9, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 1, 1),
            ReplayInputRecord(9, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 3, 2),
            ReplayInputRecord(9, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 2, 1),
        )
    val decoded =
        ReplayCodec.decode(
            ReplayCodec.encode(
                ReplayTestFixture.file(
                    inputs = records,
                    checkpoints = listOf(ReplayCheckpoint(9, 0, ReplayTestFixture.hashes())),
                )))

    assertEquals(records, decoded.inputs)
    assertNotEquals(records.reversed(), decoded.inputs)
    assertTrue(decoded.checkpoints.last().tick >= decoded.inputs.last().tick)
  }

  @Test
  fun metadataAcceptsValidNonBmpUnicodeButRejectsUnpairedSurrogates() {
    val metadata = ReplayMetadata(producerVersion = "coffee \uD83D\uDE00", note = "valid \uD83D\uDE80")
    val replay = ReplayTestFixture.file()
    val withUnicode =
        ReplayFile(
            replay.identity,
            replay.initialConditions,
            replay.inputs,
            replay.checkpoints,
            metadata,
        )

    assertEquals(metadata, ReplayCodec.decode(ReplayCodec.encode(withUnicode)).metadata)
    assertFailsWith<IllegalArgumentException> { ReplayMetadata(note = "broken \uD800") }
  }
}

internal object ReplayTestFixture {
  fun digest(seed: Int = 0): ByteArray =
      ByteArray(ReplayLimits.SHA256_BYTES) { index -> (seed + index).toByte() }

  fun identity(
      primary: ByteArray = digest(1),
      slot: ByteArray? = null,
  ): ReplayIdentity =
      ReplayIdentity(
          primary,
          slot,
          "dmg",
          ReplayClockIdentity(
              ReplayClockRatio(4_194_304, 1),
              ReplayClockRatio(60, 1),
          ),
          bootstrapFlags = 4,
          behaviorFlags = 9,
      )

  fun hashes(full: ByteArray = digest(10)): ReplayStateHashes =
      ReplayStateHashes(
          full,
          digest(11),
          digest(12),
          digest(13),
          digest(14),
          digest(15),
          digest(16),
          digest(17),
      )

  fun file(
      initial: ReplayInitialConditions =
          ReplayInitialConditions(ReplayInitialMode.BOOT_REFERENCE, 1_700_000_000_000),
      inputs: List<ReplayInputRecord> =
          listOf(
              ReplayInputRecord(2, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 1, 1),
              ReplayInputRecord(2, ReplayInputPhase.LEGACY_P1_BEFORE_TICK, 0, 3, 2),
              ReplayInputRecord(3, ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE, 1, 0x80, 0x80),
          ),
      checkpoints: List<ReplayCheckpoint> =
          listOf(
              ReplayCheckpoint(1, 0, hashes()),
              ReplayCheckpoint(5, 1, hashes()),
          ),
      embeddedState: ByteArray? = null,
  ): ReplayFile =
      ReplayFile(
          identity(),
          initial,
          inputs,
          checkpoints,
          ReplayMetadata("coffee-gb-test", 1_700_000_123_456, "deterministic fixture"),
          embeddedState,
      )

  fun embeddedSessionState(): ByteArray =
      StateCodecTestSupport.session().use {
        StateCodec.encode(StateCodec.captureVersion2(it), StateCompression.DEFLATE)
      }

  fun withChecksum(bytes: ByteArray): ByteArray =
      bytes.clone().also { result ->
        val digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(result.copyOfRange(ReplayCodec.HEADER_SIZE, result.size))
        digest.copyInto(result, 40)
      }

  fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
  }

  fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
  }

  fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0..7) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
  }

  fun readU16(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

  fun readInt(bytes: ByteArray, offset: Int): Int =
      (0..3).fold(0) { result, index ->
        (result shl 8) or (bytes[offset + index].toInt() and 0xff)
      }

  fun readLong(bytes: ByteArray, offset: Int): Long =
      (0..7).fold(0L) { result, index ->
        (result shl 8) or (bytes[offset + index].toLong() and 0xff)
      }

  fun sectionOffsets(bytes: ByteArray): List<Int> {
    val count = readInt(bytes, 24)
    var offset = ReplayCodec.HEADER_SIZE
    return List(count) {
      val result = offset
      val encodedLength = readLong(bytes, offset + 8)
      require(encodedLength in 0..Int.MAX_VALUE.toLong())
      offset += ReplayCodec.SECTION_HEADER_SIZE + encodedLength.toInt()
      result
    }
  }
}
