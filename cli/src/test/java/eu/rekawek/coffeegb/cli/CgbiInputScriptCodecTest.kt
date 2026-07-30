package eu.rekawek.coffeegb.cli

import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CgbiInputScriptCodecTest {
  @Test
  fun roundTripsCanonicalAbsoluteMasks() {
    val script =
        CgbiInputScript(
            listOf(
                CgbiInputRecord(0, 1, 0x10),
                CgbiInputRecord(0, 2, 0x01),
                CgbiInputRecord(24, 1, 0x30),
                CgbiInputRecord(25, 1, 0x00),
            ))
    val encoded = CgbiInputScriptCodec.encode(script)
    assertEquals(
        "CGBI\t1\t0\n0\t1\t0x10\n0\t2\t0x01\n24\t1\t0x30\n25\t1\t0x00\n",
        encoded.toString(StandardCharsets.UTF_8),
    )
    assertEquals(script, CgbiInputScriptCodec.decode(encoded))
  }

  @Test
  fun acceptsCrlfAndAnEmptyCanonicalTimeline() {
    val crlf = "CGBI\t1\t0\r\n1\t1\t0x01\r\n".toByteArray()
    assertEquals(listOf(CgbiInputRecord(1, 1, 1)), CgbiInputScriptCodec.decode(crlf).records)
    assertTrue(CgbiInputScriptCodec.decode("CGBI\t1\t0".toByteArray()).records.isEmpty())
  }

  @Test
  fun inputScriptOwnsItsRecordList() {
    val source = mutableListOf(CgbiInputRecord(0, 1, 1))
    val script = CgbiInputScript(source)
    source.clear()
    assertEquals(1, script.records.size)
  }

  @Test
  fun rejectsMalformedEncodingHeaderRowsAndLineEndingsWithTypedReasons() {
    assertReason(CgbiInputError.INVALID_UTF8, byteArrayOf(0xc3.toByte(), 0x28))
    assertReason(CgbiInputError.INVALID_HEADER, "CGBX\t1\t0\n".toByteArray())
    assertReason(CgbiInputError.INVALID_HEADER, "\uFEFFCGBI\t1\t0\n".toByteArray())
    assertReason(CgbiInputError.INVALID_LINE_ENDING, "CGBI\t1\t0\r1\t1\t0x01".toByteArray())
    assertReason(CgbiInputError.INVALID_RECORD, "CGBI\t1\t0\n\n".toByteArray())
    assertReason(CgbiInputError.INVALID_RECORD, "CGBI\t1\t0\n+1\t1\t0x01\n".toByteArray())
    assertReason(CgbiInputError.INVALID_RECORD, "CGBI\t1\t0\n1\t0\t0x01\n".toByteArray())
    assertReason(CgbiInputError.INVALID_RECORD, "CGBI\t1\t0\n1\t1\t0x1\n".toByteArray())
  }

  @Test
  fun rejectsOutOfOrderDuplicateAndNoOpTransitions() {
    assertReason(
        CgbiInputError.NON_CANONICAL_TIMELINE,
        "CGBI\t1\t0\n2\t1\t0x01\n1\t1\t0x02\n".toByteArray(),
    )
    assertReason(
        CgbiInputError.NON_CANONICAL_TIMELINE,
        "CGBI\t1\t0\n1\t2\t0x01\n1\t1\t0x02\n".toByteArray(),
    )
    assertReason(
        CgbiInputError.NON_CANONICAL_TIMELINE,
        "CGBI\t1\t0\n1\t1\t0x01\n2\t1\t0x01\n".toByteArray(),
    )
    assertReason(
        CgbiInputError.NON_CANONICAL_TIMELINE,
        "CGBI\t1\t0\n1\t1\t0x00\n".toByteArray(),
    )
  }

  @Test
  fun enforcesTheByteBoundaryBeforeParsing() {
    val oversized = ByteArray(CgbiInputScriptCodec.MAX_FILE_BYTES + 1)
    val failure = assertFailsWith<CgbiInputException> { CgbiInputScriptCodec.decode(oversized) }
    assertEquals(CgbiInputError.TOO_LARGE, failure.error)
  }

  private fun assertReason(reason: CgbiInputError, bytes: ByteArray) {
    val failure = assertFailsWith<CgbiInputException> { CgbiInputScriptCodec.decode(bytes) }
    assertEquals(reason, failure.error)
    assertTrue(failure.message.orEmpty().none { it == '\r' || it == '\n' })
  }
}
