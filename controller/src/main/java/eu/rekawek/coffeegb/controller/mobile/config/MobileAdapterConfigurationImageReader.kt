package eu.rekawek.coffeegb.controller.mobile.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Result of reading an owner-selected Mobile Adapter configuration image.
 *
 * Imported bytes may contain account or dial-up data. They are detached at both API boundaries
 * and are deliberately omitted from diagnostics.
 */
class MobileAdapterConfigurationImageReadResult
internal constructor(
    image: ByteArray?,
    val error: MobileAdapterConfigurationError?,
) {
  private val ownedImage = image?.clone()

  init {
    require((ownedImage != null) xor (error != null)) {
      "Image import success and typed failure must be exclusive"
    }
    require(ownedImage == null || ownedImage.size == IMAGE_SIZE) {
      "A successful image import must contain exactly 256 bytes"
    }
  }

  /** Returns a detached image, or null when [error] describes the failure. */
  fun image(): ByteArray? = ownedImage?.clone()

  override fun toString(): String =
      "MobileAdapterConfigurationImageReadResult(" +
          "image=${if (ownedImage == null) "null" else "[redacted]"}, error=$error)"

  internal companion object {
    const val IMAGE_SIZE = MobileAdapterConfiguration.CONFIGURATION_SIZE

    fun success(image: ByteArray): MobileAdapterConfigurationImageReadResult =
        MobileAdapterConfigurationImageReadResult(image, null)

    fun failure(error: MobileAdapterConfigurationError): MobileAdapterConfigurationImageReadResult =
        MobileAdapterConfigurationImageReadResult(null, error)
  }
}

/**
 * Strictly bounded reader for raw and REON/libmobile Mobile Adapter configuration images.
 *
 * Raw 256-byte images are accepted opaquely. A 512-byte envelope is accepted only after its MA
 * and LM headers, checksums, version, and address-type fields have been validated; its LM suffix is
 * then discarded.
 */
class MobileAdapterConfigurationImageReader private constructor(
    private val afterInitialAttributes: (() -> Unit)?,
    private val afterRead: (() -> Unit)?,
) {

  constructor() : this(null, null)

  fun read(source: Path): MobileAdapterConfigurationImageReadResult {
    try {
      val initialAttributes =
          Files.readAttributes(
              source,
              BasicFileAttributes::class.java,
              LinkOption.NOFOLLOW_LINKS,
          )
      if (!initialAttributes.isRegularFile) {
        return MobileAdapterConfigurationImageReadResult.failure(
            MobileAdapterConfigurationError.IMPORT_NON_REGULAR_SOURCE)
      }
      if (initialAttributes.size() !in ACCEPTED_IMAGE_SIZES) {
        return MobileAdapterConfigurationImageReadResult.failure(
            MobileAdapterConfigurationError.IMPORT_MALFORMED_IMAGE)
      }
      // A null file key prevents us from detecting a replaced directory entry. Reject providers
      // that cannot supply identity rather than weakening the source-separation contract.
      if (initialAttributes.fileKey() == null) {
        return MobileAdapterConfigurationImageReadResult.failure(
            MobileAdapterConfigurationError.IMPORT_READ_FAILED)
      }

      afterInitialAttributes?.invoke()

      return FileChannel.open(
              source,
              StandardOpenOption.READ,
              LinkOption.NOFOLLOW_LINKS,
          )
          .use { channel ->
            if (!sourceMatchesOpenedChannel(source, initialAttributes, channel)) {
              return@use MobileAdapterConfigurationImageReadResult.failure(
                  MobileAdapterConfigurationError.IMPORT_READ_FAILED)
            }
            val image = readBoundedImage(channel)
            afterRead?.invoke()
            if (!sourceMatchesOpenedChannel(source, initialAttributes, channel)) {
              image?.fill(0)
              MobileAdapterConfigurationImageReadResult.failure(
                  MobileAdapterConfigurationError.IMPORT_READ_FAILED)
            } else if (image == null) {
              MobileAdapterConfigurationImageReadResult.failure(
                  MobileAdapterConfigurationError.IMPORT_MALFORMED_IMAGE)
            } else {
              try {
                MobileAdapterConfigurationImageReadResult.success(image)
              } finally {
                image.fill(0)
              }
            }
          }
    } catch (_: IOException) {
      return MobileAdapterConfigurationImageReadResult.failure(
          MobileAdapterConfigurationError.IMPORT_READ_FAILED)
    } catch (_: SecurityException) {
      return MobileAdapterConfigurationImageReadResult.failure(
          MobileAdapterConfigurationError.IMPORT_READ_FAILED)
    } catch (_: UnsupportedOperationException) {
      return MobileAdapterConfigurationImageReadResult.failure(
          MobileAdapterConfigurationError.IMPORT_READ_FAILED)
    } catch (_: RuntimeException) {
      // Provider-specific failures must not escape with a source path or private file details.
      return MobileAdapterConfigurationImageReadResult.failure(
          MobileAdapterConfigurationError.IMPORT_READ_FAILED)
    }
  }

  private fun sourceMatchesOpenedChannel(
      source: Path,
      initial: BasicFileAttributes,
      channel: FileChannel,
  ): Boolean {
    if (channel.size() != initial.size()) return false
    val current =
        Files.readAttributes(
            source,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    return current.isRegularFile &&
        current.size() == initial.size() &&
        current.fileKey() == initial.fileKey() &&
        current.lastModifiedTime() == initial.lastModifiedTime() &&
        current.creationTime() == initial.creationTime()
  }

  private fun readBoundedImage(channel: FileChannel): ByteArray? {
    val bounded = ByteArray(MAXIMUM_PROBE_BYTES)
    try {
      val output = ByteBuffer.wrap(bounded)
      var zeroReads = 0
      while (output.hasRemaining()) {
        val count = channel.read(output)
        if (count < 0) break
        if (count == 0) {
          if (++zeroReads > MAX_ZERO_READS) {
            throw IOException("Mobile Adapter image channel made no read progress")
          }
        } else {
          zeroReads = 0
        }
      }

      return when (output.position()) {
        RAW_IMAGE_BYTES -> bounded.copyOf(RAW_IMAGE_BYTES)
        ENVELOPE_BYTES ->
            if (validEnvelope(bounded)) {
              bounded.copyOf(RAW_IMAGE_BYTES)
            } else {
              null
            }
        else -> null
      }
    } finally {
      // Do not leave a rejected image or the discarded LM suffix in the probe buffer.
      bounded.fill(0)
    }
  }

  private fun validEnvelope(envelope: ByteArray): Boolean {
    if (envelope[MA_MAGIC_OFFSET] != ASCII_M || envelope[MA_MAGIC_OFFSET + 1] != ASCII_A) {
      return false
    }
    val expectedMaChecksum = checksum(envelope, MA_CHECKSUM_START..MA_CHECKSUM_END)
    val storedMaChecksum =
        (unsigned(envelope[MA_CHECKSUM_OFFSET]) shl 8) or
            unsigned(envelope[MA_CHECKSUM_OFFSET + 1])
    if (storedMaChecksum != expectedMaChecksum) return false

    if (envelope[LM_MAGIC_OFFSET] != ASCII_L || envelope[LM_MAGIC_OFFSET + 1] != ASCII_M) {
      return false
    }
    if (unsigned(envelope[LM_VERSION_OFFSET]) != SUPPORTED_LM_VERSION) return false
    val expectedLmChecksum = checksum(envelope, LM_CHECKSUM_START..LM_CHECKSUM_END)
    val storedLmChecksum =
        unsigned(envelope[LM_CHECKSUM_OFFSET]) or
            (unsigned(envelope[LM_CHECKSUM_OFFSET + 1]) shl 8)
    if (storedLmChecksum != expectedLmChecksum) return false

    return LM_ADDRESS_TYPE_OFFSETS.all { offset ->
      unsigned(envelope[offset]) in MIN_ADDRESS_TYPE..MAX_ADDRESS_TYPE
    }
  }

  private fun checksum(bytes: ByteArray, range: IntRange): Int {
    var checksum = 0
    for (offset in range) {
      checksum = (checksum + unsigned(bytes[offset])) and 0xffff
    }
    return checksum
  }

  private fun unsigned(value: Byte): Int = value.toInt() and 0xff

  companion object {
    internal fun withOpenHookForTest(
        afterInitialAttributes: () -> Unit
    ): MobileAdapterConfigurationImageReader =
        MobileAdapterConfigurationImageReader(afterInitialAttributes, null)

    internal fun withPostReadHookForTest(
        afterRead: () -> Unit
    ): MobileAdapterConfigurationImageReader =
        MobileAdapterConfigurationImageReader(null, afterRead)

    private const val RAW_IMAGE_BYTES = MobileAdapterConfiguration.CONFIGURATION_SIZE
    private const val ENVELOPE_BYTES = 512
    private const val MAXIMUM_PROBE_BYTES = ENVELOPE_BYTES + 1
    private const val MAX_ZERO_READS = 1_024
    private val ACCEPTED_IMAGE_SIZES = setOf(RAW_IMAGE_BYTES.toLong(), ENVELOPE_BYTES.toLong())

    private const val MA_MAGIC_OFFSET = 0
    private const val MA_CHECKSUM_START = 0
    private const val MA_CHECKSUM_END = 0xbd
    private const val MA_CHECKSUM_OFFSET = 0xbe

    private const val LM_MAGIC_OFFSET = 0x100
    private const val LM_VERSION_OFFSET = 0x102
    private const val LM_CHECKSUM_OFFSET = 0x103
    private const val LM_CHECKSUM_START = 0x105
    private const val LM_CHECKSUM_END = 0x15f
    private val LM_ADDRESS_TYPE_OFFSETS = intArrayOf(0x106, 0x107, 0x10a)

    private const val SUPPORTED_LM_VERSION = 0
    private const val MIN_ADDRESS_TYPE = 0
    private const val MAX_ADDRESS_TYPE = 2

    private const val ASCII_A: Byte = 0x41
    private const val ASCII_L: Byte = 0x4c
    private const val ASCII_M: Byte = 0x4d
  }
}
