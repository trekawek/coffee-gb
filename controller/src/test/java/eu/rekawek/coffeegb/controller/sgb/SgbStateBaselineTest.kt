package eu.rekawek.coffeegb.controller.sgb

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SgbStateBaselineTest {

  @Test
  fun stateFileRoundTripResumesPacketInputRendererAndBorderContinuation() {
    val configuration =
        Gameboy.GameboyConfiguration(Rom(HardwareModelBaselineTest.syntheticRom()))
            .setGameboyType(GameboyType.SGB)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setDisplaySgbBorder(true)
            .setSupportBatterySave(false)
    val eventBus = EventBusImpl(null, null, false)
    val frames = mutableListOf<IntArray>()
    eventBus.register({ event -> frames += event.buffer().clone() }, SgbDisplay.SgbFrameReadyEvent::class.java)

    Session(configuration, eventBus, null).use { session ->
      session.heldButtons = setOf(Button.A, Button.START)
      val driver = JoypDriver(session)

      driver.sendCommand(0x00, 1, 0x21, 0x04, 0x42, 0x08, 0x63, 0x0c,
          0x7f, 0x10, 0x31, 0x14, 0x52, 0x18, 0x73, 0x1c)
      driver.sendCommand(0x04, 1, 1, 7, 0x39, 3, 2, 16, 15)

      // Exercise both transfer-command ownership and a non-default border transition through
      // the real packet collector. Three emitted frames complete each ICD2 VRAM capture.
      driver.sendCommand(0x13, 1, 0)
      runEmittedFrames(session, 3)
      driver.sendCommand(0x14, 1)
      runEmittedFrames(session, 3)
      val syntheticDmg = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT) {
        (it + it / Display.DISPLAY_WIDTH * 3) and 3
      }
      repeat(80) { eventBus.post(Display.DmgFrameReadyEvent(syntheticDmg)) }

      driver.sendCommand(0x17, 1, 3) // BLANK_COLOR0
      eventBus.post(Display.DmgFrameReadyEvent(syntheticDmg))
      driver.sendCommand(0x11, 1, 1) // two-player mode
      driver.writeSelector(0x10)
      driver.writeSelector(0x30)
      assertEquals(0x0e, session.gameboy.addressSpace.getByte(0xff00) and 0x0f)

      // One packet waits in SuperGameboy's two-packet collector while JOYP is 43 bits into a
      // second transfer. Both phases must survive StateFile capture and resume exactly.
      val twoPacket = JoypDriver.command(0x05, 2, IntArray(20) { (it * 11 + 7) and 0xff })
      driver.sendPacket(twoPacket.first())
      val partialPacket = IntArray(16)
      driver.sendIncomplete(partialPacket, 43)

      val snapshotFile = StateCodec.capture(session)
      val snapshotBytes = StateCodec.encode(snapshotFile)
      assertArrayEquals(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte()),
          snapshotBytes.copyOf(4))
      val frameBefore = frames.last().clone()

      val expectedContinuation = continueFromPartial(session, driver, partialPacket, syntheticDmg, eventBus, frames)

      // Abandon the timeline comprehensively before applying the detached file.
      session.heldButtons = emptySet()
      driver.sendCommand(0x17, 1, 2)
      repeat(4096) { session.gameboy.tick() }
      eventBus.post(Display.DmgFrameReadyEvent(IntArray(syntheticDmg.size) { 3 }))

      StateCodec.decodeAndApply(snapshotBytes, session)
      assertArrayEquals(snapshotBytes, StateCodec.encode(StateCodec.capture(session)))
      assertEquals(setOf(Button.A, Button.START), session.heldButtons)
      assertEquals(0x0e, session.gameboy.addressSpace.getByte(0xff00) and 0x0f)

      val actualContinuation = continueFromPartial(session, driver, partialPacket, syntheticDmg, eventBus, frames)
      assertArrayEquals(expectedContinuation.stateBytes, actualContinuation.stateBytes)
      assertArrayEquals(expectedContinuation.frame, actualContinuation.frame)

      val expected = expectedHashes()
      val actual =
          mapOf(
              "snapshot-state" to HardwareModelBaselineTest.sha256(snapshotBytes),
              "snapshot-frame" to HardwareModelBaselineTest.hashInts(frameBefore),
              "continuation-state" to HardwareModelBaselineTest.sha256(actualContinuation.stateBytes),
              "continuation-frame" to HardwareModelBaselineTest.hashInts(actualContinuation.frame),
          )
      assertEquals("Update only after reviewing an intentional SGB state behavior change", expected, actual)
      assertTrue(actualContinuation.frame.size == 256 * 224)
    }
  }

  private fun continueFromPartial(
      session: Session,
      driver: JoypDriver,
      partialPacket: IntArray,
      syntheticDmg: IntArray,
      eventBus: EventBusImpl,
      frames: MutableList<IntArray>,
  ): Continuation {
    driver.writeRemaining(partialPacket, 43)
    driver.sendCommand(0x01, 1, 0x11, 0x01, 0x22, 0x02, 0x33, 0x03,
        0x44, 0x04, 0x55, 0x05, 0x66, 0x06, 0x77, 0x07)
    repeat(2) { eventBus.post(Display.DmgFrameReadyEvent(syntheticDmg)) }
    repeat(2048) { session.gameboy.tick() }
    return Continuation(StateCodec.encode(StateCodec.capture(session)), frames.last().clone())
  }

  private fun expectedHashes(): Map<String, String> {
    val properties = Properties()
    javaClass.getResourceAsStream("/sgb-baselines/state-roundtrip-hashes.properties").use { stream ->
      requireNotNull(stream) { "Missing state-roundtrip-hashes.properties" }
      properties.load(stream)
    }
    return listOf("snapshot-state", "snapshot-frame", "continuation-state", "continuation-frame")
        .associateWith { key ->
          properties.getProperty(key).also { value ->
            require(value?.matches(Regex("[0-9a-f]{64}")) == true) { "Invalid hash for $key" }
          }
        }
  }

  private fun runEmittedFrames(session: Session, count: Int) {
    var frames = 0
    var ticks = 0
    val limit = Gameboy.TICKS_PER_FRAME * (count + 2)
    while (frames < count && ticks++ < limit) {
      if (session.gameboy.tick()) frames++
    }
    assertEquals("Timed out waiting for production frame boundary", count, frames)
  }

  private data class Continuation(val stateBytes: ByteArray, val frame: IntArray)

  private class JoypDriver(private val session: Session) {
    fun sendCommand(id: Int, count: Int, vararg payload: Int) {
      command(id, count, payload).forEach(::sendPacket)
    }

    fun sendPacket(packet: IntArray) {
      require(packet.size == 16)
      start()
      writeBits(packet, 0, 128)
      writeSelector(0x20)
      writeSelector(0x30)
    }

    fun sendIncomplete(packet: IntArray, bits: Int) {
      require(packet.size == 16 && bits in 0..128)
      start()
      writeBits(packet, 0, bits)
    }

    fun writeRemaining(packet: IntArray, bit: Int) {
      writeBits(packet, bit, 128)
      writeSelector(0x20)
      writeSelector(0x30)
    }

    fun writeSelector(value: Int) {
      session.gameboy.addressSpace.setByte(0xff00, value)
    }

    private fun start() {
      writeSelector(0x30)
      writeSelector(0x00)
      writeSelector(0x30)
    }

    private fun writeBits(packet: IntArray, from: Int, until: Int) {
      for (bit in from until until) {
        val value = packet[bit / 8] ushr (bit and 7) and 1
        writeSelector(if (value == 0) 0x20 else 0x10)
        writeSelector(0x30)
      }
    }

    companion object {
      fun command(id: Int, count: Int, payload: IntArray): List<IntArray> {
        require(id in 0..0x1f && count in 1..7 && payload.size <= count * 16 - 1)
        return List(count) { IntArray(16) }.also { packets ->
          packets[0][0] = id shl 3 or count
          payload.forEachIndexed { index, value ->
            require(value in 0..0xff)
            val flat = index + 1
            packets[flat / 16][flat % 16] = value
          }
        }
      }
    }
  }
}
