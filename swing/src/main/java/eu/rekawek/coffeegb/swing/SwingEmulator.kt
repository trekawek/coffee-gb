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
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import eu.rekawek.coffeegb.swing.io.GamepadConfiguration
import eu.rekawek.coffeegb.swing.io.SwingAccelerometer
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.SwingGamepad
import eu.rekawek.coffeegb.swing.io.SwingJoypad
import eu.rekawek.coffeegb.swing.io.SwingTiltKeys
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel

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
    eventBus.register<ConnectionController.ServerLostConnectionEvent> { startBasicController() }
    eventBus.register<ConnectionController.StopServerEvent> { startBasicController() }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      startBasicController()
    }
    eventBus.register<Controller.RomLoadingEvent> { releaseForLifecycleChange() }
    eventBus.register<Controller.EmulationStoppedEvent> { releaseForLifecycleChange() }
  }

  private fun startBasicController() {
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller = BasicController(eventBus, properties, console).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.file, state.state))
    }
  }

  private fun startLinkedController(mode: LinkMode, player: Int) {
    releaseForLifecycleChange()
    val state = controller.closeWithState()
    controller =
        LinkedController(eventBus, properties, console, mode, player).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.file, state.state))
    }
  }

  fun stop() {
    joypad.stop()
    tiltInput.stop()
    gamepad.stop()
    gamepadThread.interrupt()
    gamepadThread.join(1000)
    controller.close()
    sound.stopThread()
    display.stop()
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
  }

  fun bind(jFrame: JFrame) {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.add(display)
    display.addMouseMotionListener(accelerometer)

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(joypad)
    jFrame.addWindowFocusListener(joypad)
    jFrame.addWindowFocusListener(tiltInput)
    jFrame.addKeyListener(tiltKeys)
    jFrame.addMouseMotionListener(accelerometer)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      mainPanel.preferredSize = it.preferredSize
      // Setting preferredSize doesn't invalidate, and a pack() that leaves the frame the
      // same size (e.g. re-selecting the current scale, or a rotation that preserves the
      // dimensions) never triggers the reshape that refreshes the window's cached preferred
      // size - after which every later pack() reads the stale size and stops resizing.
      // Invalidating up from the panel clears the cache at each level so pack() recomputes.
      mainPanel.invalidate()
      jFrame.pack()
    }
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
