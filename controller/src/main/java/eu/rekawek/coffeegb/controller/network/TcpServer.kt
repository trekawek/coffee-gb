package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.core.events.EventBus
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Accepts one normal-link client or three fixed-slot DMG-07 clients. */
class TcpServer(
    private val eventBus: EventBus,
    private val port: Int = PORT,
    private val mode: LinkMode = LinkMode.NORMAL,
) : Runnable {

  @Volatile private var doStop = false

  @Volatile private var serverSocket: ServerSocket? = null

  private val clients = ConcurrentHashMap<Int, ClientHandle>()

  private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()

  private val pendingConnections = ConcurrentHashMap<Socket, Connection>()

  private val lock = Any()

  @Volatile private var sessionStarted = false

  override fun run() {
    doStop = false
    ServerSocket(port).use { listener ->
      serverSocket = listener
      listener.soTimeout = 100
      eventBus.post(ConnectionController.ServerStartedEvent(mode))
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        // The adapter belongs to the host and is live even with no clients attached.
        eventBus.post(ConnectionController.ServerGotConnectionEvent("localhost", mode, 0))
      }
      while (!doStop) {
        try {
          accept(listener)
        } catch (_: SocketTimeoutException) {
          // Poll doStop.
        } catch (e: SocketException) {
          if (!doStop) LOG.error("Error accepting netplay connection", e)
        } catch (e: IOException) {
          if (!doStop) LOG.error("Error accepting netplay connection", e)
        }
      }
    }
    serverSocket = null
    stopClients()
    eventBus.post(ConnectionController.ServerStoppedEvent())
  }

  private fun accept(listener: ServerSocket) {
    val socket = listener.accept()
    pendingSockets += socket
    try {
      TcpClient.configure(socket)
      val player = synchronized(lock) { firstAvailablePlayer() }
      if (player == null) {
        LOG.info("Rejecting extra connection from {}: {} session is full", socket.inetAddress, mode)
        try {
          Connection.reject(socket.getOutputStream(), Connection.RejectionReason.SERVER_FULL)
        } finally {
          pendingSockets.remove(socket)
          socket.close()
        }
        return
      }
      val connection =
          Connection(socket.getInputStream(), socket.getOutputStream(), eventBus, true, mode, player)
      pendingConnections[socket] = connection
      Thread(
              { completeHandshake(socket, connection, player) },
              "netplay-handshake-${socket.port}",
          )
          .start()
    } catch (e: IOException) {
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
            if (doStop || clients.containsKey(player)) false
            else {
              clients[player] = handle
              true
            }
          }
      if (!claimed) return
      pendingConnections.remove(socket)
      pendingSockets.remove(socket)
      LOG.info("Player {} connected from {}", player + 1, socket.inetAddress.hostAddress)
      eventBus.post(
          ConnectionController.ServerPlayerCountEvent(clients.size, mode.playerCount - 1, mode))
      Thread({ runClient(handle) }, "netplay-player-${player + 1}").start()
      if (mode == LinkMode.FOUR_PLAYER_ADAPTER) {
        connection.startSession()
      } else {
        startSessionIfFull(socket.inetAddress.hostAddress)
      }
    } catch (e: Connection.CompatibilityException) {
      if (!doStop) {
        eventBus.post(
            ConnectionController.ServerProtocolErrorEvent(
                player,
                e.message ?: "Incompatible netplay peer",
            ))
      }
    } catch (e: SocketTimeoutException) {
      if (!doStop) LOG.info("Player {} capability handshake timed out", player + 1)
    } catch (e: IOException) {
      if (!doStop) LOG.info("Player {} capability handshake failed: {}", player + 1, e.message)
    } finally {
      pendingConnections.remove(socket)
      pendingSockets.remove(socket)
      if (!claimed) closeConnection(connection, socket)
    }
  }

  private fun firstAvailablePlayer(): Int? =
      (1 until mode.playerCount).firstOrNull { !clients.containsKey(it) }

  private fun startSessionIfFull(lastHost: String) {
    val toStart =
        synchronized(lock) {
          if (sessionStarted || clients.size != mode.playerCount - 1) return
          sessionStarted = true
          clients.values.sortedBy { it.player }
        }
    eventBus.post(ConnectionController.ServerGotConnectionEvent(lastHost, mode, 0))
    toStart.forEach { it.connection.startSession() }
  }

  private fun runClient(handle: ClientHandle) {
    try {
      handle.connection.use { it.run() }
    } catch (e: Connection.ProtocolException) {
      if (!doStop) {
        LOG.info("Player {} protocol error: {}", handle.player + 1, e.reason.userMessage)
        eventBus.post(
            ConnectionController.ServerProtocolErrorEvent(
                handle.player,
                e.reason.userMessage,
          ))
      }
    } catch (e: Connection.CompatibilityException) {
      if (!doStop) {
        LOG.info("Player {} compatibility error: {}", handle.player + 1, e.message)
        eventBus.post(
            ConnectionController.ServerProtocolErrorEvent(
                handle.player,
                e.message ?: "Incompatible netplay peer",
            ))
      }
    } catch (e: IOException) {
      if (!doStop) LOG.info("Player {} disconnected: {}", handle.player + 1, e.message)
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
                eventBus.post(ConnectionController.ServerPlayerDisconnectedEvent(handle.player))
              }
              true
            }
          }
      if (!removed) {
        return
      }
      eventBus.post(
          ConnectionController.ServerPlayerCountEvent(clients.size, mode.playerCount - 1, mode))
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
      eventBus.post(ConnectionController.ServerLostConnectionEvent())
    }
    eventBus.post(
        ConnectionController.ServerPlayerCountEvent(clients.size, mode.playerCount - 1, mode))
  }

  fun stop() {
    doStop = true
    serverSocket?.close()
    stopClients()
  }

  private fun stopClients() {
    pendingSockets.forEach(Socket::close)
    pendingConnections.forEach { socket, connection -> closeConnection(connection, socket) }
    pendingSockets.clear()
    pendingConnections.clear()
    clients.values.forEach { closeConnection(it.connection, it.socket) }
    clients.clear()
  }

  private fun closeConnection(connection: Connection, socket: Socket) {
    try {
      connection.close()
    } catch (e: IOException) {
      LOG.debug("Error closing netplay connection", e)
    } finally {
      try {
        socket.close()
      } catch (e: IOException) {
        LOG.debug("Error closing netplay socket", e)
      }
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
  }
}
