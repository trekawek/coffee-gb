package eu.rekawek.coffeegb.swing

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableSettingsPathTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `jar location is independent of current directory and preserves spaces`() {
    val directory = temporaryFolder.newFolder("Coffee GB").toPath()
    val jar = Files.createFile(directory.resolve("coffee-gb.jar"))
    assertEquals(
        directory.resolve("coffeegb.properties"),
        portableSettingsPath(jar.toUri(), packagedLauncher = null),
    )
  }

  @Test
  fun `native launcher takes precedence over jar in its app subdirectory`() {
    val directory = temporaryFolder.newFolder("installed").toPath()
    val launcher = Files.createFile(directory.resolve("Coffee GB.exe"))
    val jar = Files.createFile(Files.createDirectory(directory.resolve("app")).resolve("coffee-gb.jar"))
    assertEquals(
        directory.resolve("coffeegb.properties"),
        portableSettingsPath(jar.toUri(), launcher.toString(), directory.resolve("other-tmp").toString()),
    )
  }

  @Test
  fun `class directory and extracted temporary launcher are rejected`() {
    val directory = temporaryFolder.newFolder("classes").toPath()
    val classFailure = assertFailsWith<PortableSettingsLocationException> {
      portableSettingsPath(directory.toUri(), packagedLauncher = null)
    }
    assertTrue(classFailure.message!!.contains("without a JAR"))
    val launcher = Files.createFile(directory.resolve("Coffee GB.exe"))
    val tempFailure = assertFailsWith<PortableSettingsLocationException> {
      portableSettingsPath(null, launcher.toString(), temporaryFolder.root.toString())
    }
    assertTrue(tempFailure.message!!.contains("temporary directory"))
  }
}
