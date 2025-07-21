package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.events.EventBus
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TcpClient(
    private val host: String,
    private val eventBus: EventBus,
) : Runnable {
  private var clientSocket: Socket? = null

  override fun run() {
    try {
      clientSocket = Socket(host, TcpServer.PORT)
      LOG.info("Connected to {}", clientSocket!!.inetAddress)
      eventBus.post(ConnectionController.ClientConnectedToServerEvent())
      Connection(clientSocket!!.getInputStream(), clientSocket!!.getOutputStream(), eventBus).use {
        it.run()
      }
      LOG.info("Disconnected from {}", clientSocket!!.inetAddress)
    } catch (e: SocketException) {
      if (e.message == "Socket closed") {
        LOG.atInfo().log("Disconnected from server")
      } else {
        LOG.error("Error in making connection", e)
      }
    } catch (e: IOException) {
      LOG.error("Error in making connection", e)
    }
    eventBus.post(ConnectionController.ClientDisconnectedFromServerEvent())
  }

  fun stop() {
    try {
      clientSocket?.close()
    } catch (e: IOException) {
      LOG.error("Error in closing client socket", e)
    }
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpClient::class.java)
  }
}
