package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.test.assertEquals
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
}
