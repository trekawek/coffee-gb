package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
import eu.rekawek.coffeegb.swing.emulator.session.PauseSupport
import eu.rekawek.coffeegb.swing.emulator.session.Session
import eu.rekawek.coffeegb.swing.emulator.session.SimpleSession
import eu.rekawek.coffeegb.swing.emulator.session.SnapshotSupport
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.network.Connection
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerLostConnectionEvent
import java.awt.Dimension
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    properties: EmulatorProperties,
) {
  private val display: SwingDisplay
  private val remoteDisplay: SwingDisplay?

  private val controller: SwingController
  private val sound: AudioSystemSound

  private val connectionController: ConnectionController
  private var isConnected = false
  private var remoteRomName: String? = null

  private var session: Session? = null

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

    eventBus.register<LoadRomEvent> {
      session?.stop()

      val session: Session =
          if (isConnected) {
            LinkedSession(eventBus, it.rom, console)
          } else {
            SimpleSession(eventBus, it.rom, console)
          }

      this.session = session
      eventBus.post(SessionPauseSupportEvent(session is PauseSupport))
      eventBus.post(SessionSnapshotSupportEvent(if (session is SnapshotSupport) session else null))
      if (session is SimpleSession) {
        eventBus.post(StartEmulationEvent())
      }
      if (session is LinkedSession) {
        if (this.remoteRomName == session.getRomName()) {
          eventBus.post(ConnectedGameboyStartedEvent())
          eventBus.post(StartEmulationEvent())
        } else {
          eventBus.post(WaitingForPeerEvent(session.getRomName()))
        }
      }
    }
    eventBus.register<Connection.PeerIsReadyEvent> {
      if (session is LinkedSession) {
        eventBus.post(StartEmulationEvent())
      }
    }
    eventBus.register<StartEmulationEvent> { session?.start() }
    eventBus.register<RestoreSnapshotEvent> { e ->
      session?.let {
        if (it is SnapshotSupport) {
          it.loadSnapshot(e.slot)
        }
      }
    }
    eventBus.register<PauseEmulationEvent> {
      session?.let {
        if (it is PauseSupport) {
          it.pause()
        }
      }
    }
    eventBus.register<ResumeEmulationEvent> {
      session?.let {
        if (it is PauseSupport) {
          it.resume()
        }
      }
    }
    eventBus.register<ResetEmulationEvent> { session?.reset() }
    eventBus.register<StopEmulationEvent> { session?.stop() }
    eventBus.register<SaveSnapshotEvent> { e ->
      session?.let {
        if (it is SnapshotSupport) {
          it.saveSnapshot(e.slot)
        }
      }
    }
    eventBus.register<RestoreSnapshotEvent> { e ->
      session?.let {
        if (it is SnapshotSupport) {
          it.loadSnapshot(e.slot)
        }
      }
    }

    eventBus.register<ServerGotConnectionEvent> { isConnected = true }
    eventBus.register<ServerLostConnectionEvent> {
      isConnected = false
      remoteRomName = null
    }
    eventBus.register<ClientConnectedToServerEvent> { isConnected = true }
    eventBus.register<ClientDisconnectedFromServerEvent> {
      isConnected = false
      remoteRomName = null
    }
    eventBus.register<PeerLoadedGameEvent> {
      if (isConnected) {
        remoteRomName = it.romName
      }
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
    }
  }

  data class LoadRomEvent(val rom: File) : Event

  class StartEmulationEvent : Event

  class PauseEmulationEvent : Event

  class ResumeEmulationEvent : Event

  class ResetEmulationEvent : Event

  class StopEmulationEvent : Event

  data class SaveSnapshotEvent(val slot: Int) : Event

  data class RestoreSnapshotEvent(val slot: Int) : Event

  data class SessionPauseSupportEvent(val enabled: Boolean) : Event

  data class SessionSnapshotSupportEvent(val snapshotSupport: SnapshotSupport?) : Event

  class ConnectedGameboyStartedEvent : Event

  data class WaitingForPeerEvent(val romName: String) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(SwingEmulator::class.java)

    const val SHOW_REMOTE_SCREEN = false
  }
}
