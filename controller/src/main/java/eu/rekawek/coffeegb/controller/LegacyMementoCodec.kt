package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.genie.GameGeniePatch
import eu.rekawek.coffeegb.core.genie.GameSharkPatch
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
          GameGeniePatch::class.java,
          GameSharkPatch::class.java,
      )

  private val allowedSupportClassNames =
      setOf(
          "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWrite",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWrite",
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
      if (!isAllowed(resolved)) {
        throw InvalidClassException("Rejected legacy state class", desc.name)
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
      if (info.depth() > StateLimits.LEGACY_MAX_DEPTH ||
          info.references() > StateLimits.LEGACY_MAX_REFERENCES ||
          info.streamBytes() > limit.decodedBytes ||
          info.arrayLength() > StateLimits.LEGACY_MAX_ARRAY_LENGTH) {
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
    if (type.isEnum) {
      return type.name.startsWith("eu.rekawek.coffeegb.core.")
    }
    // The dedicated marker plus Java's record constraint limits this to Coffee GB's compiled,
    // data-only core mementos; arbitrary application classes implementing Memento are rejected.
    return Memento::class.java.isAssignableFrom(type) &&
        ((type.isRecord && type.name.startsWith("eu.rekawek.coffeegb.core.")) ||
            type.name == SESSION_MEMENTO)
  }
}
