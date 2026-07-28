package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.swing.io.AudioDeviceCatalog
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.AudioOutputStatus
import eu.rekawek.coffeegb.swing.io.AudioRuntimeConfiguration
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    private val properties: EmulatorProperties,
) {
  private val display: SwingDisplay
  private val joypad: SwingJoypad
  private val gamepad: SwingGamepad
  private val gamepadThread: Thread
  private val sound: AudioSystemSound
  private val audioDeviceCatalog = AudioDeviceCatalog()
  private val accelerometer: SwingAccelerometer

  private val tiltInput: DesktopTiltInput

  private val tiltKeys: SwingTiltKeys

  private val printer: SwingPrinter

  private val connectionController: ConnectionController

  private lateinit var controller: Controller

  private var boundFrame: JFrame? = null

  private var boundPanel: JPanel? = null

  private val stopping = AtomicBoolean()

  private val stopped = AtomicBoolean()

  init {
    display = SwingDisplay(properties.display, eventBus, "main")
    sound =
        AudioSystemSound(
            properties.applicationSettings.audio.toRuntimeConfiguration(),
            eventBus,
            "main",
        ) {}
    val playerInput = DesktopPlayerInput(properties.playerInputSource, eventBus)
    tiltInput = DesktopTiltInput(eventBus)
    joypad = SwingJoypad(properties.playerInputMapping, eventBus, playerInput)
    gamepad = SwingGamepad(properties.playerInputMapping, playerInput, tiltInput, eventBus)
    gamepad.updateConfiguration(properties.applicationSettings.toGamepadConfiguration())
    accelerometer = SwingAccelerometer(eventBus, tiltInput, display.preferredSize)
    tiltKeys = SwingTiltKeys(tiltInput)
    printer = SwingPrinter(eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    sound.start()
    gamepadThread = Thread(gamepad, "gamepad").apply { isDaemon = true; start() }

    controller = BasicController(eventBus, properties, console).also { it.startController() }

    eventBus.register<ConnectionController.ServerGotConnectionEvent> {
      startLinkedController(it.mode, it.player)
    }
    eventBus.register<ConnectionController.ClientConnectedToServerEvent> {
      startLinkedController(it.mode, it.player)
    }
    eventBus.register<ConnectionController.ServerLostConnectionEvent> {
      if (!stopping.get()) {
        startBasicController()
      }
    }
    eventBus.register<ConnectionController.StopServerEvent> {
      if (!stopping.get()) {
        startBasicController()
      }
    }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      if (!stopping.get()) {
        startBasicController()
      }
    }
    eventBus.register<Controller.RomLoadingEvent> { releaseForLifecycleChange() }
    eventBus.register<Controller.EmulationStoppedEvent> { releaseForLifecycleChange() }
  }

  private fun startBasicController() {
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller = BasicController(eventBus, properties, console).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.image, state.state))
    }
  }

  private fun startLinkedController(mode: LinkMode, player: Int) {
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller =
        LinkedController(eventBus, properties, console, mode, player).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.image, state.state))
    }
  }

  @Synchronized
  fun stop() {
    if (stopped.get()) {
      return
    }
    // Gate disconnect callbacks before close so they cannot replace the controller halfway
    // through its persistence transaction. Host inputs and rumble are released before the core
    // can close its bus; a failed close clears the gate while BasicController retains the paused
    // capture and the same peripheral owners for a later retry.
    stopping.set(true)
    try {
      closeControllerAfterLifecycleRelease(::releaseForLifecycleChange, controller::close)
    } catch (failure: Exception) {
      stopping.set(false)
      throw failure
    }
    eventBus.post(ConnectionController.StopServerEvent())
    eventBus.post(ConnectionController.StopClientEvent())
    joypad.stop()
    tiltInput.stop()
    gamepad.stop()
    gamepadThread.interrupt()
    gamepadThread.join(1000)
    sound.stopThread()
    display.stop()
    stopped.set(true)
  }

  fun applyKeyboardMapping(mapping: ControllerProperties.PlayerMapping) {
    joypad.updateMapping(mapping)
  }

  fun applyDeviceSettings(settings: ApplicationSettings) {
    gamepad.updateConfiguration(settings.toGamepadConfiguration())
    sound.applyConfiguration(settings.audio.toRuntimeConfiguration())
  }

  fun gamepadCatalog(): GamepadCatalog = gamepad.catalog()

  fun audioDevices(): List<AudioDeviceSnapshot> = audioDeviceCatalog.snapshot()

  fun audioStatus(): AudioOutputStatus = sound.currentStatus()

  private fun releaseForLifecycleChange() {
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
  ) {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.minimumSize = minimumContentSize()
    display.minimumSize = minimumContentSize()
    mainPanel.add(display)
    display.addMouseMotionListener(accelerometer)
    boundFrame = jFrame
    boundPanel = mainPanel

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(joypad)
    jFrame.addWindowFocusListener(joypad)
    jFrame.addWindowFocusListener(tiltInput)
    jFrame.addKeyListener(tiltKeys)
    jFrame.addMouseMotionListener(accelerometer)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      check(SwingUtilities.isEventDispatchThread()) {
        "Display window sizing must run on the Event Dispatch Thread"
      }
      val windowed = isWindowedLayout()
      applyDisplayWindowSizing(
          jFrame,
          mainPanel,
          it.preferredSize,
          windowed,
          forceExplicitPack = true,
      )
    }
  }

  /**
   * Refreshes top-level constraints at a fullscreen boundary even when renderer geometry did not
   * change. On exit, pack only if the restored content area cannot contain the current exact
   * explicit size; otherwise preserve the remembered window bounds.
   */
  fun refreshDisplayWindowSizing(windowed: Boolean) {
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
            ),
    )
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

/**
 * Explicit scale is a real windowed pixel-size contract, so manual resizing cannot crop it.
 * Fullscreen and fit modes retain the sensible native-frame minimum; the viewport supplies a
 * uniform fit fallback if the host cannot honor an explicit top-level minimum.
 */
internal fun minimumDisplayContentSize(
    scaleMode: DisplayScaleMode,
    preferredSize: Dimension,
    windowed: Boolean,
): Dimension {
  require(preferredSize.width > 0 && preferredSize.height > 0) {
    "Preferred display size must be positive"
  }
  val base = Dimension(160, 144)
  if (!windowed || !scaleMode.isExplicit) {
    return base
  }
  return Dimension(
      maxOf(base.width, preferredSize.width),
      maxOf(base.height, preferredSize.height),
  )
}

internal fun shouldPackExplicitWindow(
    scaleMode: DisplayScaleMode,
    preferredSize: Dimension,
    currentContentSize: Dimension,
    windowed: Boolean,
): Boolean {
  require(preferredSize.width > 0 && preferredSize.height > 0) {
    "Preferred display size must be positive"
  }
  require(currentContentSize.width >= 0 && currentContentSize.height >= 0) {
    "Current display content size must not be negative"
  }
  return windowed &&
      scaleMode.isExplicit &&
      (currentContentSize.width < preferredSize.width ||
          currentContentSize.height < preferredSize.height)
}

internal fun closeControllerAfterLifecycleRelease(
    release: () -> Unit,
    close: () -> Unit,
) {
  release()
  close()
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
