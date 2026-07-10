package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingAccelerometer
import eu.rekawek.coffeegb.swing.io.SwingTiltKeys
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.SwingJoypad
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
  private val sound: AudioSystemSound
  private val accelerometer: SwingAccelerometer

  private val tiltKeys: SwingTiltKeys

  private val printer: SwingPrinter

  private val connectionController: ConnectionController

  private lateinit var controller: Controller

  init {
    display = SwingDisplay(properties.display, eventBus, "main")
    sound = AudioSystemSound(properties.sound, eventBus, "main")
    joypad = SwingJoypad(properties.controllerMapping, eventBus)
    accelerometer = SwingAccelerometer(eventBus, display.preferredSize)
    tiltKeys = SwingTiltKeys(eventBus)
    printer = SwingPrinter(eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    Thread(sound).start()

    controller = BasicController(eventBus, properties, console).also { it.startController() }

    eventBus.register<ConnectionController.ServerGotConnectionEvent> { startLinkedController() }
    eventBus.register<ConnectionController.ClientConnectedToServerEvent> { startLinkedController() }
    eventBus.register<ConnectionController.ServerLostConnectionEvent> { startBasicController() }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      startBasicController()
    }
  }

  private fun startBasicController() {
    val state = controller.closeWithState()
    controller = BasicController(eventBus, properties, console).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.file, state.memento))
    }
  }

  private fun startLinkedController() {
    val state = controller.closeWithState()
    controller = LinkedController(eventBus, properties, console).also { it.startController() }
    if (state != null) {
      eventBus.post(Controller.LoadRomEvent(state.rom.file, state.memento))
    }
  }

  fun stop() {
    controller.close()
    sound.stopThread()
    display.stop()
  }

  fun bind(jFrame: JFrame) {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.add(display)
    display.addMouseMotionListener(accelerometer)

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(joypad)
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
