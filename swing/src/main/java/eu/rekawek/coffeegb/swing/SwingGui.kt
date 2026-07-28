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
import eu.rekawek.coffeegb.core.sound.Sound
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

  private lateinit var displayController: DesktopDisplayController

  private var applicationDisposeRequested = false

  private var activeWindowTitle = "Coffee GB"

  private var romLoading = false

  private val romSessionState = RomSessionState()

  private val shutdownStarted = AtomicBoolean()

  init {
    eventBus = EventBusImpl()
    emulator = SwingEmulator(eventBus, console, properties)
  }

  private fun startGui() {
    mainWindow = JFrame("Coffee GB")
    val minimumContentSize = emulator.minimumContentSize()
    displayController =
        DesktopDisplayController(
            properties,
            eventBus,
            DesktopFullscreenRuntime(
                mainWindow,
                DesktopSize(minimumContentSize.width, minimumContentSize.height),
            ),
            DisplayWindowSizingRuntime(emulator::refreshDisplayWindowSizing),
        )

    SwingMenu(
            properties,
            mainWindow,
            eventBus,
            romSessionState,
            displayController,
            ::showPreferences,
            ::requestClose,
        )
        .addMenu()
    eventBus.register<RomLoadingEvent> {
      romLoading = true
      updateLoadingUi("Coffee GB: Loading ${it.rom.name}…", true)
    }
    eventBus.register<EmulationStartedEvent> {
      activeWindowTitle = "Coffee GB: ${it.romName}"
      romLoading = false
      romSessionState.markStarted()
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
      romSessionState.markStopped()
      if (!romLoading) {
        updateLoadingUi(activeWindowTitle, false)
      }
    }

    mainWindow.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
    mainWindow.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(windowEvent: WindowEvent) {
            requestClose()
          }

          override fun windowClosed(windowEvent: WindowEvent) {
            // Borderless fullscreen requires dispose/re-show so JFrame decorations can change.
            // Only the explicit application-close path owns runtime shutdown.
            if (applicationDisposeRequested) {
              stopGui()
            }
          }
        })

    emulator.bind(mainWindow) { !displayController.current().fullscreen }
    mainWindow.pack()
    mainWindow.repaint()
    mainWindow.setLocationRelativeTo(null)
    mainWindow.minimumSize =
        minimumFrameSize(
            emulator.minimumContentSizeForCurrentMode(
                windowed = !displayController.current().fullscreen),
            mainWindow.insets,
            mainWindow.jMenuBar?.preferredSize?.height ?: 0,
        )
    mainWindow.isResizable = true
    mainWindow.isVisible = true
    displayController.applyCurrent()
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
    if (!shutdownStarted.compareAndSet(false, true)) {
      return
    }
    // A controller close waits for its emulation and ROM-loader workers, and audio teardown may
    // wait for a platform mixer. The window is already disposed, so none of that work belongs on
    // Swing's Event Dispatch Thread.
    val shutdown =
        launchDesktopShutdown {
          try {
            eventBus.post(StopEmulationEvent())
            eventBus.post(StopServerEvent())
            eventBus.post(StopClientEvent())
            console?.stop()
            emulator.stop()
          } catch (failure: RuntimeException) {
            LOG.error("Desktop runtime did not shut down cleanly", failure)
          }
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
        }
    launchDesktopShutdownWatchdog(shutdown, DESKTOP_SHUTDOWN_TIMEOUT_MILLIS) {
      LOG.error(
          "Desktop shutdown exceeded {} ms; terminating rather than leaving a hung process",
          DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
      )
      exitProcess(1)
    }
  }

  private fun requestClose() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Application close must be requested from the Event Dispatch Thread"
    }
    val running = romSessionState.isRunning()
    val proceed =
        proceedWithRomChange(properties.romChangeConfirmationPolicy, running) {
          JOptionPane.showConfirmDialog(
              mainWindow,
              if (running) {
                "Quit Coffee GB and close the running game?"
              } else {
                "Quit Coffee GB?"
              },
              "Quit Coffee GB",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE,
          ) == JOptionPane.YES_OPTION
        }
    if (!proceed) {
      return
    }
    displayController.close()
    applicationDisposeRequested = true
    mainWindow.dispose()
  }

  private fun showPreferences() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Preferences must be opened from the Event Dispatch Thread"
    }
    PreferencesDialog.show(
        owner = mainWindow,
        initial = properties.applicationSettings,
        gamepadCatalog = emulator.gamepadCatalog(),
        audioDevices = AudioDeviceProvider(emulator::audioDevices),
    ) { edit ->
      properties.updateApplicationSettings(edit::applyTo)
      val applied = properties.applicationSettings
      emulator.applyKeyboardMapping(applied.input.toPlayerMapping())
      emulator.applyDeviceSettings(applied)
      displayController.apply(applied.display, persist = false)
      eventBus.post(Sound.SoundEnabledEvent(applied.audio.enabled))
    }
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
    private const val DESKTOP_SHUTDOWN_TIMEOUT_MILLIS = 15_000L

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

internal fun launchDesktopShutdown(shutdown: () -> Unit): Thread =
    Thread(shutdown, "coffee-gb-desktop-shutdown").apply {
      isDaemon = false
      start()
    }

internal fun launchDesktopShutdownWatchdog(
    shutdown: Thread,
    timeoutMillis: Long,
    onTimeout: () -> Unit,
): Thread {
  require(timeoutMillis > 0) { "Desktop shutdown timeout must be positive" }
  return Thread(
          {
            try {
              shutdown.join(timeoutMillis)
              if (shutdown.isAlive) {
                shutdown.interrupt()
                onTimeout()
              }
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
            }
          },
          "coffee-gb-desktop-shutdown-watchdog",
      )
      .apply {
        isDaemon = true
        start()
      }
}

internal fun minimumFrameSize(
    content: Dimension,
    insets: Insets,
    menuHeight: Int,
): Dimension {
  require(content.width > 0 && content.height > 0) { "Minimum content size must be positive" }
  require(menuHeight >= 0) { "Menu height must not be negative" }
  return Dimension(
      Math.addExact(content.width, Math.addExact(insets.left, insets.right)),
      Math.addExact(
          content.height,
          Math.addExact(menuHeight, Math.addExact(insets.top, insets.bottom)),
      ),
  )
}
