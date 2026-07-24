package eu.rekawek.coffeegb.controller

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InvalidClassException
import java.io.InvalidObjectException
import java.io.ObjectStreamConstants
import java.nio.charset.StandardCharsets

/**
 * Allocation-only pass over the constrained legacy Java stream.
 *
 * ObjectInputFilter sees arrays before allocation, but it does not expose string lengths or the
 * custom serialized sizes used by ArrayList and HashMap. This parser validates those declarations
 * without constructing any application object, then the ordinary filtered migration reader does
 * the actual compatibility decode.
 */
internal object LegacySerializationPreflight {

  fun validate(bytes: ByteArray, streamLimit: Int) {
    Parser(bytes, streamLimit).validate()
  }

  private class Parser(bytes: ByteArray, private val streamLimit: Int) {
    private val input = DataInputStream(ByteArrayInputStream(bytes))
    private val handles = mutableMapOf<Int, Handle>()
    private var nextHandle = ObjectStreamConstants.baseWireHandle
    private var allocatedArrayBytes = 0L

    fun validate() {
      if (input.readUnsignedShort() != (ObjectStreamConstants.STREAM_MAGIC.toInt() and 0xffff) ||
          input.readUnsignedShort() != (ObjectStreamConstants.STREAM_VERSION.toInt() and 0xffff)) {
        throw InvalidObjectException("Invalid legacy serialization header")
      }
      readContent()
    }

    private fun readContent(token: Int = input.readUnsignedByte()): Handle? =
        when (token) {
          ObjectStreamConstants.TC_NULL.toInt() -> null
          ObjectStreamConstants.TC_REFERENCE.toInt() -> reference()
          ObjectStreamConstants.TC_CLASSDESC.toInt() -> readNewClassDescriptor()
          ObjectStreamConstants.TC_PROXYCLASSDESC.toInt() ->
              throw InvalidClassException("Proxy classes are forbidden in legacy state")
          ObjectStreamConstants.TC_OBJECT.toInt() -> readObject()
          ObjectStreamConstants.TC_STRING.toInt() -> readString(input.readUnsignedShort().toLong())
          ObjectStreamConstants.TC_LONGSTRING.toInt() -> readString(input.readLong())
          ObjectStreamConstants.TC_ARRAY.toInt() -> readArray()
          ObjectStreamConstants.TC_CLASS.toInt() -> readClassObject()
          ObjectStreamConstants.TC_ENUM.toInt() -> readEnum()
          ObjectStreamConstants.TC_RESET.toInt() -> {
            handles.clear()
            nextHandle = ObjectStreamConstants.baseWireHandle
            null
          }
          ObjectStreamConstants.TC_EXCEPTION.toInt() ->
              throw InvalidObjectException("Exception records are forbidden in legacy state")
          else -> throw InvalidObjectException("Unexpected legacy serialization token 0x${token.toString(16)}")
        }

    private fun readNewClassDescriptor(): ClassDescriptorHandle {
      val name = input.readUTF()
      val serialVersionUid = input.readLong()
      val handle = ClassDescriptorHandle(ClassDescriptor(name, serialVersionUid))
      assign(handle)
      val flags = input.readUnsignedByte()
      handle.descriptor.flags = flags
      val fieldCount = input.readUnsignedShort()
      repeat(fieldCount) {
        val typeCode = input.readUnsignedByte().toChar()
        val fieldName = input.readUTF()
        val typeName =
            if (typeCode == 'L' || typeCode == '[') {
              val type = readContent() as? StringHandle
                  ?: throw InvalidClassException("Invalid legacy field descriptor", name)
              type.value
            } else {
              null
            }
        handle.descriptor.fields += FieldDescriptor(typeCode, fieldName, typeName)
      }
      readAnnotations(null)
      handle.descriptor.superDescriptor = readClassDescriptor()
      return handle
    }

    private fun readClassDescriptor(): ClassDescriptor? =
        when (val token = input.readUnsignedByte()) {
          ObjectStreamConstants.TC_NULL.toInt() -> null
          ObjectStreamConstants.TC_REFERENCE.toInt() ->
              (reference() as? ClassDescriptorHandle)?.descriptor
                  ?: throw InvalidClassException("Invalid legacy class descriptor reference")
          ObjectStreamConstants.TC_CLASSDESC.toInt() -> readNewClassDescriptor().descriptor
          ObjectStreamConstants.TC_PROXYCLASSDESC.toInt() ->
              throw InvalidClassException("Proxy classes are forbidden in legacy state")
          else -> throw InvalidClassException("Invalid legacy class descriptor token 0x${token.toString(16)}")
        }

    private fun readObject(): Handle {
      val descriptor = readClassDescriptor()
          ?: throw InvalidClassException("Legacy object has no class descriptor")
      val handle = ObjectHandle
      assign(handle)
      val hierarchy = generateSequence(descriptor) { it.superDescriptor }.toList().asReversed()
      for (current in hierarchy) {
        when {
          current.flags and ObjectStreamConstants.SC_ENUM.toInt() != 0 -> Unit
          current.flags and ObjectStreamConstants.SC_SERIALIZABLE.toInt() != 0 -> {
            val primitiveFields = mutableMapOf<String, Long>()
            current.fields.forEach { field ->
              readField(field)?.let { primitiveFields[field.name] = it }
            }
            validateDefaultCollectionSize(current.name, primitiveFields)
            if (current.flags and ObjectStreamConstants.SC_WRITE_METHOD.toInt() != 0) {
              readAnnotations(current.name)
            }
          }
          else ->
              throw InvalidClassException(
                  "Externalizable legacy classes are forbidden",
                  current.name,
              )
        }
      }
      return handle
    }

    private fun readField(field: FieldDescriptor): Long? =
        when (field.typeCode) {
          'B' -> input.readByte().toLong()
          'C' -> input.readChar().code.toLong()
          'D' -> input.readLong()
          'F' -> input.readInt().toLong()
          'I' -> input.readInt().toLong()
          'J' -> input.readLong()
          'S' -> input.readShort().toLong()
          'Z' -> if (input.readBoolean()) 1 else 0
          'L', '[' -> {
            readContent()
            null
          }
          else -> throw InvalidClassException("Invalid legacy field type ${field.typeCode}")
        }

    private fun readArray(): Handle {
      val descriptor = readClassDescriptor()
          ?: throw InvalidClassException("Legacy array has no descriptor")
      assign(ObjectHandle)
      val length = input.readInt()
      val allocation = LegacyArrayShapes.allocationBytes(descriptor.name, length)
      allocatedArrayBytes = Math.addExact(allocatedArrayBytes, allocation)
      if (allocatedArrayBytes > StateLimits.LEGACY_MAX_ARRAY_BYTES ||
          allocatedArrayBytes > streamLimit) {
        throw InvalidObjectException(
            "Legacy arrays exceed ${StateLimits.LEGACY_MAX_ARRAY_BYTES} allocation bytes")
      }
      when (descriptor.name) {
        "[B", "[Z" -> skipFully(length.toLong())
        "[I" -> skipFully(Math.multiplyExact(length.toLong(), 4))
        "[J" -> skipFully(Math.multiplyExact(length.toLong(), 8))
        "[[I", "[Leu.rekawek.coffeegb.core.memento.Memento;" ->
            repeat(length) { readContent() }
        else -> throw InvalidClassException("Unaudited legacy array shape", descriptor.name)
      }
      return ObjectHandle
    }

    private fun readString(encodedBytes: Long): StringHandle {
      if (encodedBytes < 0 || encodedBytes > StateLimits.LEGACY_MAX_STRING_BYTES) {
        throw InvalidObjectException(
            "Legacy state string exceeds ${StateLimits.LEGACY_MAX_STRING_BYTES} encoded bytes")
      }
      val capture = encodedBytes <= 4_096
      val value =
          if (capture) {
            ByteArray(encodedBytes.toInt()).also(input::readFully)
                .toString(StandardCharsets.ISO_8859_1)
          } else {
            skipFully(encodedBytes)
            null
          }
      return StringHandle(value).also(::assign)
    }

    private fun readClassObject(): Handle {
      readClassDescriptor() ?: throw InvalidClassException("Legacy class object has no descriptor")
      return ObjectHandle.also(::assign)
    }

    private fun readEnum(): Handle {
      readClassDescriptor() ?: throw InvalidClassException("Legacy enum has no descriptor")
      assign(ObjectHandle)
      if (readContent() !is StringHandle) {
        throw InvalidObjectException("Legacy enum constant name is not a string")
      }
      return ObjectHandle
    }

    private fun readAnnotations(owner: String?) {
      val primitivePrefix = ByteArrayOutputStream(8)
      while (true) {
        when (val token = input.readUnsignedByte()) {
          ObjectStreamConstants.TC_ENDBLOCKDATA.toInt() -> {
            validateCustomCollectionData(owner, primitivePrefix.toByteArray())
            return
          }
          ObjectStreamConstants.TC_BLOCKDATA.toInt() ->
              readBlock(input.readUnsignedByte(), primitivePrefix)
          ObjectStreamConstants.TC_BLOCKDATALONG.toInt() -> {
            val length = input.readInt()
            if (length < 0) throw InvalidObjectException("Negative legacy block length")
            readBlock(length, primitivePrefix)
          }
          else -> {
            validateCustomCollectionData(owner, primitivePrefix.toByteArray())
            primitivePrefix.reset()
            readContent(token)
          }
        }
      }
    }

    private fun readBlock(length: Int, prefix: ByteArrayOutputStream) {
      val capture = minOf(length, 8 - prefix.size())
      if (capture > 0) {
        ByteArray(capture).also(input::readFully).let(prefix::writeBytes)
      }
      skipFully((length - capture).toLong())
    }

    private fun validateDefaultCollectionSize(name: String, fields: Map<String, Long>) {
      if (name == "java.util.ArrayList") {
        validateCollectionSize(fields["size"] ?: -1, "ArrayList")
      }
    }

    private fun validateCustomCollectionData(name: String?, prefix: ByteArray) {
      if (name == "java.util.ArrayList" && prefix.size >= 4) {
        validateCollectionSize(intAt(prefix, 0).toLong(), "ArrayList")
      }
      if (name == "java.util.HashMap") {
        if (prefix.isEmpty()) return
        if (prefix.size < 8) throw InvalidObjectException("Truncated legacy HashMap declaration")
        val capacity = intAt(prefix, 0).toLong()
        val size = intAt(prefix, 4).toLong()
        validateCollectionSize(capacity, "HashMap capacity")
        validateCollectionSize(size, "HashMap")
      }
    }

    private fun validateCollectionSize(size: Long, description: String) {
      if (size < 0 || size > StateLimits.LEGACY_MAX_COLLECTION_ENTRIES) {
        throw InvalidObjectException(
            "Legacy $description exceeds ${StateLimits.LEGACY_MAX_COLLECTION_ENTRIES} entries")
      }
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun reference(): Handle {
      val handle = input.readInt()
      return handles[handle] ?: throw InvalidObjectException("Invalid legacy handle 0x${handle.toString(16)}")
    }

    private fun assign(handle: Handle) {
      handles[nextHandle++] = handle
    }

    private fun skipFully(bytes: Long) {
      if (bytes < 0 || bytes > input.available().toLong()) throw EOFException("Truncated legacy state")
      var remaining = bytes
      while (remaining > 0) {
        remaining -= input.skip(remaining)
      }
    }
  }

  private sealed interface Handle

  private data object ObjectHandle : Handle

  private data class StringHandle(val value: String?) : Handle

  private data class ClassDescriptorHandle(val descriptor: ClassDescriptor) : Handle

  private data class ClassDescriptor(
      val name: String,
      val serialVersionUid: Long,
      var flags: Int = 0,
      val fields: MutableList<FieldDescriptor> = mutableListOf(),
      var superDescriptor: ClassDescriptor? = null,
  )

  private data class FieldDescriptor(
      val typeCode: Char,
      val name: String,
      val typeName: String?,
  )
}

/** Exact array descriptors observed in the supported Coffee GB legacy memento schemas. */
internal object LegacyArrayShapes {
  private val elementWidths =
      mapOf(
          "[B" to 1,
          "[Z" to 1,
          "[I" to 4,
          "[J" to 8,
          "[[I" to Long.SIZE_BYTES,
          "[Ljava.lang.Object;" to Long.SIZE_BYTES,
          "[Ljava.util.Map\$Entry;" to Long.SIZE_BYTES,
          "[Leu.rekawek.coffeegb.core.memento.Memento;" to Long.SIZE_BYTES,
      )

  fun isAllowed(type: Class<*>): Boolean = type.name in elementWidths

  fun allocationBytes(descriptor: String, length: Int): Long {
    if (length < 0 || length > StateLimits.LEGACY_MAX_ARRAY_LENGTH) {
      throw InvalidObjectException("Invalid legacy array length $length")
    }
    val width = elementWidths[descriptor]
        ?: throw InvalidClassException("Unaudited legacy array shape", descriptor)
    val objectArrayLimit =
        if (descriptor == "[Ljava.util.Map\$Entry;") {
          StateLimits.LEGACY_MAX_MAP_TABLE_ENTRIES
        } else {
          StateLimits.LEGACY_MAX_COLLECTION_ENTRIES
        }
    if ((descriptor.startsWith("[[") || descriptor.startsWith("[L")) &&
        length > objectArrayLimit) {
      throw InvalidObjectException(
          "Legacy object array exceeds $objectArrayLimit entries")
    }
    val bytes = Math.multiplyExact(length.toLong(), width.toLong())
    if (bytes > StateLimits.LEGACY_MAX_ARRAY_BYTES) {
      throw InvalidObjectException(
          "Legacy array exceeds ${StateLimits.LEGACY_MAX_ARRAY_BYTES} allocation bytes")
    }
    return bytes
  }
}
