package eu.rekawek.coffeegb.controller.properties

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsDisplayTest {

  @Test
  fun `display model has legacy-compatible defaults and validates every numeric boundary`() {
    val defaults = ApplicationSettings.Display()
    assertEquals(ApplicationSettings.DisplayScalingMode.EXPLICIT, defaults.scalingMode)
    assertEquals(2, defaults.explicitScale)
    assertEquals(2, defaults.scale)
    assertEquals(0x000000, defaults.letterboxColor)
    assertFalse(defaults.fullscreen)

    ApplicationSettings.Display(explicitScale = 1, letterboxColor = 0x000000)
    ApplicationSettings.Display(explicitScale = 4, letterboxColor = 0xFFFFFF)
    ApplicationSettings.DisplayScalingMode.entries.forEach { mode ->
      ApplicationSettings.Display(scalingMode = mode)
    }

    listOf(0, 5).forEach { invalidScale ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.Display(explicitScale = invalidScale)
      }
    }
    listOf(-1, 0x1000000).forEach { invalidColor ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.Display(letterboxColor = invalidColor)
      }
    }
  }

  @Test
  fun `schema four display values round trip canonically and idempotently`() {
    val explicitScales =
        ApplicationSettings.MIN_EXPLICIT_DISPLAY_SCALE..
            ApplicationSettings.MAX_EXPLICIT_DISPLAY_SCALE
    ApplicationSettings.DisplayScalingMode.entries.forEach { scalingMode ->
      explicitScales.forEach { explicitScale ->
        listOf(0x000000, 0x203040, 0xFFFFFF).forEach { color ->
          val document =
              ApplicationSettingsDocument(
                  ApplicationSettings(
                      display =
                          ApplicationSettings.Display(
                              scalingMode = scalingMode,
                              explicitScale = explicitScale,
                              letterboxColor = color,
                              fullscreen = true,
                              grayscale = true,
                              blending = false,
                              colorCorrection = false,
                              rotation = ApplicationSettings.Rotation.DEG_90,
                              showSgbBorder = true,
                          )),
                  mapOf("plugin.display" to "preserve"),
              )

          val encoded = ApplicationSettingsCodec.encode(document)
          assertEquals("7", encoded[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
          assertEquals(
              scalingMode.name,
              encoded[ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY],
          )
          assertEquals(explicitScale.toString(), encoded["display.scale"])
          assertEquals(
              "%06X".format(Locale.ROOT, color),
              encoded[ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY],
          )
          assertEquals("true", encoded[ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY])

          val decoded = ApplicationSettingsCodec.decode(encoded)
          assertEquals(document, decoded)
          assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
        }
      }
    }
  }

  @Test
  fun `schemas zero through three migrate fixed scale and preserve future display fields`() {
    val futureValues =
        mapOf(
            ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY to "ASPECT_FIT",
            ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "ABCDEF",
            ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY to "true",
        )

    listOf(null, "1", "2", "3").forEach { sourceVersion ->
      val raw =
          buildMap {
            sourceVersion?.let { put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, it) }
            put("display.scale", "4")
            putAll(futureValues)
          }
      val migrated = ApplicationSettingsCodec.decode(raw)

      assertEquals(
          ApplicationSettings.DisplayScalingMode.EXPLICIT,
          migrated.settings.display.scalingMode,
      )
      assertEquals(4, migrated.settings.display.explicitScale)
      assertEquals(
          ApplicationSettings.DEFAULT_LETTERBOX_COLOR,
          migrated.settings.display.letterboxColor,
      )
      assertFalse(migrated.settings.display.fullscreen)
      assertEquals(futureValues, migrated.unknownProperties)

      val canonical = ApplicationSettingsCodec.encode(migrated)
      assertEquals("7", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
      assertEquals("EXPLICIT", canonical[ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY])
      assertEquals("4", canonical["display.scale"])
      assertEquals("000000", canonical[ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY])
      assertEquals("false", canonical[ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY])
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
  fun `schema three collision envelope retains future display values through schema four`() {
    val collisions =
        mapOf(
            ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY to "INTEGER_FIT",
            ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "123456",
            ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY to "true",
        )
    val raw =
        mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "3",
            "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                serializeCollisions(collisions),
        )

    val migrated = ApplicationSettingsCodec.decode(raw)
    assertEquals(ApplicationSettings.Display(), migrated.settings.display)
    assertEquals(collisions, migrated.unknownProperties)

    val canonical = ApplicationSettingsCodec.encode(migrated)
    assertEquals("EXPLICIT", canonical[ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY])
    assertEquals("000000", canonical[ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY])
    assertEquals("false", canonical[ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY])
    val decodedAgain = ApplicationSettingsCodec.decode(canonical)
    assertEquals(migrated, decodedAgain)
    assertEquals(canonical, ApplicationSettingsCodec.encode(decodedAgain))
  }

  @Test
  fun `schema four rejects noncanonical display values and accepts explicit three times`() {
    val validSchema = mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "4")
    val invalid =
        listOf(
            mapOf(ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY to "aspect_fit"),
            mapOf(ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY to "FIXED"),
            mapOf("display.scale" to "0"),
            mapOf("display.scale" to "5"),
            mapOf("display.scale" to "3.0"),
            mapOf(ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "#123456"),
            mapOf(ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "abcdef"),
            mapOf(ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "12345"),
            mapOf(ApplicationSettingsCodec.DISPLAY_LETTERBOX_COLOR_KEY to "1000000"),
            mapOf(ApplicationSettingsCodec.DISPLAY_FULLSCREEN_KEY to "yes"),
        )

    invalid.forEach { fields ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $fields") {
        ApplicationSettingsCodec.decode(validSchema + fields)
      }
    }

    val explicitThree =
        ApplicationSettingsCodec.decode(
            validSchema +
                mapOf(
                    ApplicationSettingsCodec.DISPLAY_SCALING_MODE_KEY to "EXPLICIT",
                    "display.scale" to "3",
                ))
    assertEquals(3, explicitThree.settings.display.explicitScale)
  }

  private fun serializeCollisions(values: Map<String, String>): String =
      buildString {
        values.toSortedMap().forEach { (key, value) ->
          append(key.length).append(':').append(key)
          append(value.length).append(':').append(value)
        }
      }
}
