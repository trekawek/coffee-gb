package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.events.EventBus
import java.io.IOException
import java.net.Socket
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TcpClient(
    private val host: String,
    private val eventBus: EventBus,
) : Runnable {
  private var clientSocket: Socket? = null
  private var connection: Connection? = null

  override fun run() {
    try {
      clientSocket = Socket(host, TcpServer.PORT)
      LOG.info("Connected to {}", clientSocket!!.inetAddress)
      eventBus.post(ConnectionController.ClientConnectedToServerEvent())
      connection =
          Connection(false, clientSocket!!.getInputStream(), clientSocket!!.getOutputStream())
      connection!!.run()
    } catch (e: IOException) {
      LOG.error("Error in making connection", e)
    }
    eventBus.post(ConnectionController.ClientDisconnectedFromServerEvent())
  }

  fun stop() {
    if (connection != null) {
      connection!!.stop()
    }
    try {
      if (clientSocket != null) {
        clientSocket!!.close()
      }
    } catch (e: IOException) {
      LOG.error("Error in closing client socket", e)
    }
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpClient::class.java)
  }
}
