package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.InvalidObjectException
import java.io.ObjectInputFilter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap

/** Local-file migration bridge for Coffee GB's historical Java-serialized mementos. */
internal object LegacyMementoCodec {

  private const val GAMEBOY_MEMENTO = "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento"
  private const val SESSION_MEMENTO = "eu.rekawek.coffeegb.controller.Session\$SessionMemento"

  private val allowedSupportClasses =
      setOf(
          ArrayList::class.java,
          HashMap::class.java,
      )

  private val allowedSupportClassNames =
      setOf(
          "java.lang.Enum",
          "java.lang.Integer",
          "java.lang.Number",
          "java.lang.Object",
          "java.lang.String",
          "java.util.Map\$Entry",
      )

  fun serializeGameboy(memento: Memento<Gameboy>): ByteArray =
      serialize(memento, StateLimits.GAME_SNAPSHOT)

  fun serializeSession(memento: Memento<Session>): ByteArray =
      serialize(memento, StateLimits.SESSION_SNAPSHOT)

  fun deserializeGameboy(bytes: ByteArray): Memento<Gameboy> =
      deserialize(bytes, StateLimits.GAME_SNAPSHOT, GAMEBOY_MEMENTO)

  fun deserializeSession(bytes: ByteArray): Memento<Session> =
      deserialize(bytes, StateLimits.SESSION_SNAPSHOT, SESSION_MEMENTO)

  fun hasJavaSerializationHeader(bytes: ByteArray): Boolean =
      bytes.size >= 4 &&
          bytes[0] == 0xac.toByte() &&
          bytes[1] == 0xed.toByte() &&
          bytes[2] == 0x00.toByte() &&
          bytes[3] == 0x05.toByte()

  private fun serialize(value: Any, limit: StateLimits.Payload): ByteArray {
    val output = ByteArrayOutputStream()
    ObjectOutputStream(output).use { it.writeObject(value) }
    return output.toByteArray().also {
      require(it.size <= limit.decodedBytes) {
        "${limit.description} exceeds the ${limit.decodedBytes}-byte legacy limit"
      }
    }
  }

  private fun <T> deserialize(
      bytes: ByteArray,
      limit: StateLimits.Payload,
      expectedRootClass: String,
  ): Memento<T> {
    if (bytes.size > limit.decodedBytes) {
      throw InvalidObjectException(
          "${limit.description} exceeds the ${limit.decodedBytes}-byte legacy limit")
    }
    if (!hasJavaSerializationHeader(bytes)) {
      throw InvalidObjectException("Invalid legacy ${limit.description} header")
    }

    val input = ByteArrayInputStream(bytes)
    val value =
        LegacyObjectInputStream(input, limit).use { stream ->
          val decoded = stream.readObject()
          if (decoded.javaClass.name != expectedRootClass) {
            throw InvalidObjectException(
                "Invalid legacy ${limit.description} root type: ${decoded.javaClass.name}")
          }
          if (stream.read() != -1) {
            throw InvalidObjectException("Trailing data in legacy ${limit.description}")
          }
          decoded
        }
    @Suppress("UNCHECKED_CAST")
    return value as Memento<T>
  }

  private class LegacyObjectInputStream(
      input: ByteArrayInputStream,
      private val limit: StateLimits.Payload,
  ) : ObjectInputStream(input) {

    init {
      setObjectInputFilter(::filter)
      enableResolveObject(true)
    }

    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
      val resolved = super.resolveClass(desc)
      if (!isAllowed(resolved) || !hasExpectedSerialShape(desc, resolved)) {
        throw InvalidClassException("Rejected legacy state class or shape", desc.name)
      }
      return resolved
    }

    override fun resolveProxyClass(interfaces: Array<out String>): Class<*> {
      throw InvalidClassException("Proxy classes are forbidden in legacy state")
    }

    override fun resolveObject(obj: Any?): Any? {
      when (obj) {
        is String ->
            if (obj.length > StateLimits.LEGACY_MAX_STRING_CHARS) {
              throw InvalidObjectException(
                  "Legacy state string exceeds ${StateLimits.LEGACY_MAX_STRING_CHARS} characters")
            }
        is Collection<*> ->
            if (obj.size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
              throw InvalidObjectException(
                  "Legacy state collection exceeds " +
                      "${StateLimits.LEGACY_MAX_COLLECTION_ENTRIES} entries")
            }
        is Map<*, *> ->
            if (obj.size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
              throw InvalidObjectException(
                  "Legacy state map exceeds ${StateLimits.LEGACY_MAX_COLLECTION_ENTRIES} entries")
            }
      }
      return super.resolveObject(obj)
    }

    private fun filter(info: ObjectInputFilter.FilterInfo): ObjectInputFilter.Status {
      val objectArray =
          info.serialClass()?.let { it.isArray && !it.componentType.isPrimitive } == true
      if (!isWithinGraphLimits(
              info.depth(),
              info.references(),
              info.streamBytes(),
              info.arrayLength(),
              objectArray,
              limit.decodedBytes,
          )) {
        return ObjectInputFilter.Status.REJECTED
      }
      val serialClass = info.serialClass() ?: return ObjectInputFilter.Status.UNDECIDED
      return if (isAllowed(serialClass)) {
        ObjectInputFilter.Status.ALLOWED
      } else {
        ObjectInputFilter.Status.REJECTED
      }
    }
  }

  private fun isAllowed(type: Class<*>): Boolean {
    if (Proxy.isProxyClass(type)) return false
    if (type.isPrimitive) return true
    if (type.isArray) return isAllowed(type.componentType)
    if (type == Memento::class.java) return true
    if (allowedSupportClasses.contains(type) || allowedSupportClassNames.contains(type.name)) {
      return true
    }
    return type.name in MementoTypeRegistry.legacyApplicationClassNames
  }

  /** Rejects added, removed, renamed, or retyped fields even when the class name is allowed. */
  private fun hasExpectedSerialShape(descriptor: ObjectStreamClass, type: Class<*>): Boolean {
    val expected = ObjectStreamClass.lookup(type) ?: return descriptor.fields.isEmpty()
    if (descriptor.serialVersionUID != expected.serialVersionUID) return false
    fun ObjectStreamClass.shape() =
        fields.map { field -> Triple(field.name, field.typeCode, field.typeString) }
    return descriptor.shape() == expected.shape()
  }

  internal fun isWithinGraphLimits(
      depth: Long,
      references: Long,
      streamBytes: Long,
      arrayLength: Long,
      objectArray: Boolean,
      streamLimit: Int,
  ): Boolean =
      depth <= StateLimits.LEGACY_MAX_DEPTH &&
          references <= StateLimits.LEGACY_MAX_REFERENCES &&
          streamBytes <= streamLimit &&
          arrayLength <= StateLimits.LEGACY_MAX_ARRAY_LENGTH &&
          (!objectArray || arrayLength <= StateLimits.LEGACY_MAX_COLLECTION_ENTRIES)
}
