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
import eu.rekawek.coffeegb.controller.network.Connection.RequestStopEvent
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
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Runs every Game Boy in a netplay session locally and rolls them back as remote input arrives. */
class LinkedController(
    parentEventBus: EventBus,
    private val properties: EmulatorProperties,
    private val console: Console?,
    private val mode: LinkMode = LinkMode.NORMAL,
    private val localPlayer: Int = 0,
) : Controller {

  private val eventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  @VisibleForTesting internal val timingTicker = TimingTicker()

  private val sessions = MutableList<Session?>(mode.playerCount) { null }

  private val configs = MutableList<GameboyConfiguration?>(mode.playerCount) { null }

  private val links = StateHistory.createLinks(mode)

  @VisibleForTesting internal val stateHistory: StateHistory = StateHistory(mode)

  @VisibleForTesting
  internal fun mainHeldButtons() = sessions[localPlayer]?.heldButtons ?: emptySet()

  @VisibleForTesting internal fun activeSessionCount() = sessions.count { it != null }

  @Volatile private var doStop = false

  private var frame = 0L

  private var currentInput: Input? = null

  private var lastInput: Input? = null

  private var initialRosterReady = false

  private var lastSync: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

  private val thread = Thread {
    while (!doStop) {
      runFrame()
    }
  }

  init {
    require(localPlayer in 0 until mode.playerCount)

    eventQueue.register<LoadedLocalConfigEvent> { e ->
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      configs[localPlayer] = e.config
      initSession(localPlayer, frame, e.snapshot?.deserializeToGameboyMemento())
      sendLocalRom(e.snapshot)
    }

    eventQueue.register<StopEmulationEvent> {
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      eventBus.postAsync(RequestStopEvent(frame, localPlayer))
    }

    eventQueue.register<ResetEmulationEvent> {
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      initSession(localPlayer, frame, null)
      eventBus.postAsync(RequestResetEvent(frame, localPlayer))
    }

    eventQueue.register<PeerLoadedGameEvent> { e ->
      if (e.player == localPlayer || e.player !in sessions.indices) {
        return@register
      }
      configs[e.player] =
          createGameboyConfig(properties, Rom(e.rom))
              .setGameboyType(e.gameboyType)
              .setBootstrapMode(e.bootstrapMode)
              .setCgb0Revision(e.cgb0Revision)
              .setBatteryData(e.battery)
      sessions[e.player]?.close()
      sessions[e.player] = null
      initSession(e.player, e.frame, e.snapshot?.deserializeToGameboyMemento())
    }

    eventQueue.register<RemoteButtonStateEvent> { e ->
      if (e.player != localPlayer && e.player in sessions.indices) {
        stateHistory.addSecondaryInput(e.player, e.frame, e.input)
      }
    }

    eventQueue.register<ReceivedRemoteResetEvent> { e ->
      if (e.player != localPlayer && e.player in sessions.indices) {
        sessions[e.player]?.close()
        sessions[e.player] = null
        initSession(e.player, e.frame, null)
      }
    }

    eventQueue.register<ReceivedRemoteStopEvent> { e ->
      if (e.player != localPlayer && e.player in sessions.indices) {
        sessions[e.player]?.close()
        sessions[e.player] = null
      }
    }

    eventQueue.register<ButtonPressEvent> { e ->
      val input = currentInput ?: Input(emptyList(), emptyList())
      currentInput =
          input.copy(
              pressedButtons = (input.pressedButtons + e.button).sorted(),
              releasedButtons = (input.releasedButtons - e.button).sorted(),
          )
    }

    eventQueue.register<ButtonReleaseEvent> { e ->
      val input = currentInput ?: Input(emptyList(), emptyList())
      currentInput =
          input.copy(
              pressedButtons = (input.pressedButtons - e.button).sorted(),
              releasedButtons = (input.releasedButtons + e.button).sorted(),
          )
    }

    eventBus.register<LoadRomEvent> {
      val rom = Rom(it.rom)
      val config = createGameboyConfig(properties, rom)
      eventBus.post(Controller.GameboyTypeEvent(config.gameboyType))
      eventBus.post(Controller.SessionPauseSupportEvent(false))
      eventBus.post(Controller.SessionSnapshotSupportEvent(null))
      eventBus.post(Controller.EmulationStartedEvent(rom.title))
      eventBus.post(LoadedLocalConfigEvent(config, it.memento?.serialize()))
    }

    eventBus.register<UpdatedSystemMappingEvent> {
      sessions[localPlayer]?.config?.let { config ->
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

    // All peers begin on the same frame. In particular, the four-player server waits for all
    // three client ROM states instead of letting player 1 advance and making late clients catch
    // up without the adapter traffic they missed.
    if (!initialRosterReady && sessions.any { it == null }) {
      if (!timingTicker.disabled) {
        Thread.sleep(1)
      }
      return
    }
    initialRosterReady = true

    if (stateHistory.merge(configs)) {
      val head = stateHistory.getHead()
      for (player in sessions.indices) {
        val session = sessions[player]
        val memento = head.mementos[player]
        if (session != null && memento != null) {
          session.restoreFromMemento(memento)
          session.heldButtons = head.buttons[player]
        }
      }
      frame = head.frame
      LOG.atDebug().log("State merged to {}", frame)
    }

    val input = currentInput ?: Input(emptyList(), emptyList())
    val effectiveInput = if (input != lastInput) input else Input(emptyList(), emptyList())
    lastInput = input
    currentInput = null

    val inputs = MutableList(mode.playerCount) { Input(emptyList(), emptyList()) }
    inputs[localPlayer] = effectiveInput
    stateHistory.addState(
        frame,
        inputs,
        sessions.map { it?.saveToMemento() },
        sessions.map { it?.heldButtons ?: emptySet() },
    )

    effectiveInput.send(sessions[localPlayer]!!.eventBus)

    if (!effectiveInput.isEmpty() || lastSync.elapsedNow() > 5.seconds) {
      eventBus.postAsync(LocalButtonStateEvent(frame, effectiveInput, localPlayer))
      lastSync = TimeSource.Monotonic.markNow()
    }

    repeat(TICKS_PER_FRAME) {
      sessions.forEach { it?.gameboy?.tick() }
      timingTicker.run()
    }

    frame++
  }

  private fun initSession(player: Int, sessionFrame: Long, state: Memento<Gameboy>?) {
    val config = configs[player] ?: return
    val sessionEventBus = EventBusImpl(null, null, false)
    if (player == localPlayer) {
      funnel(
          sessionEventBus,
          eventBus.fork("main"),
          setOf(
              Display.DmgFrameReadyEvent::class,
              Display.GbcFrameReadyEvent::class,
              SgbDisplay.SgbFrameReadyEvent::class,
              Sound.SoundSampleEvent::class,
              RumbleEvent::class,
              Joypad.JoypadPressEvent::class,
          ),
      )
    }
    val session =
        Session(
            if (state != null) config.forRestore() else config,
            sessionEventBus,
            if (player == localPlayer) console else null,
            links.serial[player],
            links.infrared[player],
        )
    if (state != null) {
      session.gameboy.restoreFromMemento(state)
    }

    // This is primarily for a ROM reload/reset arriving a few frames late. Initial four-player
    // startup is held at frame zero until every session exists.
    var current = sessionFrame
    while (current < frame) {
      stateHistory.setPlayerState(player, current, session.saveToMemento(), session.heldButtons)
      repeat(TICKS_PER_FRAME) { session.gameboy.tick() }
      current++
    }
    sessions[player] = session
  }

  private fun sendLocalRom(snapshot: ByteArray?) {
    val config = configs[localPlayer] ?: return
    val romBuffer = config.rom.file.toPath().readBytes()
    val saveFile = Cartridge.getSaveName(config.rom.file)
    val batteryBuffer = if (saveFile.exists()) saveFile.toPath().readBytes() else null

    eventBus.post(
        LocalRomLoadedEvent(
            romFile = romBuffer,
            batteryFile = batteryBuffer,
            snapshot = snapshot,
            gameboyType = config.gameboyType,
            bootstrapMode = config.bootstrapMode,
            frame = frame,
            cgb0Revision = config.isCgb0Revision,
            player = localPlayer,
        ))
  }

  override fun close() {}

  override fun closeWithState(): Controller.ControllerState? {
    doStop = true
    thread.join()

    val localSession = sessions[localPlayer]
    val state =
        localSession?.let { Controller.ControllerState(it.gameboy.saveToMemento(), it.config.rom) }

    localSession?.eventBus?.post(Controller.EmulationStoppedEvent())
    sessions.forEach { it?.close() }
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
      val cgb0Revision: Boolean = false,
      val player: Int = 0,
  ) : Event

  data class LocalButtonStateEvent(
      val frame: Long,
      val input: Input,
      val player: Int = 0,
  ) : Event

  data class RemoteButtonStateEvent(
      val frame: Long,
      val input: Input,
      val player: Int = 1,
  ) : Event

  data class LoadedLocalConfigEvent(val config: GameboyConfiguration, val snapshot: ByteArray?) :
      Event

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)
  }
}
