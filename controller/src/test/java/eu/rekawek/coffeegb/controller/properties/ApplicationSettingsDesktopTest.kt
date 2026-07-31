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
    assertEquals(ApplicationSettings.Appearance.LIGHT, defaults.desktop.appearance)
    assertTrue(defaults.desktop.commandBarVisible)

    val encoded = ApplicationSettingsCodec.encode(ApplicationSettingsDocument(defaults))
    assertEquals("8", encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
    assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_WIDTH_KEY in encoded)
    assertFalse(ApplicationSettingsCodec.DESKTOP_WINDOW_HEIGHT_KEY in encoded)
    assertEquals("LIGHT", encoded[ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY])
    assertEquals("true", encoded[ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY])

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
  fun `schema eight appearance and command bar choices round trip canonically`() {
    ApplicationSettings.Appearance.entries.forEach { appearance ->
      listOf(false, true).forEach { commandBarVisible ->
        val document =
            ApplicationSettingsDocument(
                ApplicationSettings(
                    desktop =
                        ApplicationSettings.Desktop(
                            windowSize = ApplicationSettings.WindowSize(937, 641),
                            appearance = appearance,
                            commandBarVisible = commandBarVisible,
                        )),
                mapOf("plugin.desktop" to "preserved"),
            )

        val encoded = ApplicationSettingsCodec.encode(document)

        assertEquals("8", encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
        assertEquals(appearance.name, encoded[ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY])
        assertEquals(
            commandBarVisible.toString(),
            encoded[ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY],
        )
        assertEquals(document, ApplicationSettingsCodec.decode(encoded))
        assertEquals(
            encoded,
            ApplicationSettingsCodec.encode(ApplicationSettingsCodec.decode(encoded)),
        )
      }
    }
  }

  @Test
  fun `schemas zero through seven preserve future appearance keys without applying them`() {
    val future =
        mapOf(
            ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY to "DARK",
            ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY to "false",
        )

    listOf<String?>(null, "1", "2", "3", "4", "5", "6", "7").forEach { version ->
      val raw =
          buildMap {
            version?.let { put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, it) }
            putAll(future)
          }

      val migrated = ApplicationSettingsCodec.decode(raw)

      assertEquals(
          ApplicationSettings.Appearance.LIGHT,
          migrated.settings.desktop.appearance,
          "schema ${version ?: 0}",
      )
      assertTrue(migrated.settings.desktop.commandBarVisible, "schema ${version ?: 0}")
      assertEquals(future, migrated.unknownProperties, "schema ${version ?: 0}")

      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("8", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
      assertEquals("LIGHT", canonical[ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY])
      assertEquals("true", canonical[ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY])
      assertTrue(
          canonical.keys.any {
            it.startsWith(ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
          })
      assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
    }
  }

  @Test
  fun `schema eight rejects malformed appearance and command bar values`() {
    val schema = mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "8")

    listOf("", "light", "AUTO", "DARK ").forEach { invalid ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for appearance '$invalid'") {
        ApplicationSettingsCodec.decode(
            schema + (ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY to invalid))
      }
    }
    listOf("", "yes", "1").forEach { invalid ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for visibility '$invalid'") {
        ApplicationSettingsCodec.decode(
            schema + (ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY to invalid))
      }
    }

    val defaults = ApplicationSettingsCodec.decode(schema).settings.desktop
    assertEquals(ApplicationSettings.Appearance.LIGHT, defaults.appearance)
    assertTrue(defaults.commandBarVisible)
  }

  @Test
  fun `schema seven store migration preserves colliding future appearance choices`() {
    val directory = Files.createTempDirectory("coffee-gb-appearance-schema-seven")
    val path = directory.resolve("settings.properties")
    val future =
        mapOf(
            ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY to "DARK",
            ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY to "false",
        )
    Files.write(
        path,
        ApplicationSettingsStore.encodeProperties(
            mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "7") +
                future +
                ("plugin.desktop" to "preserved")),
    )

    ApplicationSettingsStore(path, debounceMillis = 60_000).use { store ->
      assertEquals(ApplicationSettings.Appearance.LIGHT, store.current().settings.desktop.appearance)
      assertTrue(store.current().settings.desktop.commandBarVisible)
      assertEquals(future, store.current().unknownProperties.filterKeys(future::containsKey))
      assertEquals("preserved", store.current().unknownProperties["plugin.desktop"])
    }

    val canonical = ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path))
    assertEquals("8", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
    assertEquals("LIGHT", canonical[ApplicationSettingsCodec.DESKTOP_APPEARANCE_KEY])
    assertEquals("true", canonical[ApplicationSettingsCodec.DESKTOP_COMMAND_BAR_VISIBLE_KEY])
    assertTrue(
        canonical.keys.any {
          it.startsWith(ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
        })
    val reloaded = ApplicationSettingsCodec.decode(canonical)
    assertEquals(future, reloaded.unknownProperties.filterKeys(future::containsKey))
    assertEquals("preserved", reloaded.unknownProperties["plugin.desktop"])
  }

  @Test
  fun `window size round trips canonically with unknown settings`() {
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
      assertEquals("8", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
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
                    windowSize = ApplicationSettings.WindowSize(812, 577),
                    appearance = ApplicationSettings.Appearance.SYSTEM,
                    commandBarVisible = false,
                ))
      }
    }

    EmulatorProperties(path, debounceMillis = 60_000).use { reloaded ->
      assertEquals(
          ApplicationSettings.WindowSize(812, 577),
          reloaded.applicationSettings.desktop.windowSize,
      )
      assertEquals(
          ApplicationSettings.Appearance.SYSTEM,
          reloaded.applicationSettings.desktop.appearance,
      )
      assertFalse(reloaded.applicationSettings.desktop.commandBarVisible)
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
