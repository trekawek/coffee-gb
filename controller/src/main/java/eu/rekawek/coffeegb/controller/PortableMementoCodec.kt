package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap
import java.util.zip.CRC32

/**
 * Bounded, explicit-type transport for the current memento graph.
 *
 * This is the compatibility seam needed to remove Java native serialization from netplay before
 * the immutable State v2 DTOs land. The envelope is versioned and checksummed, every concrete
 * record/enum has an audited stable ID, and decoding builds a detached graph without invoking any
 * service or emulator object.
 */
internal object PortableMementoCodec {

  const val FORMAT_VERSION: Int = 1

  private const val MAGIC = 0x43474253 // CGBS
  private const val HEADER_BYTES = 10
  private const val TRAILER_BYTES = 4
  private const val GAMEBOY_ROOT: Int = 1
  private const val SESSION_ROOT: Int = 2

  private const val NULL: Int = 0
  private const val INTEGER: Int = 1
  private const val LONG: Int = 2
  private const val BOOLEAN: Int = 3
  private const val DOUBLE: Int = 4
  private const val STRING: Int = 5
  private const val ENUM: Int = 6
  private const val RECORD: Int = 7
  private const val BYTE_ARRAY: Int = 8
  private const val INT_ARRAY: Int = 9
  private const val LONG_ARRAY: Int = 10
  private const val BOOLEAN_ARRAY: Int = 11
  private const val OBJECT_ARRAY: Int = 12
  private const val LIST: Int = 13
  private const val MAP: Int = 14

  private val recordIds by lazy {
    MementoTypeRegistry.recordClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }

  private val enumIds by lazy {
    MementoTypeRegistry.enumClasses.withIndex().associate { (index, type) -> type to index + 1 }
  }

  fun encodeGameboy(memento: Memento<Gameboy>): ByteArray =
      encode(GAMEBOY_ROOT, StateLimits.GAME_SNAPSHOT) { writeValue(memento, 0) }

  fun encodeSession(memento: Memento<Session>): ByteArray {
    if (memento !is Session.SessionMemento) {
      throw IllegalArgumentException("Unsupported session memento ${memento.javaClass.name}")
    }
    return encode(SESSION_ROOT, StateLimits.SESSION_SNAPSHOT) {
      writeValue(memento.gameboyMemento, 0)
      writeValue(memento.serialEndpointMemento, 0)
    }
  }

  fun decodeGameboy(bytes: ByteArray): Memento<Gameboy> {
    val value = decode(bytes, GAMEBOY_ROOT, StateLimits.GAME_SNAPSHOT) {
      readValue(Memento::class.java, 0)
    }
    if (value?.javaClass?.name != "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento") {
      throw DecodeException("Portable game snapshot has the wrong root type")
    }
    @Suppress("UNCHECKED_CAST")
    return value as Memento<Gameboy>
  }

  fun decodeSession(bytes: ByteArray): Memento<Session> =
      decode(bytes, SESSION_ROOT, StateLimits.SESSION_SNAPSHOT) {
        val gameboy = readValue(Memento::class.java, 0)
        val serial = readValue(Memento::class.java, 0)
        if (gameboy?.javaClass?.name != "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento") {
          throw DecodeException("Portable session snapshot has the wrong game root type")
        }
        @Suppress("UNCHECKED_CAST")
        Session.SessionMemento(
            gameboy as Memento<Gameboy>,
            serial as Memento<eu.rekawek.coffeegb.core.serial.SerialEndpoint>?,
        )
      }

  fun hasHeader(bytes: ByteArray): Boolean =
      bytes.size >= HEADER_BYTES && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

  private fun encode(
      root: Int,
      limit: StateLimits.Payload,
      block: Encoder.() -> Unit,
  ): ByteArray {
    val payloadOutput =
        LimitedByteArrayOutputStream(limit.decodedBytes - HEADER_BYTES - TRAILER_BYTES)
    DataOutputStream(payloadOutput).use { output -> Encoder(output).block() }
    val payload = payloadOutput.toByteArray()
    val checksum = CRC32().also { it.update(payload) }.value.toInt()
    return ByteArrayOutputStream(HEADER_BYTES + payload.size + TRAILER_BYTES).use { bytes ->
      DataOutputStream(bytes).use { output ->
        output.writeInt(MAGIC)
        output.writeByte(FORMAT_VERSION)
        output.writeByte(root)
        output.writeInt(payload.size)
        output.write(payload)
        output.writeInt(checksum)
      }
      bytes.toByteArray()
    }
  }

  private fun <T> decode(
      bytes: ByteArray,
      expectedRoot: Int,
      limit: StateLimits.Payload,
      block: Decoder.() -> T,
  ): T {
    if (bytes.size > limit.decodedBytes) {
      throw DecodeException("${limit.description} exceeds ${limit.decodedBytes} bytes")
    }
    if (bytes.size < HEADER_BYTES + TRAILER_BYTES) {
      throw DecodeException("Truncated portable ${limit.description}")
    }
    val header = ByteBuffer.wrap(bytes)
    if (header.int != MAGIC) throw DecodeException("Unsupported state format")
    val version = header.get().toInt() and 0xff
    if (version != FORMAT_VERSION) {
      throw DecodeException("Unsupported portable state version $version")
    }
    val root = header.get().toInt() and 0xff
    if (root != expectedRoot) throw DecodeException("Portable state root type mismatch")
    val payloadLength = header.int
    if (payloadLength < 0 || payloadLength > limit.decodedBytes - HEADER_BYTES - TRAILER_BYTES) {
      throw DecodeException("Invalid portable ${limit.description} payload length $payloadLength")
    }
    val expectedSize = Math.addExact(HEADER_BYTES, Math.addExact(payloadLength, TRAILER_BYTES))
    if (bytes.size != expectedSize) throw DecodeException("Truncated or trailing portable state")
    val expectedChecksum = ByteBuffer.wrap(bytes, HEADER_BYTES + payloadLength, 4).int
    val actualChecksum =
        CRC32().also { it.update(bytes, HEADER_BYTES, payloadLength) }.value.toInt()
    if (actualChecksum != expectedChecksum) throw DecodeException("Portable state checksum mismatch")

    try {
      val input = DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, payloadLength))
      val decoder = Decoder(input)
      val value = decoder.block()
      if (input.available() != 0) throw DecodeException("Trailing portable state payload data")
      return value
    } catch (e: DecodeException) {
      throw e
    } catch (e: IOException) {
      throw DecodeException("Corrupt or truncated portable ${limit.description}", e)
    } catch (e: ReflectiveOperationException) {
      throw DecodeException("Portable state record could not be constructed", e)
    } catch (e: RuntimeException) {
      throw DecodeException("Invalid portable ${limit.description}", e)
    }
  }

  private class Encoder(private val output: DataOutputStream) {
    private var references = 0L

    fun writeValue(value: Any?, depth: Int) {
      checkDepth(depth)
      if (value == null) {
        output.writeByte(NULL)
        return
      }
      countReference()
      when (value) {
        is Int -> {
          output.writeByte(INTEGER)
          output.writeInt(value)
        }
        is Long -> {
          output.writeByte(LONG)
          output.writeLong(value)
        }
        is Boolean -> {
          output.writeByte(BOOLEAN)
          output.writeBoolean(value)
        }
        is Double -> {
          output.writeByte(DOUBLE)
          output.writeDouble(value)
        }
        is String -> writeString(value)
        is ByteArray -> writeByteArray(value)
        is IntArray -> writeIntArray(value)
        is LongArray -> writeLongArray(value)
        is BooleanArray -> writeBooleanArray(value)
        is List<*> -> writeList(value, depth)
        is Map<*, *> -> writeMap(value, depth)
        is Enum<*> -> writeEnum(value)
        else ->
            if (value.javaClass.isArray) {
              writeObjectArray(value, depth)
            } else {
              writeRecord(value, depth)
            }
      }
    }

    private fun writeString(value: String) {
      if (value.length > StateLimits.LEGACY_MAX_STRING_CHARS) {
        throw IllegalArgumentException("State string is too long")
      }
      val bytes = value.toByteArray(StandardCharsets.UTF_8)
      output.writeByte(STRING)
      output.writeInt(bytes.size)
      output.write(bytes)
    }

    private fun writeByteArray(value: ByteArray) {
      checkPrimitiveArray(value.size)
      output.writeByte(BYTE_ARRAY)
      output.writeInt(value.size)
      output.write(value)
    }

    private fun writeIntArray(value: IntArray) {
      checkPrimitiveArray(value.size)
      output.writeByte(INT_ARRAY)
      output.writeInt(value.size)
      value.forEach(output::writeInt)
    }

    private fun writeLongArray(value: LongArray) {
      checkPrimitiveArray(value.size)
      output.writeByte(LONG_ARRAY)
      output.writeInt(value.size)
      value.forEach(output::writeLong)
    }

    private fun writeBooleanArray(value: BooleanArray) {
      checkPrimitiveArray(value.size)
      output.writeByte(BOOLEAN_ARRAY)
      output.writeInt(value.size)
      value.forEach(output::writeBoolean)
    }

    private fun writeObjectArray(value: Any, depth: Int) {
      val size = ReflectArray.getLength(value)
      checkCollection(size)
      output.writeByte(OBJECT_ARRAY)
      output.writeInt(size)
      repeat(size) { writeValue(ReflectArray.get(value, it), depth + 1) }
    }

    private fun writeList(value: List<*>, depth: Int) {
      checkCollection(value.size)
      output.writeByte(LIST)
      output.writeInt(value.size)
      value.forEach { writeValue(it, depth + 1) }
    }

    private fun writeMap(value: Map<*, *>, depth: Int) {
      checkCollection(value.size)
      val entries =
          value.entries.map {
            val key = it.key as? Int
                ?: throw IllegalArgumentException("Only integer state-map keys are supported")
            key to it.value
          }.sortedBy(Pair<Int, Any?>::first)
      output.writeByte(MAP)
      output.writeInt(entries.size)
      entries.forEach { (key, entryValue) ->
        writeValue(key, depth + 1)
        writeValue(entryValue, depth + 1)
      }
    }

    private fun writeEnum(value: Enum<*>) {
      val id = enumIds[value.javaClass]
          ?: throw IllegalArgumentException("Unregistered state enum ${value.javaClass.name}")
      output.writeByte(ENUM)
      output.writeShort(id)
      output.writeShort(value.ordinal)
    }

    private fun writeRecord(value: Any, depth: Int) {
      val type = value.javaClass
      val id = recordIds[type]
          ?: throw IllegalArgumentException("Unregistered state record ${type.name}")
      val components = type.recordComponents
      output.writeByte(RECORD)
      output.writeShort(id)
      output.writeShort(components.size)
      components.forEach { component ->
        component.accessor.trySetAccessible()
        writeValue(component.accessor.invoke(value), depth + 1)
      }
    }

    private fun checkDepth(depth: Int) {
      if (depth > StateLimits.LEGACY_MAX_DEPTH) throw IllegalArgumentException("State is too deep")
    }

    private fun countReference() {
      references++
      if (references > StateLimits.LEGACY_MAX_REFERENCES) {
        throw IllegalArgumentException("State has too many references")
      }
    }
  }

  private class Decoder(private val input: DataInputStream) {
    private var references = 0L

    fun readValue(expectedType: Type?, depth: Int): Any? {
      checkDepth(depth)
      val tag = input.readUnsignedByte()
      if (tag == NULL) {
        if (expectedType.rawClass()?.isPrimitive == true) {
          throw DecodeException("Null primitive state value")
        }
        return null
      }
      countReference()
      val value =
          when (tag) {
            INTEGER -> input.readInt()
            LONG -> input.readLong()
            BOOLEAN -> input.readBoolean()
            DOUBLE -> input.readDouble()
            STRING -> readString()
            ENUM -> readEnum()
            RECORD -> readRecord(depth)
            BYTE_ARRAY -> readByteArray()
            INT_ARRAY -> readIntArray()
            LONG_ARRAY -> readLongArray()
            BOOLEAN_ARRAY -> readBooleanArray()
            OBJECT_ARRAY -> readObjectArray(expectedType.rawClass(), depth)
            LIST -> readList(expectedType, depth)
            MAP -> readMap(expectedType, depth)
            else -> throw DecodeException("Unknown portable state tag $tag")
          }
      requireExpected(value, expectedType)
      return value
    }

    private fun readString(): String {
      val size = input.readInt()
      val maxBytes = StateLimits.LEGACY_MAX_STRING_CHARS * 4
      if (size < 0 || size > maxBytes || size > input.available()) {
        throw DecodeException("Invalid portable state string length $size")
      }
      val bytes = ByteArray(size).also(input::readFully)
      val value = String(bytes, StandardCharsets.UTF_8)
      if (value.length > StateLimits.LEGACY_MAX_STRING_CHARS ||
          !value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
        throw DecodeException("Invalid portable state string")
      }
      return value
    }

    private fun readEnum(): Any {
      val id = input.readUnsignedShort()
      val type = MementoTypeRegistry.enumClasses.getOrNull(id - 1)
          ?: throw DecodeException("Unknown portable state enum ID $id")
      val ordinal = input.readUnsignedShort()
      return type.enumConstants.getOrNull(ordinal)
          ?: throw DecodeException("Invalid ${type.name} ordinal $ordinal")
    }

    private fun readRecord(depth: Int): Any {
      val id = input.readUnsignedShort()
      val type = MementoTypeRegistry.recordClasses.getOrNull(id - 1)
          ?: throw DecodeException("Unknown portable state record ID $id")
      val components = type.recordComponents
      val count = input.readUnsignedShort()
      if (count != components.size) {
        throw DecodeException("Invalid ${type.name} component count $count")
      }
      val args = components.map { readValue(it.genericType, depth + 1) }.toTypedArray()
      val constructor = type.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
      constructor.trySetAccessible()
      try {
        return constructor.newInstance(*args)
      } catch (e: InvocationTargetException) {
        throw DecodeException("Invalid ${type.name} value", e.targetException)
      }
    }

    private fun readByteArray(): ByteArray {
      val size = readPrimitiveArraySize(1)
      return ByteArray(size).also(input::readFully)
    }

    private fun readIntArray(): IntArray {
      val size = readPrimitiveArraySize(Int.SIZE_BYTES)
      return IntArray(size) { input.readInt() }
    }

    private fun readLongArray(): LongArray {
      val size = readPrimitiveArraySize(Long.SIZE_BYTES)
      return LongArray(size) { input.readLong() }
    }

    private fun readBooleanArray(): BooleanArray {
      val size = readPrimitiveArraySize(1)
      return BooleanArray(size) { input.readBoolean() }
    }

    private fun readPrimitiveArraySize(elementBytes: Int): Int {
      val size = input.readInt()
      checkPrimitiveArray(size)
      if (size.toLong() * elementBytes > input.available()) {
        throw DecodeException("Truncated portable state array")
      }
      return size
    }

    private fun readObjectArray(expectedType: Class<*>?, depth: Int): Any {
      if (expectedType?.isArray != true || expectedType.componentType.isPrimitive) {
        throw DecodeException("Unexpected portable object array")
      }
      val size = input.readInt()
      checkCollection(size)
      val result = ReflectArray.newInstance(expectedType.componentType, size)
      repeat(size) {
        ReflectArray.set(result, it, readValue(expectedType.componentType, depth + 1))
      }
      return result
    }

    private fun readList(expectedType: Type?, depth: Int): List<Any?> {
      val size = input.readInt()
      checkCollection(size)
      val elementType = expectedType.typeArguments()?.singleOrNull()
      return ArrayList<Any?>(size).also { result ->
        repeat(size) { result += readValue(elementType, depth + 1) }
      }
    }

    private fun readMap(expectedType: Type?, depth: Int): Map<Int, Any?> {
      val size = input.readInt()
      checkCollection(size)
      val arguments = expectedType.typeArguments()
      val keyType = arguments?.getOrNull(0) ?: Int::class.javaObjectType
      val valueType = arguments?.getOrNull(1)
      if (keyType.rawClass() != Int::class.javaObjectType) {
        throw DecodeException("Portable state maps require integer keys")
      }
      return HashMap<Int, Any?>(checkedHashMapCapacity(size)).also { result ->
        var previousKey: Int? = null
        repeat(size) {
          val key = readValue(keyType, depth + 1) as Int
          val precedingKey = previousKey
          if (precedingKey != null && key <= precedingKey) {
            throw DecodeException("Portable state map keys are not strictly ordered")
          }
          previousKey = key
          result[key] = readValue(valueType, depth + 1)
        }
      }
    }

    private fun checkDepth(depth: Int) {
      if (depth > StateLimits.LEGACY_MAX_DEPTH) throw DecodeException("State is too deep")
    }

    private fun countReference() {
      references++
      if (references > StateLimits.LEGACY_MAX_REFERENCES) {
        throw DecodeException("State has too many references")
      }
    }
  }

  private fun checkPrimitiveArray(size: Int) {
    if (size < 0 || size.toLong() > StateLimits.LEGACY_MAX_ARRAY_LENGTH) {
      throw DecodeException("Invalid state array length $size")
    }
  }

  private fun checkCollection(size: Int) {
    if (size < 0 || size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
      throw DecodeException("Invalid state collection length $size")
    }
  }

  private fun checkedHashMapCapacity(size: Int): Int =
      Math.addExact(size, Math.floorDiv(size, 3)).coerceAtLeast(1)

  private fun requireExpected(value: Any, expectedType: Type?) {
    if (expectedType == null) return
    val expectedClass = expectedType.rawClass() ?: return
    val boxed =
        when (expectedClass) {
          Int::class.javaPrimitiveType -> Int::class.javaObjectType
          Long::class.javaPrimitiveType -> Long::class.javaObjectType
          Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
          Double::class.javaPrimitiveType -> Double::class.javaObjectType
          else -> expectedClass
        }
    if (!boxed.isInstance(value)) {
      throw DecodeException("Expected ${expectedClass.name}, received ${value.javaClass.name}")
    }
  }

  private fun Type?.rawClass(): Class<*>? =
      when (this) {
        is Class<*> -> this
        is ParameterizedType -> rawType as? Class<*>
        is GenericArrayType ->
            genericComponentType.rawClass()?.let { ReflectArray.newInstance(it, 0).javaClass }
        is WildcardType -> upperBounds.singleOrNull().rawClass()
        else -> null
      }

  private fun Type?.typeArguments(): Array<Type>? =
      (this as? ParameterizedType)?.actualTypeArguments

  private class LimitedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) {
      requireCapacity(1)
      super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
      requireCapacity(length)
      super.write(bytes, offset, length)
    }

    private fun requireCapacity(additional: Int) {
      if (additional < 0 || additional > limit - size()) {
        throw IllegalArgumentException("Portable state exceeds $limit bytes")
      }
    }
  }

  internal class DecodeException(message: String, cause: Throwable? = null) :
      IOException(message, cause)
}
