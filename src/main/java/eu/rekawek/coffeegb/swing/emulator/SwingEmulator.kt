package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.session.LinkedSession
import eu.rekawek.coffeegb.swing.session.Session
import eu.rekawek.coffeegb.swing.session.SimpleSession
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerLostConnectionEvent
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    private val properties: EmulatorProperties,
) {
  private val display: SwingDisplay
  private val remoteDisplay: SwingDisplay?

  private val controller: SwingController
  private val sound: AudioSystemSound

  private val connectionController: ConnectionController

  private var session: Session = SimpleSession(eventBus, properties, console)

  init {
    display = SwingDisplay(properties.display, eventBus, "main")

    remoteDisplay =
        if (SHOW_REMOTE_SCREEN) {
          SwingDisplay(properties.display, eventBus, "secondary")
        } else {
          null
        }
    sound = AudioSystemSound(properties.sound, eventBus, "main")
    controller = SwingController(properties.controllerMapping, eventBus)
    connectionController = ConnectionController(eventBus)

    Thread(display).start()
    if (SHOW_REMOTE_SCREEN) {
      Thread(remoteDisplay).start()
    }
    Thread(sound).start()

    eventBus.register<ServerGotConnectionEvent> {
      session.close()
      session = LinkedSession(eventBus, properties, console)
    }
    eventBus.register<ClientConnectedToServerEvent> {
      session.close()
      session = LinkedSession(eventBus, properties, console)
    }
    eventBus.register<ServerLostConnectionEvent> {
      session.close()
      session = SimpleSession(eventBus, properties, console)
    }
    eventBus.register<ClientDisconnectedFromServerEvent> {
      session.close()
      session = SimpleSession(eventBus, properties, console)
    }
  }

  fun stop() {
    sound.stopThread()
    display.stop()
  }

  fun bind(jFrame: JFrame) {
    val mainPanel = JPanel()
    mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.X_AXIS))
    mainPanel.add(display)
    if (SHOW_REMOTE_SCREEN) {
      mainPanel.add(remoteDisplay)
    }

    jFrame.contentPane = mainPanel
    jFrame.addKeyListener(controller)

    eventBus.register<SwingDisplay.DisplaySizeUpdatedEvent> {
      val dimension =
          if (SHOW_REMOTE_SCREEN) {
            Dimension(it.preferredSize.width * 2, it.preferredSize.height)
          } else {
            it.preferredSize
          }
      mainPanel.preferredSize = dimension
      jFrame.pack()
    }
  }

  companion object {
    const val SHOW_REMOTE_SCREEN = false
  }
}
