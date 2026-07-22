package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.awt.Cursor
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

  private var activeWindowTitle = "Coffee GB"

  private var romLoading = false

  init {
    eventBus = EventBusImpl()
    emulator = SwingEmulator(eventBus, console, properties)
  }

  private fun startGui() {
    mainWindow = JFrame("Coffee GB")

    SwingMenu(properties, mainWindow, eventBus).addMenu()
    eventBus.register<RomLoadingEvent> {
      romLoading = true
      updateLoadingUi("Coffee GB: Loading ${it.rom.name}…", true)
    }
    eventBus.register<EmulationStartedEvent> {
      activeWindowTitle = "Coffee GB: ${it.romName}"
      romLoading = false
      updateLoadingUi(activeWindowTitle, false)
    }
    eventBus.register<LoadRomFailedEvent> {
      romLoading = false
      updateLoadingUi(activeWindowTitle, false)
    }
    eventBus.register<RomLoadingCancelledEvent> {
      romLoading = false
      updateLoadingUi(activeWindowTitle, false)
    }
    eventBus.register<EmulationStoppedEvent> {
      activeWindowTitle = "Coffee GB"
      if (!romLoading) {
        updateLoadingUi(activeWindowTitle, false)
      }
    }

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
      eventBus.post(Controller.LoadRomEvent(initialRom))
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

  private fun updateLoadingUi(title: String, loading: Boolean) {
    SwingUtilities.invokeLater {
      mainWindow.title = title
      mainWindow.cursor =
          Cursor.getPredefinedCursor(if (loading) Cursor.WAIT_CURSOR else Cursor.DEFAULT_CURSOR)
    }
  }

  companion object {
    fun run(debug: Boolean, initialRom: File?) {
      SwingUtilities.invokeLater { SwingGui(debug, initialRom).startGui() }
    }
  }
}
