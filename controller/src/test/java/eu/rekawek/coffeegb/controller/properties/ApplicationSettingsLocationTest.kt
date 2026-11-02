package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsLocationTest {
  @Test
  fun `Windows uses local app data with a normal file name`() {
    val root = Files.createTempDirectory("coffee-gb-location-paths")
    val home = root.resolve("home")
    val local = root.resolve("redirected-local")
    val paths = ApplicationSettingsStore.settingsPaths("Windows 11", local.toString(), home)
    assertEquals(local.resolve("Coffee GB/coffeegb.properties"), paths.path)
    assertEquals(home.resolve(".coffeegb.properties"), paths.legacyPath)
    assertEquals(
        home.resolve("AppData/Local/Coffee GB/coffeegb.properties"),
        ApplicationSettingsStore.settingsPaths("Windows 10", "", home).path,
    )
    assertEquals(
        home.resolve("AppData/Local/Coffee GB/coffeegb.properties"),
        ApplicationSettingsStore.settingsPaths("Windows 10", "relative/local", home).path,
    )
    assertEquals(
        home.resolve("AppData/Local/Coffee GB/coffeegb.properties"),
        ApplicationSettingsStore.settingsPaths("Windows 10", "invalid\u0000path", home).path,
    )
    val linux = ApplicationSettingsStore.settingsPaths("Linux", local.toString(), home)
    assertEquals(home.resolve(".coffeegb.properties"), linux.path)
    assertEquals(null, linux.legacyPath)
  }

  @Test
  fun `legacy settings migrate into new location without changing legacy file or its directory`() {
    val root = Files.createTempDirectory("coffee-gb-location")
    val legacyDir = Files.createDirectory(root.resolve("home"))
    val legacy = legacyDir.resolve(".coffeegb.properties")
    val original = "display.scale=4\nplugin.setting=retain\n".toByteArray()
    Files.write(legacy, original)
    val target = root.resolve("local/Coffee GB/coffeegb.properties")

    ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
      assertEquals(4, store.current().settings.display.scale)
      assertEquals("retain", store.current().unknownProperties["plugin.setting"])
      assertTrue(Files.exists(target))
      store.update(store.current().copy(settings = store.current().settings.copy(
          display = store.current().settings.display.copy(explicitScale = 2))))
      store.flush()
    }

    assertContentEquals(original, Files.readAllBytes(legacy))
    Files.list(legacyDir).use { assertEquals(listOf(legacy), it.toList()) }
    ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
      assertEquals(2, store.current().settings.display.scale)
    }
  }

  @Test
  fun `an existing new file takes precedence over legacy`() {
    val root = Files.createTempDirectory("coffee-gb-location-precedence")
    val legacy = root.resolve("legacy.properties")
    val target = root.resolve("new.properties")
    Files.writeString(legacy, "display.scale=4\n")
    Files.writeString(target, "display.scale=2\n")
    ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
      assertEquals(2, store.current().settings.display.scale)
    }
    assertEquals("display.scale=4\n", Files.readString(legacy))
  }

  @Test
  fun `destination created after the initial check wins over legacy import`() {
    val root = Files.createTempDirectory("coffee-gb-location-race")
    val legacy = root.resolve("legacy.properties")
    val target = root.resolve("new.properties")
    Files.writeString(legacy, "display.scale=4\n")
    val newSettings = "display.scale=2\n".toByteArray()
    val writer = object : AtomicFileWriter() {
      var firstRead = true

      override fun <T : Any?> read(target: Path, reader: AtomicFileWriter.PathReader<T>): T {
        val result = super.read(target, reader)
        if (firstRead) {
          firstRead = false
          // Simulate another instance committing after the first read released its lock.
          AtomicFileWriter.system().write(target, newSettings)
        }
        return result
      }
    }

    ApplicationSettingsStore(target, writer, legacyPath = legacy).use { store ->
      assertEquals(2, store.current().settings.display.scale)
    }
    // The existing v0 settings may be schema-migrated, but the legacy value must never replace it.
    ApplicationSettingsStore(target).use { store ->
      assertEquals(2, store.current().settings.display.scale)
    }
  }

  @Test
  fun `current schema legacy file is imported and preserved`() {
    val root = Files.createTempDirectory("coffee-gb-location-current")
    val legacy = root.resolve(".coffeegb.properties")
    val original = ApplicationSettingsStore.encodeProperties(
        ApplicationSettingsCodec.encode(ApplicationSettingsDocument(ApplicationSettings())))
    Files.write(legacy, original)
    val target = root.resolve("local/coffeegb.properties")
    ApplicationSettingsStore(target, legacyPath = legacy).use {
      assertTrue(Files.exists(target))
    }
    assertContentEquals(original, Files.readAllBytes(legacy))
  }

  @Test
  fun `invalid and oversized legacy settings remain unchanged while new settings can be saved`() {
    val invalidFiles = listOf(
        "bad=\\uZZZZ\n".toByteArray(),
        ByteArray(ApplicationSettingsStore.MAX_SETTINGS_BYTES + 1) { 'a'.code.toByte() },
    )
    for (original in invalidFiles) {
      val root = Files.createTempDirectory("coffee-gb-location-invalid")
      val legacyDir = Files.createDirectory(root.resolve("home"))
      val legacy = legacyDir.resolve(".coffeegb.properties")
      Files.write(legacy, original)
      val target = root.resolve("local/Coffee GB/coffeegb.properties")
      ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
        assertFalse(store.isReadOnly())
        assertEquals(ApplicationSettingsWarningKind.CORRUPT_FILE_RECOVERED, store.consumeLoadWarning()?.kind)
        store.update(store.current().copy(settings = store.current().settings.copy(
            display = store.current().settings.display.copy(explicitScale = 2))))
        store.flush()
      }
      assertContentEquals(original, Files.readAllBytes(legacy))
      Files.list(legacyDir).use { assertEquals(listOf(legacy), it.toList()) }
      ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
        assertEquals(2, store.current().settings.display.scale)
        assertEquals(null, store.consumeLoadWarning())
      }
    }
  }

  @Test
  fun `future legacy settings remain read only`() {
    val root = Files.createTempDirectory("coffee-gb-location-future")
    val legacy = root.resolve(".coffeegb.properties")
    val original = "settings.schemaVersion=${ApplicationSettings.CURRENT_SCHEMA_VERSION + 1}\n".toByteArray()
    Files.write(legacy, original)
    val target = root.resolve("local/coffeegb.properties")
    ApplicationSettingsStore(target, legacyPath = legacy).use { store ->
      assertTrue(store.isReadOnly())
      assertEquals(ApplicationSettingsWarningKind.FUTURE_SCHEMA, store.consumeLoadWarning()?.kind)
    }
    assertFalse(Files.exists(target))
    assertContentEquals(original, Files.readAllBytes(legacy))
  }
}
