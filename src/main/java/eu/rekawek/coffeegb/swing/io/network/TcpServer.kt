package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.events.EventBus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.Volatile

class TcpServer(private val eventBus: EventBus) : Runnable {
  @Volatile private var doStop = false
  private var connection: Connection? = null

  override fun run() {
    doStop = false
    ServerSocket(PORT).use { serverSocket ->
      serverSocket.soTimeout = 100

      eventBus.post(ConnectionController.ServerStartedEvent())
      while (!doStop) {
        var socket: Socket
        try {
          socket = serverSocket.accept()
          LOG.info("Got new connection: {}", socket.inetAddress)
          eventBus.post(ConnectionController.ServerGotConnectionEvent(socket.inetAddress.hostName))
          connection = Connection(socket.getInputStream(), socket.getOutputStream(), eventBus)
          connection!!.run()
        } catch (e: SocketTimeoutException) {
          // do nothing
        } catch (e: IOException) {
          LOG.error("Error in accepting connection", e)
        }
        eventBus.post(ConnectionController.ServerLostConnectionEvent())
      }
    }
    eventBus.post(ConnectionController.ServerStoppedEvent())
  }

  fun stop() {
    doStop = true
    if (connection != null) {
      connection!!.stop()
    }
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpServer::class.java)
    const val PORT: Int = 6688
  }
}
