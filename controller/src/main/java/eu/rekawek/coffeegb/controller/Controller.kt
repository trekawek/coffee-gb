package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.SystemProperties
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileIdentity
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.Bios
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.io.File

interface Controller : AutoCloseable {

  fun startController()

  fun closeWithState(): ControllerState?

  data class EmulationStartedEvent
  @JvmOverloads
  constructor(
      val romName: String,
      val origin: RomOrigin? = null,
      val openRequestId: Long? = null,
  ) : Event

  class EmulationStoppedEvent : Event

  data class LoadRomEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val state: MachineState? = null,
      val image: RomImage? = null,
      val openRequestId: Long? = null,
  ) : Event {
    constructor(
        image: RomImage,
        state: MachineState? = null,
        openRequestId: Long? = null,
    ) : this(
        image.origin().containerPath().map { it.toFile() }.orElse(File(image.origin().displayName())),
        state,
        image,
        openRequestId,
    )
  }

  data class RomLoadingEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val openRequestId: Long? = null,
  ) : Event

  data class RomLoadingCancelledEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val openRequestId: Long? = null,
  ) : Event

  data class LoadRomFailedEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val message: String,
      val openRequestId: Long? = null,
      val kind: RomLoadFailureKind = RomLoadFailureKind.CORE_STARTUP,
      val technicalDetails: String = message,
  ) : Event

  enum class RomLoadFailureKind {
    CORE_STARTUP,
    PERSISTENCE,
    INTERNAL,
  }

  /** Cancels only the matching user-facing open request; stale cancellation is a no-op. */
  data class CancelRomOpenEvent(val openRequestId: Long) : Event

  /**
   * A ROM replacement is paused at its persistence barrier. The old session remains alive until
   * the matching retry or cancel command is posted.
   */
  data class RomReplacementPersistenceFailedEvent
  @JvmOverloads
  constructor(
      val requestId: Long,
      val fileName: String,
      val message: String,
      val operation: PersistenceBarrierOperation = PersistenceBarrierOperation.ROM_REPLACEMENT,
      val openRequestId: Long? = null,
  ) : Event

  data class RetryRomReplacementEvent(val requestId: Long) : Event

  data class CancelRomReplacementEvent(val requestId: Long) : Event

  enum class PersistenceBarrierOperation {
    ROM_REPLACEMENT,
    STOP,
    RESET,
    CLOSE,
  }

  /**
   * A synchronous close did not release its session because persistence failed or timed out.
   * The caller must retain this controller and may invoke [closeWithState] again to retry.
   */
  class PersistenceBarrierException(
      val requestId: Long,
      val operation: PersistenceBarrierOperation,
      val fileName: String,
      message: String,
      cause: Throwable,
  ) : IllegalStateException(message, cause)

  class PauseEmulationEvent : Event

  class ResumeEmulationEvent : Event

  class ResetEmulationEvent : Event

  class StopEmulationEvent : Event

  data class SaveSnapshotEvent(val slot: Int) : Event

  data class RestoreSnapshotEvent(val slot: Int) : Event

  /** Emitted after a snapshot has been written successfully. */
  data class SnapshotSavedEvent(val slot: Int) : Event

  /** Emitted when a snapshot replacement fails; any previous slot remains recoverable. */
  data class SnapshotSaveFailedEvent(val slot: Int, val message: String) : Event

  /** Emitted after a snapshot has been restored successfully. */
  data class SnapshotRestoredEvent(val slot: Int) : Event

  /** Emitted when a snapshot is rejected or cannot be applied without changing the session. */
  data class SnapshotLoadFailedEvent(val slot: Int, val message: String) : Event

  data class SessionPauseSupportEvent(val enabled: Boolean) : Event

  data class SessionSnapshotSupportEvent(val snapshotSupport: SnapshotSupport?) : Event

  class UpdatedSystemMappingEvent : Event

  data class GameboyTypeEvent(val gameboyType: GameboyType) : Event

  /** Canonical stable profile identity for diagnostics and future replay metadata. */
  data class HardwareProfileEvent(
      val profile: HardwareProfile,
      val identity: HardwareProfileIdentity = profile.identity(),
  ) : Event

  /** Posted while the rewind key is held; the emulation plays backwards while active. */
  data class RewindEvent(val active: Boolean) : Event

  /** Connects or disconnects the Barcode Boy scanner on the link port (resets the game). */
  data class SetBarcodeBoyEvent(val enabled: Boolean) : Event

  /** Simulates swiping a card with the given 13-digit JAN-13 barcode on the Barcode Boy. */
  data class ScanBarcodeEvent(val barcode: String) : Event

  /** Connects or disconnects the Game Boy Printer on the link port (resets the game). */
  data class SetPrinterEvent(val enabled: Boolean) : Event

  /** Connects or disconnects a simulated Trimble GPS receiver on the CGB link port. */
  data class SetGpsReceiverEvent(val enabled: Boolean) : Event

  /**
   * Emitted each time the game prints a band on the Game Boy Printer. [argb] holds
   * [width]×[height] ARGB pixels (top row first, [width] is always 160). [topMargin] and
   * [bottomMargin] are the paper feed before/after the band in 1/16-tile units; a non-zero
   * [bottomMargin] ends the sheet.
   */
  class PrinterPrintEvent(
      val argb: IntArray,
      val width: Int,
      val height: Int,
      val topMargin: Int,
      val bottomMargin: Int,
      val exposure: Int,
  ) : Event

  data class ControllerState(val state: MachineState, val rom: Rom)

  companion object {
    fun createGameboyConfig(
      properties: EmulatorProperties,
      rom: Rom,
    ): Gameboy.GameboyConfiguration {
      val config = Gameboy.GameboyConfiguration(rom)
      val isDatel =
          rom.cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER)
      if (isDatel) {
        properties.applicationSettings.advanced.datelSlotRom?.let { path ->
          val file = path.toFile()
          if (file.isFile) {
            RomSourceSnapshot.open(path).use { source ->
              val image =
                  if (source.isArchive) {
                    val candidate =
                        source.candidates().firstOrNull()
                            ?: throw IllegalArgumentException(
                                "Configured Datel slot archive contains no ROM")
                    source.load(candidate.token())
                  } else {
                    source.loadSingle()
                  }
              config.setSlotRom(Rom(image))
            }
          }
        }
      }
      val hardwareProfile = getHardwareProfile(properties.system, rom)
      val bootstrapMode = properties.system.bootstrapMode
      require(bootstrapMode == Gameboy.BootstrapMode.SKIP || Bios.hasBundledBootRom(hardwareProfile)) {
        "Profile ${hardwareProfile.id()} has no bundled boot ROM; " +
            "select skip bootstrap before starting the session"
      }
      config.setHardwareProfile(hardwareProfile)
      config.setBootstrapMode(bootstrapMode)
      config.setSupportBatterySave(properties.saves.batterySavesEnabled)
      config.setPlayerInputSource(properties.playerInputSource)
      if (!config.hardwareProfile.capabilities().superGameboyBorder() ||
          !rom.isSuperGameboyFlag) {
        config.setDisplaySgbBorder(false)
      } else {
        config.setDisplaySgbBorder(properties.display.showSgbBorder)
      }

      return config
    }

    fun getHardwareProfile(properties: SystemProperties, rom: Rom): HardwareProfile {
      val colorSelection =
          rom.gameboyColorFlag == Rom.GameboyColorFlag.CGB ||
              rom.gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL ||
              rom.cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER)
      val persistedSelection =
          if (colorSelection) properties.cgbGamesSelection else properties.dmgGamesSelection
      val selected =
          properties.profileOverride
              ?: if (colorSelection) {
                if (properties.cgbGamesProfile.capabilities().superGameboyCommands() &&
                    !rom.isSuperGameboyFlag) {
                  HardwareProfileRegistry.CGB
                } else {
                  properties.cgbGamesProfile
                }
              } else {
                properties.dmgGamesProfile
              }
      return if (
          selected == HardwareProfileRegistry.CGB &&
              properties.profileOverride == null &&
              persistedSelection is ApplicationSettings.ProfileSelection.Auto &&
              rom.cartridgeProperties.has(CartridgeProperties.Feature.CGB0_REVISION)
      ) {
        HardwareProfileRegistry.CGB0
      } else {
        selected
      }
    }

    /** @deprecated Use getHardwareProfile. */
    @Deprecated("Use getHardwareProfile")
    fun getGameboyType(properties: SystemProperties, rom: Rom): GameboyType {
      return GameboyType.fromHardwareProfile(getHardwareProfile(properties, rom))
    }
  }
}
