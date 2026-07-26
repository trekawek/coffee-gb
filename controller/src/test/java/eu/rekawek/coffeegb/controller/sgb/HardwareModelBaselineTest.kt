package eu.rekawek.coffeegb.controller.sgb

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareModelBaselineTest {

  @Test
  fun currentModelsHaveStableRomIndependentFrameAndStateBaselines() {
    val actual =
        listOf(
                ModelCase("DMG", HardwareProfileRegistry.DMG),
                ModelCase("CGB", HardwareProfileRegistry.CGB),
                ModelCase("CGB0", HardwareProfileRegistry.CGB0),
                ModelCase("SGB", HardwareProfileRegistry.SGB),
                ModelCase("SGB2", HardwareProfileRegistry.SGB2),
                ModelCase("MGB", HardwareProfileRegistry.MGB),
            )
            .map(::measure)

    assertEquals(
        "A current-model baseline changed; classify and review it before updating the fixture",
        expectedRows(),
        actual,
    )
    assertEquals(1, actual.map(Baseline::romSha256).toSet().size)
    assertTrue(actual.all { it.bootstrap == "SKIP" })
  }

  private fun measure(case: ModelCase): Baseline {
    val romBytes = syntheticRom()
    val configuration =
        Gameboy.GameboyConfiguration(Rom(romBytes))
            .setHardwareProfile(case.profile)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
    val eventBus = EventBusImpl(null, null, false)
    var dmgFrames = 0
    var cgbFrames = 0
    var sgbFrames = 0
    var lastFrame = IntArray(0)
    eventBus.register({ event ->
      dmgFrames++
      lastFrame = event.pixels().clone()
    }, Display.DmgFrameReadyEvent::class.java)
    eventBus.register({ event ->
      cgbFrames++
      lastFrame = event.pixels().clone()
    }, Display.GbcFrameReadyEvent::class.java)
    eventBus.register({ event ->
      sgbFrames++
      lastFrame = event.buffer().clone()
    }, SgbDisplay.SgbFrameReadyEvent::class.java)

    Session(configuration, eventBus, null).use { session ->
      val registers = session.gameboy.cpu.registers
      val af = registers.af
      val bc = registers.bc
      val de = registers.de
      val hl = registers.hl
      val sp = registers.sp
      val pc = registers.pc
      var tickFrameSignals = 0
      repeat(session.gameboy.clockSpec.controllerTicksPerFrame()) {
        if (session.gameboy.tick()) tickFrameSignals++
      }
      val stateBytes = StateCodec.encode(StateCodec.capture(session))
      val inspection = StateCodec.inspect(stateBytes)
      assertEquals(case.profile.id(), inspection.identities.single().identity!!.profile.canonicalProfileId)
      assertEquals(case.cgb0, inspection.identities.single().identity!!.profile.cgb0Revision)
      return Baseline(
          case.name,
          case.profile == HardwareProfileRegistry.CGB0,
          "%04x".format(af),
          "%04x".format(bc),
          "%04x".format(de),
          "%04x".format(hl),
          "%04x".format(sp),
          "%04x".format(pc),
          dmgFrames,
          cgbFrames,
          sgbFrames,
          tickFrameSignals,
          sha256(stateBytes),
          hashInts(lastFrame),
          sha256(romBytes),
          configuration.bootstrapMode.name,
      )
    }
  }

  private fun expectedRows(): List<Baseline> {
    val stream = javaClass.getResourceAsStream("/sgb-baselines/model-baselines.tsv")
        ?: throw AssertionError("Missing model-baselines.tsv")
    val lines = stream.bufferedReader(StandardCharsets.UTF_8).readLines()
        .filter { it.isNotBlank() && !it.startsWith('#') }
    val header = lines.first().split('\t')
    return lines.drop(1).map { line ->
      val row = header.zip(line.split('\t')).toMap()
      Baseline(
          row.getValue("model"),
          row.getValue("cgb0").toBooleanStrict(),
          row.getValue("af"),
          row.getValue("bc"),
          row.getValue("de"),
          row.getValue("hl"),
          row.getValue("sp"),
          row.getValue("pc"),
          row.getValue("dmg_frames").toInt(),
          row.getValue("cgb_frames").toInt(),
          row.getValue("sgb_frames").toInt(),
          row.getValue("tick_frame_signals").toInt(),
          row.getValue("state_sha256"),
          row.getValue("frame_sha256"),
          row.getValue("rom_sha256"),
          row.getValue("bootstrap"),
      )
    }
  }

  private data class ModelCase(val name: String, val profile: HardwareProfile) {
    val cgb0: Boolean
      get() = profile == HardwareProfileRegistry.CGB0
  }

  private data class Baseline(
      val model: String,
      val cgb0: Boolean,
      val af: String,
      val bc: String,
      val de: String,
      val hl: String,
      val sp: String,
      val pc: String,
      val dmgFrames: Int,
      val cgbFrames: Int,
      val sgbFrames: Int,
      val tickFrameSignals: Int,
      val stateSha256: String,
      val frameSha256: String,
      val romSha256: String,
      val bootstrap: String,
  )

  companion object {
    internal fun syntheticRom(): ByteArray =
        ByteArray(0x8000).also { bytes ->
          "SYNTH-SGB-BASE".forEachIndexed { index, character ->
            bytes[0x134 + index] = character.code.toByte()
          }
          bytes[0x100] = 0x18
          bytes[0x101] = 0xfe.toByte()
          bytes[0x143] = 0x80.toByte()
          bytes[0x146] = 0x03
          bytes[0x147] = 0
          bytes[0x148] = 0
          bytes[0x149] = 0
          var checksum = 0
          for (address in 0x134..0x14c) {
            checksum = (checksum - (bytes[address].toInt() and 0xff) - 1) and 0xff
          }
          bytes[0x14d] = checksum.toByte()
        }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Same canonical int-array form as the core renderer fixture. */
    internal fun hashInts(values: IntArray): String {
      val digest = MessageDigest.getInstance("SHA-256")
      updateInt(digest, 1)
      updateInt(digest, values.size)
      values.forEach { updateInt(digest, it) }
      return digest.digest().toHex()
    }

    private fun updateInt(digest: MessageDigest, value: Int) {
      digest.update((value ushr 24).toByte())
      digest.update((value ushr 16).toByte())
      digest.update((value ushr 8).toByte())
      digest.update(value.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
