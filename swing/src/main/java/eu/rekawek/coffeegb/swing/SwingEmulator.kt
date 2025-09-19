package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingJoypad
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
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

  private val connectionController: ConnectionController

  private lateinit var controller: Controller

  init {
    display = SwingDisplay(properties.display, eventBus, "main")
    sound = AudioSystemSound(properties.sound, eventBus, "main")
    joypad = SwingJoypad(properties.controllerMapping, eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    Thread(sound).start()

    startBasicController()

    eventBus.register<ConnectionController.ServerGotConnectionEvent> {
      controller.close()
      startLinkedController()
    }
    eventBus.register<ConnectionController.ClientConnectedToServerEvent> {
      controller.close()
      startLinkedController()
    }
    eventBus.register<ConnectionController.ServerLostConnectionEvent> {
      controller.close()
      startBasicController()
    }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      controller.close()
      startBasicController()
    }
  }

  private fun startBasicController() {
    controller = BasicController(eventBus, properties, console).also { it.startController() }
  }

  private fun startLinkedController() {
    controller = LinkedController(eventBus, properties, console).also { it.startController() }
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

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(joypad)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      mainPanel.preferredSize = it.preferredSize
      jFrame.pack()
    }
  }
}
