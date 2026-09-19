package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApplicationSettingsTranslationTest {

  @Test
  fun `translation key round trips while an empty key is omitted`() {
    val defaults = ApplicationSettingsDocument(ApplicationSettings())
    assertEquals("", defaults.settings.translation.apiKey)
    assertFalse(KEY in ApplicationSettingsCodec.encode(defaults))

    val configured = defaults.copy(
        settings = defaults.settings.copy(translation = ApplicationSettings.Translation(TEST_KEY)),
        unknownProperties = mapOf("plugin.translation" to "preserved"),
    )
    val encoded = ApplicationSettingsCodec.encode(configured)
    assertEquals(TEST_KEY, encoded[KEY])
    assertEquals(configured, ApplicationSettingsCodec.decode(encoded))

    val cleared = configured.copy(
        settings = configured.settings.copy(translation = ApplicationSettings.Translation()))
    val clearedEncoding = ApplicationSettingsCodec.encode(cleared)
    assertFalse(KEY in clearedEncoding)
    assertEquals(cleared, ApplicationSettingsCodec.decode(clearedEncoding))
    assertFalse(TEST_KEY in clearedEncoding.values)
  }

  @Test
  fun `schemas zero through eleven preserve future keys without activating them`() {
    listOf(TEST_KEY, "malformed legacy key\n").forEach { previousValue ->
      (0..11).forEach { version ->
        val raw = buildMap {
          if (version != 0) put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, version.toString())
          put(KEY, previousValue)
          put("plugin.translation", "preserved")
        }
        val migrated = ApplicationSettingsCodec.decode(raw)
        assertEquals("", migrated.settings.translation.apiKey)
        assertEquals(previousValue, migrated.unknownProperties[KEY])
        val canonical = ApplicationSettingsCodec.encode(migrated)
        assertEquals(ApplicationSettings.CURRENT_SCHEMA_VERSION.toString(),
            canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
        assertFalse(KEY in canonical)
        assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
        assertFalse(migrated.toString().contains(previousValue))
      }
    }
  }

  @Test
  fun `all providers round trip with a retained optional OpenAI key`() {
    ApplicationSettings.TranslationProvider.entries.forEach { provider ->
      val configured = ApplicationSettingsDocument(ApplicationSettings(
          translation = ApplicationSettings.Translation(TEST_KEY, provider)))
      val encoded = ApplicationSettingsCodec.encode(configured)
      assertEquals(provider.name, encoded[PROVIDER])
      assertEquals(TEST_KEY, encoded[KEY])
      assertEquals(configured, ApplicationSettingsCodec.decode(encoded))
    }
    assertEquals(ApplicationSettings.TranslationProvider.AUTOMATIC,
        ApplicationSettingsCodec.decode(mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "13")).settings.translation.provider)
  }

  @Test
  fun `schema twelve retains its saved key and starts with automatic provider`() {
    val migrated = ApplicationSettingsCodec.decode(mapOf(
        ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "12",
        KEY to TEST_KEY,
    ))
    assertEquals(ApplicationSettings.Translation(TEST_KEY), migrated.settings.translation)
    assertEquals(migrated, ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(migrated)))
  }

  @Test
  fun `schemas before thirteen preserve an unknown provider without activating it`() {
    (0..12).forEach { version ->
      val migrated = ApplicationSettingsCodec.decode(buildMap {
        if (version != 0) put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, version.toString())
        put(PROVIDER, "unrecognized future provider")
      })
      assertEquals(ApplicationSettings.TranslationProvider.AUTOMATIC,
          migrated.settings.translation.provider)
      assertEquals("unrecognized future provider", migrated.unknownProperties[PROVIDER])
      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("AUTOMATIC", canonical[PROVIDER])
      assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
    }
  }

  @Test
  fun `invalid active providers fail without printing arbitrary property values`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      ApplicationSettingsCodec.decode(mapOf(
          ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "13",
          PROVIDER to TEST_KEY,
      ))
    }
    assertTrue(failure.message.orEmpty().contains(PROVIDER))
    assertFalse(failure.stackTraceToString().contains(TEST_KEY))
  }

  @Test
  fun `invalid active keys fail safely without including their values`() {
    val schema = mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "12")
    listOf(
        "$TEST_KEY ",
        " $TEST_KEY",
        "$TEST_KEY\t",
        "$TEST_KEY\nAuthorization: secret",
        "$TEST_KEY\u0000",
        "$TEST_KEY\u007f",
        "$TEST_KEY\u00e9",
        TEST_KEY + "a".repeat(ApplicationSettings.MAX_TRANSLATION_API_KEY_LENGTH),
    ).forEach { invalid ->
      val typedFailure = assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.Translation(invalid)
      }
      val decodedFailure = assertFailsWith<IllegalArgumentException> {
        ApplicationSettingsCodec.decode(schema + (KEY to invalid))
      }
      listOf(typedFailure, decodedFailure).forEach { failure ->
        assertFalse(failure.stackTraceToString().contains(TEST_KEY))
        assertTrue(failure.message.orEmpty().contains("OpenAI API key"))
      }
    }
    ApplicationSettings.Translation("a".repeat(ApplicationSettings.MAX_TRANSLATION_API_KEY_LENGTH))
    assertEquals("", ApplicationSettingsCodec.decode(schema + (KEY to "")).settings.translation.apiKey)
    assertEquals("", ApplicationSettingsCodec.decode(schema).settings.translation.apiKey)
  }

  @Test
  fun `typed and preserved credential values are redacted from diagnostics`() {
    val translation = ApplicationSettings.Translation(TEST_KEY)
    val settings = ApplicationSettings(translation = translation)
    val document = ApplicationSettingsDocument(settings, mapOf(KEY to "$TEST_KEY-previous"))
    listOf(translation, settings, document, document.copy()).forEach {
      assertFalse(it.toString().contains(TEST_KEY))
      assertTrue(it.toString().contains("<redacted>"))
    }
    assertFalse(ApplicationSettings.Translation().toString().contains(TEST_KEY))
  }

  @Test
  fun `saved key replacement and clearing persist across application sessions`() {
    val path = Files.createTempDirectory("coffee-gb-translation-settings").resolve("settings.properties")
    EmulatorProperties(path, debounceMillis = 60_000).use { properties ->
      properties.updateApplicationSettings { current ->
        current.copy(translation = ApplicationSettings.Translation(TEST_KEY))
      }
    }
    EmulatorProperties(path, debounceMillis = 60_000).use { properties ->
      assertEquals(TEST_KEY, properties.applicationSettings.translation.apiKey)
      properties.updateApplicationSettings { current ->
        current.copy(translation = ApplicationSettings.Translation("$TEST_KEY-replacement"))
      }
    }
    EmulatorProperties(path, debounceMillis = 60_000).use { properties ->
      assertEquals("$TEST_KEY-replacement", properties.applicationSettings.translation.apiKey)
      properties.updateApplicationSettings { current ->
        current.copy(translation = ApplicationSettings.Translation())
      }
    }
    EmulatorProperties(path, debounceMillis = 60_000).use { properties ->
      assertEquals("", properties.applicationSettings.translation.apiKey)
    }
    assertFalse(KEY in ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
    assertFalse(Files.readString(path).contains(TEST_KEY))
  }

  @Test
  fun `writes containing active or preserved keys restrict file permissions`() {
    val directory = Files.createTempDirectory("coffee-gb-private-translation-settings")
    assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
    val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    listOf(false, true).forEach { legacy ->
      val path = directory.resolve("settings-$legacy.properties")
      val source = if (legacy) {
        mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "11", KEY to TEST_KEY)
      } else {
        ApplicationSettingsCodec.encode(ApplicationSettingsDocument(ApplicationSettings()))
      }
      Files.write(path, ApplicationSettingsStore.encodeProperties(source))
      Files.setPosixFilePermissions(path, expected + PosixFilePermission.GROUP_READ + PosixFilePermission.OTHERS_READ)
      ApplicationSettingsStore(path, debounceMillis = 60_000).use { store ->
        if (!legacy) {
          store.update(store.current().copy(settings = ApplicationSettings(
              translation = ApplicationSettings.Translation(TEST_KEY))))
          store.flush()
        }
        assertEquals(expected, Files.getPosixFilePermissions(path))
        assertNull(store.consumeLoadWarning())
      }
      assertEquals(expected, Files.getPosixFilePermissions(path))
    }
  }

  @Test
  fun `credential permission failures leave the previous settings file untouched`() {
    val path = Files.createTempDirectory("coffee-gb-translation-write-failure").resolve("settings.properties")
    val original = ApplicationSettingsStore.encodeProperties(
        ApplicationSettingsCodec.encode(ApplicationSettingsDocument(ApplicationSettings())))
    Files.write(path, original)
    var rejectPrivateWrite = true
    val persistence = object : AtomicFileWriter() {
      override fun writeOwnerOnly(target: Path, intendedBytes: ByteArray) {
        if (rejectPrivateWrite) throw IOException("Unable to apply owner-only permissions")
        super.writeOwnerOnly(target, intendedBytes)
      }
    }
    ApplicationSettingsStore(path, persistence, debounceMillis = 60_000).use { store ->
      store.update(store.current().copy(settings = ApplicationSettings(
          translation = ApplicationSettings.Translation(TEST_KEY))))
      val failure = assertFailsWith<IOException> { store.flush() }
      assertFalse(failure.stackTraceToString().contains(TEST_KEY))
      assertTrue(original.contentEquals(Files.readAllBytes(path)))
      rejectPrivateWrite = false
    }
    val reloaded = ApplicationSettingsCodec.decode(
        ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
    assertEquals(TEST_KEY, reloaded.settings.translation.apiKey)
  }

  @Test
  fun `invalid stored key recovery does not reveal its value`() {
    val path = Files.createTempDirectory("coffee-gb-invalid-translation-settings").resolve("settings.properties")
    Files.write(path, ApplicationSettingsStore.encodeProperties(mapOf(
        ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "12",
        KEY to "$TEST_KEY\ninvalid",
    )))
    ApplicationSettingsStore(path, debounceMillis = 60_000).use { store ->
      assertEquals("", store.current().settings.translation.apiKey)
      val warning = store.consumeLoadWarning()
      assertEquals(ApplicationSettingsWarningKind.CORRUPT_FILE_RECOVERED, warning?.kind)
      assertFalse(warning.toString().contains(TEST_KEY))
    }
  }

  companion object {
    private const val KEY = ApplicationSettingsCodec.TRANSLATION_API_KEY
    private const val PROVIDER = ApplicationSettingsCodec.TRANSLATION_PROVIDER_KEY
    private const val TEST_KEY = "sk-proj-placeholder-test-key"
  }
}
