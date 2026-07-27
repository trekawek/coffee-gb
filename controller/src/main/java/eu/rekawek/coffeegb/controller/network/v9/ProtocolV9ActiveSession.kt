package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface V9PlaySend {
  /** Returns the allocated sequence, or null when admission failed and the connection is closing. */
  fun send(
      type: V9MessageType,
      flags: Int,
      correlation: Long,
      channel: Long,
      payload: ByteArray,
      onAdmitted: (Long) -> Closeable,
      onWritten: () -> Unit,
  ): Long?
}

/** Production #349 session logic. All emulator mutations remain behind [V9CheckpointTarget]. */
internal class V9PlaySession(
    private val role: V9Role,
    private val mode: V9LinkMode,
    private val authenticatedGuest: Int,
    authorization: V9CheckpointAuthorization,
    private val plan: V9GuestPlayPlan,
    private val targetGeneration: V9TargetGeneration,
    private val startTask: (String, () -> Unit) -> Thread,
    private val send: V9PlaySend,
    private val lifecycle: V9Lifecycle,
    private val fail: (V9ErrorCode, V9Diagnostic) -> Unit,
    private val onActive: (V9ActiveBoundary) -> Unit,
) : Closeable {
  private val lock = Any()
  private val grant = V9CheckpointGrant(authorization)
  private val authorization = authorization
  private var closed = false
  private var initialCheckpointReady = false
  private var checkpointFrame: Long? = null
  private var checkpointDigest: ByteArray? = null
  private var checkpointInFlight = false
  private var preparingIncoming: PendingPrepareRegistration? = null
  private var preparedIncoming: V9PreparedCheckpoint? = null
  private var preparedTicket: V9CheckpointGrant.Ticket? = null
  private var committingIncoming: CommittingCheckpoint? = null
  private var pendingStart: PendingStart? = null
  private var startSessionId: Long? = null
  private var startSequence: Long? = null
  private var coordinatorRegistration: Closeable? = null
  private var runtimeRegistration: Closeable? = null
  private val runtimeRelayLock = Any()
  private val runtimeRelayQueue =
      ArrayBlockingQueue<RuntimeRelay>(V9Limit.QUEUED_FRAMES.value.toInt())
  private var runtimeRelayClosed = false
  private var runtimeRelayTask: Thread? = null
  private val runtimeRelayOverflow = AtomicBoolean(false)
  private var activeBoundary: V9ActiveBoundary? = null
  private var lastCheckpointOutcome: V9ErrorCode? = null
  private var checkpointCompletionCount = 0
  private val lastInputOrder = mutableMapOf<Pair<Long, Int>, Int>()

  init {
    val proposal = authorization.proposal
    require(setOf(proposal.sourcePlayer, proposal.targetPlayer) ==
        setOf(0, authenticatedGuest))
    require(proposal.sourcePlayer == 0) {
      "v9 initial checkpoint is host-coordinated"
    }
    V9CheckpointStateValidation.validateManifestIdentities(
        authorization,
        targetGeneration.identities,
    )
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      try {
        runtimeRelayTask = startTask("netplay-v9-runtime-relay") { runtimeRelayLoop() }
        runtimeRegistration =
            requireNotNull(plan.fourPlayerCoordinator)
                .registerRuntime(
                    authenticatedGuest,
                    object : V9FourPlayerRuntimeRelay {
                      override fun sendInput(value: V9InputState) = offerRelayedInput(value)
                      override fun sendControl(value: V9RuntimeControl) =
                          offerRelayedControl(value)
                    },
                )
      } catch (problem: RuntimeException) {
        closeRuntimeRelay()
        throw problem
      }
    }
  }

  fun start() {
    val proposal = authorization.proposal
    if (proposal.sourcePlayer == localActor()) {
      beginCheckpoint(plan.initialKind, plan.initialOwnerPlayer, null, initial = true)
    } else if (proposal.targetPlayer != localActor()) {
      protocolFailure(V9ErrorCode.CONSENT_REJECTED)
    }
  }

  fun activeBoundary(): V9ActiveBoundary? = synchronized(lock) { activeBoundary }

  fun headerAdmission(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      encodedLength: Long,
      decodedLength: Long,
  ): V9ErrorCode? = synchronized(lock) {
    if (closed) return@synchronized V9ErrorCode.UNEXPECTED_MESSAGE
    val state = lifecycle.snapshot().state
    when (type) {
      V9MessageType.CHECKPOINT -> {
        if (state !in setOf(V9LifecycleState.SYNCHRONIZING, V9LifecycleState.ACTIVE) ||
            checkpointInFlight || flags != 0 ||
            encodedLength !in 88..V9MessageType.CHECKPOINT.spec.maximumEncodedBytes ||
            decodedLength != encodedLength ||
            channel !in 1L..4L && channel != ProtocolV9.GROUP_CHANNEL) {
          V9ErrorCode.CONSENT_REJECTED
        } else null
      }
      V9MessageType.START ->
        if (role != V9Role.CLIENT || state != V9LifecycleState.SYNCHRONIZING ||
            flags != 0 || channel != ProtocolV9.CONTROL_CHANNEL ||
            encodedLength != 16L || decodedLength != 16L) {
          V9ErrorCode.UNEXPECTED_MESSAGE
        } else null
      V9MessageType.READY ->
        if (role != V9Role.SERVER || state != V9LifecycleState.WAIT_READY ||
            flags != V9Flag.RESPONSE.wireMask || channel != ProtocolV9.CONTROL_CHANNEL ||
            encodedLength != 8L || decodedLength != 8L) {
          V9ErrorCode.UNEXPECTED_MESSAGE
        } else null
      V9MessageType.INPUT,
      V9MessageType.RESET,
      V9MessageType.STOP ->
        if (state != V9LifecycleState.ACTIVE || flags != 0 || channel !in 1L..4L ||
            encodedLength != 16L || decodedLength != 16L) {
          V9ErrorCode.UNEXPECTED_MESSAGE
        } else null
      else -> null
    }
  }

  fun handle(frame: V9Frame) {
    val type = requireNotNull(frame.header.type)
    try {
      when (type) {
        V9MessageType.CHECKPOINT -> handleCheckpoint(frame.header.channel, frame.payloadView())
        V9MessageType.START -> handleStart(frame.header.sequence, frame.payloadView())
        V9MessageType.READY -> handleReady(frame.payloadView())
        V9MessageType.INPUT -> handleInput(frame.header.channel, frame.payloadView())
        V9MessageType.RESET,
        V9MessageType.STOP -> handleControl(type, frame.header.channel, frame.payloadView())
        else -> protocolFailure(V9ErrorCode.UNEXPECTED_MESSAGE)
      }
    } catch (failure: V9ProtocolException) {
      fail(failure.reason, diagnostic(failure.reason))
    } catch (_: RuntimeException) {
      fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    }
  }

  fun sendCheckpoint(kind: V9CheckpointKind, owner: Int, frame: Long, initial: Boolean = false) {
    if (initial) throw IllegalArgumentException("initial checkpoint frame is selected at safe point")
    beginCheckpoint(kind, owner, frame, false)
  }

  private fun beginCheckpoint(
      kind: V9CheckpointKind,
      owner: Int,
      frame: Long?,
      initial: Boolean,
  ) {
    synchronized(lock) {
      ensureOpen()
      val state = lifecycle.snapshot().state
      check(state == V9LifecycleState.SYNCHRONIZING || state == V9LifecycleState.ACTIVE)
      val requiredKind =
          if (state == V9LifecycleState.SYNCHRONIZING) plan.initialKind
          else if (mode == V9LinkMode.NORMAL) V9CheckpointKind.SESSION
          else V9CheckpointKind.LINKED_SESSION
      if (kind != requiredKind || owner != localActor() ||
          state == V9LifecycleState.SYNCHRONIZING &&
              (!initial || frame != null || owner != plan.initialOwnerPlayer)) {
        throw IllegalArgumentException("v9 checkpoint root/owner does not match session phase")
      }
      if (checkpointInFlight) throw IllegalStateException("v9 checkpoint is already in flight")
      if (authorization.proposal.sourcePlayer != localActor()) {
        throw IllegalStateException("local endpoint does not own this checkpoint direction")
      }
      checkpointInFlight = true
    }
    try {
      startTask("netplay-v9-state-capture") {
        transmitCheckpoint(kind, owner, frame, initial)
      }
    } catch (_: RuntimeException) {
      synchronized(lock) { checkpointInFlight = false }
      fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    }
  }

  fun sendInput(value: V9InputState) {
    if (value.player != localActor()) throw IllegalArgumentException("v9 input player is not local")
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      ensureActive()
      requireNotNull(plan.fourPlayerCoordinator).broadcastHostInput(value)
      return
    }
    sendRuntime(V9MessageType.INPUT, value.player, V9GameplayCodec.encodeInput(value))
  }

  fun sendControl(value: V9RuntimeControl) {
    if (value.player != localActor()) throw IllegalArgumentException("v9 control player is not local")
    val type =
        when (value.kind) {
          V9RuntimeMessageKind.RESET -> V9MessageType.RESET
          V9RuntimeMessageKind.STOP -> V9MessageType.STOP
          V9RuntimeMessageKind.INPUT -> throw IllegalArgumentException("use sendInput")
        }
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      ensureActive()
      requireNotNull(plan.fourPlayerCoordinator).broadcastHostControl(value)
      return
    }
    sendRuntime(type, value.player, V9GameplayCodec.encodeControl(value))
  }

  private fun offerRelayedInput(value: V9InputState) {
    if (role != V9Role.SERVER || mode != V9LinkMode.FOUR_PLAYER) return
    offerRuntimeRelay(RuntimeRelay.Input(value))
  }

  private fun offerRelayedControl(value: V9RuntimeControl) {
    if (role != V9Role.SERVER || mode != V9LinkMode.FOUR_PLAYER) return
    offerRuntimeRelay(RuntimeRelay.Control(value))
  }

  /**
   * Controller callbacks perform only this bounded, non-blocking offer. The connection-owned
   * worker may wait for wire-state ordering or socket I/O, but the emulation safe point cannot.
   */
  private fun offerRuntimeRelay(value: RuntimeRelay) {
    val admitted = synchronized(runtimeRelayLock) {
      !runtimeRelayClosed && runtimeRelayQueue.offer(value)
    }
    if (admitted) return
    if (!isClosed() && runtimeRelayOverflow.compareAndSet(false, true)) {
      try {
        startTask("netplay-v9-runtime-relay-overflow") {
          if (!isClosed()) protocolFailure(V9ErrorCode.QUEUE_OVERFLOW)
        }
      } catch (_: RuntimeException) {
        // Connection close won task ownership; it already owns destination cleanup.
      }
    }
    throw V9ProtocolException(V9ErrorCode.QUEUE_OVERFLOW, 0)
  }

  private fun runtimeRelayLoop() {
    try {
      while (true) {
        val value = runtimeRelayQueue.poll(50, TimeUnit.MILLISECONDS)
        val stopped = synchronized(runtimeRelayLock) { runtimeRelayClosed }
        if (stopped) return
        when (value) {
          is RuntimeRelay.Input ->
            sendRuntime(
                V9MessageType.INPUT,
                value.value.player,
                V9GameplayCodec.encodeInput(value.value),
            )
          is RuntimeRelay.Control -> {
            val type =
                if (value.value.kind == V9RuntimeMessageKind.RESET) V9MessageType.RESET
                else V9MessageType.STOP
            sendRuntime(type, value.value.player, V9GameplayCodec.encodeControl(value.value))
          }
          null -> Unit
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (problem: V9ProtocolException) {
      if (!isClosed()) protocolFailure(problem.reason)
    } catch (_: RuntimeException) {
      if (!isClosed()) protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    }
  }

  private fun closeRuntimeRelay() {
    val task = synchronized(runtimeRelayLock) {
      if (runtimeRelayClosed) return
      runtimeRelayClosed = true
      runtimeRelayQueue.clear()
      runtimeRelayTask.also { runtimeRelayTask = null }
    }
    task?.takeUnless { it === Thread.currentThread() }?.interrupt()
  }

  private fun sendRuntime(type: V9MessageType, player: Int, payload: ByteArray) {
    try {
      ensureActive()
      val sequence =
          send.send(type, 0, 0, player.toLong() + 1, payload, { Closeable {} }) {
            if (!isClosed()) lifecycle.activeProgress()
          }
      if (sequence == null) protocolFailure(V9ErrorCode.QUEUE_OVERFLOW)
    } finally {
      payload.fill(0)
    }
  }

  private fun ensureActive() = synchronized(lock) {
    ensureOpen()
    check(lifecycle.snapshot().state == V9LifecycleState.ACTIVE)
  }

  private fun transmitCheckpoint(
      kind: V9CheckpointKind,
      owner: Int,
      requestedFrame: Long?,
      initial: Boolean,
  ) {
    val sharedInitial = initial && mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER
    val provider = plan.checkpointProvider
    if (!sharedInitial && provider == null) {
      return protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    }
    val mask = if (mode == V9LinkMode.NORMAL) 1 shl owner else 0x0f
    var state: ByteArray? = null
    var captured: V9CapturedCheckpoint? = null
    var ticket: V9CheckpointGrant.Ticket? = null
    try {
      val request = V9CheckpointRequest(kind, mask, owner, requestedFrame)
      captured =
          if (sharedInitial) {
            requireNotNull(plan.fourPlayerCoordinator)
                .captureInitial(authenticatedGuest, request)
          } else {
            requireNotNull(provider).capture(request)
          }
      val frame = captured.frame
      if (!captured.generation.sameIdentityGeneration(targetGeneration)) {
        throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
      }
      val metadata =
          V9CheckpointMetadata(kind, mask, owner, frame, authorization.proposal.proposalId)
      state = captured.takeStateFile()
      val declaration =
          V9CheckpointDeclaration(
              metadata,
              state.size,
              MessageDigest.getInstance("SHA-256").digest(state),
          )
      ticket =
          grant.preflight(
              declaration,
              localActor(),
              peerActor(),
              checkpointChannel(metadata),
          )
      V9CheckpointStateValidation.decodeAndValidate(
          state,
          declaration,
          captured.generation.identities,
      )
      V9CheckpointStateValidation.validateManifestIdentities(
          authorization,
          captured.generation.identities,
      )
      val payload = V9CheckpointCodec.encode(metadata, state)
      val queuedTicket = checkNotNull(ticket)
      val admitted =
          send.send(
              V9MessageType.CHECKPOINT,
              0,
              0,
              checkpointChannel(metadata),
              payload,
              { Closeable {} },
          ) {
            queuedTicket.commit()
            markCheckpointReady(frame, declaration.stateDigest(), initial)
          }
      payload.fill(0)
      if (admitted == null) throw V9ProtocolException(V9ErrorCode.QUEUE_OVERFLOW, 0)
      ticket = null
    } catch (failure: V9ProtocolException) {
      protocolFailure(failure.reason)
    } catch (_: Exception) {
      protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    } finally {
      ticket?.close()
      captured?.close()
      state?.fill(0)
      synchronized(lock) {
        // A successful queued write remains in flight until its callback commits the ticket.
        if (ticket != null || closed) checkpointInFlight = false
      }
    }
  }

  private fun handleCheckpoint(channel: Long, payload: ByteArray) {
    val declaration = V9CheckpointCodec.decodeDeclaration(payload)
    val lifecycleState = lifecycle.snapshot().state
    val requiredKind =
        if (lifecycleState == V9LifecycleState.SYNCHRONIZING) plan.initialKind
        else if (mode == V9LinkMode.NORMAL) V9CheckpointKind.SESSION
        else V9CheckpointKind.LINKED_SESSION
    if (declaration.metadata.kind != requiredKind ||
        declaration.metadata.ownerPlayer != peerActor() ||
        lifecycleState == V9LifecycleState.SYNCHRONIZING &&
            declaration.metadata.ownerPlayer != plan.initialOwnerPlayer) {
      throw V9ProtocolException(V9ErrorCode.ROOT_KIND_MISMATCH, 0)
    }
    val ticket =
        grant.preflight(declaration, peerActor(), localActor(), channel)
    val state =
        try {
          V9CheckpointCodec.copyStateFile(payload, declaration)
        } catch (failure: Throwable) {
          ticket.close()
          throw failure
        }
    synchronized(lock) { checkpointInFlight = true }
    try {
      startTask("netplay-v9-state-prepare") {
        prepareIncoming(ticket, declaration, state)
      }
    } catch (_: RuntimeException) {
      state.fill(0)
      ticket.close()
      synchronized(lock) { checkpointInFlight = false }
      protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    }
  }

  private fun prepareIncoming(
      ticket: V9CheckpointGrant.Ticket,
      declaration: V9CheckpointDeclaration,
      state: ByteArray,
  ) {
    val registration = PendingPrepareRegistration()
    try {
      val file =
          V9CheckpointStateValidation.decodeAndValidate(
              state,
              declaration,
              targetGeneration.identities,
          )
      val completed = AtomicBoolean(false)
      synchronized(lock) {
        if (closed || preparingIncoming != null) {
          ticket.close()
          checkpointInFlight = false
          return
        }
        preparingIncoming = registration
      }
      val handle =
          plan.checkpointTarget.prepare(
              V9ValidatedCheckpoint(declaration.metadata, file, declaration.digestView()),
              targetGeneration,
          ) { prepared, failure ->
            registration.finish()
            synchronized(lock) {
              if (preparingIncoming === registration) preparingIncoming = null
            }
            if (!completed.compareAndSet(false, true)) {
              prepared?.close()
              return@prepare
            }
            if (failure == null && prepared != null && !isClosed()) {
              val active = lifecycle.snapshot().state == V9LifecycleState.ACTIVE
              synchronized(lock) {
                if (closed || preparedIncoming != null || preparedTicket != null ||
                    committingIncoming != null) {
                  prepared.close()
                  ticket.close()
                  checkpointInFlight = false
                  return@prepare
                }
                preparedIncoming = prepared
                preparedTicket = ticket
                checkpointFrame = declaration.metadata.frame
                checkpointDigest?.fill(0)
                checkpointDigest = declaration.digestView().copyOf()
                if (!active) initialCheckpointReady = true
              }
              if (active) {
                commitPreparedCheckpoint { lifecycle.activeProgress() }
              } else {
                checkpointPrepared()
              }
            } else {
              prepared?.close()
              ticket.close()
              synchronized(lock) { checkpointInFlight = false }
              if (!isClosed()) protocolFailure(failure ?: V9ErrorCode.CANCELLED)
            }
          }
      registration.install(handle)
    } catch (failure: V9ProtocolException) {
      registration.close()
      synchronized(lock) {
        if (preparingIncoming === registration) preparingIncoming = null
      }
      ticket.close()
      synchronized(lock) { checkpointInFlight = false }
      protocolFailure(failure.reason)
    } catch (_: Exception) {
      registration.close()
      synchronized(lock) {
        if (preparingIncoming === registration) preparingIncoming = null
      }
      ticket.close()
      synchronized(lock) { checkpointInFlight = false }
      protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    } finally {
      state.fill(0)
    }
  }

  private fun checkpointPrepared() {
    val pending = synchronized(lock) {
      if (closed) return
      pendingStart.also { if (it != null) pendingStart = null }
    }
    if (pending != null) {
      try {
        acceptStart(pending)
      } catch (failure: V9ProtocolException) {
        protocolFailure(failure.reason)
      } catch (_: RuntimeException) {
        protocolFailure(V9ErrorCode.INTERNAL_ERROR)
      }
    }
  }

  private fun commitPreparedCheckpoint(onSuccess: () -> Unit) {
    val committing: CommittingCheckpoint
    synchronized(lock) {
      val prepared = preparedIncoming ?: return protocolFailure(V9ErrorCode.TOPOLOGY_MISMATCH)
      val ticket = preparedTicket ?: return protocolFailure(V9ErrorCode.TOPOLOGY_MISMATCH)
      if (committingIncoming != null) return protocolFailure(V9ErrorCode.TOPOLOGY_MISMATCH)
      preparedIncoming = null
      preparedTicket = null
      committing = CommittingCheckpoint(prepared, ticket, onSuccess)
      committingIncoming = committing
    }
    try {
      committing.prepared.commit { failure ->
        completeCommit(committing, failure)
      }
    } catch (_: RuntimeException) {
      completeCommit(committing, V9ErrorCode.INTERNAL_ERROR)
    }
  }

  private fun completeCommit(
      committing: CommittingCheckpoint,
      failure: V9ErrorCode?,
  ) {
    if (!committing.completed.compareAndSet(false, true)) return
    synchronized(lock) {
      if (committingIncoming === committing) committingIncoming = null
      checkpointInFlight = false
      lastCheckpointOutcome = failure
      checkpointCompletionCount++
    }
    committing.prepared.close()
    if (failure == null) {
      // The safe-point winner is one complete atomic outcome even if transport close races later.
      committing.ticket.commit()
      if (!isClosed()) committing.onSuccess()
    } else {
      committing.ticket.close()
      if (!isClosed()) protocolFailure(failure)
    }
    if (isClosed()) grant.close()
  }

  private fun markCheckpointReady(frame: Long, digest: ByteArray, initial: Boolean) {
    val pending: PendingStart?
    val active: Boolean
    synchronized(lock) {
      if (closed) return
      checkpointInFlight = false
      checkpointFrame = frame
      checkpointDigest?.fill(0)
      checkpointDigest = digest.copyOf()
      active = lifecycle.snapshot().state == V9LifecycleState.ACTIVE
      if (initial || !active) initialCheckpointReady = true
      pending = pendingStart
      if (pending != null && initialCheckpointReady) pendingStart = null
    }
    if (active) {
      lifecycle.activeProgress()
      return
    }
    if (role == V9Role.SERVER) readyToStart(frame, digest)
    else if (pending != null) {
      try {
        acceptStart(pending)
      } catch (failure: V9ProtocolException) {
        protocolFailure(failure.reason)
      } catch (_: RuntimeException) {
        protocolFailure(V9ErrorCode.INTERNAL_ERROR)
      }
    }
  }

  private fun readyToStart(frame: Long, digest: ByteArray) {
    if (mode == V9LinkMode.FOUR_PLAYER) {
      try {
        coordinatorRegistration =
            requireNotNull(plan.fourPlayerCoordinator)
                .prepared(authenticatedGuest, frame, digest) { sendStart(frame) }
      } catch (failure: V9ProtocolException) {
        protocolFailure(failure.reason)
      }
    } else {
      sendStart(frame)
    }
  }

  private fun sendStart(frame: Long) {
    val sessionId = plan.sessionIds.nextId()
    if (sessionId == 0L) return protocolFailure(V9ErrorCode.INTERNAL_ERROR)
    val payload =
        ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(sessionId).putLong(frame).array()
    val sequence =
        send.send(
            V9MessageType.START,
            0,
            0,
            ProtocolV9.CONTROL_CHANNEL,
            payload,
            { admittedSequence ->
              val lifecycleRollback = lifecycle.admitServerStart()
              synchronized(lock) {
                try {
                  ensureOpen()
                  check(startSessionId == null && startSequence == null)
                  startSessionId = sessionId
                  startSequence = admittedSequence
                } catch (failure: RuntimeException) {
                  lifecycleRollback.close()
                  throw failure
                }
              }
              Closeable {
                synchronized(lock) {
                  if (startSessionId == sessionId && startSequence == admittedSequence) {
                    startSessionId = null
                    startSequence = null
                  }
                }
                lifecycleRollback.close()
              }
            },
        ) {}
    payload.fill(0)
    if (sequence == null) return protocolFailure(V9ErrorCode.QUEUE_OVERFLOW)
  }

  private fun handleStart(sequence: Long, payload: ByteArray) {
    if (payload.size != 16) throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, 0)
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
    val pending = PendingStart(sequence, buffer.long, buffer.long)
    if (pending.sessionId == 0L) throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, 0)
    val ready = synchronized(lock) {
      if (pendingStart != null || startSessionId != null) {
        throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
      }
      if (!initialCheckpointReady) {
        pendingStart = pending
        false
      } else true
    }
    if (ready) acceptStart(pending)
  }

  private fun acceptStart(value: PendingStart) {
    val frame = synchronized(lock) { checkpointFrame }
    if (frame == null || frame != value.frame) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    lifecycle.clientStartReceived()
    synchronized(lock) {
      startSessionId = value.sessionId
      startSequence = value.sequence
    }
    commitPreparedCheckpoint { sendReady(value) }
  }

  private fun sendReady(value: PendingStart) {
    val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value.sessionId).array()
    val sequence =
        send.send(
            V9MessageType.READY,
            V9Flag.RESPONSE.wireMask,
            value.sequence,
            ProtocolV9.CONTROL_CHANNEL,
            payload,
            { lifecycle.admitClientReady() },
        ) {
          publishActive(value.sessionId, value.frame)
        }
    payload.fill(0)
    if (sequence == null) protocolFailure(V9ErrorCode.QUEUE_OVERFLOW)
  }

  private fun handleReady(payload: ByteArray) {
    if (payload.size != 8) throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, 0)
    val received = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
    val sessionId = synchronized(lock) { startSessionId }
    val frame = synchronized(lock) { checkpointFrame }
    if (sessionId == null || frame == null || received != sessionId) {
      throw V9ProtocolException(V9ErrorCode.CORRELATION_ERROR, 0)
    }
    if (mode == V9LinkMode.FOUR_PLAYER) {
      requireNotNull(plan.fourPlayerCoordinator).ready(authenticatedGuest) {
        lifecycle.serverReadyReceived()
        publishActive(sessionId, frame)
      }
    } else {
      lifecycle.serverReadyReceived()
      publishActive(sessionId, frame)
    }
  }

  private fun publishActive(sessionId: Long, frame: Long) {
    val value = V9ActiveBoundary(role, mode, authenticatedGuest, sessionId, frame)
    synchronized(lock) {
      if (closed || activeBoundary != null) return
      activeBoundary = value
    }
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      try {
        requireNotNull(plan.fourPlayerCoordinator).activateRuntime(authenticatedGuest)
      } catch (failure: V9ProtocolException) {
        protocolFailure(failure.reason)
        return
      }
    }
    onActive(value)
  }

  private fun handleInput(channel: Long, payload: ByteArray) {
    val value = V9GameplayCodec.decodeInput(payload, channel)
    if (!acceptsPeerRuntimePlayer(value.player)) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    synchronized(lock) {
      val key = value.frame to value.player
      val previous = lastInputOrder[key]
      if (previous != null && value.intraFrameOrder <= previous) {
        throw V9ProtocolException(V9ErrorCode.SEQUENCE_ERROR, 0)
      }
      lastInputOrder[key] = value.intraFrameOrder
      if (lastInputOrder.size > V9Limit.QUEUED_FRAMES.value) {
        val oldest = lastInputOrder.keys.minByOrNull { it.first }
        if (oldest != null) lastInputOrder.remove(oldest)
      }
    }
    plan.gameplayTarget.input(value) { failure ->
      if (failure == null && !isClosed()) {
        lifecycle.activeProgress()
        if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
          try {
            requireNotNull(plan.fourPlayerCoordinator)
                .relayGuestInput(authenticatedGuest, value)
          } catch (problem: V9ProtocolException) {
            protocolFailure(problem.reason)
          }
        }
      } else if (failure != null && !isClosed()) protocolFailure(failure)
    }
  }

  private fun handleControl(type: V9MessageType, channel: Long, payload: ByteArray) {
    val value = V9GameplayCodec.decodeControl(type, payload, channel)
    if (!acceptsPeerRuntimePlayer(value.player)) {
      throw V9ProtocolException(V9ErrorCode.TOPOLOGY_MISMATCH, 0)
    }
    plan.gameplayTarget.control(value) { failure ->
      if (failure == null && !isClosed()) {
        lifecycle.activeProgress()
        if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
          try {
            requireNotNull(plan.fourPlayerCoordinator)
                .relayGuestControl(authenticatedGuest, value)
          } catch (problem: V9ProtocolException) {
            protocolFailure(problem.reason)
          }
        }
      } else if (failure != null && !isClosed()) protocolFailure(failure)
    }
  }

  private fun acceptsPeerRuntimePlayer(player: Int): Boolean =
      if (mode == V9LinkMode.NORMAL || role == V9Role.SERVER) {
        player == peerActor()
      } else {
        player in 0..3 && player != authenticatedGuest
      }

  private fun checkpointChannel(metadata: V9CheckpointMetadata): Long =
      if (metadata.kind == V9CheckpointKind.LINKED_SESSION) ProtocolV9.GROUP_CHANNEL
      else metadata.ownerPlayer.toLong() + 1

  private fun localActor(): Int = if (role == V9Role.SERVER) 0 else authenticatedGuest
  private fun peerActor(): Int = if (role == V9Role.SERVER) authenticatedGuest else 0

  private fun ensureOpen() {
    check(!closed) { "v9 playable session is closed" }
  }

  private fun isClosed(): Boolean = synchronized(lock) { closed }

  internal fun runtimeRelayQueueSize(): Int = synchronized(runtimeRelayLock) {
    runtimeRelayQueue.size
  }

  internal fun offerRelayedInputForTest(value: V9InputState) = offerRelayedInput(value)

  private fun protocolFailure(reason: V9ErrorCode) {
    fail(reason, diagnostic(reason))
  }

  private fun diagnostic(reason: V9ErrorCode): V9Diagnostic = when (reason) {
    V9ErrorCode.ROM_MISMATCH,
    V9ErrorCode.PROFILE_MISMATCH,
    V9ErrorCode.STATEFILE_VERSION,
    V9ErrorCode.ROOT_KIND_MISMATCH,
    V9ErrorCode.TOPOLOGY_MISMATCH,
    V9ErrorCode.CHECKSUM_MISMATCH,
    V9ErrorCode.STATEFILE_MALFORMED -> V9Diagnostic.TRANSFER_REJECTED
    V9ErrorCode.CANCELLED -> V9Diagnostic.CANCELLED
    V9ErrorCode.TIMEOUT -> V9Diagnostic.TIMEOUT
    V9ErrorCode.QUEUE_OVERFLOW -> V9Diagnostic.QUEUE_FULL
    else -> V9Diagnostic.IO_FAILURE
  }

  override fun close() {
    val registration: Closeable?
    val runtime: Closeable?
    val preparing: PendingPrepareRegistration?
    val committing: CommittingCheckpoint?
    synchronized(lock) {
      if (closed) return
      closed = true
      registration = coordinatorRegistration
      coordinatorRegistration = null
      runtime = runtimeRegistration
      runtimeRegistration = null
      preparing = preparingIncoming
      preparingIncoming = null
      committing = committingIncoming
      checkpointDigest?.fill(0)
      checkpointDigest = null
      preparedIncoming?.close()
      preparedIncoming = null
      preparedTicket?.close()
      preparedTicket = null
      pendingStart = null
      lastInputOrder.clear()
    }
    closeRuntimeRelay()
    registration?.close()
    runtime?.close()
    preparing?.close()
    val commitStillApplying =
        committing != null && !committing.prepared.cancelBeforeCommit()
    if (committing != null && !commitStillApplying) {
      completeCommit(committing, V9ErrorCode.CANCELLED)
    }
    if (mode == V9LinkMode.FOUR_PLAYER && role == V9Role.SERVER) {
      plan.fourPlayerCoordinator?.abandon(authenticatedGuest)
    }
    // A safe-point commit that already won retains its grant ticket through its one completion.
    // Closing it here would make the complete atomic apply look unconsumed after disconnect.
    if (!commitStillApplying) grant.close()
    try {
      plan.checkpointTarget.close()
    } catch (_: RuntimeException) {
      // Cancellation is final; caller-owned cleanup cannot reopen the session.
    }
    try {
      plan.gameplayTarget.disconnected(peerActor())
    } catch (_: RuntimeException) {
      // Caller-owned cleanup cannot reopen an already closed network session.
    }
  }

  internal fun grantUses(): Int = grant.used()

  internal fun checkpointOutcome(): V9ErrorCode? = synchronized(lock) { lastCheckpointOutcome }

  internal fun checkpointCompletions(): Int = synchronized(lock) { checkpointCompletionCount }

  private sealed class RuntimeRelay {
    data class Input(val value: V9InputState) : RuntimeRelay()
    data class Control(val value: V9RuntimeControl) : RuntimeRelay()
  }

  private class PendingPrepareRegistration : Closeable {
    private val lock = Any()
    private var handle: Closeable? = null
    private var finished = false
    private var cancelled = false

    fun install(value: Closeable) {
      val discard = synchronized(lock) {
        if (finished || cancelled) true else {
          handle = value
          false
        }
      }
      if (discard) value.close()
    }

    fun finish() = synchronized(lock) {
      finished = true
      handle = null
    }

    override fun close() {
      val owned = synchronized(lock) {
        if (finished || cancelled) null else {
          cancelled = true
          handle.also { handle = null }
        }
      }
      owned?.close()
    }
  }

  private class CommittingCheckpoint(
      val prepared: V9PreparedCheckpoint,
      val ticket: V9CheckpointGrant.Ticket,
      val onSuccess: () -> Unit,
  ) {
    val completed = AtomicBoolean(false)
  }

  private data class PendingStart(val sequence: Long, val sessionId: Long, val frame: Long)
}
