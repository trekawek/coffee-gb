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
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.TimingTicker
import eu.rekawek.coffeegb.controller.state.ApplyStage
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.LinkedPlayerState
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.LinkedTopologyState
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.SerialPeripheralState
import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
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
import eu.rekawek.coffeegb.controller.network.Connection
import eu.rekawek.coffeegb.controller.network.ConnectionController.ClientProtocolErrorEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerProtocolErrorEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.ServerPlayerDisconnectedEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopClientEvent
import eu.rekawek.coffeegb.controller.network.ConnectionController.StopServerEvent
import eu.rekawek.coffeegb.controller.network.PeerFrameWindow
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
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

  private val slotRomBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private val batteryBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private var links = StateHistory.createLinks(mode)

  /** Basic-controller state retained when an unsupported local profile is rejected pre-session. */
  private var rejectedLocalState: Controller.ControllerState? = null

  @VisibleForTesting internal val stateHistory: StateHistory = StateHistory(mode)

  @VisibleForTesting
  internal fun mainHeldButtons() = sessions[localPlayer]?.heldButtons ?: emptySet()

  @VisibleForTesting internal fun activeSessionCount() = sessions.count { it != null }

  @VisibleForTesting internal fun currentFrame() = frame

  @VisibleForTesting
  internal fun mainEffectivePressedButtons() =
      sessions[localPlayer]?.gameboy?.pressedButtons ?: emptySet()

  @VisibleForTesting internal fun meteredWorkFrames() = workProgressFrame

  @VisibleForTesting
  internal fun encodedSessionStates(): List<ByteArray?> =
      sessions.map { session ->
        session?.let {
          StateCodec.encode(StateCodec.capture(it), StateCompression.DEFLATE)
        }
      }

  @VisibleForTesting
  internal fun heldButtonStates(): List<Set<Button>?> = sessions.map { it?.heldButtons }

  @VisibleForTesting
  internal fun releasedInputSourceAssignments(): List<Boolean?> =
      configs.map { config ->
        config?.let { it.playerInputSource === PlayerInputSource.RELEASED }
      }

  /** Captures controller-owned frame, topology, session machines, endpoints, and held input. */
  internal fun captureDetachedState(): LinkedSessionState =
      LinkedSessionState(
          frame,
          localPlayer,
          if (mode == LinkMode.NORMAL) LinkedTopologyState.NORMAL
          else LinkedTopologyState.FOUR_PLAYER_ADAPTER,
          (0 until CANONICAL_PLAYER_SLOTS).map { player ->
            LinkedPlayerState(player, sessions.getOrNull(player)?.captureDetachedState())
          },
      )

  /** Captures ROM/profile identities for the same canonical slots as [captureDetachedState]. */
  internal fun capturePortableIdentities(): List<StateIdentityEntry> =
      (0 until CANONICAL_PLAYER_SLOTS).map { player ->
        val session = sessions.getOrNull(player)
        StateIdentityEntry(player, session?.let { StateIdentity.from(it.config) })
      }

  /** Applies an already-configured linked group at the owning controller frame safe point. */
  internal fun restoreDetachedState(
      state: LinkedSessionState,
      probe: ((Int, ApplyStage) -> Unit)? = null,
  ) {
    validateLinkedState(state)
    val active =
        sessions.indices.mapNotNull { player ->
          val session = sessions[player] ?: return@mapNotNull null
          val sessionState = state.players[player].session ?: return@mapNotNull null
          Triple(player, session, DetachedStateAdapter.prepare(session, sessionState))
        }
    // Prepare rollback records before the first mutation as part of the same transaction.
    val rollbackState = captureDetachedState()
    val rollback =
        sessions.indices.mapNotNull { player ->
          val session = sessions[player] ?: return@mapNotNull null
          val sessionState = rollbackState.players[player].session ?: return@mapNotNull null
          Triple(player, session, DetachedStateAdapter.prepare(session, sessionState))
        }
    val oldFrame = frame
    val oldRuntimeFrameFloor = runtimeFrameFloor
    val oldCurrentInput = currentInput
    val oldLastInput = lastInput
    try {
      active.forEach { (player, session, prepared) ->
        probe?.invoke(player, ApplyStage.BEFORE_LIVE_MUTATION)
        DetachedStateAdapter.commit(session, prepared) { stage -> probe?.invoke(player, stage) }
      }
      frame = state.frame
      runtimeFrameFloor = frame
      currentInput = null
      lastInput = null
      rebaseHistoryToLiveState()
    } catch (failure: Throwable) {
      try {
        rollback.forEach { (_, session, prepared) ->
          DetachedStateAdapter.commit(session, prepared)
        }
        frame = oldFrame
        runtimeFrameFloor = oldRuntimeFrameFloor
        currentInput = oldCurrentInput
        lastInput = oldLastInput
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Linked session state could not be applied atomically", failure)
    }
  }

  private fun validateLinkedState(state: LinkedSessionState) {
    if (state.frame < 0 || state.frame > StateLimits.NETPLAY_MAX_FRAME) {
      throw StateApplyException("Detached linked frame ${state.frame} is outside the supported range")
    }
    if (state.localPlayer != localPlayer) {
      throw StateApplyException(
          "Detached local player ${state.localPlayer} does not match controller player $localPlayer")
    }
    val expectedTopology =
        if (mode == LinkMode.NORMAL) LinkedTopologyState.NORMAL
        else LinkedTopologyState.FOUR_PLAYER_ADAPTER
    if (state.topology != expectedTopology) {
      throw StateApplyException(
          "Detached ${state.topology} topology does not match $expectedTopology")
    }
    if (state.players.size != CANONICAL_PLAYER_SLOTS ||
        state.players.indices.any { state.players[it].player != it }) {
      throw StateApplyException("Detached linked state requires canonical player indices 0..3")
    }
    if (mode == LinkMode.NORMAL && state.players.drop(mode.playerCount).any { it.session != null }) {
      throw StateApplyException("Normal link state cannot populate four-player-only slots")
    }
    sessions.indices.forEach { player ->
      if ((sessions[player] == null) != (state.players[player].session == null)) {
        throw StateApplyException("Detached player $player does not match the active-session shape")
      }
    }
    val activeStates = state.players.mapNotNull(LinkedPlayerState::session)
    val expectedPeripheral =
        if (mode == LinkMode.NORMAL) SerialPeripheralState.PEER_TO_PEER
        else SerialPeripheralState.FOUR_PLAYER_ADAPTER
    if (activeStates.any { it.serialPeripheral != expectedPeripheral }) {
      throw StateApplyException("Detached serial endpoint identity does not match linked topology")
    }
    if (mode == LinkMode.FOUR_PLAYER_ADAPTER &&
        activeStates.map { it.serialState }.distinct().size > 1) {
      throw StateApplyException("Detached four-player sessions disagree on shared adapter state")
    }
  }

  @Volatile private var doStop = false

  private var frame = 0L

  /** Counts only emulator frames actually run; peer-controlled rebases cannot advance it. */
  private var workProgressFrame = 0L

  /** Runtime records older than the latest authoritative checkpoint belong to an old generation. */
  private var runtimeFrameFloor = 0L

  private var hostCheckpointPending = false

  private var localCheckpointCreditPending = false

  private val peerWorkBySource = IdentityHashMap<PeerEventSource, PeerWorkBudget>()

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
      requireCompatibleLinkedClock(configs.toMutableList().also { it[localPlayer] = e.config })
      if (sessions.all { it == null }) {
        links = StateHistory.createLinks(mode, e.config.clockSpec)
      }
      val checkpoint = isFourPlayerHost()
      if (checkpoint) reconcileHistory()
      sessions[localPlayer]?.close()
      sessions[localPlayer] = null
      // Protocol v8 owns linked P1 at frame boundaries and cannot represent local SGB P2-P4.
      // Never allow an asynchronous platform source into any linked machine.
      e.config.setPlayerInputSource(PlayerInputSource.RELEASED)
      configs[localPlayer] = e.config
      initSession(localPlayer, frame, e.snapshot)
      sendLocalRom(includeState = e.snapshot != null)
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
      initSession(localPlayer, frame, null)
      if (isFourPlayerHost()) {
        commitHostCheckpoint()
      } else {
        commitStateBoundary()
        if (mode == LinkMode.FOUR_PLAYER_ADAPTER) localCheckpointCreditPending = true
      }
      eventBus.postAsync(RequestResetEvent(frame, localPlayer))
    }

    eventQueue.register<PeerLoadedGameEvent> { e ->
      val validated = validatePeerStateFrame(e) ?: return@register
      if (!consumeReplayWork(validated.frame, validated.source)) return@register
      val checkpoint = isFourPlayerHost()
      if (!loadPeerState(validated, reconcileBeforeCommit = checkpoint)) return@register
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
        if (!consumeCheckpointWork(e.source)) {
          return@register
        }
        if (!applyPeerCheckpoint(e)) return@register
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
        slotRomBuffers[e.player] = null
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
        initSession(e.player, e.frame, null)
        if (isFourPlayerHost()) {
          commitHostCheckpoint()
        } else {
          commitStateBoundary()
          if (mode == LinkMode.FOUR_PLAYER_ADAPTER) grantCheckpointCredit(e.source)
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

    // Desktop logical P1 is translated to the existing frame-owned protocol input. P2-P4 are
    // local SGB controller slots and remain unavailable in linked mode until a versioned protocol
    // can transmit and replay them deterministically.
    eventQueue.register<LogicalPlayerButtonPressEvent> { e ->
      if (e.player != 0) return@register
      val input = currentInput ?: Input(emptyList(), emptyList())
      currentInput =
          input.copy(
              pressedButtons = (input.pressedButtons + e.button).sorted(),
              releasedButtons = (input.releasedButtons - e.button).sorted(),
          )
    }

    eventQueue.register<LogicalPlayerButtonReleaseEvent> { e ->
      if (e.player != 0) return@register
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
      if (config.hardwareProfile == HardwareProfileRegistry.SGB ||
          config.hardwareProfile == HardwareProfileRegistry.SGB2) {
        // Protocol v8 is permanently StateFile-v1-only. Its SGB RTC phase has the released legacy
        // meaning, while exact-clock SGB/SGB2 captures require v2. Reject before a linked Gameboy
        // is built or any live session/config/topology state changes, and retain the incoming Basic
        // state so transport shutdown can return to the exact pre-link machine.
        rejectedLocalState =
            it.state?.let { state -> Controller.ControllerState(state, rom) }
        val message =
            "SGB-family netplay is unavailable: protocol v8 negotiates StateFile v1 with " +
                "legacy SGB RTC phase semantics; exact-clock ${config.hardwareProfile.id()} requires v2"
        if (localPlayer == 0) {
          eventBus.post(ServerProtocolErrorEvent(localPlayer, message))
          eventBus.postAsync(StopServerEvent())
        } else {
          eventBus.post(ClientProtocolErrorEvent(message))
          eventBus.postAsync(StopClientEvent())
        }
        return@register
      }
      rejectedLocalState = null
      eventBus.post(Controller.GameboyTypeEvent(config.gameboyType))
      eventBus.post(Controller.HardwareProfileEvent(config.hardwareProfile))
      eventBus.post(Controller.SessionPauseSupportEvent(false))
      eventBus.post(Controller.SessionSnapshotSupportEvent(null))
      eventBus.post(Controller.EmulationStartedEvent(rom.title))
      eventBus.post(
          LoadedLocalConfigEvent(
              config = config,
              snapshot = it.state,
          ))
    }

    eventBus.register<UpdatedSystemMappingEvent> {
      sessions[localPlayer]?.config?.let { config ->
        val newProfile = Controller.getHardwareProfile(properties.system, config.rom)
        val newBootstrapMode = properties.system.bootstrapMode
        if (newProfile != config.hardwareProfile || newBootstrapMode != config.bootstrapMode) {
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
      peerWorkBySource.remove(disconnectedSources.poll() ?: break)
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
        sessions.map { it?.captureDetachedState() },
        sessions.map { it?.heldButtons ?: emptySet() },
    )

    sessions[localPlayer]?.let { effectiveInput.send(it.eventBus) }

    if (!effectiveInput.isEmpty() || lastSync.elapsedNow() > 5.seconds) {
      eventBus.postAsync(LocalButtonStateEvent(frame, effectiveInput, localPlayer))
      lastSync = TimeSource.Monotonic.markNow()
    }

    val clockSpec = requireCompatibleLinkedClock(configs)
    repeat(clockSpec.controllerTicksPerFrame()) {
      sessions.forEach { it?.gameboy?.tick() }
      timingTicker.run(clockSpec)
    }

    frame++
    workProgressFrame++
  }

  private fun initSession(
      player: Int,
      sessionFrame: Long,
      state: MachineState?,
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
            if (state != null) config.forRestore() else config,
            sessionEventBus,
            if (player == localPlayer) console else null,
            links.serial[player],
            links.infrared[player],
        )
    if (state != null) {
      DetachedStateAdapter.apply(session.gameboy, state)
    }

    var current = sessionFrame
    while (current < frame) {
      stateHistory.setPlayerState(player, current, session.captureDetachedState(), session.heldButtons)
      repeat(session.gameboy.clockSpec.controllerTicksPerFrame()) { session.gameboy.tick() }
      current++
    }
    sessions[player] = session
  }

  private fun loadPeerState(
      e: PeerLoadedGameEvent,
      reconcileBeforeCommit: Boolean = false,
  ): Boolean {
    if (e.player !in sessions.indices || e.player == localPlayer) return false
    val hotPlug =
        mode == LinkMode.FOUR_PLAYER_ADAPTER &&
            localPlayer == 0 &&
            configs[e.player] == null
    val prepared =
        try {
          preparePeerReplacement(e, links, requireSession = false, hotPlug = hotPlug)
        } catch (failure: Throwable) {
          rejectPeerState(e.source, e.player, failure)
          return false
        }
    try {
      requireCompatibleLinkedClock(configs.toMutableList().also { it[e.player] = prepared.config })
    } catch (failure: Throwable) {
      prepared.session.close()
      rejectPeerState(e.source, e.player, failure)
      return false
    }
    val previous = sessions[e.player]
    val oldConfig = configs[e.player]
    val oldRom = romBuffers[e.player]
    val oldSlotRom = slotRomBuffers[e.player]
    val oldBattery = batteryBuffers[e.player]
    val oldFrame = frame
    val oldRuntimeFloor = runtimeFrameFloor
    val oldHistory = stateHistory.captureSnapshot()
    val oldSessionStates = sessions.map { it?.captureDetachedState() }
    val oldHeldButtons = sessions.map { it?.heldButtons ?: emptySet() }
    try {
      if (reconcileBeforeCommit) reconcileHistory()
      sessions[e.player] = prepared.session
      configs[e.player] = prepared.config
      romBuffers[e.player] = prepared.rom
      slotRomBuffers[e.player] = prepared.slotRom
      batteryBuffers[e.player] = prepared.battery
      var current = prepared.frame
      while (!prepared.hotPlug && current < frame) {
        stateHistory.setPlayerState(
            e.player,
            current,
            prepared.session.captureDetachedState(),
            prepared.session.heldButtons,
        )
        repeat(prepared.session.gameboy.clockSpec.controllerTicksPerFrame()) {
          prepared.session.gameboy.tick()
        }
        current++
      }
    } catch (failure: Throwable) {
      sessions[e.player] = previous
      configs[e.player] = oldConfig
      romBuffers[e.player] = oldRom
      slotRomBuffers[e.player] = oldSlotRom
      batteryBuffers[e.player] = oldBattery
      frame = oldFrame
      runtimeFrameFloor = oldRuntimeFloor
      stateHistory.restoreSnapshot(oldHistory)
      try {
        sessions.indices.forEach { player ->
          val session = sessions[player]
          val state = oldSessionStates[player]
          if (session != null && state != null) {
            session.restoreDetachedState(state)
            session.heldButtons = oldHeldButtons[player]
          }
        }
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      prepared.session.close()
      rejectPeerState(e.source, e.player, failure)
      return false
    }
    previous?.close()
    return true
  }

  private fun applyPeerCheckpoint(event: SessionCheckpointEvent): Boolean {
    val prepared = arrayOfNulls<PreparedPeerReplacement>(mode.playerCount)
    lateinit var candidateLinks: StateHistory.Links
    try {
      val candidateConfigs = arrayOfNulls<GameboyConfiguration>(mode.playerCount)
      event.states.forEach { state ->
        if (state.player !in candidateConfigs.indices || candidateConfigs[state.player] != null) {
          throw StateApplyException("Checkpoint has duplicate or invalid player ${state.player}")
        }
        candidateConfigs[state.player] = peerConfiguration(state)
      }
      val clockSpec = requireCompatibleLinkedClock(candidateConfigs.toList())
      candidateLinks = StateHistory.createLinks(mode, clockSpec)
      event.states.forEach { state ->
        prepared[state.player] =
            preparePeerReplacement(
                state,
                candidateLinks,
                requireSession = true,
                hotPlug = true,
                config = requireNotNull(candidateConfigs[state.player]),
            )
      }
      val adapterStates =
          event.states.map {
            (it.portableState?.root as SessionStateRoot).session.serialState
          }
      if (adapterStates.isNotEmpty() && adapterStates.distinct().size != 1) {
        throw StateApplyException("Checkpoint sessions disagree on shared adapter state")
      }
    } catch (failure: Throwable) {
      prepared.filterNotNull().forEach { replacement -> replacement.session.close() }
      rejectPeerState(event.source, event.source?.player ?: -1, failure)
      return false
    }

    val replacementStates =
        prepared.map { replacement -> replacement?.session?.captureDetachedState() }
    val replacementButtons =
        prepared.map { replacement -> replacement?.session?.heldButtons ?: emptySet() }
    val oldSessions = sessions.toList()
    val oldConfigs = configs.toList()
    val oldRoms = romBuffers.toList()
    val oldSlotRoms = slotRomBuffers.toList()
    val oldBatteries = batteryBuffers.toList()
    val oldLinks = links
    val oldFrame = frame
    val oldRuntimeFloor = runtimeFrameFloor
    val oldHistory = stateHistory.captureSnapshot()
    try {
      links = candidateLinks
      sessions.indices.forEach { player ->
        val replacement = prepared[player]
        sessions[player] = replacement?.session
        configs[player] = replacement?.config
        romBuffers[player] = replacement?.rom
        slotRomBuffers[player] = replacement?.slotRom
        batteryBuffers[player] = replacement?.battery
      }
      frame = event.frame
      runtimeFrameFloor = event.frame
      stateHistory.replaceWithState(frame, replacementStates, replacementButtons)
    } catch (failure: Throwable) {
      links = oldLinks
      sessions.indices.forEach { player ->
        sessions[player] = oldSessions[player]
        configs[player] = oldConfigs[player]
        romBuffers[player] = oldRoms[player]
        slotRomBuffers[player] = oldSlotRoms[player]
        batteryBuffers[player] = oldBatteries[player]
      }
      frame = oldFrame
      runtimeFrameFloor = oldRuntimeFloor
      stateHistory.restoreSnapshot(oldHistory)
      prepared.filterNotNull().forEach { replacement -> replacement.session.close() }
      rejectPeerState(event.source, event.source?.player ?: -1, failure)
      return false
    }
    oldSessions.forEach { old ->
      try {
        old?.close()
      } catch (failure: Throwable) {
        LOG.warn("Unable to close replaced linked session", failure)
      }
    }
    return true
  }

  private fun preparePeerReplacement(
      event: PeerLoadedGameEvent,
      candidateLinks: StateHistory.Links,
      requireSession: Boolean,
      hotPlug: Boolean,
      config: GameboyConfiguration = peerConfiguration(event),
  ): PreparedPeerReplacement {
    var candidate: Session? = null
    try {
      val root = event.portableState?.root
      if (requireSession && root !is SessionStateRoot) {
        throw StateApplyException("Checkpoint player ${event.player} requires a SESSION StateFile")
      }
      if (!requireSession && root != null && root !is MachineStateRoot) {
        throw StateApplyException("Initial player state requires a MACHINE StateFile")
      }
      candidate =
          Session(
              if (root != null) config.forRestore() else config,
              createSessionEventBus(event.player),
              if (event.player == localPlayer) console else null,
              candidateLinks.serial[event.player],
              candidateLinks.infrared[event.player],
          )
      when (root) {
        is MachineStateRoot -> DetachedStateAdapter.apply(candidate.gameboy, root.machine)
        is SessionStateRoot -> DetachedStateAdapter.apply(candidate, root.session)
        null -> candidate.heldButtons = event.heldButtons
        else -> throw StateApplyException("Unsupported peer state root ${root.kind}")
      }
      if (root !is SessionStateRoot) candidate.heldButtons = event.heldButtons

      return PreparedPeerReplacement(
          candidate,
          config,
          event.rom,
          event.slotRom,
          event.battery,
          event.frame,
          hotPlug,
      )
    } catch (failure: Throwable) {
      candidate?.close()
      throw StateApplyException(
          "Checkpoint player ${event.player + 1} failed preparation",
          failure,
      )
    }
  }

  private fun peerConfiguration(event: PeerLoadedGameEvent): GameboyConfiguration =
      Connection.peerConfiguration(
              event.rom,
              event.slotRom,
              event.battery,
              event.gameboyType,
              event.bootstrapMode,
              event.cgb0Revision,
              event.mealybugDmgBlob,
              event.codeBreakerRumble,
              event.displaySgbBorder,
          )
          .setPlayerInputSource(PlayerInputSource.RELEASED)

  private fun createSessionEventBus(player: Int): EventBusImpl =
      EventBusImpl(null, null, false).also { sessionEventBus ->
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
      }

  private fun rejectPeerState(
      source: PeerEventSource?,
      player: Int,
      failure: Throwable,
  ) {
    val error =
        IOException(
            "Player ${player + 1} portable state could not be prepared atomically",
            failure,
        )
    LOG.info("Rejecting player {} state: {}", player + 1, failure.message)
    source?.let(eventQueue::discardSource)
    source?.reject(ProtocolErrorReason.INVALID_PORTABLE_STATE, error)
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
    val budget = peerWorkBudget(source)
    val requested = maxOf((frame - peerFrame).coerceAtLeast(0), fixedWork)
    if (requested > budget.replayAvailable) {
      rejectExcessiveWork(source, StateLimits.NETPLAY_REPLAY_WORK_FRAMES, "replay")
      return false
    }
    budget.replayAvailable -= requested
    lastDispatchReplayFrames += requested
    return true
  }

  private fun consumeCheckpointWork(source: PeerEventSource?): Boolean {
    source ?: return true
    val budget = peerWorkBudget(source)
    val credited = budget.checkpointCreditPending || localCheckpointCreditPending
    budget.checkpointCreditPending = false
    localCheckpointCreditPending = false
    if (credited) return true

    val requested = StateLimits.NETPLAY_STATE_CHANGE_FIXED_WORK
    if (requested > budget.checkpointAvailable) {
      rejectExcessiveWork(source, StateLimits.NETPLAY_CHECKPOINT_WORK_FRAMES, "checkpoint")
      return false
    }
    budget.checkpointAvailable -= requested
    lastDispatchReplayFrames += requested
    return true
  }

  /** A validated RESET and the next authoritative checkpoint are one logical transition. */
  private fun grantCheckpointCredit(source: PeerEventSource?) {
    source ?: return
    peerWorkBudget(source).checkpointCreditPending = true
  }

  private fun peerWorkBudget(source: PeerEventSource): PeerWorkBudget {
    val budget =
        peerWorkBySource.getOrPut(source) {
          PeerWorkBudget(
              StateLimits.NETPLAY_REPLAY_WORK_FRAMES,
              StateLimits.NETPLAY_CHECKPOINT_WORK_FRAMES,
              workProgressFrame,
          )
        }
    val elapsedFrames = (workProgressFrame - budget.lastProgressFrame).coerceAtLeast(0)
    val refill =
        try {
          Math.multiplyExact(elapsedFrames, StateLimits.NETPLAY_STATE_CHANGE_REFILL_PER_FRAME)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }
    budget.replayAvailable =
        refillWork(budget.replayAvailable, StateLimits.NETPLAY_REPLAY_WORK_FRAMES, refill)
    budget.checkpointAvailable =
        refillWork(
            budget.checkpointAvailable,
            StateLimits.NETPLAY_CHECKPOINT_WORK_FRAMES,
            refill,
        )
    budget.lastProgressFrame = workProgressFrame
    return budget
  }

  private fun refillWork(available: Long, limit: Long, refill: Long): Long =
      if (refill > limit - available) limit else available + refill

  private fun rejectExcessiveWork(source: PeerEventSource, limit: Long, kind: String) {
    val failure =
        IOException(
            "Player ${source.player + 1} exceeded the $limit-frame $kind work budget")
    eventQueue.discardSource(source)
    source.reject(ProtocolErrorReason.EXCESSIVE_REPLAY_WORK, failure)
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

  private fun sendLocalRom(includeState: Boolean) {
    val config = configs[localPlayer] ?: return
    val session = sessions[localPlayer] ?: return
    val romBuffer = config.rom.file.toPath().readBytes()
    val slotRomBuffer = config.slotRom?.file?.toPath()?.readBytes()
    val saveFile = Cartridge.getSaveName(config.rom.file)
    val batteryBuffer = if (saveFile.exists()) saveFile.toPath().readBytes() else null
    romBuffers[localPlayer] = romBuffer
    slotRomBuffers[localPlayer] = slotRomBuffer
    batteryBuffers[localPlayer] = batteryBuffer
    val portableState =
        if (includeState) {
          StateCodec.encode(
              StateCodec.capture(config, session.gameboy),
              StateCompression.DEFLATE,
          )
        } else {
          null
        }

    eventBus.post(
        LocalRomLoadedEvent(
            romFile = romBuffer,
            slotRomFile = slotRomBuffer,
            batteryFile = batteryBuffer,
            portableState = portableState,
            gameboyType = config.gameboyType,
            bootstrapMode = config.bootstrapMode,
            frame = frame,
            cgb0Revision = config.isCgb0Revision,
            hardwareProfileId = config.hardwareProfile.id(),
            mealybugDmgBlob = config.isMealybugDmgBlob,
            codeBreakerRumble = config.isCodeBreakerRumble,
            displaySgbBorder = config.isDisplaySgbBorder,
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
              slotRomFile = slotRomBuffers[player],
              batteryFile = batteryBuffers[player],
              portableState =
                  StateCodec.encode(
                      StateCodec.capture(session),
                      StateCompression.DEFLATE,
                  ),
              gameboyType = config.gameboyType,
              bootstrapMode = config.bootstrapMode,
              frame = frame,
              cgb0Revision = config.isCgb0Revision,
              hardwareProfileId = config.hardwareProfile.id(),
              mealybugDmgBlob = config.isMealybugDmgBlob,
              codeBreakerRumble = config.isCodeBreakerRumble,
              displaySgbBorder = config.isDisplaySgbBorder,
              player = player,
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
      val state = head.sessionStates[player]
      if (session != null && state != null) {
        session.restoreDetachedState(state)
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
    val sessionStates = sessions.map { it?.captureDetachedState() }
    val heldButtons = sessions.map { it?.heldButtons ?: emptySet() }
    stateHistory.clear()
    stateHistory.addState(
        frame,
        List(mode.playerCount) { Input(emptyList(), emptyList()) },
        sessionStates,
        heldButtons,
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
    return listOf(event.rom, event.slotRom, event.battery)
        .filterNotNull()
        .fold(event.stateDecodedBytes.toLong()) { total, value ->
          Math.addExact(total, value.size.toLong())
        }
  }

  override fun closeWithState(): Controller.ControllerState? {
    doStop = true
    thread.join()

    val localSession = sessions[localPlayer]
    val state =
        localSession?.let {
          Controller.ControllerState(DetachedStateAdapter.capture(it.gameboy), it.config.rom)
        } ?: rejectedLocalState

    localSession?.eventBus?.post(Controller.EmulationStoppedEvent())
    sessions.forEach { it?.close() }
    eventBus.close()

    return state
  }

  data class LocalRomLoadedEvent(
      val romFile: ByteArray,
      val batteryFile: ByteArray?,
      val portableState: ByteArray?,
      val gameboyType: GameboyType,
      val bootstrapMode: Gameboy.BootstrapMode,
      val frame: Long,
      val cgb0Revision: Boolean = false,
      val hardwareProfileId: String =
          when {
            gameboyType == GameboyType.CGB && cgb0Revision -> HardwareProfileRegistry.CGB0.id()
            gameboyType == GameboyType.CGB -> HardwareProfileRegistry.CGB.id()
            gameboyType == GameboyType.SGB -> HardwareProfileRegistry.SGB.id()
            else -> HardwareProfileRegistry.DMG.id()
          },
      val player: Int = 0,
      val heldButtons: Set<Button> = emptySet(),
      val slotRomFile: ByteArray? = null,
      val mealybugDmgBlob: Boolean = false,
      val codeBreakerRumble: Boolean = false,
      val displaySgbBorder: Boolean = false,
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

  data class LoadedLocalConfigEvent(
      val config: GameboyConfiguration,
      val snapshot: MachineState?,
  ) : Event

  private data class PreparedPeerReplacement(
      val session: Session,
      val config: GameboyConfiguration,
      val rom: ByteArray,
      val slotRom: ByteArray?,
      val battery: ByteArray?,
      val frame: Long,
      val hotPlug: Boolean,
  )

  private data class PeerWorkBudget(
      var replayAvailable: Long,
      var checkpointAvailable: Long,
      var lastProgressFrame: Long,
      var checkpointCreditPending: Boolean = false,
  )

  private companion object {
    const val CANONICAL_PLAYER_SLOTS = 4
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)
  }
}
