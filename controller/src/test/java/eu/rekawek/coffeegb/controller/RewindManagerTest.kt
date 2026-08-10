package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.state.MachineSnapshot
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.serial.mobile.DeterministicMobileAdapterBackend
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RewindManagerTest {

  @Test
  fun machineRewindEntriesResumeFullOutputOnEveryRestore() {
    assertRepeatedRewindKeepsPublishing(
        record = { manager, session -> manager.record(session.gameboy) },
        rewind = { manager, session -> manager.rewindOneStep(session.gameboy) },
    )
  }

  @Test
  fun sessionRewindEntriesResumeFullOutputOnEveryRestore() {
    assertRepeatedRewindKeepsPublishing(
        record = RewindManager::record,
        rewind = RewindManager::rewindOneStep,
    )
  }

  @Test
  fun suppressedFramesDoNotAdvanceRewindCaptureCadence() {
    StateCodecTestSupport.session().use { session ->
      val manager = RewindManager()
      val gameboy = session.gameboy

      gameboy.requestFrameRenderSuppression(true)
      tickUntilVBlank(gameboy)
      assertFalse(gameboy.isCurrentVisibleFrameFullyRendering)
      repeat(RewindManager.RECORD_INTERVAL * 3) { manager.record(gameboy) }
      assertEquals(0, manager.captureCount)

      gameboy.requestFrameRenderSuppression(false)
      tickUntilVBlank(gameboy)
      assertTrue(gameboy.isCurrentVisibleFrameFullyRendering)
      repeat(RewindManager.RECORD_INTERVAL) { manager.record(gameboy) }
      assertEquals(1, manager.captureCount)
    }
  }

  @Test
  fun sessionRewindRestoresPartialMobileTransferCancelsBackendAndAccountsForSerialState() {
    val backend = TrackingBackend()
    val endpoint =
        MobileAdapterSerialEndpoint(
            ClockSpec.LEGACY,
            0x08,
            ByteArray(MobileAdapterEngine.CONFIGURATION_BYTES),
            backend,
        )
    StateCodecTestSupport.session(endpoint = endpoint).use { session ->
      val begin = mobilePacket(0x10, "NINTENDO".encodeToByteArray())
      feedMobile(endpoint, begin.copyOf(2))
      endpoint.setSb(begin[2].toInt() and 0xff)
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x31)

      val manager = RewindManager()
      manager.record(session)
      val machineBytes =
          MachineSnapshot.retainedStats(manager.snapshotsForTesting()).modeledRetainedBytes
      assertTrue(manager.retainedBytesForTesting() > machineBytes)

      repeat(5) { endpoint.sendBit() }
      feedMobile(endpoint, begin.copyOfRange(3, begin.size))
      val expected = endpoint.snapshot()
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x72)
      assertEquals(
          MobileAdapterBackendPort.OfferResult.ACCEPTED,
          backend.offer(
              backend.generation(),
              MobileAdapterBackendPort.BackendRequest(3, 0x42, byteArrayOf(1, 2, 3))),
      )

      assertTrue(manager.rewindOneStep(session))
      assertEquals(0x31, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      assertTrue(backend.cancellations >= 2)
      assertEquals(0, backend.occupiedRequestSlots())
      repeat(5) { endpoint.sendBit() }
      feedMobile(endpoint, begin.copyOfRange(3, begin.size))
      assertMobileResultEquals(expected, endpoint.snapshot())
    }
  }

  @Test
  fun capacityCadenceEvictionRewindAndClearRemainExact() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      var capturedIndex = 0
      var evictedSnapshot: MachineSnapshot? = null
      repeat((RewindManager.CAPACITY + EXTRA_CAPTURES) * RewindManager.RECORD_INTERVAL) { step ->
        if (step % RewindManager.RECORD_INTERVAL == 0) {
          session.gameboy.addressSpace.setByte(TEST_ADDRESS, capturedIndex and 0xff)
          capturedIndex++
        }
        manager.record(session.gameboy)
        if (manager.captureCount == 1) {
          evictedSnapshot = manager.snapshotsForTesting().single()
        }
      }

      assertEquals(RewindManager.CAPACITY, manager.historySize)
      assertEquals(RewindManager.CAPACITY + EXTRA_CAPTURES, manager.captureCount)
      assertTrue(manager.snapshotsForTesting().none { it === evictedSnapshot })
      for (index in capturedIndex - 1 downTo EXTRA_CAPTURES) {
        assertTrue(manager.rewindOneStep(session.gameboy))
        assertEquals(index and 0xff, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      }
      assertFalse(manager.rewindOneStep(session.gameboy))
      assertEquals(0, manager.historySize)
      val heldEvictedSnapshot = requireNotNull(evictedSnapshot)
      heldEvictedSnapshot.restore(session.gameboy)
      assertEquals(0, session.gameboy.addressSpace.getByte(TEST_ADDRESS))

      repeat(RewindManager.RECORD_INTERVAL * 3) { manager.record(session.gameboy) }
      assertTrue(manager.historySize > 0)
      manager.clear()
      assertEquals(0, manager.historySize)
      heldEvictedSnapshot.restore(session.gameboy)
      assertEquals(0, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      manager.record(session.gameboy)
      assertEquals(1, manager.historySize, "clear resets the six-frame cadence")
    }
  }

  @Test
  fun disabledManagerPerformsZeroCaptureOrCadenceWork() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager(enabled = false)
      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL * 2) {
        manager.record(session.gameboy)
      }
      assertEquals(0, manager.captureCount)
      assertEquals(0, manager.historySize)
      assertFalse(manager.rewindOneStep(session.gameboy))
    }
  }

  @Test
  fun disabledManagerDoesNotPrepareSessionSeed() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager(enabled = false)

      assertNull(manager.prepareSessionSeed(session))
      assertFalse(manager.hasPreparedSessionSeed)
      assertEquals(0, manager.captureCount)
      assertEquals(0, manager.historySize)
    }
  }

  @Test
  fun preparedSessionSeedLeavesHistoryCadenceAndCaptureCountUntouched() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()

      val seed = assertNotNull(manager.prepareSessionSeed(session))

      assertFalse(manager.hasPreparedSessionSeed, "a candidate token is not history state")
      assertEquals(0, manager.captureCount)
      assertEquals(0, manager.historySize)
      seed.discard()
    }
  }

  @Test
  fun firstSessionRecordUsesPreparedSeedButOnlyRestoresPostFrameState() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x11)
      val seed = assertNotNull(manager.prepareSessionSeed(session))

      manager.beginSession(session, seed)
      assertTrue(manager.hasPreparedSessionSeed)
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x22)
      manager.record(session)

      assertEquals(1, manager.captureCount)
      assertEquals(1, manager.historySize)
      assertFalse(manager.hasPreparedSessionSeed)
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x33)
      assertTrue(manager.rewindOneStep(session))
      assertEquals(0x22, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      assertFalse(manager.rewindOneStep(session), "the pre-frame seed is never rewindable")
    }
  }

  @Test
  fun preparedSessionSeedRetainsSixFrameCadenceAndSurvivesSuppressedFrames() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      val seed = assertNotNull(manager.prepareSessionSeed(session))
      manager.beginSession(session, seed)

      session.gameboy.requestFrameRenderSuppression(true)
      tickUntilVBlank(session.gameboy)
      manager.record(session)
      assertTrue(manager.hasPreparedSessionSeed)
      assertEquals(0, manager.captureCount)

      session.gameboy.requestFrameRenderSuppression(false)
      tickUntilVBlank(session.gameboy)
      repeat(RewindManager.RECORD_INTERVAL * 2) { manager.record(session) }

      assertEquals(2, manager.captureCount)
      assertFalse(manager.hasPreparedSessionSeed)
    }
  }

  @Test
  fun clearAndMachineRecordingReleasePreparedSessionSeed() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      manager.beginSession(session, assertNotNull(manager.prepareSessionSeed(session)))
      assertTrue(manager.hasPreparedSessionSeed)
      manager.clear()
      assertFalse(manager.hasPreparedSessionSeed)

      manager.beginSession(session, assertNotNull(manager.prepareSessionSeed(session)))
      manager.record(session.gameboy)

      assertFalse(manager.hasPreparedSessionSeed)
      assertEquals(1, manager.historySize)
      assertFailsWith<IllegalStateException> { manager.rewindOneStep(session) }
    }
  }

  @Test
  fun preparedSeedTransfersExactRetentionIntoFirstHistoryEntry() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager =
          RewindManager(memoryBudgetBytes = RewindManager.MIN_MEMORY_BUDGET_BYTES)
      manager.beginSession(session, assertNotNull(manager.prepareSessionSeed(session)))

      val pendingBytes = manager.retainedBytesForTesting()
      assertTrue(pendingBytes > 0L, "the staged baseline is manager-owned memory")
      assertEquals(pendingBytes, manager.approximateRetainedBytesForTesting)

      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x5a)
      manager.record(session)

      assertEquals(1, manager.historySize)
      assertFalse(manager.hasPreparedSessionSeed)
      assertEquals(
          manager.retainedBytesForTesting(),
          manager.approximateRetainedBytesForTesting,
          "the first seed-relative entry must be charged as a complete retained root",
      )
      manager.clear()
      assertEquals(0L, manager.retainedBytesForTesting())
      assertEquals(0L, manager.approximateRetainedBytesForTesting)
    }
  }

  @Test
  fun configuredDurationAndMemoryBudgetBoundRetainedHistory() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val durationManager =
          RewindManager(
              durationSeconds = RewindManager.MIN_DURATION_SECONDS,
              memoryBudgetBytes = Long.MAX_VALUE,
          )
      val expectedCapacity =
          RewindManager.MIN_DURATION_SECONDS * 60 / RewindManager.RECORD_INTERVAL
      repeat((expectedCapacity + 2) * RewindManager.RECORD_INTERVAL) { frame ->
        session.gameboy.addressSpace.setByte(TEST_ADDRESS, frame and 0xff)
        durationManager.record(session.gameboy)
      }
      assertEquals(expectedCapacity, durationManager.historySize)

      val budgetManager =
          RewindManager(
              durationSeconds = RewindManager.MAX_DURATION_SECONDS,
              memoryBudgetBytes = RewindManager.MIN_MEMORY_BUDGET_BYTES,
          )
      repeat(500) { capture ->
        repeat(8) { page ->
          val offset = page * 1024 + capture
          val value = (capture * 73 + page * 19) and 0xff
          session.gameboy.addressSpace.setByte(0xc000 + offset, value)
          session.gameboy.gpu.videoRam0.setByte(0x8000 + offset, value xor 0x5a)
          session.gameboy.gpu.videoRam1.setByte(0x8000 + offset, value xor 0xa5)
        }
        repeat(RewindManager.RECORD_INTERVAL) {
          budgetManager.record(session.gameboy)
        }
      }
      assertTrue(
          budgetManager.retainedBytesForTesting() <= RewindManager.MIN_MEMORY_BUDGET_BYTES)
      assertTrue(budgetManager.budgetEvictionCount > 0)
    }

    assertFailsWith<IllegalArgumentException> {
      RewindManager(durationSeconds = RewindManager.MIN_DURATION_SECONDS - 1)
    }
    assertFailsWith<IllegalArgumentException> {
      RewindManager(memoryBudgetBytes = RewindManager.MIN_MEMORY_BUDGET_BYTES - 1)
    }
  }

  @Test
  fun deterministicThreeHundredEntryWorkloadBeatsMeasuredMasterBaselineByHalf() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      session.gameboy.addressSpace.setByte(0x0000, 0x0a)
      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL) { frame ->
        emulateProductionFrame(session.gameboy, frame)
        manager.record(session.gameboy)
      }
      assertEquals(RewindManager.CAPACITY, manager.historySize)
      assertTrue(
          manager.snapshotsForTesting().all {
            it.captureStats.identityVerifiedPayloadArrays > 0 &&
                it.captureStats.identityVerifiedPayloadBytes > 0L
          },
          "production-cadence captures must identity-verify their live source payloads",
      )
      val retained = MachineSnapshot.retainedStats(manager.snapshotsForTesting())
      assertTrue(
          retained.retainedPrimitiveBytes * 2 < MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES,
          "retained=${retained.retainedPrimitiveBytes}, " +
              "baseline=$MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES",
      )
    }
  }

  private fun emulateProductionFrame(
      gameboy: Gameboy,
      frame: Int,
  ) {
    repeat(Gameboy.TICKS_PER_FRAME) { gameboy.tick() }
    val value = frame and 0xff
    gameboy.addressSpace.setByte(0xc000 + (frame * 37 and 0xfff), value)
    gameboy.addressSpace.setByte(0xd000 + (frame * 53 and 0xfff), value xor 0x5a)
    gameboy.gpu.videoRam0.setByte(0x8000 + (frame * 29 and 0x1fff), value xor 0xa5)
    gameboy.gpu.videoRam1.setByte(0x8000 + (frame * 31 and 0x1fff), value xor 0x3c)
    gameboy.addressSpace.setByte(0xfe00 + (frame % 0xa0), value)
    gameboy.addressSpace.setByte(0x4000, frame ushr 5 and 0x03)
    gameboy.addressSpace.setByte(0xa000 + (frame * 43 and 0x1fff), value xor 0xc3)
  }

  private fun assertRepeatedRewindKeepsPublishing(
      record: (RewindManager, Session) -> Unit,
      rewind: (RewindManager, Session) -> Boolean,
  ) {
    StateCodecTestSupport.session().use { session ->
      val publishedFrames = AtomicInteger()
      session.eventBus.register<Display.DmgFrameReadyEvent> { publishedFrames.incrementAndGet() }
      val manager = RewindManager()

      repeat(REWIND_ENTRY_COUNT * RewindManager.RECORD_INTERVAL) {
        tickUntilVBlank(session.gameboy)
        record(manager, session)
      }
      assertEquals(REWIND_ENTRY_COUNT, manager.captureCount)
      val framesBeforeRewind = publishedFrames.get()

      repeat(REWIND_ENTRY_COUNT) {
        assertTrue(rewind(manager, session))
        assertTrue(session.gameboy.isCurrentVisibleFrameFullyRendering)
        tickUntilVBlank(session.gameboy)
      }
      assertEquals(framesBeforeRewind + REWIND_ENTRY_COUNT, publishedFrames.get())
    }
  }

  private fun tickUntilVBlank(gameboy: Gameboy) {
    repeat(Gameboy.TICKS_PER_FRAME * 2) {
      if (gameboy.tick()) return
    }
    error("PPU did not reach VBlank within two physical frames")
  }

  private fun configuration() =
      StateCodecTestSupport.configuration(
              StateCodecTestSupport.rom(cgb = true).also {
                it[0x147] = 0x1b
                it[0x149] = 0x03
              },
              GameboyType.CGB,
          )
          .setSupportBatterySave(false)

  private fun feedMobile(endpoint: MobileAdapterSerialEndpoint, bytes: ByteArray) {
    bytes.forEach { byte ->
      endpoint.setSb(byte.toInt() and 0xff)
      endpoint.startSending()
      repeat(8) { endpoint.sendBit() }
    }
  }

  private fun mobilePacket(command: Int, data: ByteArray): ByteArray {
    val bytes = ByteArray(8 + data.size)
    bytes[0] = 0x99.toByte()
    bytes[1] = 0x66
    bytes[2] = command.toByte()
    bytes[4] = (data.size ushr 8).toByte()
    bytes[5] = data.size.toByte()
    data.copyInto(bytes, 6)
    var checksum = 0
    for (index in 2 until 6 + data.size) {
      checksum = (checksum + (bytes[index].toInt() and 0xff)) and 0xffff
    }
    bytes[6 + data.size] = (checksum ushr 8).toByte()
    bytes[7 + data.size] = checksum.toByte()
    return bytes
  }

  private fun assertMobileResultEquals(
      expected: MobileAdapterEngine.EngineResult,
      actual: MobileAdapterEngine.EngineResult,
  ) {
    assertEquals(expected.phase(), actual.phase())
    assertEquals(expected.outcome(), actual.outcome())
    assertEquals(expected.error(), actual.error())
    assertContentEquals(expected.responsePacket(), actual.responsePacket())
    assertContentEquals(expected.acknowledgement(), actual.acknowledgement())
    assertEquals(expected.retainedBytes(), actual.retainedBytes())
    assertEquals(expected.pendingPacketSlots(), actual.pendingPacketSlots())
  }

  private class TrackingBackend(
      private val delegate: DeterministicMobileAdapterBackend =
          DeterministicMobileAdapterBackend(),
  ) : MobileAdapterBackendPort by delegate {
    var cancellations = 0
      private set

    override fun cancelAll() {
      cancellations++
      delegate.cancelAll()
    }
  }

  companion object {
    /** Exact production-cadence primitive-array baseline measured on master 195d9172. */
    private const val MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES = 337_665_600L
    private const val EXTRA_CAPTURES = 2
    private const val REWIND_ENTRY_COUNT = 3
    private const val TEST_ADDRESS = 0xc100
  }
}
