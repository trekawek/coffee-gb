package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.controller.events.register

class ConnectionController(private val eventBus: EventBus) {

  private var client: TcpClient? = null
  private var server: TcpServer? = null

  init {
    eventBus.register<StartServerEvent> { startServer() }
    eventBus.register<StopServerEvent> { stopServer() }
    eventBus.register<StartClientEvent> { startClient(it.host) }
    eventBus.register<StopClientEvent> { stopClient() }
  }

  private fun startServer() {
    stopClient()
    stopServer()
    server = TcpServer(eventBus)
    Thread(server).start()
  }

  private fun startClient(host: String) {
    stopClient()
    stopServer()
    client = TcpClient(host, eventBus)
    Thread(client).start()
  }

  private fun stopClient() {
    client?.stop()
    client = null
  }

  private fun stopServer() {
    server?.stop()
    server = null
  }

  class StartServerEvent : Event

  class StopServerEvent : Event

  data class StartClientEvent(val host: String) : Event

  class StopClientEvent : Event

  class ServerStartedEvent : Event

  class ServerStoppedEvent : Event

  data class ServerGotConnectionEvent(val host: String) : Event

  class ServerLostConnectionEvent : Event

  class ClientConnectedToServerEvent : Event

  class ClientDisconnectedFromServerEvent : Event
}
