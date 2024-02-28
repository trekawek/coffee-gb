package eu.rekawek.coffeegb.swing.gui.properties

import eu.rekawek.coffeegb.controller.ButtonListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.event.KeyEvent
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.*
import java.util.stream.Collectors

class EmulatorProperties() {

    internal val properties = loadProperties()

    val recentRoms = RecentRoms(this)

    val controllerMapping = ControllerProperties.getControllerMapping(properties)

    fun getProperty(key: Key, defaultValue: String? = null) = properties.getProperty(key.propertyName, defaultValue)

    fun setProperty(key: Key, value: String) {
        properties[key.propertyName] = value
        saveProperties()
    }

    internal fun saveProperties() {
        try {
            FileWriter(PROPERTIES_FILE).use { writer ->
                properties.store(writer, "")
            }
        } catch (e: IOException) {
            LOG.error("Can't store properties", e)
        }
    }

    enum class Key(val propertyName: String) {
        GameboyType("gameboy.type"),
        DisplayScale("display.scale"),
        DisplayGrayscale("display.grayscale"),
        SoundEnabled("sound.enabled"),
        RomDirectory("rom.directory"),
    }

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(EmulatorProperties::class.java)
        val PROPERTIES_FILE = File(File(System.getProperty("user.home")), ".coffeegb.properties")
        fun loadProperties(): Properties {
            val props = Properties()
            if (PROPERTIES_FILE.exists()) {
                FileReader(PROPERTIES_FILE).use { reader ->
                    props.load(reader)
                }
            }
            return props
        }

    }
}