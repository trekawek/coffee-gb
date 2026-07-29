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

  /**
   * Waives only a completed/unavailable managed close-autosave barrier retained by this controller.
   * In-flight writers are never waivable, and stale request IDs are rejected.
   */
  fun waiveCloseAutosave(requestId: Long): Boolean = false

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
      val closeAutosaveWaivable: Boolean = false,
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

  /** Applies the validated Saves preference section at the next controller frame boundary. */
  data class UpdatedSavesSettingsEvent(val saves: ApplicationSettings.Saves) : Event

  data class GameboyTypeEvent(val gameboyType: GameboyType) : Event

  /** Canonical stable profile identity for diagnostics and future replay metadata. */
  data class HardwareProfileEvent(
      val profile: HardwareProfile,
      val identity: HardwareProfileIdentity = profile.identity(),
  ) : Event

  /** Posted while the rewind key is held; the emulation plays backwards while active. */
  data class RewindEvent(val active: Boolean) : Event

  /**
   * The exclusive device selected for a standalone Game Boy link port.
   *
   * [PEER_TO_PEER] preserves the historical default: an unconnected link cable endpoint that can
   * later be paired by a linked controller. [NONE] models a physically empty port. Mobile Adapter
   * backend availability is reported separately through [SerialPeripheralStatusEvent].
   */
  enum class SerialPeripheralSelection {
    NONE,
    PRINTER,
    BARCODE_BOY,
    GPS_RECEIVER,
    MOBILE_ADAPTER_GB,
    PEER_TO_PEER,
  }

  /** Selects exactly one standalone link-port peripheral at the next controller safe point. */
  data class SetSerialPeripheralEvent(val selection: SerialPeripheralSelection) : Event

  /** Emitted after a commit and when a newly active session reasserts its authoritative choice. */
  data class SerialPeripheralSelectionChangedEvent(val selection: SerialPeripheralSelection) :
      Event

  enum class SerialPeripheralStatus {
    /** The choice is retained, but no emulation session currently owns an endpoint. */
    DETACHED,

    /** The selected endpoint is installed in the active standalone emulation session. */
    ATTACHED,

    /** The requested endpoint could not be prepared; the previous selection remains active. */
    UNAVAILABLE,
  }

  /**
   * Stable, presentation-safe peripheral failures. These values intentionally carry no exception
   * text, path, payload, host, account, or configuration bytes.
   */
  enum class SerialPeripheralError(
      val code: String,
      val userMessage: String,
  ) {
    ENDPOINT_UNAVAILABLE(
        "ENDPOINT_UNAVAILABLE",
        "The selected serial peripheral is not available in this configuration.",
    ),
    CONFIGURATION_INVALID(
        "CONFIGURATION_INVALID",
        "The selected serial peripheral configuration is invalid.",
    ),
    STORAGE_FAILED(
        "STORAGE_FAILED",
        "The selected serial peripheral configuration could not be loaded.",
    ),
    PORT_OWNED_BY_LINK(
        "PORT_OWNED_BY_LINK",
        "Stop the active network link before selecting a standalone serial peripheral.",
    ),
  }

  /** Current attachment state for a requested or committed standalone serial peripheral. */
  data class SerialPeripheralStatusEvent(
      val selection: SerialPeripheralSelection,
      val status: SerialPeripheralStatus,
      val error: SerialPeripheralError? = null,
  ) : Event {
    init {
      require((status == SerialPeripheralStatus.UNAVAILABLE) == (error != null)) {
        "Unavailable serial status and typed error must be present together"
      }
    }
  }

  /** Immutable, defensively copied configuration supplied to an offline Mobile Adapter. */
  class MobileAdapterConfiguration(
      val deviceId: Int,
      configuration: ByteArray,
  ) {
    private val configuration = configuration.clone()

    init {
      require(deviceId in 0..0x7f) { "Mobile Adapter device ID must fit in seven bits" }
      require(this.configuration.size == MOBILE_ADAPTER_CONFIGURATION_BYTES) {
        "Mobile Adapter configuration must contain $MOBILE_ADAPTER_CONFIGURATION_BYTES bytes"
      }
    }

    fun copyBytes(): ByteArray = configuration.clone()

    companion object {
      const val MOBILE_ADAPTER_CONFIGURATION_BYTES = 256

      /**
       * Fresh deterministic Phase-351 offline defaults: device 08, the documented MA header,
       * zero-filled private area, and the complete 00..7f public test pattern.
       */
      @JvmStatic
      fun syntheticOffline(): MobileAdapterConfiguration {
        val bytes = ByteArray(MOBILE_ADAPTER_CONFIGURATION_BYTES)
        bytes[0] = 0x4d
        bytes[1] = 0x41
        bytes[2] = 0x81.toByte()
        for (index in 128 until MOBILE_ADAPTER_CONFIGURATION_BYTES) {
          bytes[index] = (index - 128).toByte()
        }
        return MobileAdapterConfiguration(0x08, bytes)
      }
    }
  }

  /**
   * Synchronous preparation seam for already-local bounded configuration. Implementations must not
   * perform network I/O and should map durable-store failures to [SerialPeripheralPreparationException].
   */
  fun interface MobileAdapterConfigurationProvider {
    fun load(): MobileAdapterConfiguration
  }

  /** Typed provider failure whose public surface cannot carry raw storage or configuration data. */
  class SerialPeripheralPreparationException(val error: SerialPeripheralError) :
      IllegalStateException(error.code)

  /**
   * Legacy adapter for selecting the Barcode Boy. Disabling it only clears the port when Barcode
   * Boy still owns the exclusive selection.
   */
  data class SetBarcodeBoyEvent(val enabled: Boolean) : Event

  /** Simulates swiping a card with the given 13-digit JAN-13 barcode on the Barcode Boy. */
  data class ScanBarcodeEvent(val barcode: String) : Event

  /** Legacy ownership-aware adapter for selecting the Game Boy Printer. */
  data class SetPrinterEvent(val enabled: Boolean) : Event

  /** Legacy ownership-aware adapter for selecting a simulated Trimble GPS receiver. */
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
