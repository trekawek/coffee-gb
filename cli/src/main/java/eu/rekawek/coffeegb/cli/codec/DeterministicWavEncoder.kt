package eu.rekawek.coffeegb.cli.codec

/** Deterministic RIFF/WAVE encoder for interleaved signed PCM16 little-endian stereo samples. */
object DeterministicWavEncoder {
  const val DEFAULT_SAMPLE_RATE = 44_100
  const val MAX_PCM_BYTES = 64 * 1024 * 1024

  fun encodePcm16Stereo(
      pcmLittleEndian: ByteArray,
      sampleRate: Int = DEFAULT_SAMPLE_RATE,
  ): ByteArray {
    require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Unsupported WAV sample rate" }
    require(pcmLittleEndian.size <= MAX_PCM_BYTES) { "PCM input exceeds $MAX_PCM_BYTES bytes" }
    require(pcmLittleEndian.size % BLOCK_ALIGN == 0) {
      "Stereo PCM16 input must contain complete four-byte frames"
    }
    val output = ByteArray(WAVE_HEADER_BYTES + pcmLittleEndian.size)
    putAscii(output, 0, "RIFF")
    putIntLittleEndian(output, 4, output.size - 8)
    putAscii(output, 8, "WAVE")
    putAscii(output, 12, "fmt ")
    putIntLittleEndian(output, 16, PCM_FORMAT_BYTES)
    putShortLittleEndian(output, 20, PCM_FORMAT)
    putShortLittleEndian(output, 22, CHANNELS)
    putIntLittleEndian(output, 24, sampleRate)
    putIntLittleEndian(output, 28, sampleRate * BLOCK_ALIGN)
    putShortLittleEndian(output, 32, BLOCK_ALIGN)
    putShortLittleEndian(output, 34, BITS_PER_SAMPLE)
    putAscii(output, 36, "data")
    putIntLittleEndian(output, 40, pcmLittleEndian.size)
    pcmLittleEndian.copyInto(output, WAVE_HEADER_BYTES)
    return output
  }

  fun encodePcm16Stereo(
      interleavedSamples: ShortArray,
      sampleRate: Int = DEFAULT_SAMPLE_RATE,
  ): ByteArray {
    require(interleavedSamples.size % CHANNELS == 0) {
      "Stereo PCM16 input must contain complete sample pairs"
    }
    require(interleavedSamples.size <= MAX_PCM_BYTES / 2) { "PCM input is too large" }
    val bytes = ByteArray(interleavedSamples.size * 2)
    interleavedSamples.forEachIndexed { index, sample ->
      val value = sample.toInt()
      bytes[index * 2] = value.toByte()
      bytes[index * 2 + 1] = (value ushr 8).toByte()
    }
    return encodePcm16Stereo(bytes, sampleRate)
  }

  private fun putAscii(output: ByteArray, offset: Int, value: String) {
    require(value.length == 4)
    value.forEachIndexed { index, character -> output[offset + index] = character.code.toByte() }
  }

  private fun putShortLittleEndian(output: ByteArray, offset: Int, value: Int) {
    output[offset] = value.toByte()
    output[offset + 1] = (value ushr 8).toByte()
  }

  private fun putIntLittleEndian(output: ByteArray, offset: Int, value: Int) {
    output[offset] = value.toByte()
    output[offset + 1] = (value ushr 8).toByte()
    output[offset + 2] = (value ushr 16).toByte()
    output[offset + 3] = (value ushr 24).toByte()
  }

  private const val MIN_SAMPLE_RATE = 8_000
  private const val MAX_SAMPLE_RATE = 192_000
  private const val PCM_FORMAT_BYTES = 16
  private const val PCM_FORMAT = 1
  private const val CHANNELS = 2
  private const val BITS_PER_SAMPLE = 16
  private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8
  private const val WAVE_HEADER_BYTES = 44
}
