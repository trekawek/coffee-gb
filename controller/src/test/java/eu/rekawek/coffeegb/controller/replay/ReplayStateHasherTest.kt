package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata
import eu.rekawek.coffeegb.controller.state.StateField
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.Gameboy.BootstrapOutcome
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class ReplayStateHasherTest {

  @Test
  fun fullHashIsCanonicalAndDiagnosticFree() {
    StateCodecTestSupport.session().use { session ->
      repeat(31) { session.gameboy.tick() }
      val captured = StateCodec.captureVersion2(session)
      val withDiagnostics =
          StateFile(
              captured.identities,
              captured.root,
              StateDiagnosticMetadata("private-core", "private-build"),
              StateCodec.LATEST_FORMAT_VERSION,
          )
      val withoutDiagnostics =
          StateFile(
              captured.identities,
              captured.root,
              diagnostics = null,
              formatVersion = StateCodec.LATEST_FORMAT_VERSION,
          )

      assertEquals(ReplayStateHasher.hash(captured), ReplayStateHasher.hash(withoutDiagnostics))
      assertEquals(ReplayStateHasher.hash(captured), ReplayStateHasher.hash(withDiagnostics))
      assertEquals(ReplayStateHasher.hash(session), ReplayStateHasher.hash(session))
    }
  }

  @Test
  fun bootstrapOutcomeSidecarDoesNotChangeReplayHashes() {
    StateCodecTestSupport.session().use { session ->
      repeat(31) { session.gameboy.tick() }
      val captured = StateCodec.captureVersion2(session)
      val hashes =
          listOf<BootstrapOutcome?>(null, *BootstrapOutcome.entries.toTypedArray())
              .map { ReplayStateHasher.hash(withBootstrapOutcome(captured, it)) }

      hashes.drop(1).forEach { actual ->
        assertHashesEqual(hashes.first(), actual)
      }
    }
  }

  @Test
  fun sampledPhysicalMasksParticipateInFullAndInputHashes() {
    val inputA = PlayerInputHub()
    val inputB = PlayerInputHub()
    inputA.openSource(0).use { sourceA ->
      inputB.openSource(0).use { sourceB ->
        sourceA.update(setOf(Button.A))
        sourceB.update(setOf(Button.B))
        val configurationA = StateCodecTestSupport.configuration().setPlayerInputSource(inputA)
        val configurationB = StateCodecTestSupport.configuration().setPlayerInputSource(inputB)

        StateCodecTestSupport.session(configurationA).use { sessionA ->
          StateCodecTestSupport.session(configurationB).use { sessionB ->
            sessionA.gameboy.tick()
            sessionB.gameboy.tick()
            val hashA = ReplayStateHasher.hash(sessionA)
            val hashB = ReplayStateHasher.hash(sessionB)

            assertDigestDiffers(hashA.full, hashB.full)
            assertDigestDiffers(hashA.input, hashB.input)
            assertContentEquals(hashA.cpu, hashB.cpu)
            assertContentEquals(hashA.memory, hashB.memory)
            assertContentEquals(hashA.ppu, hashB.ppu)
            assertContentEquals(hashA.apu, hashB.apu)
            assertContentEquals(hashA.mapper, hashB.mapper)
            assertContentEquals(hashA.serial, hashB.serial)
          }
        }
      }
    }
  }

  @Test
  fun heldButtonTransitionChangesOnlyTheInputSubsystemHash() {
    StateCodecTestSupport.session().use { session ->
      val before = ReplayStateHasher.hash(session)

      session.heldButtons = setOf(Button.A)
      val after = ReplayStateHasher.hash(session)

      assertDigestDiffers(before.full, after.full)
      assertContentEquals(before.cpu, after.cpu)
      assertContentEquals(before.memory, after.memory)
      assertContentEquals(before.ppu, after.ppu)
      assertContentEquals(before.apu, after.apu)
      assertContentEquals(before.mapper, after.mapper)
      assertContentEquals(before.serial, after.serial)
      assertDigestDiffers(before.input, after.input)
    }
  }

  @Test
  fun cpuRootScalarMutationIsLocalizedToTheCpuDigest() {
    StateCodecTestSupport.session().use { session ->
      val file = StateCodec.captureVersion2(session)
      val changed = replaceRootInt(file, "speedSwitchTailTicks") { if (it == 0) 1 else 0 }
      val before = ReplayStateHasher.hash(file)
      val after = ReplayStateHasher.hash(changed)

      assertDigestDiffers(before.full, after.full)
      assertDigestDiffers(before.cpu, after.cpu)
      assertContentEquals(before.memory, after.memory)
      assertContentEquals(before.ppu, after.ppu)
      assertContentEquals(before.apu, after.apu)
      assertContentEquals(before.mapper, after.mapper)
      assertContentEquals(before.serial, after.serial)
      assertContentEquals(before.input, after.input)
    }
  }

  private fun replaceRootInt(
      file: StateFile,
      name: String,
      replacement: (Int) -> Int,
  ): StateFile {
    val session = (file.root as SessionStateRoot).session
    val machine = session.machine
    val root = machine.root
    val fields =
        root.fields.map { field ->
          if (field.name != name) field
          else {
            val current = (field.value as Int32State).value
            StateField(name, Int32State(replacement(current)))
          }
        }
    val changedMachine =
        MachineState(
            RecordState(root.typeId, fields),
            machine.rtcRuntime,
            machine.hardware,
            machine.dmgFifoRuntime,
        )
    val changedSession =
        SessionState(
            changedMachine,
            session.serialPeripheral,
            session.serialState,
            session.serialRuntime,
            session.heldButtons,
        )
    return StateFile(
        file.identities,
        SessionStateRoot(changedSession),
        diagnostics = null,
        formatVersion = StateCodec.LATEST_FORMAT_VERSION,
    )
  }

  private fun withBootstrapOutcome(file: StateFile, outcome: BootstrapOutcome?): StateFile {
    val session = (file.root as SessionStateRoot).session
    val machine = session.machine
    val changedMachine =
        MachineState(
            machine.root,
            machine.rtcRuntime,
            machine.hardware,
            machine.dmgFifoRuntime,
            outcome,
        )
    return StateFile(
        file.identities,
        SessionStateRoot(
            SessionState(
                changedMachine,
                session.serialPeripheral,
                session.serialState,
                session.serialRuntime,
                session.heldButtons,
            )),
        file.diagnostics,
        file.formatVersion,
    )
  }

  private fun assertHashesEqual(expected: ReplayStateHashes, actual: ReplayStateHashes) {
    assertContentEquals(expected.full, actual.full)
    assertContentEquals(expected.cpu, actual.cpu)
    assertContentEquals(expected.memory, actual.memory)
    assertContentEquals(expected.ppu, actual.ppu)
    assertContentEquals(expected.apu, actual.apu)
    assertContentEquals(expected.mapper, actual.mapper)
    assertContentEquals(expected.serial, actual.serial)
    assertContentEquals(expected.input, actual.input)
  }

  private fun assertDigestDiffers(left: ByteArray, right: ByteArray) {
    assertNotEquals(left.toList(), right.toList())
  }
}
