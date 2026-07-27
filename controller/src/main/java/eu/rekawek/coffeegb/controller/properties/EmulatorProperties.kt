package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.util.Properties

/**
 * Compatibility facade for existing desktop/controller call sites. The authoritative state is the
 * immutable [ApplicationSettings] document owned by [ApplicationSettingsStore]; the package-visible
 * [properties] view remains only for source-compatible tests and legacy adapters.
 */
class EmulatorProperties
internal constructor(
    val overrides: ApplicationSettingsOverrides,
    internal val settingsStore: ApplicationSettingsStore,
) : AutoCloseable {

  constructor() :
      this(
          ApplicationSettingsOverrides(),
          ApplicationSettingsStore(ApplicationSettingsStore.defaultPath()),
      )

  constructor(profileOverride: HardwareProfile?) :
      this(
          ApplicationSettingsOverrides(hardwareProfile = profileOverride),
          ApplicationSettingsStore(ApplicationSettingsStore.defaultPath()),
      )

  constructor(overrides: ApplicationSettingsOverrides) :
      this(overrides, ApplicationSettingsStore(ApplicationSettingsStore.defaultPath()))

  internal constructor(
      settingsPath: Path,
      overrides: ApplicationSettingsOverrides = ApplicationSettingsOverrides(),
      persistence: AtomicFileWriter = AtomicFileWriter.system(),
      debounceMillis: Long = ApplicationSettingsStore.DEFAULT_DEBOUNCE_MILLIS,
      clock: Clock = Clock.systemUTC(),
  ) : this(
      overrides,
      ApplicationSettingsStore(settingsPath, persistence, debounceMillis, clock),
  )

  internal val properties = Properties()

  val profileOverride: HardwareProfile?
    get() = overrides.hardwareProfile

  val recentRoms = RecentRoms(this)

  val display = DisplayProperties(this)

  val sound = SoundProperties(this)

  val system = SystemProperties(this)

  val saves = SavesProperties(this)

  val playerInputMapping: ControllerProperties.PlayerMapping

  /** Shared live-input service copied into each active machine configuration. */
  val playerInputSource = PlayerInputHub()

  val controllerMapping: Map<Int, eu.rekawek.coffeegb.core.joypad.Button>

  init {
    replaceCompatibilityProperties(settingsStore.current())
    playerInputMapping = applicationSettings.input.toPlayerMapping()
    controllerMapping = playerInputMapping.legacyPrimaryKeyboard()
  }

  val applicationSettings: ApplicationSettings
    get() = currentDocument().settings

  fun getProperty(key: Key, defaultValue: String? = null): String? =
      properties.getProperty(key.propertyName, defaultValue)

  fun setProperty(key: Key, value: String) {
    updateRaw { it[key.propertyName] = value }
  }

  fun hasProperty(key: Key): Boolean = properties.containsKey(key.propertyName)

  fun clearProperty(key: Key) {
    updateRaw { it.remove(key.propertyName) }
  }

  internal fun updateSettings(update: (ApplicationSettings) -> ApplicationSettings) {
    val current = currentDocument()
    val updated = ApplicationSettingsDocument(update(current.settings), current.unknownProperties)
    commitDocument(updated)
  }

  internal fun saveProperties() {
    commitDocument(currentDocument())
  }

  fun consumeLoadWarning(): ApplicationSettingsLoadWarning? = settingsStore.consumeLoadWarning()

  fun isReadOnly(): Boolean = settingsStore.isReadOnly()

  @Throws(IOException::class)
  fun flush() = settingsStore.flush()

  override fun close() = settingsStore.close()

  private fun updateRaw(update: (MutableMap<String, String>) -> Unit) {
    val raw = compatibilityPropertiesMap().toMutableMap()
    update(raw)
    commitDocument(ApplicationSettingsCodec.decode(raw))
  }

  private fun currentDocument(): ApplicationSettingsDocument =
      ApplicationSettingsCodec.decode(compatibilityPropertiesMap())

  /**
   * A future-schema or unpreservable file disables persistence, not the current UI session. Keep a
   * canonical in-memory document so menu changes remain useful without ever touching that file.
   */
  private fun commitDocument(updated: ApplicationSettingsDocument) {
    val canonical =
        ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(updated)).also {
          ApplicationSettingsStore.encodeProperties(ApplicationSettingsCodec.encode(it))
        }
    if (settingsStore.isReadOnly()) {
      replaceCompatibilityProperties(canonical)
    } else {
      settingsStore.update(canonical)
      replaceCompatibilityProperties(settingsStore.current())
    }
  }

  private fun compatibilityPropertiesMap(): Map<String, String> =
      synchronized(properties) {
        properties.stringPropertyNames().associateWith(properties::getProperty)
      }

  private fun replaceCompatibilityProperties(document: ApplicationSettingsDocument) {
    val encoded = ApplicationSettingsCodec.encode(document)
    synchronized(properties) {
      properties.clear()
      encoded.forEach(properties::setProperty)
    }
  }

  enum class Key(val propertyName: String) {
    DmgGamesType("system.dmgGames"),
    CgbGamesType("system.cgbGames"),
    BootstrapMode("system.bootstrapMode"),
    DisplayScale("display.scale"),
    DisplayGrayscale("display.grayscale"),
    DisplayBlending("display.blending"),
    DisplayColorCorrection("display.colorCorrection"),
    DisplayRotation("display.rotation"),
    ShowSgbBorder("display.showSgbBorder"),
    SoundEnabled("sound.enabled"),
    RomDirectory("rom.directory"),
    DatelSlotRom("datel.slot.rom"),
    FullChangerCharacter("fullchanger.character"),
  }
}
