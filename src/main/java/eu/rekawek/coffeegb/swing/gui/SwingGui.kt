package eu.rekawek.coffeegb.swing.gui

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.StopEmulationEvent
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStoppedEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.StopServerEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

class SwingGui private constructor(debug: Boolean, private val initialRom: File?) {

  private val eventBus: EventBus

  private val emulator: SwingEmulator

  private val console: Console? = if (debug) Console() else null

  private val properties: EmulatorProperties = EmulatorProperties()

  private lateinit var mainWindow: JFrame

  init {
    eventBus = EventBus()
    emulator = SwingEmulator(eventBus, console, properties)
  }

  private fun startGui() {
    mainWindow = JFrame("Coffee GB")

    SwingMenu(properties, mainWindow, eventBus).addMenu()
    eventBus.register<EmulationStartedEvent> { mainWindow.title = "Coffee GB: ${it.romName}" }
    eventBus.register<EmulationStoppedEvent> { mainWindow.title = "Coffee GB" }

    mainWindow.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    mainWindow.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosed(windowEvent: WindowEvent) {
            stopGui()
          }
        })

    emulator.bind(mainWindow)
    mainWindow.pack()
    mainWindow.repaint()
    mainWindow.setLocationRelativeTo(null)
    mainWindow.isResizable = false
    mainWindow.isVisible = true
    if (console != null) {
      Thread(console).start()
    }
    if (initialRom != null) {
      eventBus.post(SwingEmulator.LoadRomEvent(initialRom))
    }
  }

  private fun stopGui() {
    eventBus.post(StopEmulationEvent())
    eventBus.post(StopServerEvent())
    eventBus.post(StopClientEvent())
    console?.stop()
    emulator.stop()
    exitProcess(0)
  }

  companion object {
    fun run(debug: Boolean, initialRom: File?) {
      SwingUtilities.invokeLater { SwingGui(debug, initialRom).startGui() }
    }
  }
}
