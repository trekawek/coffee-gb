package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StateInventoryTest {

  @Test
  fun committedFieldInventoryExactlyMatchesAuditedProductionRegistry() {
    val documented =
        Paths.get("../docs/state-memento-schema.md")
            .readLines()
            .filter { it.startsWith("- `") }
    val runtime =
        MementoTypeRegistry.recordClasses.map { type ->
          val fields = type.recordComponents.joinToString(", ") { it.name }
          "- `${type.name}`: $fields"
        }

    assertEquals(91, runtime.size)
    assertEquals(runtime, documented)
    assertEquals(11, MementoTypeRegistry.enumClasses.size)
  }

  @Test
  fun everyProductionOriginatorAndCaptureSiteIsInTheOwnershipAudit() {
    val repository = Paths.get("..").toAbsolutePath().normalize()
    val productionRoots =
        listOf(repository.resolve("core/src/main/java"), repository.resolve("controller/src/main/java"))
    val discovered =
        productionRoots
            .flatMap { root ->
              Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .filter {
                      val source = it.readText()
                      "saveToMemento(" in source || "Originator<" in source
                    }
                    .map { repository.relativize(it).toString() }
                    .toList()
              }
            }
            .sorted()
    val documented =
        repository
            .resolve("docs/state-originator-sites.md")
            .readLines()
            .mapNotNull { line ->
              ORIGINATOR_AUDIT_LINE.matchEntire(line)?.groupValues?.get(1)
            }
            .sorted()

    assertEquals(97, discovered.size)
    assertEquals(discovered, documented)
  }

  @Test
  fun everyAdmittedRecordHasAnExplicitSemanticPolicyAndRationale() {
    assertEquals(MementoTypeRegistry.recordClassNames.toSet(), StateSemantics.policyAudit.keys)
    assertEquals(91, StateSemantics.policyAudit.size)
    assertTrue(StateSemantics.policyAudit.values.all { it.isNotBlank() })
  }

  private companion object {
    val ORIGINATOR_AUDIT_LINE = Regex("- (?:OWNER|COMPOSITE|WORKFLOW|CONTRACT) `([^`]+)`")
  }
}
