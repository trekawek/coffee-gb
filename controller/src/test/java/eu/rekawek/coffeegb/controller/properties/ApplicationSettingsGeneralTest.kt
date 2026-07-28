package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsGeneralTest {

  @Test
  fun `general defaults are bounded and confirmation semantics are explicit`() {
    val general = ApplicationSettings.General()

    assertEquals(10, ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY)
    assertEquals(0, ApplicationSettings.MIN_RECENT_FILE_CAPACITY)
    assertEquals(50, ApplicationSettings.MAX_RECENT_FILE_CAPACITY)
    assertEquals(ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY, general.recentFileCapacity)
    assertEquals(
        ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING,
        general.romChangeConfirmationPolicy,
    )

    assertTrue(ApplicationSettings.RomChangeConfirmationPolicy.ALWAYS.shouldConfirm(false))
    assertTrue(ApplicationSettings.RomChangeConfirmationPolicy.ALWAYS.shouldConfirm(true))
    assertFalse(
        ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING.shouldConfirm(false))
    assertTrue(ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING.shouldConfirm(true))
    assertFalse(ApplicationSettings.RomChangeConfirmationPolicy.NEVER.shouldConfirm(false))
    assertFalse(ApplicationSettings.RomChangeConfirmationPolicy.NEVER.shouldConfirm(true))
  }

  @Test
  fun `recent capacity accepts both safe boundaries and rejects values outside them`() {
    ApplicationSettings.General(recentFileCapacity = 0)
    ApplicationSettings.General(recentFileCapacity = 50)

    listOf(-1, 51).forEach { invalid ->
      val failure =
          assertFailsWith<IllegalArgumentException> {
            ApplicationSettings.General(recentFileCapacity = invalid)
          }
      assertTrue(failure.message!!.contains("between 0 and 50"))
    }
  }

  @Test
  fun `recent history cannot exceed its configured capacity`() {
    val roms = (0..2).map { Path.of("/roms/$it.gb") }

    val failure =
        assertFailsWith<IllegalArgumentException> {
          ApplicationSettings.General(recentRoms = roms, recentFileCapacity = 2)
        }

    assertTrue(failure.message!!.contains("3 entries"))
    assertTrue(failure.message!!.contains("capacity is 2"))
  }

  @Test
  fun `captured key codes resolve to stable persisted VK names`() {
    val ordinary = ApplicationSettings.KeyboardKey.fromKeyCode(KeyEvent.VK_A)
    assertEquals("VK_A", ordinary.propertyName)
    assertEquals(KeyEvent.VK_A, ordinary.code)

    val aliased = ApplicationSettings.KeyboardKey.fromKeyCode(KeyEvent.VK_SEPARATOR)
    assertEquals("VK_SEPARATER", aliased.propertyName)
    assertEquals(KeyEvent.VK_SEPARATOR, aliased.code)
  }

  @Test
  fun `undefined and unknown captured key codes are rejected`() {
    listOf(KeyEvent.VK_UNDEFINED, Int.MAX_VALUE).forEach { invalid ->
      assertFailsWith<IllegalArgumentException> {
        ApplicationSettings.KeyboardKey.fromKeyCode(invalid)
      }
    }
  }

  @Test
  fun `legacy and early schema-one documents receive stable general defaults`() {
    listOf(
            mapOf("plugin.legacy" to "preserved"),
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "1",
                "plugin.schema1" to "preserved",
            ),
        )
        .forEach { raw ->
          val document = ApplicationSettingsCodec.decode(raw)

          assertEquals(
              ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY,
              document.settings.general.recentFileCapacity,
          )
          assertEquals(
              ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING,
              document.settings.general.romChangeConfirmationPolicy,
          )
          val encoded = ApplicationSettingsCodec.encode(document)
          assertEquals(
              "10",
              encoded[ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY],
          )
          assertEquals(
              "WHEN_RUNNING",
              encoded[ApplicationSettingsCodec.ROM_CHANGE_CONFIRMATION_POLICY_KEY],
          )
          raw.filterKeys { it.startsWith("plugin.") }.forEach { (key, value) ->
            assertEquals(value, encoded[key])
          }
        }
  }

  @Test
  fun `all general policy values and capacity boundaries round trip canonically`() {
    val unknown = mapOf("plugin.general" to "preserve me")

    ApplicationSettings.RomChangeConfirmationPolicy.entries.forEach { policy ->
      listOf(0, 10, 50).forEach { capacity ->
        val recent = (0 until capacity.coerceAtMost(3)).map { Path.of("/roms/$it.gb") }
        val document =
            ApplicationSettingsDocument(
                ApplicationSettings(
                    general =
                        ApplicationSettings.General(
                            romDirectory = Path.of("/roms"),
                            recentRoms = recent,
                            recentFileCapacity = capacity,
                            romChangeConfirmationPolicy = policy,
                        )),
                unknown,
            )

        val encoded = ApplicationSettingsCodec.encode(document)
        val decoded = ApplicationSettingsCodec.decode(encoded)

        assertEquals(document, decoded)
        assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
      }
    }
  }

  @Test
  fun `invalid persisted capacities and policies are rejected instead of reinterpreted`() {
    listOf(
            mapOf(
                ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "-1"),
            mapOf(ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "51"),
            mapOf(ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "ten"),
            mapOf(ApplicationSettingsCodec.ROM_CHANGE_CONFIRMATION_POLICY_KEY to "when_running"),
            mapOf(ApplicationSettingsCodec.ROM_CHANGE_CONFIRMATION_POLICY_KEY to "SOMETIMES"),
        )
        .forEach { raw ->
          assertFailsWith<IllegalArgumentException>("Expected rejection for $raw") {
            ApplicationSettingsCodec.decode(
                raw + (ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "2"))
          }
        }
  }

  @Test
  fun `codec caps indexed history and does not consume unrelated future keys`() {
    val document =
        ApplicationSettingsCodec.decode(
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "2",
                ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "2",
                "rom.recent.0" to "/roms/first.gb",
                "rom.recent.1" to "/roms/second.gb",
                "rom.recent.2" to "/roms/excess.gb",
                "rom.recent.future" to "preserve me",
            ))

    assertEquals(
        listOf(Path.of("/roms/first.gb"), Path.of("/roms/second.gb")),
        document.settings.general.recentRoms,
    )
    assertEquals("preserve me", document.unknownProperties["rom.recent.future"])
    val encoded = ApplicationSettingsCodec.encode(document)
    assertFalse("rom.recent.2" in encoded)
    assertEquals("preserve me", encoded["rom.recent.future"])
  }

  @Test
  fun `documents predating configurable capacity preserve higher numeric recent keys`() {
    listOf(
            mapOf(
                "rom.recent.0" to "/roms/known.gb",
                "rom.recent.10" to "/plugin/legacy-value",
            ),
            mapOf(
                ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "1",
                "rom.recent.0" to "/roms/known.gb",
                "rom.recent.49" to "/plugin/early-schema-value",
            ),
        )
        .forEach { raw ->
          val firstDocument = ApplicationSettingsCodec.decode(raw)
          val unknownKey = raw.keys.single { it.startsWith("rom.recent.") && it != "rom.recent.0" }
          val firstEncoded = ApplicationSettingsCodec.encode(firstDocument)
          val secondDocument = ApplicationSettingsCodec.decode(firstEncoded)
          val secondEncoded = ApplicationSettingsCodec.encode(secondDocument)

          assertEquals(raw[unknownKey], firstDocument.unknownProperties[unknownKey])
          assertEquals(raw[unknownKey], firstEncoded[unknownKey])
          assertEquals(raw[unknownKey], secondDocument.unknownProperties[unknownKey])
          assertEquals(firstEncoded, secondEncoded)

          val raisedCapacity =
              secondDocument.copy(
                  settings =
                      secondDocument.settings.copy(
                          general =
                              secondDocument.settings.general.copy(
                                  recentFileCapacity =
                                      ApplicationSettings.MAX_RECENT_FILE_CAPACITY,
                              )))
          val afterCapacityIncrease =
              ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(raisedCapacity))
          assertEquals(raw[unknownKey], afterCapacityIncrease.unknownProperties[unknownKey])
          assertFalse(
              afterCapacityIncrease.settings.general.recentRoms.any {
                it.toString() == raw[unknownKey]
              })
        }
  }

  @Test
  fun `active history can claim a previously unknown numeric slot without losing its value`() {
    val migrated =
        ApplicationSettingsCodec.decode(
            mapOf(
                "rom.recent.10" to "/plugin/legacy-value",
            ))
    val activeRecent = (0..10).map { Path.of("/roms/$it.gb") }
    val expanded =
        migrated.copy(
            settings =
                migrated.settings.copy(
                    general =
                        migrated.settings.general.copy(
                            recentRoms = activeRecent,
                            recentFileCapacity = 11,
                        )))

    val encoded = ApplicationSettingsCodec.encode(expanded)
    val decoded = ApplicationSettingsCodec.decode(encoded)

    assertEquals("/plugin/legacy-value", encoded["rom.recent.10"])
    assertEquals("/roms/10.gb", encoded["${ApplicationSettingsCodec.RECENT_ROM_PREFIX}10"])
    assertEquals(activeRecent, decoded.settings.general.recentRoms)
    assertEquals("/plugin/legacy-value", decoded.unknownProperties["rom.recent.10"])
  }

  @Test
  fun `schema two escapes older unknown keys that collide with its namespace`() {
    val raw =
        mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "1",
            "rom.recent.0" to "/roms/active.gb",
            "${ApplicationSettingsCodec.RECENT_ROM_PREFIX}0" to "/plugin/future-value",
            ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "plugin-capacity",
            "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                "plugin-metadata",
        )

    val migrated = ApplicationSettingsCodec.decode(raw)
    val encoded = ApplicationSettingsCodec.encode(migrated)
    val decoded = ApplicationSettingsCodec.decode(encoded)

    assertEquals(listOf(Path.of("/roms/active.gb")), decoded.settings.general.recentRoms)
    assertEquals(
        ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY,
        decoded.settings.general.recentFileCapacity,
    )
    assertEquals(
        "/plugin/future-value",
        decoded.unknownProperties["${ApplicationSettingsCodec.RECENT_ROM_PREFIX}0"],
    )
    assertEquals(
        "plugin-capacity",
        decoded.unknownProperties[ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY],
    )
    assertEquals(
        "plugin-metadata",
        decoded.unknownProperties[
            "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0"],
    )
    assertEquals(migrated, decoded)
    assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
  }

  @Test
  fun `schema two rejects collision metadata for an unreserved property`() {
    val failure =
        assertFailsWith<IllegalArgumentException> {
          ApplicationSettingsCodec.decode(
              mapOf(
                  ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "2",
                  "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0" to
                      "8:plugin.x6:hidden",
                  "plugin.x" to "visible",
              ))
        }

    assertTrue(failure.message!!.contains("does not collide with a reserved key"))
  }

  @Test
  fun `schema two rejects malformed collision metadata records`() {
    val prefix = ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX
    val malformed =
        listOf(
            mapOf("${prefix}0" to ""),
            mapOf("${prefix}1" to "7:input.x1:a"),
            mapOf("${prefix}00" to "7:input.x1:a"),
            mapOf("${prefix}0" to "7:input.x"),
            mapOf("${prefix}0" to "7:input.x1:a7:input.x1:b"),
        )

    malformed.forEach { metadata ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $metadata") {
        ApplicationSettingsCodec.decode(
            mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "2") + metadata)
      }
    }
  }

  @Test
  fun `schema two rejects collision metadata that cannot be re-encoded within its bounds`() {
    val raw =
        buildMap {
          put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, "2")
          repeat(30) { index ->
            put(
                "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}$index",
                "x".repeat(65_536),
            )
          }
        }

    val failure =
        assertFailsWith<IllegalArgumentException> { ApplicationSettingsCodec.decode(raw) }

    assertTrue(failure.message!!.contains("bounded metadata capacity"))
  }

  @Test
  fun `schema two collision metadata cannot bypass the logical property limit`() {
    val serializedCollisions =
        buildString {
          repeat(100) { index ->
            val key = "input.plugin.$index"
            append(key.length).append(':').append(key).append("1:x")
          }
        }
    val raw =
        buildMap {
          put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, "2")
          repeat(1_950) { index -> put("plugin.$index", "value") }
          put(
              "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}0",
              serializedCollisions,
          )
        }

    val failure =
        assertFailsWith<IllegalArgumentException> { ApplicationSettingsCodec.decode(raw) }

    assertTrue(failure.message!!.contains("logical properties"))
  }

  @Test
  fun `canonical default properties count toward the logical property limit`() {
    val raw =
        buildMap {
          put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, "2")
          repeat(2_040) { index -> put("plugin.$index", "value") }
        }

    val failure =
        assertFailsWith<IllegalArgumentException> { ApplicationSettingsCodec.decode(raw) }

    assertTrue(failure.message!!.contains("logical properties"))
  }

  @Test
  fun `collision chunk expansion cannot exceed the physical property limit`() {
    val collisionKey = "input.plugin"
    val collisionValue = "x".repeat(65_536)
    val serialized =
        "${collisionKey.length}:$collisionKey${collisionValue.length}:$collisionValue"
    val raw =
        buildMap {
          put(ApplicationSettingsCodec.SCHEMA_VERSION_KEY, "2")
          repeat(2_031) { index -> put("plugin.$index", "value") }
          serialized.chunked(60_000).forEachIndexed { index, chunk ->
            put(
                "${ApplicationSettingsCodec.PRESERVED_UNKNOWN_COLLISIONS_PREFIX}$index",
                chunk,
            )
          }
        }

    val failure =
        assertFailsWith<IllegalArgumentException> { ApplicationSettingsCodec.decode(raw) }

    assertTrue(failure.message!!.contains("more than 2048 properties"))
  }

  @Test
  fun `public general facade trims history and capacity zero disables new entries`() {
    val path = Files.createTempDirectory("coffee-gb-general").resolve("settings.properties")

    EmulatorProperties(path, debounceMillis = 0).use { properties ->
      properties.recentFileCapacity = 12
      repeat(12) { properties.recentRoms.addRom("/roms/$it.gb") }
      assertEquals(12, properties.recentRoms.getRoms().size)

      properties.recentFileCapacity = 5
      assertEquals(5, properties.recentFileCapacity)
      assertEquals(
          listOf("/roms/11.gb", "/roms/10.gb", "/roms/9.gb", "/roms/8.gb", "/roms/7.gb"),
          properties.recentRoms.getRoms(),
      )

      properties.recentFileCapacity = 0
      properties.recentRoms.addRom("/roms/ignored.gb")
      assertEquals(emptyList(), properties.recentRoms.getRoms())
      properties.flush()
    }

    val persisted =
        ApplicationSettingsCodec.decode(
            ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
    assertEquals(0, persisted.settings.general.recentFileCapacity)
    assertEquals(emptyList(), persisted.settings.general.recentRoms)
  }

  @Test
  fun `atomic public update preserves unknown keys and live input getters follow Apply`() {
    val directory = Files.createTempDirectory("coffee-gb-general-apply")
    val path = directory.resolve("settings.properties")
    val initial =
        ApplicationSettingsDocument(
            ApplicationSettings(),
            mapOf("plugin.preference" to "untouched"),
        )
    Files.write(
        path,
        ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(initial)),
    )

    EmulatorProperties(path, debounceMillis = 0).use { properties ->
      val keyboard = properties.applicationSettings.input.keyboard.toMutableMap()
      keyboard[ControllerProperties.PlayerButton(0, Button.A)] =
          ApplicationSettings.KeyboardKey.parse("VK_Q", "P1 A")
      properties.updateApplicationSettings { current ->
        current.copy(
            general =
                current.general.copy(
                    recentFileCapacity = 25,
                    romChangeConfirmationPolicy =
                        ApplicationSettings.RomChangeConfirmationPolicy.NEVER,
                ),
            input = current.input.copy(keyboard = keyboard),
        )
      }

      assertEquals(25, properties.recentFileCapacity)
      assertEquals(
          ApplicationSettings.RomChangeConfirmationPolicy.NEVER,
          properties.romChangeConfirmationPolicy,
      )
      assertEquals(
          Button.A,
          properties.controllerMapping[KeyEvent.VK_Q],
      )
      properties.flush()
    }

    val persisted =
        ApplicationSettingsCodec.decode(
            ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))
    assertEquals("untouched", persisted.unknownProperties["plugin.preference"])
    assertEquals(25, persisted.settings.general.recentFileCapacity)
    assertEquals(
        ApplicationSettings.RomChangeConfirmationPolicy.NEVER,
        persisted.settings.general.romChangeConfirmationPolicy,
    )
  }

  @Test
  fun `concurrent public transforms serialize around reading and committing latest settings`() {
    val path = Files.createTempDirectory("coffee-gb-general-concurrent").resolve("settings.properties")
    val firstTransformEntered = CountDownLatch(1)
    val releaseFirstTransform = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()

    EmulatorProperties(path, debounceMillis = 0).use { properties ->
      val first =
          thread(name = "settings-first") {
            runCatching {
                  properties.updateApplicationSettings { current ->
                    firstTransformEntered.countDown()
                    check(releaseFirstTransform.await(5, TimeUnit.SECONDS))
                    current.copy(
                        general = current.general.copy(recentFileCapacity = 25))
                  }
                }
                .onFailure(failure::set)
          }
      assertTrue(firstTransformEntered.await(5, TimeUnit.SECONDS))

      val second =
          thread(name = "settings-second") {
            runCatching {
                  properties.updateApplicationSettings { current ->
                    current.copy(
                        general =
                            current.general.copy(
                                romChangeConfirmationPolicy =
                                    ApplicationSettings.RomChangeConfirmationPolicy.NEVER))
                  }
                }
                .onFailure { failure.compareAndSet(null, it) }
          }

      try {
        awaitState(second, Thread.State.BLOCKED)
      } finally {
        releaseFirstTransform.countDown()
        first.join(5_000)
        second.join(5_000)
      }

      failure.get()?.let { throw AssertionError("Concurrent settings transform failed", it) }
      assertFalse(first.isAlive)
      assertFalse(second.isAlive)
      assertEquals(25, properties.recentFileCapacity)
      assertEquals(
          ApplicationSettings.RomChangeConfirmationPolicy.NEVER,
          properties.romChangeConfirmationPolicy,
      )
    }
  }

  private fun awaitState(thread: Thread, expected: Thread.State) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (thread.isAlive && thread.state != expected && System.nanoTime() < deadline) {
      Thread.yield()
    }
    assertEquals(expected, thread.state, "Thread did not serialize behind the settings update lock")
  }
}
