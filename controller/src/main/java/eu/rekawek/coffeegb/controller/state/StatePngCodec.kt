package eu.rekawek.coffeegb.controller.state

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Bounded deterministic RGB PNG codec shared by thumbnails and screenshots.
 *
 * It deliberately supports the RGB, 8-bit PNGs Coffee GB writes (including prior desktop
 * releases) rather than relying on a desktop image toolkit in the controller runtime.
 */
object StatePngCodec {
  const val MAX_PNG_BYTES = 2 * 1024 * 1024
  const val MAX_METADATA_ENTRIES = 8
  const val MAX_METADATA_UTF8_BYTES = 1024

  private val KEY = Regex("[A-Za-z][A-Za-z0-9 ]{0,31}")
  private val VALUE = Regex("[\\x20-\\x7e]{1,128}")
  private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
  private val IHDR = "IHDR".toByteArray(StandardCharsets.US_ASCII)
  private val IDAT = "IDAT".toByteArray(StandardCharsets.US_ASCII)
  private val IEND = "IEND".toByteArray(StandardCharsets.US_ASCII)
  private val TEXT = "tEXt".toByteArray(StandardCharsets.US_ASCII)

  @JvmOverloads
  fun encode(
      image: StateImage,
      metadata: Map<String, String> = emptyMap(),
  ): ByteArray {
    validateMetadata(metadata)
    val raw = ByteArray(Math.multiplyExact(image.height, Math.addExact(Math.multiplyExact(image.width, 3), 1)))
    val pixels = image.copyRgb()
    var offset = 0
    var pixel = 0
    repeat(image.height) {
      raw[offset++] = 0 // PNG filter: None. Stable across hosts and inexpensive at Coffee GB sizes.
      repeat(image.width) {
        val rgb = pixels[pixel++]
        raw[offset++] = (rgb shr 16).toByte()
        raw[offset++] = (rgb shr 8).toByte()
        raw[offset++] = rgb.toByte()
      }
    }

    val output = ByteArrayOutputStream()
    output.write(SIGNATURE)
    writeChunk(
        output,
        IHDR,
        ByteArray(13).also { header ->
          writeInt(header, 0, image.width)
          writeInt(header, 4, image.height)
          header[8] = 8 // bit depth
          header[9] = 2 // truecolour RGB
        },
    )
    metadata.toSortedMap().forEach { (key, value) ->
      writeChunk(
          output,
          TEXT,
          (key + '\u0000' + value).toByteArray(StandardCharsets.ISO_8859_1),
      )
    }
    writeChunk(output, IDAT, deflate(raw))
    writeChunk(output, IEND, ByteArray(0))
    return output.toByteArray().also {
      if (it.size > MAX_PNG_BYTES) {
        throw IOException("PNG exceeds the $MAX_PNG_BYTES-byte limit")
      }
    }
  }

  /**
   * Decodes bounded, non-interlaced 8-bit RGB PNGs after validating the full envelope and every
   * chunk checksum. Dimensions are checked before any raster allocation.
   */
  fun decode(bytes: ByteArray): StateImage {
    if (bytes.isEmpty() || bytes.size > MAX_PNG_BYTES) {
      throw IOException("PNG byte count must be between 1 and $MAX_PNG_BYTES")
    }
    if (bytes.size < SIGNATURE.size || !bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
      throw IOException("PNG signature is invalid")
    }

    var offset = SIGNATURE.size
    var width = 0
    var height = 0
    var sawHeader = false
    var sawData = false
    val compressed = ByteArrayOutputStream()
    while (offset < bytes.size) {
      if (bytes.size - offset < 12) throw IOException("PNG chunk is truncated")
      val length = readLength(bytes, offset)
      val typeOffset = offset + 4
      val dataOffset = typeOffset + 4
      if (length > bytes.size - dataOffset - 4) throw IOException("PNG chunk exceeds input")
      val checksumOffset = dataOffset + length
      val type = bytes.copyOfRange(typeOffset, dataOffset)
      verifyCrc(bytes, typeOffset, type, dataOffset, length, checksumOffset)

      when {
        type.contentEquals(IHDR) -> {
          if (sawHeader || sawData || length != 13) throw IOException("PNG header is invalid")
          width = readLength(bytes, dataOffset)
          height = readLength(bytes, dataOffset + 4)
          if (width !in 1..StateImage.MAX_WIDTH || height !in 1..StateImage.MAX_HEIGHT) {
            throw IOException(
                "PNG dimensions $width x $height exceed " +
                    "${StateImage.MAX_WIDTH} x ${StateImage.MAX_HEIGHT}")
          }
          if (
              bytes[dataOffset + 8].toInt() != 8 ||
                  bytes[dataOffset + 9].toInt() != 2 ||
                  bytes[dataOffset + 10].toInt() != 0 ||
                  bytes[dataOffset + 11].toInt() != 0 ||
                  bytes[dataOffset + 12].toInt() != 0
          ) {
            throw IOException("PNG must be a non-interlaced 8-bit RGB image")
          }
          sawHeader = true
        }
        type.contentEquals(IDAT) -> {
          if (!sawHeader) throw IOException("PNG data appears before its header")
          sawData = true
          compressed.write(bytes, dataOffset, length)
        }
        type.contentEquals(IEND) -> {
          if (!sawHeader || !sawData || length != 0 || checksumOffset + 4 != bytes.size) {
            throw IOException("PNG end chunk is invalid")
          }
          return decodeRgb(width, height, compressed.toByteArray())
        }
        (type[0].toInt() and 0x20) == 0 -> throw IOException("PNG has an unsupported critical chunk")
      }
      offset = checksumOffset + 4
    }
    throw IOException("PNG has no end chunk")
  }

  private fun decodeRgb(width: Int, height: Int, compressed: ByteArray): StateImage {
    val stride = Math.multiplyExact(width, 3)
    val expected = Math.multiplyExact(height, Math.addExact(stride, 1))
    val filtered = inflate(compressed, expected)
    val pixels = IntArray(Math.multiplyExact(width, height))
    val previous = ByteArray(stride)
    val current = ByteArray(stride)
    var offset = 0
    var pixel = 0
    repeat(height) {
      val filter = filtered[offset++].toInt() and 0xff
      if (filter !in 0..4) throw IOException("PNG has an unsupported scanline filter")
      repeat(stride) { index ->
        val encoded = filtered[offset++].toInt() and 0xff
        val left = if (index >= 3) current[index - 3].toInt() and 0xff else 0
        val up = previous[index].toInt() and 0xff
        val upperLeft = if (index >= 3) previous[index - 3].toInt() and 0xff else 0
        current[index] =
            when (filter) {
              0 -> encoded
              1 -> encoded + left
              2 -> encoded + up
              3 -> encoded + ((left + up) ushr 1)
              else -> encoded + paeth(left, up, upperLeft)
            }.toByte()
      }
      repeat(width) { x ->
        val component = x * 3
        pixels[pixel++] =
            ((current[component].toInt() and 0xff) shl 16) or
                ((current[component + 1].toInt() and 0xff) shl 8) or
                (current[component + 2].toInt() and 0xff)
      }
      current.copyInto(previous)
    }
    return StateImage(width, height, pixels)
  }

  private fun deflate(raw: ByteArray): ByteArray {
    val compressor = Deflater(Deflater.DEFAULT_COMPRESSION)
    return try {
      compressor.setInput(raw)
      compressor.finish()
      ByteArrayOutputStream().also { output ->
        val buffer = ByteArray(4096)
        while (!compressor.finished()) {
          output.write(buffer, 0, compressor.deflate(buffer))
        }
      }.toByteArray()
    } finally {
      compressor.end()
    }
  }

  private fun inflate(compressed: ByteArray, expected: Int): ByteArray {
    val inflater = Inflater()
    return try {
      inflater.setInput(compressed)
      val output = ByteArray(expected)
      var written = 0
      while (!inflater.finished() && written < output.size) {
        val count = inflater.inflate(output, written, output.size - written)
        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          throw IOException("PNG image data is malformed")
        }
        written += count
      }
      if (!inflater.finished() || inflater.remaining != 0 || written != output.size) {
        throw IOException("PNG image data has an invalid size")
      }
      output
    } catch (failure: DataFormatException) {
      throw IOException("PNG image data is malformed", failure)
    } finally {
      inflater.end()
    }
  }

  private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
    val estimate = left + up - upperLeft
    val leftDistance = kotlin.math.abs(estimate - left)
    val upDistance = kotlin.math.abs(estimate - up)
    val upperLeftDistance = kotlin.math.abs(estimate - upperLeft)
    return when {
      leftDistance <= upDistance && leftDistance <= upperLeftDistance -> left
      upDistance <= upperLeftDistance -> up
      else -> upperLeft
    }
  }

  private fun writeChunk(output: ByteArrayOutputStream, type: ByteArray, data: ByteArray) {
    writeInt(output, data.size)
    output.write(type)
    output.write(data)
    val crc = CRC32()
    crc.update(type)
    crc.update(data)
    writeInt(output, crc.value.toInt())
  }

  private fun verifyCrc(
      bytes: ByteArray,
      typeOffset: Int,
      type: ByteArray,
      dataOffset: Int,
      length: Int,
      checksumOffset: Int,
  ) {
    val crc = CRC32()
    crc.update(bytes, typeOffset, type.size)
    crc.update(bytes, dataOffset, length)
    if (crc.value.toInt() != readRawInt(bytes, checksumOffset)) {
      throw IOException("PNG chunk checksum is invalid")
    }
  }

  private fun readLength(bytes: ByteArray, offset: Int): Int {
    val value = readRawInt(bytes, offset).toLong() and 0xffffffffL
    if (value > Int.MAX_VALUE) throw IOException("PNG chunk is too large")
    return value.toInt()
  }

  private fun readRawInt(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)

  private fun writeInt(output: ByteArrayOutputStream, value: Int) {
    output.write(value ushr 24)
    output.write(value ushr 16)
    output.write(value ushr 8)
    output.write(value)
  }

  private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
  }

  private fun validateMetadata(metadata: Map<String, String>) {
    require(metadata.size <= MAX_METADATA_ENTRIES) {
      "PNG metadata contains more than $MAX_METADATA_ENTRIES entries"
    }
    var bytes = 0
    metadata.forEach { (key, value) ->
      require(KEY.matches(key)) { "Invalid PNG metadata key" }
      require(VALUE.matches(value)) { "Invalid PNG metadata value for $key" }
      bytes = Math.addExact(
          bytes,
          key.toByteArray(StandardCharsets.UTF_8).size +
              value.toByteArray(StandardCharsets.UTF_8).size,
      )
    }
    require(bytes <= MAX_METADATA_UTF8_BYTES) {
      "PNG metadata exceeds $MAX_METADATA_UTF8_BYTES UTF-8 bytes"
    }
  }
}
