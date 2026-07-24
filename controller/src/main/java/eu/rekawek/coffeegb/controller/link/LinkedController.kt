package eu.rekawek.coffeegb.controller.link

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.Companion.createGameboyConfig
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.UpdatedSystemMappingEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.NetplayMementoCodec
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.TimingTicker
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.funnel
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.network.Connection.PeerEventSource
import eu.rekawek.coffeegb.controller.network.Connection.PeerEventSourceDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.Connection.ProtocolErrorReason
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ReceivedRemoteStopEvent
import eu.rekawek.coffeegb.controller.network.Connection.RequestResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.RequestStopEvent
import eu.rekawek.coffeegb.controller.network.Connection.SessionCheckpointEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerButtonStateEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerCheckpointEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerStateEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerResetEvent
import eu.rekawek.coffeegb.controller.network.Connection.ValidatedPeerStopEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.PeerFrameWindow
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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

  private val eventQueue =
      EventQueue(
          eventBus,
          StateLimits.NETPLAY_EVENT_QUEUE_EVENTS,
          StateLimits.NETPLAY_EVENT_QUEUE_BYTES,
          ::eventWeight,
          ::eventSource,
          StateLimits.NETPLAY_EVENT_QUEUE_SOURCE_EVENTS,
          StateLimits.NETPLAY_EVENT_QUEUE_SOURCE_BYTES,
          StateLimits.NETPLAY_EVENT_DISPATCH_EVENTS,
      )

  @VisibleForTesting internal val timingTicker = TimingTicker()

  private val sessions = MutableList<Session?>(mode.playerCount) { null }

  private val configs = MutableList<GameboyConfiguration?>(mode.playerCount) { null }

  private val romBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private val batteryBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private val links = StateHistory.createLinks(mode)

  @VisibleForTesting internal val stateHistory: StateHistory = StateHistory(mode)

  @VisibleForTesting
  internal fun mainHeldButtons() = sessions[localPlayer]?.heldButtons ?: emptySet()

  @VisibleForTesting internal fun activeSessionCount() = sessions.count { it != null }

  @VisibleForTesting internal fun currentFrame() = frame

  @VisibleForTesting
  internal fun encodedSessionStates(): List<ByteArray?> =
      sessions.map { session -> session?.let { NetplayMementoCodec.encodeSession(it.saveToMemento()) } }

  @VisibleForTesting
  internal fun heldButtonStates(): List<Set<Button>?> = sessions.map { it?.heldButtons }

  @Volatile private var doStop = false

  private var frame = 0L

  /** Runtime records older than the latest authoritative checkpoint belong to an old generation. */
  private var runtimeFrameFloor = 0L

  private var hostCheckpointPending = false

  private val replayWorkBySource = IdentityHashMap<PeerEventSource, StateChangeBudget>()

  private val disconnectedSources = ConcurrentLinkedQueue<PeerEventSource>()

  @VisibleForTesting internal var lastDispatchReplayFrames = 0L
    private set

  private var currentInput: Input? = null

  private var lastInput: Input? = null

  private var initialStateSynchronized =
      mode != LinkMode.FOUR_PLAYER_ADAPTER || localPlayer == 0

  private var lastSync: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

  private val thread = Thread {
    while (!doStop) {
      runFrame()
    }
  }

  init {
    require(localPlayer in 0 until mode.playerCount)

    eventQueue.register<LoadedLocalConfigEvent> { e ->
      val checkpoint = isFourPlayerHost()
      if (checkpoint) reconcileHistory()
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      configs[localPlayer] = e.config
      initSession(localPlayer, frame, e.snapshot?.let(NetplayMementoCodec::decodeGameboy), null)
      sendLocalRom(e.snapshot)
      if (checkpoint) commitHostCheckpoint()
    }

    eventQueue.register<StopEmulationEvent> {
      val checkpoint = isFourPlayerHost()
      if (checkpoint) reconcileHistory()
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      if (checkpoint) {
        commitHostCheckpoint()
      } else {
        eventBus.postAsync(RequestStopEvent(frame, localPlayer))
      }
    }

    eventQueue.register<ResetEmulationEvent> {
      reconcileHistory()
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      initSession(localPlayer, frame, null, null)
      if (isFourPlayerHost()) {
        commitHostCheckpoint()
      } else {
        commitStateBoundary()
      }
      eventBus.postAsync(RequestResetEvent(frame, localPlayer))
    }

    eventQueue.register<PeerLoadedGameEvent> { e ->
      val validated = validatePeerStateFrame(e) ?: return@register
      if (!consumeReplayWork(validated.frame, validated.source)) return@register
      val checkpoint = isFourPlayerHost() && validated.sessionSnapshot == null
      if (checkpoint) reconcileHistory()
      if (!loadPeerState(validated)) return@register
      eventBus.post(ValidatedPeerStateEvent(validated))
      if (checkpoint) {
        // The new physical port is now attached at this frame. Publish one checkpoint containing
        // every active console plus the shared adapter so old and new clients resume together.
        commitHostCheckpoint()
      }
    }

    eventQueue.register<SessionCheckpointEvent> { e ->
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER && localPlayer != 0) {
        if (!validatePeer(e.source) {
              PeerFrameWindow.validateCheckpoint(
                  e.frame,
                  e.states.map(PeerLoadedGameEvent::frame),
              )
            }) {
          return@register
        }
        if (!consumeReplayWork(
            e.frame,
            e.source,
            StateLimits.NETPLAY_STATE_CHANGE_FIXED_WORK * maxOf(1, e.states.size),
        )) {
          return@register
        }
        runtimeFrameFloor = e.frame
        val activePlayers = e.states.mapTo(mutableSetOf()) { it.player }
        sessions.indices.filterNot(activePlayers::contains).forEach { player ->
          sessions[player]?.close()
          sessions[player] = null
          configs[player] = null
          romBuffers[player] = null
          batteryBuffers[player] = null
        }
        e.states.forEach(::loadPeerState)
        frame = e.frame
        rebaseHistoryToLiveState()
        initialStateSynchronized = true
        eventBus.post(ValidatedPeerCheckpointEvent(e))
      }
    }

    eventQueue.register<ServerPlayerDisconnectedEvent> { e ->
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER && localPlayer == 0 && e.player in sessions.indices) {
        reconcileHistory()
        sessions[e.player]?.close()
        sessions[e.player] = null
        configs[e.player] = null
        romBuffers[e.player] = null
        batteryBuffers[e.player] = null
        commitHostCheckpoint()
      }
    }

    eventQueue.register<RemoteButtonStateEvent> { e ->
      if (e.player != localPlayer &&
          e.player in sessions.indices &&
          validateRuntimeFrame(e.frame, e.source)) {
        stateHistory.addSecondaryInput(e.player, e.frame, e.input)
        eventBus.post(ValidatedPeerButtonStateEvent(e))
      }
    }

    eventQueue.register<ReceivedRemoteResetEvent> { e ->
      if (e.player != localPlayer &&
          e.player in sessions.indices &&
          validateRuntimeFrame(e.frame, e.source) &&
          consumeReplayWork(e.frame, e.source)) {
        reconcileHistory()
        sessions[e.player]?.close()
        sessions[e.player] = null
        initSession(e.player, e.frame, null, null)
        if (isFourPlayerHost()) {
          commitHostCheckpoint()
        } else {
          commitStateBoundary()
        }
        eventBus.post(ValidatedPeerResetEvent(e))
      }
    }

    eventQueue.register<ReceivedRemoteStopEvent> { e ->
      if (e.player != localPlayer &&
          e.player in sessions.indices &&
          validateRuntimeFrame(e.frame, e.source)) {
        // A stopped port is already at the requested topology. Treat repeats as cheap no-ops so
        // they cannot force unbounded reconciliation and checkpoint fanout.
        if (sessions[e.player] == null) return@register
        if (!consumeReplayWork(e.frame, e.source)) return@register
        reconcileHistory()
        sessions[e.player]?.close()
        sessions[e.player] = null
        if (isFourPlayerHost()) {
          commitHostCheckpoint()
        } else {
          commitStateBoundary()
        }
        eventBus.post(ValidatedPeerStopEvent(e))
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
      eventBus.post(
          LoadedLocalConfigEvent(
              config = config,
              snapshot = it.memento?.let(NetplayMementoCodec::encodeGameboy),
          ))
    }

    eventBus.register<UpdatedSystemMappingEvent> {
      sessions[localPlayer]?.config?.let { config ->
        val newType = Controller.getGameboyType(properties.system, config.rom)
        val newBootstrapMode = properties.system.bootstrapMode
        if (newType != config.gameboyType || newBootstrapMode != config.bootstrapMode) {
          eventBus.post(LoadRomEvent(config.rom.file))
        }
      }
    }

    // This subscription intentionally bypasses the bounded event queue: disconnect cleanup must
    // be able to release the exact old connection's retained budget even when that queue is full.
    eventBus.register<PeerEventSourceDisconnectedEvent> {
      eventQueue.discardSource(it.source)
      disconnectedSources += it.source
    }
  }

  override fun startController() {
    thread.start()
  }

  fun runFrame() {
    while (true) {
      replayWorkBySource.remove(disconnectedSources.poll() ?: break)
    }
    lastDispatchReplayFrames = 0
    eventQueue.dispatch()

    // A DMG-07 host runs with any number of attached ports. A client waits only for the coherent
    // group checkpoint sent after the host hot-plugs it; normal two-player link keeps its original
    // both-ROM startup behavior.
    val waitingForInitialState =
        if (mode == LinkMode.FOUR_PLAYER_ADAPTER) !initialStateSynchronized
        else sessions.any { it == null }
    if (waitingForInitialState) {
      if (!timingTicker.disabled) {
        Thread.sleep(1)
      }
      return
    }

    reconcileHistory()
    flushHostCheckpoint()

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

    sessions[localPlayer]?.let { effectiveInput.send(it.eventBus) }

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

  private fun initSession(
      player: Int,
      sessionFrame: Long,
      state: Memento<Gameboy>?,
      sessionState: Memento<Session>?,
      heldButtons: Set<Button> = emptySet(),
      hotPlug: Boolean = false,
  ) {
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
            if (state != null || sessionState != null) config.forRestore() else config,
            sessionEventBus,
            if (player == localPlayer) console else null,
            links.serial[player],
            links.infrared[player],
        )
    if (sessionState != null) {
      session.restoreFromMemento(sessionState)
      session.heldButtons = heldButtons
    } else if (state != null) {
      session.gameboy.restoreFromMemento(state)
    }

    // A four-player ROM arriving at the host is a hot-plug at the current adapter phase. Session
    // checkpoints are already at their advertised frame. Other late reset/reload events retain
    // the historical catch-up path.
    var current = sessionFrame
    val alreadyAtSharedPhase = sessionState != null || hotPlug
    while (!alreadyAtSharedPhase && current < frame) {
      stateHistory.setPlayerState(player, current, session.saveToMemento(), session.heldButtons)
      repeat(TICKS_PER_FRAME) { session.gameboy.tick() }
      current++
    }
    sessions[player] = session
  }

  private fun loadPeerState(e: PeerLoadedGameEvent): Boolean {
    if (e.player !in sessions.indices ||
        (e.player == localPlayer && e.sessionSnapshot == null)) {
      return false
    }
    val newPort = configs[e.player] == null
    configs[e.player] =
        createGameboyConfig(properties, Rom(e.rom))
            .setGameboyType(e.gameboyType)
            .setBootstrapMode(e.bootstrapMode)
            .setCgb0Revision(e.cgb0Revision)
            .setBatteryData(e.battery)
    romBuffers[e.player] = e.rom
    batteryBuffers[e.player] = e.battery
    sessions[e.player]?.close()
    sessions[e.player] = null
    initSession(
        e.player,
        e.frame,
        e.decodedSnapshot ?: e.snapshot?.let(NetplayMementoCodec::decodeGameboy),
        e.decodedSessionSnapshot
            ?: e.sessionSnapshot?.let(NetplayMementoCodec::decodeSession),
        e.heldButtons,
        hotPlug =
            mode == LinkMode.FOUR_PLAYER_ADAPTER &&
                localPlayer == 0 &&
                e.player != localPlayer &&
                newPort,
    )
    return true
  }

  private fun validatePeerStateFrame(event: PeerLoadedGameEvent): PeerLoadedGameEvent? {
    // A new DMG-07 port is attached to the host's current adapter phase without replaying the
    // joining console's private pre-link timeline. All other peer state is bounded against the
    // controller-owned frame before any live configuration or session is replaced.
    if (mode == LinkMode.FOUR_PLAYER_ADAPTER &&
        localPlayer == 0 &&
        event.player in configs.indices &&
        configs[event.player] == null) {
      return if (validatePeer(event.source) {
            PeerFrameWindow.validateRuntimeFrame(event.frame, 0)
          }) {
        event.copy(frame = frame)
      } else {
        null
      }
    }
    return if (validateRuntimeFrame(event.frame, event.source)) event else null
  }

  private fun validateRuntimeFrame(peerFrame: Long, source: PeerEventSource?): Boolean {
    if (peerFrame < runtimeFrameFloor) {
      LOG.atDebug().log(
          "Discarding player {} frame {} from before checkpoint floor {}",
          source?.player?.plus(1),
          peerFrame,
          runtimeFrameFloor,
      )
      return false
    }
    return validatePeer(source) { PeerFrameWindow.validateRuntimeFrame(peerFrame, frame) }
  }

  private fun consumeReplayWork(
      peerFrame: Long,
      source: PeerEventSource?,
      fixedWork: Long = StateLimits.NETPLAY_STATE_CHANGE_FIXED_WORK,
  ): Boolean {
    source ?: return true
    val budget =
        replayWorkBySource.getOrPut(source) {
          StateChangeBudget(StateLimits.NETPLAY_REPLAY_WORK_FRAMES, frame)
        }
    val elapsedFrames = (frame - budget.lastFrame).coerceAtLeast(0)
    val refill =
        try {
          Math.multiplyExact(elapsedFrames, StateLimits.NETPLAY_STATE_CHANGE_REFILL_PER_FRAME)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }
    budget.available =
        minOf(
            StateLimits.NETPLAY_REPLAY_WORK_FRAMES,
            if (refill > StateLimits.NETPLAY_REPLAY_WORK_FRAMES - budget.available) {
              StateLimits.NETPLAY_REPLAY_WORK_FRAMES
            } else {
              budget.available + refill
            },
        )
    budget.lastFrame = frame
    val requested =
        maxOf(
            (frame - peerFrame).coerceAtLeast(0),
            fixedWork,
        )
    if (requested > budget.available) {
      val failure =
          IOException(
              "Player ${source.player + 1} exceeded the " +
                  "${StateLimits.NETPLAY_REPLAY_WORK_FRAMES}-frame replay budget")
      eventQueue.discardSource(source)
      source.reject(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK, failure)
      return false
    }
    budget.available -= requested
    lastDispatchReplayFrames += requested
    return true
  }

  private inline fun validatePeer(source: PeerEventSource?, validation: () -> Unit): Boolean =
      try {
        validation()
        true
      } catch (e: IOException) {
        LOG.info("Rejecting player {} frame at controller frame {}: {}",
            source?.player?.plus(1), frame, e.message)
        source?.let(eventQueue::discardSource)
        source?.reject(ProtocolErrorReason.INVALID_FRAME, e)
        false
      }

  private fun sendLocalRom(snapshot: ByteArray?) {
    val config = configs[localPlayer] ?: return
    val romBuffer = config.rom.file.toPath().readBytes()
    val saveFile = Cartridge.getSaveName(config.rom.file)
    val batteryBuffer = if (saveFile.exists()) saveFile.toPath().readBytes() else null
    romBuffers[localPlayer] = romBuffer
    batteryBuffers[localPlayer] = batteryBuffer

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

  private fun broadcastCurrentState() {
    val states =
        sessions.mapIndexedNotNull { player, session ->
          val config = configs[player] ?: return@mapIndexedNotNull null
          session ?: return@mapIndexedNotNull null
          val romBuffer = romBuffers[player] ?: return@mapIndexedNotNull null
          LocalRomLoadedEvent(
              romFile = romBuffer,
              batteryFile = batteryBuffers[player],
              snapshot = null,
              gameboyType = config.gameboyType,
              bootstrapMode = config.bootstrapMode,
              frame = frame,
              cgb0Revision = config.isCgb0Revision,
              player = player,
              sessionSnapshot = NetplayMementoCodec.encodeSession(session.saveToMemento()),
              heldButtons = session.heldButtons,
          )
        }
    eventBus.post(SessionStateReadyEvent(frame, states))
  }

  private fun isFourPlayerHost(): Boolean =
      mode == LinkMode.FOUR_PLAYER_ADAPTER && localPlayer == 0

  /** Applies every accepted old-generation patch before a topology snapshot is captured. */
  private fun reconcileHistory() {
    if (!stateHistory.merge(configs)) return
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

  /** Publishes and installs the same exact generation boundary used by remote clients. */
  private fun commitHostCheckpoint() {
    commitStateBoundary()
    hostCheckpointPending = true
  }

  /** Coalesces mutations in one dispatch and captures any same-frame patches after the boundary. */
  private fun flushHostCheckpoint() {
    if (!hostCheckpointPending) return
    commitStateBoundary()
    broadcastCurrentState()
    hostCheckpointPending = false
  }

  /** Ends the old input/history generation after a reset or topology mutation. */
  private fun commitStateBoundary() {
    runtimeFrameFloor = frame
    rebaseHistoryToLiveState()
  }

  private fun rebaseHistoryToLiveState() {
    stateHistory.clear()
    stateHistory.addState(
        frame,
        List(mode.playerCount) { Input(emptyList(), emptyList()) },
        sessions.map { it?.saveToMemento() },
        sessions.map { it?.heldButtons ?: emptySet() },
    )
  }

  override fun close() {}

  private fun eventWeight(event: Event): Long =
      when (event) {
        is PeerLoadedGameEvent -> peerStateWeight(event)
        is SessionCheckpointEvent ->
            event.states.fold(0L) { total, state ->
              Math.addExact(total, peerStateWeight(state))
            }
        else -> 64L
      }

  private fun eventSource(event: Event): Any? =
      when (event) {
        is PeerLoadedGameEvent -> event.source
        is SessionCheckpointEvent -> event.source
        is RemoteButtonStateEvent -> event.source
        is ReceivedRemoteResetEvent -> event.source
        is ReceivedRemoteStopEvent -> event.source
        else -> null
      }

  private fun peerStateWeight(event: PeerLoadedGameEvent): Long {
    return listOf(event.rom, event.battery, event.snapshot, event.sessionSnapshot)
        .filterNotNull()
        .fold(0L) { total, value -> Math.addExact(total, value.size.toLong()) }
  }

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
      val sessionSnapshot: ByteArray? = null,
      val heldButtons: Set<Button> = emptySet(),
  ) : Event

  data class SessionStateReadyEvent(val frame: Long, val states: List<LocalRomLoadedEvent>) : Event

  data class LocalButtonStateEvent(
      val frame: Long,
      val input: Input,
      val player: Int = 0,
  ) : Event

  data class RemoteButtonStateEvent(
      val frame: Long,
      val input: Input,
      val player: Int = 1,
      internal val source: PeerEventSource? = null,
  ) : Event

  data class LoadedLocalConfigEvent(val config: GameboyConfiguration, val snapshot: ByteArray?) :
      Event

  private data class StateChangeBudget(var available: Long, var lastFrame: Long)

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)
  }
}
