package eu.rekawek.coffeegb.controller.mobile.network

import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendCompletion
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendGeneration
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendRequest
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendStatus
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.CompletionResult
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.OfferResult
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SelectableChannel
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Presentation-safe owner-loop phase. */
enum class MobileAdapterNetworkPhase {
  IDLE,
  RESOLVING,
  CONNECTING,
  CONNECTED,
  TRANSFERRING,
  CANCELLING,
  CLOSED,
}

/** Presentation-safe backend diagnostic. No member carries endpoint or exception text. */
enum class MobileAdapterNetworkError {
  NONE,
  NETWORK_CONSENT_REQUIRED,
  PRIVATE_LOCAL_CONSENT_REQUIRED,
  DESTINATION_DENIED,
  INVALID_REQUEST,
  TRANSFER_LIMIT,
  DNS_TIMEOUT,
  DNS_MALFORMED,
  DNS_LOOKUP_FAILED,
  DNS_UNREACHABLE,
  CONNECTION_LIMIT,
  INVALID_CONNECTION,
  CONNECTION_REFUSED,
  UNREACHABLE,
  TIMEOUT,
  REMOTE_CLOSED,
  QUEUE_FULL,
  CANCELLED,
  IO_FAILURE,
}

/** Opaque process-local attachment identity; deliberately unrelated to a host or address. */
class MobileAdapterBackendAttachmentIdentity internal constructor(internal val value: Long) {
  override fun equals(other: Any?): Boolean =
      this === other || other is MobileAdapterBackendAttachmentIdentity && value == other.value

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = "MobileAdapterBackendAttachmentIdentity($value)"
}

/** Immutable, redacted status snapshot retained in a bounded queue. */
class MobileAdapterNetworkStatus(
    val attachment: MobileAdapterBackendAttachmentIdentity,
    val ownershipVersion: Long,
    val phase: MobileAdapterNetworkPhase,
    val error: MobileAdapterNetworkError,
    val activeConnections: Int,
    val pendingRequests: Int,
    val slot: Int?,
) {
  init {
    require(ownershipVersion >= 0) { "Ownership version must not be negative" }
    require(activeConnections in 0..MobileAdapterNetworkBackend.MAX_CONNECTIONS) {
      "Active connection count is invalid"
    }
    require(pendingRequests in 0..MobileAdapterBackendPort.MAX_REQUEST_SLOTS) {
      "Pending request count is invalid"
    }
    require(slot == null || slot in 0 until MobileAdapterNetworkBackend.MAX_CONNECTIONS) {
      "Connection slot is invalid"
    }
  }

  override fun toString(): String =
      "MobileAdapterNetworkStatus(attachment=$attachment, ownershipVersion=$ownershipVersion, " +
          "phase=$phase, error=$error, activeConnections=$activeConnections, " +
          "pendingRequests=$pendingRequests, slot=$slot)"
}

/**
 * Controller-owned, bounded Mobile Adapter custom-server backend.
 *
 * One daemon owner loop performs raw DNS, TCP, and UDP through nonblocking NIO. Public port calls
 * never perform host I/O or wait. Cancellation atomically rotates the core generation, clears at
 * most eight retained items and wakes the selector; the owner loop closes
 * physical resources idempotently.
 */
class MobileAdapterNetworkBackend(
    initialPolicy: MobileAdapterDestinationPolicy = MobileAdapterDestinationPolicy.offline(),
    initialAuthorization: MobileAdapterRuntimeAuthorization =
        MobileAdapterRuntimeAuthorization.DISABLED,
    private val onClosed: (MobileAdapterNetworkBackend) -> Unit = {},
) : MobileAdapterBackendPort, AutoCloseable {
  val port: MobileAdapterBackendPort
    get() = this

  val attachmentIdentity =
      MobileAdapterBackendAttachmentIdentity(NEXT_ATTACHMENT_ID.getAndIncrement())

  private val policy =
      AtomicReference(PolicyContext(initialPolicy, initialAuthorization, revisionToken = 0))
  private val dnsClient = MobileAdapterRawDnsClient()
  private val portState =
      AtomicReference(PortState.empty(BackendGeneration.create(), ownershipVersion = 0))
  private val selector = AtomicReference<Selector?>()
  /** Serializes only bounded status publication; host-resource cleanup never holds this monitor. */
  private val statusPublicationLock = Any()
  private val operationActive = AtomicBoolean()
  private val activeConnectionCount = AtomicInteger()
  private val activeConnectionOwnershipVersion = AtomicLong(0)
  private val revoked = AtomicBoolean()
  private val closeStarted = AtomicBoolean()
  private val closed = AtomicBoolean()
  private val closeNotified = AtomicBoolean()
  private var terminalStatusPublished = false
  private val terminated = java.util.concurrent.CountDownLatch(1)
  private val statuses = ArrayBlockingQueue<MobileAdapterNetworkStatus>(MAX_STATUS_SNAPSHOTS)

  private val executor =
      ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          ArrayBlockingQueue(1),
          ThreadFactory { task ->
            Thread(task, "mobile-adapter-network-${attachmentIdentity.value}").also {
              it.isDaemon = true
            }
          },
          ThreadPoolExecutor.AbortPolicy(),
      )

  init {
    publishStatus(MobileAdapterNetworkPhase.IDLE, MobileAdapterNetworkError.NONE)
    executor.execute(::ownerLoop)
    executor.shutdown()
  }

  /** Monotonically revokes both runtime gates before rotating any admitted generation. */
  fun revokeAuthorization() {
    if (closed.get() || !revoked.compareAndSet(false, true)) return
    cancelAll()
  }

  override fun generation(): BackendGeneration = portState.get().generation

  override fun offer(generation: BackendGeneration, request: BackendRequest): OfferResult {
    if (closed.get() || revoked.get()) return OfferResult.UNAVAILABLE
    val bytes = request.payload().size
    if (bytes > MAX_COMMAND_PAYLOAD_BYTES) {
      val current = portState.get()
      if (generation === current.generation) {
        publishStatus(
            settledPhase(current.ownershipVersion),
            MobileAdapterNetworkError.TRANSFER_LIMIT,
            current.ownershipVersion,
        )
      }
      return OfferResult.BYTE_LIMIT
    }
    while (true) {
      val current = portState.get()
      if (generation !== current.generation) return OfferResult.STALE_GENERATION
      if (current.contains(request.requestId())) return OfferResult.DUPLICATE_ID
      if (current.occupiedSlots >= MobileAdapterBackendPort.MAX_REQUEST_SLOTS) {
        publishStatus(
            settledPhase(current.ownershipVersion),
            MobileAdapterNetworkError.QUEUE_FULL,
            current.ownershipVersion,
        )
        return OfferResult.REQUEST_LIMIT
      }
      if (bytes > MobileAdapterBackendPort.MAX_BUFFERED_BYTES - current.bufferedBytes) {
        publishStatus(
            settledPhase(current.ownershipVersion),
            MobileAdapterNetworkError.QUEUE_FULL,
            current.ownershipVersion,
        )
        return OfferResult.BYTE_LIMIT
      }
      val updated =
          current.copy(
              queued = current.queued + request,
              bufferedBytes = current.bufferedBytes + bytes,
          )
      if (portState.compareAndSet(current, updated)) {
        selector.get()?.wakeup()
        return OfferResult.ACCEPTED
      }
    }
  }

  override fun complete(
      generation: BackendGeneration,
      requestId: Long,
      status: BackendStatus,
      response: ByteArray,
  ): CompletionResult {
    if (response.size > MAX_COMMAND_PAYLOAD_BYTES) {
      val current = portState.get()
      if (generation === current.generation) {
        publishStatus(
            settledPhase(current.ownershipVersion),
            MobileAdapterNetworkError.TRANSFER_LIMIT,
            current.ownershipVersion,
        )
      }
      return CompletionResult.BYTE_LIMIT
    }
    while (true) {
      val current = portState.get()
      if (generation !== current.generation) return CompletionResult.STALE_GENERATION
      val requestBytes = current.inFlight[requestId] ?: return CompletionResult.UNKNOWN_ID
      val retained = current.bufferedBytes - requestBytes
      if (response.size > MobileAdapterBackendPort.MAX_BUFFERED_BYTES - retained) {
        return CompletionResult.BYTE_LIMIT
      }
      val inFlight = current.inFlight - requestId
      val completion = BackendCompletion(generation, requestId, status, response)
      val updated =
          current.copy(
              inFlight = inFlight,
              completions = current.completions + completion,
              bufferedBytes = retained + response.size,
          )
      if (portState.compareAndSet(current, updated)) return CompletionResult.COMPLETED
    }
  }

  override fun poll(expectedGeneration: BackendGeneration): BackendCompletion? {
    while (true) {
      val current = portState.get()
      if (expectedGeneration !== current.generation || current.completions.isEmpty()) return null
      val completion = current.completions.first()
      val updated =
          current.copy(
              completions = current.completions.drop(1),
              bufferedBytes = current.bufferedBytes - completion.payload().size,
          )
      if (portState.compareAndSet(current, updated)) return completion
    }
  }

  /** Constant-space logical revocation. Physical close belongs to the owner loop. */
  override fun cancelAll() {
    rotateCancellation(closeOwned = false)
  }

  private fun rotateCancellation(closeOwned: Boolean) {
    if (!closeOwned && (closeStarted.get() || closed.get())) return
    synchronized(statusPublicationLock) {
      if (terminalStatusPublished ||
          (!closeOwned && (closeStarted.get() || closed.get()))) return
      var cancelledOwnershipVersion: Long
      while (true) {
        val current = portState.get()
        cancelledOwnershipVersion = Math.addExact(current.ownershipVersion, 1)
        if (portState.compareAndSet(
                current,
                PortState.empty(BackendGeneration.create(), cancelledOwnershipVersion),
            )) break
      }
      statuses.clear()
      enqueueStatus(
          MobileAdapterNetworkPhase.CANCELLING,
          MobileAdapterNetworkError.CANCELLED,
          cancelledOwnershipVersion,
      )
    }
    selector.get()?.wakeup()
  }

  override fun occupiedRequestSlots(): Int = portState.get().occupiedSlots

  override fun bufferedBytes(): Int = portState.get().bufferedBytes

  fun hasExternalWork(): Boolean =
      operationActive.get() ||
          activeConnectionCount.get() != 0 ||
          occupiedRequestSlots() != 0

  fun pollStatus(): MobileAdapterNetworkStatus? = statuses.poll()

  /** Current cancellation ownership; callers use it only to reject older queued status. */
  fun ownershipVersion(): Long = portState.get().ownershipVersion

  /** Test/off-thread shutdown hook. The emulator and EDT must never call this. */
  fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
    require(timeout >= 0) { "Termination timeout must not be negative" }
    return terminated.await(timeout, unit)
  }

  fun awaitTermination(timeoutMillis: Long): Boolean =
      awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)

  override fun close() {
    if (closed.get() || !closeStarted.compareAndSet(false, true)) return
    revoked.set(true)
    rotateCancellation(closeOwned = true)
    closed.set(true)
    selector.get()?.wakeup()
  }

  fun isClosed(): Boolean = closed.get()

  /** True only after the owner loop has closed every host resource and exited. */
  fun isTerminated(): Boolean = terminated.count == 0L

  private fun ownerLoop() {
    var ownedSelector: Selector? = null
    val connections = linkedMapOf<Int, OwnedConnection>()
    val capabilities = mutableListOf<DnsCapability>()
    // PortState starts at zero. Beginning from that known value ensures a cancellation committed
    // before the owner thread starts is still acknowledged with cleanup and a following IDLE.
    var observedOwnership = 0L
    try {
      ownedSelector = Selector.open()
      selector.set(ownedSelector)
      while (!closed.get()) {
        observedOwnership =
            reconcileOwnerOwnership(observedOwnership, connections, capabilities)
        val claimed = claimNext()
        if (claimed == null) {
          ownedSelector.select(MAX_SELECTOR_SLEEP_MILLIS)
          ownedSelector.selectedKeys().clear()
          continue
        }
        // Generation rotation and ownership version are committed in one PortState CAS. A new
        // request can therefore arrive between the loop's first sample and claim; reconcile once
        // more before it can observe sockets or DNS capabilities owned by the prior generation.
        observedOwnership =
            reconcileOwnerOwnership(observedOwnership, connections, capabilities)
        operationActive.set(true)
        try {
          process(claimed, ownedSelector, connections, capabilities)
        } catch (_: RuntimeException) {
          finish(
              claimed,
              BackendStatus.COMMUNICATION_FAILED,
              EMPTY_BYTES,
              MobileAdapterNetworkError.IO_FAILURE,
          )
        } finally {
          operationActive.set(false)
        }
      }
    } catch (_: IOException) {
      // The owner loop exposes only stable typed status.
    } finally {
      // A selector failure must not leave a live-looking port that can retain new requests.
      closed.set(true)
      portState.updateAndGet {
        PortState.empty(BackendGeneration.create(), it.ownershipVersion)
      }
      closeConnections(connections, portState.get().ownershipVersion)
      selector.compareAndSet(ownedSelector, null)
      try {
        ownedSelector?.close()
      } catch (_: IOException) {
        // Idempotent teardown remains typed.
      }
      operationActive.set(false)
      activeConnectionCount.set(0)
      publishTerminalStatus()
      terminated.countDown()
      notifyClosed()
    }
  }

  private fun reconcileOwnerOwnership(
      observedOwnership: Long,
      connections: MutableMap<Int, OwnedConnection>,
      capabilities: MutableList<DnsCapability>,
  ): Long {
    var acknowledgedOwnership = observedOwnership
    while (true) {
      val targetOwnership = portState.get().ownershipVersion
      if (targetOwnership == acknowledgedOwnership) return acknowledgedOwnership

      // Socket/channel close belongs exclusively to the owner and must never make public
      // cancellation wait on a host operation.
      closeConnections(connections, targetOwnership)
      capabilities.clear()
      val latestOwnership =
          synchronized(statusPublicationLock) {
            val latest = portState.get().ownershipVersion
            if (latest == targetOwnership && !terminalStatusPublished) {
              enqueueStatus(
                  MobileAdapterNetworkPhase.IDLE,
                  MobileAdapterNetworkError.NONE,
                  targetOwnership,
              )
            }
            latest
          }
      if (latestOwnership == targetOwnership) return targetOwnership
      acknowledgedOwnership = targetOwnership
    }
  }

  private fun notifyClosed() {
    if (!closeNotified.compareAndSet(false, true)) return
    try {
      onClosed(this)
    } catch (_: RuntimeException) {
      // Listener detail is neither operational status nor safe diagnostics.
    }
  }

  private fun process(
      claimed: ClaimedRequest,
      selector: Selector,
      connections: MutableMap<Int, OwnedConnection>,
      capabilities: MutableList<DnsCapability>,
  ) {
    if (!isCurrent(claimed.generation)) return
    when (claimed.request.command()) {
      COMMAND_TRANSFER -> transfer(claimed, selector, connections)
      COMMAND_TCP_OPEN -> open(claimed, selector, connections, capabilities, MobileAdapterTransportProtocol.TCP)
      COMMAND_TCP_CLOSE -> closeConnection(claimed, connections, MobileAdapterTransportProtocol.TCP)
      COMMAND_UDP_OPEN -> open(claimed, selector, connections, capabilities, MobileAdapterTransportProtocol.UDP)
      COMMAND_UDP_CLOSE -> closeConnection(claimed, connections, MobileAdapterTransportProtocol.UDP)
      COMMAND_DNS_QUERY -> query(claimed, selector, capabilities)
      else ->
          finish(
              claimed,
              BackendStatus.COMMUNICATION_FAILED,
              EMPTY_BYTES,
              MobileAdapterNetworkError.INVALID_REQUEST,
          )
    }
  }

  private fun query(
      claimed: ClaimedRequest,
      selector: Selector,
      capabilities: MutableList<DnsCapability>,
  ) {
    val alias = parseGuestAlias(claimed.request.payload())
    if (alias == null) {
      finish(claimed, BackendStatus.LOOKUP_FAILED, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_REQUEST)
      return
    }
    val context = policy.get()
    val rules = context.destination.rulesForCanonicalAlias(alias)
    if (rules.isEmpty()) {
      finish(claimed, BackendStatus.LOOKUP_FAILED, EMPTY_BYTES, MobileAdapterNetworkError.DESTINATION_DENIED)
      return
    }
    val target = rules.first().target
    publishStatus(
        MobileAdapterNetworkPhase.RESOLVING,
        MobileAdapterNetworkError.NONE,
        claimed.ownershipVersion,
    )
    when (val resolution = resolveTarget(claimed.generation, context, target, selector)) {
      is Resolution.Failure ->
          finish(claimed, BackendStatus.LOOKUP_FAILED, EMPTY_BYTES, resolution.error)
      is Resolution.Success -> {
        if (!isCurrent(claimed.generation, context)) return
        capabilities.removeAll { it.alias == alias }
        capabilities.add(
            DnsCapability(
                alias,
                target,
                resolution.addresses,
                context.revisionToken,
                capabilityDeadline(),
            ))
        finish(claimed, BackendStatus.SUCCESS, resolution.addresses.first().bytes(), MobileAdapterNetworkError.NONE)
      }
    }
  }

  private fun open(
      claimed: ClaimedRequest,
      selector: Selector,
      connections: MutableMap<Int, OwnedConnection>,
      capabilities: MutableList<DnsCapability>,
      protocol: MobileAdapterTransportProtocol,
  ) {
    val payload = claimed.request.payload()
    if (payload.size != OPEN_REQUEST_BYTES) {
      finish(claimed, BackendStatus.CONNECTION_FAILED, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_REQUEST)
      return
    }
    if (connections.size >= MAX_CONNECTIONS) {
      finish(claimed, BackendStatus.CONNECTION_LIMIT, EMPTY_BYTES, MobileAdapterNetworkError.CONNECTION_LIMIT)
      return
    }
    val requestedAddress = MobileAdapterIpv4Address.fromBytes(payload.copyOfRange(0, 4))
    val guestPort = ((payload[4].toInt() and 0xff) shl 8) or (payload[5].toInt() and 0xff)
    val context = policy.get()
    if (context.destination.decide(requestedAddress, context.authorization) !=
        MobileAdapterDestinationDecision.ALLOWED) {
      finishDecisionFailure(claimed, context.destination.decide(requestedAddress, context.authorization), false)
      return
    }
    val now = System.nanoTime()
    capabilities.removeAll { it.deadlineNanos - now <= 0 || it.policyToken != context.revisionToken }
    val matching =
        context.destination
            .rules()
            .filter { rule ->
              rule.protocol == protocol &&
                  rule.guestPort == guestPort &&
                  when {
                    rule.target.literalAddress != null -> rule.target.literalAddress == requestedAddress
                    else ->
                        capabilities.any { capability ->
                          capability.alias == rule.canonicalAlias &&
                              capability.target == rule.target &&
                              requestedAddress in capability.addresses
                        }
                  }
            }
    if (matching.size != 1) {
      finish(claimed, BackendStatus.CONNECTION_FAILED, EMPTY_BYTES, MobileAdapterNetworkError.DESTINATION_DENIED)
      return
    }
    val rule = matching.single()
    publishStatus(
        MobileAdapterNetworkPhase.RESOLVING,
        MobileAdapterNetworkError.NONE,
        claimed.ownershipVersion,
    )
    val resolution = resolveTarget(claimed.generation, context, rule.target, selector)
    if (resolution !is Resolution.Success) {
      val error = (resolution as Resolution.Failure).error
      finish(claimed, BackendStatus.CONNECTION_FAILED, EMPTY_BYTES, error)
      return
    }
    if (requestedAddress !in resolution.addresses ||
        !isCurrent(claimed.generation, context) ||
        !context.destination.contains(rule) ||
        context.destination.decide(requestedAddress, context.authorization) !=
            MobileAdapterDestinationDecision.ALLOWED) {
      finish(claimed, BackendStatus.CONNECTION_FAILED, EMPTY_BYTES, MobileAdapterNetworkError.DESTINATION_DENIED)
      return
    }

    val id = (0 until MAX_CONNECTIONS).first { it !in connections }
    publishStatus(
        MobileAdapterNetworkPhase.CONNECTING,
        MobileAdapterNetworkError.NONE,
        claimed.ownershipVersion,
        id,
    )
    val endpoint = InetSocketAddress(requestedAddress.inetAddress(), rule.targetPort)
    val result =
        when (protocol) {
          MobileAdapterTransportProtocol.TCP -> openTcp(claimed.generation, context, id, rule, requestedAddress, endpoint, selector)
          MobileAdapterTransportProtocol.UDP -> openUdp(claimed.generation, context, id, rule, requestedAddress, endpoint)
        }
    when (result) {
      is OpenResult.Failure ->
          finish(claimed, BackendStatus.CONNECTION_FAILED, EMPTY_BYTES, result.error)
      is OpenResult.Success -> {
        if (!isCurrent(claimed.generation, context)) {
          result.connection.close()
          return
        }
        connections[id] = result.connection
        updateActiveConnectionCount(connections, claimed.ownershipVersion)
        publishStatus(
            MobileAdapterNetworkPhase.CONNECTED,
            MobileAdapterNetworkError.NONE,
            claimed.ownershipVersion,
            id,
        )
        finish(claimed, BackendStatus.SUCCESS, byteArrayOf(id.toByte()), MobileAdapterNetworkError.NONE)
      }
    }
  }

  private fun closeConnection(
      claimed: ClaimedRequest,
      connections: MutableMap<Int, OwnedConnection>,
      protocol: MobileAdapterTransportProtocol,
  ) {
    val payload = claimed.request.payload()
    if (payload.size != 1) {
      finish(claimed, BackendStatus.INVALID_CONNECTION, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_REQUEST)
      return
    }
    val id = payload[0].toInt() and 0xff
    val connection = connections[id]
    if (connection == null ||
        connection.generation !== claimed.generation ||
        connection.protocol != protocol) {
      if (connection != null && connection.generation !== claimed.generation) {
        connections.remove(id)
        connection.close()
        updateActiveConnectionCount(connections, claimed.ownershipVersion)
      }
      finish(claimed, BackendStatus.INVALID_CONNECTION, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_CONNECTION)
      return
    }
    connections.remove(id)
    connection.close()
    updateActiveConnectionCount(connections, claimed.ownershipVersion)
    finish(claimed, BackendStatus.SUCCESS, byteArrayOf(id.toByte()), MobileAdapterNetworkError.NONE)
  }

  private fun transfer(
      claimed: ClaimedRequest,
      selector: Selector,
      connections: MutableMap<Int, OwnedConnection>,
  ) {
    val payload = claimed.request.payload()
    if (payload.isEmpty() || payload.size > MAX_COMMAND_PAYLOAD_BYTES) {
      finish(claimed, BackendStatus.INVALID_CONNECTION, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_REQUEST)
      return
    }
    val id = payload[0].toInt() and 0xff
    val connection = connections[id]
    if (connection == null || connection.generation !== claimed.generation) {
      if (connection != null) {
        connections.remove(id)
        connection.close()
        updateActiveConnectionCount(connections, claimed.ownershipVersion)
      }
      finish(claimed, BackendStatus.INVALID_CONNECTION, EMPTY_BYTES, MobileAdapterNetworkError.INVALID_CONNECTION)
      return
    }
    val context = policy.get()
    if (!isCurrent(claimed.generation, context) ||
        !context.destination.contains(connection.rule) ||
        context.destination.decide(connection.address, context.authorization) !=
            MobileAdapterDestinationDecision.ALLOWED) {
      connections.remove(id)
      connection.close()
      updateActiveConnectionCount(connections, claimed.ownershipVersion)
      finish(
          claimed,
          BackendStatus.REMOTE_CLOSED,
          EMPTY_BYTES,
          MobileAdapterNetworkError.DESTINATION_DENIED,
          slot = id,
      )
      return
    }
    publishStatus(
        MobileAdapterNetworkPhase.TRANSFERRING,
        MobileAdapterNetworkError.NONE,
        claimed.ownershipVersion,
        id,
    )
    val body = payload.copyOfRange(1, payload.size)
    val result =
        when (connection) {
          is OwnedTcp -> transferTcp(claimed.generation, context, connection, body, selector)
          is OwnedUdp -> transferUdp(claimed.generation, context, connection, body, selector)
          is RemoteClosedConnection ->
              TransferResult.Failure(
                  BackendStatus.REMOTE_CLOSED,
                  MobileAdapterNetworkError.REMOTE_CLOSED,
                  close = true,
              )
        }
    when (result) {
      is TransferResult.Success -> {
        if (result.remoteClosed) {
          connection.close()
          connections[id] =
              RemoteClosedConnection(
                  connection.id,
                  connection.rule,
                  connection.address,
                  connection.generation,
                  connection.protocol,
              )
          updateActiveConnectionCount(connections, claimed.ownershipVersion)
        }
        val response = ByteArray(Math.addExact(1, result.payload.size))
        response[0] = id.toByte()
        result.payload.copyInto(response, 1)
        finish(
            claimed,
            BackendStatus.SUCCESS,
            response,
            if (result.remoteClosed) {
              MobileAdapterNetworkError.REMOTE_CLOSED
            } else {
              MobileAdapterNetworkError.NONE
            },
            slot = id,
        )
      }
      is TransferResult.Failure -> {
        if (result.close) {
          connections.remove(id)
          connection.close()
          updateActiveConnectionCount(connections, claimed.ownershipVersion)
        }
        finish(claimed, result.status, EMPTY_BYTES, result.error, slot = id)
      }
    }
  }

  private fun resolveTarget(
      generation: BackendGeneration,
      context: PolicyContext,
      target: MobileAdapterTransportTarget,
      selector: Selector,
  ): Resolution {
    target.literalAddress?.let { address ->
      return when (val decision = context.destination.decide(address, context.authorization)) {
        MobileAdapterDestinationDecision.ALLOWED -> Resolution.Success(listOf(address))
        else -> Resolution.Failure(decisionError(decision))
      }
    }
    val resolver = context.destination.resolver ?: return Resolution.Failure(MobileAdapterNetworkError.DNS_UNREACHABLE)
    val resolverDecision = context.destination.decide(resolver.address, context.authorization)
    if (resolverDecision != MobileAdapterDestinationDecision.ALLOWED) {
      return Resolution.Failure(decisionError(resolverDecision))
    }
    val result =
        dnsClient.resolve(
            selector,
            resolver,
            checkNotNull(target.canonicalName),
            DNS_TIMEOUT_MILLIS,
            MobileAdapterCancellationProbe { isCurrent(generation, context) },
        )
    return when (result) {
      is MobileAdapterDnsResult.Failure ->
          Resolution.Failure(
              when (result.failure) {
                MobileAdapterDnsFailure.CANCELLED -> MobileAdapterNetworkError.CANCELLED
                MobileAdapterDnsFailure.TIMEOUT -> MobileAdapterNetworkError.DNS_TIMEOUT
                MobileAdapterDnsFailure.MALFORMED -> MobileAdapterNetworkError.DNS_MALFORMED
                MobileAdapterDnsFailure.RESPONSE_FAILURE ->
                    MobileAdapterNetworkError.DNS_LOOKUP_FAILED
                MobileAdapterDnsFailure.UNREACHABLE -> MobileAdapterNetworkError.DNS_UNREACHABLE
              })
      is MobileAdapterDnsResult.Success -> {
        val decisions = result.addresses.map { context.destination.decide(it, context.authorization) }
        if (result.addresses.isEmpty() || decisions.any { it != MobileAdapterDestinationDecision.ALLOWED }) {
          val firstDenied = decisions.firstOrNull { it != MobileAdapterDestinationDecision.ALLOWED }
          Resolution.Failure(decisionError(firstDenied ?: MobileAdapterDestinationDecision.HARD_DENIED))
        } else {
          Resolution.Success(result.addresses)
        }
      }
    }
  }

  private fun openTcp(
      generation: BackendGeneration,
      context: PolicyContext,
      id: Int,
      rule: MobileAdapterDestinationRule,
      address: MobileAdapterIpv4Address,
      endpoint: InetSocketAddress,
      selector: Selector,
  ): OpenResult {
    var channel: SocketChannel? = null
    return try {
      if (!isCurrent(generation, context)) {
        return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      }
      channel = SocketChannel.open()
      channel.configureBlocking(false)
      if (!isCurrent(generation, context)) {
        return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      }
      val connected = channel.connect(endpoint)
      if (!connected) {
        val deadline = deadline(CONNECT_TIMEOUT_MILLIS)
        val ready = awaitReady(channel, selector, SelectionKey.OP_CONNECT, deadline, generation, context)
        if (ready != WaitResult.READY || !channel.finishConnect()) {
          val error = if (ready == WaitResult.CANCELLED) MobileAdapterNetworkError.CANCELLED else MobileAdapterNetworkError.TIMEOUT
          return OpenResult.Failure(error)
        }
      }
      if (!isCurrent(generation, context)) return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      val owned = OwnedTcp(id, rule, address, generation, channel)
      channel = null
      OpenResult.Success(owned)
    } catch (_: ConnectException) {
      OpenResult.Failure(MobileAdapterNetworkError.CONNECTION_REFUSED)
    } catch (_: NoRouteToHostException) {
      OpenResult.Failure(MobileAdapterNetworkError.UNREACHABLE)
    } catch (_: IOException) {
      OpenResult.Failure(MobileAdapterNetworkError.IO_FAILURE)
    } finally {
      closeQuietly(channel)
    }
  }

  private fun openUdp(
      generation: BackendGeneration,
      context: PolicyContext,
      id: Int,
      rule: MobileAdapterDestinationRule,
      address: MobileAdapterIpv4Address,
      endpoint: InetSocketAddress,
  ): OpenResult {
    var channel: DatagramChannel? = null
    return try {
      if (!isCurrent(generation, context)) return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      channel = DatagramChannel.open()
      channel.configureBlocking(false)
      if (!isCurrent(generation, context)) return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      channel.connect(endpoint)
      if (!isCurrent(generation, context)) return OpenResult.Failure(MobileAdapterNetworkError.CANCELLED)
      val owned = OwnedUdp(id, rule, address, generation, channel)
      channel = null
      OpenResult.Success(owned)
    } catch (_: IOException) {
      OpenResult.Failure(MobileAdapterNetworkError.IO_FAILURE)
    } finally {
      closeQuietly(channel)
    }
  }

  private fun transferTcp(
      generation: BackendGeneration,
      context: PolicyContext,
      connection: OwnedTcp,
      body: ByteArray,
      selector: Selector,
  ): TransferResult {
    if (body.size > MAX_TRANSFER_BODY_BYTES) {
      return TransferResult.Failure(
          BackendStatus.COMMUNICATION_FAILED,
          MobileAdapterNetworkError.TRANSFER_LIMIT,
          false,
      )
    }
    try {
      if (body.isNotEmpty()) {
        val output = ByteBuffer.wrap(body)
        val deadline = deadline(WRITE_TIMEOUT_MILLIS)
        while (output.hasRemaining()) {
          if (!isCurrent(generation, context)) return cancelledTransfer()
          if (connection.channel.write(output) == 0) {
            when (awaitReady(connection.channel, selector, SelectionKey.OP_WRITE, deadline, generation, context)) {
              WaitResult.READY -> Unit
              WaitResult.TIMEOUT -> return timeoutTransfer(close = true)
              WaitResult.CANCELLED -> return cancelledTransfer()
            }
          }
        }
      }
      val input = ByteBuffer.allocate(MAX_TRANSFER_BODY_BYTES + 1)
      val deadline = deadline(READ_TIMEOUT_MILLIS)
      var remoteClosedAfterPayload = false
      while (true) {
        if (!isCurrent(generation, context)) return cancelledTransfer()
        val read = connection.channel.read(input)
        if (read < 0) {
          return if (input.position() == 0) {
            TransferResult.Failure(BackendStatus.REMOTE_CLOSED, MobileAdapterNetworkError.REMOTE_CLOSED, true)
          } else {
            break
          }
        }
        if (input.position() > MAX_TRANSFER_BODY_BYTES) {
          return TransferResult.Failure(
              BackendStatus.REMOTE_CLOSED,
              MobileAdapterNetworkError.TRANSFER_LIMIT,
              true,
          )
        }
        if (read > 0) {
          // Drain only bytes immediately available; a later transfer owns later stream data.
          while (input.hasRemaining()) {
            val more = connection.channel.read(input)
            if (more < 0) {
              remoteClosedAfterPayload = true
              break
            }
            if (more == 0) break
          }
          if (input.position() > MAX_TRANSFER_BODY_BYTES) {
            return TransferResult.Failure(
                BackendStatus.REMOTE_CLOSED,
                MobileAdapterNetworkError.TRANSFER_LIMIT,
                true,
            )
          }
          break
        }
        when (awaitReady(connection.channel, selector, SelectionKey.OP_READ, deadline, generation, context)) {
          WaitResult.READY -> Unit
          WaitResult.TIMEOUT -> return timeoutTransfer(close = true)
          WaitResult.CANCELLED -> return cancelledTransfer()
        }
      }
      return TransferResult.Success(
          input.array().copyOf(input.position()),
          remoteClosed = remoteClosedAfterPayload,
      )
    } catch (_: IOException) {
      return TransferResult.Failure(
          BackendStatus.REMOTE_CLOSED,
          MobileAdapterNetworkError.IO_FAILURE,
          true,
      )
    }
  }

  private fun transferUdp(
      generation: BackendGeneration,
      context: PolicyContext,
      connection: OwnedUdp,
      body: ByteArray,
      selector: Selector,
  ): TransferResult {
    if (body.size > MAX_TRANSFER_BODY_BYTES) {
      return TransferResult.Failure(
          BackendStatus.COMMUNICATION_FAILED,
          MobileAdapterNetworkError.TRANSFER_LIMIT,
          false,
      )
    }
    try {
      if (body.isNotEmpty()) {
        val output = ByteBuffer.wrap(body)
        val deadline = deadline(WRITE_TIMEOUT_MILLIS)
        while (output.hasRemaining()) {
          if (!isCurrent(generation, context)) return cancelledTransfer()
          val written = connection.channel.write(output)
          if (written == 0) {
            when (awaitReady(connection.channel, selector, SelectionKey.OP_WRITE, deadline, generation, context)) {
              WaitResult.READY -> Unit
              WaitResult.TIMEOUT -> return timeoutTransfer(close = false)
              WaitResult.CANCELLED -> return cancelledTransfer()
            }
          }
        }
      }
      val input = ByteBuffer.allocate(MAX_TRANSFER_BODY_BYTES + 1)
      val deadline = deadline(READ_TIMEOUT_MILLIS)
      while (true) {
        if (!isCurrent(generation, context)) return cancelledTransfer()
        // DatagramChannel.read() returns zero both when no datagram is ready and when it consumes
        // a legitimate zero-length datagram. receive() preserves that protocol distinction.
        val sender = connection.channel.receive(input)
        if (sender != null) {
          if (input.position() > MAX_TRANSFER_BODY_BYTES) {
            return TransferResult.Failure(
                BackendStatus.COMMUNICATION_FAILED,
                MobileAdapterNetworkError.TRANSFER_LIMIT,
                false,
            )
          }
          return TransferResult.Success(input.array().copyOf(input.position()))
        }
        when (awaitReady(connection.channel, selector, SelectionKey.OP_READ, deadline, generation, context)) {
          WaitResult.READY -> Unit
          WaitResult.TIMEOUT -> return timeoutTransfer(close = true)
          WaitResult.CANCELLED -> return cancelledTransfer()
        }
      }
    } catch (_: PortUnreachableException) {
      return TransferResult.Failure(BackendStatus.COMMUNICATION_FAILED, MobileAdapterNetworkError.UNREACHABLE, false)
    } catch (_: IOException) {
      return TransferResult.Failure(
          BackendStatus.REMOTE_CLOSED,
          MobileAdapterNetworkError.IO_FAILURE,
          true,
      )
    }
  }

  private fun awaitReady(
      channel: SelectableChannel,
      selector: Selector,
      operation: Int,
      deadline: Long,
      generation: BackendGeneration,
      context: PolicyContext,
  ): WaitResult {
    var key: SelectionKey? = null
    return try {
      key = channel.register(selector, operation)
      while (isCurrent(generation, context)) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return WaitResult.TIMEOUT
        val millis =
            minOf(
                MAX_SELECTOR_SLEEP_MILLIS,
                maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining)),
            )
        selector.select(millis)
        val ready = key.isValid && key.readyOps() and operation != 0
        selector.selectedKeys().clear()
        if (ready) return WaitResult.READY
      }
      WaitResult.CANCELLED
    } finally {
      key?.cancel()
      try {
        selector.selectNow()
      } catch (_: IOException) {
        // The operation result remains typed.
      }
      selector.selectedKeys().clear()
    }
  }

  private fun claimNext(): ClaimedRequest? {
    while (true) {
      val current = portState.get()
      val request = current.queued.firstOrNull() ?: return null
      val requestBytes = request.payload().size
      val updated =
          current.copy(
              queued = current.queued.drop(1),
              inFlight = current.inFlight + (request.requestId() to requestBytes),
          )
      if (portState.compareAndSet(current, updated)) {
        return ClaimedRequest(current.generation, current.ownershipVersion, request)
      }
    }
  }

  private fun finish(
      claimed: ClaimedRequest,
      status: BackendStatus,
      payload: ByteArray,
      error: MobileAdapterNetworkError,
      slot: Int? = null,
  ) {
    var completion = complete(claimed.generation, claimed.request.requestId(), status, payload)
    if (completion == CompletionResult.BYTE_LIMIT) {
      completion =
          complete(
              claimed.generation,
              claimed.request.requestId(),
              BackendStatus.COMMUNICATION_FAILED,
              EMPTY_BYTES,
          )
    }
    if (completion == CompletionResult.COMPLETED) {
      publishStatus(settledPhase(claimed.ownershipVersion), error, claimed.ownershipVersion, slot)
    }
  }

  private fun finishDecisionFailure(
      claimed: ClaimedRequest,
      decision: MobileAdapterDestinationDecision,
      lookup: Boolean,
  ) {
    finish(
        claimed,
        if (lookup) BackendStatus.LOOKUP_FAILED else BackendStatus.CONNECTION_FAILED,
        EMPTY_BYTES,
        decisionError(decision),
    )
  }

  private fun decisionError(decision: MobileAdapterDestinationDecision): MobileAdapterNetworkError =
      when (decision) {
        MobileAdapterDestinationDecision.ALLOWED -> MobileAdapterNetworkError.NONE
        MobileAdapterDestinationDecision.NETWORK_CONSENT_REQUIRED ->
            MobileAdapterNetworkError.NETWORK_CONSENT_REQUIRED
        MobileAdapterDestinationDecision.PRIVATE_LOCAL_CONSENT_REQUIRED ->
            MobileAdapterNetworkError.PRIVATE_LOCAL_CONSENT_REQUIRED
        MobileAdapterDestinationDecision.HARD_DENIED -> MobileAdapterNetworkError.DESTINATION_DENIED
      }

  private fun isCurrent(generation: BackendGeneration): Boolean =
      !closed.get() && !revoked.get() && generation === portState.get().generation

  private fun isCurrent(generation: BackendGeneration, context: PolicyContext): Boolean =
      isCurrent(generation) && policy.get() === context

  private fun parseGuestAlias(payload: ByteArray): String? {
    if (payload.isEmpty() || payload.size > MAX_COMMAND_PAYLOAD_BYTES) return null
    val zero = payload.indexOf(0)
    val length = if (zero < 0) payload.size else zero
    if (length !in 1..MAX_HOST_BYTES) return null
    if (zero >= 0 && payload.drop(zero).any { it.toInt() != 0 }) return null
    val value =
        buildString(length) {
          repeat(length) {
            val byte = payload[it].toInt() and 0xff
            if (byte !in 0x21..0x7e) return null
            append(byte.toChar())
          }
        }
    return try {
      canonicalMobileAdapterHost(value)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun publishStatus(
      phase: MobileAdapterNetworkPhase,
      error: MobileAdapterNetworkError,
      statusOwnershipVersion: Long = portState.get().ownershipVersion,
      slot: Int? = null,
  ) {
    synchronized(statusPublicationLock) {
      if (terminalStatusPublished ||
          statusOwnershipVersion < portState.get().ownershipVersion) return
      enqueueStatus(phase, error, statusOwnershipVersion, slot)
    }
  }

  private fun publishTerminalStatus() {
    synchronized(statusPublicationLock) {
      if (terminalStatusPublished) return
      terminalStatusPublished = true
      statuses.clear()
      enqueueStatus(
          MobileAdapterNetworkPhase.CLOSED,
          MobileAdapterNetworkError.NONE,
          portState.get().ownershipVersion,
      )
    }
  }

  /** Caller owns [statusPublicationLock]. */
  private fun enqueueStatus(
      phase: MobileAdapterNetworkPhase,
      error: MobileAdapterNetworkError,
      statusOwnershipVersion: Long,
      slot: Int? = null,
  ) {
    val snapshot =
        MobileAdapterNetworkStatus(
            attachmentIdentity,
            statusOwnershipVersion,
            phase,
            error,
            activeConnectionsFor(statusOwnershipVersion),
            occupiedRequestSlots(),
            slot,
        )
    if (!statuses.offer(snapshot)) {
      statuses.poll()
      statuses.offer(snapshot)
    }
  }

  private fun closeConnections(
      connections: MutableMap<Int, OwnedConnection>,
      ownershipVersion: Long,
  ) {
    connections.values.forEach(OwnedConnection::close)
    connections.clear()
    activeConnectionCount.set(0)
    activeConnectionOwnershipVersion.set(ownershipVersion)
  }

  private fun updateActiveConnectionCount(
      connections: Map<Int, OwnedConnection>,
      ownershipVersion: Long,
  ) {
    activeConnectionCount.set(connections.values.count { it !is RemoteClosedConnection })
    activeConnectionOwnershipVersion.set(ownershipVersion)
  }

  private fun activeConnectionsFor(ownershipVersion: Long): Int =
      if (activeConnectionOwnershipVersion.get() == ownershipVersion) {
        activeConnectionCount.get()
      } else {
        0
      }

  private fun settledPhase(ownershipVersion: Long): MobileAdapterNetworkPhase =
      if (activeConnectionsFor(ownershipVersion) == 0) {
        MobileAdapterNetworkPhase.IDLE
      } else {
        MobileAdapterNetworkPhase.CONNECTED
      }

  private fun capabilityDeadline(): Long = deadline(CAPABILITY_MILLIS)

  private fun deadline(timeoutMillis: Long): Long {
    val now = System.nanoTime()
    val delta = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    return try {
      Math.addExact(now, delta)
    } catch (_: ArithmeticException) {
      Long.MAX_VALUE
    }
  }

  private fun timeoutTransfer(close: Boolean): TransferResult.Failure =
      TransferResult.Failure(
          if (close) BackendStatus.REMOTE_CLOSED else BackendStatus.COMMUNICATION_FAILED,
          MobileAdapterNetworkError.TIMEOUT,
          close,
      )

  private fun cancelledTransfer(): TransferResult.Failure =
      TransferResult.Failure(BackendStatus.CANCELLED, MobileAdapterNetworkError.CANCELLED, true)

  private fun closeQuietly(channel: SelectableChannel?) {
    try {
      channel?.close()
    } catch (_: IOException) {
      // Stable typed cleanup owns presentation.
    }
  }

  private data class PolicyContext(
      val destination: MobileAdapterDestinationPolicy,
      val authorization: MobileAdapterRuntimeAuthorization,
      val revisionToken: Long,
  )

  private data class PortState(
      val generation: BackendGeneration,
      val ownershipVersion: Long,
      val queued: List<BackendRequest>,
      val inFlight: Map<Long, Int>,
      val completions: List<BackendCompletion>,
      val bufferedBytes: Int,
  ) {
    val occupiedSlots: Int
      get() = queued.size + inFlight.size + completions.size

    fun contains(id: Long): Boolean =
        queued.any { it.requestId() == id } || id in inFlight || completions.any { it.requestId() == id }

    companion object {
      fun empty(generation: BackendGeneration, ownershipVersion: Long): PortState =
          PortState(generation, ownershipVersion, emptyList(), emptyMap(), emptyList(), 0)
    }
  }

  private data class ClaimedRequest(
      val generation: BackendGeneration,
      val ownershipVersion: Long,
      val request: BackendRequest,
  )

  private data class DnsCapability(
      val alias: String,
      val target: MobileAdapterTransportTarget,
      val addresses: List<MobileAdapterIpv4Address>,
      val policyToken: Long,
      val deadlineNanos: Long,
  )

  private sealed interface Resolution {
    class Success(addresses: List<MobileAdapterIpv4Address>) : Resolution {
      val addresses = addresses.toList()
    }

    class Failure(val error: MobileAdapterNetworkError) : Resolution
  }

  private sealed interface OpenResult {
    class Success(val connection: OwnedConnection) : OpenResult
    class Failure(val error: MobileAdapterNetworkError) : OpenResult
  }

  private sealed interface TransferResult {
    class Success(val payload: ByteArray, val remoteClosed: Boolean = false) : TransferResult
    class Failure(
        val status: BackendStatus,
        val error: MobileAdapterNetworkError,
        val close: Boolean,
    ) : TransferResult
  }

  private enum class WaitResult {
    READY,
    TIMEOUT,
    CANCELLED,
  }

  private sealed class OwnedConnection(
      val id: Int,
      val rule: MobileAdapterDestinationRule,
      val address: MobileAdapterIpv4Address,
      val generation: BackendGeneration,
  ) : AutoCloseable {
    abstract val protocol: MobileAdapterTransportProtocol
  }

  private class OwnedTcp(
      id: Int,
      rule: MobileAdapterDestinationRule,
      address: MobileAdapterIpv4Address,
      generation: BackendGeneration,
      val channel: SocketChannel,
  ) : OwnedConnection(id, rule, address, generation) {
    override val protocol = MobileAdapterTransportProtocol.TCP

    override fun close() = closeQuiet(channel)
  }

  private class OwnedUdp(
      id: Int,
      rule: MobileAdapterDestinationRule,
      address: MobileAdapterIpv4Address,
      generation: BackendGeneration,
      val channel: DatagramChannel,
  ) : OwnedConnection(id, rule, address, generation) {
    override val protocol = MobileAdapterTransportProtocol.UDP

    override fun close() = closeQuiet(channel)
  }

  /** Logical slot retained until the core consumes REMOTE_CLOSED or explicitly closes it. */
  private class RemoteClosedConnection(
      id: Int,
      rule: MobileAdapterDestinationRule,
      address: MobileAdapterIpv4Address,
      generation: BackendGeneration,
      override val protocol: MobileAdapterTransportProtocol,
  ) : OwnedConnection(id, rule, address, generation) {
    override fun close() = Unit
  }

  companion object {
    const val MAX_CONNECTIONS = 2
    const val MAX_COMMAND_PAYLOAD_BYTES = 254
    const val MAX_TRANSFER_BODY_BYTES = 253
    const val MAX_HOST_BYTES = 253
    const val DNS_TIMEOUT_MILLIS = 2_000L
    const val CONNECT_TIMEOUT_MILLIS = 3_000L
    const val WRITE_TIMEOUT_MILLIS = 1_000L
    const val READ_TIMEOUT_MILLIS = 1_000L
    const val CAPABILITY_MILLIS = 10_000L
    const val MAX_STATUS_SNAPSHOTS = 16
    private const val MAX_SELECTOR_SLEEP_MILLIS = 100L
    private const val OPEN_REQUEST_BYTES = 6
    private const val COMMAND_TRANSFER = 0x15
    private const val COMMAND_TCP_OPEN = 0x23
    private const val COMMAND_TCP_CLOSE = 0x24
    private const val COMMAND_UDP_OPEN = 0x25
    private const val COMMAND_UDP_CLOSE = 0x26
    private const val COMMAND_DNS_QUERY = 0x28
    private val EMPTY_BYTES = ByteArray(0)
    private val NEXT_ATTACHMENT_ID = AtomicLong(1)

    private fun closeQuiet(channel: SelectableChannel) {
      try {
        channel.close()
      } catch (_: IOException) {
        // Stable typed cleanup owns presentation.
      }
    }
  }
}
