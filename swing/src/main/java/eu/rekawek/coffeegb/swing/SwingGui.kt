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
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.awt.Cursor
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

class SwingGui private constructor(
    debug: Boolean,
    private val initialRom: File?,
    private val properties: EmulatorProperties,
) {

  private val eventBus: EventBus

  private val emulator: SwingEmulator

  private val console: Console? = if (debug) Console() else null

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
    properties.consumeLoadWarning()?.let { warning ->
      JOptionPane.showMessageDialog(
          mainWindow,
          warning.message,
          "Settings warning",
          JOptionPane.WARNING_MESSAGE,
      )
    }
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
    // The final settings force/move can block briefly. The window is already disposed, so finish
    // persistence off the EDT and keep this non-daemon thread alive until the bounded close ends.
    Thread(
            {
              try {
                properties.close()
              } catch (failure: IllegalStateException) {
                runCatching {
                  SwingUtilities.invokeAndWait {
                    JOptionPane.showMessageDialog(
                        null,
                        "Coffee GB could not save the latest settings. " +
                            "The previous complete settings file was preserved.\n\n" +
                            (failure.cause?.message
                                ?: failure.message
                                ?: failure.javaClass.simpleName),
                        "Settings save failed",
                        JOptionPane.ERROR_MESSAGE,
                    )
                  }
                }
              }
              exitProcess(0)
            },
            "coffee-gb-settings-shutdown",
        )
        .start()
  }

  private fun updateLoadingUi(title: String, loading: Boolean) {
    SwingUtilities.invokeLater {
      mainWindow.title = title
      val cursor =
          Cursor.getPredefinedCursor(if (loading) Cursor.WAIT_CURSOR else Cursor.DEFAULT_CURSOR)
      mainWindow.cursor = cursor
      mainWindow.rootPane.cursor = cursor
      mainWindow.contentPane.cursor = cursor
      // A child's explicitly configured cursor can override the frame cursor. The transparent
      // glass pane sits above every child, so making it visible during loading both guarantees the
      // wait pointer and prevents mouse interaction with the frozen game.
      mainWindow.glassPane.cursor = cursor
      mainWindow.glassPane.isVisible = loading
    }
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(SwingGui::class.java)

    fun run(
        debug: Boolean,
        initialRom: File?,
        settingsOverrides: ApplicationSettingsOverrides = ApplicationSettingsOverrides(),
    ) {
      // Loading, validating, migrating, and recovering the settings file can touch the disk. Do
      // that on the calling launcher thread before entering Swing's Event Dispatch Thread.
      val properties = EmulatorProperties(settingsOverrides)
      Runtime.getRuntime().addShutdownHook(
          createSettingsShutdownHook(properties) { failure ->
            LOG.error("Unable to close application settings during JVM shutdown", failure)
          })
      SwingUtilities.invokeLater { SwingGui(debug, initialRom, properties).startGui() }
    }
  }
}

internal fun createSettingsShutdownHook(
    settings: AutoCloseable,
    onFailure: (Exception) -> Unit,
): Thread =
    Thread(
        {
          try {
            settings.close()
          } catch (failure: Exception) {
            onFailure(failure)
          }
        },
        "coffee-gb-settings-shutdown-hook",
    )
