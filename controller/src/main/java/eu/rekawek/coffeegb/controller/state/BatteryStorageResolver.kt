package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage
import java.nio.file.Path

data class ResolvedBatteryStorage(
    val primary: BatteryStorage?,
    val slot: BatteryStorage?,
)

/**
 * Resolves file-backed cartridge persistence from the same immutable ROM hashes used by state
 * storage.
 *
 * Blank/default Saves settings retain the released portable-JAR sidecar destination. Once a root
 * is configured, primary and pass-through-slot batteries live beside their other managed assets
 * at `<root>/games/<ROM SHA-256>/battery.sav`. Older roots and sidecars are import-only fallbacks;
 * originals are never deleted.
 */
object BatteryStorageResolver {

  fun configure(
      saves: ApplicationSettings.Saves,
      configuration: Gameboy.GameboyConfiguration,
      hashes: StateRomHashes,
  ): ResolvedBatteryStorage {
    val resolved =
        ResolvedBatteryStorage(
            storage(saves, configuration.rom, hashes.primaryRom),
            configuration.slotRom?.let { slot ->
              hashes.slotRom?.let { storage(saves, slot, it) }
            },
        )
    configuration.setBatteryStorage(resolved.primary, resolved.slot)
    return resolved
  }

  fun storage(
      saves: ApplicationSettings.Saves,
      rom: Rom,
      identity: RomIdentity,
  ): BatteryStorage? {
    val sidecar = rom.origin.persistencePath(".sav").orElse(null)
    val legacyArchive = rom.origin.legacyArchivePersistencePath(".sav").orElse(null)
    val defaultRoot =
        rom.origin.containerPath()
            .map { StateStorageResolver.defaultRoot(it) }
            .orElse(null)
    val configuredRoot = saves.directory?.let(StateStorageResolver::normalizeRoot)

    if (configuredRoot == null && sidecar == null) return null

    val active =
        if (configuredRoot == null) {
          BatteryStorage.Source.direct(sidecar)
        } else {
          managedSource(configuredRoot, identity)
        }
    val managedRoots =
        (saves.previousDirectories + listOfNotNull(defaultRoot))
            .asSequence()
            .map(StateStorageResolver::normalizeRoot)
            .filter { it != configuredRoot }
            .distinct()
            .take(ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES + 1)
            .map { managedSource(it, identity) }
            .toList()
    val imports =
        buildList {
              addAll(managedRoots)
              sidecar?.let { add(safeSidecarSource(it)) }
              legacyArchive?.let { add(safeSidecarSource(it)) }
            }
            .distinctBy { it.path() }
            .filter { it.path() != active.path() }
            .take(BatteryStorage.MAX_IMPORT_SOURCES)

    return BatteryStorage(active, imports)
  }

  private fun managedSource(root: Path, identity: RomIdentity): BatteryStorage.Source {
    val layout =
        StateStorageLayout(
            StateStorageResolver.gameDirectory(
                root,
                identity.hex(),
            ))
    return BatteryStorage.Source.managed(layout.batteryFile, root)
  }

  private fun safeSidecarSource(path: Path): BatteryStorage.Source {
    val normalized = path.toAbsolutePath().normalize()
    // A ROM may legally live directly below a filesystem root (`/game.gb`, `C:\game.gb`). The
    // direct source performs the same no-follow walk from that root and does not require inventing
    // an invalid managed root whose own parent/name is absent.
    return BatteryStorage.Source.direct(normalized)
  }
}
