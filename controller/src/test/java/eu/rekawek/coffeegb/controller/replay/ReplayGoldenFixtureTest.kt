package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ReplayGoldenFixtureTest {

  @Test
  fun committedV1FixtureInspectsRoundTripsAndPlaysDeterministically() {
    if (java.lang.Boolean.getBoolean(UPDATE_PROPERTY)) {
      updateFixtureAndStop()
    }

    val bytes =
        checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)).use { it.readBytes() }
    assertEquals(EXPECTED_SIZE, bytes.size)
    assertEquals(EXPECTED_SHA256, sha256(bytes))

    // Inspection is deliberately first: it needs neither ROM bytes nor a live emulator.
    val inspection = ReplayCodec.inspect(bytes)
    assertEquals(EXPECTED_INSPECTION, inspection.render())
    assertTrue(inspection.checksumValid)
    assertNull(inspection.identity.slotRomSha256)
    assertEquals(ReplayInitialMode.BOOT_REFERENCE, inspection.initialConditions.mode)
    assertEquals(false, inspection.hasEmbeddedState)

    val replay = ReplayCodec.decode(bytes)
    assertContentEquals(bytes, ReplayCodec.encode(replay))

    ReplayPlayer.open(replay, ReplayGoldenFixture.configuration(PlayerInputSource.RELEASED)).use {
        player ->
      val executionBudget =
          replay.checkpoints.last().tick - replay.initialConditions.initialTick + 1L
      val completed =
          assertIs<ReplayPlaybackStatus.Completed>(player.playToEnd(executionBudget))
      assertEquals(replay.checkpoints.last().tick, completed.position.tick)
      assertEquals(replay.checkpoints.last().frame, completed.position.frame)
    }
  }

  private fun updateFixtureAndStop(): Nothing {
    val bytes = ReplayGoldenFixture.create()
    val path = Path.of("src/test/resources/replay-v1/synthetic-input.cgbreplay")
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
    throw AssertionError(
        buildString {
          appendLine("Updated $path")
          appendLine("size=${bytes.size}")
          appendLine("sha256=${sha256(bytes)}")
          appendLine("synthetic-rom-sha256=${sha256(ReplayGoldenFixture.syntheticRom())}")
          append(ReplayCodec.inspect(bytes).render())
        })
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes)
          .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private companion object {
    const val RESOURCE_PATH = "/replay-v1/synthetic-input.cgbreplay"
    const val UPDATE_PROPERTY = "coffeeGb.updateReplayGolden"
    const val EXPECTED_SIZE = 973
    const val EXPECTED_SHA256 =
        "1699d15e8cee45d7cc117838fb1b3a57a51f72d1e6dae16f6c985d4a69862ca2"
    val EXPECTED_INSPECTION =
        """
        magic=CGBR format=1 checksum=true
        required-features=0x0 optional-features=0x0 payload=901 decoded-sections=846 profile="dmg"
        initial=BOOT_REFERENCE tick=0 frame=0 rtc=946684800000
        inputs=7 checkpoints=2 final-tick=69911 final-frame=1 embedded-state=false
        producer="coffee-gb-test/replay-v1" created=1700000123456 note="repository-owned synthetic input timeline"
        section=1 version=1 required=true compression=NONE encoded=92 decoded=92
        section=2 version=1 required=true compression=NONE encoded=28 decoded=28
        section=3 version=1 required=true compression=DEFLATE encoded=43 decoded=90
        section=4 version=1 required=true compression=DEFLATE encoded=532 decoded=550
        section=5 version=1 required=false compression=NONE encoded=86 decoded=86
        """
            .trimIndent() + "\n"
  }
}

/** Repository-owned synthetic ROM and deterministic input provenance for the v1 golden fixture. */
private object ReplayGoldenFixture {
  private const val RTC_EPOCH_MILLIS = 946_684_800_000L
  private const val CREATED_AT_MILLIS = 1_700_000_123_456L
  private const val TAIL_TICKS = 7

  fun create(): ByteArray {
    val input = PlayerInputHub()
    val configuration = configuration(input)
    input.openSource(0).use { p1 ->
      input.openSource(1).use { p2 ->
        Session(configuration, EventBusImpl(null, null, false), null).use { session ->
          ReplayRecorder.start(
                  session,
                  ReplayRecordingOptions(
                      checkpointIntervalFrames = 1,
                      rtcEpochMillis = RTC_EPOCH_MILLIS,
                      metadata =
                          ReplayMetadata(
                              producerVersion = "coffee-gb-test/replay-v1",
                              createdAtEpochMillis = CREATED_AT_MILLIS,
                              note = "repository-owned synthetic input timeline",
                          ),
                  ),
              )
              .use { recorder ->
                session.eventBus.post(ButtonPressEvent(Button.A))
                p1.update(setOf(Button.LEFT))
                recorder.tick()

                session.eventBus.post(ButtonReleaseEvent(Button.A))
                p1.update(setOf(Button.LEFT, Button.START))
                p2.update(setOf(Button.B))
                recorder.tick()

                p1.update(emptySet())
                p2.update(emptySet())
                recorder.tick()

                val ticksToFirstFrame =
                    session.gameboy.clockSpec.controllerTicksPerFrame() - recorder.tickCount.toInt()
                repeat(ticksToFirstFrame) { recorder.tick() }
                repeat(TAIL_TICKS) { recorder.tick() }
                return ReplayCodec.encode(recorder.finish())
              }
        }
      }
    }
  }

  fun configuration(input: PlayerInputSource): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(syntheticRom()))
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
          .setSupportBatterySave(false)
          .setBatteryStorage(null, null)
          .setPlayerInputSource(input)

  fun syntheticRom(): ByteArray =
      ByteArray(0x8000).also { bytes ->
        "CGBR-TEST".forEachIndexed { index, character ->
          bytes[0x134 + index] = character.code.toByte()
        }
        bytes[0x100] = 0x00
        bytes[0x101] = 0x18
        bytes[0x102] = 0xfd.toByte()
        bytes[0x143] = 0
        bytes[0x146] = 0
        bytes[0x147] = 0
        bytes[0x148] = 0
        bytes[0x149] = 0
        var headerChecksum = 0
        for (address in 0x134..0x14c) {
          headerChecksum = (headerChecksum - (bytes[address].toInt() and 0xff) - 1) and 0xff
        }
        bytes[0x14d] = headerChecksum.toByte()
      }
}
