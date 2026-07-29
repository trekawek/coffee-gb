package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsDesktopTest {

  @Test
  fun `desktop defaults omit window geometry and dimensions must be positive`() {
    val defaults = ApplicationSettings()
    assertNull(defaults.desktop.windowSize)

    val encoded = ApplicationSettingsCodec.encode(ApplicationSettingsDocument(defaults))
    assertEquals("6", encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
    assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY in encoded)
    assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY in encoded)

    ApplicationSettings.WindowSize(1, 1)
    ApplicationSettings.WindowSize(Int.MAX_VALUE, Int.MAX_VALUE)
    listOf(0, -1, Int.MIN_VALUE).forEach { invalid ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.WindowSize(invalid, 480)
      }
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.WindowSize(640, invalid)
      }
    }
  }

  @Test
  fun `schema six window size round trips canonically with unknown settings`() {
    val size = ApplicationSettings.WindowSize(937, 641)
    val document =
        ApplicationSettingsDocument(
            ApplicationSettings(desktop = ApplicationSettings.Desktop(size)),
            mapOf("plugin.desktop" to "preserved"),
        )

    val encoded = ApplicationSettingsCodec.encode(document)
    assertEquals("937", encoded[ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY])
    assertEquals("641", encoded[ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY])
    assertEquals(document, ApplicationSettingsCodec.decode(encoded))
    assertEquals(encoded, ApplicationSettingsCodec.encode(ApplicationSettingsCodec.decode(encoded)))
  }

  @Test
  fun `schemas zero through five preserve future window keys without applying them`() {
    val future =
        mapOf(
            ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY to "901",
            ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY to "607",
        )

    listOf<String?>(null, "1", "2", "3", "4", "5").forEach { version ->
      val raw =
          buildMap {
            version?.let { put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, it) }
            putAll(future)
          }
      val migrated = ApplicationSettingsCodec.decode(raw)
      assertNull(migrated.settings.desktop.windowSize, "schema ${version ?: 0}")
      assertEquals(future, migrated.unknownProperties, "schema ${version ?: 0}")

      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("6", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
      assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY in canonical)
      assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY in canonical)
      assertTrue(
          canonical.keys.any {
            it.startsWith(ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
          })
      assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
    }
  }

  @Test
  fun `schema six rejects partial malformed overflowing and nonpositive geometry`() {
    val schema = mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "6")
    val invalid =
        listOf(
            mapOf(ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY to "640"),
            mapOf(ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY to "480"),
            windowFields("zero", "480"),
            windowFields("640", "zero"),
            windowFields("2147483648", "480"),
            windowFields("640", "2147483648"),
            windowFields("0", "480"),
            windowFields("640", "0"),
            windowFields("-1", "480"),
            windowFields("640", "-1"),
        )

    invalid.forEach { fields ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $fields") {
        ApplicationSettingsCodec.decode(schema + fields)
      }
    }
  }

  @Test
  fun `window size survives settings close and reload without losing unknown values`() {
    val directory = Files.createTempDirectory("coffee-gb-window-size")
    val path = directory.resolve("settings.properties")
    val original =
        ApplicationSettingsDocument(
            ApplicationSettings(),
            mapOf("plugin.desktop" to "keep me"),
        )
    Files.write(
        path,
        ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(original)),
    )

    EmulatorProperties(path, debounceMillis = 60_000).use { properties ->
      properties.updateApplicationSettings { current ->
        current.copy(
            desktop =
                current.desktop.copy(
                    windowSize = ApplicationSettings.WindowSize(812, 577)))
      }
    }

    EmulatorProperties(path, debounceMillis = 60_000).use { reloaded ->
      assertEquals(
          ApplicationSettings.WindowSize(812, 577),
          reloaded.applicationSettings.desktop.windowSize,
      )
      val document =
          ApplicationSettingsCodec.decode(
              ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
      assertEquals("keep me", document.unknownProperties["plugin.desktop"])
    }
  }

  private fun windowFields(width: String, height: String): Map<String, String> =
      mapOf(
          ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY to width,
          ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY to height,
      )
}
