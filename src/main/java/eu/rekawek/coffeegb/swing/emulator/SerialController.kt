package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.swing.io.serial.SerialEndpointWrapper
import eu.rekawek.coffeegb.swing.io.serial.SerialTcpClient
import eu.rekawek.coffeegb.swing.io.serial.SerialTcpServer

class SerialController(private val serialEndpointWrapper: SerialEndpointWrapper) {

    private var client: SerialTcpClient? = null

    private var server: SerialTcpServer? = null
    fun startServer() {
        stop()
        server = SerialTcpServer(serialEndpointWrapper)
        Thread(server).start()
    }

    fun startClient(host: String) {
        stop()
        client = SerialTcpClient(host, serialEndpointWrapper)
        Thread(client).start()
    }

    fun stop() {
        client?.stop()
        client = null

        server?.stop()
        server = null
    }

}
