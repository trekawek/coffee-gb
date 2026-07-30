package eu.rekawek.coffeegb.cli.codec

import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class DeterministicWavEncoderTest {
  @Test
  fun matchesPcm16StereoGoldenBytes() {
    val encoded = DeterministicWavEncoder.encodePcm16Stereo(
        shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, -1),
    )

    assertEquals(
        "524946462c00000057415645666d7420100000000100020044ac000010b10200" +
            "0400100064617461080000000000ff7f0080ffff",
        encoded.toHex(),
    )
    assertEquals(
        "76bcb9da5accef228f993a8904e71c025632f45f8268b5fb428ef4ab67ec7c87",
        MessageDigest.getInstance("SHA-256").digest(encoded).toHex(),
    )
    assertContentEquals(
        encoded,
        DeterministicWavEncoder.encodePcm16Stereo(
            byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte(), 0xff.toByte(), 0xff.toByte()),
        ),
    )
  }

  @Test
  fun writesRequestedSampleRateWithoutMetadataChunks() {
    val encoded = DeterministicWavEncoder.encodePcm16Stereo(ByteArray(4), 48_000)

    assertEquals("RIFF", String(encoded, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(encoded, 8, 4, Charsets.US_ASCII))
    assertEquals("fmt ", String(encoded, 12, 4, Charsets.US_ASCII))
    assertEquals("data", String(encoded, 36, 4, Charsets.US_ASCII))
    assertEquals(48_000, readIntLittleEndian(encoded, 24))
    assertEquals(48_000 * 4, readIntLittleEndian(encoded, 28))
    assertEquals(48, encoded.size)
  }

  @Test
  fun rejectsIncompleteFramesAndUnsupportedRates() {
    assertFailsWith<IllegalArgumentException> {
      DeterministicWavEncoder.encodePcm16Stereo(ByteArray(2))
    }
    assertFailsWith<IllegalArgumentException> {
      DeterministicWavEncoder.encodePcm16Stereo(shortArrayOf(1))
    }
    assertFailsWith<IllegalArgumentException> {
      DeterministicWavEncoder.encodePcm16Stereo(ByteArray(4), 1)
    }
  }

  private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
      (bytes[offset].toInt() and 0xff) or
          ((bytes[offset + 1].toInt() and 0xff) shl 8) or
          ((bytes[offset + 2].toInt() and 0xff) shl 16) or
          ((bytes[offset + 3].toInt() and 0xff) shl 24)

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
