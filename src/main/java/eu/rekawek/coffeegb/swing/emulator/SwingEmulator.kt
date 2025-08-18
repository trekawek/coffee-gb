package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.GameboyType
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Rom
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
    private val properties: EmulatorProperties,
) {
  private val gameboyTypeResolver = GameboyTypeResolver(properties.system)

  private val display: SwingDisplay
  private val remoteDisplay: SwingDisplay?

  private val controller: SwingController
  private val sound: AudioSystemSound

  private val connectionController: ConnectionController
  private var isConnected = false

  private var session: Session? = null

  private var config: Gameboy.GameboyConfiguration? = null

  private var mainConfig: Gameboy.GameboyConfiguration? = null
  private var peerConfig: Gameboy.GameboyConfiguration? = null

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
        mainConfig = createGameboyConfig(Rom(it.rom))
        startLinkedSession()
        eventBus.post(
            WaitingForPeerEvent(
                romBuffer, batteryBuffer, mainConfig!!.gameboyType, mainConfig!!.bootstrapMode))
      } else {
        config = createGameboyConfig(Rom(it.rom))
        session = SimpleSession(eventBus.fork("session"), config!!, console)
        eventBus.post(SessionPauseSupportEvent(true))
        eventBus.post(SessionSnapshotSupportEvent(session as? SnapshotSupport))
        eventBus.post(StartEmulationEvent())
      }
    }
    eventBus.register<PeerLoadedGameEvent> {
      peerConfig =
          createGameboyConfig(Rom(it.rom))
              .setGameboyType(it.gameboyType)
              .setBootstrapMode(it.bootstrapMode)
              .setBatteryData(it.battery)
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
    eventBus.register<UpdatedSystemMappingEvent> {
      if (session is SimpleSession && config != null) {
        val newType = gameboyTypeResolver.getGameboyType(config!!.rom)
        if (newType != config!!.gameboyType) {
          eventBus.post(LoadRomEvent(config!!.rom.file))
        }
      }
    }
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
      mainConfig = null
      peerConfig = null
    }
    eventBus.register<ClientConnectedToServerEvent> {
      isConnected = true
      session?.stop()
    }
    eventBus.register<ClientDisconnectedFromServerEvent> {
      isConnected = false
      session?.stop()
      mainConfig = null
      peerConfig = null
    }
  }

  private fun startLinkedSession() {
    if (mainConfig != null && peerConfig != null) {
      session?.shutDown()
      session = LinkedSession(eventBus.fork("session"), mainConfig!!, peerConfig!!, console)
      eventBus.post(SessionPauseSupportEvent(true))
      eventBus.post(SessionSnapshotSupportEvent(null))
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
      jFrame.pack()
    }
  }

  fun createGameboyConfig(rom: Rom): Gameboy.GameboyConfiguration {
    val config = Gameboy.GameboyConfiguration(rom)
    val gameboyType = gameboyTypeResolver.getGameboyType(rom)
    config.setGameboyType(gameboyType)
    if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB && gameboyType == GameboyType.CGB) {
      config.setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
    } else {
      config.setBootstrapMode(Gameboy.BootstrapMode.SKIP)
    }
    if (config.gameboyType == GameboyType.SGB && !rom.isSuperGameboyFlag) {
      config.setDisplaySgbBorder(false)
    } else {
      config.setDisplaySgbBorder(properties.display.showSgbBorder)
    }

    return config
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

  class UpdatedSystemMappingEvent : Event

  data class WaitingForPeerEvent(
      val romFile: ByteArray,
      val batteryFile: ByteArray?,
      val gameboyType: GameboyType,
      val bootstrapMode: Gameboy.BootstrapMode
  ) : Event

  data class GameboyTypeEvent(val gameboyType: GameboyType) : Event

  companion object {
    const val SHOW_REMOTE_SCREEN = false
  }
}
