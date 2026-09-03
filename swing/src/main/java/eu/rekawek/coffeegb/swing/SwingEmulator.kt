package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationSink
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.swing.io.AudioDeviceCatalog
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.AudioOutputStatus
import eu.rekawek.coffeegb.swing.io.AudioRuntimeConfiguration
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.DesktopAutofireInput
import eu.rekawek.coffeegb.swing.io.DesktopPlayerInput
import eu.rekawek.coffeegb.swing.io.DesktopTiltInput
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import eu.rekawek.coffeegb.swing.io.GamepadConfiguration
import eu.rekawek.coffeegb.swing.io.SwingAccelerometer
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.SwingGamepad
import eu.rekawek.coffeegb.swing.io.SwingJoypad
import eu.rekawek.coffeegb.swing.io.SwingTiltKeys
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

fun interface SwingAudioOutputFactory {
  fun create(
      configuration: AudioRuntimeConfiguration,
      eventBus: EventBus,
      callerId: String,
  ): AudioSystemSound
}

/** Synchronous boundary emitted before an old controller is closed and replaced. */
internal class ControllerOwnershipChangingEvent : Event

/** Emitted only after replacement-controller ownership has been installed successfully. */
internal class ControllerOwnershipCommittedEvent : Event

/** Idempotently returns serial-port ownership from a linked controller to a basic controller. */
internal class EnsureStandaloneControllerEvent : Event

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    private val properties: EmulatorProperties,
    private val mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider =
        Controller.MobileAdapterConfigurationProvider {
          Controller.MobileAdapterConfiguration.syntheticOffline()
        },
    private val mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink =
        MobileAdapterGuestConfigurationSink.NO_OP,
    private val audioOutputFactory: SwingAudioOutputFactory =
        SwingAudioOutputFactory { configuration, bus, callerId ->
          AudioSystemSound(configuration, bus, callerId) {}
        },
) {
  /** Adds transient startup policy without changing the published primary constructor ABI. */
  internal constructor(
      eventBus: EventBus,
      console: Console?,
      properties: EmulatorProperties,
      mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider,
      mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink,
      startMuted: Boolean,
  ) : this(
      eventBus,
      console,
      properties,
      mobileAdapterConfigurationProvider,
      mobileAdapterGuestConfigurationSink,
      startupAudioOutputFactory(startMuted),
  )

  private val display: SwingDisplay
  private val joypad: SwingJoypad
  private val autofireInput: DesktopAutofireInput
  private val gamepad: SwingGamepad
  private val gamepadThread: Thread
  private val sound: AudioSystemSound
  private val audioDeviceCatalog = AudioDeviceCatalog()
  private val accelerometer: SwingAccelerometer

  private val playerInput: DesktopPlayerInput

  private val tiltInput: DesktopTiltInput

  private val tiltKeys: SwingTiltKeys

  private val printer: SwingPrinter

  private val connectionController: ConnectionController

  private lateinit var controller: Controller

  private var boundFrame: JFrame? = null

  private var boundPanel: JPanel? = null

  private var inputRouter: DesktopInputRouter? = null

  private var portableMenu: SwingProposal3Menu? = null

  private var preferredSizeChangedWhileFullscreen = false

  private val controllerLifecycle = ControllerLifecycleGate()

  @Volatile private var linkedControllerActive = false

  init {
    display = SwingDisplay(properties.display, eventBus, "main")
    sound =
        audioOutputFactory.create(
            properties.applicationSettings.audio.toRuntimeConfiguration(),
            eventBus,
            "main",
        )
    playerInput = DesktopPlayerInput(properties.playerInputSource, eventBus)
    autofireInput = DesktopAutofireInput(playerInput, eventBus, "main")
    tiltInput = DesktopTiltInput(eventBus)
    joypad = SwingJoypad(properties.playerInputMapping, eventBus, playerInput, autofireInput)
    gamepad =
        SwingGamepad(
            properties.applicationSettings.toGamepadConfiguration(),
            playerInput,
            tiltInput,
            eventBus,
            autofireInput,
        )
    accelerometer = SwingAccelerometer(eventBus, tiltInput, display.preferredSize)
    tiltKeys = SwingTiltKeys(tiltInput)
    printer = SwingPrinter(eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    sound.start()
    gamepadThread = Thread(gamepad, "gamepad").apply { isDaemon = true; start() }

    controller =
        BasicController(
                eventBus,
                properties,
                console,
                DesktopStateExternalActions(),
                mobileAdapterConfigurationProvider,
                ::captureDisplayImage,
                mobileAdapterGuestConfigurationSink,
            )
            .also { it.startController() }

    eventBus.register<ConnectionController.ServerGotConnectionEvent> {
      controllerLifecycle.transitionIfActive { startLinkedController(it.mode, it.player) }
    }
    eventBus.register<ConnectionController.ClientConnectedToServerEvent> {
      controllerLifecycle.transitionIfActive { startLinkedController(it.mode, it.player) }
    }
    eventBus.register<ConnectionController.ServerLostConnectionEvent> {
      returnToBasicControllerIfLinked()
    }
    eventBus.register<ConnectionController.StopServerEvent> {
      returnToBasicControllerIfLinked()
    }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      returnToBasicControllerIfLinked()
    }
    eventBus.register<EnsureStandaloneControllerEvent> {
      returnToBasicControllerIfLinked()
    }
    eventBus.register<Controller.RomLoadingEvent> { releaseForLifecycleChange() }
    eventBus.register<Controller.EmulationStoppedEvent> { releaseForLifecycleChange() }
  }

  private fun startBasicController() {
    eventBus.post(ControllerOwnershipChangingEvent())
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller =
        BasicController(
                eventBus,
                properties,
                console,
                DesktopStateExternalActions(),
                mobileAdapterConfigurationProvider,
                ::captureDisplayImage,
                mobileAdapterGuestConfigurationSink,
            )
            .also { it.startController() }
    linkedControllerActive = false
    eventBus.post(ControllerOwnershipCommittedEvent())
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.image, state.state))
    }
  }

  private fun returnToBasicControllerIfLinked() {
    // STOP_CLIENT can synchronously install the standalone controller and still be followed by a
    // delayed ClientDisconnected event. Re-check linked ownership while holding the same lifecycle
    // lock as the transition so that stale/duplicate callbacks cannot replace the new controller.
    controllerLifecycle.transitionIfActiveWhen(
        condition = { linkedControllerActive },
        transition = ::startBasicController,
    )
  }

  private fun startLinkedController(mode: LinkMode, player: Int) {
    eventBus.post(ControllerOwnershipChangingEvent())
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller =
        LinkedController(eventBus, properties, console, mode, player).also { it.startController() }
    linkedControllerActive = true
    eventBus.post(ControllerOwnershipCommittedEvent())
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.image, state.state))
    }
  }

  fun stop() {
    controllerLifecycle.stop(
        releaseControllerOwnership = {
          // Host inputs and rumble are released before the core can close its bus. A persistence
          // failure retains both the paused controller and this shutdown gate for an explicit
          // stop retry; network callbacks must not replace a controller that has begun teardown.
          closeControllerAfterLifecycleRelease(::releaseForLifecycleChange, controller::close)
        },
        finishTeardown = {
          portableMenu?.closeForLifecycle()
          portableMenu = null
          playerInput.setMenuCapture(null)
          inputRouter?.close()
          inputRouter = null
          eventBus.post(ConnectionController.StopServerEvent())
          eventBus.post(ConnectionController.StopClientEvent())
          joypad.stop()
          tiltInput.stop()
          gamepad.stop()
          gamepadThread.interrupt()
          gamepadThread.join(1000)
          sound.stopThread()
          display.stop()
          printer.close()
        },
    )
  }

  internal fun isLinkedControllerActive(): Boolean = linkedControllerActive

  /** Installs the portable Proposal 3 host after desktop actions and native dialogs exist. */
  internal fun installPortableMenu(
      commands: PortableMenuCommandBridge,
      onVisibilityChanged: (Boolean) -> Unit = {},
  ): SwingProposal3Menu {
    check(portableMenu == null) { "The portable menu is already installed" }
    val installedMenu =
        SwingProposal3Menu(
            frameSink = { frame ->
              if (frame == null) display.clearMenuOverlay() else display.setMenuOverlay(frame)
            },
            commands = commands,
            capturePausePreview = { display.captureMenuPreview() },
            onVisibilityChanged = onVisibilityChanged,
            releaseGameplay = {
              joypad.releaseForLifecycleChange()
              tiltInput.releaseForLifecycleChange()
              gamepad.releaseForLifecycleChange()
              inputRouter?.releaseForOwnershipChange()
              playerInput.releaseAll()
            },
            printer =
                object : PortableMenuPrinterBridge {
                  override fun hasPaper() = printer.hasPaper()

                  override fun paperPreview() = printer.menuPreview()

                  override fun open() = printer.showWindow()

                  override fun clear() = printer.clearFromPortableMenu()

                  override fun export() = printer.exportFromPortableMenu()
                },
            )
    portableMenu = installedMenu
    playerInput.setMenuCapture(portableMenu)
    return installedMenu
  }

  internal fun openPortableMenu() {
    portableMenu?.openFromDesktop()
  }

  internal fun togglePortableMenu() {
    portableMenu?.toggleFromDesktop()
  }

  internal fun attachPrinterWindow(owner: java.awt.Window, bounds: PrinterWindowBounds) {
    printer.attachDesktopWindow(owner, bounds)
  }

  /**
   * Applies an explicit close-autosave waiver to the controller retained by a failed stop. The
   * lifecycle gate permits this narrow operation while stopping, but still rejects it once
   * controller ownership has been released.
   */
  fun waiveCloseAutosave(requestId: Long): Boolean =
      controllerLifecycle.withRetainedController {
        controller.waiveCloseAutosave(requestId)
      } ?: false

  fun applyKeyboardMapping(mapping: ControllerProperties.PlayerMapping) {
    inputRouter?.releaseForOwnershipChange()
    joypad.updateMapping(mapping)
  }

  fun applyDeviceSettings(settings: ApplicationSettings) {
    gamepad.updateConfiguration(settings.toGamepadConfiguration())
    sound.applyConfiguration(settings.audio.toRuntimeConfiguration())
  }

  /** Reconfigures devices/volume while retaining an explicit transient mute or unmute. */
  internal fun applyDeviceSettingsPreservingMute(settings: ApplicationSettings) {
    gamepad.updateConfiguration(settings.toGamepadConfiguration())
    sound.applyConfiguration(
        settings.audio.toRuntimeConfigurationPreservingMute(sound.currentConfiguration()))
  }

  fun gamepadCatalog(): GamepadCatalog = gamepad.catalog()

  fun audioDevices(): List<AudioDeviceSnapshot> = audioDeviceCatalog.snapshot()

  fun audioStatus(): AudioOutputStatus = sound.currentStatus()

  fun captureDisplayImage(): StateImage = display.captureStateImage()

  private fun releaseForLifecycleChange() {
    portableMenu?.closeForLifecycle()
    joypad.releaseForLifecycleChange()
    tiltInput.releaseForLifecycleChange()
    gamepad.releaseForLifecycleChange()
    display.releaseForLifecycleChange()
  }

  fun minimumContentSize(): Dimension = Dimension(MINIMUM_CONTENT_WIDTH, MINIMUM_CONTENT_HEIGHT)

  fun minimumContentSizeForCurrentMode(windowed: Boolean): Dimension =
      minimumDisplayContentSize(display.scaleMode, display.preferredSize, windowed)

  fun bind(
      jFrame: JFrame,
      isWindowedLayout: () -> Boolean = { true },
  ): JPanel {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.minimumSize = minimumContentSize()
    display.minimumSize = minimumContentSize()
    mainPanel.add(display)
    display.addMouseMotionListener(accelerometer)
    boundFrame = jFrame
    boundPanel = mainPanel

    jFrame.contentPane = mainPanel
    jFrame.addWindowFocusListener(joypad)
    jFrame.addWindowFocusListener(tiltInput)
    jFrame.addMouseMotionListener(accelerometer)
    check(inputRouter == null) { "The emulator input router is already installed" }
    inputRouter =
        DesktopInputRouter(
                jFrame,
                joypadHandles = joypad::handlesKeyCode,
                joypadPressed = joypad::keyPressed,
                joypadReleased = joypad::keyReleased,
                tiltHandles = tiltKeys::handlesKeyCode,
                tiltPressed = tiltKeys::keyPressed,
                tiltReleased = tiltKeys::keyReleased,
                releaseAll = {
                  joypad.releaseForLifecycleChange()
                  tiltInput.releaseForLifecycleChange()
                },
                portableMenu = portableMenu,
                menuKeyForKeyCode = joypad::menuKeyForKeyCode,
            )
            .also(DesktopInputRouter::install)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      check(SwingUtilities.isEventDispatchThread()) {
        "Display window sizing must run on the Event Dispatch Thread"
      }
      val windowed = isWindowedLayout()
      if (!windowed) {
        preferredSizeChangedWhileFullscreen = true
      }
      applyDisplayWindowSizing(
          jFrame,
          mainPanel,
          it.preferredSize,
          windowed,
          forceExplicitPack = true,
      )
      if (windowed) {
        preferredSizeChangedWhileFullscreen = false
      }
    }
    return mainPanel
  }

  /** Refreshes top-level constraints at a fullscreen boundary even when geometry did not change. */
  fun refreshDisplayWindowSizing(
      windowed: Boolean,
      forceExplicitPack: Boolean,
  ) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Display window sizing must run on the Event Dispatch Thread"
    }
    val frame = checkNotNull(boundFrame) { "The emulator display is not bound to a window" }
    val panel = checkNotNull(boundPanel) { "The emulator display is not bound to a panel" }
    val preferred = display.preferredSize
    val currentContent =
        Dimension(
            (frame.width - frame.insets.left - frame.insets.right).coerceAtLeast(0),
            (frame.height -
                    frame.insets.top -
                    frame.insets.bottom -
                    (frame.jMenuBar?.height ?: 0))
                .coerceAtLeast(0),
        )
    applyDisplayWindowSizing(
        frame,
        panel,
        preferred,
        windowed,
        forceExplicitPack =
            shouldPackExplicitWindow(
                display.scaleMode,
                preferred,
                currentContent,
                windowed,
                forceRequested = forceExplicitPack,
                preferredSizeChangedWhileFullscreen = preferredSizeChangedWhileFullscreen,
            ),
    )
    if (windowed) {
      preferredSizeChangedWhileFullscreen = false
    }
  }

  private fun applyDisplayWindowSizing(
      frame: JFrame,
      panel: JPanel,
      preferred: Dimension,
      windowed: Boolean,
      forceExplicitPack: Boolean,
  ) {
    panel.preferredSize = Dimension(preferred)
    val minimum = minimumDisplayContentSize(display.scaleMode, preferred, windowed)
    panel.minimumSize = minimum
    display.minimumSize = minimum
    if (frame.isDisplayable) {
      frame.minimumSize =
          minimumFrameSize(
              minimum,
              frame.insets,
              frame.jMenuBar?.preferredSize?.height ?: 0,
          )
    }
    // Setting preferredSize doesn't invalidate, and a pack() that leaves the frame the same size
    // never triggers the reshape that refreshes the window's cached preferred size. Invalidating
    // up from the panel makes every later pack recompute it.
    panel.invalidate()
    if (forceExplicitPack && display.scaleMode.isExplicit && windowed) {
      frame.pack()
    } else {
      panel.revalidate()
      frame.validate()
    }
  }

  private companion object {
    const val MINIMUM_CONTENT_WIDTH = 160
    const val MINIMUM_CONTENT_HEIGHT = 144
  }
}

/** Every display mode remains resizable down to one native Game Boy frame. */
internal fun minimumDisplayContentSize(
    scaleMode: DisplayScaleMode,
    preferredSize: Dimension,
    windowed: Boolean,
): Dimension {
  require(preferredSize.width > 0 && preferredSize.height > 0) {
    "Preferred display size must be positive"
  }
  val base = Dimension(160, 144)
  return base
}

internal fun shouldPackExplicitWindow(
    scaleMode: DisplayScaleMode,
    preferredSize: Dimension,
    currentContentSize: Dimension,
    windowed: Boolean,
    forceRequested: Boolean = false,
    preferredSizeChangedWhileFullscreen: Boolean = false,
): Boolean {
  require(preferredSize.width > 0 && preferredSize.height > 0) {
    "Preferred display size must be positive"
  }
  require(currentContentSize.width >= 0 && currentContentSize.height >= 0) {
    "Current display content size must not be negative"
  }
  return windowed &&
      scaleMode.isExplicit &&
      (forceRequested || preferredSizeChangedWhileFullscreen)
}

internal fun closeControllerAfterLifecycleRelease(
    release: () -> Unit,
    close: () -> Unit,
) {
  release()
  close()
}

/**
 * Serializes every controller ownership transition with application shutdown. The lifecycle flags
 * are read and changed only while holding [lock], so a network callback cannot pass a stale
 * pre-stop check and install a controller after teardown has started.
 */
internal class ControllerLifecycleGate {
  private val lock = Any()
  private var stopping = false
  private var controllerOwnershipReleased = false
  private var stopped = false

  fun transitionIfActive(transition: () -> Unit): Boolean =
      synchronized(lock) {
        if (stopping || stopped) {
          return@synchronized false
        }
        transition()
        true
      }

  fun transitionIfActiveWhen(
      condition: () -> Boolean,
      transition: () -> Unit,
  ): Boolean =
      synchronized(lock) {
        if (stopping || stopped || !condition()) {
          return@synchronized false
        }
        transition()
        true
      }

  fun <T> withRetainedController(action: () -> T): T? =
      synchronized(lock) {
        if (stopped || controllerOwnershipReleased) {
          null
        } else {
          action()
        }
      }

  fun stop(
      releaseControllerOwnership: () -> Unit,
      finishTeardown: () -> Unit,
  ): Boolean =
      synchronized(lock) {
        if (stopped) {
          return@synchronized false
        }
        stopping = true
        if (!controllerOwnershipReleased) {
          try {
            releaseControllerOwnership()
            controllerOwnershipReleased = true
          } catch (failure: Exception) {
            // A persistence barrier retains the original controller for stop retry, while an
            // event-bus timeout may have partially gated its descendants. Neither state is safe
            // for a network callback to replace, so stopping intentionally remains true.
            throw failure
          }
        }
        // Peripheral teardown may also be retried, but controller ownership is never released
        // twice after the first successful close.
        finishTeardown()
        stopped = true
        true
      }
}

internal fun ApplicationSettings.toGamepadConfiguration(): GamepadConfiguration =
    GamepadConfiguration(
        input.toPlayerMapping().gamepads,
        input.gamepadTunings.mapValues { (_, tuning) ->
          GamepadConfiguration.Tuning(
              tuning.movementDeadZone,
              tuning.tiltDeadZone,
              tuning.invertMovementX,
              tuning.invertMovementY,
              tuning.invertTiltX,
              tuning.invertTiltY,
          )
        },
    )

internal fun ApplicationSettings.Audio.toRuntimeConfiguration(): AudioRuntimeConfiguration =
    AudioRuntimeConfiguration(
        when (val selection = output) {
          ApplicationSettings.AudioOutputSelection.Default -> AudioDeviceSnapshot.SYSTEM_DEFAULT_ID
          is ApplicationSettings.AudioOutputSelection.Device -> selection.stableId
        },
        volume,
        !enabled,
        AudioRuntimeConfiguration.LatencyPreset.valueOf(latency.name),
    )

internal fun startupAudioMuted(audio: ApplicationSettings.Audio, startMuted: Boolean): Boolean =
    startMuted || !audio.enabled

private fun startupAudioOutputFactory(startMuted: Boolean): SwingAudioOutputFactory =
    SwingAudioOutputFactory { configuration, eventBus, callerId ->
      AudioSystemSound(
          startupAudioRuntimeConfiguration(configuration, startMuted),
          eventBus,
          callerId,
      ) {}
    }

internal fun startupAudioRuntimeConfiguration(
    configured: AudioRuntimeConfiguration,
    startMuted: Boolean,
): AudioRuntimeConfiguration = configured.withMuted(startMuted || configured.muted())

/** Device, volume, and latency edits must not silently undo a process-local mute. */
internal fun ApplicationSettings.Audio.toRuntimeConfigurationPreservingMute(
    current: AudioRuntimeConfiguration,
): AudioRuntimeConfiguration = toRuntimeConfiguration().withMuted(current.muted())
