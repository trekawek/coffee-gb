package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.io.AudioSystemSound
import eu.rekawek.coffeegb.swing.io.SwingController
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import eu.rekawek.coffeegb.swing.io.network.ConnectionController
import java.io.File
import javax.swing.JFrame

class SwingEmulator(
    private val eventBus: EventBus,
    private val console: Console?,
    private val snapshotManager: SnapshotManager,
    properties: EmulatorProperties,
) {
  private val display: SwingDisplay
  private val controller: SwingController
  private val sound: AudioSystemSound

  val connectionController: ConnectionController

  private var currentRom: File? = null
  private var gameboyType: GameboyType

  init {
    display = SwingDisplay(properties.display, eventBus)
    sound = AudioSystemSound(properties.sound, eventBus)
    controller = SwingController(properties.controllerMapping, eventBus)
    connectionController = ConnectionController(eventBus)

    gameboyType = properties.gameboy.gameboyType

    Thread(display).start()
    Thread(sound).start()

    eventBus.register<StartEmulationEvent> { startEmulation(it.rom) }
    eventBus.register<RestoreSnapshotEvent> { e ->
      snapshotManager.loadSnapshot(e.slot)?.let { startEmulation(currentRom, it) }
    }
    eventBus.register<SetGameboyType> {
      gameboyType = it.type
      reset()
    }
  }

  fun stop() {
    sound.stopThread()
    display.stop()
  }

  fun bind(jFrame: JFrame) {
    jFrame.contentPane = display
    jFrame.addKeyListener(controller)
  }

  private fun startEmulation(rom: File?, gameboySnapshot: Gameboy? = null) {
    eventBus.post(StopEmulationEvent())

    val cart = Cartridge(rom, true, gameboyType, false)
    val gameboy = gameboySnapshot ?: Gameboy(cart)
    val localEventBus = eventBus.fork()

    gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, console)
    gameboy.registerTickListener(TimingTicker())

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

    Thread(gameboy).start()
    currentRom = rom
    console?.setGameboy(gameboy)
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
}
