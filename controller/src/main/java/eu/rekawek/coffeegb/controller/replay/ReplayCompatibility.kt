package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.HardwareProfile
import eu.rekawek.coffeegb.controller.state.MachineIdentity
import eu.rekawek.coffeegb.controller.state.NoSerialRuntimeState
import eu.rekawek.coffeegb.controller.state.NullState
import eu.rekawek.coffeegb.controller.state.SerialPeripheralState
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateBootstrapMode
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint

/** Read-only replay preflight. Every check completes before a player may mutate a session. */
object ReplayCompatibility {

  const val BOOTSTRAP_NORMAL: Long = 1L shl 0
  const val BOOTSTRAP_FAST_FORWARD: Long = 1L shl 1
  const val BOOTSTRAP_SKIP: Long = 1L shl 2

  const val BEHAVIOR_MEALYBUG_DMG_BLOB: Long = 1L shl 0
  const val BEHAVIOR_CODEBREAKER_RUMBLE: Long = 1L shl 1
  const val BEHAVIOR_DISPLAY_SGB_BORDER: Long = 1L shl 2

  /** Builds the exact portable replay identity for one configuration. */
  fun identity(configuration: Gameboy.GameboyConfiguration): ReplayIdentity {
    val machine = StateIdentity.from(configuration)
    val clock = configuration.clockSpec
    return ReplayIdentity(
        machine.primaryRom.copyBytes(),
        machine.slotRom?.copyBytes(),
        machine.profile.canonicalProfileId,
        ReplayClockIdentity(
            ReplayClockRatio(
                clock.ticksPerSecondNumerator(),
                clock.ticksPerSecondDenominator(),
            ),
            ReplayClockRatio(
                clock.controllerFramesPerSecondNumerator(),
                clock.controllerFramesPerSecondDenominator(),
            ),
        ),
        bootstrapFlags(machine.profile.bootstrapMode),
        behaviorFlags(machine.profile),
    )
  }

  /** Validates all replay identity fields without constructing or mutating a machine. */
  fun validateIdentity(
      replayIdentity: ReplayIdentity,
      configuration: Gameboy.GameboyConfiguration,
  ) {
    if (replayIdentity.replaySemanticsVersion != ReplayIdentity.REPLAY_SEMANTICS_VERSION) {
      incompatible(
          ReplayCompatibilityReason.REPLAY_SEMANTICS_MISMATCH,
          "Replay semantics version is not supported",
      )
    }
    if (replayIdentity.requiredStateFileVersion != StateCodec.LATEST_FORMAT_VERSION) {
      incompatible(
          ReplayCompatibilityReason.STATE_FILE_VERSION_MISMATCH,
          "Replay requires an unsupported StateFile version",
      )
    }
    val expected = identity(configuration)
    if (!replayIdentity.primaryRomSha256.contentEquals(expected.primaryRomSha256)) {
      incompatible(
          ReplayCompatibilityReason.PRIMARY_ROM_MISMATCH,
          "Replay primary ROM does not match the selected cartridge",
      )
    }
    if (!nullableContentEquals(replayIdentity.slotRomSha256, expected.slotRomSha256)) {
      incompatible(
          ReplayCompatibilityReason.SLOT_ROM_MISMATCH,
          "Replay pass-through slot ROM does not match the selected cartridge",
      )
    }
    if (replayIdentity.canonicalProfileId != expected.canonicalProfileId) {
      incompatible(
          ReplayCompatibilityReason.HARDWARE_PROFILE_MISMATCH,
          "Replay hardware profile does not match the selected profile",
      )
    }
    if (replayIdentity.clocks != expected.clocks) {
      incompatible(
          ReplayCompatibilityReason.CLOCK_MISMATCH,
          "Replay clock identity does not match the selected profile",
      )
    }
    if (replayIdentity.bootstrapFlags != expected.bootstrapFlags) {
      incompatible(
          ReplayCompatibilityReason.BOOTSTRAP_MISMATCH,
          "Replay bootstrap behavior does not match the selected configuration",
      )
    }
    if (replayIdentity.behaviorFlags != expected.behaviorFlags) {
      incompatible(
          ReplayCompatibilityReason.BEHAVIOR_MISMATCH,
          "Replay behavior flags do not match the selected configuration",
      )
    }
  }

  /** Rejects cartridges whose live host/sensor inputs are outside replay-v1's capture model. */
  fun validatePlayback(configuration: Gameboy.GameboyConfiguration) {
    validateCartridges(configuration)
  }

  /** Rejects live services and sensor cartridges outside deterministic replay-v1's scope. */
  fun validateRecording(session: Session, initialMode: ReplayInitialMode) {
    validateCartridges(session.config)
    if (session.serialEndpoint !== SerialEndpoint.NULL_ENDPOINT) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_SERIAL_PERIPHERAL,
          "Replay recording requires an empty serial port",
      )
    }
    if (session.infraredEndpoint !== InfraredEndpoint.NULL_ENDPOINT) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_INFRARED_ENDPOINT,
          "Replay recording requires an empty infrared port",
      )
    }
    if (session.gameboy.sampledPlayerInput != PlayerInputSnapshot.released()) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_INITIAL_PHYSICAL_INPUT,
          "Replay recording requires released latched physical input",
      )
    }
    if (initialMode == ReplayInitialMode.BOOT_REFERENCE && session.heldButtons.isNotEmpty()) {
      incompatible(
          ReplayCompatibilityReason.BEHAVIOR_MISMATCH,
          "Boot-reference recording requires released legacy buttons",
      )
    }
    if (session.gameboy.hasPausedCartridgeRtc()) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_PAUSED_RTC,
          "Replay recording cannot begin while a cartridge RTC is at a host-time pause boundary",
      )
    }
  }

  /**
   * Decodes and validates a sensitive embedded initial state's portable identity and service
   * contract without applying it. Target-dependent shape is checked later against the isolated
   * replay session before the state adapter's first mutation.
   */
  fun validateEmbeddedState(
      bytes: ByteArray,
      configuration: Gameboy.GameboyConfiguration,
  ): StateFile {
    validateCartridges(configuration)
    val file =
        try {
          StateCodec.decodeNetworkState(bytes)
        } catch (_: StateDecodeException) {
          incompatible(
              ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
              "Replay embedded StateFile is malformed",
          )
        } catch (_: IllegalArgumentException) {
          incompatible(
              ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
              "Replay embedded StateFile is invalid",
          )
        }
    if (file.formatVersion != StateCodec.LATEST_FORMAT_VERSION) {
      incompatible(
          ReplayCompatibilityReason.STATE_FILE_VERSION_MISMATCH,
          "Replay embedded state must use StateFile v${StateCodec.LATEST_FORMAT_VERSION}",
      )
    }
    val root =
        file.root as? SessionStateRoot
            ?: incompatible(
                ReplayCompatibilityReason.UNSUPPORTED_STATE_ROOT,
                "Replay embedded state must contain one session",
            )
    val identity =
        file.identities.singleOrNull()?.takeIf { it.player == 0 }?.identity
            ?: incompatible(
                ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
                "Replay embedded state must contain exactly one P1 identity",
            )
    validateEmbeddedIdentity(identity, StateIdentity.from(configuration))
    val session = root.session
    if (session.serialPeripheral != SerialPeripheralState.NONE) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_SERIAL_PERIPHERAL,
          "Replay embedded state contains a serial peripheral",
      )
    }
    if (session.serialState !== NullState || session.serialRuntime !== NoSerialRuntimeState) {
      incompatible(
          ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
          "Replay embedded empty serial port has non-empty state",
      )
    }
    if (session.machine.rtcRuntime.primary?.emulationPaused == true ||
        session.machine.rtcRuntime.slot?.emulationPaused == true) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_PAUSED_RTC,
          "Replay embedded state contains a host-time RTC pause boundary",
      )
    }
    return file
  }

  /**
   * Atomically prepares and applies the already decoded state to the isolated replay session.
   * StateCodec completes target validation and reconstruction before its first mutation;
   * state-layer failures retain one stable replay compatibility type.
   */
  internal fun applyEmbeddedState(file: StateFile, session: Session) {
    try {
      val identity = StateIdentity.from(session.config)
      StateCodec.applyDecoded(file, session, identity)
    } catch (failure: StateDecodeException) {
      incompatible(
          ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
          "Replay embedded StateFile is incompatible with the isolated session",
          failure,
      )
    } catch (failure: IllegalArgumentException) {
      incompatible(
          ReplayCompatibilityReason.INVALID_EMBEDDED_STATE,
          "Replay embedded StateFile has invalid target-dependent state",
          failure,
      )
    }
  }

  private fun validateEmbeddedIdentity(actual: MachineIdentity, expected: MachineIdentity) {
    if (actual.primaryRom != expected.primaryRom) {
      incompatible(
          ReplayCompatibilityReason.PRIMARY_ROM_MISMATCH,
          "Embedded state primary ROM does not match the selected cartridge",
      )
    }
    if (actual.slotRom != expected.slotRom) {
      incompatible(
          ReplayCompatibilityReason.SLOT_ROM_MISMATCH,
          "Embedded state slot ROM does not match the selected cartridge",
      )
    }
    val actualProfile = actual.profile
    val expectedProfile = expected.profile
    if (actualProfile.canonicalProfileId != expectedProfile.canonicalProfileId ||
        actualProfile.hardware != expectedProfile.hardware ||
        actualProfile.cgb0Revision != expectedProfile.cgb0Revision) {
      incompatible(
          ReplayCompatibilityReason.HARDWARE_PROFILE_MISMATCH,
          "Embedded state hardware profile does not match the selected profile",
      )
    }
    if (actualProfile.bootstrapMode != expectedProfile.bootstrapMode) {
      incompatible(
          ReplayCompatibilityReason.BOOTSTRAP_MISMATCH,
          "Embedded state bootstrap behavior does not match the selected configuration",
      )
    }
    if (behaviorFlags(actualProfile) != behaviorFlags(expectedProfile)) {
      incompatible(
          ReplayCompatibilityReason.BEHAVIOR_MISMATCH,
          "Embedded state behavior does not match the selected configuration",
      )
    }
  }

  private fun validateCartridges(configuration: Gameboy.GameboyConfiguration) {
    rejectSensorCartridge(configuration.rom)
    configuration.slotRom?.let(::rejectSensorCartridge)
  }

  private fun rejectSensorCartridge(rom: Rom) {
    val type = rom.type
    if (type.isHuc3 || type.isTama5) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_WALL_CLOCK_CARTRIDGE,
          "Replay v1 does not support cartridge mappers that read host wall time",
      )
    }
    val pocketCamera =
        type.isPocketCamera ||
            rom.cartridgeProperties.mapper == CartridgeProperties.Mapper.POCKET_CAMERA
    if (pocketCamera || type.isMbc7) {
      incompatible(
          ReplayCompatibilityReason.UNSUPPORTED_SENSOR_CARTRIDGE,
          "Replay v1 does not record cartridge sensor input",
      )
    }
  }

  private fun bootstrapFlags(mode: StateBootstrapMode): Long =
      when (mode) {
        StateBootstrapMode.NORMAL -> BOOTSTRAP_NORMAL
        StateBootstrapMode.FAST_FORWARD -> BOOTSTRAP_FAST_FORWARD
        StateBootstrapMode.SKIP -> BOOTSTRAP_SKIP
      }

  private fun behaviorFlags(profile: HardwareProfile): Long {
    var result = 0L
    if (profile.mealybugDmgBlob) result = result or BEHAVIOR_MEALYBUG_DMG_BLOB
    if (profile.codeBreakerRumble) result = result or BEHAVIOR_CODEBREAKER_RUMBLE
    if (profile.displaySgbBorder) result = result or BEHAVIOR_DISPLAY_SGB_BORDER
    return result
  }

  private fun nullableContentEquals(left: ByteArray?, right: ByteArray?): Boolean =
      if (left == null || right == null) left == null && right == null
      else left.contentEquals(right)

  private fun incompatible(
      reason: ReplayCompatibilityReason,
      message: String,
      cause: Throwable? = null,
  ): Nothing = throw ReplayCompatibilityException(reason, message, cause)
}
