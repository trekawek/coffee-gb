package eu.rekawek.coffeegb.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CliApplicationTest {
  @Test
  fun helpVersionAndUsageHaveStableStreamOwnership() {
    val engine = CliExecutionEngine { error("must not execute") }
    val help = invoke(engine, "--help")
    assertEquals(0, help.exitCode)
    assertTrue(help.stdout.startsWith("Coffee GB headless CLI\n"))
    assertEquals("", help.stderr)

    val version = invoke(engine, "--version")
    assertEquals(0, version.exitCode)
    assertEquals("coffee-gb-cli test-version\n", version.stdout)
    assertEquals("", version.stderr)

    val invalid = invoke(engine, "run", "--rom", "private/location.gb")
    assertEquals(2, invalid.exitCode)
    assertEquals(
        "{\"schema\":\"coffee-gb/headless-report\",\"version\":1," +
            "\"status\":\"error\",\"exitCode\":2," +
            "\"error\":{\"code\":\"execution-bound\"}}\n",
        invalid.stdout,
    )
    assertTrue(invalid.stderr.startsWith("error[execution-bound]:"))
    assertFalse("private/location.gb" in invalid.stderr)
  }

  @Test
  fun passesEveryRuntimeExitCodeAndOneJsonLineThroughExactly() {
    for (exit in CliExitCode.entries.filter { it != CliExitCode.INVALID_ARGUMENTS }) {
      val json = "{\"schema\":\"test\",\"exitCode\":${exit.processCode}}"
      val result =
          invoke(
              CliExecutionEngine {
                CliExecutionOutcome(exit, json, CliDiagnostic("test-result", "Test result"))
              },
              "run",
              "--rom",
              "game.gb",
              "--ticks",
              "1",
          )
      assertEquals(exit.processCode, result.exitCode)
      assertEquals("$json\n", result.stdout)
      assertEquals("error[test-result]: Test result\n", result.stderr)
    }
  }

  @Test
  fun acceptsOneCanonicalWriterLineEndingWithoutAddingABlankLine() {
    val result =
        invoke(
            CliExecutionEngine {
              CliExecutionOutcome(CliExitCode.SUCCESS, "{\"status\":\"ok\"}\n")
            },
            "run",
            "--rom",
            "game.gb",
            "--ticks",
            "1",
        )
    assertEquals("{\"status\":\"ok\"}\n", result.stdout)
  }

  @Test
  fun strictlyLoadsCgbiBeforeCallingTheEngine() {
    val script = Files.createTempFile("coffee-gb-cli-input-", ".cgbi")
    try {
      Files.writeString(script, "CGBI\t1\t0\n7\t1\t0x10\n", StandardCharsets.UTF_8)
      var captured: CliExecutionRequest? = null
      val result =
          invoke(
              CliExecutionEngine {
                captured = it
                CliExecutionOutcome(CliExitCode.SUCCESS, "{\"status\":\"ok\"}")
              },
              "run",
              "--rom",
              "game.gb",
              "--ticks",
              "10",
              "--input-script",
              script.toString(),
          )
      assertEquals(0, result.exitCode)
      assertNotNull(captured)
      assertEquals(listOf(CgbiInputRecord(7, 1, 0x10)), captured.inputScript!!.records)
    } finally {
      Files.deleteIfExists(script)
    }
  }

  @Test
  fun sanitizesInputRuntimeAndInvalidReportFailures() {
    val missing = "/private/secret/input.cgbi"
    val inputFailure =
        invoke(
            CliExecutionEngine { error("must not execute") },
            "run",
            "--rom",
            "game.gb",
            "--ticks",
            "1",
            "--input-script",
            missing,
        )
    assertEquals(2, inputFailure.exitCode)
    assertTrue(inputFailure.stdout.contains("\"code\":\"input-script-invalid\""))
    assertFalse(missing in inputFailure.stdout + inputFailure.stderr)

    val runtimeFailure =
        invoke(
            CliExecutionEngine { throw IllegalStateException("token=SECRET /private/rom.gb") },
            "run",
            "--rom",
            "game.gb",
            "--ticks",
            "1",
        )
    assertEquals(6, runtimeFailure.exitCode)
    assertFalse("SECRET" in runtimeFailure.stdout + runtimeFailure.stderr)
    assertFalse("/private/rom.gb" in runtimeFailure.stdout + runtimeFailure.stderr)

    val invalidReport =
        invoke(
            CliExecutionEngine {
              CliExecutionOutcome(CliExitCode.SUCCESS, "{\"ok\":true}\nleaked")
            },
            "run",
            "--rom",
            "game.gb",
            "--ticks",
            "1",
        )
    assertEquals(6, invalidReport.exitCode)
    assertTrue(invalidReport.stdout.contains("invalid-runtime-report"))
    assertNull(invalidReport.stdout.lines().find { "leaked" in it })
  }

  private fun invoke(engine: CliExecutionEngine, vararg args: String): Invocation {
    val stdoutBytes = ByteArrayOutputStream()
    val stderrBytes = ByteArrayOutputStream()
    val exit =
        CliApplication(engine)
            .run(
                arrayOf(*args),
                PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
                PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
                "test-version",
            )
    return Invocation(
        exit,
        stdoutBytes.toString(StandardCharsets.UTF_8),
        stderrBytes.toString(StandardCharsets.UTF_8),
    )
  }

  private data class Invocation(val exitCode: Int, val stdout: String, val stderr: String)
}
