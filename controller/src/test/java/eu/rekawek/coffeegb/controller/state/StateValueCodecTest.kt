package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.MementoTypeRegistry
import eu.rekawek.coffeegb.controller.StateLimits
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.Test

class StateValueCodecTest {

  @Test
  fun everyStateKindHasAnExplicitDeterministicRoundTrip() {
    val recordType = MementoTypeRegistry.recordClasses.first()
    val nan = java.lang.Double.longBitsToDouble(0x7ff8_0000_0000_0042L)
    val values =
        listOf(
            NullState,
            Int32State(Int.MIN_VALUE),
            Int64State(Long.MAX_VALUE),
            BooleanState(true),
            Float64State(nan),
            StringState("strict \u20ac \ud83d\ude80"),
            EnumState(1, 0),
            RecordState(
                1,
                recordType.recordComponents.map { StateField(it.name, NullState) },
            ),
            BytesState(byteArrayOf(0, -1)),
            Int32ArrayState(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)),
            Int64ArrayState(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)),
            BooleanArrayState(booleanArrayOf(false, true)),
            ObjectArrayState(listOf(Int32State(7), NullState)),
            ListState(listOf(StringState("list"))),
            Int32MapState(listOf(Int32MapEntry(-2, BooleanState(false)), Int32MapEntry(9, NullState))),
        )

    values.forEach { value ->
      val first = encode(value)
      val second = encode(value)
      assertContentEquals(first, second, value.kind.name)
      val decoded = decode(first)
      if (value is Float64State) {
        assertEquals(
            java.lang.Double.doubleToRawLongBits(value.value),
            java.lang.Double.doubleToRawLongBits(assertIs<Float64State>(decoded).value),
        )
      } else {
        assertEquals(value, decoded, value.kind.name)
      }
    }
  }

  @Test
  fun exactGraphDepthIsAcceptedAndDepthPlusOneIsRejected() {
    fun nested(depth: Int): StateValue {
      var value: StateValue = NullState
      repeat(depth) { value = ListState(listOf(value)) }
      return value
    }
    decode(encode(nested(StateLimits.PORTABLE_MAX_GRAPH_DEPTH)))
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      decode(encodeWithoutEncoderDepthCheck(nested(StateLimits.PORTABLE_MAX_GRAPH_DEPTH + 1)))
    }
    assertFailsWith<StateEncodeException> {
      encode(nested(StateLimits.PORTABLE_MAX_GRAPH_DEPTH + 1))
    }
  }

  @Test
  fun primitiveWidthCollectionStringAndArithmeticBoundsAreChecked() {
    assertEquals(
        StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS,
        PortableBounds.arrayBytes(
            StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS.toLong(),
            Byte.SIZE_BYTES.toLong(),
        ),
    )
    assertEquals(
        StateLimits.PORTABLE_MAX_ARRAY_BYTES,
        PortableBounds.arrayBytes(
            (StateLimits.PORTABLE_MAX_ARRAY_BYTES / Long.SIZE_BYTES).toLong(),
            Long.SIZE_BYTES.toLong(),
        ),
    )
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      PortableBounds.arrayBytes(
          StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS.toLong(),
          Long.SIZE_BYTES.toLong(),
      )
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      PortableBounds.arrayBytes(
          StateLimits.PORTABLE_MAX_ARRAY_ELEMENTS.toLong() + 1,
          Byte.SIZE_BYTES.toLong(),
      )
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      PortableBounds.checkedMultiply(Long.MAX_VALUE, 2, Long.MAX_VALUE, "overflow")
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      PortableBounds.checkedAdd(Long.MAX_VALUE, 1, Long.MAX_VALUE, "overflow")
    }
    assertFailsWith<StateEncodeException> {
      encode(StringState("\ud800"))
    }
    assertEquals(
        StringState("a".repeat(StateLimits.PORTABLE_MAX_STRING_CHARS)),
        decode(encode(StringState("a".repeat(StateLimits.PORTABLE_MAX_STRING_CHARS)))),
    )
    assertEquals(
        StringState("\u20ac".repeat(StateLimits.PORTABLE_MAX_STRING_CHARS)),
        decode(encode(StringState("\u20ac".repeat(StateLimits.PORTABLE_MAX_STRING_CHARS)))),
    )
    assertFailsWith<IllegalArgumentException> {
      StringState("a".repeat(StateLimits.PORTABLE_MAX_STRING_CHARS + 1))
    }
    val tooManyCharacters =
        PortableWriter(StateLimits.PORTABLE_MAX_STRING_BYTES + 5).also {
          it.writeByte(5)
          it.writeU32((StateLimits.PORTABLE_MAX_STRING_CHARS + 1).toLong())
          it.writeBytes(
              ByteArray(StateLimits.PORTABLE_MAX_STRING_CHARS + 1) { 'a'.code.toByte() })
        }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      decode(tooManyCharacters.toByteArray())
    }
    decode(encode(ListState(List(StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES) { NullState })))
    assertFailsWith<StateEncodeException> {
      encode(ListState(List(StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES + 1) { NullState }))
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      decode(byteArrayOf(5, 0, 3, 0, 1))
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      decode(byteArrayOf(13, 0, 0, 0x40, 1))
    }
    assertReason(StateDecodeReason.LIMIT_EXCEEDED) {
      decode(byteArrayOf(8, 1, 0, 0, 1))
    }
  }

  @Test
  fun malformedTagsBooleansEnumsUtf8AndMapOrderingHaveTypedReasons() {
    assertReason(StateDecodeReason.MALFORMED_TAG) { decode(byteArrayOf(0x7f)) }
    assertReason(StateDecodeReason.MALFORMED_TAG) {
      decode(byteArrayOf(3, 2))
    }
    assertReason(StateDecodeReason.MALFORMED_TAG) {
      decode(byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 0))
    }
    assertReason(StateDecodeReason.MALFORMED_TAG) {
      decode(
          byteArrayOf(
              7,
              0, 0, 0, (MementoTypeRegistry.recordClasses.size + 1).toByte(),
              0, 0, 0, 0,
          ))
    }
    assertReason(StateDecodeReason.MALFORMED_ENUM) {
      decode(byteArrayOf(6, 0, 0, 0, 1, 0x7f, 0x7f, 0x7f, 0x7f))
    }
    assertReason(StateDecodeReason.MALFORMED_UTF8) {
      decode(byteArrayOf(5, 0, 0, 0, 2, 0xc3.toByte(), 0x28))
    }
    val unordered =
        byteArrayOf(
            14,
            0, 0, 0, 2,
            0, 0, 0, 1,
            0,
            0, 0, 0, 1,
            0,
        )
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) { decode(unordered) }
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) {
      decode(byteArrayOf(7, 0, 0, 0, 1, 0, 0, 0, 0))
    }
    val firstRecord =
        RecordState(
            1,
            MementoTypeRegistry.recordClasses.first().recordComponents.map {
              StateField(it.name, NullState)
            },
        )
    val wrongFieldName = encode(firstRecord)
    wrongFieldName[13] = (wrongFieldName[13].toInt() xor 1).toByte()
    assertReason(StateDecodeReason.MALFORMED_STRUCTURE) { decode(wrongFieldName) }
    assertReason(StateDecodeReason.MALFORMED_TAG) {
      decode(byteArrayOf(11, 0, 0, 0, 1, 2))
    }
    assertReason(StateDecodeReason.TRUNCATED) {
      decode(byteArrayOf(10, 0, 0, 0, 1, 0))
    }
  }

  @Test
  fun referenceBudgetRejectsBoundaryPlusOne() {
    fun tree(references: Int): StateValue {
      var remaining = references - 1
      val children = ArrayList<StateValue>()
      while (remaining > 0) {
        val count = minOf(StateLimits.PORTABLE_MAX_COLLECTION_ENTRIES, remaining - 1)
        children += ListState(List(count) { Int32State(it) })
        remaining -= count + 1
      }
      return ListState(children)
    }
    val exact = tree(StateLimits.PORTABLE_MAX_REFERENCES)
    decode(encode(exact))
    assertFailsWith<StateEncodeException> {
      encode(tree(StateLimits.PORTABLE_MAX_REFERENCES + 1))
    }
  }

  private fun encode(value: StateValue): ByteArray {
    val writer = PortableWriter(StateLimits.PORTABLE_MAX_DECODED_PAYLOAD_BYTES)
    StateValueCodec.Encoder(writer).write(value)
    return writer.toByteArray()
  }

  private fun decode(bytes: ByteArray): StateValue {
    val reader = PortableReader(bytes)
    return StateValueCodec.Decoder(reader).read().also { reader.requireExhausted() }
  }

  private fun encodeWithoutEncoderDepthCheck(value: StateValue): ByteArray {
    val writer = PortableWriter(4096)
    fun write(current: StateValue) {
      if (current === NullState) {
        writer.writeByte(0)
      } else {
        val list = current as ListState
        writer.writeByte(13)
        writer.writeU32(1)
        write(list.values.single())
      }
    }
    write(value)
    return writer.toByteArray()
  }

  private fun assertReason(reason: StateDecodeReason, block: () -> Unit) {
    assertEquals(reason, assertFailsWith<StateDecodeException>(block = block).reason)
  }
}
