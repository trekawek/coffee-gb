package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ApplicationSettingsStoreTest {

  @Test
  fun `all legacy keys migrate to a canonical idempotent schema while unknowns survive`() {
    val raw =
        ApplicationSettingsStore.decodeProperties(
            checkNotNull(javaClass.getResourceAsStream("/settings/legacy-v0-all.properties"))
                .use { it.readBytes() })

    val migrated = ApplicationSettingsCodec.migrateLegacy(raw)
    val settings = migrated.settings
    assertEquals(Path.of("/legacy/roms"), settings.general.romDirectory)
    assertEquals(
        listOf(
            Path.of("/legacy/roms/first.gb"),
            Path.of("/legacy/roms/second.gbc"),
        ),
        settings.general.recentRoms,
    )
    assertEquals(10, settings.general.recentFileCapacity)
    assertEquals(
        ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING,
        settings.general.romChangeConfirmationPolicy,
    )
    assertEquals(4, settings.display.scale)
    assertTrue(settings.display.grayscale)
    assertFalse(settings.display.blending)
    assertFalse(settings.display.colorCorrection)
    assertEquals(ApplicationSettings.Rotation.DEG_270, settings.display.rotation)
    assertTrue(settings.display.showSgbBorder)
    assertFalse(settings.audio.enabled)
    assertFalse(settings.saves.batterySavesEnabled)
    assertEquals(
        HardwareProfileRegistry.DMG,
        settings.advanced.dmgGamesProfile.explicitProfileOrNull(),
    )
    assertEquals(
        HardwareProfileRegistry.CGB0,
        settings.advanced.cgbGamesProfile.explicitProfileOrNull(),
    )
    assertEquals(BootstrapMode.FAST_FORWARD, settings.advanced.bootstrapMode)
    assertEquals(Path.of("/legacy/roms/action-replay.gbc"), settings.advanced.datelSlotRom)
    assertEquals("07 ま Magnesium Powered", settings.advanced.fullChangerCharacter)
    assertEquals(
        "VK_Q",
        settings.input.keyboard[
                ControllerProperties.PlayerButton(0, Button.A)]
            ?.propertyName,
    )
    assertEquals(
        "VK_W",
        settings.input.keyboard[
                ControllerProperties.PlayerButton(1, Button.B)]
            ?.propertyName,
    )
    assertEquals(
        ApplicationSettings.GamepadSelection.Disabled,
        settings.input.gamepads[0],
    )
    assertEquals(
        ApplicationSettings.GamepadSelection.Device("sdl-" + "a".repeat(64)),
        settings.input.gamepads[1],
    )
    assertEquals(
        mapOf(
            "plugin.setting" to "preserve me",
            "rom.recent.future" to "preserve future recent metadata",
        ),
        migrated.unknownProperties,
    )

    val canonical = ApplicationSettingsCodec.encode(migrated)
    assertEquals(expectedCanonicalFixture(), canonical)
    assertFalse("btn_a" in canonical)
    assertEquals("dmg", canonical["system.dmgGames"])
    assertEquals("cgb0", canonical["system.cgbGames"])

    val decodedAgain = ApplicationSettingsCodec.decode(canonical)
    assertEquals(migrated, decodedAgain)
    assertEquals(canonical, ApplicationSettingsCodec.encode(decodedAgain))

    val encodedBytes = ApplicationSettingsStore.encodeProperties(canonical)
    val encodedText = encodedBytes.toString(StandardCharsets.US_ASCII)
    assertFalse(encodedText.contains('#'), "canonical files must not contain timestamp comments")
    assertTrue(encodedText.contains("fullchanger.character=07\\ \\u307E\\ Magnesium\\ Powered\n"))
    assertEquals(canonical, ApplicationSettingsStore.decodeProperties(encodedBytes))
    assertEquals(encodedText.lines().filter(String::isNotEmpty).sorted(), encodedText.lines().filter(String::isNotEmpty))
  }

  @Test
  fun `noncanonical recent keys survive migration as unknown properties`() {
    val migrated =
        ApplicationSettingsCodec.migrateLegacy(
            mapOf(
                "rom.recent.01" to "/legacy/roms/noncanonical.gb",
                "rom.recent.1" to "/legacy/roms/canonical.gbc",
            ))

    assertEquals(
        listOf(Path.of("/legacy/roms/canonical.gbc")),
        migrated.settings.general.recentRoms,
    )
    assertEquals(
        "/legacy/roms/noncanonical.gb",
        migrated.unknownProperties["rom.recent.01"],
    )
    assertEquals(
        migrated,
        ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(migrated)),
    )
  }

  @Test
  fun `settings collections are immutable snapshots`() {
    val recent = mutableListOf(Path.of("/roms/original.gb"))
    val keyboard = ApplicationSettings.Input.defaults().keyboard.toMutableMap()
    val gamepads = ApplicationSettings.Input.defaults().gamepads.toMutableMap()
    val unknown = mutableMapOf("plugin.value" to "original")
    val document =
        ApplicationSettingsDocument(
            ApplicationSettings(
                general = ApplicationSettings.General(recentRoms = recent),
                input = ApplicationSettings.Input(keyboard, gamepads),
            ),
            unknown,
        )

    recent.clear()
    keyboard.clear()
    gamepads.clear()
    unknown.clear()

    assertEquals(listOf(Path.of("/roms/original.gb")), document.settings.general.recentRoms)
    assertEquals(ApplicationSettings.Input.defaults(), document.settings.input)
    assertEquals(mapOf("plugin.value" to "original"), document.unknownProperties)
    assertFailsWith<UnsupportedOperationException> {
      (document.settings.general.recentRoms as MutableList<Path>).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (document.settings.input.keyboard as MutableMap<ControllerProperties.PlayerButton, *>).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (document.settings.input.gamepads as MutableMap<Int, *>).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (document.unknownProperties as MutableMap<String, String>).clear()
    }
  }

  @Test
  fun `legacy unescaped text uses the historical platform charset before canonical migration`() {
    val windows1252 = Charset.forName("windows-1252")
    val legacyText =
        "rom.directory=C:/Roms/Pokémon\n" +
            "fullchanger.character=Crème brûlée €\n" +
            "plugin.label=café\n"
    val raw =
        ApplicationSettingsStore.decodeProperties(
            legacyText.toByteArray(windows1252),
            windows1252,
        )

    assertEquals("C:/Roms/Pokémon", raw["rom.directory"])
    assertEquals("Crème brûlée €", raw["fullchanger.character"])
    assertEquals("café", raw["plugin.label"])
    val migrated = ApplicationSettingsCodec.migrateLegacy(raw)
    assertEquals(Path.of("C:/Roms/Pokémon"), migrated.settings.general.romDirectory)
    assertEquals("Crème brûlée €", migrated.settings.advanced.fullChangerCharacter)
    assertEquals("café", migrated.unknownProperties["plugin.label"])

    val canonical = ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(migrated))
    val canonicalText = canonical.toString(StandardCharsets.US_ASCII)
    assertTrue(canonicalText.contains("Pok\\u00E9mon"))
    assertTrue(canonicalText.contains("Cr\\u00E8me\\ br\\u00FBl\\u00E9e\\ \\u20AC"))
    assertEquals(migrated, ApplicationSettingsCodec.decode(ApplicationSettingsStore.decodeProperties(canonical)))
  }

  @Test
  fun `recognized values are parsed strictly`() {
    val invalid =
        listOf(
            mapOf("settings.schemaVersion" to "one"),
            mapOf("settings.schemaVersion" to "-1"),
            mapOf("settings.schemaVersion" to "0"),
            mapOf("display.scale" to "large"),
            mapOf("display.scale" to "3"),
            mapOf("display.rotation" to "45"),
            mapOf("display.grayscale" to "yes"),
            mapOf("display.blending" to "1"),
            mapOf("display.colorCorrection" to "off"),
            mapOf("display.showSgbBorder" to "enabled"),
            mapOf("sound.enabled" to "sometimes"),
            mapOf("saves.batteryEnabled" to "y"),
            mapOf(
                "settings.schemaVersion" to "2",
                "general.recentFileCapacity" to "-1",
            ),
            mapOf(
                "settings.schemaVersion" to "2",
                "general.recentFileCapacity" to "51",
            ),
            mapOf(
                "settings.schemaVersion" to "2",
                "general.romChangeConfirmationPolicy" to "when_running",
            ),
            mapOf("system.bootstrapMode" to "fast_forward"),
            mapOf("system.dmgGames" to "MGB"),
            mapOf("system.cgbGames" to "ordinal-1"),
            mapOf("input.p1.btn_a" to "VK_NOT_A_KEY"),
            mapOf("input.p5.btn_a" to "VK_Q"),
            mapOf("input.p1.gamepad" to "sdl-" + "A".repeat(64)),
            mapOf("input.p1.unknown" to "VK_Q"),
            mapOf("datel.slot.rom" to "bad\u0000path"),
        )

    invalid.forEach { raw ->
      assertFailsWith<IllegalArgumentException>("Expected rejection for $raw") {
        ApplicationSettingsCodec.decode(raw)
      }
    }
  }

  @Test
  fun `document bounds reject excessive keys names and values`() {
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettingsCodec.decode((0..2_048).associate { "unknown.$it" to "value" })
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettingsCodec.decode(mapOf("x".repeat(257) to "value"))
    }
    assertFailsWith<IllegalArgumentException> {
      ApplicationSettingsCodec.decode(mapOf("unknown" to "x".repeat(65_537)))
    }
  }

  @Test
  fun `aggregate encoded size is rejected before update mutates or schedules a write`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-size")
    val writer = CountingWriter()
    val store =
        ApplicationSettingsStore(
            directory.resolve("settings.properties"),
            writer,
            debounceMillis = 60_000,
        )
    val before = store.current()
    val oversized =
        before.copy(
            unknownProperties =
                (0 until 17).associate { index ->
                  "large.unknown.$index" to "x".repeat(65_536)
                })

    val failure = runCatching { store.update(oversized) }.exceptionOrNull()
    assertIs<IllegalArgumentException>(failure)
    assertEquals(before, store.current())
    assertEquals(0, writer.writes.get())
    store.close()
    assertEquals(0, writer.writes.get())
  }

  @Test
  fun `corrupt file is preserved byte for byte and warning is consumed once`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-corrupt")
    val path = directory.resolve("settings.properties")
    val corrupt = "display.scale=2\nbroken=\\u12G4\n".toByteArray(StandardCharsets.US_ASCII)
    Files.write(path, corrupt)
    val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)

    ApplicationSettingsStore(path, clock = clock, debounceMillis = 60_000).use { store ->
      assertEquals(ApplicationSettings(), store.current().settings)
      assertFalse(store.isReadOnly())
      val warning = assertNotNull(store.consumeLoadWarning())
      assertEquals(ApplicationSettingsWarningKind.CORRUPT_FILE_RECOVERED, warning.kind)
      val preserved = assertNotNull(warning.preservedFile)
      assertEquals(
          directory.resolve("settings.properties.corrupt-20260102-030405"),
          preserved,
      )
      assertTrue(corrupt.contentEquals(Files.readAllBytes(preserved)))
      assertFalse(Files.exists(path))
      assertNull(store.consumeLoadWarning())

      store.update(
          store.current().withSettings {
            copy(display = display.copy(scale = 4))
          })
      store.flush()
      assertEquals(4, readDocument(path).settings.display.scale)
    }
  }

  @Test
  fun `corrupt preservation failure leaves original untouched and disables persistence`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-corrupt-preservation-failure")
    val path = directory.resolve("settings.properties")
    val corrupt = "broken=\\u12G4\n".toByteArray(StandardCharsets.US_ASCII)
    Files.write(path, corrupt)
    val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)
    val backupBase = "settings.properties.corrupt-20260102-030405"
    repeat(1_000) { suffix ->
      val suffixText = if (suffix == 0) "" else "-$suffix"
      Files.createFile(directory.resolve("$backupBase$suffixText"))
    }

    ApplicationSettingsStore(path, clock = clock, debounceMillis = 0).use { store ->
      assertTrue(store.isReadOnly())
      assertEquals(ApplicationSettings(), store.current().settings)
      val warning = assertNotNull(store.consumeLoadWarning())
      assertEquals(
          ApplicationSettingsWarningKind.CORRUPT_FILE_PRESERVATION_FAILED,
          warning.kind,
      )
      assertNull(warning.preservedFile)
      assertNull(store.consumeLoadWarning())
      store.flush()
    }

    assertTrue(corrupt.contentEquals(Files.readAllBytes(path)))
    assertTrue(Files.isRegularFile(path))
  }

  @Test
  fun `future schema remains untouched and read only`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-future")
    val path = directory.resolve("settings.properties")
    val original =
        "settings.schemaVersion=999999999999999999999\nfuture.setting=do-not-touch\n"
            .toByteArray(StandardCharsets.US_ASCII)
    Files.write(path, original)

    EmulatorProperties(path, debounceMillis = 0).use { properties ->
      assertTrue(properties.isReadOnly())
      assertEquals(ApplicationSettings(), properties.applicationSettings)
      val warning = assertNotNull(properties.consumeLoadWarning())
      assertEquals(ApplicationSettingsWarningKind.FUTURE_SCHEMA, warning.kind)
      assertNull(properties.consumeLoadWarning())

      properties.setProperty(EmulatorProperties.Key.DisplayScale, "4")
      properties.setProperty(EmulatorProperties.Key.SoundEnabled, "false")
      assertEquals(4, properties.display.scale)
      assertFalse(properties.sound.soundEnabled)
      assertFailsWith<IllegalArgumentException> {
        properties.setProperty(EmulatorProperties.Key.DisplayScale, "3")
      }
      assertEquals(4, properties.display.scale)

      // Persistence is disabled, but validated menu/facade changes remain useful in this session.
      properties.flush()
    }

    assertTrue(original.contentEquals(Files.readAllBytes(path)))
    Files.list(directory).use { entries ->
      assertEquals(listOf(path), entries.toList())
    }

    ApplicationSettingsStore(path, debounceMillis = 0).use { store ->
      assertTrue(store.isReadOnly())
      assertFailsWith<IllegalStateException> {
        store.update(ApplicationSettingsDocument(ApplicationSettings()))
      }
    }

    assertTrue(original.contentEquals(Files.readAllBytes(path)))
  }

  @Test
  fun `schema one file is rewritten once as canonical schema two`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-schema-one")
    val path = directory.resolve("settings.properties")
    val schemaOne =
        mapOf(
            ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "1",
            "rom.recent.0" to "/roms/active.gb",
            "${ApplicationSettingsCodec.RECENT_ROM_PREFIX}0" to "/plugin/future.gb",
            ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY to "plugin-capacity",
            "plugin.setting" to "preserve me",
        )
    Files.write(path, ApplicationSettingsStore.encodeProperties(schemaOne))

    val canonicalBytes =
        ApplicationSettingsStore(path, debounceMillis = 60_000).use { store ->
          assertFalse(store.isReadOnly())
          assertNull(store.consumeLoadWarning())
          assertEquals(
              listOf(Path.of("/roms/active.gb")),
              store.current().settings.general.recentRoms,
          )
          assertEquals(
              "/plugin/future.gb",
              store.current().unknownProperties["${ApplicationSettingsCodec.RECENT_ROM_PREFIX}0"],
          )
          assertEquals(
              "plugin-capacity",
              store.current().unknownProperties[ApplicationSettingsCodec.RECENT_FILE_CAPACITY_KEY],
          )

          Files.readAllBytes(path).also { bytes ->
            val canonical = ApplicationSettingsStore.decodeProperties(bytes)
            assertEquals("2", canonical[ApplicationSettingsCodec.SCHEMA_VERSION_KEY])
            assertEquals(store.current(), ApplicationSettingsCodec.decode(canonical))
            assertEquals(canonical, ApplicationSettingsCodec.encode(store.current()))
          }
        }

    ApplicationSettingsStore(path, debounceMillis = 60_000).use { store ->
      assertEquals("2", store.current().settings.schemaVersion.toString())
    }
    assertTrue(canonicalBytes.contentEquals(Files.readAllBytes(path)))
  }

  @Test
  fun `gamepad selections have stable canonical encode decode forms`() {
    val device = ApplicationSettings.GamepadSelection.Device("sdl-" + "b".repeat(64))
    val cases =
        listOf(
            ApplicationSettings.GamepadSelection.Disabled to "none",
            ApplicationSettings.GamepadSelection.Auto to "auto",
            device to device.stableId,
        )

    cases.forEach { (selection, encodedValue) ->
      val input = ApplicationSettings.Input.defaults().copy(gamepads = mapOf(0 to selection))
      val document = ApplicationSettingsDocument(ApplicationSettings(input = input))
      val encoded = ApplicationSettingsCodec.encode(document)
      assertEquals(encodedValue, encoded["input.p1.gamepad"])
      val decoded = ApplicationSettingsCodec.decode(encoded)
      assertEquals(input, decoded.settings.input)
      assertEquals(encoded, ApplicationSettingsCodec.encode(decoded))
    }

    val secondaryDisabled =
        ApplicationSettingsCodec.decode(mapOf("input.p2.gamepad" to "none"))
    assertEquals(
        mapOf(0 to ApplicationSettings.GamepadSelection.Auto),
        secondaryDisabled.settings.input.gamepads,
    )
    assertFalse("input.p2.gamepad" in ApplicationSettingsCodec.encode(secondaryDisabled))

    val legacyMissingBindings = ApplicationSettingsCodec.decode(emptyMap()).settings.input
    assertEquals(8, legacyMissingBindings.keyboard.size)
    assertEquals(ApplicationSettings.GamepadSelection.Auto, legacyMissingBindings.gamepads[0])

    val schemaOneMissingBindings =
        ApplicationSettingsCodec.decode(
                mapOf(ApplicationSettingsCodec.SCHEMA_VERSION_KEY to "1"))
            .settings
            .input
    assertTrue(schemaOneMissingBindings.keyboard.isEmpty())
    assertEquals(
        ApplicationSettings.GamepadSelection.Disabled,
        schemaOneMissingBindings.gamepads[0],
    )
  }

  @Test
  fun `generic read failure leaves target untouched and disables persistence`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-read-failure")
    val path = directory.resolve("settings.properties")
    Files.createDirectory(path)

    ApplicationSettingsStore(path, debounceMillis = 0).use { store ->
      assertTrue(store.isReadOnly())
      assertEquals(ApplicationSettings(), store.current().settings)
      val warning = assertNotNull(store.consumeLoadWarning())
      assertEquals(ApplicationSettingsWarningKind.READ_FAILED, warning.kind)
      assertNull(store.consumeLoadWarning())
    }

    assertTrue(Files.isDirectory(path))
    Files.list(directory).use { entries -> assertEquals(listOf(path), entries.toList()) }
  }

  @Test
  fun `CGB0 compatibility selection applies only to persisted auto profile`() {
    val romPath =
        listOf(
                Path.of("../core/src/test/resources/roms/cgb-acid-hell/cgb-acid-hell.gbc"),
                Path.of("core/src/test/resources/roms/cgb-acid-hell/cgb-acid-hell.gbc"),
            )
            .first(Files::isRegularFile)
    val rom = Rom(romPath.toFile())
    val directory = Files.createTempDirectory("coffee-gb-settings-cgb0-selection")

    val autoPath = directory.resolve("auto.properties")
    writeDocument(autoPath, ApplicationSettingsDocument(ApplicationSettings()))
    EmulatorProperties(autoPath, debounceMillis = 60_000).use { properties ->
      assertIs<ApplicationSettings.ProfileSelection.Auto>(
          properties.applicationSettings.advanced.cgbGamesProfile)
      assertEquals(HardwareProfileRegistry.CGB0, Controller.getHardwareProfile(properties.system, rom))
    }

    val explicitCgbPath = directory.resolve("explicit-cgb.properties")
    writeDocument(
        explicitCgbPath,
        ApplicationSettingsDocument(
            ApplicationSettings(
                advanced =
                    ApplicationSettings.Advanced(
                        cgbGamesProfile =
                            ApplicationSettings.ProfileSelection.Explicit(
                                HardwareProfileRegistry.CGB),
                    ),
            ),
        ),
    )
    EmulatorProperties(explicitCgbPath, debounceMillis = 60_000).use { properties ->
      assertEquals(HardwareProfileRegistry.CGB, Controller.getHardwareProfile(properties.system, rom))
    }

    val overridePath = directory.resolve("override-cgb.properties")
    writeDocument(overridePath, ApplicationSettingsDocument(ApplicationSettings()))
    EmulatorProperties(
            overridePath,
            ApplicationSettingsOverrides(hardwareProfile = HardwareProfileRegistry.CGB),
            debounceMillis = 60_000,
        )
        .use { properties ->
          assertEquals(
              HardwareProfileRegistry.CGB,
              Controller.getHardwareProfile(properties.system, rom),
          )
        }
  }

  @Test
  fun `rapid updates debounce and flush persists only the latest revision`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-debounce")
    val path = directory.resolve("settings.properties")
    val writer = CountingWriter()
    val store = ApplicationSettingsStore(path, writer, debounceMillis = 60_000)

    store.update(store.current().withSettings { copy(display = display.copy(scale = 1)) })
    store.update(store.current().withSettings { copy(display = display.copy(scale = 2)) })
    store.update(store.current().withSettings { copy(display = display.copy(scale = 4)) })
    assertEquals(4, store.current().settings.display.scale)
    assertEquals(0, writer.writes.get())

    store.flush()
    assertEquals(1, writer.writes.get())
    assertEquals(4, readDocument(path).settings.display.scale)
    store.close()
    assertEquals(1, writer.writes.get())
  }

  @Test
  fun `elapsed debounce automatically coalesces rapid updates into the latest write`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-elapsed-debounce")
    val path = directory.resolve("settings.properties")
    val writer = SignallingWriter()
    val store = ApplicationSettingsStore(path, writer, debounceMillis = 1_000)
    try {
      store.update(store.current().withSettings { copy(display = display.copy(scale = 1)) })
      assertFalse(writer.written.await(100, TimeUnit.MILLISECONDS))

      store.update(store.current().withSettings { copy(display = display.copy(scale = 4)) })
      assertFalse(writer.written.await(250, TimeUnit.MILLISECONDS))
      assertEquals(0, writer.writes.get())

      assertTrue(writer.written.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(1, writer.writes.get())
      assertEquals(4, readDocument(path).settings.display.scale)
    } finally {
      store.close()
    }
    assertEquals(1, writer.writes.get())
  }

  @Test
  fun `failed replacement leaves old file and close retries dirty revision`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-failure")
    val path = directory.resolve("settings.properties")
    val originalDocument = ApplicationSettingsDocument(ApplicationSettings())
    val originalBytes =
        ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(originalDocument))
    Files.write(path, originalBytes)
    val writer = FailOnceWriter()
    val store = ApplicationSettingsStore(path, writer, debounceMillis = 60_000)
    store.update(store.current().withSettings { copy(display = display.copy(scale = 4)) })

    val failure = assertFailsWith<IOException> { store.flush() }
    assertTrue(failure.message!!.contains("injected"))
    assertNotNull(store.lastWriteFailure())
    assertTrue(originalBytes.contentEquals(Files.readAllBytes(path)))
    assertEquals(4, store.current().settings.display.scale)

    store.close()
    assertNull(store.lastWriteFailure())
    assertEquals(2, writer.writes.get())
    assertEquals(4, readDocument(path).settings.display.scale)
  }

  @Test
  fun `close racing a blocked background write commits the newest revision`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-close-race")
    val path = directory.resolve("settings.properties")
    val writer = BlockingFirstWriter()
    val store = ApplicationSettingsStore(path, writer, debounceMillis = 0)
    store.update(store.current().withSettings { copy(display = display.copy(scale = 1)) })
    assertTrue(writer.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    store.update(store.current().withSettings { copy(display = display.copy(scale = 4)) })
    val closeFailure = AtomicReference<Throwable?>()
    val closeStarted = CountDownLatch(1)
    val closer =
        thread(name = "settings-test-closer") {
          closeStarted.countDown()
          try {
            store.close()
          } catch (failure: Throwable) {
            closeFailure.set(failure)
          }
        }
    assertTrue(closeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    awaitBlocked(closer)
    writer.release.countDown()
    closer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

    assertFalse(closer.isAlive, "close must finish after the blocked write is released")
    assertNull(closeFailure.get())
    assertEquals(2, writer.writes.get())
    assertEquals(4, readDocument(path).settings.display.scale)
  }

  @Test
  fun `close times out and interrupts a writer that never releases`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-close-timeout")
    val writer = NeverReleasedWriter()
    val store =
        ApplicationSettingsStore(
            directory.resolve("settings.properties"),
            writer,
            debounceMillis = 0,
            closeTimeoutMillis = 100,
        )
    store.update(store.current().withSettings { copy(display = display.copy(scale = 4)) })
    assertTrue(writer.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    val started = System.nanoTime()
    val failure = assertFailsWith<IllegalStateException> { store.close() }
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    assertTrue(failure.message!!.contains("Unable to close"))
    assertTrue(elapsedMillis < 2_000, "close took $elapsedMillis ms")
    assertTrue(writer.interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    assertFailsWith<IllegalStateException> {
      store.update(store.current())
    }
  }

  @Test
  fun `transient overrides win in controller configuration and are never persisted`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-overrides")
    val path = directory.resolve("settings.properties")
    val persisted =
        ApplicationSettingsDocument(
            ApplicationSettings(
                saves = ApplicationSettings.Saves(batterySavesEnabled = false),
                advanced =
                    ApplicationSettings.Advanced(
                        dmgGamesProfile =
                            ApplicationSettings.ProfileSelection.Explicit(
                                HardwareProfileRegistry.MGB),
                        cgbGamesProfile =
                            ApplicationSettings.ProfileSelection.Explicit(
                                HardwareProfileRegistry.CGB0),
                        bootstrapMode = BootstrapMode.NORMAL,
                    ),
            ))
    val original = ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(persisted))
    Files.write(path, original)
    val overrides =
        ApplicationSettingsOverrides(
            hardwareProfile = HardwareProfileRegistry.DMG,
            bootstrapMode = BootstrapMode.FAST_FORWARD,
            batterySavesEnabled = true,
        )

    EmulatorProperties(path, overrides, debounceMillis = 60_000).use { properties ->
      assertEquals(HardwareProfileRegistry.MGB, properties.system.dmgGamesProfile)
      assertEquals(BootstrapMode.FAST_FORWARD, properties.system.bootstrapMode)
      assertTrue(properties.saves.batterySavesEnabled)
      assertEquals(BootstrapMode.NORMAL, properties.applicationSettings.advanced.bootstrapMode)
      assertFalse(properties.applicationSettings.saves.batterySavesEnabled)

      val rom = Rom(Path.of("src/test/resources/roms/cpu_instrs.gb").toFile())
      val configuration = Controller.createGameboyConfig(properties, rom)
      assertEquals(HardwareProfileRegistry.DMG, configuration.hardwareProfile)
      assertEquals(BootstrapMode.FAST_FORWARD, configuration.bootstrapMode)
      assertTrue(configuration.isSupportBatterySave)
    }

    assertTrue(original.contentEquals(Files.readAllBytes(path)))
    val reloaded = readDocument(path).settings
    assertEquals(HardwareProfileRegistry.MGB, reloaded.advanced.dmgGamesProfile.explicitProfileOrNull())
    assertEquals(BootstrapMode.NORMAL, reloaded.advanced.bootstrapMode)
    assertFalse(reloaded.saves.batterySavesEnabled)
  }

  @Test
  fun `effective bootstrap and profile are validated across persisted and CLI sources`() {
    val directory = Files.createTempDirectory("coffee-gb-settings-bootstrap-profile")
    val romPath =
        listOf(
                Path.of("../core/src/test/resources/roms/mooneye/manual-only/sprite_priority.gb"),
                Path.of("core/src/test/resources/roms/mooneye/manual-only/sprite_priority.gb"),
            )
            .first(Files::isRegularFile)
    val rom = Rom(romPath.toFile())

    val persistedBootstrapPath = directory.resolve("persisted-bootstrap.properties")
    writeDocument(
        persistedBootstrapPath,
        ApplicationSettingsDocument(
            ApplicationSettings(
                advanced =
                    ApplicationSettings.Advanced(bootstrapMode = BootstrapMode.NORMAL))))
    EmulatorProperties(
            persistedBootstrapPath,
            ApplicationSettingsOverrides(hardwareProfile = HardwareProfileRegistry.MGB),
            debounceMillis = 60_000,
        )
        .use { properties ->
          val failure =
              assertFailsWith<IllegalArgumentException> {
                Controller.createGameboyConfig(properties, rom)
              }
          assertTrue(failure.message!!.contains("mgb"))
          assertTrue(failure.message!!.contains("no bundled boot ROM"))
        }

    val persistedProfilePath = directory.resolve("persisted-profile.properties")
    writeDocument(
        persistedProfilePath,
        ApplicationSettingsDocument(
            ApplicationSettings(
                advanced =
                    ApplicationSettings.Advanced(
                        dmgGamesProfile =
                            ApplicationSettings.ProfileSelection.Explicit(
                                HardwareProfileRegistry.MGB)))))
    EmulatorProperties(
            persistedProfilePath,
            ApplicationSettingsOverrides(bootstrapMode = BootstrapMode.NORMAL),
            debounceMillis = 60_000,
        )
        .use { properties ->
          val failure =
              assertFailsWith<IllegalArgumentException> {
                Controller.createGameboyConfig(properties, rom)
              }
          assertTrue(failure.message!!.contains("mgb"))
          assertTrue(failure.message!!.contains("no bundled boot ROM"))
        }
  }

  private fun expectedCanonicalFixture(): Map<String, String> =
      mapOf(
              "datel.slot.rom" to "/legacy/roms/action-replay.gbc",
              "display.blending" to "false",
              "display.colorCorrection" to "false",
              "display.grayscale" to "true",
              "display.rotation" to "270",
              "display.scale" to "4",
              "display.showSgbBorder" to "true",
              "fullchanger.character" to "07 ま Magnesium Powered",
              "general.recentFileCapacity" to "10",
              "general.romChangeConfirmationPolicy" to "WHEN_RUNNING",
              "input.p1.btn_a" to "VK_Q",
              "input.p1.btn_b" to "VK_X",
              "input.p1.btn_down" to "VK_DOWN",
              "input.p1.btn_left" to "VK_LEFT",
              "input.p1.btn_right" to "VK_RIGHT",
              "input.p1.btn_select" to "VK_SHIFT",
              "input.p1.btn_start" to "VK_ENTER",
              "input.p1.btn_up" to "VK_UP",
              "input.p1.gamepad" to "none",
              "input.p2.btn_b" to "VK_W",
              "input.p2.gamepad" to "sdl-" + "a".repeat(64),
              "plugin.setting" to "preserve me",
              "rom.directory" to "/legacy/roms",
              "general.recent.0" to "/legacy/roms/first.gb",
              "general.recent.1" to "/legacy/roms/second.gbc",
              "rom.recent.future" to "preserve future recent metadata",
              "saves.batteryEnabled" to "false",
              "settings.schemaVersion" to "2",
              "sound.enabled" to "false",
              "system.bootstrapMode" to "FAST_FORWARD",
              "system.cgbGames" to "cgb0",
              "system.dmgGames" to "dmg",
          )
          .toSortedMap()

  private fun readDocument(path: Path): ApplicationSettingsDocument =
      ApplicationSettingsCodec.decode(
          ApplicationSettingsStore.decodeProperties(Files.readAllBytes(path)))

  private fun writeDocument(path: Path, document: ApplicationSettingsDocument) {
    Files.write(
        path,
        ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(document)),
    )
  }

  private fun ApplicationSettingsDocument.withSettings(
      update: ApplicationSettings.() -> ApplicationSettings
  ): ApplicationSettingsDocument = copy(settings = settings.update())

  private fun awaitBlocked(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    while (thread.state != Thread.State.WAITING &&
        thread.state != Thread.State.TIMED_WAITING &&
        thread.state != Thread.State.BLOCKED) {
      if (!thread.isAlive || System.nanoTime() >= deadline) {
        throw AssertionError("close did not block behind the in-flight settings write")
      }
      Thread.yield()
    }
  }

  private open class CountingWriter : AtomicFileWriter() {
    val writes = AtomicInteger()

    override fun write(target: Path, intendedBytes: ByteArray) {
      writes.incrementAndGet()
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class FailOnceWriter : CountingWriter() {
    override fun write(target: Path, intendedBytes: ByteArray) {
      if (writes.incrementAndGet() == 1) {
        throw IOException("injected settings replacement failure")
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class SignallingWriter : CountingWriter() {
    val written = CountDownLatch(1)

    override fun write(target: Path, intendedBytes: ByteArray) {
      super.write(target, intendedBytes)
      written.countDown()
    }
  }

  private class BlockingFirstWriter : AtomicFileWriter() {
    val writes = AtomicInteger()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun write(target: Path, intendedBytes: ByteArray) {
      if (writes.incrementAndGet() == 1) {
        started.countDown()
        try {
          if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("timed out waiting to release blocked settings write")
          }
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("blocked settings write was interrupted", interrupted)
        }
      }
      AtomicFileWriter.system().write(target, intendedBytes)
    }
  }

  private class NeverReleasedWriter : AtomicFileWriter() {
    val started = CountDownLatch(1)
    val interrupted = CountDownLatch(1)

    override fun write(target: Path, intendedBytes: ByteArray) {
      started.countDown()
      try {
        CountDownLatch(1).await()
      } catch (caught: InterruptedException) {
        interrupted.countDown()
        Thread.currentThread().interrupt()
        throw IOException("injected never-released settings writer was interrupted", caught)
      }
    }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 10L
  }
}
