package eu.rekawek.coffeegb.controller.sgb

import eu.rekawek.coffeegb.core.sgb.Commands
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SgbInventoryGuardTest {

  @Test
  fun commandMatrixCoversEveryIdAndMatchesProductionRegistry() {
    val rows = tsv("/sgb-baselines/sgb-command-matrix.tsv")
    assertEquals((0x00..0x1f).toList(), rows.map { it["id"]!!.removePrefix("0x").toInt(16) })
    assertEquals(rows.size, rows.map { it["id"] }.toSet().size)

    val statuses = setOf("implemented", "partial", "intentionally-unsupported", "unknown")
    rows.forEach { row ->
      val id = row.getValue("id").removePrefix("0x").toInt(16)
      val recognized = row.getValue("recognized").toBooleanStrict()
      val commandClass = Commands.commandClass(id)
      assertEquals("Production recognition drift for ${row.getValue("id")}", recognized,
          Commands.isRecognizedCommandId(id))
      if (recognized) {
        assertEquals(row.getValue("production_class"), commandClass!!.simpleName)
      } else {
        assertEquals(null, commandClass)
        assertEquals("-", row.getValue("production_class"))
      }
      assertTrue("Invalid status for ${row.getValue("id")}", row.getValue("status") in statuses)
      REQUIRED_MATRIX_FIELDS.forEach { field ->
        assertFalse("$field is missing for ${row.getValue("id")}", row.getValue(field).isBlank())
      }
      assertTrue("Evidence key is missing for ${row.getValue("id")}",
          row.getValue("evidence").split('+').all { it in EVIDENCE_KEYS })
    }

    assertEquals((0x00..0x19).toList(), rows.filter { it.getValue("recognized") == "true" }
        .map { it.getValue("id").removePrefix("0x").toInt(16) })
  }

  @Test
  fun modelDecisionInventoryClassifiesEveryProductionOccurrence() {
    val root = repositoryRoot()
    val rows = tsv("/sgb-baselines/model-decision-inventory.tsv")
    val expected = linkedMapOf<Pair<String, String>, Int>()

    rows.forEach { row ->
      val relativePath = row.getValue("path")
      val source = root.resolve(relativePath)
      assertTrue("Inventory path does not exist: $relativePath", source.isRegularFile())
      val content = source.readText(StandardCharsets.UTF_8)
      row.getValue("symbols").split('|').forEach { symbol ->
        assertTrue("Stable context '$symbol' is absent from $relativePath", content.contains(symbol))
      }
      assertTrue(row.getValue("classification") in CLASSIFICATIONS)
      assertTrue(row.getValue("phase_owner") in PHASE_OWNERS)
      for (field in listOf("current_behavior", "models", "evidence", "uncertainty")) {
        assertFalse("$field is missing for $relativePath", row.getValue(field).isBlank())
      }
      row.getValue("matches").split(';').forEach { declaration ->
        val parts = declaration.split('=')
        assertEquals("Invalid match declaration '$declaration'", 2, parts.size)
        val category = parts[0]
        val count = parts[1].toInt()
        assertTrue("Unknown category $category", category in DECISION_PATTERNS)
        assertTrue("Expected occurrence count must be positive", count > 0)
        assertEquals("Duplicate inventory category/path", null, expected.put(category to relativePath, count))
      }
    }

    val actual = linkedMapOf<Pair<String, String>, Int>()
    PRODUCTION_ROOTS.forEach { productionRoot ->
      Files.walk(root.resolve(productionRoot)).use { paths ->
        paths.filter { it.isRegularFile() && it.extension in setOf("java", "kt") }
            .sorted()
            .forEach { source ->
              val relativePath = root.relativize(source).toString().replace('\\', '/')
              val lines = Files.readAllLines(source, StandardCharsets.UTF_8)
              DECISION_PATTERNS.forEach { (category, pattern) ->
                val count = lines.sumOf { line ->
                  val trimmed = line.trimStart()
                  if (SKIPPED_PREFIXES.any(trimmed::startsWith)) 0
                  else pattern.findAll(line).count()
                }
                if (count > 0) actual[category to relativePath] = count
              }
            }
      }
    }

    assertEquals(
        "A production model/clock decision was added, removed, or changed without classification",
        expected,
        actual,
    )
    assertTrue("Inventory must cover all decision categories", DECISION_PATTERNS.keys.all { category ->
      actual.keys.any { it.first == category }
    })

    val expectedFingerprints =
        tsv("/sgb-baselines/model-decision-fingerprints.tsv").associate { row ->
          row.getValue("path") to row.getValue("sha256")
        }
    assertEquals("Fingerprint inventory paths must match classified paths",
        rows.map { it.getValue("path") }.toSet(), expectedFingerprints.keys)
    val actualFingerprints =
        expectedFingerprints.keys.associateWith { relativePath ->
          decisionFingerprint(Files.readAllLines(root.resolve(relativePath), StandardCharsets.UTF_8))
        }
    assertEquals(
        "A classified decision changed context without a reviewed inventory update",
        expectedFingerprints,
        actualFingerprints,
    )
  }

  private fun decisionFingerprint(lines: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DECISION_PATTERNS.forEach { (category, pattern) ->
      lines.forEach { line ->
        val trimmed = line.trimStart()
        if (!SKIPPED_PREFIXES.any(trimmed::startsWith)) {
          val count = pattern.findAll(line).count()
          if (count > 0) {
            val normalized = line.trim().replace(Regex("\\s+"), " ")
            digest.update("$category\t$count\t$normalized\n".toByteArray(StandardCharsets.UTF_8))
          }
        }
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun tsv(resource: String): List<Map<String, String>> {
    val stream = javaClass.getResourceAsStream(resource)
    assertNotNull("Missing $resource", stream)
    val lines = stream!!.bufferedReader(StandardCharsets.UTF_8).readLines()
        .filter { it.isNotBlank() && !it.startsWith('#') }
    assertTrue("TSV requires a header and data", lines.size > 1)
    val header = lines.first().split('\t')
    return lines.drop(1).mapIndexed { index, line ->
      val values = line.split('\t')
      assertEquals("Column count at $resource:${index + 2}", header.size, values.size)
      header.zip(values).toMap()
    }
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
    val REQUIRED_MATRIX_FIELDS =
        listOf(
            "name",
            "aliases",
            "documented_packets",
            "packet_validation",
            "length_validation",
            "stored_state",
            "dmg_effect",
            "border_effect",
            "multiplayer_effect",
            "snes_dependency",
            "save_restore",
            "status",
            "evidence",
            "uncertainty",
        )

    val EVIDENCE_KEYS =
        setOf(
            "PANDOCS_SUMMARY",
            "PANDOCS_PACKET",
            "PANDOCS_PALETTE",
            "PANDOCS_ATTRIBUTE",
            "PANDOCS_SOUND",
            "PANDOCS_SYSTEM",
            "PANDOCS_MULTIPLAYER",
            "PANDOCS_BORDER",
            "PANDOCS_UNDOCUMENTED",
            "COFFEEGB_BASELINE",
        )

    val DECISION_PATTERNS =
        linkedMapOf(
            "GAMEBOY_TYPE" to Regex("GameboyType"),
            "CGB_FLAG" to Regex("\\b(gbc|isCgb|isGbc)\\b"),
            "SGB_FLAG" to Regex("\\b(sgb|isSgb)\\b"),
            "CGB0" to Regex("cgb0Revision|CGB0_REVISION|PROFILE_CGB0"),
            "BOOTSTRAP" to Regex("BootstrapMode|bootstrapMode"),
            "CLOCK" to Regex("TICKS_PER_SEC|TICKS_PER_FRAME|FRAME_DURATION_NANOS"),
            "SGB_BORDER" to Regex("displaySgbBorder|sgbBorder|PROFILE_SGB_BORDER"),
            "MEALYBUG" to Regex("mealybugDmgBlob|MEALYBUG_DMG_BLOB|PROFILE_MEALYBUG_DMG_BLOB"),
            "CODEBREAKER" to Regex("codeBreakerRumble|CODEBREAKER_RUMBLE|PROFILE_CODEBREAKER_RUMBLE"),
            "ROM_MODEL" to
                Regex(
                    "GameboyColorFlag|isSuperGameboyFlag|DmgGamesType|CgbGamesType|" +
                        "forceDmg|forceCgb|force-dmg|force-cgb"),
            "PORTABLE_PROFILE" to Regex("MachineHardwareState|StateHardwareProfile"),
        )

    val PRODUCTION_ROOTS =
        listOf("core/src/main", "controller/src/main", "swing/src/main")

    val SKIPPED_PREFIXES = listOf("//", "/*", "*", "package ", "import ")

    val CLASSIFICATIONS =
        setOf(
            "hardware-policy",
            "configuration",
            "compatibility-adapter",
            "portable-state-adapter",
            "legacy-importer",
            "platform-adapter",
        )

    val PHASE_OWNERS =
        setOf(
            "phase-1-commands",
            "phase-2-multiplayer",
            "phase-3-profiles",
            "phase-4-sgb2-timing",
            "phase-5-mgb",
        )
  }
}
