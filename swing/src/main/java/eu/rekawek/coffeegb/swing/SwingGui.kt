package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationStore
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.swing.debug.JlineConsole
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera
import eu.rekawek.coffeegb.core.sound.Sound
import eu.rekawek.coffeegb.swing.io.DesktopCameraSource
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.packaging.NativeRuntimeBootstrap
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

internal const val ROM_OPEN_QUIESCE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val CONTROLLER_SHUTDOWN_BUDGET_MILLIS = 8_000L
internal const val GAMEPAD_SHUTDOWN_BUDGET_MILLIS = 1_000L
internal const val AUDIO_SHUTDOWN_BUDGET_MILLIS = 2_250L
internal const val CAMERA_SHUTDOWN_BUDGET_MILLIS = 1_000L
internal const val ROM_OPEN_CLOSE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val SETTINGS_CLOSE_SHUTDOWN_BUDGET_MILLIS = 5_000L
internal const val MOBILE_ADAPTER_CONFIGURATION_SHUTDOWN_BUDGET_MILLIS = 2_000L
internal const val DESKTOP_SHUTDOWN_SCHEDULING_MARGIN_MILLIS = 8_750L
internal const val DESKTOP_SHUTDOWN_REQUIRED_BUDGET_MILLIS =
    ROM_OPEN_QUIESCE_SHUTDOWN_BUDGET_MILLIS +
        CONTROLLER_SHUTDOWN_BUDGET_MILLIS +
        GAMEPAD_SHUTDOWN_BUDGET_MILLIS +
        AUDIO_SHUTDOWN_BUDGET_MILLIS +
        CAMERA_SHUTDOWN_BUDGET_MILLIS +
        ROM_OPEN_CLOSE_SHUTDOWN_BUDGET_MILLIS +
        SETTINGS_CLOSE_SHUTDOWN_BUDGET_MILLIS +
        MOBILE_ADAPTER_CONFIGURATION_SHUTDOWN_BUDGET_MILLIS
internal const val DESKTOP_SHUTDOWN_TIMEOUT_MILLIS =
    DESKTOP_SHUTDOWN_REQUIRED_BUDGET_MILLIS + DESKTOP_SHUTDOWN_SCHEDULING_MARGIN_MILLIS

private enum class QuitDecision {
  QUIT,
  KEEP_OPEN,
}

private enum class ClosePersistenceDecision {
  RETRY,
  CLOSE_WITHOUT_AUTOSAVE,
  KEEP_OPEN,
}

class SwingGui private constructor(
    debug: Boolean,
    private val initialRom: File?,
    private val properties: EmulatorProperties,
    private val mobileAdapterConfiguration: MobileAdapterConfigurationCoordinator,
    private val mobileAdapterConfigurationUiState: MobileAdapterConfigurationUiState,
    private val desktopOpenFiles: DesktopOpenFilesBridge,
    private val jvmShutdown: DesktopJvmShutdownCoordinator,
    private val themeManager: DesktopThemeManager,
    private val initialTheme: DesktopThemeApplication,
    private val desktopUiStateStore: DesktopUiStateStore,
    private val initialDesktopUiState: DesktopUiState,
) {

  private val desktopDialogFactory =
      DesktopDialogFactory { themeManager.current?.tokens ?: initialTheme.tokens }

  private val eventBus: EventBus

  private val emulator: SwingEmulator

  private val console: JlineConsole? = if (debug) JlineConsole() else null

  private lateinit var mainWindow: JFrame

  private lateinit var displayController: DesktopDisplayController

  private lateinit var fullscreenEscape: FullscreenEscapeDispatcher

  private lateinit var romOpen: DesktopRomOpen

  private lateinit var dropFeedback: RomDropFeedback

  private lateinit var stateUxController: StateUxDesktopController

  private lateinit var debuggerController: DesktopDebuggerController

  private lateinit var netplayWindow: NetplayWindowHost

  private lateinit var mobileAdapterWindow: MobileAdapterConfigurationWindowHost

  private lateinit var menu: SwingMenu

  private lateinit var desktopActions: DesktopActionRegistry

  private lateinit var desktopMainPanel: DesktopMainPanel

  private lateinit var desktopUiCoordinator: DesktopUiCoordinator

  private lateinit var desktopPlaybackState: DesktopPlaybackState

  private lateinit var desktopUiStateController: DesktopUiStateController

  private val desktopQuit = DesktopQuitBridge()

  /** Reads Home previews outside Swing; every UI update is guarded by this monotonically rising id. */
  private val recentGamePreviewLoader = RecentGamePreviewLoader()

  private val recentGamePreviewGeneration = AtomicLong()

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
          menu.closeCameraAfterSuccessfulStop(CAMERA_SHUTDOWN_BUDGET_MILLIS)
          romOpen.close()
          runDesktopEdtStep(debuggerController::close)
          runDesktopEdtStep(netplayWindow::close)
          runDesktopEdtStep(mobileAdapterWindow::close)
          runDesktopEdtStep(stateUxController::close)
          recentGamePreviewLoader.close()
          console?.stop()
          runDesktopEdtStep(desktopUiStateController::close)
          // No process-wide key dispatcher may persist display settings once store closure begins.
          runDesktopEdtStep(fullscreenEscape::close)
          closeSettings()
          mobileAdapterConfiguration.close()
          jvmShutdown.markCompleted()
        },
        timeoutMillis = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
        onPersistenceFailure = ::showClosePersistenceFailure,
        onFailure = ::showCloseFailure,
        onTimeout = ::showCloseTimeout,
        onSuccess = ::finishSuccessfulShutdown,
    )
  }

  init {
    PocketCamera.setCameraSource(DesktopCameraSource.INSTANCE)
    eventBus = EventBusImpl()
    emulator =
        SwingEmulator(
            eventBus,
            console,
            properties,
            mobileAdapterConfiguration.provider,
            mobileAdapterConfiguration,
        )
  }

  private fun startGui() {
    val proposal3MenuEnabled = DesktopFeatureFlags.proposal3MenuEnabled()
    mainWindow = JFrame("Coffee GB")
    mainWindow.iconImages = listOf(16, 32, 48, 128, 256).map(CoffeeGbIcon::image)
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
    fullscreenEscape =
        FullscreenEscapeDispatcher(
                mainWindow,
                isFullscreen = { displayController.current().fullscreen },
                exitFullscreen = { displayController.setFullscreen(false) },
            )
            .also(FullscreenEscapeDispatcher::install)
    desktopUiStateController =
        DesktopUiStateController(
            mainWindow,
            desktopUiStateStore,
            initialDesktopUiState,
            isFullscreen = displayController::isFullscreen,
            onSaveFailure = {
              if (::desktopUiCoordinator.isInitialized) {
                desktopUiCoordinator.warning(
                    "Window placement could not be saved for the next launch.")
              }
            },
        )
    emulator.attachPrinterWindow(
        mainWindow,
        object : PrinterWindowBounds {
          override fun restore(): java.awt.Rectangle? =
              desktopUiStateController.utilityBounds(DesktopUtilityWindow.PRINTER)

          override fun remember(bounds: java.awt.Rectangle) {
            desktopUiStateController.rememberUtilityBounds(
                DesktopUtilityWindow.PRINTER,
                bounds,
            )
          }
        },
    )
    mobileAdapterWindow =
        MobileAdapterConfigurationWindowHost(
            owner = mainWindow,
            coordinator = mobileAdapterConfiguration,
            rootEventBus = eventBus,
            launcherState = mobileAdapterConfigurationUiState,
            initialBounds =
                desktopUiStateController.utilityBounds(DesktopUtilityWindow.MOBILE_ADAPTER),
            onBoundsChanged = { bounds ->
              desktopUiStateController.rememberUtilityBounds(
                  DesktopUtilityWindow.MOBILE_ADAPTER,
                  bounds,
              )
            },
            tokenProvider = { themeManager.current?.tokens ?: initialTheme.tokens },
            onGuestImagePersistenceFailure = { message ->
              if (::desktopUiCoordinator.isInitialized) {
                desktopUiCoordinator.warning(message)
              }
            },
        )
    stateUxController =
        StateUxDesktopController(
            owner = mainWindow,
            rootEventBus = eventBus,
            captureDisplayImage = emulator::captureDisplayImage,
            initialBounds = desktopUiStateController.utilityBounds(DesktopUtilityWindow.STATES),
            onBoundsChanged = { bounds ->
              desktopUiStateController.rememberUtilityBounds(DesktopUtilityWindow.STATES, bounds)
            },
            onDesktopStatus = { message, recovery ->
              if (::desktopUiCoordinator.isInitialized) {
                desktopUiCoordinator.warning(message, recovery)
              }
            },
            onSlotLoadAvailability = { slot, available ->
              if (::desktopUiCoordinator.isInitialized) {
                desktopUiCoordinator.stateSlotLoadAvailability(slot, available)
              }
            },
            onPortableCatalog = { catalog ->
              if (::desktopActions.isInitialized) {
                desktopActions.updatePortableStateSlots(
                    catalog.entries.filter { it.ref is eu.rekawek.coffeegb.controller.state.StateRef.Slot }
                        .filter {
                          (it.ref as eu.rekawek.coffeegb.controller.state.StateRef.Slot).index in
                              eu.rekawek.coffeegb.controller.state.StateRef.MIN_SLOT..
                                  eu.rekawek.coffeegb.controller.state.StateRef.MAX_SLOT
                        }
                        .map { entry ->
                          val ref = entry.ref as eu.rekawek.coffeegb.controller.state.StateRef.Slot
                          val image = entry.thumbnail
                          val preview = if (image == null) {
                            eu.rekawek.coffeegb.ui.menu.MenuPreview.empty()
                          } else {
                            val rgb = image.copyRgb()
                            val argb = IntArray(rgb.size) { index -> 0xff000000.toInt() or rgb[index] }
                            eu.rekawek.coffeegb.ui.menu.MenuPreview.ready(image.width, image.height, argb)
                          }
                          PortableMenuStateSlot(
                              ref.index,
                              entry.canLoad,
                              preview,
                              entry.catalogEntry?.metadata?.savedAt
                                  ?.takeIf { entry.canLoad },
                          )
                        })
              }
            },
            onRememberResumeDecision = { resume ->
              properties.updateApplicationSettings { current ->
                current.copy(
                    saves =
                        current.saves.copy(
                            resumePolicy =
                                if (resume) {
                                  ApplicationSettings.ResumePolicy.ALWAYS
                                } else {
                                  ApplicationSettings.ResumePolicy.NEVER
                                },
                        ))
              }
            },
            dialogFactory = desktopDialogFactory,
        )
    debuggerController =
        DesktopDebuggerController(
            mainWindow,
            eventBus,
            DesktopDebuggerViewFactory { owner -> DebuggerWindow(owner) },
        )
    netplayWindow =
        NetplayWindowHost(
            owner = mainWindow,
            rootEventBus = eventBus,
            onPresentation = { presentation ->
              if (::desktopUiCoordinator.isInitialized) {
                desktopUiCoordinator.netplaySummary(netplaySummary(presentation))
              }
            },
            confirmPeripheralHandoff = ::confirmNetplayPeripheralHandoff,
            initialBounds = desktopUiStateController.utilityBounds(DesktopUtilityWindow.NETPLAY),
            onBoundsChanged = { bounds ->
              desktopUiStateController.rememberUtilityBounds(
                  DesktopUtilityWindow.NETPLAY,
                  bounds,
              )
            },
        )

    romOpen =
        DesktopRomOpen(
            mainWindow,
            eventBus,
            properties,
            romSessionState,
            onRecentChanged = ::updateRecentRoms,
            dialogFactory = desktopDialogFactory,
            onUpdate = ::handleRomOpenUpdate,
        )
    jvmShutdown.installParticipant {
      runDesktopJvmShutdownSteps(
          romOpen::quiesce,
          {
            // Keep this a single step: runDesktopJvmShutdownSteps attempts later independent
            // cleanup after a failure, but camera ownership must survive a failed emulator stop.
            stopEmulatorBeforeCamera(
                emulator::stop,
                if (::menu.isInitialized) {
                  {
                    menu.closeCameraAfterSuccessfulStop(
                        CAMERA_SHUTDOWN_BUDGET_MILLIS)
                  }
                } else {
                  null
                },
            )
          },
          romOpen::close,
          { runDesktopEdtStep(debuggerController::close) },
          { runDesktopEdtStep(netplayWindow::close) },
          { runDesktopEdtStep(mobileAdapterWindow::close) },
          {
            if (::desktopUiStateController.isInitialized) {
              runDesktopEdtStep(desktopUiStateController::close)
            }
          },
          recentGamePreviewLoader::close,
          properties::close,
          mobileAdapterConfiguration::close,
      )
    }
    desktopActions =
        DesktopActionRegistry(
            DesktopCommandHandlers(
                openRom = { menu.openRomChooser() },
                closeGame = { menu.requestCloseGame() },
                preferences = ::showPreferences,
                quit = ::requestClose,
                openMenu = emulator::openPortableMenu,
                setPaused = { paused ->
                  eventBus.post(
                      if (paused) Controller.PauseEmulationEvent()
                      else Controller.ResumeEmulationEvent())
                },
                reset = { eventBus.post(Controller.ResetEmulationEvent()) },
                saveState = stateUxController::saveSlot,
                loadState = stateUxController::loadSlot,
                manageStates = stateUxController::showBrowser,
                openSaveFolder = stateUxController::openSaveFolder,
                netplay = netplayWindow::show,
                setMuted = { muted ->
                  desktopUiCoordinator.muted(muted)
                  eventBus.post(Sound.SoundEnabledEvent(!muted))
                  properties.setProperty(
                      EmulatorProperties.Key.SoundEnabled,
                      (!muted).toString(),
                  )
                },
                setAudioVolume = { volume ->
                  val current = properties.applicationSettings
                  if (current.audio.volume != volume) {
                    properties.updateApplicationSettings { settings ->
                      settings.copy(audio = settings.audio.copy(volume = volume))
                    }
                    emulator.applyDeviceSettings(properties.applicationSettings)
                  }
                  desktopUiCoordinator.audioVolume(volume)
                },
                setFullscreen = displayController::setFullscreen,
                screenshot = stateUxController::takeScreenshot,
                setCommandBarVisible = ::setCommandBarVisible,
                selectStateSlot = { slot ->
                  desktopUiCoordinator.stateSlot(slot)
                  stateUxController.selectSlot(slot)
                },
                preferencesForCategory = { category -> showPreferences(category) },
                openAbout = { menu.showAbout() },
            ),
            proposal3MenuAvailable = proposal3MenuEnabled,
            stateCatalogRefresh = stateUxController::refreshPortableCatalog,
        )
    desktopActions.applyShortcuts(
        DesktopShortcutRegistry(
            DesktopKeyboardKeyAdapter.keyCodes(properties.applicationSettings.input.keyboard.values)))
    val portableMenu =
        installDesktopProposal3Menu(proposal3MenuEnabled) {
          emulator.installPortableMenu(desktopActions) { visible ->
            if (visible && ::dropFeedback.isInitialized) dropFeedback.update(false)
          }
        }
    portableMenu?.let(romOpen::setArchiveSelectionHost)
    menu =
        SwingMenu(
            properties,
            mainWindow,
            eventBus,
            displayController,
            romOpen::open,
            ::acceptRomLifecycle,
            { category -> showPreferences(category) },
            DebuggerMenuActions(
                debuggerController::showTool,
                debuggerController::applyLayout,
            ),
            desktopActions,
            emulator::isLinkedControllerActive,
            mobileAdapterWindow::show,
            proposal3MenuEnabled,
            { themeManager.current?.tokens ?: initialTheme.tokens },
            { message ->
              desktopUiCoordinator.warning(message, DesktopCommand.PREFERENCES)
            },
        )
    menu.addMenu()

    val displayPanel = emulator.bind(mainWindow) { !displayController.current().fullscreen }
    desktopMainPanel =
        DesktopMainPanel(
            displayPanel,
            desktopActions,
            onOpenRecent = { path -> romOpen.open(path, RomOpenSource.RECENT) },
            onCancelTask = { romLoadingRequestId?.let(romOpen::cancel) },
            initialTokens = initialTheme.tokens,
        )
    mainWindow.contentPane = desktopMainPanel
    desktopUiCoordinator =
        DesktopUiCoordinator(
            DesktopPresentation(
                commands =
                    DesktopCommandPresentation(
                        muted = !properties.applicationSettings.audio.enabled,
                        audioVolume = properties.applicationSettings.audio.volume,
                        commandBarVisible =
                            properties.applicationSettings.desktop.commandBarVisible,
                        exactWindowScaleOne =
                            properties.applicationSettings.display.scalingMode ==
                                ApplicationSettings.DisplayScalingMode.EXPLICIT &&
                                properties.applicationSettings.display.explicitScale == 1,
                    ),
                persistentStatus =
                    "Ready",
                notice =
                    initialTheme.fallback?.let {
                      DesktopNotice(
                          "${it.unavailableAppearance.displayName} appearance was unavailable; " +
                              "System appearance is active for this launch.")
                    },
            ),
            desktopMainPanel::render,
        )
    desktopPlaybackState = DesktopPlaybackState(desktopUiCoordinator::paused)
    desktopUiCoordinator.publish()
    updateRecentRoms()
    eventBus.register<Controller.SessionPauseSupportEvent> { event ->
      dispatchSwingMutation { desktopUiCoordinator.pauseSupport(event.enabled) }
    }
    eventBus.register<Controller.SessionPlaybackStateEvent> { event ->
      dispatchSwingMutation { desktopPlaybackState.playbackChanged(event) }
    }
    eventBus.register<StateUxSessionEvent> { session ->
      dispatchSwingMutation {
        desktopUiCoordinator.stateAvailability(
            quick = session.available,
            browser = session.available || session.unavailableReason != null,
        )
      }
    }
    eventBus.register<Sound.SoundEnabledEvent> { event ->
      dispatchSwingMutation { desktopUiCoordinator.muted(!event.enabled()) }
    }
    eventBus.register<SwingDisplay.PresentationFrameRateEvent> { event ->
      dispatchSwingMutation {
        desktopUiCoordinator.presentedFramesPerSecond(event.framesPerSecond)
      }
    }
    eventBus.register<SwingDisplay.PresentationFrameRateResetEvent> {
      dispatchSwingMutation { desktopUiCoordinator.presentedFramesPerSecond(null) }
    }
    eventBus.register<DisplaySettingsChangedEvent> { event ->
      dispatchSwingMutation { desktopUiCoordinator.displaySettings(event.display) }
    }
    eventBus.register<RomLoadingEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        romLoading = true
        romLoadingRequestId = event.openRequestId
        desktopUiCoordinator.opening(event.rom.name)
      }
    }
    eventBus.register<EmulationStartedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        activeWindowTitle = "${event.romName} — Coffee GB"
        romLoading = false
        romLoadingRequestId = null
        romSessionState.markStarted()
        desktopPlaybackState.sessionStarted(event.sessionGeneration)
        mainWindow.title = activeWindowTitle
        desktopUiCoordinator.opened(event.romName, event.sessionGeneration)
      }
    }
    eventBus.register<Controller.SessionPresentationEvent> { event ->
      dispatchSwingMutation {
        desktopUiCoordinator.sessionMetadata(event.batterySaveActive, event.sessionGeneration)
      }
    }
    eventBus.register<LoadRomFailedEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        if (matchesLoadingRequest(event.openRequestId)) {
          romLoading = false
          romLoadingRequestId = null
          desktopUiCoordinator.openingFinished("Opening the game failed")
        }
      }
    }
    eventBus.register<RomLoadingCancelledEvent> { event ->
      dispatchAcceptedRomLifecycle(event.openRequestId, ::acceptRomLifecycle) {
        if (matchesLoadingRequest(event.openRequestId)) {
          romLoading = false
          romLoadingRequestId = null
          desktopUiCoordinator.openingFinished("Opening was cancelled")
        }
      }
    }
    eventBus.register<EmulationStoppedEvent> {
      dispatchAcceptedRomLifecycle(null, ::acceptRomLifecycle) {
        if (!romOpen.hasActiveRequest()) {
          // Fullscreen is a session presentation state. Leave it before revealing Home so the
          // runtime, persisted display choice, and shell selection cannot disagree while idle.
          if (displayController.isFullscreen()) {
            displayController.setFullscreen(false)
          }
          activeWindowTitle = "Coffee GB"
          romSessionState.markStopped()
          desktopPlaybackState.sessionStopped()
          if (!romLoading) {
            mainWindow.title = activeWindowTitle
            desktopUiCoordinator.stopped()
            // The unload autosave just committed its preview; refresh Home now rather than
            // waiting for another ROM-open or preference change.
            updateRecentRoms()
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

    installRomDropTarget { portableMenu?.visible() == true }
    mainWindow.pack()
    mainWindow.minimumSize =
        minimumFrameSize(
            emulator.minimumContentSizeForCurrentMode(
                windowed = !displayController.current().fullscreen),
            mainWindow.insets,
            mainWindow.jMenuBar?.preferredSize?.height ?: 0,
        )
    mainWindow.repaint()
    mainWindow.isResizable = true
    val legacyOuterSize =
        properties.applicationSettings.desktop.windowSize?.let {
          DesktopSize(it.width, it.height)
        }
    desktopUiStateController.restoreMainWindow(legacyOuterSize)
    // Claim native Quit only after every coordinated-shutdown dependency exists. Attaching before
    // installation also guarantees a callback can only enqueue, never run inside AppKit dispatch.
    desktopQuit.attach(::requestClose)
    installDesktopQuitHandler(desktopQuit::accept)
    mainWindow.isVisible = true
    displayController.applyCurrent()
    desktopUiStateController.install()
    properties.consumeLoadWarning()?.let { warning ->
      LOG.warn("Desktop settings loaded with a recoverable warning")
      desktopUiCoordinator.warning(warning.message)
    }
    if (console != null) {
      Thread(console).start()
    }
    if (initialRom != null) {
      romOpen.open(initialRom.toPath(), RomOpenSource.INITIAL_ARGUMENT)
    }
    requestDesktopStartupSmokeIfConfigured()
  }

  private fun installRomDropTarget(menuVisible: () -> Boolean) {
    val root = mainWindow.rootPane
    val dropEnabled = { !menuVisible() }
    dropFeedback = RomDropFeedback(root, dropEnabled)
    root.transferHandler =
        RomDropTransferHandler(
            submit = { inputs -> romOpen.open(inputs, RomOpenSource.DROP) },
            feedback = dropFeedback::update,
            enabled = dropEnabled,
        )
  }

  private fun updateRecentRoms() {
    if (::menu.isInitialized) menu.updateRecentRoms()
    if (::desktopMainPanel.isInitialized) {
      val paths = properties.recentRoms.getPaths()
      desktopMainPanel.updateRecentGames(paths.map(::DesktopRecentGame))
      val generation = recentGamePreviewGeneration.incrementAndGet()
      val saves = properties.applicationSettings.saves
      recentGamePreviewLoader.load(paths, saves) { games ->
        dispatchSwingMutation {
          if (generation == recentGamePreviewGeneration.get() &&
              ::desktopMainPanel.isInitialized) {
            desktopMainPanel.updateRecentGames(games)
          }
        }
      }
    }
  }

  private fun handleRomOpenUpdate(update: RomOpenUpdate) {
    if (!::desktopUiCoordinator.isInitialized) return
    when (update) {
      is RomOpenUpdate.Progress -> {
        romLoading = true
        romLoadingRequestId = update.requestId
        val target = update.path?.fileName?.toString() ?: "ROM"
        val stage =
            when (update.stage) {
              RomOpenStage.QUEUED -> "Preparing $target"
              RomOpenStage.SNAPSHOTTING -> "Reading $target"
              RomOpenStage.INSPECTING -> "Checking $target"
              RomOpenStage.AWAITING_ARCHIVE_SELECTION -> "Choose a game from $target"
              RomOpenStage.PREPARING_CORE -> "Starting $target"
              RomOpenStage.AWAITING_PERSISTENCE_DECISION ->
                  "Waiting for a save-before-replace decision"
            }
        desktopUiCoordinator.openingProgress("$stage…")
      }
      is RomOpenUpdate.Opened -> updateRecentRoms()
      is RomOpenUpdate.Cancelled -> {
        if (romLoadingRequestId == update.requestId) {
          romLoading = false
          romLoadingRequestId = null
          desktopUiCoordinator.openingFinished("Opening was cancelled")
        }
      }
      is RomOpenUpdate.Failed -> {
        if (romLoadingRequestId == update.requestId) {
          romLoading = false
          romLoadingRequestId = null
          desktopUiCoordinator.openingFinished("Opening the game failed")
        }
      }
    }
  }

  private fun setCommandBarVisible(visible: Boolean) {
    properties.updateApplicationSettings { current ->
      current.copy(desktop = current.desktop.copy(commandBarVisible = visible))
    }
    desktopUiCoordinator.commandBarVisible(visible)
  }

  private fun netplaySummary(presentation: NetplayUiPresentation): String =
      when (presentation.state.phase) {
        NetplayPhase.DISCONNECTED -> "Netplay: Off"
        NetplayPhase.STARTING_HOST -> "Netplay: Starting"
        NetplayPhase.WAITING_FOR_PEERS -> "Netplay: Hosting"
        NetplayPhase.CONNECTING -> "Netplay: Connecting"
        NetplayPhase.NEGOTIATING -> "Netplay: Synchronizing"
        NetplayPhase.ACTIVE -> "Netplay: Active"
        NetplayPhase.STOPPING -> "Netplay: Stopping"
        NetplayPhase.FAILED -> "Netplay: Failed"
      }

  private fun confirmNetplayPeripheralHandoff(
      selection: Controller.SerialPeripheralSelection,
  ): Boolean =
      desktopDialogFactory.showDecision(
              mainWindow,
              DesktopDecisionSpec(
                  title = "Use the link port for Netplay?",
                  heading = "Netplay needs the Game Boy link port",
                  message =
                      "${SerialPeripheralMenuBinding.label(selection)} will be detached while " +
                          "this netplay session is starting or active. Coffee GB will restore " +
                          "it when the session ends or the connection fails.",
                  buttons =
                      DesktopDialogButtons(
                          primary =
                              DesktopDialogAction(
                                  "Use Link Port",
                                  true,
                                  accessibleDescription =
                                      "Detach the current peripheral and continue to Netplay",
                              ),
                          cancel = DesktopDialogAction("Cancel", false),
                      ),
              ),
          )

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
          desktopDialogFactory.showDecision(
              mainWindow,
              DesktopDecisionSpec(
                  title = "Quit Coffee GB",
                  heading = if (running) "Quit and close the running game?" else "Quit Coffee GB?",
                  message =
                      if (running) {
                        "Coffee GB will finish required save work before closing the application."
                      } else {
                        "Close Coffee GB and all retained utility and debugger windows."
                      },
                  buttons =
                      DesktopDialogButtons(
                          primary =
                              DesktopDialogAction(
                                  "Quit Coffee GB",
                                  QuitDecision.QUIT,
                                  mnemonic = java.awt.event.KeyEvent.VK_Q,
                                  destructive = true,
                              ),
                          cancel =
                              DesktopDialogAction(
                                  if (running) "Keep playing" else "Cancel",
                                  QuitDecision.KEEP_OPEN,
                              ),
                          defaultButton = DesktopDialogDefaultButton.CANCEL,
                      ),
                  remember =
                      DesktopDecisionRememberOption(
                          results = setOf(QuitDecision.QUIT),
                          onSelected = {
                            properties.romChangeConfirmationPolicy =
                                ApplicationSettings.RomChangeConfirmationPolicy.NEVER
                          },
                      ),
                  modality = DesktopOwnedDialogModality.APPLICATION,
              )) == QuitDecision.QUIT
        }
    if (!proceed) {
      return
    }
    requestAutomatedClose()
  }

  private fun requestAutomatedClose() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Application close must be requested from the Event Dispatch Thread"
    }
    // The coordinator's watchdog owns the entire shutdown, including managed-state autosave.
    // Starting it here prevents a slow state writer from running outside the desktop deadline.
    if (shutdownCoordinator.request()) {
      updateLoadingUi("Coffee GB: Saving before quit…", true)
    }
  }

  private fun finishSuccessfulShutdown() {
    SwingUtilities.invokeLater {
      dropFeedback.close()
      fullscreenEscape.close()
      displayController.close()
      mainWindow.dispose()
      exitProcess(0)
    }
  }

  private fun showClosePersistenceFailure(
      failure: Controller.PersistenceBarrierException,
      retry: () -> Unit,
      cancel: () -> Unit,
  ) {
    SwingUtilities.invokeLater {
      val choice =
          desktopDialogFactory.showDecision(
              mainWindow,
              DesktopDecisionSpec(
                  title = "Save before quit failed",
                  heading = "Coffee GB could not safely save ${failure.fileName}",
                  message =
                      "The paused session and pending changes remain open. Retry the save" +
                          if (failure.closeAutosaveWaivable) {
                            ", close without a new autosave, or keep the session open."
                          } else {
                            " or keep the session open."
                          },
                  buttons =
                      DesktopDialogButtons(
                          primary =
                              DesktopDialogAction(
                                  "Retry save",
                                  ClosePersistenceDecision.RETRY,
                                  mnemonic = java.awt.event.KeyEvent.VK_R,
                              ),
                          secondary =
                              if (failure.closeAutosaveWaivable) {
                                listOf(
                                    DesktopDialogAction(
                                        "Close without autosave",
                                        ClosePersistenceDecision.CLOSE_WITHOUT_AUTOSAVE,
                                        destructive = true,
                                    ))
                              } else {
                                emptyList()
                              },
                          cancel =
                              DesktopDialogAction(
                                  "Keep paused session open",
                                  ClosePersistenceDecision.KEEP_OPEN,
                              ),
                          defaultButton = DesktopDialogDefaultButton.CANCEL,
                      ),
              ))
      when (choice) {
        ClosePersistenceDecision.RETRY -> {
          updateLoadingUi("Coffee GB: Retrying save before quit…", true)
          retry()
        }
        ClosePersistenceDecision.CLOSE_WITHOUT_AUTOSAVE -> {
          if (emulator.waiveCloseAutosave(failure.requestId)) {
            updateLoadingUi("Coffee GB: Closing without a new autosave…", true)
            retry()
          } else {
            cancel()
            pausedQuitRetryUi().let { updateLoadingUi(it.title, it.blocksInput) }
            desktopDialogFactory.showError(
                mainWindow,
                DesktopErrorSpec(
                    title = "Close choice expired",
                    summary = "The autosave attempt changed before it could be waived.",
                    recovery = "Close Coffee GB again to retry with the current save operation.",
                    buttons =
                        DesktopDialogButtons(
                            cancel = DesktopDialogAction("Keep open", Unit),
                        ),
                ))
          }
        }
        ClosePersistenceDecision.KEEP_OPEN -> {
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
      desktopDialogFactory.showError(
          mainWindow,
          DesktopErrorSpec(
              title = "Quit failed",
              summary = "Coffee GB did not finish shutting down.",
              recovery =
                  "The window is still open and ROM opening remains paused. Close again to retry.",
              sanitizedDetails = failure.javaClass.simpleName,
              buttons = DesktopDialogButtons(cancel = DesktopDialogAction("Keep open", Unit)),
          ))
    }
  }

  private fun showCloseTimeout() {
    LOG.error("Desktop shutdown exceeded {} ms", DESKTOP_SHUTDOWN_TIMEOUT_MILLIS)
    SwingUtilities.invokeLater {
      updateLoadingUi(activeWindowTitle, false)
      desktopDialogFactory.showError(
          mainWindow,
          DesktopErrorSpec(
              title = "Quit is taking too long",
              summary = "Coffee GB kept the window open instead of forcing an unsafe exit.",
              recovery =
                  "Shutdown work may still be unwinding. Wait, then close Coffee GB again to retry.",
              buttons = DesktopDialogButtons(cancel = DesktopDialogAction("Keep open", Unit)),
          ))
    }
  }

  private fun closeSettings() {
    // Let the coordinator retain the window and accept a later close retry. The settings store
    // keeps its latest dirty revision open after a timeout/failure, so swallowing this exception
    // here would turn an otherwise recoverable flush into silent data loss at process exit.
    properties.close()
  }

  /**
   * CI's packaged-desktop smoke reaches this point only after the production frame, menu,
   * renderer, audio/input adapters, controller, and native bootstrap have all been constructed.
   * Evidence is written off the EDT and the normal bounded shutdown path is then exercised.
   */
  private fun requestDesktopStartupSmokeIfConfigured() {
    val markerText = System.getenv(DESKTOP_SMOKE_MARKER_ENV)?.takeIf(String::isNotBlank) ?: return
    check(SwingUtilities.isEventDispatchThread()) {
      "Desktop startup smoke readiness must be observed on the Event Dispatch Thread"
    }
    check(mainWindow.isDisplayable && mainWindow.isVisible) {
      "Desktop startup smoke requires one visible displayable frame"
    }
    check(mainWindow.jMenuBar != null && mainWindow.contentPane.componentCount > 0) {
      "Desktop startup smoke requires the production menu and display content"
    }
    check(hasMobileAdapterDesktopControls(mainWindow.jMenuBar)) {
      "Desktop startup smoke requires the production Mobile Adapter controls"
    }
    val marker =
        try {
          Path.of(markerText).toAbsolutePath().normalize()
        } catch (failure: RuntimeException) {
          LOG.error("Desktop startup smoke marker is invalid", failure)
          exitProcess(1)
        }
    val evidence =
        "Coffee GB desktop ready OK: edt=true, visible=true, displayable=true, menu=true, " +
            "mobile-adapter=true\n"
    writeDesktopStartupEvidence(marker, evidence) { failure ->
      if (failure != null) {
        LOG.error("Unable to write desktop startup smoke evidence", failure)
        exitProcess(1)
      }
      SwingUtilities.invokeLater(::requestAutomatedClose)
    }
  }

  private fun showPreferences() {
    showPreferences(null)
  }

  private fun showPreferences(requestedCategory: PreferencesCategory?) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Preferences must be opened from the Event Dispatch Thread"
    }
    PreferencesDialog.show(
        owner = mainWindow,
        initial = properties.applicationSettings,
        gamepadCatalog = emulator.gamepadCatalog(),
        audioDevices = AudioDeviceProvider(emulator::audioDevices),
        persistence =
            if (properties.isReadOnly()) {
              PreferencesPersistencePresentation.sessionOnly(
                  "These settings are active for this session only because the settings file " +
                      "cannot be safely updated.")
            } else {
              PreferencesPersistencePresentation.PERSISTENT
            },
        initialCategory =
            requestedCategory
                ?: desktopUiStateController.lastPreferencesCategory().toPreferencesCategory(),
        initialBounds = desktopUiStateController.utilityBounds(DesktopUtilityWindow.PREFERENCES),
        mobileAdapterSummary = mobileAdapterWindow.currentSummary().preferencesText(),
        configureMobileAdapter = mobileAdapterWindow::showOrRaise,
        onCategoryChanged = { category ->
          desktopUiStateController.rememberPreferencesCategory(category.toDesktopCategory())
        },
        onBoundsChanged = { bounds ->
          desktopUiStateController.rememberUtilityBounds(
              DesktopUtilityWindow.PREFERENCES,
              bounds,
          )
        },
    ) { edit ->
      val previousAdvanced = properties.applicationSettings.advanced
      val previousDesktop = properties.applicationSettings.desktop
      properties.updateApplicationSettings(edit::applyTo)
      val applied = properties.applicationSettings
      applyPreferencesRuntime(previousAdvanced, previousDesktop, applied, edit)
    }
    updateRecentRoms()
  }

  private fun applyPreferencesRuntime(
      previousAdvanced: ApplicationSettings.Advanced,
      previousDesktop: ApplicationSettings.Desktop,
      applied: ApplicationSettings,
      edit: PreferencesEdit,
  ) {
    val failedEffects = mutableListOf<String>()
    fun applyEffect(label: String, effect: () -> Unit) {
      try {
        effect()
      } catch (failure: RuntimeException) {
        failedEffects += label
        LOG.error("Unable to apply the saved {} Preferences effect", label, failure)
      }
    }

    applyEffect("controls") {
      emulator.applyKeyboardMapping(applied.input.toPlayerMapping())
      desktopActions.applyShortcuts(
          DesktopShortcutRegistry(DesktopKeyboardKeyAdapter.keyCodes(applied.input.keyboard.values)))
    }
    applyEffect("audio and game controllers") { emulator.applyDeviceSettings(applied) }
    // Preferences can change the volume outside the portable overlay. Republish the applied
    // value so the overlay's cached command presentation (and its next +/- adjustment) starts
    // from the setting the user just applied.
    desktopUiCoordinator.audioVolume(applied.audio.volume)
    applyEffect("camera") { menu.applyCameraSettings(applied.peripherals) }
    applyEffect("display") {
      displayController.apply(
          applied.display,
          persist = false,
          forceWindowSize = edit.forceWindowSize,
      )
    }
    if (applied.desktop.appearance != previousDesktop.appearance) {
      applyEffect("appearance") {
        val application = themeManager.apply(applied.desktop.appearance.toDesktopAppearance())
        application.fallback?.let {
          desktopUiCoordinator.warning(
              "${it.unavailableAppearance.displayName} appearance is unavailable; " +
                  "System appearance is active for this launch.")
        }
      }
    }
    desktopUiCoordinator.commandBarVisible(applied.desktop.commandBarVisible)
    applyEffect("audio mute") {
      eventBus.post(Sound.SoundEnabledEvent(applied.audio.enabled))
    }
    applyEffect("save and rewind settings") {
      eventBus.post(Controller.UpdatedSavesSettingsEvent(applied.saves))
    }
    if (applied.advanced != previousAdvanced) {
      applyEffect("system mapping") { eventBus.post(Controller.UpdatedSystemMappingEvent()) }
    }
    updateRecentRoms()
    if (failedEffects.isNotEmpty()) {
      desktopUiCoordinator.warning(
          "Preferences were saved, but ${failedEffects.distinct().joinToString()} could not be " +
              "applied completely. Restart Coffee GB to retry.")
    }
  }

  private fun updateLoadingUi(title: String, loading: Boolean) {
    dispatchSwingMutation {
      mainWindow.title = activeWindowTitle
      if (::desktopUiCoordinator.isInitialized) {
        if (loading) {
          desktopUiCoordinator.savingBeforeQuit(
              title.removePrefix("Coffee GB: ").removeSuffix("…"))
        } else {
          desktopUiCoordinator.openingFinished()
        }
      }
      val cursor =
          Cursor.getPredefinedCursor(if (loading) Cursor.WAIT_CURSOR else Cursor.DEFAULT_CURSOR)
      mainWindow.cursor = cursor
      mainWindow.rootPane.cursor = cursor
      mainWindow.contentPane.cursor = cursor
    }
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(SwingGui::class.java)
    private const val DESKTOP_SMOKE_MARKER_ENV = "COFFEE_GB_DESKTOP_SMOKE_MARKER"

    fun run(
        debug: Boolean,
        initialRom: File?,
        settingsOverrides: ApplicationSettingsOverrides = ApplicationSettingsOverrides(),
    ) {
      val desktopOpenFiles = DesktopOpenFilesBridge()
      prepareDesktopLaunch(
          desktopOpenFiles,
          ::installDesktopOpenFileHandler,
          NativeRuntimeBootstrap::bootstrapFromSystem,
      )
      // Loading, validating, migrating, and recovering the settings file can touch the disk. Do
      // that on the calling launcher thread before entering Swing's Event Dispatch Thread.
      val properties = EmulatorProperties(settingsOverrides)
      val themeManager = DesktopThemeManager()
      val initialTheme =
          themeManager.apply(properties.applicationSettings.desktop.appearance.toDesktopAppearance())
      val desktopUiStateStore = DesktopUiStateStore()
      val initialDesktopUiState = desktopUiStateStore.load()
      val mobileConfigurationStore =
          MobileAdapterConfigurationStore(MobileAdapterConfigurationStore.defaultPath())
      val mobileConfigurationResult = mobileConfigurationStore.load()
      mobileConfigurationResult.error?.let { error ->
        LOG.warn("Mobile Adapter configuration load used a safe fallback ({})", error.code)
      }
      val mobileAdapterConfigurationUiState =
          MobileAdapterConfigurationUiState.from(mobileConfigurationResult)
      val mobileAdapterConfiguration =
          MobileAdapterConfigurationCoordinator(
              mobileConfigurationResult.configuration,
              mobileConfigurationStore,
          )
      val jvmShutdown =
          DesktopJvmShutdownCoordinator(
              fallback = {
                mobileAdapterConfiguration.close()
                properties.close()
              },
              timeoutMillis = DESKTOP_SHUTDOWN_TIMEOUT_MILLIS,
          ) { failure ->
            LOG.error("Unable to complete bounded desktop JVM shutdown", failure)
          }
      Runtime.getRuntime().addShutdownHook(jvmShutdown.createHook())
      SwingUtilities.invokeLater {
        SwingGui(
                debug,
                initialRom,
                properties,
                mobileAdapterConfiguration,
                mobileAdapterConfigurationUiState,
                desktopOpenFiles,
                jvmShutdown,
                themeManager,
                initialTheme,
                desktopUiStateStore,
                initialDesktopUiState,
            )
            .startGui()
      }
    }
  }
}

/** Keeps Proposal 3 construction and its input capture out of the default desktop startup path. */
internal fun <T> installDesktopProposal3Menu(enabled: Boolean, install: () -> T): T? =
    if (enabled) install() else null

internal fun ApplicationSettings.Appearance.toDesktopAppearance(): DesktopAppearance =
    when (this) {
      ApplicationSettings.Appearance.LIGHT -> DesktopAppearance.LIGHT
      ApplicationSettings.Appearance.DARK -> DesktopAppearance.DARK
      ApplicationSettings.Appearance.SYSTEM -> DesktopAppearance.SYSTEM
    }

internal fun DesktopPreferencesCategory.toPreferencesCategory(): PreferencesCategory =
    when (this) {
      DesktopPreferencesCategory.GENERAL -> PreferencesCategory.GENERAL
      DesktopPreferencesCategory.DISPLAY -> PreferencesCategory.DISPLAY
      DesktopPreferencesCategory.AUDIO -> PreferencesCategory.AUDIO
      DesktopPreferencesCategory.CONTROLS -> PreferencesCategory.CONTROLS
      DesktopPreferencesCategory.SAVES_AND_REWIND -> PreferencesCategory.SAVES_AND_REWIND
      DesktopPreferencesCategory.SYSTEM -> PreferencesCategory.SYSTEM
      DesktopPreferencesCategory.PERIPHERALS -> PreferencesCategory.PERIPHERALS
    }

internal fun PreferencesCategory.toDesktopCategory(): DesktopPreferencesCategory =
    when (this) {
      PreferencesCategory.GENERAL -> DesktopPreferencesCategory.GENERAL
      PreferencesCategory.DISPLAY -> DesktopPreferencesCategory.DISPLAY
      PreferencesCategory.AUDIO -> DesktopPreferencesCategory.AUDIO
      PreferencesCategory.CONTROLS -> DesktopPreferencesCategory.CONTROLS
      PreferencesCategory.SAVES_AND_REWIND -> DesktopPreferencesCategory.SAVES_AND_REWIND
      PreferencesCategory.SYSTEM -> DesktopPreferencesCategory.SYSTEM
      PreferencesCategory.PERIPHERALS -> DesktopPreferencesCategory.PERIPHERALS
    }

/**
 * Opens the platform file delivery gate before native extraction can delay startup.
 *
 * Both operations deliberately run on the caller's launcher thread: native bootstrap must finish
 * before settings construct gamepad backends or the EDT constructs camera UI, while an OS
 * open-file callbacks received during bootstrap are retained by [DesktopOpenFilesBridge].
 */
internal fun prepareDesktopLaunch(
    desktopOpenFiles: DesktopOpenFilesBridge,
    installOpenFileHandler: ((List<java.nio.file.Path>) -> Unit) -> Boolean,
    nativeBootstrap: () -> Unit,
) {
  installOpenFileHandler(desktopOpenFiles::accept)
  nativeBootstrap()
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

internal fun writeDesktopStartupEvidence(
    marker: Path,
    evidence: String,
    completed: (Exception?) -> Unit,
): Thread {
  require(evidence.isNotBlank()) { "Desktop startup smoke evidence must not be blank" }
  val worker =
      Thread(
          {
            val failure =
                runCatching {
                      val parent =
                          checkNotNull(marker.parent) {
                            "Desktop startup smoke marker must have a parent"
                          }
                      check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                        "Desktop startup smoke marker parent is not a directory"
                      }
                      check(!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                        "Desktop startup smoke marker already exists"
                      }
                      Files.newByteChannel(
                              marker,
                              setOf(
                                  StandardOpenOption.CREATE_NEW,
                                  StandardOpenOption.WRITE,
                                  LinkOption.NOFOLLOW_LINKS,
                              ),
                          )
                          .use { channel ->
                            val bytes = StandardCharsets.UTF_8.encode(evidence)
                            while (bytes.hasRemaining()) {
                              channel.write(bytes)
                            }
                          }
                    }
                    .exceptionOrNull()
                    ?.let {
                      if (it is Exception) it else IllegalStateException(it)
                    }
            completed(failure)
          },
          "coffee-gb-desktop-smoke-evidence",
      )
  worker.isDaemon = false
  worker.start()
  return worker
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

internal fun stopEmulatorBeforeCamera(
    stopEmulator: () -> Unit,
    cameraAfterStop: (() -> Unit)?,
) {
  stopEmulator()
  cameraAfterStop?.invoke()
}

/** Keeps window-size observation recoverable until the settings store has closed successfully. */
internal fun closeDesktopSettingsRecoverably(
    suspendWindowSize: () -> Unit,
    closeSettings: () -> Unit,
    resumeWindowSize: () -> Unit,
    finishWindowSize: () -> Unit,
) {
  suspendWindowSize()
  try {
    closeSettings()
  } catch (failure: Exception) {
    try {
      resumeWindowSize()
    } catch (resumeFailure: Exception) {
      failure.addSuppressed(resumeFailure)
    }
    throw failure
  }
  finishWindowSize()
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

/** Runs UI-only shutdown work synchronously while retaining the coordinator's failure boundary. */
internal fun runDesktopEdtStep(step: () -> Unit) {
  if (SwingUtilities.isEventDispatchThread()) {
    step()
    return
  }

  val state = AtomicReference(DesktopEdtStepState.QUEUED)
  val completed = CountDownLatch(1)
  val failure = AtomicReference<Throwable?>()
  SwingUtilities.invokeLater {
    if (!state.compareAndSet(DesktopEdtStepState.QUEUED, DesktopEdtStepState.RUNNING)) {
      completed.countDown()
      return@invokeLater
    }
    try {
      step()
    } catch (problem: Throwable) {
      failure.set(problem)
    } finally {
      state.set(DesktopEdtStepState.COMPLETED)
      completed.countDown()
    }
  }

  try {
    completed.await()
  } catch (interrupted: InterruptedException) {
    if (!state.compareAndSet(DesktopEdtStepState.QUEUED, DesktopEdtStepState.CANCELLED)) {
      // Once the EDT owns the step, wait for its result so the coordinator never races work that
      // has already become irreversible. Further interrupts remain represented by the original.
      while (completed.count != 0L) {
        try {
          completed.await()
        } catch (_: InterruptedException) {}
      }
      failure.get()?.let(interrupted::addSuppressed)
    }
    Thread.currentThread().interrupt()
    throw interrupted
  }

  failure.get()?.let { throw it }
}

private enum class DesktopEdtStepState {
  QUEUED,
  RUNNING,
  CANCELLED,
  COMPLETED,
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
