package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicReference
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

/**
 * Small, redistributable end-to-end diagnostic used against the actual packaged launcher.
 *
 * The ROM is generated from reviewable instructions and placed in a private temporary 7z archive
 * so the packaged launcher also proves its isolated archive helper can run. Generated ROM bytes
 * remain confined to temporary files which are deleted after extraction. One deterministic
 * machine exercises the extracted bytes, video and audio publication, live input sampling, and the
 * existing portable StateFile codec. It does not construct Swing, inspect a user's files, enable
 * battery persistence, or package or upload the generated archive.
 */
object PackageRuntimeSmoke {

  private const val MAX_TICKS = 250_000

  private const val ARCHIVE_ENTRY = "generated/coffee-gb-package-smoke.gb"

  data class Result(
      val ticks: Int,
      val videoFrames: Int,
      val audioBuffers: Int,
      val stateBytes: Int,
      val nativeTarget: String,
  )

  fun run(nativeTarget: String = "portable"): Result {
    require(nativeTarget == "portable" || nativeTarget.isNotBlank()) {
      "Package smoke native target evidence must not be blank"
    }
    val pressed =
        PlayerInputSnapshot.of(
            listOf(setOf(Button.A), emptySet(), emptySet(), emptySet()),
        )
    val input = AtomicReference(pressed)
    val image = loadSyntheticSevenZ()
    val configuration =
        Gameboy.GameboyConfiguration(Rom(image))
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
      return Result(ticks, videoFrames, audioBuffers, state.size, nativeTarget)
    } finally {
      gameboy.close()
      bus.close()
    }
  }

  private fun loadSyntheticSevenZ(): RomImage {
    val expected = syntheticPackageRom()
    val archive = createPrivateTemporaryArchive()
    var failure: Throwable? = null
    try {
      SevenZOutputFile(archive.toFile()).use { output ->
        output.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2)))
        val entry =
            SevenZArchiveEntry().apply {
              name = ARCHIVE_ENTRY
              size = expected.size.toLong()
            }
        output.putArchiveEntry(entry)
        output.write(expected)
        output.closeArchiveEntry()
      }

      RomSourceSnapshot.open(archive).use { snapshot ->
        check(snapshot.candidates().size == 1) {
          "Synthetic 7z did not expose exactly one ROM candidate"
        }
        val image = snapshot.loadSingle()
        check(image.bytes().contentEquals(expected)) {
          "Synthetic 7z extraction changed the ROM bytes"
        }
        check(image.origin().kind() == RomOrigin.Kind.ARCHIVE_ENTRY) {
          "Synthetic 7z did not retain an archive-entry origin"
        }
        check(image.origin().containerPath().orElseThrow() == archive.toAbsolutePath().normalize()) {
          "Synthetic 7z origin did not retain the exact container path"
        }
        check(image.origin().archiveEntry().orElseThrow() == ARCHIVE_ENTRY) {
          "Synthetic 7z origin did not retain the exact entry name"
        }
        check(image.origin().archiveEntryOccurrence() == 0) {
          "Synthetic 7z origin did not retain the exact entry occurrence"
        }
        return image
      }
    } catch (problem: Throwable) {
      failure = problem
      throw problem
    } finally {
      try {
        Files.deleteIfExists(archive)
      } catch (cleanupFailure: IOException) {
        archive.toFile().deleteOnExit()
        failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
      }
    }
  }

  private fun createPrivateTemporaryArchive(): Path {
    val permissions =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    return try {
      Files.createTempFile("coffee-gb-package-smoke-", ".7z", permissions)
    } catch (_: UnsupportedOperationException) {
      // Windows providers do not accept POSIX attributes. Their temporary-file implementation
      // still creates a new unpredictable path owned by the current process account.
      Files.createTempFile("coffee-gb-package-smoke-", ".7z")
    }
  }

}

/**
 * Reviewable, redistributable ROM bytes shared by the in-process runtime smoke and the installed
 * file-association smoke. The fixture contains no third-party game code or assets.
 */
internal fun syntheticPackageRom(): ByteArray =
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
