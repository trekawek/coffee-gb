package eu.rekawek.coffeegb.cli.codec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Deterministic, metadata-free RGB8 PNG encoder with no AWT/ImageIO dependency. */
object DeterministicPngEncoder {
  const val MAX_WIDTH = 256
  const val MAX_HEIGHT = 224
  const val MAX_ENCODED_BYTES = 256 * 1024

  fun encodeRgb8(width: Int, height: Int, rgb: IntArray): ByteArray {
    require(width in 1..MAX_WIDTH) { "PNG width must be between 1 and $MAX_WIDTH" }
    require(height in 1..MAX_HEIGHT) { "PNG height must be between 1 and $MAX_HEIGHT" }
    require(rgb.size == width * height) { "RGB pixel count does not match image dimensions" }

    val scanlines = ByteArray(height * (1 + width * 3))
    var outputIndex = 0
    var pixelIndex = 0
    repeat(height) {
      scanlines[outputIndex++] = 0 // PNG filter method: None.
      repeat(width) {
        val pixel = rgb[pixelIndex++]
        scanlines[outputIndex++] = (pixel ushr 16).toByte()
        scanlines[outputIndex++] = (pixel ushr 8).toByte()
        scanlines[outputIndex++] = pixel.toByte()
      }
    }

    val output = ByteArrayOutputStream()
    output.write(PNG_SIGNATURE)
    val header = ByteArrayOutputStream(IHDR_SIZE)
    writeIntBigEndian(header, width)
    writeIntBigEndian(header, height)
    header.write(8) // bit depth
    header.write(2) // truecolour RGB
    header.write(0) // compression
    header.write(0) // filter
    header.write(0) // no interlace
    writeChunk(output, "IHDR", header.toByteArray())
    writeChunk(output, "IDAT", storedZlib(scanlines))
    writeChunk(output, "IEND", ByteArray(0))

    val encoded = output.toByteArray()
    check(encoded.size <= MAX_ENCODED_BYTES) { "PNG output exceeds $MAX_ENCODED_BYTES bytes" }
    return encoded
  }

  private fun storedZlib(input: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(input.size + input.size / DEFLATE_BLOCK_SIZE * 5 + 16)
    output.write(0x78)
    output.write(0x01) // zlib: 32 KiB window, fastest/no compression.
    var offset = 0
    while (offset < input.size) {
      val length = minOf(DEFLATE_BLOCK_SIZE, input.size - offset)
      val final = offset + length == input.size
      output.write(if (final) 1 else 0) // BFINAL followed by stored BTYPE and zero padding.
      writeShortLittleEndian(output, length)
      writeShortLittleEndian(output, length xor 0xffff)
      output.write(input, offset, length)
      offset += length
    }
    writeIntBigEndian(output, adler32(input))
    return output.toByteArray()
  }

  private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
    require(typeBytes.size == 4)
    writeIntBigEndian(output, data.size)
    output.write(typeBytes)
    output.write(data)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    writeIntBigEndian(output, crc.value.toInt())
  }

  private fun adler32(input: ByteArray): Int {
    var first = 1
    var second = 0
    input.forEach { byte ->
      first = (first + (byte.toInt() and 0xff)) % ADLER_MODULUS
      second = (second + first) % ADLER_MODULUS
    }
    return (second shl 16) or first
  }

  private fun writeIntBigEndian(output: ByteArrayOutputStream, value: Int) {
    output.write(value ushr 24)
    output.write(value ushr 16)
    output.write(value ushr 8)
    output.write(value)
  }

  private fun writeShortLittleEndian(output: ByteArrayOutputStream, value: Int) {
    output.write(value)
    output.write(value ushr 8)
  }

  private const val IHDR_SIZE = 13
  private const val DEFLATE_BLOCK_SIZE = 65_535
  private const val ADLER_MODULUS = 65_521
  private val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
