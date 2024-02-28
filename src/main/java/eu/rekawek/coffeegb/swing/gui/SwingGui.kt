package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.swing.emulator.EmulatorStateListener
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties.Key
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*

class SwingGui(debug: Boolean, private val initialRom: File?) {

    private val emulator: SwingEmulator

    private val console: Console? = if (debug) Console() else null

    private val properties: EmulatorProperties = EmulatorProperties()

    private lateinit var mainWindow: JFrame

    init {
        emulator = SwingEmulator(console, properties.controllerMapping)

        emulator.displayController.scale = properties.getProperty(Key.DisplayScale, "2").toInt()
        emulator.displayController.grayscale = properties.getProperty(Key.DisplayGrayscale, "false").toBoolean()
        emulator.soundController.enabled = properties.getProperty(Key.SoundEnabled, "true").toBoolean()
        emulator.gameboyType = GameboyType.valueOf(properties.getProperty(Key.GameboyType, GameboyType.AUTOMATIC.name))
    }

    fun run() {
        System.setProperty("apple.awt.application.name", "Coffee GB")
        System.setProperty("sun.java2d.opengl", "true")
        SwingUtilities.invokeLater { this.startGui() }
    }

    private fun startGui() {
        mainWindow = JFrame("Coffee GB")
        SwingMenu(emulator, properties, mainWindow).addMenu()
        emulator.addEmulatorStateListener(object : EmulatorStateListener {
            override fun onEmulationStart(cartTitle: String) {
                mainWindow.title = "Coffee GB: $cartTitle"
            }

            override fun onEmulationStop() {
                mainWindow.title = "Coffee GB"
            }
        })

        mainWindow.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        mainWindow.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(windowEvent: WindowEvent) {
                stopGui()
            }
        })

        emulator.bind(mainWindow)
        mainWindow.isResizable = false
        mainWindow.isVisible = true
        mainWindow.pack()
        mainWindow.setLocationRelativeTo(null)
        if (console != null) {
            Thread(console).start()
        }
        if (initialRom != null) {
            emulator.startEmulation(initialRom)
        }
    }

    private fun stopGui() {
        emulator.stopEmulation()
        console?.stop()
        System.exit(0)
    }


}
