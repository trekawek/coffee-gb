package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsSavesTest {

  @Test
  fun `save defaults preserve schemas zero through four behavior`() {
    val future =
        mapOf(
            ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to "/future/saves",
            "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0" to "/future/old",
            ApplicationSettingsCodec.REWIND_ENABLED_KEY to "false",
            ApplicationSettingsCodec.REWIND_SECONDS_KEY to "120",
            ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY to "256",
            ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY to "ON_CLOSE_AND_ROM_SWITCH",
            ApplicationSettingsCodec.RESUME_POLICY_KEY to "ALWAYS",
        )

    listOf(null, "1", "2", "3", "4").forEach { version ->
      val raw =
          buildMap {
            version?.let { put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, it) }
            put(ApplicationSettingsCodec.BATTERY_SAVES_KEY, "false")
            putAll(future)
          }
      val migrated = ApplicationSettingsCodec.decode(raw)

      assertEquals(
          ApplicationSettings.Saves(batterySavesEnabled = false),
          migrated.settings.saves,
          "schema ${version ?: 0}",
      )
      assertEquals(future, migrated.unknownProperties, "schema ${version ?: 0}")

      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("8", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
      assertEquals("false", canonical[ApplicationSettingsCodec.BATTERY_SAVES_KEY])
      assertEquals("true", canonical[ApplicationSettingsCodec.REWIND_ENABLED_KEY])
      assertEquals("30", canonical[ApplicationSettingsCodec.REWIND_SECONDS_KEY])
      assertEquals("64", canonical[ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY])
      assertEquals("DISABLED", canonical[ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY])
      assertEquals("ASK", canonical[ApplicationSettingsCodec.RESUME_POLICY_KEY])
      assertTrue(
          canonical.keys.any {
            it.startsWith(ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
          })

      val decodedAgain = ApplicationSettingsCodec.decode(canonical)
      assertEquals(migrated, decodedAgain)
      assertEquals(canonical, ApplicationSettingsCodec.encode(decodedAgain))
    }
  }

  @Test
  fun `schema five save values round trip canonically and defensively`() {
    val previous = mutableListOf(Path.of("/old/一"), Path.of("/old/two"))
    val document =
        ApplicationSettingsDocument(
            ApplicationSettings(
                saves =
                    ApplicationSettings.Saves(
                        directory = Path.of("/new/é"),
                        previousDirectories = previous,
                        batterySavesEnabled = false,
                        rewindEnabled = false,
                        rewindSeconds = 120,
                        rewindMemoryMiB = 256,
                        autosavePolicy =
                            ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                        resumePolicy = ApplicationSettings.ResumePolicy.ALWAYS,
                    )),
            mapOf("plugin.saves" to "preserved"),
        )
    previous.clear()

    val encoded = ApplicationSettingsCodec.encode(document)
    assertEquals("8", encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
    assertEquals(
        Path.of("/new/é").toString(),
        encoded[ApplicationSettingsCodec.SAVE_DIRECTORY_KEY],
    )
    assertEquals(
        Path.of("/old/一").toString(),
        encoded["${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0"],
    )
    assertEquals(
        Path.of("/old/two").toString(),
        encoded["${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}1"],
    )
    assertEquals("false", encoded[ApplicationSettingsCodec.BATTERY_SAVES_KEY])
    assertEquals("false", encoded[ApplicationSettingsCodec.REWIND_ENABLED_KEY])
    assertEquals("120", encoded[ApplicationSettingsCodec.REWIND_SECONDS_KEY])
    assertEquals("256", encoded[ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY])
    assertEquals(
        "ON_CLOSE_AND_ROM_SWITCH",
        encoded[ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY],
    )
    assertEquals("ALWAYS", encoded[ApplicationSettingsCodec.RESUME_POLICY_KEY])

    val decoded = ApplicationSettingsCodec.decode(encoded)
    assertEquals(document, decoded)
    assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
    assertFailsWith<UnsupportedOperationException> {
      (decoded.settings.saves.previousDirectories as MutableList<Path>).clear()
    }
  }

  @Test
  fun `nullable save directory is omitted while policy defaults remain explicit`() {
    val encoded =
        ApplicationSettingsCodec.encode(
            ApplicationSettingsDocument(ApplicationSettings()))

    assertFalse(ApplicationSettingsCodec.SAVE_DIRECTORY_KEY in encoded)
    assertFalse(
        encoded.keys.any { it.startsWith(ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX) })
    assertEquals("true", encoded[ApplicationSettingsCodec.BATTERY_SAVES_KEY])
    assertEquals("true", encoded[ApplicationSettingsCodec.REWIND_ENABLED_KEY])
    assertEquals("30", encoded[ApplicationSettingsCodec.REWIND_SECONDS_KEY])
    assertEquals("64", encoded[ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY])
    assertEquals("DISABLED", encoded[ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY])
    assertEquals("ASK", encoded[ApplicationSettingsCodec.RESUME_POLICY_KEY])
  }

  @Test
  fun `save model enforces bounded unique history and rewind duration`() {
    val filesystemRoot =
        Path.of("").toAbsolutePath().root
            ?: throw AssertionError("Default filesystem has no root")
    ApplicationSettings.Saves(rewindSeconds = ApplicationSettings.MIN_REWIND_SECONDS)
    ApplicationSettings.Saves(rewindSeconds = ApplicationSettings.MAX_REWIND_SECONDS)
    ApplicationSettings.Saves(rewindMemoryMiB = ApplicationSettings.MIN_REWIND_MEMORY_MIB)
    ApplicationSettings.Saves(rewindMemoryMiB = ApplicationSettings.MAX_REWIND_MEMORY_MIB)

    listOf(4, 121).forEach { invalid ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.Saves(rewindSeconds = invalid)
      }
    }
    listOf(7, 513).forEach { invalid ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.Saves(rewindMemoryMiB = invalid)
      }
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(
          previousDirectories =
              (0..ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES)
                  .map { Path.of("/old/$it") })
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(
          previousDirectories = listOf(Path.of("/old"), Path.of("/old")))
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(
          previousDirectories = listOf(Path.of("/old/../same"), Path.of("/same")))
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(
          directory = Path.of("/active"),
          previousDirectories = listOf(Path.of("/active")),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(directory = Path.of(""))
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(directory = filesystemRoot)
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Saves(previousDirectories = listOf(filesystemRoot))
    }
  }

  @Test
  fun `schema five rejects malformed policies ranges paths and indexed history`() {
    val schema = mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "5")
    val filesystemRoot =
        Path.of("").toAbsolutePath().root
            ?: throw AssertionError("Default filesystem has no root")
    val invalid =
        listOf(
            mapOf(ApplicationSettingsCodec.REWIND_ENABLED_KEY to "yes"),
            mapOf(ApplicationSettingsCodec.REWIND_SECONDS_KEY to "4"),
            mapOf(ApplicationSettingsCodec.REWIND_SECONDS_KEY to "121"),
            mapOf(ApplicationSettingsCodec.REWIND_SECONDS_KEY to "thirty"),
            mapOf(ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY to "7"),
            mapOf(ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY to "513"),
            mapOf(ApplicationSettingsCodec.REWIND_MEMORY_MIB_KEY to "large"),
            mapOf(ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY to "on_close"),
            mapOf(ApplicationSettingsCodec.AUTOSAVE_POLICY_KEY to "ON_SWITCH"),
            mapOf(ApplicationSettingsCodec.RESUME_POLICY_KEY to "ask"),
            mapOf(ApplicationSettingsCodec.RESUME_POLICY_KEY to "SOMETIMES"),
            mapOf(ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to "bad\u0000path"),
            mapOf(ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to ""),
            mapOf(ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to filesystemRoot.toString()),
            mapOf(
                "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0" to
                    filesystemRoot.toString()),
            mapOf("${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}1" to "/old/one"),
            mapOf(
                ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to "/same",
                "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0" to "/same",
            ),
        )

    invalid.forEach { fields ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $fields") {
        ApplicationSettingsCodec.decode(schema + fields)
      }
    }
  }

  @Test
  fun `noncanonical and excess history keys remain unknown`() {
    val excess =
        "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}" +
            ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES
    val noncanonical = "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}01"
    val document =
        ApplicationSettingsCodec.decode(
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "5",
                "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0" to "/old/zero",
                excess to "/plugin/excess",
                noncanonical to "/plugin/noncanonical",
            ))

    assertEquals(listOf(Path.of("/old/zero")), document.settings.saves.previousDirectories)
    assertEquals("/plugin/excess", document.unknownProperties[excess])
    assertEquals("/plugin/noncanonical", document.unknownProperties[noncanonical])
    assertEquals(
        document,
        ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(document)),
    )
  }

  @Test
  fun `schema four collision envelope retains future save values through schema five`() {
    val collisions =
        mapOf(
            ApplicationSettingsCodec.SAVE_DIRECTORY_KEY to "/plugin/root",
            ApplicationSettingsCodec.REWIND_SECONDS_KEY to "plugin-duration",
            "${ApplicationSettingsCodec.PREVIOUS_SAVE_DIRECTORY_PREFIX}0" to "/plugin/old",
        )
    val raw =
        mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "4",
            "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                serializeCollisions(collisions),
        )

    val migrated = ApplicationSettingsCodec.decode(raw)
    assertEquals(ApplicationSettings.Saves(), migrated.settings.saves)
    assertEquals(collisions, migrated.unknownProperties)

    val canonical = ApplicationSettingsCodec.encode(migrated)
    val decodedAgain = ApplicationSettingsCodec.decode(canonical)
    assertEquals(migrated, decodedAgain)
    assertEquals(canonical, ApplicationSettingsCodec.encode(decodedAgain))
  }

  @Test
  fun `live saves facade exposes the atomically applied schema five model`() {
    testEmulatorProperties().use { properties ->
      val saves =
          ApplicationSettings.Saves(
              directory = Path.of("/active"),
              previousDirectories = listOf(Path.of("/old")),
              batterySavesEnabled = false,
              rewindEnabled = false,
              rewindSeconds = 75,
              rewindMemoryMiB = 128,
              autosavePolicy = ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
              resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
          )
      properties.updateApplicationSettings { it.copy(saves = saves) }

      assertEquals(saves.directory, properties.saves.directory)
      assertEquals(saves.previousDirectories, properties.saves.previousDirectories)
      assertFalse(properties.saves.batterySavesEnabled)
      assertFalse(properties.saves.rewindEnabled)
      assertEquals(75, properties.saves.rewindSeconds)
      assertEquals(128, properties.saves.rewindMemoryMiB)
      assertEquals(saves.autosavePolicy, properties.saves.autosavePolicy)
      assertEquals(saves.resumePolicy, properties.saves.resumePolicy)
    }
  }

  private fun serializeCollisions(values: Map<String, String>): String =
      buildString {
        values.toSortedMap().forEach { (key, value) ->
          append(key.length).append(':').append(key)
          append(value.length).append(':').append(value)
        }
      }
}
