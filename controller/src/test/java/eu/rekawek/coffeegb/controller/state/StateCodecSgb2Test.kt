package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class StateCodecSgb2Test {

  @Test
  fun sgb2UsesExplicitV2IdentityAndContinuesDeterministically() {
    val configuration = sgb2Configuration()
    StateCodecTestSupport.session(configuration).use { session ->
      repeat(9_123) { session.gameboy.tick() }
      val snapshot = StateCodec.encode(StateCodec.capture(session), StateCompression.DEFLATE)
      assertEquals(2, StateCodecTestSupport.readU16(snapshot, 4))
      val inspection = StateCodec.inspect(snapshot)
      assertEquals(2, inspection.formatVersion)
      assertEquals("sgb2", inspection.identities.single().identity!!.profile.canonicalProfileId)
      assertTrue(inspection.render().contains("profile=sgb2"))
      assertContentEquals(snapshot, StateCodec.encode(StateCodec.decode(snapshot), StateCompression.DEFLATE))

      repeat(7_777) { session.gameboy.tick() }
      val expected = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(snapshot, session)
      repeat(7_777) { session.gameboy.tick() }
      assertContentEquals(expected, StateCodec.encode(StateCodec.capture(session)))
    }
  }

  @Test
  fun sgbAndSgb2MismatchRejectsBeforeAnyLiveMutation() {
    val bytes =
        StateCodecTestSupport.session(sgb2Configuration()).use { source ->
          repeat(1_337) { source.gameboy.tick() }
          StateCodec.encode(StateCodec.capture(source))
        }
    val sgb =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
    StateCodecTestSupport.session(sgb).use { target ->
      val before = StateCodec.encode(StateCodec.capture(target))
      val stages = mutableListOf<ApplyStage>()
      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decodeAndApply(bytes, target) { stages += it }
          }
      assertEquals(StateDecodeReason.HARDWARE_PROFILE_MISMATCH, failure.reason)
      assertTrue(stages.isEmpty())
      assertContentEquals(before, StateCodec.encode(StateCodec.capture(target)))
    }
  }

  @Test
  fun v2ProfileIdRejectsCaseUnknownLengthTruncationAndChecksumCorruption() {
    val baseline =
        StateCodecTestSupport.session(sgb2Configuration()).use {
          StateCodec.encode(StateCodec.capture(it), StateCompression.NONE)
        }
    val sections = StateCodecTestSupport.sections(baseline)
    val identity = sections.single { it.id == 1 }
    val payload = sections.single { it.id == 2 }

    fun mutateIdentity(body: ByteArray, reason: StateDecodeReason) {
      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decode(
                StateCodecTestSupport.rawFile(
                    StateRootKind.SESSION,
                    listOf(identity.copy(body = body), payload),
                    formatVersion = 2,
                ))
          }
      assertEquals(reason, failure.reason)
    }

    mutateIdentity(identity.body.clone().also { it[52] = 'S'.code.toByte() },
        StateDecodeReason.MALFORMED_STRUCTURE)
    mutateIdentity(identity.body.clone().also { "sgb3".toByteArray().copyInto(it, 52) },
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH)
    mutateIdentity(identity.body.clone().also {
      StateCodecTestSupport.writeU16(it, 50, eu.rekawek.coffeegb.controller.StateLimits.PORTABLE_MAX_PROFILE_ID_BYTES + 1)
    }, StateDecodeReason.LIMIT_EXCEEDED)
    mutateIdentity(identity.body.copyOf(identity.body.size - 1), StateDecodeReason.TRUNCATED)

    val corrupt = baseline.clone().also { it[36] = (it[36].toInt() xor 1).toByte() }
    assertEquals(
        StateDecodeReason.CORRUPT_CHECKSUM,
        assertFailsWith<StateDecodeException> { StateCodec.decode(corrupt) }.reason,
    )
  }

  @Test
  fun releasedV1SgbIdentityAlwaysMeansSgb() {
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val bytes = StateCodec.encode(StateCodec.capture(session))
      assertEquals(1, StateCodecTestSupport.readU16(bytes, 4))
      assertEquals("sgb", StateCodec.decode(bytes).identities.single().identity!!.profile.canonicalProfileId)
    }
  }

  private fun sgb2Configuration(): Gameboy.GameboyConfiguration =
      StateCodecTestSupport.configuration(StateCodecTestSupport.rom(sgb = true), GameboyType.SGB)
          .setHardwareProfile(HardwareProfileRegistry.SGB2)
}
