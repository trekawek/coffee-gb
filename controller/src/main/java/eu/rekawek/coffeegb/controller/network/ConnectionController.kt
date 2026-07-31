package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus

/** Owns one correlated network worker and drops callbacks from workers superseded by a retry. */
class ConnectionController(private val eventBus: EventBus) {

  private val commandLock = Any()
  private val lifecycleLock = Any()
  private var client: ActiveClient? = null
  private var server: ActiveServer? = null

  init {
    eventBus.register<StartServerEvent> { startServer(it.mode, it.attemptId) }
    eventBus.register<StopServerEvent> { stopServer(it.attemptId) }
    eventBus.register<StartClientEvent> { startClient(it.host, it.attemptId) }
    eventBus.register<StopClientEvent> { stopClient(it.attemptId) }
  }

  private fun startServer(mode: LinkMode, attemptId: Long) {
    synchronized(commandLock) {
      val retired = retireWorkers()
      retired.client?.stop()
      retired.server?.stop()

      val generation = Any()
      val worker =
          TcpServer(
              eventBus,
              mode = mode,
              attemptId = attemptId,
              lifecyclePublisher = { publishServerLifecycle(generation, it) },
          )
      synchronized(lifecycleLock) { server = ActiveServer(worker, generation, attemptId) }
      Thread(worker, workerThreadName("server", attemptId)).start()
    }
  }

  private fun startClient(host: String, attemptId: Long) {
    synchronized(commandLock) {
      val retired = retireWorkers()
      retired.client?.stop()
      retired.server?.stop()

      val generation = Any()
      val worker =
          TcpClient(
              host,
              eventBus,
              attemptId = attemptId,
              lifecyclePublisher = { publishClientLifecycle(generation, it) },
          )
      synchronized(lifecycleLock) { client = ActiveClient(worker, generation, attemptId) }
      Thread(worker, workerThreadName("client", attemptId)).start()
    }
  }

  /** Explicit stops retain the generation until its terminal callback has been published. */
  private fun stopClient(attemptId: Long) {
    synchronized(commandLock) {
      val worker =
          synchronized(lifecycleLock) {
            client?.takeIf { attemptId == LEGACY_ATTEMPT || it.attemptId == attemptId }?.worker
          }
      worker?.stop()
    }
  }

  /** Explicit stops retain the generation until its terminal callback has been published. */
  private fun stopServer(attemptId: Long) {
    synchronized(commandLock) {
      val worker =
          synchronized(lifecycleLock) {
            server?.takeIf { attemptId == LEGACY_ATTEMPT || it.attemptId == attemptId }?.worker
          }
      worker?.stop()
    }
  }

  private fun retireWorkers(): RetiredWorkers =
      synchronized(lifecycleLock) {
        val retired = RetiredWorkers(client?.worker, server?.worker)
        client = null
        server = null
        retired
      }

  private fun publishServerLifecycle(generation: Any, event: Event) {
    synchronized(lifecycleLock) {
      val active = server ?: return
      if (active.generation !== generation) return
      if (event is ServerStartFailedEvent || event is ServerStoppedEvent) server = null
      eventBus.post(event)
    }
  }

  private fun publishClientLifecycle(generation: Any, event: Event) {
    synchronized(lifecycleLock) {
      val active = client ?: return
      if (active.generation !== generation) return
      if (event is ClientDisconnectedFromServerEvent) client = null
      eventBus.post(event)
    }
  }

  private data class ActiveClient(
      val worker: TcpClient,
      val generation: Any,
      val attemptId: Long,
  )

  private data class ActiveServer(
      val worker: TcpServer,
      val generation: Any,
      val attemptId: Long,
  )

  private data class RetiredWorkers(val client: TcpClient?, val server: TcpServer?)

  data class StartServerEvent(
      val mode: LinkMode = LinkMode.NORMAL,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class StopServerEvent(val attemptId: Long = LEGACY_ATTEMPT) : Event

  data class StartClientEvent(
      val host: String,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class StopClientEvent(val attemptId: Long = LEGACY_ATTEMPT) : Event

  data class ServerStartedEvent(
      val mode: LinkMode = LinkMode.NORMAL,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  enum class ServerStartFailure {
    PORT_UNAVAILABLE,
  }

  data class ServerStartFailedEvent(
      val failure: ServerStartFailure,
      val port: Int,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ServerStoppedEvent(val attemptId: Long = LEGACY_ATTEMPT) : Event

  data class ServerGotConnectionEvent(
      val host: String,
      val mode: LinkMode = LinkMode.NORMAL,
      val player: Int = 0,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ServerLostConnectionEvent(val attemptId: Long = LEGACY_ATTEMPT) : Event

  data class ClientHandshakeCompletedEvent(
      val mode: LinkMode,
      val player: Int,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ClientConnectionRejectedEvent(
      val message: String,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ClientProtocolErrorEvent(
      val message: String,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ServerProtocolErrorEvent(
      val player: Int,
      val message: String,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ClientConnectedToServerEvent(
      val mode: LinkMode = LinkMode.NORMAL,
      val player: Int = 1,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ServerPlayerCountEvent(
      val connected: Int,
      val required: Int,
      val mode: LinkMode,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ServerPlayerDisconnectedEvent(
      val player: Int,
      val attemptId: Long = LEGACY_ATTEMPT,
  ) : Event

  data class ClientDisconnectedFromServerEvent(val attemptId: Long = LEGACY_ATTEMPT) : Event

  companion object {
    /** Attempt zero keeps source compatibility for non-correlated callers and legacy callbacks. */
    const val LEGACY_ATTEMPT: Long = 0L

    private fun workerThreadName(role: String, attemptId: Long): String =
        if (attemptId == LEGACY_ATTEMPT) "netplay-$role" else "netplay-$role-$attemptId"
  }
}
