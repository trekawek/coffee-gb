package eu.rekawek.coffeegb.swing.gui.properties

import java.util.*
import java.util.stream.Collectors

class RecentRoms(private val emulatorProperties: EmulatorProperties) {
    private val roms = LinkedList<String>()

    private val properties = emulatorProperties.properties

    init {
        for (i in 0 until MAX_ROMS) {
            val key = ROM_KEY_PREFIX + i
            if (properties.containsKey(key)) {
                roms.add(properties.getProperty(key))
            }
        }
    }

    fun getRoms(): List<String> {
        return roms
    }

    fun addRom(rom: String) {
        roms.remove(rom)
        roms.addFirst(rom)
        while (roms.size > MAX_ROMS) {
            roms.removeLast()
        }
        cleanProperties()
        setProperties()
        emulatorProperties.saveProperties()
    }

    private fun cleanProperties() {
        val keys = properties.keys.stream().map { o: Any -> o as String }.filter { k: String -> k.startsWith(ROM_KEY_PREFIX) }.collect(Collectors.toList())
        for (k in keys) {
            properties.remove(k)
        }
    }

    private fun setProperties() {
        for (i in roms.indices) {
            properties.setProperty(ROM_KEY_PREFIX + i, roms[i])
        }
    }

    private companion object {
        const val ROM_KEY_PREFIX = "rom.recent."
        const val MAX_ROMS = 10
    }
}