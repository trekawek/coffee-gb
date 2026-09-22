package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalNetplayInstanceLauncherTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `the running desktop test JVM has a reusable launcher`() {
    assertTrue(localNetplayLauncherPrefix(currentProcessCommand()).orEmpty().isNotEmpty())
  }

  @Test
  fun `child processes merge stderr into the drained stdout pipe`() {
    val builder = localNetplayProcessBuilder(listOf("coffee-gb"))
    assertTrue(builder.redirectErrorStream())
    assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectOutput())
    assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectError())
  }

  @Test
  fun `child output lines are forwarded with the client prefix`() {
    val bytes = ByteArrayOutputStream()
    val output = PrintStream(bytes, true, Charsets.UTF_8)

    forwardPrefixedLocalNetplayOutput(
        ByteArrayInputStream("first line\nwarning line\nlast line".toByteArray()),
        "netplay-client-2",
        output,
    )

    val newline = System.lineSeparator()
    assertEquals(
        "[netplay-client-2] first line$newline" +
            "[netplay-client-2] warning line$newline" +
            "[netplay-client-2] last line$newline",
        bytes.toString(Charsets.UTF_8),
    )
  }

  @Test
  fun `jar launcher gives every client a persistent copy of the host battery save`() {
    val started = mutableListOf<Pair<List<String>, String>>()
    val directory = temporaryFolder.newFolder("test data").toPath()
    val rom = Files.createFile(directory.resolve("Tetris.gb"))
    val hostSave = Files.write(directory.resolve("Tetris.sav"), byteArrayOf(1, 2, 3))
    val retainedClientSave =
        Files.write(directory.resolve("Tetris-client2.sav"), byteArrayOf(9, 8, 7))
    val launcher =
        CurrentProcessLocalNetplayInstanceLauncher(
            listOf("/usr/bin/java", "-Dcoffee-gb.theme=dark", "-jar", "/apps/coffee-gb.jar", "old.gb"),
        { command, prefix -> started += command to prefix },
    )

    val result = launcher.launch(rom, HardwareProfileRegistry.CGB, endpoint("localhost"), 3)

    assertEquals(3, result.started)
    assertEquals(3, started.size)
    started.forEachIndexed { index, (command, prefix) ->
      val clientSave = directory.resolve("Tetris-client${index + 1}.sav")
      assertEquals("netplay-client-${index + 1}", prefix)
      assertEquals(
          listOf(
              "/usr/bin/java",
              "-Dcoffee-gb.theme=dark",
              "-jar",
              "/apps/coffee-gb.jar",
              "--profile=cgb",
              "--battery-save",
              clientSave.toString(),
              "--start-muted",
              "--join-netplay",
              "localhost",
              rom.toString(),
          ),
          command,
      )
      assertContentEquals(
          if (index == 1) Files.readAllBytes(retainedClientSave) else Files.readAllBytes(hostSave),
          Files.readAllBytes(clientSave),
      )
    }
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
        ) { _, _ ->
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
