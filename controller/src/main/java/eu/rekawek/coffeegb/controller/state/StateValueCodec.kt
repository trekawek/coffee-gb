package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits

/** Explicit numeric v1 codec for every Phase-1 [StateKind]. */
internal object StateValueCodec {
  private const val NULL = 0
  private const val INT32 = 1
  private const val INT64 = 2
  private const val BOOLEAN = 3
  private const val FLOAT64 = 4
  private const val STRING = 5
  private const val ENUM = 6
  private const val RECORD = 7
  private const val BYTES = 8
  private const val INT32_ARRAY = 9
  private const val INT64_ARRAY = 10
  private const val BOOLEAN_ARRAY = 11
  private const val OBJECT_ARRAY = 12
  private const val LIST = 13
  private const val INT32_MAP = 14

  class Encoder(private val writer: PortableWriter) {
    private var occurrences = 0

    fun write(value: StateValue, depth: Int = 0) {
      checkDepth(depth)
      countOccurrence()
      if (value === NullState) {
        writer.writeByte(NULL)
        return
      }
      when (value) {
        is Int32State -> {
          writer.writeByte(INT32)
          writer.writeInt(value.value)
        }
        is Int64State -> {
          writer.writeByte(INT64)
          writer.writeLong(value.value)
        }
        is BooleanState -> {
          writer.writeByte(BOOLEAN)
          writer.writeBoolean(value.value)
        }
        is Float64State -> {
          writer.writeByte(FLOAT64)
          writer.writeLong(java.lang.Double.doubleToRawLongBits(value.value))
        }
        is StringState -> {
          writer.writeByte(STRING)
          writer.writeString(value.value)
        }
        is EnumState -> writeEnum(value)
        is RecordState -> writeRecord(value, depth)
        is BytesState -> {
          writer.writeByte(BYTES)
          requireArray(value.size, Byte.SIZE_BYTES)
          val bytes = value.copyValue()
          writer.writeU32(bytes.size.toLong())
          writer.writeBytes(bytes)
        }
        is Int32ArrayState -> {
          writer.writeByte(INT32_ARRAY)
          requireArray(value.size, Int.SIZE_BYTES)
          val values = value.copyValue()
          writer.writeU32(values.size.toLong())
          values.forEach(writer::writeInt)
        }
        is Int64ArrayState -> {
          writer.writeByte(INT64_ARRAY)
          requireArray(value.size, Long.SIZE_BYTES)
          val values = value.copyValue()
          writer.writeU32(values.size.toLong())
          values.forEach(writer::writeLong)
        }
        is BooleanArrayState -> {
          writer.writeByte(BOOLEAN_ARRAY)
          requireArray(value.size, 1)
          val values = value.copyValue()
          writer.writeU32(values.size.toLong())
          values.forEach(writer::writeBoolean)
        }
        is ObjectArrayState -> writeValues(OBJECT_ARRAY, value.values, depth)
        is ListState -> writeValues(LIST, value.values, depth)
        is Int32MapState -> writeMap(value, depth)
        NullState -> error("handled above")
      }
    }

    private fun writeEnum(value: EnumState) {
      val type =
          StateTypeRegistry.enumClasses.getOrNull(value.typeId - 1)
              ?: throw StateEncodeException("Unknown detached enum type ID ${value.typeId}")
      val values = PortableEnumValues.values(value.typeId, type)
      if (value.ordinal !in values.indices) {
        throw StateEncodeException("Invalid detached enum ordinal ${value.ordinal}")
      }
      writer.writeByte(ENUM)
      writer.writeU32(value.typeId.toLong())
      // Values are explicit one-based v1 IDs. The Java declaration ordinal is never serialized.
      writer.writeU32((value.ordinal + 1).toLong())
    }

    private fun writeRecord(value: RecordState, depth: Int) {
      val type =
          StateTypeRegistry.recordClasses.getOrNull(value.typeId - 1)
              ?: throw StateEncodeException("Unknown detached record type ID ${value.typeId}")
      val components = StateRecordIntrospection.components(type)
      if (value.fields.size != components.size ||
          value.fields.indices.any { value.fields[it].name != components[it].name }) {
        throw StateEncodeException("Detached record ${type.name} has an invalid field inventory")
      }
      writer.writeByte(RECORD)
      writer.writeU32(value.typeId.toLong())
      writer.writeU32(value.fields.size.toLong())
      value.fields.forEach { field ->
        writer.writeString(field.name)
        write(field.value, depth + 1)
      }
    }

    private fun writeValues(tag: Int, values: List<StateValue>, depth: Int) {
      requireCollection(values.size)
      writer.writeByte(tag)
      writer.writeU32(values.size.toLong())
      values.forEach { write(it, depth + 1) }
    }

    private fun writeMap(value: Int32MapState, depth: Int) {
      requireCollection(value.entries.size)
      var previous: Int? = null
      value.entries.forEach {
        if (previous != null && it.key <= checkNotNull(previous)) {
          throw StateEncodeException("Detached map keys are not strictly increasing")
        }
        previous = it.key
      }
      writer.writeByte(INT32_MAP)
      writer.writeU32(value.entries.size.toLong())
      value.entries.forEach {
        writer.writeInt(it.key)
        write(it.value, depth + 1)
      }
    }

    private fun countOccurrence() {
      occurrences++
      if (occurrences > StateLimits.PORTABLE_MAX_VALUE_OCCURRENCES) {
        throw StateEncodeException("Portable state has too many value occurrences")
      }
    }

    private fun checkDepth(depth: Int) {
      if (depth > StateLimits.PORTABLE_MAX_GRAPH_DEPTH) {
        throw StateEncodeException("Portable state exceeds its graph-depth limit")
      }
    }

    private fun requireCollection(size: Int) {
      if (size !in 0..StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES) {
        throw StateEncodeException("Portable collection length $size is invalid")
      }
    }

    private fun requireArray(size: Int, width: Int) {
      try {
        PortableBounds.arrayBytes(size.toLong(), width.toLong())
      } catch (failure: StateDecodeException) {
        throw StateEncodeException(failure.message ?: "Portable array is invalid", failure)
      }
    }
  }

  class Decoder(private val reader: PortableReader) {
    private var occurrences = 0

    fun read(depth: Int = 0): StateValue {
      checkDepth(depth)
      val tag = reader.readByte()
      countOccurrence()
      if (tag == NULL) return NullState
      return when (tag) {
        INT32 -> Int32State(reader.readInt())
        INT64 -> Int64State(reader.readLong())
        BOOLEAN -> BooleanState(reader.readBoolean())
        FLOAT64 -> Float64State(java.lang.Double.longBitsToDouble(reader.readLong()))
        STRING -> StringState(reader.readString())
        ENUM -> readEnum()
        RECORD -> readRecord(depth)
        BYTES -> readBytes()
        INT32_ARRAY -> readIntArray()
        INT64_ARRAY -> readLongArray()
        BOOLEAN_ARRAY -> readBooleanArray()
        OBJECT_ARRAY -> ObjectArrayState(readValues(depth))
        LIST -> ListState(readValues(depth))
        INT32_MAP -> readMap(depth)
        else ->
            throw StateDecodeException(
                StateDecodeReason.MALFORMED_TAG,
                "Unknown detached value tag $tag",
            )
      }
    }

    private fun readEnum(): EnumState {
      val typeId = requireTypeId(reader.readU32(), StateTypeRegistry.enumClasses.size, "enum")
      val type = StateTypeRegistry.enumClasses[typeId - 1]
      val values = PortableEnumValues.values(typeId, type)
      val valueId = reader.readU32()
      if (valueId !in 1..values.size.toLong()) {
        throw StateDecodeException(
            StateDecodeReason.MALFORMED_ENUM,
            "Invalid detached enum value ID $valueId for type $typeId",
        )
      }
      return EnumState(typeId, valueId.toInt() - 1)
    }

    private fun readRecord(depth: Int): RecordState {
      val typeId = requireTypeId(reader.readU32(), StateTypeRegistry.recordClasses.size, "record")
      val components = StateRecordIntrospection.components(StateTypeRegistry.recordClasses[typeId - 1])
      val count =
          PortableBounds.requireCount(
              reader.readU32(),
              StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES.toLong(),
              "Detached record field count",
          )
      if (count != components.size) malformed("Detached record type $typeId has $count fields")
      val fields =
          ArrayList<StateField>(count).also { result ->
            components.forEach { component ->
              val name = reader.readString()
              if (name != component.name) {
                malformed(
                    "Detached record type $typeId field ${result.size} is $name, expected ${component.name}")
              }
              result += StateField(name, read(depth + 1))
            }
          }
      return RecordState(typeId, fields)
    }

    private fun readBytes(): BytesState {
      val count = readArrayCount(Byte.SIZE_BYTES)
      return BytesState(reader.readBytes(count, StateLimits.PORTABLE_MAX_ARRAY_BYTES))
    }

    private fun readIntArray(): Int32ArrayState {
      val count = readArrayCount(Int.SIZE_BYTES)
      return Int32ArrayState(IntArray(count) { reader.readInt() })
    }

    private fun readLongArray(): Int64ArrayState {
      val count = readArrayCount(Long.SIZE_BYTES)
      return Int64ArrayState(LongArray(count) { reader.readLong() })
    }

    private fun readBooleanArray(): BooleanArrayState {
      val count = readArrayCount(1)
      return BooleanArrayState(BooleanArray(count) { reader.readBoolean() })
    }

    private fun readArrayCount(width: Int): Int {
      val count =
          PortableBounds.requireCount(
              reader.readU32(),
              StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS.toLong(),
              "Portable array element count",
          )
      PortableBounds.arrayBytes(count.toLong(), width.toLong())
      return count
    }

    private fun readValues(depth: Int): List<StateValue> {
      val count = readCollectionCount()
      return ArrayList<StateValue>(count).also { result ->
        repeat(count) { result += read(depth + 1) }
      }
    }

    private fun readMap(depth: Int): Int32MapState {
      val count = readCollectionCount()
      val entries = ArrayList<Int32MapEntry>(count)
      var previous: Int? = null
      repeat(count) {
        val key = reader.readInt()
        if (previous != null && key <= checkNotNull(previous)) {
          malformed("Portable map keys are not strictly increasing")
        }
        previous = key
        entries += Int32MapEntry(key, read(depth + 1))
      }
      return Int32MapState(entries)
    }

    private fun readCollectionCount(): Int =
        PortableBounds.requireCount(
            reader.readU32(),
            StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES.toLong(),
            "Portable collection count",
        )

    private fun countOccurrence() {
      occurrences++
      if (occurrences > StateLimits.PORTABLE_MAX_VALUE_OCCURRENCES) {
        PortableBounds.limit("Portable state has too many value occurrences")
      }
    }

    private fun checkDepth(depth: Int) {
      if (depth > StateLimits.PORTABLE_MAX_GRAPH_DEPTH) {
        PortableBounds.limit("Portable state exceeds its graph-depth limit")
      }
    }

    private fun malformed(message: String): Nothing = PortableBounds.malformed(message)

    private fun requireTypeId(value: Long, count: Int, label: String): Int {
      if (value !in 1..count.toLong()) {
        throw StateDecodeException(
            StateDecodeReason.MALFORMED_TAG,
            "Detached $label type ID $value is not registered",
        )
      }
      return value.toInt()
    }
  }

  /**
   * Explicit StateFile-v1 enum value registry. Names are verified against production classes so a
   * source reorder/rename fails closed instead of changing portable bytes.
   */
  private object PortableEnumValues {
    private val names =
        listOf(
            listOf(
                "OPCODE",
                "EXT_OPCODE",
                "OPERAND",
                "RUNNING",
                "IRQ_WAIT_1",
                "IRQ_WAIT_2",
                "IRQ_PUSH_1",
                "IRQ_PUSH_2",
                "IRQ_JUMP",
                "STOPPED",
                "HALTED",
                "SPEED_SWITCH",
                "LOCKED",
            ),
            listOf("VBlank", "LCDC", "Timer", "Serial", "P10_13"),
            listOf("HBlank", "VBlank", "OamSearch", "PixelTransfer"),
            listOf("READING_Y", "READING_X"),
            listOf("NONE", "UNRESOLVED", "DMA", "CPU"),
            listOf("LOW", "HIGH", "REQUESTED"),
            listOf("NONE", "REVERSE_PENDING", "PREEMPT_CPU", "YIELD_CPU"),
            listOf("IDLE", "COMMAND", "READING", "WRITING"),
            listOf("HANDSHAKE", "READY", "SENDING"),
            listOf("PING", "TRANSMISSION_INDICATOR", "TRANSMISSION", "PING_INDICATOR"),
            listOf("CANCEL", "FREEZE", "BLANK_BLACK", "BLANK_COLOR0"),
        )

    fun values(typeId: Int, type: Class<*>): List<String> {
      val expected =
          names.getOrNull(typeId - 1)
              ?: throw StateDecodeException(
                  StateDecodeReason.MALFORMED_ENUM,
                  "Portable enum type $typeId has no v1 value registry",
              )
      val actual = type.enumConstants.map { (it as Enum<*>).name }
      if (actual != expected) {
        throw StateDecodeException(
            StateDecodeReason.UNSUPPORTED_SECTION_VERSION,
            "Portable enum type $typeId no longer matches its v1 value registry",
        )
      }
      return expected
    }
  }
}
