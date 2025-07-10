package eu.rekawek.coffeegb.swing.io.serial

import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SerialTcpClient(
    private val host: String,
    private val serialEndpointWrapper: SerialEndpointWrapper
) : Runnable {
  private var clientSocket: Socket? = null
  private var endpoint: StreamSerialEndpoint? = null
  private val listeners = CopyOnWriteArrayList<ClientEventListener>()

  override fun run() {
    try {
      clientSocket = Socket(host, SerialTcpServer.PORT)
      LOG.info("Connected to {}", clientSocket!!.inetAddress)
      listeners.forEach { it.onConnectedToServer() }

      endpoint =
          StreamSerialEndpoint(clientSocket!!.getInputStream(), clientSocket!!.getOutputStream())
      serialEndpointWrapper.setDelegate(endpoint)
      endpoint!!.run()
    } catch (e: IOException) {
      LOG.error("Error in making connection", e)
    }
    listeners.forEach { it.onDisconnectedFromServer() }
  }

  fun stop() {
    serialEndpointWrapper.setDelegate(null)
    if (endpoint != null) {
      endpoint!!.stop()
    }
    try {
      if (clientSocket != null) {
        clientSocket!!.close()
      }
    } catch (e: IOException) {
      LOG.error("Error in closing client socket", e)
    }
  }

  fun registerListener(listener: ClientEventListener) {
    listeners.add(listener)
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(SerialTcpServer::class.java)
  }
}
