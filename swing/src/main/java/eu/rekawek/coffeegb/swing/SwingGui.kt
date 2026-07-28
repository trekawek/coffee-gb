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
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

internal const val ROM_OPEN_QUIESCE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val CONTROLLER_SHUTDOWN_BUDGET_MILLIS = 8_000L
internal const val GAMEPAD_SHUTDOWN_BUDGET_MILLIS = 1_000L
internal const val AUDIO_SHUTDOWN_BUDGET_MILLIS = 2_250L
internal const val ROM_OPEN_CLOSE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val SETTINGS_CLOSE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val DESKTOP_SHUTDOWN_SCHEDULING_MARGIN_MILLIS = 8_750L
internal const val DESKTOP_SHUTDOWN_REQUIRED_BUDGET_MILLIS =
    ROM_OPEN_QUIESCE_SHUTDOWN_BUDGET_MILLIS +
        CONTROLLER_SHUTDOWN_BUDGET_MILLIS +
        GAMEPAD_SHUTDOWN_BUDGET_MILLIS +
        AUDIO_SHUTDOWN_BUDGET_MILLIS +
        ROM_OPEN_CLOSE_SHUTDOWN_BUDGET_MILLIS +
        SETTINGS_CLOSE_SHUTDOWN_BUDGET_MILLIS
internal const val DESKTOP_SHUTDOWN_TIMEOUT_MILLIS =
    DESKTOP_SHUTDOWN_REQUIRED_BUDGET_MILLIS + DESKTOP_SHUTDOWN_SCHEDULING_MARGIN_MILLIS

class SwingGui private constructor(
    debug: Boolean,
    private val initialRom: File?,
    private val properties: EmulatorProperties,
    private val desktopOpenFiles: DesktopOpenFilesBridge,
    private val jvmShutdown: DesktopJvmShutdownCoordinator,
) {

  private val eventBus: EventBus

  private val emulator: SwingEmulator

  private val console: Console? = if (debug) Console() else null

  private lateinit var mainWindow: JFrame

  private lateinit var displayController: DesktopDisplayController

  private lateinit var romOpen: DesktopRomOpen

  private lateinit var dropFeedback: RomDropFeedback

  private lateinit var stateUxController: StateUxDesktopController

  private lateinit var menu: SwingMenu

  private var activeWindowTitle = "Coffee GB"

  private var romLoading = false

  private var romLoadingRequestId: Long? = null

  private val romSessionState = RomSessionState()

  private val shutdownCoordinator by lazy {
    DesktopShutdownCoordinator(
        shutdown = {
          romOpen.quiesce()
          emulator.stop()
        },
        commit = {
          // Only a timely, successful emulator stop makes desktop teardown irreversible. A
          // persistence failure or watchdog timeout retains a quiesced (not closed) ROM service.
          romOpen.close()
          stateUxController.close()
          console?.stop()
          closeSettings()
          jvmShutdown.markCompleted()
        },
        timeoutMillis = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
        onPersistenceFailure = ::showClosePersistenceFailure,
        onFailure = ::showCloseFailure,
        onTimeout = ::showCloseTimeout,
        onSuccess = {
          SwingUtilities.invokeLater {
            dropFeedback.close()
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
    stateUxController =
        StateUxDesktopController(
            mainWindow,
            eventBus,
            emulator::captureDisplayImage,
        )

    romOpen =
        DesktopRomOpen(
            mainWindow,
            eventBus,
            properties,
            romSessionState,
            onRecentChanged = { menu.updateRecentRoms() },
        )
    jvmShutdown.installParticipant {
      runDesktopJvmShutdownSteps(
          romOpen::quiesce,
          emulator::stop,
          romOpen::close,
          properties::close,
      )
    }
    menu =
        SwingMenu(
            properties,
            mainWindow,
            eventBus,
            romSessionState,
            displayController,
            romOpen::open,
            ::acceptRomLifecycle,
            ::showPreferences,
            stateUxController::showBrowser,
            stateUxController::takeScreenshot,
            stateUxController::openSaveFolder,
            ::requestClose,
        )
    menu.addMenu()
    eventBus.register<RomLoadingEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        romLoading = true
        romLoadingRequestId = event.openRequestId
        updateLoadingUi("Coffee GB: Loading ${event.rom.name}…", true)
      }
    }
    eventBus.register<EmulationStartedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        activeWindowTitle = "Coffee GB: ${event.romName}"
        romLoading = false
        romLoadingRequestId = null
        romSessionState.markStarted()
        updateLoadingUi(activeWindowTitle, false)
      }
    }
    eventBus.register<LoadRomFailedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        if (matchesLoadingRequest(event.openRequestId)) {
          romLoading = false
          romLoadingRequestId = null
          updateLoadingUi(activeWindowTitle, false)
        }
      }
    }
    eventBus.register<RomLoadingCancelledEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        if (matchesLoadingRequest(event.openRequestId)) {
          romLoading = false
          romLoadingRequestId = null
          updateLoadingUi(activeWindowTitle, false)
        }
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      dispatchAcceptedRomLifecycle(null, ::acceptRomLifecycle) {
        if (!romOpen.hasActiveRequest()) {
          activeWindowTitle = "Coffee GB"
          romSessionState.markStopped()
          if (!romLoading) {
            updateLoadingUi(activeWindowTitle, false)
          }
        }
      }
    }
    desktopOpenFiles.attach(initialRom?.toPath()) { paths ->
      romOpen.open(
          paths.map(RomOpenInput::LocalPath),
          RomOpenSource.DESKTOP_OPEN_FILE,
      )
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
    dropFeedback = RomDropFeedback(root)
    root.transferHandler =
        RomDropTransferHandler(
            submit = { inputs -> romOpen.open(inputs, RomOpenSource.DROP) },
            feedback = dropFeedback::update,
        )
  }

  private fun acceptRomLifecycle(openRequestId: Long?): Boolean =
      shouldApplyRomLifecycleEvent(
          openRequestId,
          romOpen.hasActiveRequest(),
          romOpen::ownsVisibleRequest,
      )

  private fun matchesLoadingRequest(openRequestId: Long?): Boolean =
      if (openRequestId == null) {
        romLoading && romLoadingRequestId == null
      } else {
        romLoading && romLoadingRequestId == openRequestId
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
    // The coordinator's watchdog owns the entire shutdown, including managed-state autosave.
    // Starting it here prevents a slow state writer from running outside the desktop deadline.
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
      val options =
          if (failure.closeAutosaveWaivable) {
            arrayOf("Retry", "Close without autosave", "Keep paused session open")
          } else {
            arrayOf("Retry", "Keep paused session open")
          }
      val choice =
          JOptionPane.showOptionDialog(
              mainWindow,
              "Coffee GB could not safely persist ${failure.fileName}. " +
                  "The session and its pending changes are retained, paused awaiting retry.\n\n" +
                  (failure.message ?: failure.cause?.message ?: failure.javaClass.simpleName),
              "Save before quit failed",
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.ERROR_MESSAGE,
              null,
              options,
              options[0],
          )
      when {
        choice == 0 -> {
          updateLoadingUi("Coffee GB: Retrying save before quit…", true)
          retry()
        }
        failure.closeAutosaveWaivable && choice == 1 -> {
          if (emulator.waiveCloseAutosave(failure.requestId)) {
            updateLoadingUi("Coffee GB: Closing without a new autosave…", true)
            retry()
          } else {
            cancel()
            pausedQuitRetryUi().let { updateLoadingUi(it.title, it.blocksInput) }
            JOptionPane.showMessageDialog(
                mainWindow,
                "The autosave attempt changed before it could be waived. Close again to retry.",
                "Close choice expired",
                JOptionPane.WARNING_MESSAGE,
            )
          }
        }
        else -> {
          cancel()
          pausedQuitRetryUi().let { updateLoadingUi(it.title, it.blocksInput) }
        }
      }
    }
  }

  private fun showCloseFailure(failure: Exception) {
    LOG.error("Desktop runtime did not shut down cleanly", failure)
    SwingUtilities.invokeLater {
      updateLoadingUi(activeWindowTitle, false)
      JOptionPane.showMessageDialog(
          mainWindow,
          "Coffee GB did not finish shutting down. The window has been kept open, and ROM " +
              "opening remains paused so close can be retried safely.\n\n" +
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
    // Let the coordinator retain the window and accept a later close retry. The settings store
    // keeps its latest dirty revision open after a timeout/failure, so swallowing this exception
    // here would turn an otherwise recoverable flush into silent data loss at process exit.
    properties.close()
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
      eventBus.post(Controller.UpdatedSavesSettingsEvent(applied.saves))
    }
  }

  private fun updateLoadingUi(title: String, loading: Boolean) {
    dispatchSwingMutation {
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
      val desktopOpenFiles = DesktopOpenFilesBridge()
      installDesktopOpenFileHandler(desktopOpenFiles::accept)
      // Loading, validating, migrating, and recovering the settings file can touch the disk. Do
      // that on the calling launcher thread before entering Swing's Event Dispatch Thread.
      val properties = EmulatorProperties(settingsOverrides)
      val jvmShutdown =
          DesktopJvmShutdownCoordinator(
              fallback = properties::close,
              timeoutMillis = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
          ) { failure ->
            LOG.error("Unable to complete bounded desktop JVM shutdown", failure)
          }
      Runtime.getRuntime().addShutdownHook(jvmShutdown.createHook())
      SwingUtilities.invokeLater {
        SwingGui(debug, initialRom, properties, desktopOpenFiles, jvmShutdown).startGui()
      }
    }
  }
}

internal fun shouldApplyRomLifecycleEvent(
    openRequestId: Long?,
    managedOpenActive: Boolean,
    ownsVisibleRequest: (Long) -> Boolean,
): Boolean =
    if (openRequestId == null) {
      !managedOpenActive
    } else {
      ownsVisibleRequest(openRequestId)
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

internal fun runDesktopJvmShutdownSteps(vararg steps: () -> Unit) {
  var failure: Exception? = null
  steps.forEach { step ->
    try {
      step()
    } catch (problem: Exception) {
      if (failure == null) {
        failure = problem
      } else {
        failure.addSuppressed(problem)
      }
    }
  }
  failure?.let { throw it }
}

/**
 * A JVM hook is registered before Swing initialization, then upgraded to the complete desktop
 * participant once the emulator exists. Normal coordinated shutdown marks it complete before
 * exit, preventing a second controller/settings close.
 */
internal class DesktopJvmShutdownCoordinator(
    private val fallback: () -> Unit,
    private val timeoutMillis: Long,
    private val onFailure: (Exception) -> Unit,
) {
  private val participant = AtomicReference<(() -> Unit)?>(null)
  private val started = AtomicBoolean()
  private val completed = AtomicBoolean()

  init {
    require(timeoutMillis > 0) { "JVM shutdown timeout must be positive" }
  }

  fun installParticipant(action: () -> Unit): Boolean {
    if (completed.get() || started.get()) return false
    participant.set(action)
    return !completed.get() && !started.get()
  }

  fun markCompleted() {
    completed.set(true)
  }

  fun createHook(): Thread =
      Thread(
          {
            if (completed.get() || !started.compareAndSet(false, true)) {
              return@Thread
            }
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Exception?>()
            val worker =
                Thread(
                        {
                          try {
                            (participant.get() ?: fallback).invoke()
                          } catch (problem: Exception) {
                            failure.set(problem)
                          } finally {
                            finished.countDown()
                          }
                        },
                        "coffee-gb-jvm-shutdown-worker",
                    )
                    .apply { isDaemon = true }
            worker.start()
            try {
              if (!finished.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                worker.interrupt()
                failure.compareAndSet(
                    null,
                    IOException("Desktop JVM shutdown exceeded $timeoutMillis ms"),
                )
              }
            } catch (interrupted: InterruptedException) {
              Thread.currentThread().interrupt()
              worker.interrupt()
              failure.compareAndSet(null, IOException("Desktop JVM shutdown was interrupted", interrupted))
            }
            failure.get()?.let(onFailure)
            completed.set(true)
          },
          "coffee-gb-desktop-shutdown-hook",
      )
}

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
    private val commit: () -> Unit = {},
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
      if (attempt.timeOut()) {
        onTimeout()
      }
    }
    return true
  }

  private fun finishPersistenceFailure(
      attempt: ShutdownAttempt,
      failure: Controller.PersistenceBarrierException,
  ) {
    if (!attempt.finishActive()) {
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
    if (attempt.finishActive()) {
      activeAttempt.compareAndSet(attempt, null)
      onFailure(failure)
    } else {
      activeAttempt.compareAndSet(attempt, null)
    }
  }

  private fun finishSuccess(attempt: ShutdownAttempt) {
    if (!attempt.beginCommit()) {
      activeAttempt.compareAndSet(attempt, null)
      return
    }
    try {
      commit()
    } catch (failure: Exception) {
      if (attempt.finishCommit()) {
        onFailure(failure)
      }
      activeAttempt.compareAndSet(attempt, null)
      return
    }
    if (attempt.finishCommit()) {
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
    private val state = AtomicReference(State.ACTIVE)

    fun beginCommit(): Boolean = state.compareAndSet(State.ACTIVE, State.COMMITTING)

    fun finishActive(): Boolean = state.compareAndSet(State.ACTIVE, State.TERMINAL)

    fun finishCommit(): Boolean = state.compareAndSet(State.COMMITTING, State.TERMINAL)

    fun timeOut(): Boolean {
      while (true) {
        when (val current = state.get()) {
          State.ACTIVE,
          State.COMMITTING -> if (state.compareAndSet(current, State.TIMED_OUT)) return true
          State.TIMED_OUT,
          State.TERMINAL -> return false
        }
      }
    }

    private enum class State {
      ACTIVE,
      COMMITTING,
      TIMED_OUT,
      TERMINAL,
    }
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
