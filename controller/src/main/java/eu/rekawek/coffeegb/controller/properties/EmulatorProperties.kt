package eu.rekawek.coffeegb.controller.properties

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.*

class EmulatorProperties() {

  internal val properties = loadProperties()

  val recentRoms = RecentRoms(this)

  val display = DisplayProperties(this)

  val sound = SoundProperties(this)

  val system = SystemProperties(this)

  val controllerMapping = ControllerProperties.getControllerMapping(properties)

  fun getProperty(key: Key, defaultValue: String? = null) =
      properties.getProperty(key.propertyName, defaultValue)

  fun setProperty(key: Key, value: String) {
    properties[key.propertyName] = value
    saveProperties()
  }

  fun removeProperty(key: Key) {
    properties.remove(key.propertyName)
    saveProperties()
  }

  internal fun saveProperties() {
    try {
      val path = PROPERTIES_FILE.toPath()
      val supportsPosix =
          Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null
      if (supportsPosix) {
        if (!Files.exists(path)) {
          Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
        }
        // Tighten an existing properties file before an API key can be written to it.
        Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
      }
      FileWriter(PROPERTIES_FILE).use { writer -> properties.store(writer, "") }
      if (supportsPosix) {
        Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
      }
    } catch (e: IOException) {
      LOG.error("Can't store properties", e)
    }
  }

  enum class Key(val propertyName: String) {
    DmgGamesType("system.dmgGames"),
    CgbGamesType("system.cgbGames"),
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
    RetroAchievementsUsername("retroachievements.username"),
    RetroAchievementsApiKey("retroachievements.apiKey"),
  }

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(EmulatorProperties::class.java)
    val PROPERTIES_FILE = File(File(System.getProperty("user.home")), ".coffeegb.properties")
    val OWNER_ONLY_PERMISSIONS: Set<PosixFilePermission> =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    fun loadProperties(): Properties {
      val props = Properties()
      if (PROPERTIES_FILE.exists()) {
        FileReader(PROPERTIES_FILE).use { reader -> props.load(reader) }
      }
      return props
    }
  }
}
