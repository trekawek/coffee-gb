package eu.rekawek.coffeegb.controller.link

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.Companion.createGameboyConfig
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.UpdatedSystemMappingEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.TimingTicker
import eu.rekawek.coffeegb.controller.deserializeToGameboyMemento
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.funnel
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteStopEvent
import eu.rekawek.coffeegb.controller.network.Connection.RequestResetEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.serialize
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LinkedController(
    parentEventBus: EventBus,
    private val properties: EmulatorProperties,
    private val console: Console?,
) : Controller {

  private val eventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  @VisibleForTesting internal val timingTicker = TimingTicker()

  private var mainSession: Session? = null

  private var peerSession: Session? = null

  private var mainConfig: GameboyConfiguration? = null

  private var peerConfig: GameboyConfiguration? = null

  @VisibleForTesting internal val stateHistory: StateHistory = StateHistory()

  @Volatile private var doStop = false

  private val mainSerialEndpoint = Peer2PeerSerialEndpoint()

  private val peerSerialEndpoint = Peer2PeerSerialEndpoint()

  private var frame = 0L

  private var currentInput: Input? = null

  private var lastInput: Input? = null

  private var lastSync: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

  private val thread = Thread {
    while (!doStop) {
      runFrame()
    }
  }

  init {
    peerSerialEndpoint.init(mainSerialEndpoint)

    eventQueue.register<LoadedMainConfigEvent> { e ->
      mainSession?.close()
      mainConfig = e.config
      initMainSession(e.snapshot)
    }

    eventQueue.register<StopEmulationEvent> {
      mainSession?.close()
      mainSession = null
    }

    eventQueue.register<ResetEmulationEvent> {
      mainSession?.close()
      initMainSession(null)
      eventBus.postAsync(RequestResetEvent(frame))
    }

    eventQueue.register<PeerLoadedGameEvent> { e ->
      peerConfig =
          createGameboyConfig(properties, Rom(e.rom))
              .setGameboyType(e.gameboyType)
              .setBootstrapMode(e.bootstrapMode)
              .setBatteryData(e.battery)
      initPeerSession(e.frame, e.snapshot?.deserializeToGameboyMemento())
    }

    eventQueue.register<RemoteButtonStateEvent> { e ->
      stateHistory.addSecondaryInput(e.frame, e.input)
    }

    eventQueue.register<ReceivedRemoteResetEvent> { e ->
      peerSession?.close()
      initPeerSession(e.frame, null)
    }

    eventQueue.register<ReceivedRemoteStopEvent> { peerSession?.close() }

    eventQueue.register<ButtonPressEvent> { e ->
      val currentInput = currentInput ?: Input(emptyList(), emptyList())
      this.currentInput =
          currentInput.copy(
              pressedButtons = (currentInput.pressedButtons + e.button).sorted(),
              releasedButtons = (currentInput.releasedButtons - e.button).sorted(),
          )
    }

    eventQueue.register<ButtonReleaseEvent> { e ->
      val currentInput = currentInput ?: Input(emptyList(), emptyList())
      this.currentInput =
          currentInput.copy(
              pressedButtons = (currentInput.pressedButtons - e.button).sorted(),
              releasedButtons = (currentInput.releasedButtons + e.button).sorted(),
          )
    }

    eventBus.register<LoadRomEvent> {
      val rom = Rom(it.rom)
      val config = createGameboyConfig(properties, rom)
      eventBus.post(Controller.GameboyTypeEvent(config.gameboyType))
      eventBus.post(Controller.SessionPauseSupportEvent(false))
      eventBus.post(Controller.SessionSnapshotSupportEvent(null))
      eventBus.post(Controller.EmulationStartedEvent(rom.title))
      eventBus.post(LoadedMainConfigEvent(config, it.memento?.serialize()))
    }

    eventBus.register<UpdatedSystemMappingEvent> {
      mainSession?.config?.let { config ->
        val newType = Controller.getGameboyType(properties.system, config.rom)
        if (newType != config.gameboyType) {
          eventBus.post(LoadRomEvent(config.rom.file))
        }
      }
    }
  }

  override fun startController() {
    thread.start()
  }

  fun runFrame() {
    eventQueue.dispatch()

    val mainSession = mainSession
    val peerSession = peerSession
    if (stateHistory.merge(mainSession?.config, peerSession?.config)) {
      val head = stateHistory.getHead()
      if (mainSession != null && head.mainMemento != null) {
        mainSession.restoreFromMemento(head.mainMemento)
      }
      if (peerSession != null && head.peerMemento != null) {
        peerSession.restoreFromMemento(head.peerMemento)
      }
      frame = head.frame
      LOG.atDebug().log("State merged to {}", frame)
    }

    val input = currentInput ?: Input(emptyList(), emptyList())
    val effectiveInput = if (input != lastInput) input else Input(emptyList(), emptyList())
    lastInput = input
    if (mainSession != null) {
      effectiveInput.send(mainSession.eventBus)
    }
    currentInput = null

    if (!effectiveInput.isEmpty() || lastSync.elapsedNow() > 5.seconds) {
      eventBus.postAsync(LocalButtonStateEvent(frame, effectiveInput))
      lastSync = TimeSource.Monotonic.markNow()
    }

    stateHistory.addState(
        frame,
        effectiveInput,
        mainSession?.saveToMemento(),
        peerSession?.saveToMemento(),
    )

    repeat(TICKS_PER_FRAME) {
      mainSession?.gameboy?.tick()
      peerSession?.gameboy?.tick()
      timingTicker.run()
    }

    frame++
  }

  private fun initMainSession(snapshot: ByteArray?) {
    val mainConfig = mainConfig ?: return

    val mainEventBus = EventBusImpl(null, null, false)
    funnel(
        mainEventBus,
        eventBus.fork("main"),
        setOf(
            Display.DmgFrameReadyEvent::class,
            Display.GbcFrameReadyEvent::class,
            SgbDisplay.SgbFrameReadyEvent::class,
            Sound.SoundSampleEvent::class,
            Joypad.JoypadPressEvent::class,
        ),
    )
    mainSession = Session(mainConfig, mainEventBus, console, mainSerialEndpoint)
    if (snapshot != null) {
      mainSession?.gameboy?.restoreFromMemento(snapshot.deserializeToGameboyMemento())
    }

    val romBuffer = mainConfig.rom.file.toPath().readBytes()
    val saveFile = Cartridge.getSaveName(mainConfig.rom.file)
    val batteryBuffer =
        if (saveFile.exists()) {
          saveFile.toPath().readBytes()
        } else {
          null
        }

    eventBus.post(
        LocalRomLoadedEvent(
            romFile = romBuffer,
            batteryFile = batteryBuffer,
            snapshot = snapshot,
            mainConfig.gameboyType,
            mainConfig.bootstrapMode,
            frame,
        )
    )
  }

  private fun initPeerSession(frame: Long, state: Memento<Gameboy>?) {
    val peerConfig = peerConfig ?: return
    val peerEventBus = EventBusImpl(null, null, false)
    val peerSession = Session(peerConfig, peerEventBus, null, peerSerialEndpoint)
    if (state != null) {
      peerSession.gameboy.restoreFromMemento(state)
    }

    val mainSession = mainSession
    while (this.frame < frame) {
      if (mainSession != null) {
        repeat(TICKS_PER_FRAME) { mainSession.gameboy.tick() }
      }
      this.frame++
    }

    var peerFrame = frame
    while (this.frame > peerFrame) {
      stateHistory.setPeerState(peerFrame, peerSession.saveToMemento())
      repeat(TICKS_PER_FRAME) { peerSession.gameboy.tick() }
      peerFrame++
    }

    this.peerSession = peerSession
  }

  override fun close() {}

  override fun closeWithState(): Controller.ControllerState? {
    doStop = true
    thread.join()

    val state =
        mainSession?.let { Controller.ControllerState(it.gameboy.saveToMemento(), it.config.rom) }

    mainSession?.eventBus?.post(Controller.EmulationStoppedEvent())
    mainSession?.close()
    peerSession?.close()
    eventBus.close()

    return state
  }

  data class LocalRomLoadedEvent(
      val romFile: ByteArray,
      val batteryFile: ByteArray?,
      val snapshot: ByteArray?,
      val gameboyType: GameboyType,
      val bootstrapMode: Gameboy.BootstrapMode,
      val frame: Long,
  ) : Event

  data class LocalButtonStateEvent(
      val frame: Long,
      val input: Input,
  ) : Event

  data class RemoteButtonStateEvent(
      val frame: Long,
      val input: Input,
  ) : Event

  data class LoadedMainConfigEvent(val config: GameboyConfiguration, val snapshot: ByteArray?) :
      Event

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)
  }
}
