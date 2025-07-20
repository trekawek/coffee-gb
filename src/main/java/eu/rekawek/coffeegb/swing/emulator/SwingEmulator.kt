package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
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
import eu.rekawek.coffeegb.swing.io.network.Connection.ReceivedRomEvent
import eu.rekawek.coffeegb.swing.io.network.Connection.RequestRomEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerLostConnectionEvent
import java.awt.Dimension
import java.io.File
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

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

  private val remoteRoms: Path

  init {
    remoteRoms = createTempDirectory("roms")

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
      session?.shutDown()

      val sessionEventBus = eventBus.fork("session")
      val session: Session =
          if (isConnected) {
            LinkedSession(sessionEventBus, it.rom, console)
          } else {
            SimpleSession(sessionEventBus, it.rom, console)
          }

      this.session = session
      eventBus.post(SessionPauseSupportEvent(true))
      eventBus.post(SessionSnapshotSupportEvent(session as? SnapshotSupport))
      if (session is SimpleSession) {
        eventBus.post(StartEmulationEvent())
      }
      if (session is LinkedSession) {
        if (this.remoteRomName == session.getRomName()) {
          eventBus.post(ConnectedGameboyStartedEvent())
          eventBus.post(StartEmulationEvent())
        } else {
          eventBus.post(WaitingForPeerEvent(session.getRomName(), it.rom))
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
      session?.pause()
      if (session is LinkedSession) {
        eventBus.post(Connection.RequestPauseEvent())
      }
    }
    eventBus.register<Connection.ReceivedRemotePauseEvent> { session?.pause() }
    eventBus.register<ResumeEmulationEvent> {
      session?.resume()
      if (session is LinkedSession) {
        eventBus.post(Connection.RequestResumeEvent())
      }
    }
    eventBus.register<Connection.ReceivedRemoteResumeEvent> { session?.resume() }
    eventBus.register<ResetEmulationEvent> {
      session?.reset()
      if (session is LinkedSession) {
        eventBus.post(Connection.RequestResetEvent())
      }
    }
    eventBus.register<Connection.ReceivedRemoteResetEvent> { session?.reset() }
    eventBus.register<StopEmulationEvent> {
      session?.stop()
      if (session is LinkedSession) {
        eventBus.post(Connection.RequestStopEvent())
      }
    }
    eventBus.register<Connection.ReceivedRemoteStopEvent> { session?.stop() }
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
        loadRemoteRom()
      }
    }
    eventBus.register<ReceivedRomEvent> {
      val path = remoteRoms.resolve("$remoteRomName.rom")
      path.writeBytes(it.rom)
      eventBus.post(LoadRomEvent(path.toFile()))
    }
  }

  fun loadRemoteRom() {
    val path = remoteRoms.resolve("$remoteRomName.rom")
    if (path.exists()) {
      eventBus.post(LoadRomEvent(path.toFile()))
    } else {
      eventBus.post(RequestRomEvent())
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

  data class WaitingForPeerEvent(val romName: String, val romFile: File) : Event

  companion object {
    const val SHOW_REMOTE_SCREEN = false
  }
}
