package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ReplayCompatibilityValidationTest {

  @Test
  fun exactIdentityRoundTripsAndEveryFieldHasAStructuredMismatch() {
    val configuration = StateCodecTestSupport.configuration()
    val identity = ReplayCompatibility.identity(configuration)
    ReplayCompatibility.validateIdentity(identity, configuration)

    val changedPrimary = identity.primaryRomSha256.also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertReason(ReplayCompatibilityReason.PRIMARY_ROM_MISMATCH) {
      ReplayCompatibility.validateIdentity(copyIdentity(identity, primary = changedPrimary), configuration)
    }
    assertReason(ReplayCompatibilityReason.HARDWARE_PROFILE_MISMATCH) {
      ReplayCompatibility.validateIdentity(
          copyIdentity(identity, profile = identity.canonicalProfileId + "-other"),
          configuration,
      )
    }
    assertReason(ReplayCompatibilityReason.CLOCK_MISMATCH) {
      ReplayCompatibility.validateIdentity(
          copyIdentity(
              identity,
              clocks =
                  ReplayClockIdentity(
                      ReplayClockRatio(
                          identity.clocks.ticksPerSecond.numerator + 1,
                          identity.clocks.ticksPerSecond.denominator,
                      ),
                      identity.clocks.controllerFramesPerSecond,
                  ),
          ),
          configuration,
      )
    }
    assertReason(ReplayCompatibilityReason.BOOTSTRAP_MISMATCH) {
      ReplayCompatibility.validateIdentity(
          copyIdentity(identity, bootstrap = identity.bootstrapFlags xor 0x10),
          configuration,
      )
    }
    assertReason(ReplayCompatibilityReason.BEHAVIOR_MISMATCH) {
      ReplayCompatibility.validateIdentity(
          copyIdentity(identity, behavior = identity.behaviorFlags xor 0x10),
          configuration,
      )
    }
  }

  @Test
  fun recordingRejectsSerialInfraredAndSensorInputsWithoutMutation() {
    val configuration = StateCodecTestSupport.configuration()
    Session(
            configuration,
            EventBusImpl(),
            null,
            ByteReceivingSerialEndpoint {},
        )
        .use { session ->
          assertReason(ReplayCompatibilityReason.UNSUPPORTED_SERIAL_PERIPHERAL) {
            ReplayCompatibility.validateRecording(session, ReplayInitialMode.EMBEDDED_SESSION_STATE)
          }
        }

    val infrared =
        object : InfraredEndpoint {
          override fun setLightOn(lightOn: Boolean) {}

          override fun isLightOn(): Boolean = false
        }
    Session(
            StateCodecTestSupport.configuration(),
            EventBusImpl(),
            null,
            infraredEndpoint = infrared,
        )
        .use { session ->
          assertReason(ReplayCompatibilityReason.UNSUPPORTED_INFRARED_ENDPOINT) {
            ReplayCompatibility.validateRecording(session, ReplayInitialMode.EMBEDDED_SESSION_STATE)
          }
        }

    val input = PlayerInputHub()
    input.openSource(2).update(setOf(Button.START))
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration().setPlayerInputSource(input),
        )
        .use { session ->
          session.gameboy.tick()
          assertReason(ReplayCompatibilityReason.UNSUPPORTED_INITIAL_PHYSICAL_INPUT) {
            ReplayCompatibility.validateRecording(session, ReplayInitialMode.EMBEDDED_SESSION_STATE)
          }
        }

    val sensorBytes = StateCodecTestSupport.rom().also { it[0x147] = 0x22 }
    val sensorConfiguration = StateCodecTestSupport.configuration(sensorBytes)
    assertReason(ReplayCompatibilityReason.UNSUPPORTED_SENSOR_CARTRIDGE) {
      ReplayCompatibility.validatePlayback(sensorConfiguration)
    }
    StateCodecTestSupport.session(
            sensorConfiguration,
        )
        .use { session ->
          assertReason(ReplayCompatibilityReason.UNSUPPORTED_SENSOR_CARTRIDGE) {
            ReplayCompatibility.validateRecording(session, ReplayInitialMode.EMBEDDED_SESSION_STATE)
          }
        }

    listOf(0xfe, 0xfd).forEach { cartridgeType ->
      val wallClockBytes =
          StateCodecTestSupport.rom().also { it[0x147] = cartridgeType.toByte() }
      assertReason(ReplayCompatibilityReason.UNSUPPORTED_WALL_CLOCK_CARTRIDGE) {
        ReplayCompatibility.validatePlayback(
            StateCodecTestSupport.configuration(wallClockBytes),
        )
      }
    }
  }

  @Test
  fun `recording requires accuracy until replay identity carries execution mode`() {
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration().setExecutionMode(ExecutionMode.PERFORMANCE))
        .use { session ->
          assertReason(ReplayCompatibilityReason.BEHAVIOR_MISMATCH) {
            ReplayCompatibility.validateRecording(session, ReplayInitialMode.EMBEDDED_SESSION_STATE)
          }
        }
  }

  @Test
  fun embeddedStateRequiresV2SessionIdentityAndEmptySerialPort() {
    val configuration = StateCodecTestSupport.configuration()
    StateCodecTestSupport.session(configuration).use { session ->
      val v2Bytes = StateCodec.encode(StateCodec.captureVersion2(session))
      val validated = ReplayCompatibility.validateEmbeddedState(v2Bytes, configuration)
      assertEquals(StateCodec.LATEST_FORMAT_VERSION, validated.formatVersion)

      val v1Bytes = StateCodec.encode(StateCodec.capture(session))
      assertReason(ReplayCompatibilityReason.STATE_FILE_VERSION_MISMATCH) {
        ReplayCompatibility.validateEmbeddedState(v1Bytes, configuration)
      }
      assertReason(ReplayCompatibilityReason.PRIMARY_ROM_MISMATCH) {
        ReplayCompatibility.validateEmbeddedState(
            v2Bytes,
            StateCodecTestSupport.configuration(StateCodecTestSupport.rom(seed = 9)),
        )
      }
      assertReason(ReplayCompatibilityReason.INVALID_EMBEDDED_STATE) {
        ReplayCompatibility.validateEmbeddedState(byteArrayOf(1, 2, 3), configuration)
      }
    }

    val endpoint = ByteReceivingSerialEndpoint {}
    StateCodecTestSupport.session(
            StateCodecTestSupport.configuration(),
            endpoint,
        )
        .use { serialSession ->
          val bytes = StateCodec.encode(StateCodec.captureVersion2(serialSession))
          assertReason(ReplayCompatibilityReason.UNSUPPORTED_SERIAL_PERIPHERAL) {
            ReplayCompatibility.validateEmbeddedState(bytes, serialSession.config)
          }
        }

    val rtcBytes = StateCodecTestSupport.rom().also { it[0x147] = 0x10 }
    StateCodecTestSupport.session(StateCodecTestSupport.configuration(rtcBytes)).use { rtcSession ->
      rtcSession.gameboy.setCartridgeClockPaused(true)
      assertReason(ReplayCompatibilityReason.UNSUPPORTED_PAUSED_RTC) {
        ReplayCompatibility.validateRecording(
            rtcSession,
            ReplayInitialMode.EMBEDDED_SESSION_STATE,
        )
      }
      val pausedState = StateCodec.encode(StateCodec.captureVersion2(rtcSession))
      assertReason(ReplayCompatibilityReason.UNSUPPORTED_PAUSED_RTC) {
        ReplayCompatibility.validateEmbeddedState(pausedState, rtcSession.config)
      }
    }
  }

  private fun copyIdentity(
      source: ReplayIdentity,
      primary: ByteArray = source.primaryRomSha256,
      profile: String = source.canonicalProfileId,
      clocks: ReplayClockIdentity = source.clocks,
      bootstrap: Long = source.bootstrapFlags,
      behavior: Long = source.behaviorFlags,
  ): ReplayIdentity =
      ReplayIdentity(
          primary,
          source.slotRomSha256,
          profile,
          clocks,
          bootstrap,
          behavior,
          source.replaySemanticsVersion,
          source.requiredStateFileVersion,
      )

  private fun assertReason(
      expected: ReplayCompatibilityReason,
      action: () -> Unit,
  ) {
    assertEquals(
        expected,
        assertFailsWith<ReplayCompatibilityException>(block = action).reason,
    )
  }
}
