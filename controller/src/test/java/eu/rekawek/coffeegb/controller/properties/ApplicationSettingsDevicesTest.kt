package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsDevicesTest {

  @Test
  fun `audio and gamepad tuning models enforce explicit defaults and bounds`() {
    assertEquals(
        ApplicationSettings.AudioOutputSelection.Default,
        ApplicationSettings.Audio().output,
    )
    assertEquals(ApplicationSettings.DEFAULT_AUDIO_VOLUME, ApplicationSettings.Audio().volume)
    assertEquals(ApplicationSettings.AudioLatency.BALANCED, ApplicationSettings.Audio().latency)
    ApplicationSettings.Audio(volume = ApplicationSettings.MIN_AUDIO_VOLUME)
    ApplicationSettings.Audio(volume = ApplicationSettings.MAX_AUDIO_VOLUME)
    ApplicationSettings.AudioOutputSelection.Device(audioId('a'))

    listOf(-1, 101).forEach { volume ->
      assertFailsWith<IllegalArgumentException> { ApplicationSettings.Audio(volume = volume) }
    }
    listOf(
            "default",
            "java-sound-" + "A".repeat(64),
            "java-sound-" + "a".repeat(63),
            "sdl-" + "a".repeat(64),
        )
        .forEach { invalid ->
          assertFailsWith<IllegalArgumentException> {
            ApplicationSettings.AudioOutputSelection.Device(invalid)
          }
        }

    ApplicationSettings.GamepadTuning(
        movementDeadZone = ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE,
        tiltDeadZone = ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE,
    )
    listOf(-1, 32_767).forEach { invalid ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.GamepadTuning(movementDeadZone = invalid)
      }
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.GamepadTuning(tiltDeadZone = invalid)
      }
    }

    val maximumProfiles =
        (0 until ApplicationSettings.MAX_GAMEPAD_TUNINGS).associate { index ->
          gamepadId(index) to ApplicationSettings.GamepadTuning()
        }
    ApplicationSettings.Input.defaults().copy(gamepadTunings = maximumProfiles)
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Input.defaults().copy(
          gamepadTunings =
              maximumProfiles + (gamepadId(ApplicationSettings.MAX_GAMEPAD_TUNINGS) to
                  ApplicationSettings.GamepadTuning()))
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettings.Input.defaults().copy(
          gamepadTunings = mapOf("controller-0" to ApplicationSettings.GamepadTuning()))
    }
  }

  @Test
  fun `schema three audio and per-device tuning round trip canonically and deterministically`() {
    val firstId = gamepadId('a')
    val secondId = gamepadId('b')
    val firstTuning =
        ApplicationSettings.GamepadTuning(
            movementDeadZone = 0,
            tiltDeadZone = 32_766,
            invertMovementX = true,
            invertTiltY = true,
        )
    val secondTuning =
        ApplicationSettings.GamepadTuning(
            movementDeadZone = 12_345,
            tiltDeadZone = 2_345,
            invertMovementY = true,
            invertTiltX = true,
        )
    val mutableTunings = linkedMapOf(secondId to secondTuning, firstId to firstTuning)
    val document =
        ApplicationSettingsDocument(
            ApplicationSettings(
                audio =
                    ApplicationSettings.Audio(
                        enabled = false,
                        output = ApplicationSettings.AudioOutputSelection.Device(audioId('c')),
                        volume = 37,
                        latency = ApplicationSettings.AudioLatency.LOW,
                    ),
                input =
                    ApplicationSettings.Input.defaults().copy(
                        gamepadTunings = mutableTunings)),
            mapOf("plugin.device-setting" to "preserved"),
        )
    mutableTunings.clear()

    val encoded = ApplicationSettingsCodec.encode(document)
    assertEquals(
        ApplicationSettings.CURRENT_SCHEMA_VERSION.toString(),
        encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY],
    )
    assertEquals(audioId('c'), encoded[ApplicationSettingsCodec.AUDIO_OUTPUT_KEY])
    assertEquals("37", encoded[ApplicationSettingsCodec.AUDIO_VOLUME_KEY])
    assertEquals("LOW", encoded[ApplicationSettingsCodec.AUDIO_LATENCY_KEY])
    assertEquals("0", encoded[tuningKey(firstId, "movementDeadZone")])
    assertEquals("32766", encoded[tuningKey(firstId, "tiltDeadZone")])
    assertEquals("true", encoded[tuningKey(firstId, "invertMovementX")])
    assertEquals("false", encoded[tuningKey(firstId, "invertMovementY")])
    assertEquals("false", encoded[tuningKey(firstId, "invertTiltX")])
    assertEquals("true", encoded[tuningKey(firstId, "invertTiltY")])
    assertEquals("12345", encoded[tuningKey(secondId, "movementDeadZone")])
    assertEquals("2345", encoded[tuningKey(secondId, "tiltDeadZone")])
    assertEquals("false", encoded[tuningKey(secondId, "invertMovementX")])
    assertEquals("true", encoded[tuningKey(secondId, "invertMovementY")])
    assertEquals("true", encoded[tuningKey(secondId, "invertTiltX")])
    assertEquals("false", encoded[tuningKey(secondId, "invertTiltY")])
    assertEquals(encoded.keys.sorted(), encoded.keys.toList())

    val decoded = ApplicationSettingsCodec.decode(encoded)
    assertEquals(document, decoded)
    assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
    assertFailsWith<UnsupportedOperationException> {
      (decoded.settings.input.gamepadTunings as MutableMap<String, *>).clear()
    }
  }

  @Test
  fun `schemas zero through two preserve future device fields without interpreting them`() {
    val stableId = gamepadId('d')
    val futureValues =
        mapOf(
            ApplicationSettingsCodec.AUDIO_OUTPUT_KEY to "plugin-output",
            ApplicationSettingsCodec.AUDIO_VOLUME_KEY to "999",
            ApplicationSettingsCodec.AUDIO_LATENCY_KEY to "TURBO",
            tuningKey(stableId, "movementDeadZone") to "32767",
            tuningKey(stableId, "invertMovementX") to "plugin-boolean",
        )

    listOf(null, "1", "2").forEach { sourceVersion ->
      val raw =
          buildMap {
            sourceVersion?.let {
              put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, it)
            }
            put("sound.enabled", "false")
            putAll(futureValues)
          }
      val migrated = ApplicationSettingsCodec.decode(raw)

      assertFalse(migrated.settings.audio.enabled)
      assertEquals(
          ApplicationSettings.AudioOutputSelection.Default,
          migrated.settings.audio.output,
      )
      assertEquals(ApplicationSettings.DEFAULT_AUDIO_VOLUME, migrated.settings.audio.volume)
      assertEquals(ApplicationSettings.AudioLatency.BALANCED, migrated.settings.audio.latency)
      assertTrue(migrated.settings.input.gamepadTunings.isEmpty())
      assertEquals(futureValues, migrated.unknownProperties)

      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals(
          ApplicationSettings.CURRENT_SCHEMA_VERSION.toString(),
          canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY],
      )
      assertEquals("default", canonical[ApplicationSettingsCodec.AUDIO_OUTPUT_KEY])
      assertEquals("100", canonical[ApplicationSettingsCodec.AUDIO_VOLUME_KEY])
      assertEquals("BALANCED", canonical[ApplicationSettingsCodec.AUDIO_LATENCY_KEY])
      assertFalse(tuningKey(stableId, "movementDeadZone") in canonical)
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
  fun `schema two collision envelope retains future device values through schema three`() {
    val stableId = gamepadId('e')
    val collisions =
        mapOf(
            ApplicationSettingsCodec.AUDIO_OUTPUT_KEY to audioId('e'),
            ApplicationSettingsCodec.AUDIO_VOLUME_KEY to "0",
            ApplicationSettingsCodec.AUDIO_LATENCY_KEY to "SAFE",
            tuningKey(stableId, "movementDeadZone") to "0",
            tuningKey(stableId, "invertTiltY") to "true",
        )
    val raw =
        mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "2",
            "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                serializeCollisions(collisions),
        )

    val migrated = ApplicationSettingsCodec.decode(raw)
    assertEquals(ApplicationSettings.Audio(), migrated.settings.audio)
    assertTrue(migrated.settings.input.gamepadTunings.isEmpty())
    assertEquals(collisions, migrated.unknownProperties)

    val canonical = ApplicationSettingsCodec.encode(migrated)
    assertEquals("default", canonical[ApplicationSettingsCodec.AUDIO_OUTPUT_KEY])
    assertFalse(tuningKey(stableId, "movementDeadZone") in canonical)
    val decodedAgain = ApplicationSettingsCodec.decode(canonical)
    assertEquals(migrated, decodedAgain)
    assertEquals(canonical, ApplicationSettingsCodec.encode(decodedAgain))
  }

  @Test
  fun `schema three strictly validates audio tuning fields and profile count`() {
    val stableId = gamepadId('f')
    val partial =
        ApplicationSettingsCodec.decode(
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "3",
                tuningKey(stableId, "invertTiltX") to "true",
            ))
    assertEquals(
        ApplicationSettings.GamepadTuning(invertTiltX = true),
        partial.settings.input.gamepadTunings[stableId],
    )
    assertEquals(
        ApplicationSettings.DEFAULT_AUDIO_VOLUME,
        partial.settings.audio.volume,
    )

    val invalid =
        listOf(
            mapOf(ApplicationSettingsCodec.AUDIO_OUTPUT_KEY to "java-sound-" + "F".repeat(64)),
            mapOf(ApplicationSettingsCodec.AUDIO_OUTPUT_KEY to "speaker"),
            mapOf(ApplicationSettingsCodec.AUDIO_VOLUME_KEY to "-1"),
            mapOf(ApplicationSettingsCodec.AUDIO_VOLUME_KEY to "101"),
            mapOf(ApplicationSettingsCodec.AUDIO_VOLUME_KEY to "loud"),
            mapOf(ApplicationSettingsCodec.AUDIO_LATENCY_KEY to "FAST"),
            mapOf(tuningKey(stableId, "movementDeadZone") to "-1"),
            mapOf(tuningKey(stableId, "movementDeadZone") to "32767"),
            mapOf(tuningKey(stableId, "tiltDeadZone") to "wide"),
            mapOf(tuningKey(stableId, "invertMovementY") to "yes"),
        )

    invalid.forEach { fields ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $fields") {
        ApplicationSettingsCodec.decode(
            fields + (ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "3"))
      }
    }

    val tooManyProfiles =
        buildMap {
          put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, "3")
          repeat(ApplicationSettings.MAX_GAMEPAD_TUNINGS + 1) { index ->
            put(tuningKey(gamepadId(index), "movementDeadZone"), "1")
          }
        }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettingsCodec.decode(tooManyProfiles)
    }
  }

  @Test
  fun `camera device model has an explicit bounded default`() {
    assertEquals(
        ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX,
        ApplicationSettings.Peripherals().cameraDeviceIndex,
    )
    ApplicationSettings.Peripherals(ApplicationSettings.MIN_CAMERA_DEVICE_INDEX)
    ApplicationSettings.Peripherals(ApplicationSettings.MAX_CAMERA_DEVICE_INDEX)
    listOf(
            ApplicationSettings.MIN_CAMERA_DEVICE_INDEX - 1,
            ApplicationSettings.MAX_CAMERA_DEVICE_INDEX + 1,
        )
        .forEach { invalid ->
          assertFailsWith<IllegalArgumentException> {
            ApplicationSettings.Peripherals(invalid)
          }
        }
  }

  @Test
  fun `schema seven camera choice round trips and rejects malformed indexes`() {
    val document =
        ApplicationSettingsDocument(
            ApplicationSettings(
                peripherals =
                    ApplicationSettings.Peripherals(
                        ApplicationSettings.MAX_CAMERA_DEVICE_INDEX)),
            mapOf("plugin.camera-filter" to "sepia"),
        )

    val encoded = ApplicationSettingsCodec.encode(document)

    assertEquals(
        ApplicationSettings.CURRENT_SCHEMA_VERSION.toString(),
        encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY],
    )
    assertEquals(
        ApplicationSettings.MAX_CAMERA_DEVICE_INDEX.toString(),
        encoded[ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY],
    )
    assertEquals(document, ApplicationSettingsCodec.decode(encoded))

    listOf(
            "camera",
            "-1",
            (ApplicationSettings.MAX_CAMERA_DEVICE_INDEX + 1).toString(),
            "2147483648",
        )
        .forEach { invalid ->
          assertFailsWith<IllegalArgumentException>("Expected rejection for $invalid") {
            ApplicationSettingsCodec.decode(
                mapOf(
                    ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "7",
                    ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY to invalid,
                ))
          }
        }
  }

  @Test
  fun `schemas zero through six preserve future camera choice without activating it`() {
    (0..6).forEach { sourceVersion ->
      val raw =
          buildMap {
            if (sourceVersion > 0) {
              put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, sourceVersion.toString())
            }
            put(ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY, "4")
          }

      val migrated = ApplicationSettingsCodec.decode(raw)

      assertEquals(
          ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX,
          migrated.settings.peripherals.cameraDeviceIndex,
      )
      assertEquals(
          "4",
          migrated.unknownProperties[ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY],
      )
      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("0", canonical[ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY])
      assertTrue(
          canonical.keys.any {
            it.startsWith(ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
          })
      assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
    }
  }

  @Test
  fun `schema six collision envelope retains future camera choice through schema seven`() {
    val collision = mapOf(ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY to "9")
    val migrated =
        ApplicationSettingsCodec.decode(
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "6",
                "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                    serializeCollisions(collision),
            ))

    assertEquals(ApplicationSettings.Peripherals(), migrated.settings.peripherals)
    assertEquals(collision, migrated.unknownProperties)
    val canonical = ApplicationSettingsCodec.encode(migrated)
    assertEquals("0", canonical[ApplicationSettingsCodec.CAMERA_DEVICE_INDEX_KEY])
    assertEquals(migrated, ApplicationSettingsCodec.decode(canonical))
  }

  @Test
  fun `controller facades expose and persist applied device snapshots`() {
    val path = Files.createTempDirectory("coffee-gb-devices").resolve("settings.properties")
    val stableId = gamepadId('1')
    val audio =
        ApplicationSettings.Audio(
            enabled = false,
            output = ApplicationSettings.AudioOutputSelection.Device(audioId('1')),
            volume = 65,
            latency = ApplicationSettings.AudioLatency.SAFE,
        )
    val tuning =
        ApplicationSettings.GamepadTuning(
            movementDeadZone = 1_024,
            tiltDeadZone = 512,
            invertMovementX = true,
        )

    EmulatorProperties(path, debounceMillis = 0).use { properties ->
      properties.updateApplicationSettings { current ->
        current.copy(
            audio = audio,
            input = current.input.copy(gamepadTunings = mapOf(stableId to tuning)),
            peripherals = ApplicationSettings.Peripherals(cameraDeviceIndex = 7),
        )
      }

      assertFalse(properties.sound.soundEnabled)
      assertEquals(audio.output, properties.sound.output)
      assertEquals(65, properties.sound.volume)
      assertEquals(ApplicationSettings.AudioLatency.SAFE, properties.sound.latency)
      assertEquals(mapOf(stableId to tuning), properties.gamepadTunings)
      assertEquals(tuning, properties.gamepadTuning(stableId))
      assertEquals(ApplicationSettings.GamepadTuning(), properties.gamepadTuning(gamepadId('2')))
      assertEquals(7, properties.applicationSettings.peripherals.cameraDeviceIndex)
      properties.flush()
    }

    val persisted =
        ApplicationSettingsCodec.decode(
            ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
    assertEquals(audio, persisted.settings.audio)
    assertEquals(mapOf(stableId to tuning), persisted.settings.input.gamepadTunings)
    assertEquals(7, persisted.settings.peripherals.cameraDeviceIndex)
  }

  private fun gamepadId(fill: Char): String = "sdl-" + fill.toString().repeat(64)

  private fun gamepadId(index: Int): String =
      "sdl-" + index.toString(16).padStart(64, '0')

  private fun audioId(fill: Char): String = "java-sound-" + fill.toString().repeat(64)

  private fun tuningKey(stableId: String, field: String): String =
      "${ApplicationSettingsCodec.GAMEPAD_TUNING_PREFIX}$stableId.$field"

  private fun serializeCollisions(collisions: Map<String, String>): String =
      buildString {
        collisions.toSortedMap().forEach { (key, value) ->
          append(key.length).append(':').append(key)
          append(value.length).append(':').append(value)
        }
      }
}
