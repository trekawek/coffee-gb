package eu.rekawek.coffeegb.cli.codec

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CanonicalJsonWriterTest {
  @Test
  fun writesExplicitOrderEscapesAndOneLf() {
    val value = CanonicalJson.obj(
        "z" to CanonicalJson.number(-7),
        "a" to CanonicalJson.array(
            CanonicalJson.bool(true),
            CanonicalJson.nil(),
            CanonicalJson.string("quote\"\n"),
        ),
        "unicode" to CanonicalJson.string("é😀"),
    )

    assertEquals(
        "{\"z\":-7,\"a\":[true,null,\"quote\\\"\\n\"]," +
            "\"unicode\":\"\\u00e9\\ud83d\\ude00\"}\n",
        CanonicalJsonWriter.encodeToString(value),
    )
  }

  @Test
  fun outputIsDeterministic() {
    val value = CanonicalJson.obj(
        "one" to CanonicalJson.number(Long.MAX_VALUE),
        "two" to CanonicalJson.string("value"),
    )

    assertContentEquals(CanonicalJsonWriter.encode(value), CanonicalJsonWriter.encode(value))
  }

  @Test
  fun rejectsDuplicateObjectKeys() {
    assertFailsWith<IllegalArgumentException> {
      CanonicalJson.obj(
          "same" to CanonicalJson.number(1),
          "same" to CanonicalJson.number(2),
      )
    }
  }

  @Test
  fun enforcesOutputAndDepthBounds() {
    assertFailsWith<IllegalArgumentException> {
      CanonicalJsonWriter.encode(
          CanonicalJson.string("x".repeat(CanonicalJsonWriter.MAX_ENCODED_BYTES)))
    }

    var nested: CanonicalJson.Value = CanonicalJson.nil()
    repeat(66) { nested = CanonicalJson.array(nested) }
    assertFailsWith<IllegalArgumentException> { CanonicalJsonWriter.encode(nested) }
  }
}
