package eu.rekawek.coffeegb.controller.link

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.Companion.createGameboyConfig
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.ResetEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.StopEmulationEvent
import eu.rekawek.coffeegb.controller.Controller.UpdatedSystemMappingEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.PreparedSession
import eu.rekawek.coffeegb.controller.RetainedClosePersistence
import eu.rekawek.coffeegb.controller.RomSessionPreparer
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.TimingTicker
import eu.rekawek.coffeegb.controller.stagedEventBus
import eu.rekawek.coffeegb.controller.state.ApplyStage
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.LinkedPlayerState
import eu.rekawek.coffeegb.controller.state.LinkedSessionState
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateProfilePolicy
import eu.rekawek.coffeegb.controller.state.LinkedTopologyState
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.PreparedMachineState
import eu.rekawek.coffeegb.controller.state.PreparedSessionState
import eu.rekawek.coffeegb.controller.state.SerialPeripheralState
import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.owningFunnel
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
import eu.rekawek.coffeegb.controller.network.NetplayRollbackMetrics
import eu.rekawek.coffeegb.controller.network.NetplayRollbackMetricsSnapshot
import eu.rekawek.coffeegb.controller.network.NetplayRollbackReason
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointCommitCompletion
import eu.rekawek.coffeegb.controller.network.v9.V9CapturedCheckpoint
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointKind
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointProvider
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointRequest
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointPrepareCompletion
import eu.rekawek.coffeegb.controller.network.v9.V9CheckpointTarget
import eu.rekawek.coffeegb.controller.network.v9.V9ErrorCode
import eu.rekawek.coffeegb.controller.network.v9.V9GameplayCompletion
import eu.rekawek.coffeegb.controller.network.v9.V9GameplayTarget
import eu.rekawek.coffeegb.controller.network.v9.V9InputState
import eu.rekawek.coffeegb.controller.network.v9.V9RuntimeControl
import eu.rekawek.coffeegb.controller.network.v9.V9RuntimeMessageKind
import eu.rekawek.coffeegb.controller.network.v9.V9TargetGeneration
import eu.rekawek.coffeegb.controller.network.v9.V9PreparedCheckpoint
import eu.rekawek.coffeegb.controller.network.v9.V9ValidatedCheckpoint
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.events.EventBusTeardownTimeoutException
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceFailedEvent
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Runs every Game Boy in a netplay session locally and rolls them back as remote input arrives. */
class LinkedController(
    private val parentEventBus: EventBus,
    private val properties: EmulatorProperties,
    private val console: Console?,
    private val mode: LinkMode = LinkMode.NORMAL,
    private val localPlayer: Int = 0,
    private val closeTimeoutMillis: Long = CONTROLLER_CLOSE_TIMEOUT_MILLIS,
) : Controller {

  private val eventBus = parentEventBus.fork("session")

  /** One ingress sequence preserves arrival order across the control and peer event queues. */
  private val incomingEventSequence = AtomicLong()

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
          incomingEventSequence::getAndIncrement,
      )

  /**
   * Frame-owned open/control commands that remain dispatchable while [eventQueue] and every linked
   * machine are frozen. Stop/reset can cancel work here, but their session mutation waits until the
   * persistence worker has physically unwound.
   */
  private val localOpenEventQueue =
      EventQueue(
          eventBus,
          maxEvents = LOCAL_OPEN_QUEUE_EVENTS,
          maxBytes = LOCAL_OPEN_QUEUE_BYTES,
          eventWeight = { LOCAL_OPEN_EVENT_WEIGHT },
          maxSourceEvents = LOCAL_OPEN_QUEUE_EVENTS,
          maxSourceBytes = LOCAL_OPEN_QUEUE_BYTES,
          maxDispatchEvents = LOCAL_OPEN_QUEUE_EVENTS,
          eventOrder = incomingEventSequence::getAndIncrement,
      )

  @VisibleForTesting internal val timingTicker = TimingTicker()

  @VisibleForTesting
  internal var persistLocalBatteryCapture: (BatteryFlush) -> BatteryPersistenceResult =
      BatteryFlush::persist

  @VisibleForTesting
  internal var persistLocalCloseCapture: (BatteryFlush) -> BatteryPersistenceResult =
      BatteryFlush::persist

  private val localSessionPreparer =
      RomSessionPreparer(configure = { it.setPlayerInputSource(PlayerInputSource.RELEASED) })

  @VisibleForTesting
  internal var prepareLocalRom: (LoadRomEvent) -> PreparedSession = { event ->
    localSessionPreparer.prepare(properties, event)
  }

  @VisibleForTesting
  internal var materializeLocalSession:
      (PreparedSession, Long, StateHistory.Links) -> Session =
      { prepared, sessionFrame, candidateLinks ->
        val gameboy = prepared.materialize()
        createInitializedSession(
            localPlayer,
            prepared.config,
            sessionFrame,
            state = null,
            staged = true,
            prebuiltGameboy = gameboy,
            sessionLinks = candidateLinks,
        )
      }

  @VisibleForTesting internal var localPayloadProbe: (() -> Unit)? = null

  @VisibleForTesting internal var localCandidateDiscardProbe: ((Session) -> Unit)? = null

  private val localLoadExecutor: ExecutorService =
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "coffee-gb-linked-rom-loader").apply { isDaemon = true }
      }

  private val localLoadLock = Any()

  private var localLoadJob: LocalLoadJob? = null

  private var localReplacementJob: LocalReplacementJob? = null

  private var nextLocalPersistenceRequestId = 1L

  private val localLoadExecutorClosed = AtomicBoolean()

  /** Includes a cancelled callable until its body actually returns, not merely Future.isDone. */
  private val localWorkerTasks = AtomicInteger()

  /** Stop/reset waits here after cancelling an open until its physical worker has drained. */
  private var pendingLocalSessionCommand: LocalSessionCommand? = null

  /** Retryable persistence/materialization transaction for a local stop or reset. */
  private var localSessionCommandJob: LocalSessionCommandJob? = null

  private var closeCapture: BatteryFlush? = null

  private var closeState: Controller.ControllerState? = null

  private var closeRequestId: Long? = null

  private var closePersistenceAttempt: RetainedClosePersistence? = null

  private var closed = false

  private val sessions = MutableList<Session?>(mode.playerCount) { null }

  private val configs = MutableList<GameboyConfiguration?>(mode.playerCount) { null }

  private val romBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private val slotRomBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private val batteryBuffers = MutableList<ByteArray?>(mode.playerCount) { null }

  private var links = StateHistory.createLinks(mode)

  /** Basic-controller state retained when an unsupported local profile is rejected pre-session. */
  private var rejectedLocalState: Controller.ControllerState? = null

  @VisibleForTesting internal val stateHistory: StateHistory = StateHistory(mode)

  private val rollbackMetrics = NetplayRollbackMetrics(StateHistory.MAX_HISTORY_STATES)

  internal fun rollbackMetricsSource(): NetplaySnapshotSource<NetplayRollbackMetricsSnapshot> =
      rollbackMetrics

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

  /** Exact v9 logical roster: normal is 0/1, four-player is always logical 0..3. */
  private fun captureV9PortableIdentities(): List<StateIdentityEntry> =
      capturePortableIdentities().take(if (mode == LinkMode.NORMAL) 2 else CANONICAL_PLAYER_SLOTS)

  /** Explicit opt-in protocol-v9 target. The default v8 controller wiring never constructs it. */
  internal fun createV9Target(): V9LinkedControllerTarget =
      V9LinkedControllerTarget(this)

  @VisibleForTesting
  internal var v9ApplyProbe: ((Int, ApplyStage) -> Unit)? = null

  internal fun enqueueV9CheckpointPreparation(
      preparation: V9PendingCheckpointPreparation,
      lease: V9TargetLease,
  ) {
    eventBus.post(V9CheckpointPrepareEvent(preparation, lease))
  }

  internal fun enqueueV9CheckpointCommit(
      transaction: V9LinkedPreparedCheckpoint,
  ) {
    eventBus.post(V9CheckpointCommitEvent(transaction))
  }

  internal fun enqueueV9CheckpointCapture(
      request: V9CheckpointRequest,
      capture: V9PendingCheckpointCapture,
      lease: V9TargetLease,
  ) {
    eventBus.post(V9CheckpointCaptureEvent(request, capture, lease))
  }

  internal fun enqueueV9GenerationCapture(
      capture: V9PendingGenerationCapture,
      lease: V9TargetLease,
  ) {
    eventBus.post(V9GenerationCaptureEvent(capture, lease))
  }

  internal fun enqueueV9Disconnect(player: Int) {
    eventBus.post(V9TargetDisconnectedEvent(player))
  }

  internal fun enqueueV9Input(value: V9InputState) {
    enqueueV9Input(value, V9GameplayCompletion {})
  }

  internal fun enqueueV9Input(value: V9InputState, completion: V9GameplayCompletion) {
    eventBus.post(V9RemoteInputEvent(value, completion, null))
  }

  internal fun enqueueV9Input(
      value: V9InputState,
      completion: V9GameplayCompletion,
      lease: V9TargetLease,
  ) {
    eventBus.post(V9RemoteInputEvent(value, completion, lease))
  }

  internal fun enqueueV9Control(
      value: V9RuntimeControl,
      completion: V9GameplayCompletion,
      lease: V9TargetLease,
  ) {
    eventBus.post(V9RemoteControlEvent(value, completion, lease))
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
    val oldHistory = stateHistory.captureSnapshot()
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
      rollbackMetrics.recordCheckpoint(NetplayRollbackReason.CHECKPOINT)
    } catch (failure: Throwable) {
      try {
        rollback.forEach { (_, session, prepared) ->
          DetachedStateAdapter.commit(session, prepared)
        }
        frame = oldFrame
        runtimeFrameFloor = oldRuntimeFrameFloor
        currentInput = oldCurrentInput
        lastInput = oldLastInput
        stateHistory.restoreSnapshot(oldHistory)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("Linked session state could not be applied atomically", failure)
    }
  }

  /** Applies only the reconstruction retained by prepare; candidate state is never rebuilt here. */
  private fun applyV9Checkpoint(transaction: V9LinkedPreparedCheckpoint) {
    val prepared = transaction.prepared
    val currentGeneration = captureV9TargetGeneration()
    if (!prepared.generation.sameIdentityGeneration(currentGeneration) ||
        sessions.indices.any { sessions[it] !== prepared.targetSessions[it] } ||
        links !== prepared.targetLinks ||
        captureV9PortableIdentities() != prepared.targetIdentities) {
      throw StateApplyException("V9 checkpoint target changed after preparation")
    }

    // Prepare a complete rollback against the current live generation before the first mutation.
    val rollbackSessions =
        sessions.map { session ->
          session?.let { DetachedStateAdapter.prepare(it, it.captureDetachedState()) }
        }
    val oldSessions = sessions.toList()
    val oldConfigs = configs.toList()
    val oldRoms = romBuffers.toList()
    val oldSlotRoms = slotRomBuffers.toList()
    val oldBatteries = batteryBuffers.toList()
    val oldLinks = links
    val oldFrame = frame
    val oldFloor = runtimeFrameFloor
    val oldCurrentInput = currentInput
    val oldLastInput = lastInput
    val oldHistory = stateHistory.captureSnapshot()
    val oldV9Buttons = v9RemoteButtons.map { it?.toSet() }
    val oldInitialSynchronized = initialStateSynchronized
    val oldHostCheckpointPending = hostCheckpointPending
    val oldLocalCheckpointCreditPending = localCheckpointCreditPending
    try {
      prepared.players.forEach { player ->
        val session = player.session
        player.machine?.let {
          DetachedStateAdapter.commit(session.gameboy, it) { stage ->
            v9ApplyProbe?.invoke(player.player, stage)
          }
        }
        player.sessionState?.let {
          DetachedStateAdapter.commit(session, it) { stage ->
            v9ApplyProbe?.invoke(player.player, stage)
          }
        }
      }
      frame = prepared.frame
      runtimeFrameFloor = prepared.frame
      currentInput = null
      lastInput = null
      initialStateSynchronized = true
      rebaseHistoryToLiveState()
      syncV9RemoteButtons()
      rollbackMetrics.recordCheckpoint(NetplayRollbackReason.RESYNCHRONIZATION)
    } catch (failure: Throwable) {
      try {
        links = oldLinks
        sessions.indices.forEach { player ->
          sessions[player] = oldSessions[player]
          configs[player] = oldConfigs[player]
          romBuffers[player] = oldRoms[player]
          slotRomBuffers[player] = oldSlotRoms[player]
          batteryBuffers[player] = oldBatteries[player]
        }
        sessions.indices.forEach { player ->
          val session = sessions[player]
          val rollback = rollbackSessions[player]
          if (session != null && rollback != null) DetachedStateAdapter.commit(session, rollback)
          v9RemoteButtons[player] = oldV9Buttons[player]
        }
        frame = oldFrame
        runtimeFrameFloor = oldFloor
        currentInput = oldCurrentInput
        lastInput = oldLastInput
        initialStateSynchronized = oldInitialSynchronized
        hostCheckpointPending = oldHostCheckpointPending
        localCheckpointCreditPending = oldLocalCheckpointCreditPending
        stateHistory.restoreSnapshot(oldHistory)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      throw StateApplyException("V9 checkpoint could not be applied atomically", failure)
    }
  }

  /** Target-dependent reconstruction at the event safe point without any live mutation. */
  private fun prepareV9Checkpoint(
      checkpoint: V9ValidatedCheckpoint,
      generation: V9TargetGeneration,
  ): V9PreparedControllerState {
    val metadata = checkpoint.metadata
    if (metadata.frame < 0 || metadata.frame > StateLimits.NETPLAY_MAX_FRAME) {
      throw StateApplyException("V9 checkpoint frame is outside the controller range")
    }
    val currentGeneration = captureV9TargetGeneration()
    if (!generation.sameIdentityGeneration(currentGeneration)) {
      throw StateApplyException("V9 checkpoint identity/topology generation changed")
    }
    val identities = currentGeneration.identities
    val expected =
        if (metadata.kind == V9CheckpointKind.LINKED_SESSION) identities
        else {
          val identity = identities.singleOrNull {
            it.player == metadata.ownerPlayer && it.identity != null
          }?.identity ?: throw StateApplyException("V9 checkpoint owner is not active")
          listOf(StateIdentityEntry(0, identity))
        }
    StateCodec.validateForTarget(checkpoint.stateFile, metadata.kind.rootKind, expected)
    val preparedPlayers = mutableListOf<V9PreparedPlayerState>()
    var preparedFrame = metadata.frame
    when (val root = checkpoint.stateFile.root) {
      is eu.rekawek.coffeegb.controller.state.LinkedSessionStateRoot -> {
        if (mode != LinkMode.FOUR_PLAYER_ADAPTER || metadata.slotMask != 0x0f ||
            root.linked.localPlayer != 0) {
          throw StateApplyException("V9 linked checkpoint is not the canonical host-owned group")
        }
        val normalized =
            LinkedSessionState(
                root.linked.frame,
                localPlayer,
                root.linked.topology,
                root.linked.players,
            )
        validateLinkedState(normalized)
        preparedFrame = normalized.frame
        sessions.indices.forEach { player ->
          val session = sessions[player] ?: return@forEach
          val state = normalized.players[player].session ?: return@forEach
          preparedPlayers +=
              V9PreparedPlayerState(
                  player,
                  session,
                  sessionState = DetachedStateAdapter.prepare(session, state),
              )
        }
      }
      is MachineStateRoot -> {
        if (mode != LinkMode.NORMAL || metadata.slotMask != 1 shl metadata.ownerPlayer) {
          throw StateApplyException("V9 machine checkpoint does not match normal topology")
        }
        val session = sessions.getOrNull(metadata.ownerPlayer)
            ?: throw StateApplyException("V9 checkpoint owner is not active")
        preparedPlayers +=
            V9PreparedPlayerState(
                metadata.ownerPlayer,
                session,
                machine = DetachedStateAdapter.prepare(session.gameboy, root.machine),
            )
      }
      is SessionStateRoot -> {
        if (mode != LinkMode.NORMAL || metadata.slotMask != 1 shl metadata.ownerPlayer) {
          throw StateApplyException("V9 session checkpoint does not match normal topology")
        }
        val session = sessions.getOrNull(metadata.ownerPlayer)
            ?: throw StateApplyException("V9 checkpoint owner is not active")
        preparedPlayers +=
            V9PreparedPlayerState(
                metadata.ownerPlayer,
                session,
                sessionState = DetachedStateAdapter.prepare(session, root.session),
            )
      }
    }
    return V9PreparedControllerState(
        preparedFrame,
        sessions.toList(),
        identities,
        links,
        preparedPlayers.toList(),
        currentGeneration,
    )
  }

  /** Captures the requested direct v2 root and identity tuple at the controller event safe point. */
  private fun captureV9Checkpoint(request: V9CheckpointRequest): V9CapturedStateFile {
    if (request.frame != null && request.frame != frame ||
        frame < 0 || frame > StateLimits.NETPLAY_MAX_FRAME) {
      throw StateApplyException("V9 checkpoint capture frame is no longer current")
    }
    val generation = captureV9TargetGeneration()
    val file = when (request.kind) {
      V9CheckpointKind.MACHINE -> {
        if (mode != LinkMode.NORMAL || request.slotMask != 1 shl request.ownerPlayer) {
          throw StateApplyException("V9 MACHINE capture does not match normal topology")
        }
        val session = sessions.getOrNull(request.ownerPlayer)
            ?: throw StateApplyException("V9 checkpoint owner is not active")
        StateCodec.captureVersion2(session.config, session.gameboy)
      }
      V9CheckpointKind.SESSION -> {
        if (mode != LinkMode.NORMAL || request.slotMask != 1 shl request.ownerPlayer) {
          throw StateApplyException("V9 SESSION capture does not match normal topology")
        }
        StateCodec.captureVersion2(
            sessions.getOrNull(request.ownerPlayer)
                ?: throw StateApplyException("V9 checkpoint owner is not active"))
      }
      V9CheckpointKind.LINKED_SESSION -> {
        if (mode != LinkMode.FOUR_PLAYER_ADAPTER || request.slotMask != 0x0f ||
            request.ownerPlayer != 0) {
          throw StateApplyException("V9 LINKED_SESSION capture is not the canonical group")
        }
        StateCodec.captureVersion2(this)
      }
    }
    return V9CapturedStateFile(file, generation)
  }

  /** The only production identity/topology read used by v9; called at the event safe point. */
  private fun captureV9TargetGeneration(): V9TargetGeneration {
    val identities = captureV9PortableIdentities()
    val previous = v9GenerationKey
    if (previous == null || !previous.matches(sessions, links, identities)) {
      if (v9GenerationId == Long.MAX_VALUE) {
        throw StateApplyException("V9 identity generation is exhausted")
      }
      v9GenerationId++
      v9GenerationKey = V9GenerationKey(sessions.toList(), links, identities)
    }
    return V9TargetGeneration(
        v9GenerationId,
        if (mode == LinkMode.NORMAL) eu.rekawek.coffeegb.controller.network.v9.V9LinkMode.NORMAL
        else eu.rekawek.coffeegb.controller.network.v9.V9LinkMode.FOUR_PLAYER,
        frame,
        identities,
    )
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

  private var v9GenerationId = 0L

  private var v9GenerationKey: V9GenerationKey? = null

  private val peerWorkBySource = IdentityHashMap<PeerEventSource, PeerWorkBudget>()

  private val disconnectedSources = ConcurrentLinkedQueue<PeerEventSource>()

  @VisibleForTesting internal var lastDispatchReplayFrames = 0L
    private set

  @VisibleForTesting internal var timingFrameProbe: (() -> Unit)? = null

  @VisibleForTesting internal var localWorkerExitProbe: (() -> Unit)? = null

  private var currentInput: Input? = null

  private var lastInput: Input? = null

  /** Last v9 absolute masks translated at the same frame-safe event boundary. */
  private val v9RemoteButtons = arrayOfNulls<Set<Button>>(CANONICAL_PLAYER_SLOTS)

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

    localOpenEventQueue.register<LoadedLocalConfigEvent> { e ->
      beginLocalReplacement(e)
    }

    localOpenEventQueue.register<StopEmulationEvent> {
      requestLocalSessionCommand(LocalSessionCommand.STOP)
    }

    localOpenEventQueue.register<ResetEmulationEvent> {
      requestLocalSessionCommand(LocalSessionCommand.RESET)
    }

    eventQueue.register<PeerLoadedGameEvent> { e ->
      val validated = validatePeerStateFrame(e) ?: return@register
      if (!consumeReplayWork(validated.frame, validated.source)) return@register
      val checkpoint = isFourPlayerHost()
      if (!loadPeerState(validated, reconcileBeforeCommit = checkpoint)) return@register
      eventBus.postAsync(ValidatedPeerStateEvent(validated))
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
        eventBus.postAsync(ValidatedPeerCheckpointEvent(e))
      }
    }

    eventQueue.register<V9CheckpointPrepareEvent> { event ->
      if (!event.lease.runIfActive {
            event.preparation.execute { checkpoint, generation ->
              val prepared = prepareV9Checkpoint(checkpoint, generation)
              V9LinkedPreparedCheckpoint(
                  this,
                  checkpoint,
                  prepared,
                  event.lease,
                  event.preparation::release,
              )
            }
          }) {
        event.preparation.cancel()
      }
    }

    eventQueue.register<V9CheckpointCommitEvent> { event ->
      event.transaction.applyAtSafePoint { applyV9Checkpoint(event.transaction) }
    }

    eventQueue.register<V9CheckpointCaptureEvent> { event ->
      if (!event.lease.runIfActive {
            try {
              event.capture.complete(captureV9Checkpoint(event.request), null)
            } catch (_: Throwable) {
              event.capture.complete(null, V9ErrorCode.TOPOLOGY_MISMATCH)
            }
          }) {
        event.capture.complete(null, V9ErrorCode.CANCELLED)
      }
    }

    eventQueue.register<V9GenerationCaptureEvent> { event ->
      if (!event.lease.runIfActive {
            try {
              event.capture.complete(captureV9TargetGeneration(), null)
            } catch (_: Throwable) {
              event.capture.complete(null, V9ErrorCode.TOPOLOGY_MISMATCH)
            }
          }) {
        event.capture.complete(null, V9ErrorCode.CANCELLED)
      }
    }

    eventQueue.register<V9TargetDisconnectedEvent> { event ->
      if (event.player in sessions.indices && event.player != localPlayer) {
        applyV9InputEvent(
            V9RemoteInputEvent(
                V9InputState(frame, event.player, 0, 0xffff),
                V9GameplayCompletion {},
                null,
            ))
      }
    }

    eventQueue.register<V9RemoteInputEvent> { event ->
      val value = event.value
      val accepted = event.lease?.runIfActive {
        applyV9InputEvent(event)
      } ?: run {
        applyV9InputEvent(event)
        true
      }
      if (!accepted) event.completion.complete(V9ErrorCode.CANCELLED)
    }

    eventQueue.register<V9RemoteControlEvent> { event ->
      if (!event.lease.runIfActive { applyV9ControlEvent(event) }) {
        event.completion.complete(V9ErrorCode.CANCELLED)
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
        eventBus.postAsync(ValidatedPeerButtonStateEvent(e))
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
        v9RemoteButtons[e.player] = null
        if (isFourPlayerHost()) {
          commitHostCheckpoint()
        } else {
          commitStateBoundary(NetplayRollbackReason.RESET)
          if (mode == LinkMode.FOUR_PLAYER_ADAPTER) grantCheckpointCredit(e.source)
        }
        eventBus.postAsync(ValidatedPeerResetEvent(e))
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
        v9RemoteButtons[e.player] = null
        if (isFourPlayerHost()) {
          commitHostCheckpoint()
        } else {
          commitStateBoundary(NetplayRollbackReason.TOPOLOGY_CHANGE)
        }
        eventBus.postAsync(ValidatedPeerStopEvent(e))
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

    localOpenEventQueue.register<LoadRomEvent>(::requestLocalLoad)
    localOpenEventQueue.register<Controller.CancelRomOpenEvent> {
      cancelLocalOpenRequest(it.openRequestId)
    }
    localOpenEventQueue.register<Controller.RetryRomReplacementEvent> {
      if (!retryLocalSessionCommand(it.requestId)) {
        retryLocalReplacement(it.requestId)
      }
    }
    localOpenEventQueue.register<Controller.CancelRomReplacementEvent> {
      if (!cancelLocalSessionCommand(it.requestId)) {
        cancelLocalReplacement(it.requestId)
      }
    }

    eventBus.register<UpdatedSystemMappingEvent> {
      sessions[localPlayer]?.config?.let { config ->
        val newProfile = Controller.getHardwareProfile(properties.system, config.rom)
        val newBootstrapMode = properties.system.bootstrapMode
        if (newProfile != config.hardwareProfile || newBootstrapMode != config.bootstrapMode) {
          eventBus.post(LoadRomEvent(config.rom.image))
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
    timingFrameProbe?.invoke()
    processPendingWorkAtSafePoint()

    // Local preparation and battery persistence are deliberately owned by a worker. Hold every
    // linked machine and its queued topology/rollback commands at the last completed frame until
    // that worker commits, fails, or physically unwinds after cancellation. This keeps the
    // immutable battery capture as the final old-session generation and prevents a second writer.
    if (hasPendingLocalWork()) {
      if (!timingTicker.disabled) {
        Thread.sleep(1)
      }
      return
    }

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
    rollbackMetrics.updateHistory(stateHistory.entryCount())

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

  /**
   * Applies everything admitted at the current linked safe point without advancing any machine.
   * Network boundary code and its tests use the same operation when a capture or lifecycle
   * transition must complete at an exact frame.
   */
  internal fun processPendingWorkAtSafePoint() {
    while (true) {
      peerWorkBySource.remove(disconnectedSources.poll() ?: break)
    }
    lastDispatchReplayFrames = 0
    if (hasPendingLocalWork()) {
      cancelPendingWorkSupersededByQueuedCommand()
      dispatchUrgentLocalCommands()
    } else {
      dispatchOrderedIngress()
    }
    // Ordered dispatch may just have established a worker while another already-queued local
    // command supersedes it. Observe that intent before a manually driven frame waits for (and
    // could otherwise commit) the first worker.
    if (hasPendingLocalWork()) {
      cancelPendingWorkSupersededByQueuedCommand()
      dispatchUrgentLocalCommands()
    }
    if (isManuallyDriven()) {
      awaitLocalLoadForManualDrive()
    }
    finishPreparedLocalLoad()
    if (isManuallyDriven()) {
      awaitLocalReplacementForManualDrive()
    }
    finishLocalReplacement()
    finishPendingLocalSessionCommand()
    if (isManuallyDriven()) {
      awaitLocalSessionCommandForManualDrive()
    }
    finishLocalSessionCommand()
    if (!hasPendingLocalWork()) {
      dispatchOrderedIngress()
    }
  }

  private fun requestLocalLoad(event: LoadRomEvent) {
    if (doStop || localLoadExecutorClosed.get()) {
      return
    }
    // This event was ordered after any pending stop/reset in the frame-owned queue.
    pendingLocalSessionCommand = null
    cancelCurrentLocalSessionCommand()
    val token = LocalOpenToken(event)
    val task =
        LocalPreparationTask(
            Callable {
              ensureLocalLoadActive(token)
              val prepared = prepareLocalRom(event)
              ensureLocalLoadActive(token)
              if (StateProfilePolicy.protocolV8Representable(prepared.config.hardwareProfile)) {
                LocalLoadPreparation.Accepted(prepared)
              } else {
                prepared.discard()
                LocalLoadPreparation.Rejected(
                    prepared.config,
                    "Profile ${prepared.config.hardwareProfile.id()} netplay is unavailable: protocol v8 " +
                        "negotiates StateFile v1, while this profile requires explicit " +
                        "StateFile v2 identity",
                )
              }
            })
    val job = LocalLoadJob(token, task)
    val previous =
        synchronized(localLoadLock) {
          if (doStop || localLoadExecutorClosed.get()) {
            return
          }
          val old = localLoadJob to localReplacementJob
          localLoadJob = job
          localReplacementJob = null
          old
        }
    previous.first?.let { cancelLocalJob(it, notifyCancellation = true) }
    previous.second?.let { cancelLocalReplacement(it, notifyCancellation = true) }
    postHostEventSafely(Controller.RomLoadingEvent(event.rom, event.openRequestId))
    try {
      executeLocalTask(task)
    } catch (failure: RuntimeException) {
      finishLocalLoadFailure(token, failure)
    }
  }

  private fun finishPreparedLocalLoad() {
    val job = synchronized(localLoadLock) { localLoadJob } ?: return
    if (!job.task.isDone) {
      return
    }
    val prepared =
        try {
          job.task.take()
        } catch (_: CancellationException) {
          return
        } catch (failure: ExecutionException) {
          finishLocalLoadFailure(job.token, failure.cause ?: failure)
          return
        } catch (failure: Exception) {
          finishLocalLoadFailure(job.token, failure)
          return
        }
    if (!isCurrentLocalLoad(job)) {
      if (prepared is LocalLoadPreparation.Accepted) {
        prepared.prepared.discard()
      }
      return
    }
    when (prepared) {
      is LocalLoadPreparation.Accepted ->
          beginLocalReplacement(
              LoadedLocalConfigEvent(
                  config = prepared.prepared.config,
                  snapshot = job.token.event.state,
                  battery = null,
                  romFile = job.token.event.rom,
                  openRequestId = job.token.event.openRequestId,
              ),
              job,
              prepared.prepared,
          )
      is LocalLoadPreparation.Rejected -> rejectLocalProfile(job, prepared)
    }
  }

  private fun rejectLocalProfile(
      job: LocalLoadJob,
      rejected: LocalLoadPreparation.Rejected,
  ) {
    if (!finishLocalToken(job.token, expectedLoad = job)) {
      return
    }
    // Protocol v8 is permanently StateFile-v1-only. Retain an incoming Basic-controller state so
    // transport shutdown can return to that exact pre-link machine.
    rejectedLocalState =
        job.token.event.state?.let { state ->
          Controller.ControllerState(state, rejected.config.rom)
        }
    postHostEventSafely(
        Controller.LoadRomFailedEvent(
            job.token.event.rom,
            rejected.message,
            job.token.event.openRequestId,
        ))
    if (localPlayer == 0) {
      eventBus.post(ServerProtocolErrorEvent(localPlayer, rejected.message))
      parentEventBus.postAsync(StopServerEvent())
    } else {
      eventBus.post(ClientProtocolErrorEvent(rejected.message))
      parentEventBus.postAsync(StopClientEvent())
    }
  }

  private fun beginLocalReplacement(
      event: LoadedLocalConfigEvent,
      preparedJob: LocalLoadJob? = null,
      transferredPrepared: PreparedSession? = null,
  ) {
    if (preparedJob == null) {
      // Direct prepared-config events are load commands and supersede an earlier queued stop/reset.
      pendingLocalSessionCommand = null
      cancelCurrentLocalSessionCommand()
    }
    val token =
        preparedJob?.token
            ?: LocalOpenToken(
                LoadRomEvent(
                    event.romFile,
                    event.snapshot,
                    openRequestId = event.openRequestId,
                ),
            )
    if (token.cancelled.get()) {
      return
    }
    try {
      requireCompatibleLinkedClock(
          configs.toMutableList().also { it[localPlayer] = event.config })
    } catch (failure: Exception) {
      transferredPrepared?.discard()
      finishLocalLoadFailure(token, failure, preparedJob)
      return
    }

    // Protocol v8 owns linked P1 at frame boundaries and cannot represent local SGB P2-P4.
    // Apply this before materialization: Joypad captures the configured source in its constructor.
    event.config.setPlayerInputSource(PlayerInputSource.RELEASED)
    val previousSession = sessions[localPlayer]
    val capture = previousSession?.gameboy?.prepareCartridgeFlush()
    val candidateLinks =
        if (sessions.all { it == null }) {
          StateHistory.createLinks(mode, event.config.clockSpec)
        } else {
          links
        }
    val prepared =
        transferredPrepared
            ?: event.snapshot?.let { PreparedSession.FromDetachedState(event.config, it) }
            ?: PreparedSession.Deferred(event.config)
    val replacement =
        LocalReplacementJob(
            requestId = nextLocalPersistenceRequestId++,
            token = token,
            event = event,
            previousSession = previousSession,
            capture = capture,
            prepared = prepared,
            candidateLinks = candidateLinks,
        )
    replacement.attempt = createLocalReplacementTask(replacement)
    val installed =
        synchronized(localLoadLock) {
          if (token.cancelled.get() ||
              token.terminal.get() ||
              (preparedJob != null && localLoadJob !== preparedJob) ||
              (preparedJob == null &&
                  (localLoadJob != null || localReplacementJob != null))) {
            false
          } else {
            if (preparedJob != null) {
              localLoadJob = null
            }
            localReplacementJob = replacement
            true
          }
        }
    if (!installed) {
      replacement.attempt?.cancelAndDiscard()
      replacement.prepared.discard()
      return
    }
    submitLocalReplacementAttempt(replacement)
  }

  private fun createLocalReplacementTask(
      replacement: LocalReplacementJob,
  ): LocalReplacementTask =
      LocalReplacementTask(
          Callable {
            ensureLocalLoadActive(replacement.token)
            val persistence =
                replacement.capture?.let(persistLocalBatteryCapture)
                    ?: BatteryPersistenceResult.Success(0)
            ensureLocalLoadActive(replacement.token)
            if (persistence is BatteryPersistenceResult.Failure) {
              LocalReplacementResult.PersistenceFailure(persistence)
            } else {
              var candidate: Session? = null
              try {
                validateLocalBatterySidecars(replacement.event.config, replacement.token)
                val stagedCandidate =
                    materializeLocalSession(
                        replacement.prepared,
                        frame,
                        replacement.candidateLinks,
                    )
                candidate = stagedCandidate
                ensureLocalLoadActive(replacement.token)
                // Materialization owns legacy-sidecar migration and atomic-backup recovery. Read
                // the target afterwards so the peer receives the same recovered generation.
                val battery =
                    replacement.event.battery
                        ?: readLocalBattery(replacement.event.config, replacement.token)
                ensureLocalLoadActive(replacement.token)
                val outbound =
                    prepareLocalRomPayload(
                        stagedCandidate,
                        replacement.event.config,
                        includeState = replacement.event.snapshot != null,
                        batteryBuffer = battery,
                        payloadFrame = frame,
                    )
                ensureLocalLoadActive(replacement.token)
                val ready =
                    LocalReplacementResult.Ready(
                        persistence as BatteryPersistenceResult.Success,
                        battery,
                        stagedCandidate,
                        outbound,
                    )
                candidate = null
                ready
              } finally {
                candidate?.let(::discardWorkerCandidate)
              }
            }
          })

  private fun discardWorkerCandidate(candidate: Session) {
    try {
      localCandidateDiscardProbe?.invoke(candidate)
    } finally {
      candidate.discardUnstarted()
    }
  }

  private fun submitLocalReplacementAttempt(replacement: LocalReplacementJob) {
    val attempt = replacement.attempt ?: return
    try {
      executeLocalTask(attempt)
    } catch (failure: RuntimeException) {
      synchronized(localLoadLock) {
        if (localReplacementJob === replacement) {
          localReplacementJob = null
        }
      }
      attempt.cancelAndDiscard()
      replacement.prepared.discard()
      finishLocalLoadFailure(replacement.token, failure)
    }
  }

  private fun finishLocalReplacement() {
    val replacement = synchronized(localLoadLock) { localReplacementJob } ?: return
    val attempt = replacement.attempt ?: return
    if (!attempt.isDone) {
      return
    }
    val result =
        try {
          attempt.take()
        } catch (_: CancellationException) {
          return
        } catch (failure: ExecutionException) {
          replacement.prepared.discard()
          val cause = failure.cause ?: failure
          if (cause is IOException) {
            if (finishLocalToken(replacement.token)) {
              reportLocalBatteryFailure(
                  replacement.event.config,
                  replacement.event.romFile,
                  cause,
                  replacement.token.event.openRequestId,
              )
            }
          } else {
            finishLocalLoadFailure(replacement.token, cause)
          }
          return
        } catch (failure: Exception) {
          replacement.prepared.discard()
          finishLocalLoadFailure(replacement.token, failure)
          return
        }

    if (!isCurrentLocalReplacement(replacement)) {
      if (result is LocalReplacementResult.Ready) {
        discardWorkerCandidate(result.candidate)
      }
      return
    }
    when (result) {
      is LocalReplacementResult.PersistenceFailure -> {
        synchronized(localLoadLock) {
          if (localReplacementJob === replacement) {
            replacement.attempt = null
          }
        }
        reportLocalBatteryPersistenceFailure(replacement, result.failure)
      }
      is LocalReplacementResult.Ready -> activateLocalReplacement(replacement, result)
    }
  }

  private fun activateLocalReplacement(
      replacement: LocalReplacementJob,
      ready: LocalReplacementResult.Ready,
  ) {
    if (!isCurrentLocalReplacement(replacement)) {
      return
    }
    replacement.capture?.complete(ready.persistence)

    val checkpoint = isFourPlayerHost()
    if (checkpoint) reconcileHistory()
    val candidate = ready.candidate

    var previousOwnershipChanged = false
    val committed =
        synchronized(localLoadLock) {
          if (localReplacementJob !== replacement ||
              replacement.token.cancelled.get() ||
              replacement.token.terminal.get()) {
            false
          } else if (sessions[localPlayer] !== replacement.previousSession) {
            previousOwnershipChanged = true
            false
          } else if (!replacement.token.terminal.compareAndSet(false, true)) {
            false
          } else {
            localReplacementJob = null
            links = replacement.candidateLinks
            sessions[localPlayer] = candidate
            configs[localPlayer] = replacement.event.config
            rejectedLocalState = null
            true
          }
        }
    if (!committed) {
      try {
        candidate.discardUnstarted()
      } catch (cleanupFailure: RuntimeException) {
        LOG.warn("Unable to discard stale linked replacement candidate", cleanupFailure)
      }
      if (previousOwnershipChanged) {
        finishLocalLoadFailure(
            replacement.token,
            IllegalStateException("Linked session ownership changed before replacement commit"),
        )
      }
      return
    }

    // This assignment is the replacement ownership commit. Only now may host lifecycle
    // subscribers release input, rumble and UI state for the old machine.
    replacement.previousSession?.let { previous ->
      postHostEventSafely(Controller.EmulationStoppedEvent())
      try {
        previous.closeAfterCartridgeFlush()
      } catch (cleanupFailure: RuntimeException) {
        LOG.warn(
            "Old linked session cleanup failed after replacement ownership committed",
            cleanupFailure,
        )
      }
    }
    try {
      candidate.activate()
    } catch (activationFailure: RuntimeException) {
      // Ownership is already committed and the previous session has been released. Retain the
      // candidate and keep the timing loop alive; rolling back would reattach a stopped machine.
      LOG.error("Linked ROM ownership committed but candidate activation failed", activationFailure)
      reportLocalLoadFailure(
          replacement.event.romFile,
          activationFailure,
          replacement.token.event.openRequestId,
      )
      return
    }
    postHostEventSafely(Controller.GameboyTypeEvent(replacement.event.config.gameboyType))
    postHostEventSafely(
        Controller.HardwareProfileEvent(replacement.event.config.hardwareProfile))
    postHostEventSafely(Controller.SessionPauseSupportEvent(false))
    postHostEventSafely(Controller.SessionSnapshotSupportEvent(null))
    postHostEventSafely(
        Controller.EmulationStartedEvent(
            replacement.event.config.rom.title,
            replacement.event.config.rom.origin,
            replacement.token.event.openRequestId,
        ))
    publishPreparedLocalRom(ready.outbound)
    if (checkpoint) commitHostCheckpoint()
  }

  private fun retryLocalReplacement(requestId: Long) {
    val replacement =
        synchronized(localLoadLock) {
          localReplacementJob?.takeIf {
            it.requestId == requestId && it.attempt == null && !it.token.cancelled.get()
          }?.also {
            it.attempt = createLocalReplacementTask(it)
          }
        } ?: return
    submitLocalReplacementAttempt(replacement)
  }

  private fun cancelLocalReplacement(requestId: Long) {
    val replacement =
        synchronized(localLoadLock) {
          localReplacementJob?.takeIf { it.requestId == requestId }?.also {
            localReplacementJob = null
          }
        } ?: return
    cancelLocalReplacement(replacement, notifyCancellation = true)
  }

  private fun cancelLocalOpenRequest(openRequestId: Long) {
    val cancelled =
        synchronized(localLoadLock) {
          val load =
              localLoadJob?.takeIf { it.token.event.openRequestId == openRequestId }?.also {
                localLoadJob = null
              }
          val replacement =
              localReplacementJob
                  ?.takeIf { it.token.event.openRequestId == openRequestId }
                  ?.also { localReplacementJob = null }
          load to replacement
        }
    cancelled.first?.let { cancelLocalJob(it, notifyCancellation = true) }
    cancelled.second?.let { cancelLocalReplacement(it, notifyCancellation = true) }
  }

  private fun cancelAllLocalOpens(notifyCancellation: Boolean) {
    val cancelled =
        synchronized(localLoadLock) {
          val jobs = localLoadJob to localReplacementJob
          localLoadJob = null
          localReplacementJob = null
          jobs
        }
    cancelled.first?.let { cancelLocalJob(it, notifyCancellation) }
    cancelled.second?.let { cancelLocalReplacement(it, notifyCancellation) }
  }

  private fun cancelLocalJob(job: LocalLoadJob, notifyCancellation: Boolean) {
    job.token.cancelled.set(true)
    job.task.cancel(true)
    finishLocalCancellation(job.token, notifyCancellation)
  }

  private fun cancelLocalReplacement(
      replacement: LocalReplacementJob,
      notifyCancellation: Boolean,
  ) {
    replacement.token.cancelled.set(true)
    replacement.attempt?.cancelAndDiscard()
    replacement.attempt = null
    replacement.prepared.discard()
    finishLocalCancellation(replacement.token, notifyCancellation)
  }

  private fun finishLocalCancellation(token: LocalOpenToken, notifyCancellation: Boolean) {
    if (token.terminal.compareAndSet(false, true) && notifyCancellation) {
      postHostEventSafely(
          Controller.RomLoadingCancelledEvent(
              token.event.rom,
              token.event.openRequestId,
          ))
    }
  }

  private fun finishLocalLoadFailure(
      token: LocalOpenToken,
      failure: Throwable,
      expectedLoad: LocalLoadJob? = null,
  ) {
    synchronized(localLoadLock) {
      if (expectedLoad != null && localLoadJob !== expectedLoad) {
        return
      }
      if (localLoadJob?.token === token) {
        localLoadJob = null
      }
      if (localReplacementJob?.token === token) {
        localReplacementJob = null
      }
    }
    if (!token.terminal.compareAndSet(false, true) || token.cancelled.get()) {
      return
    }
    reportLocalLoadFailure(
        token.event.rom,
        failure,
        token.event.openRequestId,
    )
  }

  private fun finishLocalToken(
      token: LocalOpenToken,
      expectedLoad: LocalLoadJob? = null,
  ): Boolean {
    synchronized(localLoadLock) {
      if (expectedLoad != null && localLoadJob !== expectedLoad) {
        return false
      }
      if (localLoadJob?.token === token) {
        localLoadJob = null
      }
      if (localReplacementJob?.token === token) {
        localReplacementJob = null
      }
    }
    return !token.cancelled.get() && token.terminal.compareAndSet(false, true)
  }

  private fun isCurrentLocalLoad(job: LocalLoadJob): Boolean =
      synchronized(localLoadLock) {
        localLoadJob === job && !job.token.cancelled.get() && !job.token.terminal.get()
      }

  private fun isCurrentLocalReplacement(replacement: LocalReplacementJob): Boolean =
      synchronized(localLoadLock) {
        localReplacementJob === replacement &&
            !replacement.token.cancelled.get() &&
            !replacement.token.terminal.get()
      }

  private fun hasPendingLocalOpen(): Boolean =
      synchronized(localLoadLock) {
        localLoadJob != null ||
            localReplacementJob != null ||
            localWorkerTasks.get() != 0
      }

  private fun hasPendingLocalWork(): Boolean =
      hasPendingLocalOpen() ||
          pendingLocalSessionCommand != null ||
          localSessionCommandJob != null

  private fun dispatchOrderedIngress() {
    var remaining =
        StateLimits.NETPLAY_EVENT_DISPATCH_EVENTS + LOCAL_OPEN_QUEUE_EVENTS
    while (remaining-- > 0 && !hasPendingLocalWork()) {
      val localOrder = localOpenEventQueue.nextOrder()
      val peerOrder = eventQueue.nextOrder()
      when {
        localOrder == null && peerOrder == null -> return
        peerOrder == null || (localOrder != null && localOrder <= peerOrder) ->
          localOpenEventQueue.dispatchOne()
        else -> eventQueue.dispatchOne()
      }
    }
  }

  /**
   * Cancellation/retry may unblock an already-established persistence barrier. State-changing
   * Load/Stop/Reset commands remain behind every earlier peer event in the shared ingress order.
   */
  private fun dispatchUrgentLocalCommands() {
    while (
        localOpenEventQueue.dispatchFirstMatching {
          it is Controller.CancelRomOpenEvent ||
              it is Controller.RetryRomReplacementEvent ||
              it is Controller.CancelRomReplacementEvent
        }) {
      // A decision for an established worker barrier is deliberately not head-of-line blocked by
      // an ordinary state command. Removing it leaves every other event in its original order.
    }
  }

  /**
   * A later local state command is also an immediate supersession intent. Cancel the current
   * physical attempt now, but leave that command queued so its actual topology/session mutation
   * still observes the one ingress order shared with peer traffic.
   */
  private fun cancelPendingWorkSupersededByQueuedCommand() {
    val superseded =
        localOpenEventQueue.anyEvent {
          it is LoadRomEvent ||
              it is StopEmulationEvent ||
              it is ResetEmulationEvent ||
              it is LoadedLocalConfigEvent
        }
    if (!superseded) {
      return
    }
    pendingLocalSessionCommand = null
    cancelAllLocalOpens(notifyCancellation = true)
    cancelCurrentLocalSessionCommand()
  }

  private fun executeLocalTask(task: PhysicallyTrackedFutureTask<*>) {
    localWorkerTasks.incrementAndGet()
    try {
      localLoadExecutor.execute {
        try {
          task.run()
        } finally {
          try {
            localWorkerExitProbe?.invoke()
          } finally {
            task.finishPhysicalExecution()
          }
        }
      }
    } catch (failure: RuntimeException) {
      task.finishPhysicalExecution()
      throw failure
    }
  }

  private fun requestLocalSessionCommand(command: LocalSessionCommand) {
    cancelAllLocalOpens(notifyCancellation = true)
    cancelCurrentLocalSessionCommand()
    pendingLocalSessionCommand = command
  }

  private fun finishPendingLocalSessionCommand() {
    val command = pendingLocalSessionCommand ?: return
    if (hasPendingLocalOpen() || localSessionCommandJob != null) {
      return
    }
    pendingLocalSessionCommand = null
    startLocalSessionCommand(command)
  }

  private fun startLocalSessionCommand(command: LocalSessionCommand) {
    val previous = sessions[localPlayer]
    val config = configs[localPlayer]
    if (command == LocalSessionCommand.RESET && config == null) {
      return
    }
    val capture = previous?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    val prepared = config?.let { PreparedSession.Deferred(it) }
    val job =
        LocalSessionCommandJob(
            requestId = nextLocalPersistenceRequestId++,
            command = command,
            previousSession = previous,
            capture = capture,
            prepared = prepared,
            candidateLinks = links,
            sessionFrame = frame,
        )
    job.attempt = createLocalSessionCommandTask(job)
    localSessionCommandJob = job
    submitLocalSessionCommandAttempt(job)
  }

  private fun createLocalSessionCommandTask(
      job: LocalSessionCommandJob,
  ): LocalSessionCommandTask =
      LocalSessionCommandTask(
          Callable {
            val persistence = persistLocalBatteryCapture(job.capture)
            if (persistence is BatteryPersistenceResult.Failure) {
              LocalSessionCommandResult.Failure(persistence)
            } else if (job.command == LocalSessionCommand.STOP) {
              LocalSessionCommandResult.Ready(
                  persistence as BatteryPersistenceResult.Success,
                  candidate = null,
              )
            } else {
              var candidate: Session? = null
              try {
                val prepared = checkNotNull(job.prepared)
                validateLocalBatterySidecars(prepared.config, null)
                val stagedCandidate =
                    materializeLocalSession(
                        prepared,
                        job.sessionFrame,
                        job.candidateLinks,
                    )
                candidate = stagedCandidate
                val ready =
                    LocalSessionCommandResult.Ready(
                        persistence as BatteryPersistenceResult.Success,
                        stagedCandidate,
                    )
                candidate = null
                ready
              } finally {
                candidate?.let(::discardWorkerCandidate)
              }
            }
          })

  private fun submitLocalSessionCommandAttempt(job: LocalSessionCommandJob) {
    val attempt = job.attempt ?: return
    try {
      executeLocalTask(attempt)
    } catch (failure: RuntimeException) {
      if (localSessionCommandJob === job) {
        localSessionCommandJob = null
      }
      attempt.cancelAndDiscard()
      job.prepared?.discard()
      reportLocalSessionCommandFailure(job, unexpectedPersistenceFailure(failure))
    }
  }

  private fun finishLocalSessionCommand() {
    val job = localSessionCommandJob ?: return
    val attempt = job.attempt ?: return
    if (!attempt.isDone) {
      return
    }
    val result =
        try {
          attempt.take()
        } catch (_: CancellationException) {
          return
        } catch (failure: Exception) {
          LocalSessionCommandResult.Failure(unexpectedPersistenceFailure(failure))
        }
    if (localSessionCommandJob !== job) {
      if (result is LocalSessionCommandResult.Ready) {
        result.candidate?.let(::discardWorkerCandidate)
      }
      return
    }
    job.attempt = null
    if (result is LocalSessionCommandResult.Failure) {
      reportLocalSessionCommandFailure(job, result.failure)
      return
    }
    result as LocalSessionCommandResult.Ready
    if (sessions[localPlayer] !== job.previousSession) {
      localSessionCommandJob = null
      result.candidate?.let(::discardWorkerCandidate)
      job.prepared?.discard()
      return
    }

    job.capture.complete(result.persistence)
    when (job.command) {
      LocalSessionCommand.STOP -> commitLocalStop(job)
      LocalSessionCommand.RESET ->
          commitLocalReset(job, checkNotNull(result.candidate))
    }
  }

  private fun commitLocalStop(job: LocalSessionCommandJob) {
    val checkpoint = isFourPlayerHost()
    if (checkpoint) reconcileHistory()
    localSessionCommandJob = null
    job.previousSession?.let { localSession ->
      // Match BasicController's explicit stop boundary: publish while the old session bus is
      // still routable, then release the core and commit the empty local slot.
      postHostEventSafely(Controller.EmulationStoppedEvent())
      localSession.closeAfterCartridgeFlush()
    }
    sessions[localPlayer] = null
    if (checkpoint) {
      commitHostCheckpoint()
    } else {
      eventBus.postAsync(RequestStopEvent(frame, localPlayer))
    }
  }

  private fun commitLocalReset(job: LocalSessionCommandJob, candidate: Session) {
    reconcileHistory()
    localSessionCommandJob = null
    sessions[localPlayer] = candidate
    job.previousSession?.closeAfterCartridgeFlush()
    candidate.activate()
    if (isFourPlayerHost()) {
      commitHostCheckpoint()
    } else {
      commitStateBoundary(NetplayRollbackReason.RESET)
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) localCheckpointCreditPending = true
    }
    eventBus.postAsync(RequestResetEvent(frame, localPlayer))
  }

  private fun retryLocalSessionCommand(requestId: Long): Boolean {
    val job = localSessionCommandJob?.takeIf { it.requestId == requestId } ?: return false
    if (job.attempt == null) {
      job.attempt = createLocalSessionCommandTask(job)
      submitLocalSessionCommandAttempt(job)
    }
    return true
  }

  private fun cancelLocalSessionCommand(requestId: Long): Boolean {
    val job = localSessionCommandJob?.takeIf { it.requestId == requestId } ?: return false
    localSessionCommandJob = null
    job.attempt?.cancelAndDiscard()
    job.attempt = null
    job.prepared?.discard()
    return true
  }

  private fun cancelCurrentLocalSessionCommand() {
    val job = localSessionCommandJob ?: return
    localSessionCommandJob = null
    job.attempt?.cancelAndDiscard()
    job.attempt = null
    job.prepared?.discard()
  }

  private fun reportLocalSessionCommandFailure(
      job: LocalSessionCommandJob,
      failure: BatteryPersistenceResult.Failure,
  ) {
    postHostEventSafely(
        Controller.RomReplacementPersistenceFailedEvent(
            job.requestId,
            failure.fileName(),
            failure.message(),
            if (job.command == LocalSessionCommand.STOP) {
              Controller.PersistenceBarrierOperation.STOP
            } else {
              Controller.PersistenceBarrierOperation.RESET
            },
        ))
  }

  private fun unexpectedPersistenceFailure(
      failure: Exception,
  ): BatteryPersistenceResult.Failure {
    val cause = failure.cause ?: failure
    val ioFailure =
        if (cause is IOException) {
          cause
        } else {
          IOException("Unexpected linked persistence worker failure", cause)
        }
    return BatteryPersistenceResult.Failure(
        BatteryPersistenceResult.FailureKind.WRITE_FAILED,
        configs[localPlayer]?.rom?.origin?.displayName() ?: "battery save",
        "Unable to persist the current linked session. Changes remain pending and can be retried.",
        ioFailure,
    )
  }

  private fun isManuallyDriven(): Boolean =
      !thread.isAlive && Thread.currentThread() !== thread

  private fun awaitLocalLoadForManualDrive() {
    val task = synchronized(localLoadLock) { localLoadJob?.task } ?: return
    runCatching { task.get() }
  }

  private fun awaitLocalReplacementForManualDrive() {
    val task = synchronized(localLoadLock) { localReplacementJob?.attempt } ?: return
    runCatching { task.get() }
  }

  private fun awaitLocalSessionCommandForManualDrive() {
    val task = localSessionCommandJob?.attempt ?: return
    runCatching { task.get() }
  }

  private fun ensureLocalLoadActive(token: LocalOpenToken?) {
    if (Thread.currentThread().isInterrupted ||
        doStop ||
        token?.cancelled?.get() == true ||
        token?.terminal?.get() == true) {
      throw CancellationException("Linked ROM preparation cancelled")
    }
  }

  private fun initSession(
      player: Int,
      sessionFrame: Long,
      state: MachineState?,
  ) {
    val config = configs[player] ?: return
    sessions[player] = createInitializedSession(player, config, sessionFrame, state)
  }

  private fun createInitializedSession(
      player: Int,
      config: GameboyConfiguration,
      sessionFrame: Long,
      state: MachineState?,
      staged: Boolean = false,
      prebuiltGameboy: Gameboy? = null,
      sessionLinks: StateHistory.Links = links,
  ): Session {
    val sessionEventBusDelegate = createSessionEventBus(player)
    val sessionEventBus =
        if (staged) stagedEventBus(sessionEventBusDelegate) else sessionEventBusDelegate
    var session: Session? = null
    var ownedGameboy = prebuiltGameboy
    try {
      session =
          Session(
              if (state != null) config.forRestore() else config,
              sessionEventBus,
              if (player == localPlayer) console else null,
              sessionLinks.serial[player],
              sessionLinks.infrared[player],
              ownedGameboy,
          )
      ownedGameboy = null
      if (state != null) {
        DetachedStateAdapter.apply(session.gameboy, state)
      }

      var current = sessionFrame
      while (current < frame) {
        stateHistory.setPlayerState(
            player,
            current,
            session.captureDetachedState(),
            session.heldButtons,
        )
        repeat(session.gameboy.clockSpec.controllerTicksPerFrame()) { session.gameboy.tick() }
        current++
      }
      return session
    } catch (failure: Throwable) {
      try {
        // A candidate never owned the live cartridge generation. Discard it without flushing so
        // a failed state restore cannot rewrite the user's adjacent battery sidecar.
        if (session != null) {
          session.discardUnstarted()
        } else {
          ownedGameboy?.discardUnstarted()
          sessionEventBus.close()
        }
      } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
      }
      throw failure
    }
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
    rollbackMetrics.recordCheckpoint(NetplayRollbackReason.CHECKPOINT)
    rollbackMetrics.updateHistory(stateHistory.entryCount())
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
              event.portableState != null,
          )
          .setPlayerInputSource(PlayerInputSource.RELEASED)

  private fun createSessionEventBus(player: Int): EventBus =
      EventBusImpl(null, null, false).let { sessionEventBus ->
        if (player != localPlayer) {
          sessionEventBus
        } else {
          // Machine input stays isolated from the shared tree; otherwise frame-owned input posted
          // into the session feeds back into LinkedController's command queue. The returned owner
          // closes both this isolated bus and the exact shared output fork.
          owningFunnel(
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
    LOG.atDebug().log("Rejecting portable state for player {}", player + 1)
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
    val historyFloor = maxOf(runtimeFrameFloor, stateHistory.oldestFrame() ?: runtimeFrameFloor)
    if (peerFrame < historyFloor) {
      rollbackMetrics.recordHistoryExhausted()
      LOG.atDebug().log(
          "Discarding player {} frame from before retained history",
          source?.player?.plus(1),
      )
      return false
    }
    return validatePeer(source) { PeerFrameWindow.validateRuntimeFrame(peerFrame, frame) }
  }

  private fun validateV9Runtime(
      peerFrame: Long,
      player: Int,
      requireActiveSession: Boolean = true,
  ): V9ErrorCode? {
    val historyExhausted =
        peerFrame < runtimeFrameFloor || stateHistory.oldestFrame()?.let { peerFrame < it } == true
    if (player == localPlayer || player !in sessions.indices ||
        requireActiveSession && sessions[player] == null ||
        !requireActiveSession && configs[player] == null ||
        historyExhausted) {
      if (historyExhausted) rollbackMetrics.recordHistoryExhausted()
      return V9ErrorCode.SEQUENCE_ERROR
    }
    return try {
      PeerFrameWindow.validateRuntimeFrame(peerFrame, frame)
      null
    } catch (_: Exception) {
      V9ErrorCode.SEQUENCE_ERROR
    }
  }

  private fun applyV9InputEvent(event: V9RemoteInputEvent) {
    val value = event.value
    val failure = validateV9Runtime(value.frame, value.player)
    if (failure != null) {
      event.completion.complete(failure)
      return
    }
    try {
      val buttons = v9Buttons(value.buttonMask)
      val current = v9RemoteButtons[value.player]
          ?: sessions.getOrNull(value.player)?.heldButtons ?: emptySet()
      v9RemoteButtons[value.player] = buttons
      val translated =
          RemoteButtonStateEvent(
              value.frame,
              Input((buttons - current).sorted(), (current - buttons).sorted()),
              value.player,
          )
      stateHistory.addSecondaryInput(value.player, value.frame, translated.input)
      eventBus.postAsync(ValidatedPeerButtonStateEvent(translated))
      event.completion.complete(null)
    } catch (_: Exception) {
      event.completion.complete(V9ErrorCode.INTERNAL_ERROR)
    }
  }

  private fun applyV9ControlEvent(event: V9RemoteControlEvent) {
    val value = event.value
    val failure =
        validateV9Runtime(
            value.frame,
            value.player,
            requireActiveSession = value.kind != V9RuntimeMessageKind.RESET,
        )
    if (failure != null) {
      event.completion.complete(failure)
      return
    }
    try {
      when (value.kind) {
        V9RuntimeMessageKind.RESET -> applyV9Reset(value)
        V9RuntimeMessageKind.STOP -> applyV9Stop(value)
        V9RuntimeMessageKind.INPUT -> error("INPUT is not a control event")
      }
      event.completion.complete(null)
    } catch (_: Exception) {
      event.completion.complete(V9ErrorCode.INTERNAL_ERROR)
    }
  }

  private fun applyV9Reset(value: V9RuntimeControl) {
    reconcileHistory()
    sessions[value.player]?.close()
    sessions[value.player] = null
    initSession(value.player, value.frame, null)
    v9RemoteButtons[value.player] = null
    if (isFourPlayerHost()) commitHostCheckpoint()
    else {
      commitStateBoundary(NetplayRollbackReason.RESET)
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) localCheckpointCreditPending = true
    }
    eventBus.postAsync(ValidatedPeerResetEvent(ReceivedRemoteResetEvent(value.frame, value.player)))
  }

  private fun applyV9Stop(value: V9RuntimeControl) {
    if (sessions[value.player] == null) return
    reconcileHistory()
    sessions[value.player]?.close()
    sessions[value.player] = null
    v9RemoteButtons[value.player] = null
    if (isFourPlayerHost()) commitHostCheckpoint()
    else commitStateBoundary(NetplayRollbackReason.TOPOLOGY_CHANGE)
    eventBus.postAsync(ValidatedPeerStopEvent(ReceivedRemoteStopEvent(value.frame, value.player)))
  }

  private fun syncV9RemoteButtons() {
    v9RemoteButtons.indices.forEach { player ->
      v9RemoteButtons[player] = sessions.getOrNull(player)?.heldButtons?.toSet()
    }
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
        LOG.atDebug().log("Rejecting invalid peer frame for player {}",
            source?.player?.plus(1))
        source?.let(eventQueue::discardSource)
        source?.reject(ProtocolErrorReason.INVALID_FRAME, e)
        false
      }

  /**
   * Clones and encodes the complete immutable outbound generation on the loader worker. The frame
   * is frozen while this runs, so the candidate state and battery bytes describe one exact commit.
   */
  private fun prepareLocalRomPayload(
      session: Session,
      config: GameboyConfiguration,
      includeState: Boolean,
      batteryBuffer: ByteArray?,
      payloadFrame: Long,
  ): PreparedLocalRomPayload {
    localPayloadProbe?.invoke()
    val romBuffer = config.rom.image.bytes()
    val slotRomBuffer = config.slotRom?.image?.bytes()
    val portableState =
        if (includeState) {
          StateCodec.encode(
              StateCodec.capture(config, session.gameboy),
              StateCompression.DEFLATE,
          )
        } else {
          null
        }
    return PreparedLocalRomPayload(
        romBuffer,
        slotRomBuffer,
        batteryBuffer,
        LocalRomLoadedEvent(
            romFile = romBuffer,
            slotRomFile = slotRomBuffer,
            batteryFile = batteryBuffer,
            portableState = portableState,
            gameboyType = config.gameboyType,
            bootstrapMode = config.bootstrapMode,
            frame = payloadFrame,
            cgb0Revision = config.isCgb0Revision,
            hardwareProfileId = config.hardwareProfile.id(),
            mealybugDmgBlob = config.isMealybugDmgBlob,
            codeBreakerRumble = config.isCodeBreakerRumble,
            displaySgbBorder = config.isDisplaySgbBorder,
            player = localPlayer,
        ),
    )
  }

  /** Commits retained payload ownership, then leaves connection validation/compression to async. */
  private fun publishPreparedLocalRom(payload: PreparedLocalRomPayload) {
    romBuffers[localPlayer] = payload.rom
    slotRomBuffers[localPlayer] = payload.slotRom
    batteryBuffers[localPlayer] = payload.battery
    eventBus.postAsync(payload.event)
  }

  private fun readLocalBattery(
      config: GameboyConfiguration,
      token: LocalOpenToken,
  ): ByteArray? {
    if (!config.isSupportBatterySave) {
      return null
    }
    val saveFile =
        config.batteryStorage?.firstReadablePath()?.orElse(null)
            ?: config.rom.origin
                .persistencePath(".sav")
                .map { Cartridge.getSaveName(config.rom).toPath() }
                .orElse(null)
    return if (
        saveFile != null && Files.exists(saveFile, LinkOption.NOFOLLOW_LINKS)
    ) {
      readBoundedBattery(saveFile, token) {
        config.batteryStorage?.ensureReadablePath(saveFile)
      }
    } else {
      null
    }
  }

  private fun reportLocalBatteryFailure(
      config: GameboyConfiguration,
      romFile: java.io.File,
      failure: IOException,
      openRequestId: Long? = null,
  ) {
    val fileName =
        config.rom.origin
            .persistencePath(".sav")
            .map { path -> path.fileName.toString() }
            .orElse(config.rom.origin.displayName())
    val message = failure.message ?: "Unable to read battery save"
    LOG.warn("Unable to read bounded linked battery payload from {}", fileName, failure)
    postHostEventSafely(
        BatteryPersistenceFailedEvent(
            BatteryPersistenceFailedEvent.Operation.LOAD,
            fileName,
            message,
        ))
    postHostEventSafely(
        Controller.LoadRomFailedEvent(
            romFile,
            message,
            openRequestId,
            Controller.RomLoadFailureKind.CORE_STARTUP,
            sanitizedLocalLoadDetail(failure),
        ))
  }

  private fun reportLocalLoadFailure(
      romFile: java.io.File,
      failure: Throwable,
      openRequestId: Long? = null,
  ) {
    val message =
        failure.message?.takeIf { it.isNotBlank() }
            ?: "Unable to prepare ${romFile.name.ifBlank { "ROM" }}"
    LOG.warn("Unable to prepare linked ROM {}", romFile, failure)
    postHostEventSafely(
        Controller.LoadRomFailedEvent(
            romFile,
            message,
            openRequestId,
            Controller.RomLoadFailureKind.CORE_STARTUP,
            sanitizedLocalLoadDetail(failure),
        ))
  }

  private fun postHostEventSafely(event: Event) {
    try {
      eventBus.post(event)
    } catch (subscriberFailure: RuntimeException) {
      LOG.warn(
          "Linked controller host event subscriber failed for {}",
          event.javaClass.simpleName,
          subscriberFailure,
      )
    }
  }

  private fun reportLocalBatteryPersistenceFailure(
      replacement: LocalReplacementJob,
      failure: BatteryPersistenceResult.Failure,
  ) {
    LOG.warn(
        "Unable to publish linked battery generation to {}",
        failure.fileName(),
        failure.cause(),
    )
    postHostEventSafely(
        BatteryPersistenceFailedEvent(
            BatteryPersistenceFailedEvent.Operation.SAVE,
            failure.fileName(),
            failure.message(),
        ))
    postHostEventSafely(
        Controller.RomReplacementPersistenceFailedEvent(
            replacement.requestId,
            failure.fileName(),
            failure.message(),
            Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
            replacement.token.event.openRequestId,
        ))
  }

  private fun sanitizedLocalLoadDetail(failure: Throwable): String =
      failure.message
          ?.replace(Regex("[\\r\\n\\t]+"), " ")
          ?.trim()
          ?.take(320)
          ?.takeIf(String::isNotEmpty)
          ?: failure.javaClass.simpleName

  private fun readBoundedBattery(
      path: Path,
      token: LocalOpenToken,
      validatePath: () -> Unit = {},
  ): ByteArray {
    ensureLocalLoadActive(token)
    validatePath()
    val limit = StateLimits.BATTERY.decodedBytes
    requireRegularBatterySidecar(path)
    return FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
        .use { channel ->
          val declaredSize = channel.size()
          if (declaredSize > limit) {
            throw IOException("Battery file exceeds the $limit-byte safety limit")
          }
          val output = ByteArray(declaredSize.toInt())
          val buffer = ByteBuffer.wrap(output)
          var zeroReads = 0
          while (buffer.hasRemaining()) {
            ensureLocalLoadActive(token)
            val read = channel.read(buffer)
            if (read < 0) {
              return@use output.copyOf(buffer.position())
            }
            if (read == 0) {
              if (++zeroReads > 1_024) {
                throw IOException("Battery file made no read progress")
              }
            } else {
              zeroReads = 0
            }
          }
          // Detect growth through the same no-follow handle without allocating past the bound.
          val overflow = ByteBuffer.allocate(1)
          ensureLocalLoadActive(token)
          if (channel.read(overflow) >= 0) {
            throw IOException("Battery file exceeds the $limit-byte safety limit")
          }
          output
        }
  }

  private fun validateLocalBatterySidecars(
      config: GameboyConfiguration,
      token: LocalOpenToken?,
  ) {
    if (!config.isSupportBatterySave) {
      return
    }
    sequenceOf(config.rom, config.slotRom)
        .filterNotNull()
        .flatMap { rom ->
          sequenceOf(
              rom.origin.persistencePath(".sav").orElse(null),
              rom.origin.legacyArchivePersistencePath(".sav").orElse(null),
          )
        }
        .filterNotNull()
        .forEach { path ->
          ensureLocalLoadActive(token)
          if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularBatterySidecar(path)
          }
        }
  }

  private fun requireRegularBatterySidecar(path: Path) {
    if (Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException(
          "Battery sidecar ${path.fileName} must be a regular non-symbolic file")
    }
    try {
      FileChannel.open(
              path,
              StandardOpenOption.READ,
              LinkOption.NOFOLLOW_LINKS,
          )
          .use { channel ->
            if (channel.size() > StateLimits.BATTERY.decodedBytes) {
              throw IOException(
                  "Battery file exceeds the ${StateLimits.BATTERY.decodedBytes}-byte safety limit")
            }
          }
    } catch (failure: UnsupportedOperationException) {
      throw IOException("Battery sidecar cannot be opened without following links", failure)
    }
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
              portableState = null,
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
              portableStateFile = StateCodec.capture(session),
          )
        }
    // Encoding and per-connection validation/compression are package work, not frame work.
    eventBus.postAsync(SessionStateReadyEvent(frame, states))
  }

  private fun isFourPlayerHost(): Boolean =
      mode == LinkMode.FOUR_PLAYER_ADAPTER && localPlayer == 0

  /** Applies every accepted old-generation patch before a topology snapshot is captured. */
  private fun reconcileHistory() {
    val merge = stateHistory.mergeDetailed(configs) ?: return
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
    rollbackMetrics.recordRollback(merge.framesRewound, merge.framesResimulated)
    rollbackMetrics.updateHistory(stateHistory.entryCount())
    LOG.atDebug().log("State merged to {}", frame)
  }

  /** Publishes and installs the same exact generation boundary used by remote clients. */
  private fun commitHostCheckpoint() {
    commitStateBoundary(NetplayRollbackReason.TOPOLOGY_CHANGE)
    hostCheckpointPending = true
  }

  /** Coalesces mutations in one dispatch and captures any same-frame patches after the boundary. */
  private fun flushHostCheckpoint() {
    if (!hostCheckpointPending) return
    commitStateBoundary(NetplayRollbackReason.TOPOLOGY_CHANGE, record = false)
    broadcastCurrentState()
    hostCheckpointPending = false
  }

  /** Ends the old input/history generation after a reset or topology mutation. */
  private fun commitStateBoundary(
      reason: NetplayRollbackReason = NetplayRollbackReason.CHECKPOINT,
      record: Boolean = true,
  ) {
    runtimeFrameFloor = frame
    rebaseHistoryToLiveState()
    if (record) rollbackMetrics.recordCheckpoint(reason)
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
    rollbackMetrics.updateHistory(stateHistory.entryCount())
  }

  override fun close() {
    closeWithState()
  }

  private fun eventWeight(event: Event): Long =
      when (event) {
        is PeerLoadedGameEvent -> peerStateWeight(event)
        is SessionCheckpointEvent ->
            event.states.fold(0L) { total, state ->
              Math.addExact(total, peerStateWeight(state))
            }
        is V9CheckpointPrepareEvent ->
          // The decoder already enforced the 32 MiB direct-file and 128 MiB aggregate bounds.
          StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES.toLong()
        is V9CheckpointCommitEvent -> 64L
        is V9CheckpointCaptureEvent -> 64L
        is V9GenerationCaptureEvent -> 64L
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

  @Synchronized
  override fun closeWithState(): Controller.ControllerState? {
    if (closed) {
      return null
    }
    val closeDeadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
    doStop = true
    awaitTimingThread(closeDeadlineNanos)

    // The close caller owns retry presentation. Cancel without lifecycle callbacks, retain the
    // exact current machine generation, and queue its writer behind any cancelled worker body.
    cancelAllLocalOpens(notifyCancellation = false)
    pendingLocalSessionCommand = null
    cancelCurrentLocalSessionCommand()
    if (closeState == null) {
      closeState =
          sessions[localPlayer]?.let {
            Controller.ControllerState(DetachedStateAdapter.capture(it.gameboy), it.config.rom)
          } ?: rejectedLocalState
    }
    if (closeCapture == null) {
      closeCapture =
          sessions[localPlayer]?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    }
    val capture = checkNotNull(closeCapture)
    val persistence = persistCloseCapture(capture, closeDeadlineNanos)
    if (persistence is BatteryPersistenceResult.Failure) {
      val requestId = closeRequestId ?: nextLocalPersistenceRequestId++
      closeRequestId = requestId
      throw Controller.PersistenceBarrierException(
          requestId,
          Controller.PersistenceBarrierOperation.CLOSE,
          persistence.fileName(),
          persistence.message(),
          persistence.cause(),
      )
    }
    capture.complete(persistence)
    val state = closeState

    try {
      shutdownLocalLoadExecutor(closeDeadlineNanos)
      eventBus.close(
          remainingCloseNanos(closeDeadlineNanos, "controller event-bus teardown"),
          TimeUnit.NANOSECONDS,
      )
      // Only a persisted generation with no live loader/subscriber may release machines.
      sessions.forEach { session ->
        session?.closeAfterCartridgeFlush(
            remainingCloseNanos(closeDeadlineNanos, "linked session teardown"),
            TimeUnit.NANOSECONDS,
        )
      }
    } catch (failure: EventBusTeardownTimeoutException) {
      throw closeBarrierFailure(
          "Linked session subscribers did not stop before the close deadline. " +
              "The persisted machines remain retained and close can be retried.",
          failure,
      )
    }

    rollbackMetrics.close()
    closed = true
    closeCapture = null
    closeState = null
    closeRequestId = null
    closePersistenceAttempt = null
    return state
  }

  private fun awaitTimingThread(closeDeadlineNanos: Long) {
    if (Thread.currentThread() === thread || !thread.isAlive) {
      return
    }
    val remainingNanos =
        remainingCloseNanos(closeDeadlineNanos, "linked controller timing-thread stop")
    try {
      thread.join(
          TimeUnit.NANOSECONDS.toMillis(remainingNanos),
          (remainingNanos % 1_000_000).toInt(),
      )
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw closeBarrierFailure(
          "Interrupted while waiting for the linked timing thread to stop.",
          failure,
      )
    }
    if (thread.isAlive) {
      throw closeBarrierFailure(
          "Linked timing thread did not stop before the close deadline. " +
              "The running session remains retained and close can be retried.",
          IOException("Linked timing-thread stop timed out"),
      )
    }
  }

  private fun persistCloseCapture(
      capture: BatteryFlush,
      closeDeadlineNanos: Long,
  ): BatteryPersistenceResult {
    val fileName = closeFileName()
    val attempt =
        closePersistenceAttempt
            ?: RetainedClosePersistence(
                    capture,
                    localLoadExecutor,
                    persistLocalCloseCapture,
                )
                .also { closePersistenceAttempt = it }
    val result =
        attempt.await(
            fileName,
            remainingCloseNanos(closeDeadlineNanos, "linked battery persistence"),
            TimeUnit.NANOSECONDS,
            ::unexpectedClosePersistenceFailure,
        )
    // Completed failures may safely create a fresh retry. Timeouts retain the exact queued/running
    // task so an interrupt-ignoring filesystem writer can never overlap a newer generation.
    if (result is BatteryPersistenceResult.Failure && attempt.isDone) {
      closePersistenceAttempt = null
    }
    return result
  }

  private fun shutdownLocalLoadExecutor(closeDeadlineNanos: Long) {
    if (localLoadExecutorClosed.compareAndSet(false, true)) {
      localLoadExecutor.shutdownNow()
    }
    if (Thread.currentThread().name == "coffee-gb-linked-rom-loader") {
      throw closeBarrierFailure(
          "The linked ROM loader cannot synchronously close itself.",
          IOException("Linked ROM loader attempted reentrant close"),
      )
    }
    try {
      if (!localLoadExecutor.awaitTermination(
          remainingCloseNanos(closeDeadlineNanos, "linked ROM-loader stop"),
          TimeUnit.NANOSECONDS,
      )) {
        throw closeBarrierFailure(
            "Linked ROM loader did not stop before the close deadline. Close can be retried.",
            IOException("Linked ROM-loader stop timed out"),
        )
      }
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw closeBarrierFailure(
          "Interrupted while waiting for the linked ROM loader to stop.",
          failure,
      )
    }
  }

  private fun remainingCloseNanos(closeDeadlineNanos: Long, stage: String): Long {
    val remainingNanos = closeDeadlineNanos - System.nanoTime()
    if (remainingNanos <= 0) {
      throw closeBarrierFailure(
          "Linked controller close timed out during $stage. " +
              "The session remains retained and close can be retried.",
          IOException("Linked controller close deadline expired during $stage"),
      )
    }
    return remainingNanos
  }

  private fun unexpectedClosePersistenceFailure(
      failure: Exception,
  ): BatteryPersistenceResult.Failure {
    val cause = failure.cause ?: failure
    val ioFailure =
        if (cause is IOException) cause
        else IOException("Unexpected linked persistence worker failure", cause)
    return BatteryPersistenceResult.Failure(
        BatteryPersistenceResult.FailureKind.WRITE_FAILED,
        closeFileName(),
        "Unable to persist the linked session. Changes remain pending and can be retried.",
        ioFailure,
    )
  }

  private fun closeBarrierFailure(
      message: String,
      cause: Throwable,
  ): Controller.PersistenceBarrierException {
    val requestId = closeRequestId ?: nextLocalPersistenceRequestId++
    closeRequestId = requestId
    return Controller.PersistenceBarrierException(
        requestId,
        Controller.PersistenceBarrierOperation.CLOSE,
        closeFileName(),
        message,
        cause,
    )
  }

  private fun closeFileName(): String =
      sessions[localPlayer]?.config?.rom?.origin?.displayName()
          ?: closeState?.rom?.origin?.displayName()
          ?: "battery save"

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
      internal val portableStateFile: StateFile? = null,
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
      val battery: ByteArray?,
      val romFile: java.io.File =
          config.rom.origin
              .containerPath()
              .map { it.toFile() }
              .orElse(java.io.File(config.rom.origin.displayName())),
      val openRequestId: Long? = null,
  ) : Event

  private class LocalOpenToken(val event: LoadRomEvent) {
    val cancelled = AtomicBoolean()
    val terminal = AtomicBoolean()
  }

  private enum class LocalSessionCommand {
    STOP,
    RESET,
  }

  private class LocalSessionCommandJob(
      val requestId: Long,
      val command: LocalSessionCommand,
      val previousSession: Session?,
      val capture: BatteryFlush,
      val prepared: PreparedSession?,
      val candidateLinks: StateHistory.Links,
      val sessionFrame: Long,
  ) {
    @Volatile var attempt: LocalSessionCommandTask? = null
  }

  private sealed interface LocalSessionCommandResult {

    data class Failure(
        val failure: BatteryPersistenceResult.Failure,
    ) : LocalSessionCommandResult

    data class Ready(
        val persistence: BatteryPersistenceResult.Success,
        val candidate: Session?,
    ) : LocalSessionCommandResult
  }

  private data class LocalLoadJob(
      val token: LocalOpenToken,
      val task: LocalPreparationTask,
  )

  private sealed interface LocalLoadPreparation {

    data class Accepted(val prepared: PreparedSession) : LocalLoadPreparation

    data class Rejected(
        val config: GameboyConfiguration,
        val message: String,
    ) : LocalLoadPreparation
  }

  private class LocalReplacementJob(
      val requestId: Long,
      val token: LocalOpenToken,
      val event: LoadedLocalConfigEvent,
      val previousSession: Session?,
      val capture: BatteryFlush?,
      val prepared: PreparedSession,
      val candidateLinks: StateHistory.Links,
  ) {
    @Volatile var attempt: LocalReplacementTask? = null
  }

  private sealed interface LocalReplacementResult {

    data class PersistenceFailure(
        val failure: BatteryPersistenceResult.Failure,
    ) : LocalReplacementResult

    data class Ready(
        val persistence: BatteryPersistenceResult.Success,
        val battery: ByteArray?,
        val candidate: Session,
        val outbound: PreparedLocalRomPayload,
    ) : LocalReplacementResult
  }

  private data class PreparedLocalRomPayload(
      val rom: ByteArray,
      val slotRom: ByteArray?,
      val battery: ByteArray?,
      val event: LocalRomLoadedEvent,
  )

  /** Retains/discards prepared machines correctly across FutureTask cancellation races. */
  private inner class LocalPreparationTask(callable: Callable<LocalLoadPreparation>) :
      PhysicallyTrackedFutureTask<LocalLoadPreparation>(callable) {

    private val prepared = AtomicReference<PreparedSession>()

    override fun set(value: LocalLoadPreparation) {
      if (value is LocalLoadPreparation.Accepted) {
        prepared.set(value.prepared)
      }
      super.set(value)
      if (isCancelled) {
        prepared.getAndSet(null)?.discard()
      }
    }

    override fun done() {
      if (isCancelled) {
        prepared.getAndSet(null)?.discard()
      }
    }

    fun take(): LocalLoadPreparation {
      val value = get()
      if (value is LocalLoadPreparation.Accepted) {
        prepared.compareAndSet(value.prepared, null)
      }
      return value
    }
  }

  /** Owns a worker-materialized candidate until the timing thread commits or discards it. */
  private inner class LocalReplacementTask(callable: Callable<LocalReplacementResult>) :
      PhysicallyTrackedFutureTask<LocalReplacementResult>(callable) {

    private val candidate = AtomicReference<Session>()

    override fun set(value: LocalReplacementResult) {
      if (value is LocalReplacementResult.Ready) {
        candidate.set(value.candidate)
      }
      super.set(value)
      if (isCancelled) {
        candidate.getAndSet(null)?.discardUnstarted()
      }
    }

    override fun done() {
      if (isCancelled) {
        candidate.getAndSet(null)?.discardUnstarted()
      }
    }

    fun take(): LocalReplacementResult {
      val value = get()
      if (value is LocalReplacementResult.Ready) {
        candidate.compareAndSet(value.candidate, null)
      }
      return value
    }

    fun cancelAndDiscard() {
      cancel(true)
      candidate.getAndSet(null)?.discardUnstarted()
    }
  }

  /** Retains a reset candidate across the worker/timing ownership handoff. */
  private inner class LocalSessionCommandTask(callable: Callable<LocalSessionCommandResult>) :
      PhysicallyTrackedFutureTask<LocalSessionCommandResult>(callable) {

    private val candidate = AtomicReference<Session>()

    override fun set(value: LocalSessionCommandResult) {
      if (value is LocalSessionCommandResult.Ready) {
        value.candidate?.let(candidate::set)
      }
      super.set(value)
      if (isCancelled) {
        candidate.getAndSet(null)?.discardUnstarted()
      }
    }

    override fun done() {
      if (isCancelled) {
        candidate.getAndSet(null)?.discardUnstarted()
      }
    }

    fun take(): LocalSessionCommandResult {
      val value = get()
      if (value is LocalSessionCommandResult.Ready) {
        value.candidate?.let { candidate.compareAndSet(it, null) }
      }
      return value
    }

    fun cancelAndDiscard() {
      cancel(true)
      candidate.getAndSet(null)?.discardUnstarted()
    }
  }

  /**
   * Publishes normal completion only after the physical worker count has been released.
   *
   * [FutureTask.get] may otherwise return before an executor wrapper reaches its `finally` block.
   * A manually driven safe point can then mistake that completed wrapper for live cancelled work
   * and leave an already-admitted peer event queued for an extra frame. Cancellation deliberately
   * retains the count until [finishPhysicalExecution] runs from the executor wrapper, because the
   * callable may still be unwinding after `get()` observes the cancelled state.
   */
  private abstract inner class PhysicallyTrackedFutureTask<T>(callable: Callable<T>) :
      FutureTask<T>(callable) {

    private val physicalExecutionFinished = AtomicBoolean()

    override fun set(value: T) {
      finishPhysicalExecution()
      super.set(value)
    }

    override fun setException(failure: Throwable) {
      finishPhysicalExecution()
      super.setException(failure)
    }

    fun finishPhysicalExecution() {
      if (physicalExecutionFinished.compareAndSet(false, true)) {
        localWorkerTasks.decrementAndGet()
      }
    }
  }

  private data class V9CheckpointPrepareEvent(
      val preparation: V9PendingCheckpointPreparation,
      val lease: V9TargetLease,
  ) : Event

  private data class V9CheckpointCommitEvent(
      val transaction: V9LinkedPreparedCheckpoint,
  ) : Event

  private data class V9CheckpointCaptureEvent(
      val request: V9CheckpointRequest,
      val capture: V9PendingCheckpointCapture,
      val lease: V9TargetLease,
  ) : Event

  private data class V9GenerationCaptureEvent(
      val capture: V9PendingGenerationCapture,
      val lease: V9TargetLease,
  ) : Event

  private data class V9TargetDisconnectedEvent(val player: Int) : Event

  private data class V9RemoteInputEvent(
      val value: V9InputState,
      val completion: V9GameplayCompletion,
      val lease: V9TargetLease?,
  ) : Event

  private data class V9RemoteControlEvent(
      val value: V9RuntimeControl,
      val completion: V9GameplayCompletion,
      val lease: V9TargetLease,
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

  private class V9GenerationKey(
      private val sessions: List<Session?>,
      private val links: StateHistory.Links,
      private val identities: List<StateIdentityEntry>,
  ) {
    fun matches(
        currentSessions: List<Session?>,
        currentLinks: StateHistory.Links,
        currentIdentities: List<StateIdentityEntry>,
    ): Boolean =
        links === currentLinks &&
            sessions.size == currentSessions.size &&
            sessions.indices.all { sessions[it] === currentSessions[it] } &&
            identities == currentIdentities
  }

  private companion object {
    const val CANONICAL_PLAYER_SLOTS = 4
    const val LOCAL_OPEN_QUEUE_EVENTS = 64
    const val LOCAL_OPEN_QUEUE_BYTES = 4_096L
    const val LOCAL_OPEN_EVENT_WEIGHT = 64L
    const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 8_000L
    val LOG: Logger = LoggerFactory.getLogger(LinkedController::class.java)

    fun v9Buttons(mask: Int): Set<Button> = buildSet {
      if (mask and 0x01 != 0) add(Button.RIGHT)
      if (mask and 0x02 != 0) add(Button.LEFT)
      if (mask and 0x04 != 0) add(Button.UP)
      if (mask and 0x08 != 0) add(Button.DOWN)
      if (mask and 0x10 != 0) add(Button.A)
      if (mask and 0x20 != 0) add(Button.B)
      if (mask and 0x40 != 0) add(Button.SELECT)
      if (mask and 0x80 != 0) add(Button.START)
    }
  }
}

/** Caller-owned adapter; it only posts bounded values to the controller's existing safe point. */
internal class V9LinkedControllerTarget(
    private val controller: LinkedController,
) : V9CheckpointTarget, V9CheckpointProvider, V9GameplayTarget, Closeable {
  private val lease = V9TargetLease()
  private val captureLock = Any()
  private val pendingCaptures = mutableSetOf<V9PendingCheckpointCapture>()
  private val pendingGenerations = mutableSetOf<V9PendingGenerationCapture>()
  private val pendingPreparations = mutableSetOf<V9PendingCheckpointPreparation>()
  private val preparedTransactions = mutableSetOf<V9LinkedPreparedCheckpoint>()

  override fun captureGeneration(): V9TargetGeneration {
    val pending = V9PendingGenerationCapture()
    synchronized(captureLock) {
      if (!lease.runIfActive {
            pendingGenerations.add(pending)
            controller.enqueueV9GenerationCapture(pending, lease)
          }) {
        throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(
            V9ErrorCode.CANCELLED,
            0,
        )
      }
    }
    return try {
      pending.await()
    } finally {
      synchronized(captureLock) { pendingGenerations.remove(pending) }
    }
  }

  override fun capture(request: V9CheckpointRequest): V9CapturedCheckpoint {
    val pending = V9PendingCheckpointCapture()
    synchronized(captureLock) {
      if (!lease.runIfActive {
            pendingCaptures.add(pending)
            controller.enqueueV9CheckpointCapture(request, pending, lease)
          }) {
        throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(
            V9ErrorCode.CANCELLED,
            0,
        )
      }
    }
    return try {
      pending.awaitEncoded()
    } finally {
      synchronized(captureLock) { pendingCaptures.remove(pending) }
    }
  }

  override fun prepare(
      checkpoint: V9ValidatedCheckpoint,
      generation: V9TargetGeneration,
      completion: V9CheckpointPrepareCompletion,
  ): Closeable {
    lateinit var pending: V9PendingCheckpointPreparation
    pending =
        V9PendingCheckpointPreparation(
            checkpoint,
            generation,
            completion,
            onPrepared = { transaction ->
              synchronized(captureLock) { preparedTransactions.add(transaction) }
            },
            onReleased = { transaction ->
              synchronized(captureLock) { preparedTransactions.remove(transaction) }
            },
            onFinished = {
              synchronized(captureLock) { pendingPreparations.remove(pending) }
            },
        )
    synchronized(captureLock) {
      if (!lease.runIfActive {
            pendingPreparations.add(pending)
            controller.enqueueV9CheckpointPreparation(pending, lease)
          }) {
        pending.cancel()
      }
    }
    return pending
  }

  override fun input(value: V9InputState, completion: V9GameplayCompletion) {
    if (!lease.runIfActive { controller.enqueueV9Input(value, completion, lease) }) {
      completion.complete(V9ErrorCode.CANCELLED)
    }
  }

  override fun control(value: V9RuntimeControl, completion: V9GameplayCompletion) {
    if (!lease.runIfActive { controller.enqueueV9Control(value, completion, lease) }) {
      completion.complete(V9ErrorCode.CANCELLED)
    }
  }

  override fun rollbackMetricsSource(): NetplaySnapshotSource<NetplayRollbackMetricsSnapshot> =
      controller.rollbackMetricsSource()

  override fun disconnected(player: Int) {
    close()
    // Release only this peer's held state; session/topology ownership remains with the controller.
    controller.enqueueV9Disconnect(player)
  }

  override fun close() {
    lease.close()
    val (preparations, transactions) = synchronized(captureLock) {
      pendingCaptures.forEach(V9PendingCheckpointCapture::cancel)
      pendingCaptures.clear()
      pendingGenerations.forEach(V9PendingGenerationCapture::cancel)
      pendingGenerations.clear()
      val pending = pendingPreparations.toList().also { pendingPreparations.clear() }
      val prepared = preparedTransactions.toList().also { preparedTransactions.clear() }
      pending to prepared
    }
    preparations.forEach(V9PendingCheckpointPreparation::cancel)
    transactions.forEach(V9LinkedPreparedCheckpoint::cancelBeforeCommit)
  }

  internal fun pendingCaptureCount(): Int =
      synchronized(captureLock) {
        pendingCaptures.size + pendingGenerations.size + pendingPreparations.size
      }

  internal fun preparedTransactionCount(): Int =
      synchronized(captureLock) { preparedTransactions.size }

}

internal class V9TargetLease {
  private val lock = Any()
  private var active = true

  fun runIfActive(block: () -> Unit): Boolean = synchronized(lock) {
    if (!active) false else {
      block()
      true
    }
  }

  fun close() = synchronized(lock) { active = false }
}

/** Owns decoded checkpoint state only until prepare wins or connection cancellation discards it. */
internal class V9PendingCheckpointPreparation(
    checkpoint: V9ValidatedCheckpoint,
    generation: V9TargetGeneration,
    completion: V9CheckpointPrepareCompletion,
    private val onPrepared: (V9LinkedPreparedCheckpoint) -> Unit,
    private val onReleased: (V9LinkedPreparedCheckpoint) -> Unit,
    private val onFinished: () -> Unit,
) : Closeable {
  private val lock = Any()
  private var checkpoint: V9ValidatedCheckpoint? = checkpoint
  private var generation: V9TargetGeneration? = generation
  private var completion: V9CheckpointPrepareCompletion? = completion

  fun execute(
      prepare: (V9ValidatedCheckpoint, V9TargetGeneration) -> V9PreparedCheckpoint,
  ) {
    val values = synchronized(lock) {
      val value = checkpoint ?: return
      val expected = generation ?: return
      value to expected
    }
    var prepared: V9PreparedCheckpoint?
    var failure: V9ErrorCode?
    try {
      prepared = prepare(values.first, values.second)
      if (prepared is V9LinkedPreparedCheckpoint) onPrepared(prepared)
      failure = null
    } catch (_: Throwable) {
      prepared = null
      failure = V9ErrorCode.TOPOLOGY_MISMATCH
    }
    complete(prepared, failure)
  }

  fun release(transaction: V9LinkedPreparedCheckpoint) = onReleased(transaction)

  private fun complete(prepared: V9PreparedCheckpoint?, failure: V9ErrorCode?) {
    val callback = synchronized(lock) {
      val value = completion
      if (value == null) null else {
        completion = null
        checkpoint = null
        generation = null
        value
      }
    }
    if (callback == null) prepared?.close()
    else {
      try {
        callback.complete(prepared, failure)
      } finally {
        onFinished()
      }
    }
  }

  fun cancel() {
    val callback = synchronized(lock) {
      val value = completion
      completion = null
      checkpoint = null
      generation = null
      value
    }
    if (callback != null) {
      try {
        callback.complete(null, V9ErrorCode.CANCELLED)
      } finally {
        onFinished()
      }
    }
  }

  override fun close() = cancel()
}

internal class V9PendingCheckpointCapture {
  private val lock = Any()
  private val latch = CountDownLatch(1)
  private var completed = false
  @Volatile private var cancelled = false
  private var captured: V9CapturedStateFile? = null
  private var failure: V9ErrorCode? = null

  fun complete(
      value: V9CapturedStateFile?,
      problem: V9ErrorCode?,
  ) {
    synchronized(lock) {
      if (completed || cancelled) return
      completed = true
      captured = value
      failure = problem
      latch.countDown()
    }
  }

  fun cancel() {
    synchronized(lock) {
      if (cancelled) return
      cancelled = true
      captured = null
      failure = V9ErrorCode.CANCELLED
      latch.countDown()
    }
  }

  fun awaitEncoded(): V9CapturedCheckpoint {
    try {
      latch.await()
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      cancel()
    }
    val value = synchronized(lock) {
      failure?.let {
        throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(it, 0)
      }
      captured.also { captured = null }
          ?: throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(
              V9ErrorCode.CANCELLED,
              0,
          )
    }
    val encoded = StateCodec.encode(value.stateFile, StateCompression.DEFLATE)
    if (cancelled) {
      encoded.fill(0)
      throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(
          V9ErrorCode.CANCELLED,
          0,
      )
    }
    return V9CapturedCheckpoint(value.generation, value.generation.observedFrame, encoded)
  }
}

internal data class V9CapturedStateFile(
    val stateFile: eu.rekawek.coffeegb.controller.state.StateFile,
    val generation: V9TargetGeneration,
)

internal class V9PendingGenerationCapture {
  private val lock = Any()
  private val latch = CountDownLatch(1)
  private var generation: V9TargetGeneration? = null
  private var failure: V9ErrorCode? = null
  private var completed = false

  fun complete(value: V9TargetGeneration?, problem: V9ErrorCode?) = synchronized(lock) {
    if (completed) return@synchronized
    completed = true
    generation = value
    failure = problem
    latch.countDown()
  }

  fun cancel() = complete(null, V9ErrorCode.CANCELLED)

  fun await(): V9TargetGeneration {
    try {
      latch.await()
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      cancel()
    }
    return synchronized(lock) {
      failure?.let {
        throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(it, 0)
      }
      generation ?: throw eu.rekawek.coffeegb.controller.network.v9.V9ProtocolException(
          V9ErrorCode.CANCELLED,
          0,
      )
    }
  }
}

internal data class V9PreparedPlayerState(
    val player: Int,
    val session: Session,
    val machine: PreparedMachineState? = null,
    val sessionState: PreparedSessionState? = null,
)

internal data class V9PreparedControllerState(
    val frame: Long,
    val targetSessions: List<Session?>,
    val targetIdentities: List<StateIdentityEntry>,
    val targetLinks: StateHistory.Links,
    val players: List<V9PreparedPlayerState>,
    val generation: V9TargetGeneration,
)

internal class V9LinkedPreparedCheckpoint(
    private val controller: LinkedController,
    internal val checkpoint: V9ValidatedCheckpoint,
    internal val prepared: V9PreparedControllerState,
    private val lease: V9TargetLease,
    private val onFinished: (V9LinkedPreparedCheckpoint) -> Unit,
) : V9PreparedCheckpoint {
  private val lock = Any()
  private var state = CommitState.PREPARED
  private var completion: V9CheckpointCommitCompletion? = null
  private var released = false

  override fun commit(completion: V9CheckpointCommitCompletion) {
    val enqueue = synchronized(lock) {
      if (state != CommitState.PREPARED) false else {
        state = CommitState.QUEUED
        this.completion = completion
        true
      }
    }
    if (!enqueue) {
      completion.complete(V9ErrorCode.CANCELLED)
    } else {
      try {
        controller.enqueueV9CheckpointCommit(this)
      } catch (_: RuntimeException) {
        finishBeforeSafePoint(V9ErrorCode.CANCELLED)
      }
    }
  }

  internal fun applyAtSafePoint(apply: () -> Unit) {
    var started = false
    var failure: V9ErrorCode? = null
    val active = lease.runIfActive {
      started = synchronized(lock) {
        if (state != CommitState.QUEUED) false else {
          state = CommitState.APPLYING
          true
        }
      }
      if (started) {
        try {
          apply()
        } catch (_: Throwable) {
          failure = V9ErrorCode.TOPOLOGY_MISMATCH
        }
      }
    }
    if (!active || !started) {
      finishBeforeSafePoint(V9ErrorCode.CANCELLED)
    } else {
      finishAfterSafePoint(failure)
    }
  }

  private fun finishBeforeSafePoint(failure: V9ErrorCode) {
    val callback = synchronized(lock) {
      if (state == CommitState.APPLYING || state == CommitState.COMPLETED ||
          state == CommitState.CANCELLED) {
        null
      } else {
        state = CommitState.CANCELLED
        completion.also { completion = null }
      }
    }
    try {
      callback?.complete(failure)
    } finally {
      releaseIfTerminal()
    }
  }

  private fun finishAfterSafePoint(failure: V9ErrorCode?) {
    val callback = synchronized(lock) {
      if (state != CommitState.APPLYING) null else {
        state = CommitState.COMPLETED
        completion.also { completion = null }
      }
    }
    try {
      callback?.complete(failure)
    } finally {
      releaseIfTerminal()
    }
  }

  override fun cancelBeforeCommit(): Boolean {
    val (cancelled, callback) = synchronized(lock) {
      when (state) {
        CommitState.PREPARED -> {
          state = CommitState.CANCELLED
          true to null
        }
        CommitState.QUEUED -> {
          state = CommitState.CANCELLED
          true to completion.also { completion = null }
        }
        CommitState.CANCELLED -> true to null
        CommitState.APPLYING,
        CommitState.COMPLETED -> false to null
      }
    }
    if (cancelled) {
      try {
        callback?.complete(V9ErrorCode.CANCELLED)
      } finally {
        releaseIfTerminal()
      }
    }
    return cancelled
  }

  override fun close() {
    cancelBeforeCommit()
  }

  private fun releaseIfTerminal() {
    val notify = synchronized(lock) {
      if (released || state !in setOf(CommitState.COMPLETED, CommitState.CANCELLED)) false
      else {
        released = true
        true
      }
    }
    if (notify) onFinished(this)
  }

  private enum class CommitState { PREPARED, QUEUED, APPLYING, COMPLETED, CANCELLED }
}
