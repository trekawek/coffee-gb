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

  private val lock = Any()

  @Volatile private var sessionStarted = false

  override fun run() {
    doStop = false
    ServerSocket(port).use { listener ->
      serverSocket = listener
      listener.soTimeout = 100
      eventBus.post(ConnectionController.ServerStartedEvent(mode))
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
    TcpClient.configure(socket)
    val player = synchronized(lock) { firstAvailablePlayer() }
    if (player == null) {
      LOG.info("Rejecting extra connection from {}: {} session is full", socket.inetAddress, mode)
      try {
        Connection.reject(socket.getOutputStream(), Connection.RejectionReason.SERVER_FULL)
      } finally {
        socket.close()
      }
      return
    }

    try {
      val connection =
          Connection(socket.getInputStream(), socket.getOutputStream(), eventBus, true, mode, player)
      val handle = ClientHandle(player, socket, connection)
      clients[player] = handle
      LOG.info("Player {} connected from {}", player + 1, socket.inetAddress.hostAddress)
      eventBus.post(
          ConnectionController.ServerPlayerCountEvent(clients.size, mode.playerCount - 1, mode))
      Thread({ runClient(handle) }, "netplay-player-${player + 1}").start()
      startSessionIfFull(socket.inetAddress.hostAddress)
    } catch (e: IOException) {
      socket.close()
      throw e
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
    } catch (e: IOException) {
      if (!doStop) LOG.info("Player {} disconnected: {}", handle.player + 1, e.message)
    } finally {
      handle.socket.close()
      onDisconnected(handle)
    }
  }

  private fun onDisconnected(handle: ClientHandle) {
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
    clients.values.forEach {
      it.connection.stop()
      it.socket.close()
    }
    clients.clear()
  }

  private data class ClientHandle(
      val player: Int,
      val socket: Socket,
      val connection: Connection,
  )

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpServer::class.java)
    const val PORT: Int = 6688
  }
}
