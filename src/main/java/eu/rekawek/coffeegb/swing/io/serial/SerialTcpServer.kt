package eu.rekawek.coffeegb.swing.io.serial

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.Volatile

class SerialTcpServer(private val serialEndpointWrapper: SerialEndpointWrapper) : Runnable {
    @Volatile
    private var doStop = false
    private var endpoint: StreamSerialEndpoint? = null
    private val listeners = CopyOnWriteArrayList<ServerEventListener>()

    override fun run() {
        doStop = false
        ServerSocket(PORT).use { serverSocket ->
            serverSocket.soTimeout = 100
            listeners.forEach { it.onServerStarted() }
            while (!doStop) {
                var socket: Socket
                try {
                    socket = serverSocket.accept()
                    LOG.info("Got new connection: {}", socket.inetAddress)
                    endpoint = StreamSerialEndpoint(
                        socket.getInputStream(),
                        socket.getOutputStream()
                    )
                    serialEndpointWrapper.setDelegate(endpoint)
                    listeners.forEach { it.onNewConnection(socket.inetAddress.hostName) }
                    endpoint!!.run()
                    listeners.forEach { it.onConnectionClosed() }
                } catch (e: SocketTimeoutException) {
                    // do nothing
                } catch (e: IOException) {
                    LOG.error("Error in accepting connection", e)
                }
            }
        }
        listeners.forEach { it.onServerStopped() }
    }

    fun stop() {
        serialEndpointWrapper.setDelegate(null)
        doStop = true
        if (endpoint != null) {
            endpoint!!.stop()
        }
    }

    fun registerListener(listener: ServerEventListener) {
        listeners.add(listener)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(SerialTcpServer::class.java)
        const val PORT: Int = 6688
    }
}
