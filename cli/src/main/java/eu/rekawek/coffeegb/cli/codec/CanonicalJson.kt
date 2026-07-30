package eu.rekawek.coffeegb.cli.codec

import java.nio.charset.StandardCharsets

/**
 * Small JSON value model whose object order is explicit rather than inherited from a map.
 *
 * The model intentionally supports integral numbers only. Diagnostic artifacts do not need
 * floating-point values, and excluding them avoids platform-specific spellings and non-finite
 * values.
 */
object CanonicalJson {
  sealed interface Value

  class ObjectValue internal constructor(fields: List<Pair<String, Value>>) : Value {
    internal val fields: List<Pair<String, Value>> = fields.toList()

    init {
      require(this.fields.size <= MAX_CONTAINER_ENTRIES) { "Too many JSON object fields" }
      val names = HashSet<String>()
      this.fields.forEach { (name, _) ->
        require(names.add(name)) { "Duplicate JSON object key: $name" }
      }
    }
  }

  class ArrayValue internal constructor(values: List<Value>) : Value {
    internal val values: List<Value> = values.toList()

    init {
      require(this.values.size <= MAX_CONTAINER_ENTRIES) { "Too many JSON array values" }
    }
  }

  class StringValue internal constructor(internal val value: String) : Value

  class NumberValue internal constructor(internal val value: Long) : Value

  class BooleanValue internal constructor(internal val value: Boolean) : Value

  data object NullValue : Value

  fun obj(vararg fields: Pair<String, Value>): Value = ObjectValue(fields.toList())

  fun obj(fields: Iterable<Pair<String, Value>>): Value = ObjectValue(fields.toList())

  fun array(vararg values: Value): Value = ArrayValue(values.toList())

  fun array(values: Iterable<Value>): Value = ArrayValue(values.toList())

  fun string(value: String): Value = StringValue(value)

  fun number(value: Long): Value = NumberValue(value)

  fun number(value: Int): Value = NumberValue(value.toLong())

  fun bool(value: Boolean): Value = BooleanValue(value)

  fun nil(): Value = NullValue

  internal const val MAX_CONTAINER_ENTRIES = 4096
}

/** Writes ASCII-only, whitespace-free JSON followed by exactly one LF byte. */
object CanonicalJsonWriter {
  const val MAX_ENCODED_BYTES = 1024 * 1024

  fun encode(value: CanonicalJson.Value): ByteArray =
      encodeToString(value).toByteArray(StandardCharsets.US_ASCII)

  fun encodeToString(value: CanonicalJson.Value): String {
    val output = StringBuilder()
    writeValue(output, value, 0)
    append(output, '\n')
    return output.toString()
  }

  private fun writeValue(
      output: StringBuilder,
      value: CanonicalJson.Value,
      depth: Int,
  ) {
    require(depth <= MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH levels" }
    when (value) {
      is CanonicalJson.ObjectValue -> {
        append(output, '{')
        value.fields.forEachIndexed { index, (name, child) ->
          if (index != 0) append(output, ',')
          writeString(output, name)
          append(output, ':')
          writeValue(output, child, depth + 1)
        }
        append(output, '}')
      }
      is CanonicalJson.ArrayValue -> {
        append(output, '[')
        value.values.forEachIndexed { index, child ->
          if (index != 0) append(output, ',')
          writeValue(output, child, depth + 1)
        }
        append(output, ']')
      }
      is CanonicalJson.StringValue -> writeString(output, value.value)
      is CanonicalJson.NumberValue -> append(output, value.value.toString())
      is CanonicalJson.BooleanValue -> append(output, value.value.toString())
      CanonicalJson.NullValue -> append(output, "null")
    }
  }

  private fun writeString(output: StringBuilder, value: String) {
    require(value.length <= MAX_STRING_CODE_UNITS) { "JSON string is too long" }
    append(output, '"')
    value.forEach { character ->
      when (character) {
        '"' -> append(output, "\\\"")
        '\\' -> append(output, "\\\\")
        '\b' -> append(output, "\\b")
        '\u000c' -> append(output, "\\f")
        '\n' -> append(output, "\\n")
        '\r' -> append(output, "\\r")
        '\t' -> append(output, "\\t")
        else -> {
          if (character.code in 0x20..0x7e) {
            append(output, character)
          } else {
            append(output, "\\u")
            append(output, character.code.toString(16).padStart(4, '0'))
          }
        }
      }
    }
    append(output, '"')
  }

  private fun append(output: StringBuilder, value: Char) {
    require(output.length < MAX_ENCODED_BYTES) { "JSON output exceeds $MAX_ENCODED_BYTES bytes" }
    output.append(value)
  }

  private fun append(output: StringBuilder, value: String) {
    require(output.length <= MAX_ENCODED_BYTES - value.length) {
      "JSON output exceeds $MAX_ENCODED_BYTES bytes"
    }
    output.append(value)
  }

  private const val MAX_DEPTH = 64
  private const val MAX_STRING_CODE_UNITS = 256 * 1024
}
