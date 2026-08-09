package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.AddressSpace
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SessionSnapshotRtcPauseTest {

  @Test
  fun restoreReanchorsRunningCheckpointToEffectiveControllerPause() {
    val time = VirtualTimeSource(120_000)
    val configuration =
        StateCodecTestSupport.configuration(mbc3Rom()).setRtcTimeSource(time)
    StateCodecTestSupport.session(configuration).use { session ->
      selectRtcSeconds(session.gameboy.addressSpace)
      session.gameboy.addressSpace.setByte(0xa000, 5)
      val target = SessionSnapshot.capture(session)

      session.gameboy.addressSpace.setByte(0xa000, 37)
      time.forward(10, TimeUnit.SECONDS)
      target.restore(session, effectiveCartridgePause = true)

      val runtime = requireNotNull(session.gameboy.captureRtcRuntimeState().primary())
      assertTrue(runtime.emulationPaused())
      assertEquals(time.currentTimeMillis(), runtime.pauseStartedMillis())
      assertEquals(5, session.gameboy.addressSpace.getByte(0xa000))

      time.forward(1, TimeUnit.SECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      assertEquals(6, session.gameboy.addressSpace.getByte(0xa000))
    }
  }

  @Test
  fun restoreReanchorsTheDatelSlotRtcWithoutInventingAPrimaryRtc() {
    val time = VirtualTimeSource(240_000)
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.datelRom(), GameboyType.CGB)
            .setSlotRom(Rom(mbc3Rom()))
            .setRtcTimeSource(time)
    StateCodecTestSupport.session(configuration).use { session ->
      val target = SessionSnapshot.capture(session)
      time.forward(5, TimeUnit.SECONDS)

      target.restore(session, effectiveCartridgePause = true)

      val runtime = session.gameboy.captureRtcRuntimeState()
      assertNull(runtime.primary())
      val slot = requireNotNull(runtime.slot())
      assertTrue(slot.emulationPaused())
      assertEquals(time.currentTimeMillis(), slot.pauseStartedMillis())
    }
  }

  @Test
  fun restoreKeepsUnlatchedHuC3AndTama5TimeThroughTheCheckpointOnly() {
    assertUnlatchedWallClockCheckpoint(0xfe, ::readHuc3Minutes)
    assertUnlatchedWallClockCheckpoint(0xfd, ::readTama5Minutes)
  }

  @Test
  fun datelSlotWallClockCaptureIsSingleReadAndRollbackCaptureIsServiceFree() {
    val time = CountingTimeSource(120_000)
    val configuration =
        StateCodecTestSupport.configuration(StateCodecTestSupport.datelRom(), GameboyType.CGB)
            .setSlotRom(Rom(wallClockRom(0xfd)))
            .setRtcTimeSource(time)
    StateCodecTestSupport.session(configuration).use { session ->
      time.forward(2, TimeUnit.MINUTES)
      val callsBeforeCheckpoint = time.calls
      val target = SessionSnapshot.capture(session)
      assertEquals(1, time.calls - callsBeforeCheckpoint)

      val callsBeforeRollbackCapture = time.calls
      val rollbackRuntime = session.gameboy.captureWallClockRuntimeStateWithoutTimeSource()
      assertNull(rollbackRuntime.primary())
      assertTrue(rollbackRuntime.slot() != null)
      assertEquals(callsBeforeRollbackCapture, time.calls)

      time.forward(10, TimeUnit.MINUTES)
      exposeDatelSlot(session.gameboy.addressSpace)
      assertEquals(12, readTama5Minutes(session.gameboy.addressSpace))
      target.restore(session, effectiveCartridgePause = true)
      exposeDatelSlot(session.gameboy.addressSpace)
      assertEquals(2, readTama5Minutes(session.gameboy.addressSpace))
    }
  }

  @Test
  fun timeSourceFailureDuringReanchorRollsBackTheWholeSession() {
    val time = FailingTimeSource(360_000)
    val configuration =
        StateCodecTestSupport.configuration(mbc3Rom())
            .setRtcTimeSource(time)
            .setCodeBreakerRumble(true)
    StateCodecTestSupport.session(configuration).use { session ->
      session.gameboy.setCartridgeClockPaused(true)
      val rumble = mutableListOf<Boolean>()
      session.eventBus.register({ event -> rumble += event.on() }, RumbleEvent::class.java)
      session.gameboy.addressSpace.setByte(0xfffe, 0x80)
      val target = SessionSnapshot.capture(session)
      session.gameboy.addressSpace.setByte(0xfffe, 0x00)
      session.gameboy.addressSpace.setByte(0xc123, 0x77)
      repeat(128) { session.gameboy.tick() }
      val before = DetachedStateAdapter.capture(session)
      assertTrue(session.gameboy.isCurrentVisibleFrameFullyRendering)
      rumble.clear()

      // The rollback capture consults the paused clock twice. Fail only after that preflight, at
      // the final pause re-anchor, so the machine and rumble state have already been mutated.
      time.failAfterSuccessfulCalls(2)
      assertFailsWith<StateApplyException> {
        target.restore(session, effectiveCartridgePause = true)
      }
      assertEquals(1, time.failureCount)
      time.resume()

      assertEquals(before, DetachedStateAdapter.capture(session))
      assertEquals(emptyList(), rumble)
      assertTrue(
          session.gameboy.isCurrentVisibleFrameFullyRendering,
          "a failed outer transaction must restore the prior full-output host state",
      )

      target.restore(session, effectiveCartridgePause = true)
      assertEquals(listOf(true), rumble)
      assertTrue(session.gameboy.isCurrentVisibleFrameFullyRendering)
    }
  }

  @Test
  fun committedSessionRestorePublishesExactlyOneAggregateMapperRumbleState() {
    val configuration = StateCodecTestSupport.configuration(mbc5RumbleRom())
    StateCodecTestSupport.session(configuration).use { session ->
      val rumble = mutableListOf<Boolean>()
      session.eventBus.register({ event -> rumble += event.on() }, RumbleEvent::class.java)
      val motorOff = SessionSnapshot.capture(session)
      session.gameboy.addressSpace.setByte(0x4000, 0x08)
      val motorOn = SessionSnapshot.capture(session)
      session.gameboy.addressSpace.setByte(0x4000, 0x00)
      rumble.clear()
      session.eventBus.register<RumbleEvent>(
          { throw IllegalStateException("Injected presentation failure") },
          RumbleEvent::class.java,
      )

      motorOn.restore(session)
      assertEquals(listOf(true), rumble)

      rumble.clear()
      motorOff.restore(session)
      assertEquals(listOf(false), rumble)

      rumble.clear()
      motorOff.restore(session)
      assertEquals(emptyList(), rumble)
    }
  }

  private fun selectRtcSeconds(bus: eu.rekawek.coffeegb.core.AddressSpace) {
    bus.setByte(0x0000, 0x0a)
    bus.setByte(0x4000, 0x08)
  }

  private fun assertUnlatchedWallClockCheckpoint(
      type: Int,
      readMinutes: (AddressSpace) -> Int,
  ) {
    val time = VirtualTimeSource(120_000)
    val configuration =
        StateCodecTestSupport.configuration(wallClockRom(type)).setRtcTimeSource(time)
    StateCodecTestSupport.session(configuration).use { session ->
      time.forward(2, TimeUnit.MINUTES)
      // No mapper RTC command/read occurs before this checkpoint.
      val target = SessionSnapshot.capture(session)

      time.forward(10, TimeUnit.MINUTES)
      assertEquals(12, readMinutes(session.gameboy.addressSpace))
      target.restore(session, effectiveCartridgePause = true)
      assertEquals(2, readMinutes(session.gameboy.addressSpace))

      time.forward(1, TimeUnit.MINUTES)
      assertEquals(3, readMinutes(session.gameboy.addressSpace))
    }
  }

  private fun readHuc3Minutes(bus: AddressSpace): Int {
    bus.setByte(0x0000, 0x0b)
    bus.setByte(0xa000, 0x40)
    bus.setByte(0xa000, 0x50)
    bus.setByte(0xa000, 0x10)
    bus.setByte(0x0000, 0x0c)
    val low = bus.getByte(0xa000) and 0x0f
    bus.setByte(0x0000, 0x0b)
    bus.setByte(0xa000, 0x10)
    bus.setByte(0x0000, 0x0c)
    val middle = bus.getByte(0xa000) and 0x0f
    return middle * 16 + low
  }

  private fun readTama5Minutes(bus: AddressSpace): Int {
    writeTama5Register(bus, 0x4, 0x6)
    writeTama5Register(bus, 0x5, 0x0)
    writeTama5Register(bus, 0x6, 0x4)
    writeTama5Register(bus, 0x7, 0x6)
    bus.setByte(0xa001, 0x0c)
    val low = bus.getByte(0xa000) and 0x0f
    bus.setByte(0xa001, 0x0d)
    val high = bus.getByte(0xa000) and 0x0f
    return high * 10 + low
  }

  private fun writeTama5Register(bus: AddressSpace, register: Int, value: Int) {
    bus.setByte(0xa001, register)
    bus.setByte(0xa000, value)
  }

  private fun exposeDatelSlot(bus: AddressSpace) {
    bus.setByte(0x7fe5, 0x10)
  }

  private fun mbc3Rom(): ByteArray =
      StateCodecTestSupport.rom().also {
        it[0x147] = 0x10
        it[0x149] = 0x03
      }

  private fun mbc5RumbleRom(): ByteArray =
      StateCodecTestSupport.rom().also {
        it[0x147] = 0x1e
        it[0x149] = 0x03
      }

  private fun wallClockRom(type: Int): ByteArray =
      StateCodecTestSupport.rom().also {
        it[0x147] = type.toByte()
        it[0x149] = 0x03
      }

  private class CountingTimeSource(
      private var current: Long,
  ) : TimeSource {
    var calls = 0
      private set

    fun forward(amount: Long, unit: TimeUnit) {
      current += unit.toMillis(amount)
    }

    override fun currentTimeMillis(): Long {
      calls++
      return current
    }
  }

  private class FailingTimeSource(
      private var current: Long,
  ) : TimeSource {
    @Volatile private var successfulCallsUntilFailure: Int? = null

    @Volatile var failureCount = 0
      private set

    fun failAfterSuccessfulCalls(count: Int) {
      require(count >= 0)
      successfulCallsUntilFailure = count
    }

    fun resume() {
      successfulCallsUntilFailure = null
    }

    override fun currentTimeMillis(): Long {
      val remaining = successfulCallsUntilFailure
      if (remaining != null) {
        if (remaining == 0) {
          failureCount++
          error("Injected time-source failure")
        }
        successfulCallsUntilFailure = remaining - 1
      }
      return current
    }
  }
}
