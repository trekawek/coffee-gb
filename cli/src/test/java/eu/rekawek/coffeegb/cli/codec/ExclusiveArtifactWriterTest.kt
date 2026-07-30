package eu.rekawek.coffeegb.cli.codec

import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExclusiveArtifactWriterTest {
  @get:Rule
  val temporary = TemporaryFolder()

  @Test
  fun writesOnceAndNeverOverwritesExistingBytes() {
    val target = temporary.root.toPath().resolve("nested/artifact.bin")

    assertEquals(target.toAbsolutePath().normalize(), ExclusiveArtifactWriter.write(target, byteArrayOf(1, 2, 3)))
    assertFailsWith<FileAlreadyExistsException> {
      ExclusiveArtifactWriter.write(target, byteArrayOf(9, 9, 9))
    }
    assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
  }

  @Test
  fun refusesASymbolicLinkInTheParentPath() {
    val real = temporary.newFolder("real").toPath()
    val link = temporary.root.toPath().resolve("linked")
    try {
      Files.createSymbolicLink(link, real)
    } catch (failure: UnsupportedOperationException) {
      assumeNoException(failure)
    } catch (failure: FileSystemException) {
      assumeNoException(failure)
    } catch (failure: SecurityException) {
      assumeNoException(failure)
    }

    assertFailsWith<java.io.IOException> {
      ExclusiveArtifactWriter.write(link.resolve("artifact.bin"), byteArrayOf(1))
    }
    assertEquals(0, real.toFile().listFiles()?.size ?: 0)
  }

  @Test
  fun rejectsEmptyArtifacts() {
    assertFailsWith<IllegalArgumentException> {
      ExclusiveArtifactWriter.write(temporary.root.toPath().resolve("empty"), ByteArray(0))
    }
  }
}
