package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import kotlin.test.assertEquals
import org.junit.Test

class StateRecordIntrospectionTest {

  @Test
  fun `uses canonical constructor parameter names for every audited state record`() {
    StateTypeRegistry.recordClasses.forEach { type ->
      val constructor =
          type.declaredConstructors.single { candidate ->
            candidate.parameterCount == StateRecordIntrospection.components(type).size
          }
      assertEquals(
          constructor.parameters.map { it.name },
          StateRecordIntrospection.components(type).map { it.name },
          type.name,
      )
    }
  }
}
