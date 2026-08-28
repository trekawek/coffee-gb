package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LocalNetplayInstanceLauncherTest {

  @Test
  fun `the running desktop test JVM has a reusable launcher`() {
    assertTrue(localNetplayLauncherPrefix(currentProcessCommand()).orEmpty().isNotEmpty())
  }

  @Test
  fun `child processes do not require host console handles or retain unread pipes`() {
    val builder = localNetplayProcessBuilder(listOf("coffee-gb"))
    assertEquals(
        ProcessBuilder.Redirect.DISCARD,
        builder.redirectOutput(),
    )
    assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError())
  }

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
            "--start-muted",
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
    assertNull(localNetplayLauncherPrefix(listOf("C:\\Java\\bin\\javaw.exe", "old.gb")))
  }

  @Test
  fun `Windows JVM without process arguments is reconstructed from its classpath`() {
    assertEquals(
        listOf(
            "C:\\Java\\bin\\java.exe",
            "-cp",
            "swing\\target\\classes;controller\\target\\classes",
            "eu.rekawek.coffeegb.swing.MainKt",
        ),
        currentProcessCommand(
            packagedLauncher = null,
            executable = "C:\\Java\\bin\\java.exe",
            arguments = emptyList(),
            classPath = "swing\\target\\classes;controller\\target\\classes",
        ),
    )
  }

  @Test
  fun `installed jpackage launcher takes precedence over its embedded JVM details`() {
    assertEquals(
        listOf("C:\\Program Files\\Coffee GB\\Coffee GB.exe"),
        currentProcessCommand(
            packagedLauncher = "C:\\Program Files\\Coffee GB\\Coffee GB.exe",
            executable = "C:\\Program Files\\Coffee GB\\runtime\\bin\\javaw.exe",
            arguments = emptyList(),
            classPath = "app\\coffee-gb.jar",
        ),
    )
  }

  @Test
  fun `custom Java host without desktop main on its system classpath is unavailable`() {
    for (arguments in listOf(emptyList(), listOf("-jar", "custom-host.jar"))) {
      assertTrue(
          currentProcessCommand(
                  packagedLauncher = null,
                  executable = "C:\\Java\\bin\\java.exe",
                  arguments = arguments,
                  classPath = "maven-embedder.jar",
                  desktopMainOnSystemClassPath = false,
              )
              .isEmpty())
    }
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
