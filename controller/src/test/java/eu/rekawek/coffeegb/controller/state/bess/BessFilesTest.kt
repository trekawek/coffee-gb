package eu.rekawek.coffeegb.controller.state.bess

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.state.bess.BessCodec
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BessFilesTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `BESS worker round trip passes complete detached-state validation`() {
    for (hardware in listOf(HardwareProfileRegistry.DMG, HardwareProfileRegistry.CGB,
        HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2)) {
      val color = hardware == HardwareProfileRegistry.CGB
      val configuration = Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom(cgb = color)))
          .setHardwareProfile(hardware)
          .setSupportBatterySave(false)
      configuration.build().use { live ->
        live.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
        live.addressSpace.setByte(0xc123, 0x52)
        live.runTicks(1000)
        val path = temporary.newFolder().toPath().resolve("portable.bess")
        val snapshot = DetachedStateAdapter.capture(live)
        val worker = configuration.forBessTransfer(live)
        BessFiles.save(path, worker, snapshot)
        live.addressSpace.setByte(0xc123, 0x17)
        DetachedStateAdapter.apply(live, BessFiles.load(path, worker))
        assertEquals(0x52, live.addressSpace.getByte(0xc123))
        live.runTicks(1024)
        assertEquals(0x52, live.addressSpace.getByte(0xc123))
      }
    }
  }

  @Test
  fun `workers never migrate or overwrite file batteries even when import fails`() {
    val directory = temporary.newFolder().toPath()
    val target = directory.resolve("current.sav")
    val legacy = directory.resolve("legacy.sav")
    val original = ByteArray(0x2000) { 0x11 }
    Files.write(target, original)
    Files.setLastModifiedTime(target, FileTime.fromMillis(1000))
    val rom = StateCodecTestSupport.rom().also {
      it[0x147] = 3 // MBC1 + RAM + battery
      it[0x149] = 2 // 8 KiB RAM
    }
    val configuration = Gameboy.GameboyConfiguration(Rom(rom))
        .setBatteryStorage(BatteryStorage.direct(target, listOf(legacy)), null)
    val live = configuration.build()
    try {
      live.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
      live.addressSpace.setByte(0, 0x0a)
      live.addressSpace.setByte(0xa000, 0x35)
      // A new candidate with a real FileBattery would import this newer fallback in its constructor.
      Files.write(legacy, ByteArray(0x2000) { 0x77 })
      Files.setLastModifiedTime(legacy, FileTime.fromMillis(2000))
      val worker = configuration.forBessTransfer(live)
      val path = directory.resolve("portable.bess")
      BessFiles.save(path, worker, DetachedStateAdapter.capture(live))
      assertContentEquals(original, Files.readAllBytes(target))

      val loaded = BessFiles.load(path, worker)
      assertContentEquals(original, Files.readAllBytes(target))
      live.addressSpace.setByte(0xa000, 0x68)
      DetachedStateAdapter.apply(live, loaded)
      assertEquals(0x35, live.addressSpace.getByte(0xa000))
      assertContentEquals(original, Files.readAllBytes(target))

      val wrongRom = BessCodec.read(Files.readAllBytes(path))
      wrongRom.info()[0] = (wrongRom.info()[0].toInt() xor 1).toByte()
      Files.write(path, BessCodec.write(wrongRom))
      assertFailsWith<IllegalArgumentException> { BessFiles.load(path, worker) }
      assertEquals(0x35, live.addressSpace.getByte(0xa000))
      assertContentEquals(original, Files.readAllBytes(target))
    } finally {
      // The live fixture also remains unpublished; no test teardown should flush its cartridge.
      live.discardUnstarted()
    }
  }

  @Test
  fun `workers use frozen clock and released input instead of calling live services`() {
    var clockCalls = 0
    var inputCalls = 0
    val configuration = Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom()))
        .setSupportBatterySave(false)
        .setRtcTimeSource { clockCalls++; 100_000L }
        .setPlayerInputSource { inputCalls++; eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot.released() }
    configuration.build().use { live ->
      val snapshot = DetachedStateAdapter.capture(live)
      val worker = configuration.forBessTransfer(live)
      val originalClockCalls = clockCalls
      val originalInputCalls = inputCalls
      val path = temporary.newFolder().toPath().resolve("isolated.bess")
      BessFiles.save(path, worker, snapshot)
      BessFiles.load(path, worker)
      assertEquals(originalClockCalls, clockCalls)
      assertEquals(originalInputCalls, inputCalls)
    }
  }
}
