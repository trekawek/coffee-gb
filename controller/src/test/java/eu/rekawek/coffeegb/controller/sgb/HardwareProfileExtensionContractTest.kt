package eu.rekawek.coffeegb.controller.sgb

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class HardwareProfileExtensionContractTest {

  @Test
  fun contributorTemplateAndNormativeChecklistRetainEveryRequiredGate() {
    val root = repositoryRoot()
    val template =
        Files.readString(
            root.resolve(".github/ISSUE_TEMPLATE/hardware-profile-extension.md"),
            StandardCharsets.UTF_8,
        )
    val guide =
        Files.readString(
            root.resolve("docs/hardware-profile-contribution.md"),
            StandardCharsets.UTF_8,
        )

    REQUIRED_SECTIONS.forEach { section ->
      assertTrue(template.contains("## $section"), "issue template lost '$section'")
      assertTrue(guide.contains(section, ignoreCase = true), "normative guide lost '$section'")
    }
    assertTrue(template.contains("mvn -B clean test"))
    assertTrue(guide.contains("Java-16"))
    assertTrue(guide.contains("future Android", ignoreCase = true))
  }

  @Test
  fun registryMatrixDocumentationAndExecutableSelectionCannotDrift() {
    val root = repositoryRoot()
    val expected = HardwareProfileRegistry.supportedIds()
    val matrixIds =
        Files.readAllLines(
                root.resolve(
                    "controller/src/test/resources/sgb-baselines/hardware-profile-matrix.tsv"),
                StandardCharsets.UTF_8,
            )
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.substringBefore('\t') }
    assertEquals(expected, matrixIds)

    val guide = Files.readString(root.resolve("docs/hardware-profile-contribution.md"))
    val marker =
        Regex("<!-- profile-registry-ids: ([a-z0-9,-]+) -->")
            .find(guide)
            ?.groupValues
            ?.get(1)
            ?.split(',')
    assertEquals(expected, marker)

    val cli = Files.readString(root.resolve("swing/src/main/java/eu/rekawek/coffeegb/swing/Main.kt"))
    val menu = Files.readString(root.resolve("swing/src/main/java/eu/rekawek/coffeegb/swing/SwingMenu.kt"))
    val settings =
        Files.readString(
            root.resolve(
                "controller/src/main/java/eu/rekawek/coffeegb/controller/properties/SystemProperties.kt"))
    assertTrue(cli.contains("HardwareProfileRegistry.supportedIds()"))
    assertTrue(menu.contains("HardwareProfileRegistry.supportedProfiles()"))
    assertTrue(settings.contains("HardwareProfileRegistry.resolveSetting("))
  }

  private fun repositoryRoot(): Path {
    System.getProperty("maven.multiModuleProjectDirectory")?.let { candidate ->
      val root = Path.of(candidate).toAbsolutePath().normalize()
      if (Files.isRegularFile(root.resolve("core/pom.xml"))) return root
    }
    var candidate = Path.of("").toAbsolutePath().normalize()
    while (candidate.parent != null) {
      if (Files.isRegularFile(candidate.resolve("core/pom.xml")) &&
          Files.isRegularFile(candidate.resolve("controller/pom.xml"))) return candidate
      candidate = candidate.parent
    }
    throw AssertionError("Cannot locate repository root")
  }

  private companion object {
    val REQUIRED_SECTIONS =
        listOf(
            "Permanent identity and aliases",
            "Evidence, licenses, and uncertainty",
            "Exact clock and cadence math",
            "Capabilities and quirks",
            "Authentic and skip boot policy",
            "Construction and Auto resolution",
            "State, rewind, netplay, and future replay",
            "CLI, UI, settings, and diagnostics",
            "Legal fixtures and provenance",
            "Positive, boundary, malformed, reset, and mismatch tests",
            "Matrices, inventory, and documentation",
            "Java 16 build and compatibility CI",
        )
  }
}
