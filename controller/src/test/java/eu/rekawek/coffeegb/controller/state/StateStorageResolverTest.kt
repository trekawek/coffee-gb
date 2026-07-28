package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class StateStorageResolverTest {

  @Test
  fun `ROM names and paths never become storage components and old roots remain fallbacks`() {
    val root = Files.createTempDirectory("state resolver [edge]")
    val romDirectory = Files.createDirectories(root.resolve("ROMs with spaces").resolve("日本語"))
    val romPath = romDirectory.resolve("name .. [%] ü.gbc")
    val configuration =
        Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom(seed = 11), romPath.toFile()))
            .setGameboyType(GameboyType.DMG)
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setSupportBatterySave(false)
    val identity = StateIdentity.from(configuration).primaryRom.hex()
    val configured = root.resolve("configured states")
    val previous = root.resolve("previous states")

    val paths =
        StateStorageResolver.resolve(
            ApplicationSettings.Saves(
                directory = configured,
                previousDirectories = listOf(previous),
            ),
            configuration,
        )

    assertEquals(
        configured.toAbsolutePath().normalize().resolve("games").resolve(identity),
        paths.layout.gameDirectory,
    )
    assertEquals(paths.layout.gameDirectory.resolve("screenshots"), paths.screenshotsDirectory)
    assertEquals(
        listOf(
            romDirectory
                .resolve(StateStorageResolver.DEFAULT_DIRECTORY)
                .resolve("games")
                .resolve(identity)
                .toAbsolutePath()
                .normalize(),
            previous
                .resolve("games")
                .resolve(identity)
                .toAbsolutePath()
                .normalize(),
        ),
        paths.fallbackLayouts.map(StateStorageLayout::gameDirectory),
    )
    assertFalse(paths.layout.gameDirectory.toString().contains(romPath.fileName.toString()))

    val defaults =
        StateStorageResolver.resolve(ApplicationSettings.Saves(), configuration)
    assertEquals(paths.fallbackLayouts.first().gameDirectory, defaults.layout.gameDirectory)
    assertEquals(emptyList(), defaults.fallbackLayouts)
  }
}
