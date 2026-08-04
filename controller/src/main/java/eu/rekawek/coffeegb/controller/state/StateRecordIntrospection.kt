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

  private val metadataByClass = ConcurrentHashMap<Class<*>, StateRecordMetadata>()

  fun components(type: Class<*>): List<StateRecordComponent> =
      metadataByClass.computeIfAbsent(type, ::readMetadata).components

  fun requireConstructible(type: Class<*>) {
    metadataByClass.computeIfAbsent(type, ::readMetadata)
  }

  private fun readMetadata(type: Class<*>): StateRecordMetadata {
    val fields =
        type.declaredFields
          .asSequence()
          .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
          .toList()
    val constructor =
        type.declaredConstructors.singleOrNull { candidate ->
          candidate.parameterCount == fields.size &&
              candidate.parameterTypes.toList().groupingBy { it }.eachCount() ==
                  fields.groupingBy(Field::getType).eachCount()
        } ?: throw IllegalArgumentException("Audited state type has no canonical constructor: $type")
    val remaining = fields.toMutableList()
    val components =
        constructor.parameterTypes.map { parameterType ->
          val index = remaining.indexOfFirst { it.type == parameterType }
          if (index < 0) throw IllegalArgumentException("Invalid canonical constructor for $type")
          StateRecordComponent(remaining.removeAt(index))
        }
    return StateRecordMetadata(components)
  }
}

private class StateRecordMetadata(val components: List<StateRecordComponent>)

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
