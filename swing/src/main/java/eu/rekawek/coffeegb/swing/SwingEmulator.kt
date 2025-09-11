package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingController
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

  private val controller: SwingController
  private val sound: AudioSystemSound

  private val connectionController: ConnectionController

  private var session: Controller = BasicController(eventBus, properties, console)

  init {
    display = SwingDisplay(properties.display, eventBus, "main")
    sound = AudioSystemSound(properties.sound, eventBus, "main")
    controller = SwingController(properties.controllerMapping, eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    Thread(sound).start()

    eventBus.register<ConnectionController.ServerGotConnectionEvent> {
      session.close()
      session = LinkedController(eventBus, properties, console)
    }
    eventBus.register<ConnectionController.ClientConnectedToServerEvent> {
      session.close()
      session = LinkedController(eventBus, properties, console)
    }
    eventBus.register<ConnectionController.ServerLostConnectionEvent> {
      session.close()
      session = BasicController(eventBus, properties, console)
    }
    eventBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      session.close()
      session = BasicController(eventBus, properties, console)
    }
  }

  fun stop() {
    session.close()
    sound.stopThread()
    display.stop()
  }

  fun bind(jFrame: JFrame) {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.add(display)

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(controller)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      mainPanel.preferredSize = it.preferredSize
      jFrame.pack()
    }
  }
}
