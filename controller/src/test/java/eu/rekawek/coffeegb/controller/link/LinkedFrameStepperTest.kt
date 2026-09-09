package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LinkedFrameStepperTest {

  @Test
  fun mirroredCgbFastInternalMastersStopWhenOnePeerBecomesTheListener() {
    val electionRom =
        StateCodecTestSupport.rom(cgb = true).also { bytes ->
          // LD A,$82; LDH ($02),A; LD A,$01; LDH ($80),A; JR -2
          // The marker distinguishes the listener transition from an oversized fixed lead.
          byteArrayOf(
                  0x3e,
                  0x82.toByte(),
                  0xe0.toByte(),
                  0x02,
                  0x3e,
                  0x01,
                  0xe0.toByte(),
                  0x80.toByte(),
                  0x18,
                  0xfe.toByte(),
              )
              .copyInto(bytes, destinationOffset = 0x100)
        }
    PairFixture(GameboyType.CGB, electionRom).use { fixture ->
      fixture.armBoth(0x83)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.INTERNAL_CLOCK_COLLISION,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
      assertTrue(fixture.first.gameboy.isInternalClockTransferActive)
      assertTrue(fixture.second.gameboy.isExternalClockTransferActive)
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
      assertTrue(dividerDelta(fixture.second, fixture.first) > 0)
      assertEquals(0, fixture.second.gameboy.addressSpace.getByte(0xff80))
    }
  }

  @Test
  fun mirroredInternalMastersReceiveOneDeterministicP2PhaseEscape() {
    PairFixture().use { fixture ->
      fixture.armBoth(0x81)
      assertTrue(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.INTERNAL_CLOCK_COLLISION,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      val expectedLead = 13L * fixture.clockSpec.controllerTicksPerFrame()
      assertEquals(
          expectedLead.toInt() and 0xffff,
          dividerDelta(fixture.second, fixture.first),
      )
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
      assertNull(
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
          "the completed contender no longer conflicts with the remaining master",
      )
    }
  }

  @Test
  fun mirroredDmgExternalListenersReceiveABoundedP1ElectionLead() {
    PairFixture().use { fixture ->
      fixture.armBoth(0x80)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      val expectedLead = 2L * fixture.clockSpec.controllerTicksPerFrame()
      assertEquals(
          expectedLead.toInt() and 0xffff,
          dividerDelta(fixture.first, fixture.second),
      )
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
    }
  }

  @Test
  fun mirroredCgbNormalExternalListenersKeepTheBoundedP1ElectionLead() {
    PairFixture(GameboyType.CGB).use { fixture ->
      fixture.armBoth(0x80)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      val expectedLead = 2L * fixture.clockSpec.controllerTicksPerFrame()
      assertEquals(
          expectedLead.toInt() and 0xffff,
          dividerDelta(fixture.first, fixture.second),
      )
      assertFalse(fixture.first.gameboy.isFastSerialClockSelectedForActiveTransfer)
      assertFalse(fixture.second.gameboy.isFastSerialClockSelectedForActiveTransfer)
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
    }
  }

  @Test
  fun mirroredCgbFastExternalListenersReceiveOnlyASingleTickP1PhaseLead() {
    PairFixture(GameboyType.CGB).use { fixture ->
      fixture.armBoth(0x82)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      assertEquals(1, dividerDelta(fixture.first, fixture.second))
      assertTrue(fixture.first.gameboy.isExternalClockTransferActive)
      assertTrue(fixture.second.gameboy.isExternalClockTransferActive)
      assertTrue(fixture.first.gameboy.isFastSerialClockSelectedForActiveTransfer)
      assertTrue(fixture.second.gameboy.isFastSerialClockSelectedForActiveTransfer)
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
    }
  }

  @Test
  fun mirroredCgbFastInputCanStartANewElectionAfterThePassivePhaseLead() {
    PairFixture(GameboyType.CGB).use { fixture ->
      fixture.armBoth(0x82)
      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))

      fixture.first.heldButtons = setOf(Button.A)
      fixture.second.heldButtons = setOf(Button.A)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      val expectedLead = 1L + 2L * fixture.clockSpec.controllerTicksPerFrame()
      assertEquals(expectedLead.toInt() and 0xffff, dividerDelta(fixture.first, fixture.second))
    }
  }

  @Test
  fun unequalFastInputDoesNotBypassTheTimingPhaseGuard() {
    PairFixture(GameboyType.CGB).use { fixture ->
      fixture.armBoth(0x82)
      assertEquals(
          LinkedFrameStepper.SymmetryBreak.EXTERNAL_CLOCK_DEADLOCK,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
      fixture.first.heldButtons = setOf(Button.A)
      fixture.second.heldButtons = setOf(Button.B)

      assertNull(
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
    }
  }

  @Test
  fun matchingInternalRequestsResolveAfterTheirMachinePhasesHaveDiverged() {
    PairFixture().use { fixture ->
      // A passive startup already separated the machines. The user has released the menu
      // confirmation by the time its delayed handshake begins, so no held input identifies it.
      fixture.armBoth(0x80)
      LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec)
      fixture.armBoth(0x81)
      assertFalse(fixture.first.gameboy.hasSameLinkTimingPhase(fixture.second.gameboy))
      assertTrue(fixture.first.heldButtons.isEmpty())
      assertTrue(fixture.second.heldButtons.isEmpty())
      val before = dividerDelta(fixture.second, fixture.first)

      assertEquals(
          LinkedFrameStepper.SymmetryBreak.INTERNAL_CLOCK_COLLISION,
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )

      val expectedLead = 13L * fixture.clockSpec.controllerTicksPerFrame()
      assertEquals(
          (before + expectedLead.toInt()) and 0xffff,
          dividerDelta(fixture.second, fixture.first),
      )
      assertNull(LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec))
    }
  }

  @Test
  fun unequalPassivePhasesOrDifferentOutgoingBytesDoNotTriggerAnEscape() {
    PairFixture().use { fixture ->
      fixture.first.gameboy.tick()
      fixture.armBoth(0x80)
      assertNull(
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
    }

    PairFixture().use { fixture ->
      fixture.armBoth(0x81)
      fixture.second.gameboy.addressSpace.setByte(0xff01, 0x34)
      assertNull(
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
    }

    PairFixture().use { fixture ->
      fixture.armBoth(0x81)
      fixture.firstEndpoint.sendBit()
      assertNull(
          LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec),
      )
    }
  }

  @Test
  fun masterAndListenerKeepTheirExistingSchedule() {
    PairFixture().use { fixture ->
      fixture.armBoth(0x81)
      fixture.second.gameboy.addressSpace.setByte(0xff02, 0x80)
      val before = dividerDelta(fixture.second, fixture.first)
      assertNull(LinkedFrameStepper.breakMirroredRoleElection(fixture.sessions, fixture.clockSpec))
      assertEquals(before, dividerDelta(fixture.second, fixture.first))
    }
  }

  private fun dividerDelta(ahead: Session, behind: Session): Int {
    val aheadDivider = ahead.gameboy.captureDebugSnapshot(0, 0, 0, 0, 0, false).timer.dividerCounter
    val behindDivider =
        behind.gameboy.captureDebugSnapshot(0, 0, 0, 0, 0, false).timer.dividerCounter
    return (aheadDivider - behindDivider) and 0xffff
  }

  private class PairFixture(
      hardware: GameboyType = GameboyType.DMG,
      rom: ByteArray = StateCodecTestSupport.rom(cgb = hardware == GameboyType.CGB),
  ) : AutoCloseable {
    val firstEndpoint = Peer2PeerSerialEndpoint()
    val secondEndpoint = Peer2PeerSerialEndpoint()
    val first: Session
    val second: Session
    val sessions: List<Session?>
    val clockSpec: ClockSpec

    init {
      firstEndpoint.init(secondEndpoint)
      val firstConfig =
          StateCodecTestSupport.configuration(
              bytes = rom.clone(),
              hardware = hardware,
          )
      val secondConfig =
          StateCodecTestSupport.configuration(
              bytes = rom.clone(),
              hardware = hardware,
          )
      first = Session(firstConfig, EventBusImpl(null, null, false), null, firstEndpoint)
      second = Session(secondConfig, EventBusImpl(null, null, false), null, secondEndpoint)
      sessions = listOf(first, second)
      clockSpec = first.gameboy.clockSpec
    }

    fun armBoth(sc: Int) {
      for (session in sessions.filterNotNull()) {
        session.gameboy.addressSpace.setByte(0xff01, 0x5a)
        session.gameboy.addressSpace.setByte(0xff02, sc)
      }
    }

    override fun close() {
      var failure: Throwable? = null
      for (session in sessions.reversed().filterNotNull()) {
        try {
          session.close()
        } catch (caught: Throwable) {
          failure?.addSuppressed(caught) ?: run { failure = caught }
        }
      }
      failure?.let { throw it }
    }
  }
}
