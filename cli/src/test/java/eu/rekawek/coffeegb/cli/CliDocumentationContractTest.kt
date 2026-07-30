package eu.rekawek.coffeegb.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import org.junit.Test

class CliDocumentationContractTest {
  private val repoRoot = Path.of(System.getProperty("coffee-gb.repo-root")).toAbsolutePath()

  @Test
  fun publicCommandAndExitContractsAreDocumented() {
    val guide = Files.readString(repoRoot.resolve("docs/headless-cli.md"))
    listOf(
            "coffee-gb-cli.jar run",
            "coffee-gb-cli.jar replay",
            "--ticks",
            "--frames",
            "--max-ticks",
            "--input-script",
            "--break",
            "--memory",
            "--screenshot",
            "--wav",
            "--json-out",
            "--bundle",
            "| 0 |",
            "| 2 |",
            "| 3 |",
            "| 4 |",
            "| 5 |",
            "| 6 |",
            "coffee-gb/headless-report",
            "do not reference or initialize AWT or Swing",
        )
        .forEach { required -> assertTrue(required in guide, "headless CLI docs omit $required") }
  }

  @Test
  fun bundlePrivacyAndTwoGateContractAreDocumented() {
    val guide = Files.readString(repoRoot.resolve("docs/diagnostic-bundle-v1.md"))
    listOf(
            "coffee-gb/diagnostic-bundle-manifest",
            "manifest.json",
            "report.json",
            "logs.ndjson",
            "--bundle-include-replay",
            "--bundle-include-memory",
            "--bundle-include-media",
            "--confirm-sensitive-bundle",
            "sensitive/replay.cgbreplay",
            "ROM bytes",
            "battery saves",
            "boot-ROM bytes",
            "filesystem paths",
            "tokens",
        )
        .forEach { required -> assertTrue(required in guide, "bundle docs omit $required") }
  }
}
