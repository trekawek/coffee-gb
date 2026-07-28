package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.sound.Sound
import java.awt.Cursor
import java.awt.Color
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.BorderFactory
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
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

  private lateinit var romOpen: DesktopRomOpen

  private var activeWindowTitle = "Coffee GB"

  private var romLoading = false

  private val romSessionState = RomSessionState()

  private val shutdownCoordinator by lazy {
    DesktopShutdownCoordinator(
        shutdown = {
          emulator.stop()
          romOpen.close()
          console?.stop()
          closeSettings()
        },
        timeoutMillis = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
        onPersistenceFailure = ::showClosePersistenceFailure,
        onFailure = ::showCloseFailure,
        onTimeout = ::showCloseTimeout,
        onSuccess = {
          SwingUtilities.invokeLater {
            displayController.close()
            mainWindow.dispose()
            exitProcess(0)
          }
        },
    )
  }

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

    lateinit var menu: SwingMenu
    romOpen =
        DesktopRomOpen(
            mainWindow,
            eventBus,
            properties,
            romSessionState,
            onRecentChanged = { menu.updateRecentRoms() },
        )
    menu =
        SwingMenu(
            properties,
            mainWindow,
            eventBus,
            romSessionState,
            displayController,
            romOpen::open,
            ::showPreferences,
            ::requestClose,
        )
    menu.addMenu()
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
        })

    emulator.bind(mainWindow) { !displayController.current().fullscreen }
    installRomDropTarget()
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
    installDesktopOpenFileHandler { paths ->
      romOpen.open(
          paths.map(RomOpenInput::LocalPath),
          RomOpenSource.DESKTOP_OPEN_FILE,
      )
    }
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
      romOpen.open(initialRom.toPath(), RomOpenSource.INITIAL_ARGUMENT)
    }
  }

  private fun installRomDropTarget() {
    val root = mainWindow.rootPane
    val normalBorder = root.border
    val highlight =
        BorderFactory.createLineBorder(
            UIManager.getColor("Component.focusColor") ?: Color(65, 105, 225),
            3,
        )
    val clearFeedback =
        Timer(300) { root.border = normalBorder }.apply {
          isRepeats = false
        }
    root.accessibleContext.accessibleDescription =
        "Drop one Game Boy ROM or ZIP archive here to open it"
    root.transferHandler =
        RomDropTransferHandler(
            submit = { inputs -> romOpen.open(inputs, RomOpenSource.DROP) },
            feedback = { active ->
              root.border = if (active) highlight else normalBorder
              if (active) clearFeedback.restart() else clearFeedback.stop()
              root.repaint()
            },
        )
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
    if (shutdownCoordinator.request()) {
      updateLoadingUi("Coffee GB: Saving before quit…", true)
    }
  }

  private fun showClosePersistenceFailure(
      failure: Controller.PersistenceBarrierException,
      retry: () -> Unit,
      cancel: () -> Unit,
  ) {
    SwingUtilities.invokeLater {
      val choice =
          JOptionPane.showOptionDialog(
              mainWindow,
              "Coffee GB could not safely persist ${failure.fileName}. " +
                  "The session and its pending changes are retained, paused awaiting retry.\n\n" +
                  (failure.message ?: failure.cause?.message ?: failure.javaClass.simpleName),
              "Save before quit failed",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.ERROR_MESSAGE,
              null,
              arrayOf("Retry", "Keep paused session open"),
              "Retry",
          )
      if (choice == JOptionPane.YES_OPTION) {
        updateLoadingUi("Coffee GB: Retrying save before quit…", true)
        retry()
      } else {
        cancel()
        pausedQuitRetryUi().let { updateLoadingUi(it.title, it.blocksInput) }
      }
    }
  }

  private fun showCloseFailure(failure: Exception) {
    LOG.error("Desktop runtime did not shut down cleanly", failure)
    SwingUtilities.invokeLater {
      updateLoadingUi(activeWindowTitle, false)
      JOptionPane.showMessageDialog(
          mainWindow,
          "Coffee GB did not finish shutting down. The window has been kept open.\n\n" +
              (failure.message ?: failure.javaClass.simpleName),
          "Quit failed",
          JOptionPane.ERROR_MESSAGE,
      )
    }
  }

  private fun showCloseTimeout() {
    LOG.error("Desktop shutdown exceeded {} ms", DESKTOP_SHUTDOWN_TIMEOUT_MILLIS)
    SwingUtilities.invokeLater {
      updateLoadingUi(activeWindowTitle, false)
      JOptionPane.showMessageDialog(
          mainWindow,
          "Coffee GB kept the window open instead of forcing an unsafe exit. " +
              "Shutdown work may still be unwinding; a late completion will not close the window.",
          "Quit is taking too long",
          JOptionPane.WARNING_MESSAGE,
      )
    }
  }

  private fun closeSettings() {
    try {
      properties.close()
    } catch (failure: IllegalStateException) {
      runCatching {
        SwingUtilities.invokeAndWait {
          JOptionPane.showMessageDialog(
              mainWindow,
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

internal data class DesktopLoadingUiState(
    val title: String,
    val blocksInput: Boolean,
)

internal fun pausedQuitRetryUi() =
    DesktopLoadingUiState(
        title = "Coffee GB: Paused; close again to retry saving before quit",
        blocksInput = true,
    )

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
      isDaemon = true
      start()
    }

internal class DesktopShutdownCoordinator(
    private val shutdown: () -> Unit,
    private val timeoutMillis: Long,
    private val onPersistenceFailure:
        (Controller.PersistenceBarrierException, retry: () -> Unit, cancel: () -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onTimeout: () -> Unit,
    private val onSuccess: () -> Unit,
) {
  private val activeAttempt = AtomicReference<ShutdownAttempt?>()
  private val decisionPending = AtomicBoolean()
  private val completed = AtomicBoolean()

  fun request(): Boolean {
    if (completed.get() || decisionPending.get()) {
      return false
    }
    val attempt = ShutdownAttempt()
    if (!activeAttempt.compareAndSet(null, attempt)) {
      return false
    }
    val worker =
        launchDesktopShutdown {
          try {
            shutdown()
          } catch (failure: Controller.PersistenceBarrierException) {
            finishPersistenceFailure(attempt, failure)
            return@launchDesktopShutdown
          } catch (failure: Exception) {
            finishFailure(attempt, failure)
            return@launchDesktopShutdown
          }
          finishSuccess(attempt)
        }
    launchDesktopShutdownWatchdog(worker, timeoutMillis) {
      if (attempt.terminal.compareAndSet(false, true)) {
        onTimeout()
      }
    }
    return true
  }

  private fun finishPersistenceFailure(
      attempt: ShutdownAttempt,
      failure: Controller.PersistenceBarrierException,
  ) {
    if (!attempt.terminal.compareAndSet(false, true)) {
      activeAttempt.compareAndSet(attempt, null)
      return
    }
    decisionPending.set(true)
    activeAttempt.compareAndSet(attempt, null)
    onPersistenceFailure(
        failure,
        { resolvePersistenceFailure(retry = true) },
        { resolvePersistenceFailure(retry = false) },
    )
  }

  private fun finishFailure(attempt: ShutdownAttempt, failure: Exception) {
    if (attempt.terminal.compareAndSet(false, true)) {
      activeAttempt.compareAndSet(attempt, null)
      onFailure(failure)
    } else {
      activeAttempt.compareAndSet(attempt, null)
    }
  }

  private fun finishSuccess(attempt: ShutdownAttempt) {
    if (attempt.terminal.compareAndSet(false, true)) {
      completed.set(true)
      activeAttempt.compareAndSet(attempt, null)
      onSuccess()
    } else {
      // The watchdog already retained the UI. Never let this late success dispose or exit.
      activeAttempt.compareAndSet(attempt, null)
    }
  }

  private fun resolvePersistenceFailure(retry: Boolean) {
    if (!decisionPending.compareAndSet(true, false)) {
      return
    }
    if (retry) {
      request()
    }
  }

  private class ShutdownAttempt {
    val terminal = AtomicBoolean()
  }
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
