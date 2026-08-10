package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.sgb.HardwareModelBaselineTest
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.memory.cart.Rom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SgbInputStateTest {

  @Test
  fun directPhysicalPrimaryInputIsEffectiveButExcludedFromDetachedSessionOwnership() {
    val input = PlayerInputHub()
    input.openSource(0).update(setOf(Button.A))
    session(input).use { session ->
      session.gameboy.tick()

      assertEquals(setOf(Button.A), session.gameboy.pressedButtons)
      assertTrue(session.heldButtons.isEmpty())
      val root = StateCodec.capture(session).root as SessionStateRoot
      assertTrue(root.session.heldButtons.isEmpty())
    }
  }

  @Test
  fun machineSnapshotAndStateFileRestoreMultiplexWhileKeepingCurrentFourSlotInput() {
    val input = PlayerInputHub()
    val playerTwo = input.openSource(1)
    playerTwo.update(setOf(Button.B))
    session(input).use { session ->
      session.gameboy.tick() // latch the first immutable four-slot sample
      sendMltReq(session, 1)
      selectNext(session)
      assertEquals(1, session.gameboy.sgbMultiplayerStatus.selectedPlayer)
      assertSelectedButtons(session, 0x0d)

      val machineSnapshot = MachineSnapshot.capture(session.gameboy)
      val portable = StateCodec.encode(StateCodec.capture(session))

      playerTwo.update(setOf(Button.START))
      // The desktop PlayerInputHub is sampled at its bounded 64-master-tick cadence.
      repeat(64) { session.gameboy.tick() }
      sendMltReq(session, 0)
      assertEquals(0, session.gameboy.sgbMultiplayerStatus.selectedPlayer)

      machineSnapshot.restore(session.gameboy)
      assertEquals(Joypad.SgbMultiplayerMode.TWO_PLAYER,
          session.gameboy.sgbMultiplayerStatus.mode)
      assertEquals(1, session.gameboy.sgbMultiplayerStatus.selectedPlayer)
      assertSelectedButtons(session, 0x07)

      sendMltReq(session, 0)
      StateCodec.decodeAndApply(portable, session)
      assertEquals(1, session.gameboy.sgbMultiplayerStatus.selectedPlayer)
      assertSelectedButtons(session, 0x07)
      assertEquals(portable.toList(), StateCodec.encode(StateCodec.capture(session)).toList())
    }
  }

  @Test
  fun invalidMultiplexCombinationsRejectBeforeLiveMutation() {
    session(PlayerInputHub()).use { session ->
      val before = session.captureDetachedState()
      val invalid =
          listOf(
              4 to 0,
              0 to 1,
              1 to 2,
              2 to 1,
              3 to 4,
          )
      invalid.forEach { (control, player) ->
        val candidate =
            before.withMachineRoot(
                before.machine.root
                    .replaceRecordField(JOYPAD_STATE, "players", Int32State(control))
                    .replaceRecordField(JOYPAD_STATE, "currentPlayer", Int32State(player)))
        val stages = mutableListOf<ApplyStage>()
        assertFailsWith<StateApplyException>("control=$control player=$player") {
          DetachedStateAdapter.apply(session, candidate) { stages += it }
        }
        assertTrue(stages.isEmpty(), "invalid multiplex state reached live mutation")
        assertEquals(before, session.captureDetachedState())
      }
    }
  }

  private fun session(input: PlayerInputHub): Session {
    val configuration =
        Gameboy.GameboyConfiguration(Rom(HardwareModelBaselineTest.syntheticRom()))
            .setGameboyType(GameboyType.SGB)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
            .setPlayerInputSource(input)
    return Session(configuration, EventBusImpl(null, null, false), null)
  }

  private fun sendMltReq(session: Session, control: Int) {
    val packet = IntArray(16)
    packet[0] = (0x11 shl 3) or 1
    packet[1] = control
    sendPacket(session, packet)
  }

  private fun sendPacket(session: Session, packet: IntArray) {
    val joyp = session.gameboy.addressSpace
    joyp.setByte(0xff00, 0x30)
    joyp.setByte(0xff00, 0)
    joyp.setByte(0xff00, 0x30)
    repeat(128) { bit ->
      val one = packet[bit / 8] ushr (bit and 7) and 1
      joyp.setByte(0xff00, if (one == 0) 0x20 else 0x10)
      joyp.setByte(0xff00, 0x30)
    }
    joyp.setByte(0xff00, 0x20)
    joyp.setByte(0xff00, 0x30)
  }

  private fun selectNext(session: Session) {
    session.gameboy.addressSpace.setByte(0xff00, 0x10)
    session.gameboy.addressSpace.setByte(0xff00, 0x30)
  }

  private fun assertSelectedButtons(session: Session, expected: Int) {
    session.gameboy.addressSpace.setByte(0xff00, 0x10)
    assertEquals(expected, session.gameboy.addressSpace.getByte(0xff00) and 0x0f)
  }

  private fun SessionState.withMachineRoot(root: RecordState): SessionState =
      SessionState(
          MachineState(root, machine.rtcRuntime, machine.hardware, machine.dmgFifoRuntime),
          serialPeripheral,
          serialState,
          serialRuntime,
          heldButtons,
      )

  private fun RecordState.replaceRecordField(
      ownerClass: String,
      fieldName: String,
      replacement: StateValue,
  ): RecordState {
    fun replace(value: StateValue): StateValue =
        when (value) {
          is RecordState -> {
            val owner = StateTypeRegistry.recordClasses[value.typeId - 1].name == ownerClass
            RecordState(
                value.typeId,
                value.fields.map { field ->
                  StateField(
                      field.name,
                      if (owner && field.name == fieldName) replacement else replace(field.value),
                  )
                },
            )
          }
          is ObjectArrayState -> ObjectArrayState(value.values.map(::replace))
          is ListState -> ListState(value.values.map(::replace))
          is Int32MapState ->
              Int32MapState(value.entries.map { Int32MapEntry(it.key, replace(it.value)) })
          else -> value
        }
    return replace(this) as RecordState
  }

  companion object {
    private const val JOYPAD_STATE = "eu.rekawek.coffeegb.core.joypad.Joypad\$JoypadState"
  }
}
