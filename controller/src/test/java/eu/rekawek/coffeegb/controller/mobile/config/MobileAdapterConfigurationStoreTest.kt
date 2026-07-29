package eu.rekawek.coffeegb.controller.mobile.config

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MobileAdapterConfigurationStoreTest {

  @Test
  fun `configuration owns bytes validates bounds and redacts diagnostics`() {
    val input = ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { it.toByte() }
    val configuration = MobileAdapterConfiguration(127, input)
    input[0] = 0x7f
    val detached = configuration.configurationBytes()
    detached[1] = 0

    assertEquals(0, configuration.configurationBytes()[0].toInt() and 0xff)
    assertEquals(1, configuration.configurationBytes()[1].toInt() and 0xff)
    assertEquals(configuration, MobileAdapterConfiguration(127, ByteArray(256) { it.toByte() }))
    assertEquals(configuration.hashCode(), MobileAdapterConfiguration(127, ByteArray(256) { it.toByte() }).hashCode())
    assertEquals(
        "MobileAdapterConfiguration(deviceId=127, configuration=[redacted])",
        configuration.toString(),
    )
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(-1, ByteArray(256)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(128, ByteArray(256)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(8, ByteArray(255)) }
    assertFailsWith<IllegalArgumentException> { MobileAdapterConfiguration(8, ByteArray(257)) }
  }

  @Test
  fun `codec is deterministic fixed size and round trips the synthetic fixture`() {
    val fallback = MobileAdapterConfiguration.syntheticFallback()
    val first = MobileAdapterConfigurationCodec.encode(fallback)
    val second = MobileAdapterConfigurationCodec.encode(fallback)

    assertEquals(MobileAdapterConfigurationCodec.ENCODED_SIZE, first.size)
    assertContentEquals(first, second)
    assertContentEquals("CGBMACFG".toByteArray(StandardCharsets.US_ASCII), first.copyOfRange(0, 8))
    assertEquals(MobileAdapterConfigurationCodec.FORMAT_VERSION, first[8].toInt() and 0xff)
    assertEquals(0x08, first[9].toInt() and 0xff)
    assertEquals(1, first[10].toInt() and 0xff)
    assertEquals(0, first[11].toInt() and 0xff)
    assertEquals(fallback, MobileAdapterConfigurationCodec.decode(first))
    assertEquals(0x4d, fallback.configurationBytes()[0].toInt() and 0xff)
    assertEquals(0x81, fallback.configurationBytes()[2].toInt() and 0xff)
    assertEquals(127, fallback.configurationBytes()[255].toInt() and 0xff)
  }

  @Test
  fun `codec rejects size header bounds version and integrity violations`() {
    val encoded =
        MobileAdapterConfigurationCodec.encode(MobileAdapterConfiguration.syntheticFallback())

    assertDecodeError(
        encoded.copyOf(encoded.size - 1),
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        encoded.copyOf(encoded.size + 1),
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        encoded.clone().also { it[0] = 0 },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        encoded.clone().also { it[8] = 2 },
        MobileAdapterConfigurationError.UNSUPPORTED_VERSION,
    )
    assertDecodeError(
        encoded.clone().also { it[9] = 0x80.toByte() },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        encoded.clone().also {
          it[10] = 0
          it[11] = 0
        },
        MobileAdapterConfigurationError.MALFORMED_FILE,
    )
    assertDecodeError(
        encoded.clone().also { it[12] = (it[12].toInt() xor 1).toByte() },
        MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED,
    )
  }

  @Test
  fun `missing file uses deterministic synthetic fallback without warning`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-missing")
    val store = MobileAdapterConfigurationStore(directory.resolve("adapter.bin"))

    val first = store.load()
    val second = MobileAdapterConfigurationStore(directory.resolve("other.bin")).load()

    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, first.source)
    assertEquals(null, first.error)
    assertFalse(first.recoveryPerformed)
    assertEquals(MobileAdapterConfiguration.syntheticFallback(), first.configuration)
    assertEquals(first.configuration, second.configuration)
  }

  @Test
  fun `save and load round trip atomically with restrictive final permissions`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-roundtrip")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(42, 0x5a)
    val store = MobileAdapterConfigurationStore(path)

    assertEquals(MobileAdapterConfigurationSaveResult(saved = true), store.save(expected))
    assertEquals(MobileAdapterConfigurationCodec.ENCODED_SIZE.toLong(), Files.size(path))
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      assertEquals(
          setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
          Files.getPosixFilePermissions(path),
      )
    }

    val loaded = MobileAdapterConfigurationStore(path).load()
    assertEquals(MobileAdapterConfigurationSource.PERSISTED, loaded.source)
    assertEquals(null, loaded.error)
    assertEquals(expected, loaded.configuration)
  }

  @Test
  fun `corrupt startup data falls back with typed redacted error`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-corrupt")
    val path = directory.resolve("private-account-adapter.bin")
    val hostile = ByteArray(MobileAdapterConfigurationCodec.ENCODED_SIZE)
    val secret = "phone=5551234 token=private /sensitive/account"
    secret.toByteArray(StandardCharsets.UTF_8).copyInto(hostile)
    Files.write(path, hostile)

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, result.source)
    assertEquals(MobileAdapterConfigurationError.MALFORMED_FILE, result.error)
    assertEquals(MobileAdapterConfiguration.syntheticFallback(), result.configuration)
    assertFalse(result.toString().contains("5551234"))
    assertFalse(result.toString().contains("private-account"))
    assertFalse(result.toString().contains(directory.toString()))
    assertFalse(result.error!!.userMessage.contains(path.toString()))
  }

  @Test
  fun `malformed existing record is permission hardened before decode fails`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-private-corrupt")
    val path = directory.resolve("adapter.bin")
    Files.write(path, ByteArray(MobileAdapterConfigurationCodec.ENCODED_SIZE))
    if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
    Files.setPosixFilePermissions(
        path,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        ),
    )

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationError.MALFORMED_FILE, result.error)
    assertEquals(
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(path),
    )
  }

  @Test
  fun `corrupt reload retains the last good immutable configuration`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-last-good")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(9, 0x33)
    val store = MobileAdapterConfigurationStore(path)
    assertTrue(store.save(expected).saved)
    val corrupt = Files.readAllBytes(path)
    corrupt[12] = (corrupt[12].toInt() xor 1).toByte()
    Files.write(path, corrupt)

    val result = store.load()

    assertEquals(MobileAdapterConfigurationSource.LAST_GOOD, result.source)
    assertEquals(MobileAdapterConfigurationError.INTEGRITY_CHECK_FAILED, result.error)
    assertEquals(expected, result.configuration)
    assertEquals(expected, store.current())
  }

  @Test
  fun `failed pre commit replacement preserves persisted and in memory last good values`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-interrupted")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val originalBytes = Files.readAllBytes(path)
    val store = MobileAdapterConfigurationStore(path, FailBeforeWriteWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.STORAGE_WRITE_FAILED, failure.error)
    assertContentEquals(originalBytes, Files.readAllBytes(path))
    assertEquals(original, store.current())
  }

  @Test
  fun `reported post commit failure stays failed and retains last good until retry`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-post-commit")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    val updated = configuration(10, 0x7f)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val store = MobileAdapterConfigurationStore(path, CommitThenFailOnceWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(updated)

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.STORAGE_WRITE_FAILED, failure.error)
    assertContentEquals(MobileAdapterConfigurationCodec.encode(updated), Files.readAllBytes(path))
    assertEquals(original, store.current())

    assertTrue(store.save(updated).saved)
    assertEquals(updated, store.current())
    assertEquals(updated, MobileAdapterConfigurationStore(path).load().configuration)
  }

  @Test
  fun `permission preparation failure cannot commit private replacement`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-permission-failure")
    val path = directory.resolve("adapter.bin")
    val original = configuration(8, 0x21)
    assertTrue(MobileAdapterConfigurationStore(path).save(original).saved)
    val originalBytes = Files.readAllBytes(path)
    val store = MobileAdapterConfigurationStore(path, PermissionFailingWriter())
    assertEquals(original, store.load().configuration)

    val failure = store.save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED, failure.error)
    assertContentEquals(originalBytes, Files.readAllBytes(path))
    assertEquals(original, store.current())
  }

  @Test
  fun `group writable parent is rejected before a private record is created`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-untrusted-parent")
    assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
    Files.setPosixFilePermissions(
        directory,
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
        ),
    )
    val path = directory.resolve("adapter.bin")

    val failure = MobileAdapterConfigurationStore(path).save(configuration(10, 0x7f))

    assertFalse(failure.saved)
    assertEquals(MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED, failure.error)
    assertFalse(Files.exists(path))
  }

  @Test
  fun `atomic writer restores a complete last good backup before decoding`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-backup")
    val path = directory.resolve("adapter.bin")
    val expected = configuration(17, 0x66)
    Files.write(backupPath(path), MobileAdapterConfigurationCodec.encode(expected))

    val result = MobileAdapterConfigurationStore(path).load()

    assertEquals(MobileAdapterConfigurationSource.RECOVERED_BACKUP, result.source)
    assertTrue(result.recoveryPerformed)
    assertEquals(null, result.error)
    assertEquals(expected, result.configuration)
    assertTrue(Files.isRegularFile(path))
    assertFalse(Files.exists(backupPath(path)))
  }

  @Test
  fun `non regular target is rejected without following a symbolic link`() {
    val directory = Files.createTempDirectory("coffee-gb-mobile-config-symlink")
    val external = directory.resolve("external.bin")
    val externalBytes = MobileAdapterConfigurationCodec.encode(configuration(2, 0x44))
    Files.write(external, externalBytes)
    val path = directory.resolve("adapter.bin")
    try {
      Files.createSymbolicLink(path, external.fileName)
    } catch (unsupported: UnsupportedOperationException) {
      return
    } catch (denied: SecurityException) {
      return
    } catch (denied: IOException) {
      return
    }
    val store = MobileAdapterConfigurationStore(path)

    val load = store.load()
    val save = store.save(configuration(3, 0x55))

    assertEquals(MobileAdapterConfigurationError.NON_REGULAR_FILE, load.error)
    assertEquals(MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK, load.source)
    assertEquals(MobileAdapterConfigurationError.NON_REGULAR_FILE, save.error)
    assertTrue(Files.isSymbolicLink(path))
    assertContentEquals(externalBytes, Files.readAllBytes(external))
  }

  private fun assertDecodeError(
      encoded: ByteArray,
      expected: MobileAdapterConfigurationError,
  ) {
    val failure =
        assertFailsWith<MobileAdapterConfigurationFormatException> {
          MobileAdapterConfigurationCodec.decode(encoded)
        }
    assertEquals(expected, failure.error)
  }

  private fun configuration(deviceId: Int, seed: Int): MobileAdapterConfiguration =
      MobileAdapterConfiguration(
          deviceId,
          ByteArray(MobileAdapterConfiguration.CONFIGURATION_SIZE) { index ->
            (seed + index * 31).toByte()
          },
      )

  private fun backupPath(target: Path): Path {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(target.fileName.toString().toByteArray(StandardCharsets.UTF_8))
    val id = (0 until 16).joinToString("") { "%02x".format(digest[it].toInt() and 0xff) }
    return target.parent.resolve(".coffeegb-$id.backup")
  }

  private class FailBeforeWriteWriter : AtomicFileWriter() {
    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      throw IOException("injected pre-commit replacement failure")
    }
  }

  private class CommitThenFailOnceWriter : AtomicFileWriter() {
    private var fail = true

    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      AtomicFileWriter.system().writeOwnerOnly(target, intendedBytes)
      if (fail) {
        fail = false
        throw IOException("injected post-commit replacement failure")
      }
    }
  }

  private class PermissionFailingWriter : AtomicFileWriter() {
    override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
      throw AtomicFileWriter.OwnerOnlyPermissionsException(
          "injected private permission preparation failure")
    }
  }
}
