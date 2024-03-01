package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.swing.io.serial.*

class SerialController(private val serialEndpointWrapper: SerialEndpointWrapper) {

    private var client: SerialTcpClient? = null
    private var server: SerialTcpServer? = null
    private val serverListeners = mutableListOf<ServerEventListener>()
    private val clientListeners = mutableListOf<ClientEventListener>()

    fun startServer() {
        stop()
        server = SerialTcpServer(serialEndpointWrapper)
        serverListeners.forEach { server!!.registerListener(it) }
        Thread(server).start()
    }

    fun startClient(host: String) {
        stop()
        client = SerialTcpClient(host, serialEndpointWrapper)
        clientListeners.forEach { client!!.registerListener(it) }
        Thread(client).start()
    }

    fun stop() {
        client?.stop()
        client = null

        server?.stop()
        server = null
    }

    fun registerServerListener(listener: ServerEventListener) {
        serverListeners.add(listener)
        server?.registerListener(listener)
    }

    fun registerClientListener(listener: ClientEventListener) {
        clientListeners.add(listener)
        client?.registerListener(listener)
    }
}
