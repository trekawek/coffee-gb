package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.state.BooleanState
import eu.rekawek.coffeegb.controller.state.Int32MapEntry
import eu.rekawek.coffeegb.controller.state.Int32MapState
import eu.rekawek.coffeegb.controller.state.Int32State
import eu.rekawek.coffeegb.controller.state.ListState
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.ObjectArrayState
import eu.rekawek.coffeegb.controller.state.RecordState
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata
import eu.rekawek.coffeegb.controller.state.StateField
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateValue
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
  fun legacyReplayProjectionDropsOnlyFetcherCoordinates() {
    StateCodecTestSupport.session().use { session ->
      repeat(31) { session.gameboy.tick() }
      val original = StateCodec.captureVersion2(session)
      val originalBytes =
          StateCodec.encode(original, StateCompression.NONE)
      val changed = replaceFetcherCoordinates(original)

      val legacyBefore =
          ReplayStateHasher.hash(original, ReplayIdentity.LEGACY_REPLAY_SEMANTICS_VERSION)
      val legacyAfter =
          ReplayStateHasher.hash(changed, ReplayIdentity.LEGACY_REPLAY_SEMANTICS_VERSION)
      assertEquals(legacyBefore, legacyAfter)
      assertContentEquals(
          originalBytes,
          StateCodec.encode(original, StateCompression.NONE),
      )

      val legacyCpuChanged =
          replaceRootInt(original, "speedSwitchTailTicks") { if (it == 0) 1 else 0 }
      val legacyCpuAfter =
          ReplayStateHasher.hash(legacyCpuChanged, ReplayIdentity.LEGACY_REPLAY_SEMANTICS_VERSION)
      assertDigestDiffers(legacyBefore.full, legacyCpuAfter.full)
      assertDigestDiffers(legacyBefore.cpu, legacyCpuAfter.cpu)
      assertContentEquals(legacyBefore.ppu, legacyCpuAfter.ppu)

      val currentBefore = ReplayStateHasher.hash(original, ReplayIdentity.REPLAY_SEMANTICS_VERSION)
      val currentAfter = ReplayStateHasher.hash(changed, ReplayIdentity.REPLAY_SEMANTICS_VERSION)
      assertDigestDiffers(currentBefore.full, currentAfter.full)
      assertDigestDiffers(currentBefore.ppu, currentAfter.ppu)
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

  private fun replaceFetcherCoordinates(file: StateFile): StateFile {
    val session = (file.root as SessionStateRoot).session
    val machine = session.machine
    val (mutatedRoot, changed) = mutateFetcher(machine.root)
    assertTrue(changed, "The captured state must contain a FetcherState")
    val changedMachine =
        MachineState(
            mutatedRoot as RecordState,
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

  private fun mutateFetcher(value: StateValue): Pair<StateValue, Boolean> {
    when (value) {
      is RecordState -> {
        if (value.fields.size >= 3 &&
            value.fields.takeLast(3).map(StateField::name) ==
                listOf("tileMapX", "xBasePosition", "xBaseObjectFetch")) {
          val fields =
              value.fields.dropLast(3) +
                  listOf(
                      StateField("tileMapX", Int32State(7)),
                      StateField("xBasePosition", Int32State(11)),
                      StateField("xBaseObjectFetch", BooleanState(true)),
                  )
          return RecordState(value.typeId, fields) to true
        }
        value.fields.forEachIndexed { index, field ->
          val (child, changed) = mutateFetcher(field.value)
          if (changed) {
            return RecordState(
                value.typeId,
                value.fields.mapIndexed { fieldIndex, current ->
                  if (fieldIndex == index) StateField(current.name, child) else current
                },
            ) to true
          }
        }
        return value to false
      }
      is ObjectArrayState -> {
        value.values.forEachIndexed { index, childValue ->
          val (child, changed) = mutateFetcher(childValue)
          if (changed) {
            return ObjectArrayState(
                value.values.mapIndexed { valueIndex, current ->
                  if (valueIndex == index) child else current
                },
            ) to true
          }
        }
        return value to false
      }
      is ListState -> {
        value.values.forEachIndexed { index, childValue ->
          val (child, changed) = mutateFetcher(childValue)
          if (changed) {
            return ListState(
                value.values.mapIndexed { valueIndex, current ->
                  if (valueIndex == index) child else current
                },
            ) to true
          }
        }
        return value to false
      }
      is Int32MapState -> {
        value.entries.forEachIndexed { index, entry ->
          val (child, changed) = mutateFetcher(entry.value)
          if (changed) {
            return Int32MapState(
                value.entries.mapIndexed { entryIndex, current ->
                  if (entryIndex == index) Int32MapEntry(current.key, child) else current
                },
            ) to true
          }
        }
        return value to false
      }
      else -> return value to false
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

  private fun assertDigestDiffers(left: ByteArray, right: ByteArray) {
    assertNotEquals(left.toList(), right.toList())
  }
}
