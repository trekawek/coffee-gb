package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Accepts one normal-link client or three fixed-slot DMG-07 clients. */
class TcpServer(
    private val eventBus: EventBus,
    private val port: Int = PORT,
    private val mode: LinkMode = LinkMode.NORMAL,
    private val attemptId: Long = ConnectionController.LEGACY_ATTEMPT,
    private val lifecyclePublisher: (Event) -> Unit = { eventBus.post(it) },
) : Runnable {

  @Volatile private var doStop = false

  @Volatile private var serverSocket: ServerSocket? = null

  private val clients = ConcurrentHashMap<Int, ClientHandle>()

  private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()

  private val pendingConnections = ConcurrentHashMap<Socket, Connection>()

  private val lock = Any()

  private val pendingPlayers = mutableSetOf<Int>()

  private val handshakeExecutor =
      ThreadPoolExecutor(
          StateLimits.NETPLAY_HANDSHAKE_WORKERS,
          StateLimits.NETPLAY_HANDSHAKE_WORKERS,
          0,
          TimeUnit.MILLISECONDS,
          ArrayBlockingQueue(StateLimits.NETPLAY_PENDING_HANDSHAKES),
          { task -> Thread(task, "netplay-handshake-worker").also { it.isDaemon = true } },
      )

  @Volatile private var sessionStarted = false

  override fun run() {
    var started = false
    var startFailurePublished = false
    try {
      ServerSocket(port).use { listener ->
        serverSocket = listener
        listener.soTimeout = 100
        lifecyclePublisher(ConnectionController.ServerStartedEvent(mode, attemptId))
        started = true
        if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
          // The adapter belongs to the host and is live even with no clients attached.
          lifecyclePublisher(
              ConnectionController.ServerGotConnectionEvent(
                  REDACTED_PEER,
                  mode,
                  0,
                  attemptId,
              ))
        }
        while (!doStop) {
          try {
            accept(listener)
          } catch (_: SocketTimeoutException) {
            // Poll doStop.
          } catch (e: SocketException) {
            if (!doStop) {
              LOG.error(
                  "Error accepting netplay connection ({})",
                  netplaySocketFailureSummary(e),
              )
            }
          } catch (e: IOException) {
            if (!doStop) {
              LOG.error(
                  "Error accepting netplay connection ({})",
                  netplaySocketFailureSummary(e),
              )
            }
          }
        }
      }
    } catch (failure: IOException) {
      if (!doStop) {
        if (started) {
          LOG.error(
              "Netplay server on TCP port {} stopped unexpectedly ({})",
              port,
              netplaySocketFailureSummary(failure),
          )
        } else {
          LOG.error(
              "Unable to start netplay server on TCP port {} ({})",
              port,
              netplaySocketFailureSummary(failure),
          )
          startFailurePublished = true
          lifecyclePublisher(
              ConnectionController.ServerStartFailedEvent(
                  ConnectionController.ServerStartFailure.PORT_UNAVAILABLE,
                  port,
                  attemptId,
              ))
        }
      }
    } finally {
      serverSocket = null
      stopClients()
      handshakeExecutor.shutdownNow()
      if (!startFailurePublished && (started || doStop)) {
        lifecyclePublisher(ConnectionController.ServerStoppedEvent(attemptId))
      }
    }
  }

  private fun accept(listener: ServerSocket) {
    val socket = listener.accept()
    val admitted =
        synchronized(lock) {
          if (doStop || pendingSockets.size >= StateLimits.NETPLAY_PENDING_HANDSHAKES) {
            false
          } else {
            pendingSockets += socket
            true
          }
        }
    if (!admitted) {
      LOG.atInfo().log("Rejecting incoming connection: pending handshake limit reached")
      try {
        Connection.reject(socket.getOutputStream(), Connection.RejectionReason.SERVER_BUSY)
      } finally {
        socket.close()
      }
      return
    }
    var player: Int? = null
    try {
      TcpClient.configure(socket)
      player = synchronized(lock) { reservePlayer() }
      if (player == null) {
        LOG.info("Rejecting extra incoming connection: {} session is full", mode)
        try {
          Connection.reject(socket.getOutputStream(), Connection.RejectionReason.SERVER_FULL)
        } finally {
          pendingSockets.remove(socket)
          socket.close()
        }
        return
      }
      val reservedPlayer = requireNotNull(player)
      val connection =
          Connection(
              socket.getInputStream(),
              socket.getOutputStream(),
              eventBus,
              true,
              mode,
              reservedPlayer,
              cancelTransport = { socket.close() },
              attemptId = attemptId,
              lifecyclePublisher = lifecyclePublisher,
          )
      pendingConnections[socket] = connection
      handshakeExecutor.execute { completeHandshake(socket, connection, reservedPlayer) }
    } catch (e: RejectedExecutionException) {
      synchronized(lock) { player?.let(pendingPlayers::remove) }
      pendingSockets.remove(socket)
      pendingConnections.remove(socket)?.close()
      socket.close()
      if (!doStop) throw IOException("Netplay handshake executor is full", e)
    } catch (e: IOException) {
      synchronized(lock) { player?.let(pendingPlayers::remove) }
      pendingSockets.remove(socket)
      socket.close()
      throw e
    }
  }

  private fun completeHandshake(socket: Socket, connection: Connection, player: Int) {
    var claimed = false
    try {
      socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
      connection.completeServerHandshake()
      socket.soTimeout = 0
      val handle = ClientHandle(player, socket, connection)
      claimed =
          synchronized(lock) {
            pendingPlayers.remove(player)
            if (doStop || clients.containsKey(player)) false
            else {
              clients[player] = handle
              true
            }
          }
      if (!claimed) return
      pendingConnections.remove(socket)
      pendingSockets.remove(socket)
      LOG.info("Player {} connected", player + 1)
      lifecyclePublisher(
          ConnectionController.ServerPlayerCountEvent(
              clients.size,
              mode.playerCount - 1,
              mode,
              attemptId,
          ))
      Thread({ runClient(handle) }, "netplay-player-${player + 1}").start()
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        connection.startSession()
      } else {
        startSessionIfFull()
      }
    } catch (e: Connection.CompatibilityException) {
      if (!doStop) {
        val message = safePeerDiagnostic(e.message, "Incompatible netplay peer")
        lifecyclePublisher(
            ConnectionController.ServerProtocolErrorEvent(
                player,
                message,
                attemptId,
            ))
      }
    } catch (e: SocketTimeoutException) {
      if (!doStop) LOG.info("Player {} capability handshake timed out", player + 1)
    } catch (e: IOException) {
      if (!doStop) {
        LOG.info(
            "Player {} capability handshake failed ({})",
            player + 1,
            netplaySocketFailureSummary(e),
        )
      }
    } finally {
      synchronized(lock) { pendingPlayers.remove(player) }
      pendingConnections.remove(socket)
      pendingSockets.remove(socket)
      if (!claimed) closeConnection(connection, socket)
    }
  }

  private fun reservePlayer(): Int? {
    if (mode == LinkMode.NORMAL) {
      // Normal mode deliberately permits several speculative handshakes for its single slot so a
      // silent peer cannot monopolize admission. The first completed capability exchange claims it.
      return 1.takeUnless { clients.containsKey(it) }
    }
    val player =
        (1 until mode.playerCount).firstOrNull {
          !clients.containsKey(it) && it !in pendingPlayers
        }
    player?.let(pendingPlayers::add)
    return player
  }

  private fun startSessionIfFull() {
    val toStart =
        synchronized(lock) {
          if (sessionStarted || clients.size != mode.playerCount - 1) return
          sessionStarted = true
          clients.values.sortedBy { it.player }
        }
    lifecyclePublisher(
        ConnectionController.ServerGotConnectionEvent(REDACTED_PEER, mode, 0, attemptId))
    toStart.forEach { it.connection.startSession() }
  }

  private fun runClient(handle: ClientHandle) {
    try {
      handle.connection.use { it.run() }
    } catch (e: Connection.ProtocolException) {
      if (!doStop) {
        val message = safePeerDiagnostic(e.message, e.reason.userMessage)
        LOG.info("Player {} protocol error: {}", handle.player + 1, message)
        lifecyclePublisher(
            ConnectionController.ServerProtocolErrorEvent(
                handle.player,
                message,
                attemptId,
            ))
      }
    } catch (e: Connection.CompatibilityException) {
      if (!doStop) {
        val message = safePeerDiagnostic(e.message, "Incompatible netplay peer")
        LOG.info("Player {} compatibility error: {}", handle.player + 1, message)
        lifecyclePublisher(
            ConnectionController.ServerProtocolErrorEvent(
                handle.player,
                message,
                attemptId,
            ))
      }
    } catch (e: IOException) {
      if (!doStop) {
        LOG.info(
            "Player {} disconnected ({})",
            handle.player + 1,
            netplaySocketFailureSummary(e),
        )
      }
    } finally {
      handle.socket.close()
      onDisconnected(handle)
    }
  }

  private fun onDisconnected(handle: ClientHandle) {
    if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
      val removed =
          synchronized(lock) {
            if (!clients.remove(handle.player, handle)) {
              false
            } else {
              // Queue removal before the slot becomes visible to accept(), so a fast replacement
              // cannot be attached and then removed by this stale disconnect.
              if (!doStop) {
                lifecyclePublisher(
                    ConnectionController.ServerPlayerDisconnectedEvent(handle.player, attemptId))
              }
              true
            }
          }
      if (!removed) {
        return
      }
      lifecyclePublisher(
          ConnectionController.ServerPlayerCountEvent(
              clients.size,
              mode.playerCount - 1,
              mode,
              attemptId,
          ))
      return
    }
    if (!clients.remove(handle.player, handle)) return
    val endSession =
        synchronized(lock) {
          if (!sessionStarted) false
          else {
            sessionStarted = false
            true
          }
        }
    if (endSession) {
      // Rollback state is shared by all players. Once any member leaves, end the group rather than
      // silently reassigning a physical DMG-07 port in the middle of a game.
      clients.values.forEach {
        it.connection.stop()
        it.socket.close()
      }
      lifecyclePublisher(ConnectionController.ServerLostConnectionEvent(attemptId))
    }
    lifecyclePublisher(
        ConnectionController.ServerPlayerCountEvent(
            clients.size,
            mode.playerCount - 1,
            mode,
            attemptId,
        ))
  }

  fun stop() {
    doStop = true
    serverSocket?.close()
    stopClients()
    handshakeExecutor.shutdownNow()
  }

  private fun stopClients() {
    pendingSockets.forEach(Socket::close)
    pendingConnections.forEach { socket, connection -> closeConnection(connection, socket) }
    pendingSockets.clear()
    pendingConnections.clear()
    clients.values.forEach { closeConnection(it.connection, it.socket) }
    clients.clear()
    synchronized(lock) { pendingPlayers.clear() }
  }

  internal fun pendingHandshakeCount(): Int = pendingSockets.size

  internal fun pendingConnectionCount(): Int = pendingConnections.size

  internal fun handshakeWorkerCount(): Int = handshakeExecutor.poolSize

  private fun closeConnection(connection: Connection, socket: Socket) {
    var socketFailure: IOException? = null
    try {
      // Closing the raw socket first cancels an in-flight writer before Connection waits for its
      // bounded writer thread. A peer that stopped reading cannot deadlock server shutdown.
      socket.close()
    } catch (e: IOException) {
      socketFailure = e
      LOG.debug("Error closing netplay socket ({})", netplaySocketFailureSummary(e))
    }
    try {
      connection.close()
    } catch (e: IOException) {
      socketFailure?.let(e::addSuppressed)
      LOG.debug("Error closing netplay connection ({})", netplaySocketFailureSummary(e))
    }
  }

  private data class ClientHandle(
      val player: Int,
      val socket: Socket,
      val connection: Connection,
  )

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpServer::class.java)
    const val PORT: Int = 6688
    private const val HANDSHAKE_TIMEOUT_MILLIS = 2_000
    private const val REDACTED_PEER = "<redacted>"
  }
}
