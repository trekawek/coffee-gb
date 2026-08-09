package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.ir.FullChanger
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class MachineSnapshotTest {

  @Test
  fun directRestoreResumesFullHostOutputAfterCommit() {
    StateCodecTestSupport.session().use { session ->
      val snapshot = MachineSnapshot.capture(session.gameboy)
      repeat(2_000) { session.gameboy.tick() }

      snapshot.restore(session.gameboy)

      assertTrue(session.gameboy.isCurrentVisibleFrameFullyRendering)
    }
  }

  @Test
  fun stableProfileIdentityRejectsCgbAndCgb0CrossRestoreBeforeMutation() {
    val bytes = StateCodecTestSupport.rom(cgb = true)
    val cgbConfig =
        StateCodecTestSupport.configuration(bytes, GameboyType.CGB)
            .setHardwareProfile(HardwareProfileRegistry.CGB)
    val cgb0Config =
        StateCodecTestSupport.configuration(bytes, GameboyType.CGB)
            .setHardwareProfile(HardwareProfileRegistry.CGB0)
    StateCodecTestSupport.session(cgbConfig).use { source ->
      StateCodecTestSupport.session(cgb0Config).use { target ->
        val snapshot = MachineSnapshot.capture(source.gameboy)
        val before = DetachedStateAdapter.capture(target.gameboy)
        val stages = mutableListOf<ApplyStage>()

        assertFailsWith<StateApplyException> { snapshot.restore(target.gameboy) { stages += it } }

        assertTrue(stages.isEmpty())
        assertEquals(before, DetachedStateAdapter.capture(target.gameboy))
      }
    }
  }

  @Test
  fun stableProfileIdentityRejectsSgbAndSgb2CrossRestoreBeforeMutation() {
    val bytes = StateCodecTestSupport.rom(sgb = true)
    val sgbConfig =
        StateCodecTestSupport.configuration(bytes, GameboyType.SGB)
            .setHardwareProfile(HardwareProfileRegistry.SGB)
    val sgb2Config =
        StateCodecTestSupport.configuration(bytes, GameboyType.SGB)
            .setHardwareProfile(HardwareProfileRegistry.SGB2)
    StateCodecTestSupport.session(sgb2Config).use { source ->
      repeat(7_777) { source.gameboy.tick() }
      val snapshot = MachineSnapshot.capture(source.gameboy)
      repeat(2_048) { source.gameboy.tick() }
      val expected = DetachedStateAdapter.capture(source.gameboy)
      snapshot.restore(source.gameboy)
      repeat(2_048) { source.gameboy.tick() }
      assertEquals(expected, DetachedStateAdapter.capture(source.gameboy))

      StateCodecTestSupport.session(sgbConfig).use { target ->
        val before = DetachedStateAdapter.capture(target.gameboy)
        val stages = mutableListOf<ApplyStage>()
        assertFailsWith<StateApplyException> { snapshot.restore(target.gameboy) { stages += it } }
        assertTrue(stages.isEmpty())
        assertEquals(before, DetachedStateAdapter.capture(target.gameboy))
      }
    }
  }

  @Test
  fun unchangedPagesReuseIdentityAndOneWramWriteCopiesOnlyItsPage() {
    StateCodecTestSupport.session(cgbMbc5Configuration()).use { session ->
      val gameboy = session.gameboy
      val first = MachineSnapshot.capture(gameboy)
      assertPagedArray(first, GBC_RAM_MEMENTO, "ram", 7 * 0x1000)
      assertTrue(
          first.debugArrays(RAM_MEMENTO, "space").count { it.size == 0x2000 } >= 2,
          "both VRAM banks must be represented by immutable pages",
      )
      assertEquals(
          1,
          first.debugArrays(RAM_MEMENTO, "space").count { it.size == 0x00a0 },
          "VRAM and OAM must be represented by immutable pages",
      )
      assertPagedArray(first, DISPLAY_MEMENTO, "buffer", 160 * 144)
      assertPagedArray(first, DISPLAY_MEMENTO, "lastFrame", 160 * 144)
      assertPagedArray(first, MBC5_MEMENTO, "ram", 0x8000)
      val unchanged = MachineSnapshot.capture(gameboy, first)

      assertEquals(0, unchanged.captureStats.copiedPages)
      assertEquals(0, unchanged.captureStats.copiedPageBytes)
      assertTrue(unchanged.captureStats.identityVerifiedPayloadArrays > 0)
      assertTrue(unchanged.captureStats.identityVerifiedPayloadBytes > 0)
      assertTrue(unchanged.captureStats.reusedPages > 0)
      assertSamePages(
          first.debugArrays(RAM_MEMENTO, "space"),
          unchanged.debugArrays(RAM_MEMENTO, "space"),
      )

      gameboy.addressSpace.setByte(0xc321, 0x5a)
      val changed = MachineSnapshot.capture(gameboy, unchanged)
      val before = unchanged.debugArrays(RAM_MEMENTO, "space")
      val after = changed.debugArrays(RAM_MEMENTO, "space")
      val changedPageCount =
          before.indices.sumOf { index ->
            before[index].pageTokens.indices.count { page ->
              before[index].pageTokens[page] !== after[index].pageTokens[page]
            }
          }
      assertEquals(1, changedPageCount)
      assertEquals(1, changed.captureStats.copiedPages)
      assertEquals(MachineSnapshot.PAGE_BYTES.toLong(), changed.captureStats.copiedPageBytes)
      assertEquals(
          unchanged.captureStats.identityVerifiedPayloadArrays,
          changed.captureStats.identityVerifiedPayloadArrays,
      )
      assertEquals(
          unchanged.captureStats.identityVerifiedPayloadBytes,
          changed.captureStats.identityVerifiedPayloadBytes,
      )

      // The only debug surface returns opaque immutable page tokens, never backing arrays.
      assertTrue(after.flatMap { it.pageTokens }.none { it.javaClass.isArray })
    }
  }

  @Test
  fun legacyGameboyMementoStillDeepOwnsPrimitivePayloads() {
    StateCodecTestSupport.session(cgbMbc5Configuration()).use { session ->
      val gameboy = session.gameboy
      val bus = gameboy.addressSpace
      bus.setByte(0x0000, 0x0a)
      bus.setByte(0xc321, 0x11)
      gameboy.gpu.videoRam0.setByte(0x8123, 0x22)
      bus.setByte(0xfe23, 0x33)
      bus.setByte(0xa123, 0x44)
      repeat(8_765) { gameboy.tick() }
      val expected = DetachedStateAdapter.capture(gameboy)
      val legacy = gameboy.captureState()

      bus.setByte(0xc321, 0xaa)
      gameboy.gpu.videoRam0.setByte(0x8123, 0xbb)
      bus.setByte(0xfe23, 0xcc)
      bus.setByte(0xa123, 0xdd)
      repeat(2_048) { gameboy.tick() }

      gameboy.restoreState(legacy)
      assertEquals(expected, DetachedStateAdapter.capture(gameboy))
      assertEquals(0x11, bus.getByte(0xc321))
      assertEquals(0x22, gameboy.gpu.videoRam0.getByte(0x8123))
      assertEquals(0x44, bus.getByte(0xa123))
    }
  }

  @Test
  fun liveMutationRestoreBranchAndSnapshotEvictionCannotAliasPages() {
    StateCodecTestSupport.session(cgbMbc5Configuration()).use { session ->
      val gameboy = session.gameboy
      val bus = gameboy.addressSpace
      bus.setByte(0xc100, 0x11)
      val old = MachineSnapshot.capture(gameboy)
      bus.setByte(0xc100, 0x22)
      val newer = MachineSnapshot.capture(gameboy, old)
      bus.setByte(0xc100, 0x33)

      old.restore(gameboy)
      assertEquals(0x11, bus.getByte(0xc100))
      bus.setByte(0xc100, 0x44)
      val branch = MachineSnapshot.capture(gameboy, old)

      newer.restore(gameboy)
      assertEquals(0x22, bus.getByte(0xc100))
      old.restore(gameboy)
      assertEquals(0x11, bus.getByte(0xc100))
      branch.restore(gameboy)
      assertEquals(0x44, bus.getByte(0xc100))

      val oldPages = old.debugArrays(RAM_MEMENTO, "space")
      val newerPages = newer.debugArrays(RAM_MEMENTO, "space")
      val branchPages = branch.debugArrays(RAM_MEMENTO, "space")
      assertNotEquals(oldPages, newerPages)
      assertNotEquals(oldPages, branchPages)
    }
  }

  @Test
  fun displayIsRetainedOnceByPageIdentityAndRestoresVisibleContinuation() {
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration(hardware = GameboyType.DMG))
        .use { session ->
          val initial = MachineSnapshot.capture(session.gameboy)
          val buffer = initial.debugArrays(DISPLAY_MEMENTO, "buffer").single()
          val lastFrame = initial.debugArrays(DISPLAY_MEMENTO, "lastFrame").single()
          assertEquals(buffer.size, lastFrame.size)
          assertTrue(
              buffer.pageTokens.indices.all {
                buffer.pageTokens[it] === lastFrame.pageTokens[it]
              },
              "equal panel buffers must retain one immutable page payload",
          )

          repeat(31_337) { session.gameboy.tick() }
          val captured = MachineSnapshot.capture(session.gameboy, initial)
          val displayBefore = DetachedStateAdapter.capture(session.gameboy).record(DISPLAY_MEMENTO)
          repeat(4_321) { session.gameboy.tick() }
          val expected = DetachedStateAdapter.capture(session.gameboy)
          repeat(997) { session.gameboy.tick() }

          captured.restore(session.gameboy)
          assertEquals(
              displayBefore,
              DetachedStateAdapter.capture(session.gameboy).record(DISPLAY_MEMENTO),
          )
          repeat(4_321) { session.gameboy.tick() }
          assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
        }
  }

  @Test
  fun doubleSpeedDmaHdmaAudioSerialInfraredAndHeldInputPolicyContinue() {
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration(cgbSpeedSwitchRom(), GameboyType.CGB))
        .use { session ->
          repeat(140_000) {
            if (session.gameboy.speedMode.speedMode == 1 ||
                session.gameboy.cpu.isSpeedSwitching) {
              session.gameboy.tick()
            }
          }
          assertEquals(2, session.gameboy.speedMode.speedMode)
          val bus = session.gameboy.addressSpace
          bus.setByte(0xff26, 0x80)
          bus.setByte(0xff11, 0x80)
          bus.setByte(0xff12, 0xf3)
          bus.setByte(0xff13, 0x40)
          bus.setByte(0xff14, 0x87)
          repeat(512) { session.gameboy.tick() }
          bus.setByte(0xff01, 0xa5)
          bus.setByte(0xff02, 0x81)
          bus.setByte(0xff51, 0xc0)
          bus.setByte(0xff52, 0x00)
          bus.setByte(0xff53, 0x00)
          bus.setByte(0xff54, 0x00)
          bus.setByte(0xff55, 0x80)
          bus.setByte(0xff46, 0xc0)
          bus.setByte(0xff56, 0xc0)
          session.eventBus.post(FullChanger.TransformEvent(5))
          bus.getByte(0xff56)
          repeat(30) { session.gameboy.tick() }
          session.heldButtons = setOf(Button.A, Button.RIGHT)

          val snapshot = MachineSnapshot.capture(session.gameboy)
          val captured = DetachedStateAdapter.capture(session.gameboy)
          assertTrue(
              snapshot.debugArrays(SOUND_MEMENTO, "buffer").single().size > 0,
              "active audio must identity-verify the pending output prefix",
          )
          assertTrue(captured.record(DMA_MEMENTO).bool("transferInProgress"))
          assertTrue(captured.record(HDMA_MEMENTO).bool("transferInProgress"))
          assertTrue(captured.record(SOUND_MODE_MEMENTO).bool("channelEnabled"))
          assertEquals(0x81, captured.record(SERIAL_PORT_MEMENTO).int("sc"))
          assertTrue(captured.record(FULL_CHANGER_MEMENTO).bool("running"))

          // Physical input is intentionally outside rewind snapshots. Use the same held
          // input for both continuations and prove restore keeps it.
          session.heldButtons = setOf(Button.B)
          repeat(192) { session.gameboy.tick() }
          val expected = DetachedStateAdapter.capture(session.gameboy)
          repeat(1_000) { session.gameboy.tick() }
          snapshot.restore(session.gameboy)
          assertEquals(setOf(Button.B), session.heldButtons, "rewind preserves physical input")
          repeat(192) { session.gameboy.tick() }
          assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
        }
  }

  @Test
  fun mbc6FlashAndMbc3RamRtcPagesRestoreDeterministically() {
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration(mbc6Rom(), GameboyType.CGB)
                .setBatteryData(ByteArray(0x108000) { 0xff.toByte() }))
        .use { session ->
          val bus = session.gameboy.addressSpace
          enableMbc6Flash(bus)
          bus.setByte(0x5555, 0xaa)
          bus.setByte(0x4aaa, 0x55)
          bus.setByte(0x5555, 0xa0)
          val armed = MachineSnapshot.capture(session.gameboy)
          assertPagedArray(armed, MBC6_MEMENTO, "ram", 0x8000)
          assertPagedArray(armed, MBC6_MEMENTO, "flash", 0x100000)
          assertTrue(DetachedStateAdapter.capture(session.gameboy).record(MBC6_MEMENTO)
              .bool("flashProgramMode"))

          bus.setByte(0x4321, 0x12)
          val programmed = MachineSnapshot.capture(session.gameboy, armed)
          val before = armed.debugArrays(MBC6_MEMENTO, "flash").single()
          val after = programmed.debugArrays(MBC6_MEMENTO, "flash").single()
          assertEquals(
              1,
              before.pageTokens.indices.count {
                before.pageTokens[it] !== after.pageTokens[it]
              },
          )
          val expected = DetachedStateAdapter.capture(session.gameboy)
          bus.setByte(0x4321, 0)
          armed.restore(session.gameboy)
          bus.setByte(0x4321, 0x12)
          assertEquals(0x12, bus.getByte(0x4321))
          assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
        }

    val time = VirtualTimeSource(120_000)
    val configuration =
        StateCodecTestSupport.configuration(mbc3Rom())
            .setRtcTimeSource(time)
            .setSupportBatterySave(false)
    StateCodecTestSupport.session(configuration).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0x0000, 0x0a)
      bus.setByte(0x4000, 0)
      bus.setByte(0xa000, 0x4a)
      session.gameboy.setCartridgeClockPaused(true)
      val snapshot = MachineSnapshot.capture(session.gameboy)
      val capturedTime = time.currentTimeMillis()

      time.forward(250, TimeUnit.MILLISECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      repeat(128) { session.gameboy.tick() }
      val expected = DetachedStateAdapter.capture(session.gameboy)
      bus.setByte(0xa000, 0xe1)
      time.forward(5, TimeUnit.SECONDS)
      repeat(1_000) { session.gameboy.tick() }

      snapshot.restore(session.gameboy)
      time.setCurrentTimeMillis(capturedTime)
      time.forward(250, TimeUnit.MILLISECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      repeat(128) { session.gameboy.tick() }
      assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
    }
  }

  @Test
  fun mbc7EepromMidWriteCopiesOnePageAndContinuesDeterministically() {
    val configuration =
        StateCodecTestSupport.configuration(mbc7Rom(), GameboyType.CGB)
            .setBatteryData(ByteArray(256) { 0xff.toByte() })
    StateCodecTestSupport.session(configuration).use { session ->
      val bus = session.gameboy.addressSpace
      bus.setByte(0x0000, 0x0a)
      bus.setByte(0x4000, 0x40)
      mbc7Command(bus, 0b00, 0b11000000) // EWEN
      bus.setByte(0xa080, 0)
      mbc7Command(bus, 0b01, 0x12) // WRITE
      mbc7SendBits(bus, 0xbe, 8)

      val midWrite = MachineSnapshot.capture(session.gameboy)
      val eepromBefore = midWrite.debugArrays(MBC7_EEPROM_MEMENTO, "eeprom").single()
      assertEquals(256, eepromBefore.size)

      mbc7SendBits(bus, 0xef, 8)
      val completed = MachineSnapshot.capture(session.gameboy, midWrite)
      val eepromAfter = completed.debugArrays(MBC7_EEPROM_MEMENTO, "eeprom").single()
      assertEquals(
          1,
          eepromBefore.pageTokens.indices.count {
            eepromBefore.pageTokens[it] !== eepromAfter.pageTokens[it]
          },
      )
      val expected = DetachedStateAdapter.capture(session.gameboy)

      bus.setByte(0xa080, 0)
      mbc7Command(bus, 0b01, 0x12)
      mbc7SendBits(bus, 0, 16)
      midWrite.restore(session.gameboy)
      mbc7SendBits(bus, 0xef, 8)
      assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
    }
  }

  @Test
  fun activeSgbMultipacketAndBorderBuffersRestoreDeterministically() {
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration(hardware = GameboyType.SGB))
        .use { session ->
          val firstPacket =
              IntArray(16).also {
                it[0] = (0x05 shl 3) or 2 // first half of a valid two-packet ATTR_LIN
                it[1] = 15
                repeat(14) { index -> it[index + 2] = (1 shl 5) or index }
              }
          sendSgbPacket(session.gameboy.addressSpace, firstPacket)
          val snapshot = MachineSnapshot.capture(session.gameboy)
          assertEquals(
              1,
              DetachedStateAdapter.capture(session.gameboy)
                  .record(SUPER_GAMEBOY_MEMENTO)
                  .int("multipacketIndex"),
          )
          assertPagedArray(snapshot, SGB_DISPLAY_MEMENTO, "sgbBuffer", 256 * 224)
          assertPagedArray(snapshot, SGB_DISPLAY_MEMENTO, "sgbMask", 256 * 224)
          assertPagedArray(snapshot, SGB_DISPLAY_MEMENTO, "paletteMap", 20 * 18)

          val secondPacket = IntArray(16)
          sendSgbPacket(session.gameboy.addressSpace, secondPacket)
          repeat(512) { session.gameboy.tick() }
          val expected = DetachedStateAdapter.capture(session.gameboy)

          sendSgbPacket(session.gameboy.addressSpace, IntArray(16) { 0xff })
          repeat(1_000) { session.gameboy.tick() }
          snapshot.restore(session.gameboy)
          sendSgbPacket(session.gameboy.addressSpace, secondPacket)
          repeat(512) { session.gameboy.tick() }
          assertEquals(expected, DetachedStateAdapter.capture(session.gameboy))
        }
  }

  @Test
  fun unexpectedLiveFailureRollsBackMachineRtcAndRuntime() {
    val time = VirtualTimeSource(120_000)
    val configuration =
        StateCodecTestSupport.configuration(mbc3Rom())
            .setRtcTimeSource(time)
            .setSupportBatterySave(false)
    StateCodecTestSupport.session(configuration).use { session ->
      session.gameboy.addressSpace.setByte(0xc234, 0x11)
      session.gameboy.setCartridgeClockPaused(true)
      val target = MachineSnapshot.capture(session.gameboy)

      time.forward(2, TimeUnit.SECONDS)
      session.gameboy.setCartridgeClockPaused(false)
      session.gameboy.addressSpace.setByte(0xc234, 0x77)
      repeat(2_000) { session.gameboy.tick() }
      session.heldButtons = setOf(Button.START)
      val before = DetachedStateAdapter.capture(session.gameboy)
      assertTrue(session.gameboy.isCurrentVisibleFrameFullyRendering)

      assertFailsWith<StateApplyException> {
        target.restore(session.gameboy) { stage ->
          if (stage == ApplyStage.AFTER_MACHINE_MUTATION) throw InjectedFailure()
        }
      }
      assertEquals(before, DetachedStateAdapter.capture(session.gameboy))
      assertEquals(setOf(Button.START), session.heldButtons)
      assertTrue(
          session.gameboy.isCurrentVisibleFrameFullyRendering,
          "a failed transaction must restore the prior full-output host state",
      )
    }
  }

  private fun assertSamePages(
      expected: List<MachineSnapshot.ArrayDebug>,
      actual: List<MachineSnapshot.ArrayDebug>,
  ) {
    assertEquals(expected.map { it.size }, actual.map { it.size })
    expected.indices.forEach { index ->
      assertTrue(
          expected[index].pageTokens.indices.all { page ->
            expected[index].pageTokens[page] === actual[index].pageTokens[page]
          })
    }
  }

  private fun assertPagedArray(
      snapshot: MachineSnapshot,
      owner: String,
      field: String,
      size: Int,
  ) {
    val array = snapshot.debugArrays(owner, field).single()
    assertEquals(size, array.size)
    assertTrue(array.pageTokens.isNotEmpty())
  }

  private fun MachineState.record(className: String): RecordState {
    fun find(value: StateValue): RecordState? =
        when (value) {
          is RecordState ->
              if (MementoClassNames.record(value.typeId) == className) value
              else value.fields.firstNotNullOfOrNull { find(it.value) }
          is ObjectArrayState -> value.values.firstNotNullOfOrNull(::find)
          is ListState -> value.values.firstNotNullOfOrNull(::find)
          is Int32MapState -> value.entries.firstNotNullOfOrNull { find(it.value) }
          else -> null
        }
    return requireNotNull(find(root)) { "No $className record" }
  }

  private fun RecordState.field(name: String): StateValue =
      fields.single { it.name == name }.value

  private fun RecordState.bool(name: String): Boolean = (field(name) as BooleanState).value

  private fun RecordState.int(name: String): Int = (field(name) as Int32State).value

  private fun cgbMbc5Configuration() =
      StateCodecTestSupport.configuration(mbc5Rom(), GameboyType.CGB)
          .setSupportBatterySave(false)

  private fun mbc5Rom(): ByteArray =
      StateCodecTestSupport.rom(cgb = true).also {
        it[0x147] = 0x1b
        it[0x149] = 0x03
      }

  private fun mbc3Rom(): ByteArray =
      StateCodecTestSupport.rom().also {
        it[0x147] = 0x10
        it[0x149] = 0x03
      }

  private fun mbc6Rom(): ByteArray =
      ByteArray(0x10000).also {
        it[0x100] = 0x18
        it[0x101] = 0xfe.toByte()
        it[0x143] = 0x80.toByte()
        it[0x147] = 0x20
        it[0x148] = 0x01
        it[0x149] = 0x03
      }

  private fun mbc7Rom(): ByteArray =
      StateCodecTestSupport.rom(cgb = true).also {
        it[0x147] = 0x22
        it[0x149] = 0x00
      }

  private fun cgbSpeedSwitchRom(): ByteArray =
      ByteArray(0x8000).also {
        it[0x100] = 0x3e
        it[0x101] = 0x01
        it[0x102] = 0xe0.toByte()
        it[0x103] = 0x4d
        it[0x104] = 0x10
        it[0x105] = 0x00
        it[0x106] = 0x18
        it[0x107] = 0xfe.toByte()
        it[0x143] = 0x80.toByte()
      }

  private fun enableMbc6Flash(bus: eu.rekawek.coffeegb.core.AddressSpace) {
    bus.setByte(0x1000, 1)
    bus.setByte(0x0c00, 1)
    bus.setByte(0x2000, 0)
    bus.setByte(0x2800, 0x08)
  }

  private fun mbc7SendBit(
      bus: eu.rekawek.coffeegb.core.AddressSpace,
      bit: Boolean,
  ) {
    val data = if (bit) 0x02 else 0
    bus.setByte(0xa080, 0x80 or data)
    bus.setByte(0xa080, 0xc0 or data)
  }

  private fun mbc7SendBits(
      bus: eu.rekawek.coffeegb.core.AddressSpace,
      value: Int,
      count: Int,
  ) {
    for (bit in count - 1 downTo 0) mbc7SendBit(bus, ((value shr bit) and 1) != 0)
  }

  private fun mbc7Command(
      bus: eu.rekawek.coffeegb.core.AddressSpace,
      op: Int,
      address: Int,
  ) {
    mbc7SendBit(bus, true)
    mbc7SendBits(bus, op, 2)
    mbc7SendBits(bus, address, 8)
  }

  private fun sendSgbPacket(
      bus: eu.rekawek.coffeegb.core.AddressSpace,
      packet: IntArray,
  ) {
    require(packet.size == 16)
    bus.setByte(0xff00, 0x30)
    bus.setByte(0xff00, 0x00)
    bus.setByte(0xff00, 0x30)
    repeat(128) { bitIndex ->
      val bit = (packet[bitIndex / 8] shr (bitIndex and 7)) and 1
      bus.setByte(0xff00, if (bit == 0) 0x20 else 0x10)
      bus.setByte(0xff00, 0x30)
    }
    bus.setByte(0xff00, 0x20)
    bus.setByte(0xff00, 0x30)
  }

  private object MementoClassNames {
    fun record(typeId: Int): String =
        eu.rekawek.coffeegb.controller.StateTypeRegistry.recordClasses[typeId - 1].name
  }

  private class InjectedFailure : RuntimeException()

  companion object {
    private const val RAM_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.Ram\$RamState"
    private const val DISPLAY_MEMENTO =
        "eu.rekawek.coffeegb.core.gpu.Display\$DisplayState"
    private const val DMA_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.Dma\$DmaState"
    private const val HDMA_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaState"
    private const val SOUND_MODE_MEMENTO =
        "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeState"
    private const val SOUND_MEMENTO =
        "eu.rekawek.coffeegb.core.sound.Sound\$SoundState"
    private const val SERIAL_PORT_MEMENTO =
        "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortState"
    private const val FULL_CHANGER_MEMENTO =
        "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerState"
    private const val MBC6_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6State"
    private const val MBC7_EEPROM_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromState"
    private const val MBC5_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5State"
    private const val GBC_RAM_MEMENTO =
        "eu.rekawek.coffeegb.core.memory.GbcRam\$GbcRamState"
    private const val SUPER_GAMEBOY_MEMENTO =
        "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyState"
    private const val SGB_DISPLAY_MEMENTO =
        "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayState"
  }
}
