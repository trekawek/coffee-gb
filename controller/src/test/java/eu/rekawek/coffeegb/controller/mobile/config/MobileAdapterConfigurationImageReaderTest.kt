package eu.rekawek.coffeegb.controller.mobile.config

import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

class MobileAdapterConfigurationImageReaderTest {

  private val reader = MobileAdapterConfigurationImageReader()

  @Test
  fun `accepts an exact raw image opaquely`() {
    val source = temporaryFile("raw-private-free.bin", syntheticBytes(256))

    val result = reader.read(source)

    assertNull(result.error)
    assertContentEquals(syntheticBytes(256), result.image())
    assertEquals(
        "MobileAdapterConfigurationImageReadResult(image=[redacted], error=null)",
        result.toString(),
    )
  }

  @Test
  fun `accepts a valid REON libmobile envelope and discards its suffix`() {
    val envelope = validEnvelope()
    val source = temporaryFile("envelope-private-free.bin", envelope)

    val result = reader.read(source)

    assertNull(result.error)
    assertContentEquals(envelope.copyOf(256), result.image())
    assertFalse(result.image()!!.contentEquals(envelope.copyOfRange(256, 512)))
  }

  @Test
  fun `rejects every envelope validation failure with one stable malformed error`() {
    val invalidEnvelopes =
        listOf(
            "MA magic" to validEnvelope().also { it[0] = 0 },
            "MA checksum" to validEnvelope().also { it[2] = (it[2].toInt() xor 1).toByte() },
            "LM magic" to validEnvelope().also { it[0x100] = 0 },
            "LM version" to validEnvelope().also { it[0x102] = 1 },
            "LM checksum" to
                validEnvelope().also { it[0x105] = (it[0x105].toInt() xor 1).toByte() },
        )
    invalidEnvelopes.forEach { (label, envelope) ->
      assertMalformed(temporaryFile("invalid-${label.replace(' ', '-')}.bin", envelope), label)
    }

    listOf(0x106, 0x107, 0x10a).forEach { offset ->
      val envelope = validEnvelope().also { it[offset] = 3 }
      rewriteLmChecksum(envelope)
      assertMalformed(temporaryFile("invalid-address-$offset.bin", envelope), "address $offset")
    }
  }

  @Test
  fun `rejects invalid sizes at both accepted boundaries`() {
    listOf(255, 257, 511, 513).forEach { size ->
      val result = reader.read(temporaryFile("invalid-size-$size.bin", syntheticBytes(size)))

      assertNull(result.image(), "size $size")
      assertEquals(
          MobileAdapterConfigurationError.IMPORT_MALFORMED_IMAGE,
          result.error,
          "size $size",
      )
    }
  }

  @Test
  fun `rejects directories and symbolic links as non-regular sources`() {
    val directory = Files.createTempDirectory("coffee-gb-image-directory")
    val directoryResult = reader.read(directory)

    assertNull(directoryResult.image())
    assertEquals(
        MobileAdapterConfigurationError.IMPORT_NON_REGULAR_SOURCE,
        directoryResult.error,
    )

    val target = temporaryFile("symlink-target.bin", syntheticBytes(256))
    val link = target.parent.resolve("selected-image-link.bin")
    try {
      Files.createSymbolicLink(link, target.fileName)
    } catch (unsupported: UnsupportedOperationException) {
      assumeNoException("Symbolic links are not supported", unsupported)
    } catch (denied: IOException) {
      assumeNoException("Symbolic links are not permitted", denied)
    } catch (denied: SecurityException) {
      assumeNoException("Symbolic links are not permitted", denied)
    }

    val linkResult = reader.read(link)
    assertNull(linkResult.image())
    assertEquals(
        MobileAdapterConfigurationError.IMPORT_NON_REGULAR_SOURCE,
        linkResult.error,
    )
  }

  @Test
  fun `reports a portable missing-source read failure with redacted diagnostics`() {
    val directory = Files.createTempDirectory("coffee-gb-image-missing")
    val secretName = "private-account-513-bytes-secret.bin"
    val missing = directory.resolve(secretName)

    val result = reader.read(missing)

    assertNull(result.image())
    val error = checkNotNull(result.error)
    assertEquals(MobileAdapterConfigurationError.IMPORT_READ_FAILED, error)
    val diagnostics = result.toString() + error.code + error.userMessage
    assertFalse(diagnostics.contains(directory.toString()))
    assertFalse(diagnostics.contains(secretName))
    assertFalse(diagnostics.contains("513"))
    assertFalse(diagnostics.contains("account"))
    assertEquals(
        "MobileAdapterConfigurationImageReadResult(image=null, error=IMPORT_READ_FAILED)",
        result.toString(),
    )
  }

  @Test
  fun `rejects a directory entry replaced between validation and open`() {
    val directory = Files.createTempDirectory("coffee-gb-image-replaced")
    val source = Files.write(directory.resolve("selected.bin"), syntheticBytes(256))
    val replacement =
        Files.write(directory.resolve("replacement.bin"), syntheticBytes(256).reversedArray())
    Files.setLastModifiedTime(
        replacement,
        FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 60_000),
    )
    val swappingReader =
        MobileAdapterConfigurationImageReader.withOpenHookForTest {
          Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING)
        }

    val result = swappingReader.read(source)

    assertNull(result.image())
    assertEquals(MobileAdapterConfigurationError.IMPORT_READ_FAILED, result.error)
  }

  @Test
  fun `reports a malformed candidate changed after read as an unstable source`() {
    val source = temporaryFile("malformed-changing.bin", validEnvelope().also { it[0] = 0 })
    val changingReader =
        MobileAdapterConfigurationImageReader.withPostReadHookForTest {
          Files.write(source, byteArrayOf(0), StandardOpenOption.APPEND)
        }

    val result = changingReader.read(source)

    assertNull(result.image())
    assertEquals(MobileAdapterConfigurationError.IMPORT_READ_FAILED, result.error)
  }

  @Test
  fun `rejects a provider without stable file identity`() {
    val directory = Files.createTempDirectory("coffee-gb-image-no-file-key")
    val archive = directory.resolve("images.zip")
    val uri = URI.create("jar:${archive.toUri()}")
    FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fileSystem ->
      val source = Files.write(fileSystem.getPath("/selected.bin"), syntheticBytes(256))
      val attributes =
          Files.readAttributes(source, BasicFileAttributes::class.java)
      assumeTrue("ZIP provider unexpectedly supplies a file key", attributes.fileKey() == null)

      val result = reader.read(source)

      assertNull(result.image())
      assertEquals(MobileAdapterConfigurationError.IMPORT_READ_FAILED, result.error)
    }
  }

  @Test
  fun `does not change source permissions`() {
    val source = temporaryFile("permission-sentinel.bin", syntheticBytes(256))
    assumeTrue(Files.getFileStore(source).supportsFileAttributeView("posix"))
    val permissions =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
        )
    Files.setPosixFilePermissions(source, permissions)

    val result = reader.read(source)

    assertNull(result.error)
    assertEquals(permissions, Files.getPosixFilePermissions(source))
  }

  @Test
  fun `result owns its image and enforces exclusive success or failure`() {
    val supplied = syntheticBytes(256)
    val result = MobileAdapterConfigurationImageReadResult(supplied, null)
    supplied[0] = 0
    val firstCopy = result.image()!!
    firstCopy[1] = 0

    assertEquals(0x5a, result.image()!![0].toInt() and 0xff)
    assertEquals(0x7f, result.image()!![1].toInt() and 0xff)
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterConfigurationImageReadResult(null, null)
    }
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterConfigurationImageReadResult(
          syntheticBytes(256),
          MobileAdapterConfigurationError.IMPORT_MALFORMED_IMAGE,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      MobileAdapterConfigurationImageReadResult(ByteArray(255), null)
    }
  }

  private fun assertMalformed(source: Path, label: String) {
    val result = reader.read(source)
    assertNull(result.image(), label)
    assertEquals(MobileAdapterConfigurationError.IMPORT_MALFORMED_IMAGE, result.error, label)
    assertEquals(
        "The selected Mobile Adapter image has an invalid format.",
        result.error!!.userMessage,
        label,
    )
  }

  private fun validEnvelope(): ByteArray {
    val envelope = syntheticBytes(512)
    envelope[0] = 'M'.code.toByte()
    envelope[1] = 'A'.code.toByte()
    val maChecksum = checksum(envelope, 0..0xbd)
    envelope[0xbe] = (maChecksum ushr 8).toByte()
    envelope[0xbf] = maChecksum.toByte()

    envelope[0x100] = 'L'.code.toByte()
    envelope[0x101] = 'M'.code.toByte()
    envelope[0x102] = 0
    envelope[0x106] = 0
    envelope[0x107] = 1
    envelope[0x10a] = 2
    rewriteLmChecksum(envelope)
    return envelope
  }

  private fun rewriteLmChecksum(envelope: ByteArray) {
    val lmChecksum = checksum(envelope, 0x105..0x15f)
    envelope[0x103] = lmChecksum.toByte()
    envelope[0x104] = (lmChecksum ushr 8).toByte()
  }

  private fun checksum(bytes: ByteArray, range: IntRange): Int =
      range.fold(0) { sum, offset -> (sum + (bytes[offset].toInt() and 0xff)) and 0xffff }

  private fun syntheticBytes(size: Int): ByteArray =
      ByteArray(size) { index -> ((index * 37 + 0x5a) and 0xff).toByte() }

  private fun temporaryFile(name: String, bytes: ByteArray): Path {
    val directory = Files.createTempDirectory("coffee-gb-image-reader")
    return Files.write(directory.resolve(name), bytes)
  }
}
