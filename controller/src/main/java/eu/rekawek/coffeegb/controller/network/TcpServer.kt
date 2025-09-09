package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.core.events.EventBus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.Volatile

class TcpServer(private val eventBus: EventBus) : Runnable {
  @Volatile private var doStop = false

  @Volatile private var socket: Socket? = null

  override fun run() {
    doStop = false
    ServerSocket(PORT).use { serverSocket ->
      serverSocket.soTimeout = 100

      eventBus.post(ConnectionController.ServerStartedEvent())
      while (!doStop) {
        try {
          val socket = serverSocket.accept()
          this.socket = socket
          LOG.info("Got new connection: {}", socket.inetAddress)
          eventBus.post(ConnectionController.ServerGotConnectionEvent(socket.inetAddress.hostName))
          try {
            Connection(socket.getInputStream(), socket.getOutputStream(), eventBus).use { it.run() }
          } finally {
            LOG.info("Client disconnected: {}", socket.inetAddress)
            eventBus.post(ConnectionController.ServerLostConnectionEvent())
          }
        } catch (_: SocketTimeoutException) {
          // do nothing
        } catch (e: SocketException) {
          if (e.message == "Socket closed") {
            LOG.info("Client disconnected, server closed")
          } else {
            LOG.error("Error in accepting connection", e)
          }
        } catch (e: IOException) {
          LOG.error("Error in accepting connection", e)
        } finally {
          socket = null
        }
      }
    }
    eventBus.post(ConnectionController.ServerStoppedEvent())
  }

  fun stop() {
    doStop = true
    socket?.close()
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpServer::class.java)
    const val PORT: Int = 6688
  }
}
