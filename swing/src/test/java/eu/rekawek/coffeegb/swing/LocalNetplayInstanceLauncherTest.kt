package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LocalNetplayInstanceLauncherTest {

  @Test
  fun `jar launcher starts each client with the ROM profile and localhost join command`() {
    val started = mutableListOf<List<String>>()
    val rom = Path.of("test data", "Tetris.gb").toAbsolutePath().normalize()
    val launcher =
        CurrentProcessLocalNetplayInstanceLauncher(
            listOf("/usr/bin/java", "-Dcoffee-gb.theme=dark", "-jar", "/apps/coffee-gb.jar", "old.gb"),
        started::add,
    )

    val result = launcher.launch(rom, HardwareProfileRegistry.CGB, endpoint("localhost"), 3)

    assertEquals(3, result.started)
    assertEquals(3, started.size)
    val command = started.first()
    assertEquals(
        listOf(
            "/usr/bin/java",
            "-Dcoffee-gb.theme=dark",
            "-jar",
            "/apps/coffee-gb.jar",
            "--profile=cgb",
            "--disable-battery-saves",
            "--join-netplay",
            "localhost",
            rom.toString(),
        ),
        command,
    )
    assertTrue(started.all { it == command })
  }

  @Test
  fun `native launcher omits the current process app arguments`() {
    assertEquals(
        listOf("/Applications/Coffee GB.app/Contents/MacOS/Coffee GB"),
        localNetplayLauncherPrefix(
            listOf("/Applications/Coffee GB.app/Contents/MacOS/Coffee GB", "--debug", "old.gb"),
        ),
    )
    assertNull(localNetplayLauncherPrefix(listOf("/usr/bin/java", "old.gb")))
  }

  @Test
  fun `a failed child launch retains the successful launches in the result`() {
    var attempts = 0
    val launcher =
        CurrentProcessLocalNetplayInstanceLauncher(
            listOf("coffee-gb"),
        ) {
          attempts++
          if (attempts == 2) throw IllegalStateException("synthetic failure")
        }

    val result =
        launcher.launch(Path.of("Tetris.gb"), HardwareProfileRegistry.DMG, endpoint("localhost"), 3)

    assertEquals(1, result.started)
    assertEquals(3, result.requested)
    assertTrue(result.userMessage().contains("only 1 of 3"))
  }

  private fun endpoint(value: String): NetplayV8Endpoint =
      (validateNetplayV8Address(value) as NetplayAddressValidation.Valid).endpoint
}
