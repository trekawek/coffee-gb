package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class BatteryStorageResolverTest {

  @Test
  fun `configured root owns collision-safe primary and slot batteries with durable fallbacks`() {
    val directory = Files.createTempDirectory("battery-resolver")
    val romDirectory = Files.createDirectories(directory.resolve("roms"))
    val primary =
        Rom(
            StateCodecTestSupport.datelRom(),
            romDirectory.resolve("same-name.gb").toFile(),
        )
    val slot =
        Rom(
            StateCodecTestSupport.rom(seed = 9),
            romDirectory.resolve("slot").resolve("same-name.gb").also {
              Files.createDirectories(it.parent)
            }.toFile(),
        )
    val configuration =
        Gameboy.GameboyConfiguration(primary)
            .setSlotRom(slot)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
    val hashes = StateIdentity.hashes(configuration)
    val active = Files.createDirectory(directory.resolve("active"))
    val previous = Files.createDirectory(directory.resolve("previous"))
    val saves =
        ApplicationSettings.Saves(
            directory = active,
            previousDirectories = listOf(previous),
        )

    val resolved = BatteryStorageResolver.configure(saves, configuration, hashes)

    val primaryTarget =
        active.resolve("games").resolve(hashes.primaryRom.hex()).resolve("battery.sav")
    val slotHash = requireNotNull(hashes.slotRom)
    val slotTarget = active.resolve("games").resolve(slotHash.hex()).resolve("battery.sav")
    assertEquals(primaryTarget, resolved.primary?.targetPath())
    assertEquals(slotTarget, resolved.slot?.targetPath())
    assertNotEquals(resolved.primary?.targetPath(), resolved.slot?.targetPath())
    assertFalse(primaryTarget.toString().contains("same-name"))
    assertEquals(
        listOf(
            previous.resolve("games").resolve(hashes.primaryRom.hex()).resolve("battery.sav"),
            romDirectory
                .resolve(".coffee-gb")
                .resolve("games")
                .resolve(hashes.primaryRom.hex())
                .resolve("battery.sav"),
            romDirectory.resolve("same-name.sav"),
        ),
        resolved.primary?.importSources()?.map { it.path() },
    )
    assertTrue(resolved.primary!!.importSources().all { it.managedRoot().isPresent })
    assertSame(resolved.primary, configuration.batteryStorage)
    assertSame(resolved.slot, configuration.slotBatteryStorage)
    assertSame(configuration.batteryStorage, configuration.forRestore().batteryStorage)
  }

  @Test
  fun `blank saves setting preserves portable sidecar destination and can return from old root`() {
    val directory = Files.createTempDirectory("battery-portable")
    val romPath = directory.resolve("portable.gbc")
    val rom = Rom(StateCodecTestSupport.rom(seed = 3), romPath.toFile())
    val identity = StateIdentity.hash(rom)
    val previous = Files.createDirectory(directory.resolve("old-configured"))

    val storage =
        BatteryStorageResolver.storage(
            ApplicationSettings.Saves(previousDirectories = listOf(previous)),
            rom,
            identity,
        )

    assertEquals(directory.resolve("portable.sav"), storage?.targetPath())
    assertEquals(
        listOf(
            previous.resolve("games").resolve(identity.hex()).resolve("battery.sav"),
            directory
                .resolve(".coffee-gb")
                .resolve("games")
                .resolve(identity.hex())
                .resolve("battery.sav"),
        ),
        storage?.importSources()?.map { it.path() },
    )
    assertTrue(storage!!.importSources().size <=
        eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage.MAX_IMPORT_SOURCES)
  }

  @Test
  fun `configured archive battery retains exact-entry sidecar and unambiguous legacy imports`() {
    val directory = Files.createTempDirectory("battery-archive")
    val archive = directory.resolve("collection.zip")
    Files.write(archive, byteArrayOf())
    val rom =
        Rom(
            RomImage(
                RomOrigin.archiveEntry(archive, "folder/game.gb", true),
                StateCodecTestSupport.rom(seed = 7),
            ))
    val identity = StateIdentity.hash(rom)
    val active = Files.createDirectory(directory.resolve("active"))

    val storage =
        requireNotNull(
            BatteryStorageResolver.storage(
                ApplicationSettings.Saves(directory = active),
                rom,
                identity,
            ))

    assertEquals(
        active.resolve("games").resolve(identity.hex()).resolve("battery.sav"),
        storage.targetPath(),
    )
    assertEquals(directory.resolve("collection.sav"), storage.importSources().last().path())
    assertTrue(
        storage.importSources().any {
          it.path().fileName.toString().startsWith("collection--game-")
        })
  }
}
