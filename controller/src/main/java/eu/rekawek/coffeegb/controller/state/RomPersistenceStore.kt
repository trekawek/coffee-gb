package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import java.nio.file.Path

/**
 * Host-owned persistence for one emulation session.
 *
 * The controller never derives a writable location from a pathless ROM. Desktop callers use
 * [DesktopRomPersistenceStore], while hosts such as Android can supply an app-private,
 * hash-keyed implementation before the machine is built.
 */
fun interface RomPersistenceStore {
  fun resolve(
      configuration: Gameboy.GameboyConfiguration,
      hashes: StateRomHashes,
  ): SessionPersistence
}

/** State and battery stores resolved atomically for the exact primary ROM identity. */
data class SessionPersistence(
    /** Null only for legacy in-memory desktop callers that supplied no writable host store. */
    val stateStore: StateStore?,
    val primaryBatteryStore: BatteryStore?,
    val slotBatteryStore: BatteryStore?,
) {
  fun applyTo(configuration: Gameboy.GameboyConfiguration) {
    configuration.setBatteryStorage(
        primaryBatteryStore?.storage(),
        slotBatteryStore?.storage(),
    )
  }
}

/** Portable state-store contract; concrete stores choose their own safe backing location. */
interface StateStore {
  val layout: StateStorageLayout

  fun repository(): StateRepository
}

/** Portable battery-store contract retained independently from the state store. */
fun interface BatteryStore {
  fun storage(): BatteryStorage
}

/** Existing NIO/desktop state repository exposed through the portable state-store contract. */
class FileStateStore(
    override val layout: StateStorageLayout,
) : StateStore {
  override fun repository(): StateRepository = StateRepository(layout)
}

/** Existing desktop save policy retained as the default controller adapter. */
class DesktopRomPersistenceStore(
    private val saves: ApplicationSettings.Saves,
    private val primaryBatteryPath: Path? = null,
) : RomPersistenceStore {
  override fun resolve(
      configuration: Gameboy.GameboyConfiguration,
      hashes: StateRomHashes,
  ): SessionPersistence {
    val stateStore =
        configuration.rom.file?.let { FileStateStore(StateStorageResolver.resolve(saves, configuration).layout) }
    val batteries = BatteryStorageResolver.resolve(saves, configuration, hashes)
    val primaryBattery = primaryBatteryPath?.let(BatteryStorage::direct) ?: batteries.primary
    return SessionPersistence(
        stateStore,
        primaryBattery?.let { battery -> BatteryStore { battery } },
        batteries.slot?.let { battery -> BatteryStore { battery } },
    )
  }
}
