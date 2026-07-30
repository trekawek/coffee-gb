package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sound.Sound
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DebugInstructionReplayerTest {

  @Test
  fun `replay restores the exact halt bug retirement`() {
    val configuration =
        configuration(
            program =
                intArrayOf(
                    0xf3, // DI
                    0x3e,
                    0x01, // LD A,$01
                    0xea,
                    0xff,
                    0xff, // LD ($FFFF),A -- enable VBlank IRQ
                    0xe0,
                    0x0f, // LDH ($0F),A -- request it while IME remains clear
                    0x76, // HALT -- enters the HALT bug instead of stopping
                    0x04, // INC B
                    0x00, // NOP
                    0x18,
                    0xfe, // JR $-2
                ))

    replayPreviousInstruction(configuration, targetRetirement = 5).use { replay ->
      assertTrue(replay.expectedDebug.execution().haltBug())
      replay.assertExactResult()
    }
  }

  @Test
  fun `replay crosses a CGB speed switch and restores its first double speed retirement`() {
    val configuration =
        configuration(
            cgb = true,
            program =
                intArrayOf(
                    0x3e,
                    0x01, // LD A,$01
                    0xe0,
                    0x4d, // LDH ($4D),A -- arm KEY1
                    0x10,
                    0x00, // STOP -- perform the speed switch
                    0x00, // first NOP at double speed (the replay target)
                    0x00, // second NOP gives reverse-step a later cursor
                    0x18,
                    0xfe,
                ))

    replayPreviousInstruction(configuration, targetRetirement = 4).use { replay ->
      assertTrue(replay.expectedDebug.execution().doubleSpeed())
      replay.assertExactResult()
    }
  }

  @Test
  fun `active DMA serial and speculative rumble stay exact without leaking host events`() {
    val configuration =
        configuration(
                cgb = true,
                program =
                    intArrayOf(
                        0x3e,
                        0x80, // LD A,$80
                        0xea,
                        0xfe,
                        0xff, // LD ($FFFE),A -- CodeBreaker rumble on
                        0x3e,
                        0xa5, // LD A,$A5
                        0xe0,
                        0x01, // LDH ($01),A -- SB
                        0x3e,
                        0x83, // LD A,$83 -- internal, CGB fast clock
                        0xe0,
                        0x02, // LDH ($02),A -- start serial transfer
                        0x3e,
                        0xc0, // LD A,$C0
                        0xe0,
                        0x46, // LDH ($46),A -- start OAM DMA
                        0x00, // a later retirement while DMA owns the CPU bus
                    ))
            .setCodeBreakerRumble(true)
    val eventBus = EventBusImpl()
    val rumbleEvents = AtomicInteger()
    val frameEvents = AtomicInteger()
    val soundEvents = AtomicInteger()
    eventBus.register({ rumbleEvents.incrementAndGet() }, RumbleEvent::class.java)
    eventBus.register({ frameEvents.incrementAndGet() }, Display.DmgFrameReadyEvent::class.java)
    eventBus.register({ soundEvents.incrementAndGet() }, Sound.SoundSampleEvent::class.java)

    Session(configuration, eventBus, null, SerialEndpoint.NULL_ENDPOINT).use { session ->
      for (index in 0 until 0xa0) {
        session.gameboy.addressSpace.setByte(0xc000 + index, index xor 0x5a)
      }
      replayPreviousInstruction(session, targetRetirement = 8).use { replay ->
        assertTrue(replay.expectedDebug.execution().opcode() == 0xe0)
        assertTrue(session.gameboy.isRumbleActive)
        assertTrue(session.gameboy.addressSpace.getByte(0xff02) and 0x80 != 0)

        val beforeRumble = rumbleEvents.get()
        val beforeFrames = frameEvents.get()
        val beforeSamples = soundEvents.get()
        replay.execute()

        assertEquals(beforeRumble, rumbleEvents.get(), "speculative rumble escaped replay")
        assertEquals(beforeFrames, frameEvents.get(), "speculative video escaped replay")
        assertEquals(beforeSamples, soundEvents.get(), "speculative audio escaped replay")
        replay.assertExactResult()
      }
    }
  }

  @Test
  fun `replay preserves MBC3 RTC and in-memory battery state`() {
    val configuration =
        configuration(
                program = IntArray(32),
                cartridgeType = 0x10, // MBC3 + timer + RAM + battery
                ramSize = 0x03,
            )
            .setBatteryData(ByteArray(0))
            .setRtcTimeSource(VirtualTimeSource(120_000))

    StateCodecTestSupport.session(configuration).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0x0000, 0x0a) // RAM/RTC gate
      bus.setByte(0x4000, 0x00)
      bus.setByte(0xa000, 0x6d) // battery-backed RAM
      bus.setByte(0x4000, 0x08)
      bus.setByte(0xa000, 0x2a) // RTC seconds
      bus.setByte(0x6000, 0x00)
      bus.setByte(0x6000, 0x01) // latch the RTC view

      replayPreviousInstruction(session, targetRetirement = 12).use { replay ->
        replay.assertExactResult()
        assertEquals(0x2a, session.gameboy.addressSpace.getByte(0xa000))
        session.gameboy.addressSpace.setByte(0x4000, 0x00)
        assertEquals(0x6d, session.gameboy.addressSpace.getByte(0xa000))
      }
    }
  }

  @Test
  fun `paused MBC3 replay reanchors an unrelated scratch clock without elapsed time`() {
    val liveTime = VirtualTimeSource(120_000)
    val configuration =
        configuration(
                program = IntArray(32),
                cartridgeType = 0x10, // MBC3 + timer + RAM + battery
                ramSize = 0x03,
            )
            .setBatteryData(ByteArray(0))
            .setRtcTimeSource(liveTime)

    StateCodecTestSupport.session(configuration).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0x0000, 0x0a)
      bus.setByte(0x4000, 0x08)
      bus.setByte(0xa000, 0x2a)
      session.gameboy.setCartridgeClockPaused(true)

      replayPreviousInstruction(
              session,
              targetRetirement = 12,
              cartridgePaused = true,
          )
          .use { replay ->
            replay.assertExactResult()
            assertEquals(0x2a, session.gameboy.addressSpace.getByte(0xa000))
          }
    }
  }

  private fun replayPreviousInstruction(
      configuration: Gameboy.GameboyConfiguration,
      targetRetirement: Int,
      cartridgePaused: Boolean = false,
  ): ReplayFixture {
    val session = StateCodecTestSupport.session(configuration)
    return try {
      replayPreviousInstruction(session, targetRetirement, cartridgePaused).also {
        it.ownsSession = true
      }
    } catch (failure: Throwable) {
      session.close()
      throw failure
    }
  }

  private fun replayPreviousInstruction(
      session: Session,
      targetRetirement: Int,
      cartridgePaused: Boolean = false,
  ): ReplayFixture {
    require(targetRetirement > 0)
    val frameTicks = session.gameboy.clockSpec.controllerTicksPerFrame()
    val history = DebugCheckpointHistory()
    session.gameboy.enableDebugRetirementTracking()
    history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)

    var completedTicks = 0
    var expectedState: ByteArray? = null
    var expectedDebug: DebugSnapshot? = null
    var expectedPosition = -1
    val wantedCurrent = targetRetirement.toLong() + 1
    while (session.gameboy.debugRetirementSequence < wantedCurrent) {
      check(completedTicks < frameTicks) { "test program did not retire inside one frame" }
      history.onTickStarted(cartridgePaused)
      val before = session.gameboy.debugRetirementSequence
      try {
        session.gameboy.tick()
      } catch (failure: Throwable) {
        history.abortTick()
        throw failure
      }
      completedTicks++
      val retired = session.gameboy.debugRetirementSequence != before
      history.onTickCompleted(retired, frameTicks)
      if (retired && session.gameboy.debugRetirementSequence == targetRetirement.toLong()) {
        expectedState = StateCodec.encode(StateCodec.capture(session))
        expectedDebug =
            session.gameboy.captureDebugSnapshot(
                1,
                1,
                completedTicks.toLong(),
                0,
                completedTicks,
                true,
            )
        expectedPosition = completedTicks
      }
    }

    val plan = checkNotNull(history.planPreviousInstruction())
    assertEquals(targetRetirement, plan.targetRetirementOrdinal)
    return ReplayFixture(
        session,
        history,
        plan,
        frameTicks,
        checkNotNull(expectedState),
        checkNotNull(expectedDebug),
        expectedPosition,
    )
  }

  private class ReplayFixture(
      private val session: Session,
      private val history: DebugCheckpointHistory,
      private val plan: DebugCheckpointHistory.InstructionReplayPlan,
      private val frameTicks: Int,
      private val expectedState: ByteArray,
      val expectedDebug: DebugSnapshot,
      private val expectedFramePosition: Int,
  ) : AutoCloseable {
    var ownsSession = false
    private var result: DebugInstructionReplayer.Result? = null

    fun execute(): DebugInstructionReplayer.Result {
      result?.let { return it }
      return DebugInstructionReplayer().use { replayer ->
        replayer.replay(session, plan, frameTicks)
      }.also { result = it }
    }

    fun assertExactResult() {
      val replayed = execute()
      assertEquals(expectedFramePosition.toLong(), replayed.position.masterTick())
      assertEquals(0, replayed.position.frame())
      assertEquals(expectedFramePosition, replayed.position.framePosition())
      assertEquals(
          JoypadButtonMask.fromButtons(session.gameboy.legacyPressedButtons),
          replayed.input.legacyMask,
      )
      assertEquals(session.gameboy.sampledPlayerInput, replayed.input.physical)

      replayed.snapshot.restore(session)
      session.gameboy.seedDeterministicReplayInput(
          JoypadButtonMask.toButtons(replayed.input.legacyMask),
          replayed.input.physical,
      )
      val actualState = StateCodec.encode(StateCodec.capture(session))
      assertContentEquals(expectedState, actualState)

      val actualDebug =
          session.gameboy.captureDebugSnapshot(
              1,
              1,
              replayed.position.masterTick(),
              replayed.position.frame(),
              replayed.position.framePosition(),
              true,
          )
      assertEquals(expectedDebug.registers(), actualDebug.registers())
      assertEquals(expectedDebug.interrupts(), actualDebug.interrupts())
      assertEquals(expectedDebug.timer(), actualDebug.timer())
      assertEquals(expectedDebug.ppu(), actualDebug.ppu())
      assertEquals(expectedDebug.apu(), actualDebug.apu())
      assertEquals(expectedDebug.mapper(), actualDebug.mapper())
      assertEquals(expectedDebug.execution().cpuState(), actualDebug.execution().cpuState())
      assertEquals(expectedDebug.execution().opcode(), actualDebug.execution().opcode())
      assertEquals(
          expectedDebug.execution().extendedOpcode(),
          actualDebug.execution().extendedOpcode(),
      )
      assertEquals(
          expectedDebug.execution().machineCycle(),
          actualDebug.execution().machineCycle(),
      )
      assertEquals(expectedDebug.execution().doubleSpeed(), actualDebug.execution().doubleSpeed())
      assertEquals(expectedDebug.execution().haltBug(), actualDebug.execution().haltBug())
    }

    override fun close() {
      history.disable(
          eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason.SESSION_BOUNDARY)
      if (ownsSession) session.close()
    }
  }

  private companion object {
    fun configuration(
        program: IntArray,
        cgb: Boolean = false,
        cartridgeType: Int = 0,
        ramSize: Int = 0,
    ): Gameboy.GameboyConfiguration {
      val bytes = StateCodecTestSupport.rom(cgb = cgb)
      program.forEachIndexed { index, opcode -> bytes[0x100 + index] = opcode.toByte() }
      bytes[0x147] = cartridgeType.toByte()
      bytes[0x149] = ramSize.toByte()
      return StateCodecTestSupport.configuration(
          bytes,
          if (cgb) GameboyType.CGB else GameboyType.DMG,
      )
    }
  }
}
