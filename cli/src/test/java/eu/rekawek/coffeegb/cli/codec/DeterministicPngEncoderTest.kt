package eu.rekawek.coffeegb.cli.codec

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class DeterministicPngEncoderTest {
  @Test
  fun matchesRgb8GoldenBytes() {
    val encoded = DeterministicPngEncoder.encodeRgb8(
        2,
        2,
        intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff),
    )

    assertEquals(
        "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
            "00000019494441547801010e00f1ff00ff000000ff00000000ffffffff1fee05fb" +
            "deddec2b0000000049454e44ae426082",
        encoded.toHex(),
    )
    assertEquals(
        "d268586051b64827ed456d804e07a48de48145ac2c7f9b7b5b79c12964551b40",
        MessageDigest.getInstance("SHA-256").digest(encoded).toHex(),
    )
    assertContentEquals(encoded, DeterministicPngEncoder.encodeRgb8(
        2,
        2,
        intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff),
    ))
  }

  @Test
  fun emitsOnlyValidIhdrIdatIendChunksAndNoneFilters() {
    val encoded = DeterministicPngEncoder.encodeRgb8(
        2,
        2,
        intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff),
    )
    val chunks = chunks(encoded)

    assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.first })
    assertContentEquals(
        byteArrayOf(
            0, 0xff.toByte(), 0, 0, 0, 0xff.toByte(), 0,
            0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        ),
        inflate(chunks.single { it.first == "IDAT" }.second),
    )
  }

  @Test
  fun enforcesImageBoundsAndPixelCount() {
    assertFailsWith<IllegalArgumentException> {
      DeterministicPngEncoder.encodeRgb8(0, 1, IntArray(0))
    }
    assertFailsWith<IllegalArgumentException> {
      DeterministicPngEncoder.encodeRgb8(DeterministicPngEncoder.MAX_WIDTH + 1, 1, IntArray(0))
    }
    assertFailsWith<IllegalArgumentException> {
      DeterministicPngEncoder.encodeRgb8(2, 2, IntArray(3))
    }
  }

  private fun chunks(png: ByteArray): List<Pair<String, ByteArray>> {
    val result = ArrayList<Pair<String, ByteArray>>()
    var offset = 8
    while (offset < png.size) {
      val length = readIntBigEndian(png, offset)
      val type = String(png, offset + 4, 4, Charsets.US_ASCII)
      val data = png.copyOfRange(offset + 8, offset + 8 + length)
      val expectedCrc = readIntBigEndian(png, offset + 8 + length).toLong() and 0xffffffffL
      val actualCrc = CRC32().apply {
        update(type.toByteArray(Charsets.US_ASCII))
        update(data)
      }.value
      assertEquals(expectedCrc, actualCrc)
      result += type to data
      offset += 12 + length
    }
    assertEquals(png.size, offset)
    return result
  }

  private fun inflate(compressed: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(compressed)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(128)
    while (!inflater.finished()) {
      val count = inflater.inflate(buffer)
      assertTrue(count > 0)
      output.write(buffer, 0, count)
    }
    inflater.end()
    return output.toByteArray()
  }

  private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
