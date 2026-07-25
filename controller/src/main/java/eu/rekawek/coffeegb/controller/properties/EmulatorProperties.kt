package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.*

class EmulatorProperties(val profileOverride: HardwareProfile? = null) {

  internal val properties = loadProperties()

  val recentRoms = RecentRoms(this)

  val display = DisplayProperties(this)

  val sound = SoundProperties(this)

  val system = SystemProperties(this)

  val playerInputMapping = ControllerProperties.getPlayerMapping(properties)

  /** Shared live-input service copied into each active machine configuration. */
  val playerInputSource = PlayerInputHub()

  val controllerMapping = playerInputMapping.legacyPrimaryKeyboard()

  fun getProperty(key: Key, defaultValue: String? = null) =
      properties.getProperty(key.propertyName, defaultValue)

  fun setProperty(key: Key, value: String) {
    properties[key.propertyName] = value
    saveProperties()
  }

  internal fun saveProperties() {
    try {
      FileWriter(PROPERTIES_FILE).use { writer -> properties.store(writer, "") }
    } catch (e: IOException) {
      LOG.error("Can't store properties", e)
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

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(EmulatorProperties::class.java)
    val PROPERTIES_FILE = File(File(System.getProperty("user.home")), ".coffeegb.properties")

    fun loadProperties(): Properties {
      val props = Properties()
      if (PROPERTIES_FILE.exists()) {
        FileReader(PROPERTIES_FILE).use { reader -> props.load(reader) }
      }
      return props
    }
  }
}
