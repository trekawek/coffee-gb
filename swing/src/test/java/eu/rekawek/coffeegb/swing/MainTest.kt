package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MainTest {

  @Test
  fun `JVM shutdown hook closes pending settings`() {
    var closeCount = 0
    var reportedFailure: Exception? = null
    val hook =
        createSettingsShutdownHook(AutoCloseable { closeCount++ }) { failure ->
          reportedFailure = failure
        }

    hook.run()

    assertEquals(1, closeCount)
    assertNull(reportedFailure)
  }

  @Test
  fun `empty command line launches once with no overrides`() {
    val execution = execute()

    val request = execution.singleLaunch()
    assertEquals(0, execution.exitCode)
    assertEquals("", execution.stdout)
    assertEquals("", execution.stderr)
    assertFalse(request.debug)
    assertNull(request.initialRom)
    assertNull(request.joinNetplayHost)
    assertFalse(request.startMuted)
    assertFalse(request.suppressInitialAutosaveResume)
    assertNull(request.settingsOverrides.hardwareProfile)
    assertNull(request.settingsOverrides.bootstrapMode)
    assertNull(request.settingsOverrides.batterySavesEnabled)
  }

  @Test
  fun `all launch flags produce one typed process-local request`() {
    val execution = execute("--debug", "-d", "-b", "-db", "--start-muted", "game.gb")

    val request = execution.singleLaunch()
    assertEquals(0, execution.exitCode)
    assertTrue(request.debug)
    assertEquals(File("game.gb"), request.initialRom)
    assertEquals(HardwareProfileRegistry.DMG, request.settingsOverrides.hardwareProfile)
    assertEquals(BootstrapMode.NORMAL, request.settingsOverrides.bootstrapMode)
    assertEquals(false, request.settingsOverrides.batterySavesEnabled)
    assertTrue(request.startMuted)
  }

  @Test
  fun `join netplay accepts a direct host value and requires a ROM`() {
    val spaced = execute("--join-netplay", "play.local:7000", "game.gb").singleLaunch()
    assertEquals(File("game.gb"), spaced.initialRom)
    assertEquals("play.local:7000", spaced.joinNetplayHost)
    assertTrue(spaced.suppressInitialAutosaveResume)
    assertTrue(spaced.settingsOverrides.forceInMemoryBattery)
    assertTrue(spaced.settingsOverrides.suppressCloseAutosave)

    val equals = execute("--join-netplay=localhost", "game.gb").singleLaunch()
    assertEquals("localhost", equals.joinNetplayHost)
    assertTrue(equals.suppressInitialAutosaveResume)
    assertTrue(equals.settingsOverrides.forceInMemoryBattery)
    assertTrue(equals.settingsOverrides.suppressCloseAutosave)

    assertUsageFailure(
        execute("--join-netplay"),
        "--join-netplay requires a hostname or IPv4 address",
    )
    assertUsageFailure(execute("--join-netplay", "localhost"), "--join-netplay requires a ROM file")
    assertUsageFailure(
        execute("--join-netplay", "play.local:0", "game.gb"),
        "--join-netplay The port must be between 1 and 65535",
    )
  }

  @Test
  fun `legacy aliases and their long forms have identical typed effects`() {
    for (alias in listOf("-d", "--force-dmg")) {
      assertEquals(
          HardwareProfileRegistry.DMG,
          execute(alias).singleLaunch().settingsOverrides.hardwareProfile,
      )
    }
    for (alias in listOf("-c", "--force-cgb")) {
      assertEquals(
          HardwareProfileRegistry.CGB,
          execute(alias).singleLaunch().settingsOverrides.hardwareProfile,
      )
    }
    for (alias in listOf("-b", "--use-bootstrap")) {
      assertEquals(
          BootstrapMode.NORMAL,
          execute(alias).singleLaunch().settingsOverrides.bootstrapMode,
      )
    }
    for (alias in listOf("-db", "--disable-battery-saves")) {
      assertEquals(false, execute(alias).singleLaunch().settingsOverrides.batterySavesEnabled)
    }

    assertTrue(execute("--debug").singleLaunch().debug)
  }

  @Test
  fun `each canonical profile ID reaches the launch request`() {
    for (profile in HardwareProfileRegistry.supportedProfiles()) {
      val request = execute("--profile=${profile.id()}").singleLaunch()
      assertEquals(profile, request.settingsOverrides.hardwareProfile)
    }
  }

  @Test
  fun `option terminator permits an option-like ROM path`() {
    val terminated = execute("--", "--force-dmg").singleLaunch()
    assertEquals(File("--force-dmg"), terminated.initialRom)
    assertNull(terminated.settingsOverrides.hardwareProfile)

    val singleDash = execute("-").singleLaunch()
    assertEquals(File("-"), singleDash.initialRom)

    val optionAfterRom = execute("game.gb", "--debug").singleLaunch()
    assertEquals(File("game.gb"), optionAfterRom.initialRom)
    assertTrue(optionAfterRom.debug)
  }

  @Test
  fun `help aliases print the complete contract without launching`() {
    for (alias in listOf("-h", "--help")) {
      val execution = execute(alias)
      assertEquals(0, execution.exitCode)
      assertEquals("", execution.stderr)
      assertTrue(execution.launches.isEmpty())
      assertTrue(execution.stdout.startsWith("Usage:${newline()}"))
      for (advertised in
          listOf(
              "-d  --force-dmg",
              "-c  --force-cgb",
              "--profile=<id>",
              "-b  --use-bootstrap",
              "-db --disable-battery-saves",
              "--join-netplay HOST",
              "--start-muted",
              "--debug",
              "-h  --help",
              "--version",
              "--package-smoke",
              "--                         Treat",
              "dmg, cgb, cgb0, sgb, sgb2, mgb",
          )) {
        assertTrue(execution.stdout.contains(advertised), "Missing help entry: $advertised")
      }
    }
  }

  @Test
  fun `version is deterministic and does not launch`() {
    val execution = execute("--version", version = "9.8.7-test")

    assertEquals(0, execution.exitCode)
    assertEquals("Coffee GB 9.8.7-test${newline()}", execution.stdout)
    assertEquals("", execution.stderr)
    assertTrue(execution.launches.isEmpty())
  }

  @Test
  fun `package smoke is a terminal self-contained command`() {
    var smokeCount = 0
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val launches = mutableListOf<CliLaunchRequest>()

    val exitCode =
        runCli(
            arrayOf("--package-smoke"),
            PrintStream(stdout),
            PrintStream(stderr),
            TEST_VERSION,
            packageSmoke = {
              smokeCount++
              PackageRuntimeSmoke.Result(123, 2, 3, 456, "linux-x86-64")
            },
        ) {
          launches += it
        }

    assertEquals(0, exitCode)
    assertEquals(1, smokeCount)
    assertTrue(launches.isEmpty())
    assertTrue(stdout.toString().contains("native-target=linux-x86-64"))
    assertEquals("", stderr.utf8())
    assertEquals(
        "Coffee GB package smoke OK: ticks=123, video=2, audio=3, state=456, " +
            "native-target=linux-x86-64${newline()}",
        stdout.utf8(),
    )
  }

  @Test
  fun `unknown malformed and extra arguments fail with usage status`() {
    val failures =
        listOf(
            Failure(arrayOf("--unknown"), "Unknown option '--unknown'"),
            Failure(arrayOf("-x"), "Unknown option '-x'"),
            Failure(arrayOf("-dc"), "Unknown option '-dc'"),
            Failure(arrayOf("--debug=true"), "Unknown option '--debug=true'"),
            Failure(arrayOf("--profile"), "--profile requires =<id>"),
            Failure(arrayOf("--profile="), "--profile requires one non-empty stable ID"),
            Failure(arrayOf("--profile=cgb=extra"), "--profile requires one non-empty stable ID"),
            Failure(arrayOf("--profile=CGB"), "Unknown hardware profile 'CGB'"),
            Failure(
                arrayOf("--join-netplay=host", "--join-netplay=other", "game.gb"),
                "Option '--join-netplay' may be specified only once",
            ),
            Failure(
                arrayOf("--start-muted", "--start-muted"),
                "Option '--start-muted' may be specified only once",
            ),
            Failure(
                arrayOf("--package-smoke", "game.gb"),
                "--package-smoke cannot be combined with launch options or a ROM file",
            ),
            Failure(
                arrayOf("--package-smoke", "--debug"),
                "--package-smoke cannot be combined with launch options or a ROM file",
            ),
            Failure(
                arrayOf("--package-smoke", "--start-muted"),
                "--package-smoke cannot be combined with launch options or a ROM file",
            ),
            Failure(arrayOf("one.gb", "two.gb"), "Expected at most one ROM file, received 2"),
            Failure(
                arrayOf("--profile=cgb", "--profile=cgb"),
                "Option '--profile' may be specified only once",
            ),
            Failure(
                arrayOf("--profile=cgb", "--profile=cgb0"),
                "Option '--profile' may be specified only once",
            ),
            Failure(
                arrayOf("--", "first.gb", "--second.gb"),
                "Expected at most one ROM file, received 2",
            ),
        )

    for (failure in failures) {
      assertUsageFailure(execute(*failure.args), failure.message)
    }
  }

  @Test
  fun `conflicting options fail before any launch`() {
    val failures =
        listOf(
            Failure(
                arrayOf("--force-dmg", "-c"),
                "--force-dmg and --force-cgb cannot be used together",
            ),
            Failure(
                arrayOf("--profile=cgb", "--force-cgb"),
                "--profile conflicts with --force-dmg/--force-cgb",
            ),
            Failure(
                arrayOf("-d", "--profile=dmg"),
                "--profile conflicts with --force-dmg/--force-cgb",
            ),
            Failure(
                arrayOf("--help", "--version"),
                "--help, --version, and --package-smoke cannot be used together",
            ),
            Failure(
                arrayOf("--version", "--package-smoke"),
                "--help, --version, and --package-smoke cannot be used together",
            ),
        )

    for (failure in failures) {
      assertUsageFailure(execute(*failure.args), failure.message)
    }
  }

  @Test
  fun `bootstrap rejects explicit profiles without bundled boot ROMs`() {
    for (profile in listOf("mgb", "sgb2")) {
      for (bootstrap in listOf("-b", "--use-bootstrap")) {
        val execution = execute("--profile=$profile", bootstrap)
        assertUsageFailure(execution, "--use-bootstrap cannot be used with profile '$profile'")
        assertTrue(execution.stderr.contains("does not bundle its boot ROM"))
      }
    }
  }

  @Test
  fun `help and version do not hide invalid arguments`() {
    assertUsageFailure(execute("--help", "--unknown"), "Unknown option '--unknown'")
    assertUsageFailure(
        execute("--help", "--force-dmg", "--force-cgb"),
        "--force-dmg and --force-cgb cannot be used together",
    )
    assertUsageFailure(
        execute("--version", "--profile=cgb", "-c"),
        "--profile conflicts with --force-dmg/--force-cgb",
    )
  }

  @Test
  fun `repeated options including equivalent aliases are usage errors`() {
    val failures =
        listOf(
            Failure(arrayOf("-h", "--help"), "Option '--help' may be specified only once"),
            Failure(
                arrayOf("--version", "--version"),
                "Option '--version' may be specified only once",
            ),
            Failure(
                arrayOf("--package-smoke", "--package-smoke"),
                "Option '--package-smoke' may be specified only once",
            ),
            Failure(arrayOf("--debug", "--debug"), "Option '--debug' may be specified only once"),
            Failure(
                arrayOf("-d", "--force-dmg"),
                "Option '--force-dmg' may be specified only once",
            ),
            Failure(
                arrayOf("-c", "--force-cgb"),
                "Option '--force-cgb' may be specified only once",
            ),
            Failure(
                arrayOf("-b", "--use-bootstrap"),
                "Option '--use-bootstrap' may be specified only once",
            ),
            Failure(
                arrayOf("-db", "--disable-battery-saves"),
                "Option '--disable-battery-saves' may be specified only once",
            ),
        )

    for (failure in failures) {
      assertUsageFailure(execute(*failure.args), failure.message)
    }
  }

  @Test
  fun `launcher failures are not reclassified as command-line errors`() {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    val failure =
        assertFailsWith<IllegalStateException> {
          runCli(
              emptyArray(),
              PrintStream(stdout),
              PrintStream(stderr),
              TEST_VERSION,
          ) {
            throw IllegalStateException("launcher failed")
          }
        }

    assertEquals("launcher failed", failure.message)
    assertEquals("", stdout.utf8())
    assertEquals("", stderr.utf8())
  }

  @Test
  fun `usage diagnostics have a stable stderr-only shape`() {
    val execution = execute("--unknown")

    assertEquals(
        "coffee-gb: Unknown option '--unknown'${newline()}" +
            "Try 'java -jar coffee-gb.jar --help' for more information.${newline()}",
        execution.stderr,
    )
    assertEquals("", execution.stdout)
    assertTrue(execution.launches.isEmpty())
  }

  private fun assertUsageFailure(execution: Execution, message: String) {
    assertEquals(2, execution.exitCode)
    assertEquals("", execution.stdout)
    assertTrue(execution.stderr.contains("coffee-gb: $message"), execution.stderr)
    assertTrue(execution.stderr.contains("--help"), execution.stderr)
    assertTrue(execution.launches.isEmpty())
  }

  private fun execute(vararg args: String, version: String = TEST_VERSION): Execution {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val launches = mutableListOf<CliLaunchRequest>()
    val out = PrintStream(stdout)
    val err = PrintStream(stderr)
    val exitCode = runCli(arrayOf(*args), out, err, version) { launches += it }
    out.flush()
    err.flush()
    return Execution(exitCode, stdout.utf8(), stderr.utf8(), launches.toList())
  }

  private data class Failure(val args: Array<String>, val message: String)

  private data class Execution(
      val exitCode: Int,
      val stdout: String,
      val stderr: String,
      val launches: List<CliLaunchRequest>,
  ) {
    fun singleLaunch(): CliLaunchRequest {
      assertEquals(1, launches.size)
      return launches.single()
    }
  }

  private companion object {
    const val TEST_VERSION = "test-version"

    fun newline(): String = System.lineSeparator()

    fun ByteArrayOutputStream.utf8(): String = toString(Charsets.UTF_8.name())
  }
}
