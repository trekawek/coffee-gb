package eu.rekawek.coffeegb.controller.state

import java.lang.reflect.Field
import java.lang.reflect.Type
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection metadata for Coffee GB's audited state records.
 *
 * Android API 26 can run record-desugared classes, but does not expose the Java 16 record
 * reflection APIs. State records have a canonical constructor and one instance field per
 * component, so their existing audited inventories can use the stable reflection APIs instead.
 */
internal object StateRecordIntrospection {

  private val componentsByClass = ConcurrentHashMap<Class<*>, List<StateRecordComponent>>()

  fun components(type: Class<*>): List<StateRecordComponent> =
      componentsByClass.computeIfAbsent(type, ::readComponents)

  fun requireConstructible(type: Class<*>) {
    val components = components(type)
    try {
      type.getDeclaredConstructor(*components.map(StateRecordComponent::type).toTypedArray())
    } catch (failure: ReflectiveOperationException) {
      throw IllegalArgumentException("Audited state type has no canonical constructor: $type", failure)
    }
  }

  private fun readComponents(type: Class<*>): List<StateRecordComponent> =
      type.declaredFields
          .asSequence()
          .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
          .map(::StateRecordComponent)
          .toList()
}

internal class StateRecordComponent(private val javaField: Field) {

  init {
    javaField.isAccessible = true
  }

  val name: String
    get() = javaField.name

  val type: Class<*>
    get() = javaField.type

  val genericType: Type
    get() = javaField.genericType

  fun value(record: Any): Any? = javaField.get(record)
}
