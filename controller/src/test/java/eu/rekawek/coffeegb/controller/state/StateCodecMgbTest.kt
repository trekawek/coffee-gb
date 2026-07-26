package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class StateCodecMgbTest {

  @Test
  fun mgbMachineSessionAndDetachedLinkedRootsSelectV2() {
    val firstEndpoint = Peer2PeerSerialEndpoint()
    val secondEndpoint = Peer2PeerSerialEndpoint()
    firstEndpoint.init(secondEndpoint)
    val firstConfig = configuration(HardwareProfileRegistry.MGB)
    val secondConfig = configuration(HardwareProfileRegistry.MGB)
    StateCodecTestSupport.session(firstConfig, firstEndpoint).use { first ->
      StateCodecTestSupport.session(secondConfig, secondEndpoint).use { second ->
        repeat(1_024) {
          first.gameboy.tick()
          second.gameboy.tick()
        }

        val machine = StateCodec.capture(first.config, first.gameboy)
        val session = StateCodec.capture(first)
        val firstState = (session.root as SessionStateRoot).session
        val secondState = (StateCodec.capture(second).root as SessionStateRoot).session
        val linked =
            StateFile(
                listOf(
                    StateIdentityEntry(0, StateIdentity.from(first.config)),
                    StateIdentityEntry(1, StateIdentity.from(second.config)),
                    StateIdentityEntry(2, null),
                    StateIdentityEntry(3, null),
                ),
                LinkedSessionStateRoot(
                    LinkedSessionState(
                        0,
                        0,
                        LinkedTopologyState.NORMAL,
                        listOf(
                            LinkedPlayerState(0, firstState),
                            LinkedPlayerState(1, secondState),
                            LinkedPlayerState(2, null),
                            LinkedPlayerState(3, null),
                        ),
                    )),
            )

        listOf(machine, session, linked).forEach { file ->
          assertEquals(2, file.formatVersion, file.root.kind.name)
          val bytes = StateCodec.encode(file, StateCompression.DEFLATE)
          assertEquals(2, StateCodecTestSupport.readU16(bytes, 4), file.root.kind.name)
          assertEquals(file.root.kind, StateCodec.inspect(bytes).rootKind)
          assertEquals(file, StateCodec.decode(bytes))
          assertFailsWith<StateEncodeException> {
            StateCodec.encode(StateFile(file.identities, file.root, formatVersion = 1))
          }
        }
      }
    }
  }

  @Test
  fun mgbUsesExplicitV2IdentityAndContinuesDeterministically() {
    StateCodecTestSupport.session(configuration(HardwareProfileRegistry.MGB)).use { session ->
      repeat(9_123) { session.gameboy.tick() }
      val captured = StateCodec.capture(session)
      val bytes = StateCodec.encode(captured, StateCompression.DEFLATE)

      assertEquals(2, captured.formatVersion)
      assertEquals(2, StateCodecTestSupport.readU16(bytes, 4))
      assertEquals("mgb", StateCodec.inspect(bytes).identities.single().identity!!.profile.canonicalProfileId)
      assertTrue(StateCodec.inspect(bytes).render().contains("profile=mgb"))
      assertContentEquals(bytes, StateCodec.encode(StateCodec.decode(bytes), StateCompression.DEFLATE))

      repeat(7_777) { session.gameboy.tick() }
      val expected = StateCodec.encode(StateCodec.capture(session))
      StateCodec.decodeAndApply(bytes, session)
      repeat(7_777) { session.gameboy.tick() }
      assertContentEquals(expected, StateCodec.encode(StateCodec.capture(session)))

      val forcedV1 = StateFile(captured.identities, captured.root, captured.diagnostics, 1)
      assertFailsWith<StateEncodeException> { StateCodec.encode(forcedV1) }
    }
  }

  @Test
  fun dmgAndMgbRejectCrossProfileApplyBeforeMutationInBothDirections() {
    for ((sourceProfile, targetProfile) in
        listOf(
            HardwareProfileRegistry.MGB to HardwareProfileRegistry.DMG,
            HardwareProfileRegistry.DMG to HardwareProfileRegistry.MGB,
        )) {
      val bytes =
          StateCodecTestSupport.session(configuration(sourceProfile)).use { source ->
            repeat(1_337) { source.gameboy.tick() }
            StateCodec.encode(StateCodec.capture(source))
          }
      StateCodecTestSupport.session(configuration(targetProfile)).use { target ->
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
  }

  @Test
  fun mgbV2IdentityRejectsUnknownCaseAndCoarseFamilyConflictBeforeGraphDecode() {
    val baseline =
        StateCodecTestSupport.session(configuration(HardwareProfileRegistry.MGB)).use {
          StateCodec.encode(StateCodec.capture(it), StateCompression.NONE)
        }
    val sections = StateCodecTestSupport.sections(baseline)
    val identity = sections.single { it.id == 1 }
    val payload = sections.single { it.id == 2 }

    fun reject(body: ByteArray, expected: StateDecodeReason) {
      val failure =
          assertFailsWith<StateDecodeException> {
            StateCodec.decode(
                StateCodecTestSupport.rawFile(
                    StateRootKind.SESSION,
                    listOf(identity.copy(body = body), payload),
                    formatVersion = 2,
                ))
          }
      assertEquals(expected, failure.reason)
    }

    reject(identity.body.clone().also { it[52] = 'M'.code.toByte() },
        StateDecodeReason.MALFORMED_STRUCTURE)
    reject(identity.body.clone().also { "zzz".toByteArray().copyInto(it, 52) },
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH)
    reject(identity.body.clone().also { it[44] = 2.toByte() },
        StateDecodeReason.HARDWARE_PROFILE_MISMATCH)
  }

  @Test
  fun machineSnapshotRetainsExactMgbIdentityAndRejectsDmgBeforeMutation() {
    StateCodecTestSupport.session(configuration(HardwareProfileRegistry.MGB)).use { source ->
      repeat(4_321) { source.gameboy.tick() }
      val snapshot = MachineSnapshot.capture(source.gameboy)
      repeat(2_048) { source.gameboy.tick() }
      val expected = DetachedStateAdapter.capture(source.gameboy)
      snapshot.restore(source.gameboy)
      repeat(2_048) { source.gameboy.tick() }
      assertEquals(expected, DetachedStateAdapter.capture(source.gameboy))

      StateCodecTestSupport.session(configuration(HardwareProfileRegistry.DMG)).use { target ->
        val before = DetachedStateAdapter.capture(target.gameboy)
        val stages = mutableListOf<ApplyStage>()
        assertFailsWith<StateApplyException> { snapshot.restore(target.gameboy) { stages += it } }
        assertTrue(stages.isEmpty())
        assertEquals(before, DetachedStateAdapter.capture(target.gameboy))
      }
    }
  }

  private fun configuration(profile: HardwareProfile): Gameboy.GameboyConfiguration =
      StateCodecTestSupport.configuration(
              StateCodecTestSupport.rom(),
              GameboyType.DMG,
          )
          .setHardwareProfile(profile)
}
