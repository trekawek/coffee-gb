package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.network.ButtonReceiver
import eu.rekawek.coffeegb.swing.io.network.ButtonSender
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerButtonEvents
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerIsReadyEvent
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientConnectedToServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ClientDisconnectedFromServerEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerGotConnectionEvent
import eu.rekawek.coffeegb.swing.io.network.ConnectionController.ServerLostConnectionEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    private val snapshotManager: SnapshotManager,
    properties: EmulatorProperties,
) {
  private val display: SwingDisplay

  private val remoteEventBus = EventBus()
  private val remoteDisplay: SwingDisplay

  private val controller: SwingController
  private val sound: AudioSystemSound

  private val connectionController: ConnectionController

  private var currentRom: File? = null
  private var gameboyType: GameboyType

  private var isConnected = false
  private var remoteRomName: String? = null

  init {
    display = SwingDisplay(properties.display, eventBus)

    remoteDisplay =
        if (SHOW_REMOTE_SCREEN) {
          SwingDisplay(properties.display, remoteEventBus)
        } else {
          SwingDisplay(properties.display, EventBus())
        }
    sound = AudioSystemSound(properties.sound, eventBus)
    controller = SwingController(properties.controllerMapping, eventBus)
    connectionController = ConnectionController(eventBus)

    gameboyType = properties.gameboy.gameboyType

    Thread(display).start()
    if (SHOW_REMOTE_SCREEN) {
      Thread(remoteDisplay).start()
    }
    Thread(sound).start()

    eventBus.register<StartEmulationEvent> { startEmulation(it.rom) }
    eventBus.register<RestoreSnapshotEvent> { e ->
      snapshotManager.loadSnapshot(e.slot)?.let { startEmulation(currentRom, it) }
    }
    eventBus.register<SetGameboyType> {
      gameboyType = it.type
      reset()
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
  }

  private fun startEmulation(rom: File?, gameboySnapshot: Gameboy? = null) {
    eventBus.post(StopEmulationEvent())

    currentRom = rom
    val cart = Cartridge(rom, true, gameboyType, false)
    val gameboy = gameboySnapshot ?: Gameboy(cart)
    console?.setGameboy(gameboy)

    val localEventBus = eventBus.fork()
    localEventBus.register<PauseEmulationEvent> { gameboy.pause() }
    localEventBus.register<ResumeEmulationEvent> { gameboy.resume() }
    localEventBus.register<ResetEmulationEvent> { reset() }
    localEventBus.register<StopEmulationEvent> {
      gameboy.stop()
      cart.flushBattery()
      console?.setGameboy(null)
      localEventBus.post(EmulationStoppedEvent())
      localEventBus.stop()
    }
    localEventBus.register<SaveSnapshotEvent> { snapshotManager.saveSnapshot(it.slot, gameboy) }

    if (isConnected) {
      val remoteGameboy = Gameboy(cart)
      val remoteSerial = Peer2PeerSerialEndpoint(null)
      val localRemoteEventBus = remoteEventBus.fork()
      localEventBus.register<PeerButtonEvents> { localRemoteEventBus.post(it) }
      ButtonReceiver.peerToLocalButtons(localRemoteEventBus)
      remoteGameboy.init(localRemoteEventBus, remoteSerial, null)
      localRemoteEventBus.register<RunTicksEvent> {
        LOG.atDebug().log("Running {} ticks", it.ticks)
        repeat(it.ticks) { remoteGameboy.tick() }
      }
      localEventBus.register<StopEmulationEvent> { localRemoteEventBus.stop() }

      val localSerial = Peer2PeerSerialEndpoint(remoteSerial)
      gameboy.init(localEventBus, localSerial, console)
      gameboy.registerTickListener(TimingTicker())
      gameboy.registerTickListener(ButtonSender(localEventBus))

      if (cart.title == remoteRomName) {
        Thread(gameboy).start()
        localEventBus.post(ConnectedGameboyStartedEvent())
      } else {
        localEventBus.post(WaitingForPeerEvent(cart.title))
        localEventBus.register<PeerIsReadyEvent> { Thread(gameboy).start() }
      }
    } else {
      gameboy.init(localEventBus, SerialEndpoint.NULL_ENDPOINT, console)
      gameboy.registerTickListener(TimingTicker())
      Thread(gameboy).start()
    }

    eventBus.post(EmulationStartedEvent(cart.title))
  }

  fun reset() {
    if (currentRom != null) {
      eventBus.post(StartEmulationEvent(currentRom!!))
    }
  }

  class EmulationStartedEvent(val romName: String) : Event

  class EmulationStoppedEvent : Event

  data class StartEmulationEvent(val rom: File) : Event

  class PauseEmulationEvent : Event

  class ResumeEmulationEvent : Event

  class ResetEmulationEvent : Event

  class StopEmulationEvent : Event

  data class SaveSnapshotEvent(val slot: Int) : Event

  data class RestoreSnapshotEvent(val slot: Int) : Event

  data class SetGameboyType(val type: GameboyType) : Event

  class ConnectedGameboyStartedEvent : Event

  data class WaitingForPeerEvent(val romName: String) : Event

  data class RunTicksEvent(val ticks: Int) : Event

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(SwingEmulator::class.java)

    const val SHOW_REMOTE_SCREEN = true
  }
}
