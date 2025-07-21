package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
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
import kotlin.io.path.readBytes

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

  private var session: Session? = null

  private var mainRom: File? = null
  private var peerRom: ByteArray? = null
  private var peerBattery: ByteArray? = null

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
      session?.shutDown()

      if (isConnected) {
        val romBuffer = it.rom.toPath().readBytes()
        val saveFile = Cartridge.getSaveName(it.rom)
        val batteryBuffer =
            if (saveFile.exists()) {
              saveFile.toPath().readBytes()
            } else {
              null
            }
        this.mainRom = it.rom
        startLinkedSession()
        eventBus.post(WaitingForPeerEvent(romBuffer, batteryBuffer))
      } else {
        this.session = SimpleSession(eventBus.fork("session"), it.rom, console)
      }

      eventBus.post(SessionPauseSupportEvent(true))
      eventBus.post(SessionSnapshotSupportEvent(session as? SnapshotSupport))
      if (session is SimpleSession) {
        eventBus.post(StartEmulationEvent())
      }
    }
    eventBus.register<PeerLoadedGameEvent> {
      peerRom = it.rom
      peerBattery = it.battery
      startLinkedSession()
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
    eventBus.register<ServerGotConnectionEvent> {
      isConnected = true
      session?.stop()
    }
    eventBus.register<ServerLostConnectionEvent> {
      isConnected = false
      session?.stop()
      mainRom = null
      peerRom = null
    }
    eventBus.register<ClientConnectedToServerEvent> {
      isConnected = true
      session?.stop()
    }
    eventBus.register<ClientDisconnectedFromServerEvent> {
      isConnected = false
      session?.stop()
      mainRom = null
      peerRom = null
    }
  }

  private fun startLinkedSession() {
    if (mainRom != null && peerRom != null) {
      session?.shutDown()
      session =
        LinkedSession(eventBus.fork("session"), mainRom!!, peerRom!!, peerBattery, console)
      //eventBus.post(ConnectedGameboyStartedEvent())
      eventBus.post(StartEmulationEvent())
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

  data class WaitingForPeerEvent(val romFile: ByteArray, val batteryFile: ByteArray?) : Event

  companion object {
    const val SHOW_REMOTE_SCREEN = false
  }
}
