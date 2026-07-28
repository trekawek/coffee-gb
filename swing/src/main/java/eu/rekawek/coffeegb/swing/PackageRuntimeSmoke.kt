package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sound.Sound
import java.util.concurrent.atomic.AtomicReference

/**
 * Small, redistributable end-to-end diagnostic used against the actual packaged launcher.
 *
 * The ROM is generated in memory from reviewable instructions and is never written or packaged.
 * One deterministic machine exercises video and audio publication, live input sampling, and the
 * existing portable StateFile codec. It does not construct Swing, inspect a user's files, or
 * enable battery persistence.
 */
object PackageRuntimeSmoke {

  private const val MAX_TICKS = 250_000

  data class Result(
      val ticks: Int,
      val videoFrames: Int,
      val audioBuffers: Int,
      val stateBytes: Int,
  )

  fun run(): Result {
    val pressed =
        PlayerInputSnapshot.of(
            listOf(setOf(Button.A), emptySet(), emptySet(), emptySet()),
        )
    val input = AtomicReference(pressed)
    val configuration =
        Gameboy.GameboyConfiguration(Rom(syntheticRom()))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
            .setPlayerInputSource { input.get() }
    val bus = EventBusImpl(null, "package-smoke", false)
    var videoFrames = 0
    var audioBuffers = 0
    bus.register(
        { event ->
          check(event.pixels().size == Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
          videoFrames++
        },
        Display.DmgFrameReadyEvent::class.java,
    )
    bus.register(
        { event ->
          check(event.buffer().size == configuration.clockSpec.controllerTicksPerFrame() * 2)
          audioBuffers++
        },
        Sound.SoundSampleEvent::class.java,
    )

    val gameboy = configuration.build()
    try {
      gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, null)
      gameboy.tick()
      check(Button.A in gameboy.pressedButtons) { "Synthetic input press was not sampled" }
      input.set(PlayerInputSnapshot.released())
      gameboy.tick()
      check(Button.A !in gameboy.pressedButtons) { "Synthetic input release was not sampled" }

      var ticks = 2
      while ((videoFrames == 0 || audioBuffers == 0) && ticks < MAX_TICKS) {
        gameboy.tick()
        ticks++
      }
      check(videoFrames > 0) { "Synthetic video frame was not published" }
      check(audioBuffers > 0) { "Synthetic audio buffer was not published" }

      val state =
          StateCodec.encode(
              StateCodec.capture(configuration, gameboy),
              StateCompression.DEFLATE,
          )
      val inspection = StateCodec.inspect(state)
      check(inspection.checksumValid) { "Synthetic StateFile checksum failed inspection" }
      repeat(1024) { gameboy.tick() }
      StateCodec.decodeAndApply(state, configuration, gameboy)
      val restored =
          StateCodec.encode(
              StateCodec.capture(configuration, gameboy),
              StateCompression.DEFLATE,
          )
      check(state.contentEquals(restored)) { "Synthetic StateFile restore did not round-trip" }
      return Result(ticks, videoFrames, audioBuffers, state.size)
    } finally {
      gameboy.close()
      bus.close()
    }
  }

  private fun syntheticRom(): ByteArray =
      ByteArray(0x8000).also { bytes ->
        // Infinite JR loop at the cartridge entry point. Header fields select a plain 32 KiB
        // DMG ROM-only cartridge. The title and checksum make provenance and validation explicit.
        bytes[0x100] = 0x18
        bytes[0x101] = 0xfe.toByte()
        "COFFEE-CI-SMOKE".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x143] = 0
        bytes[0x146] = 0
        bytes[0x147] = 0
        bytes[0x148] = 0
        bytes[0x149] = 0
        var checksum = 0
        for (address in 0x134..0x14c) {
          checksum = (checksum - (bytes[address].toInt() and 0xff) - 1) and 0xff
        }
        bytes[0x14d] = checksum.toByte()
      }
}
