package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode

class ConnectionController(private val eventBus: EventBus) {

  private var client: TcpClient? = null
  private var server: TcpServer? = null

  init {
    eventBus.register<StartServerEvent> { startServer(it.mode) }
    eventBus.register<StopServerEvent> { stopServer() }
    eventBus.register<StartClientEvent> { startClient(it.host) }
    eventBus.register<StopClientEvent> { stopClient() }
  }

  private fun startServer(mode: LinkMode) {
    stopClient()
    stopServer()
    server = TcpServer(eventBus, mode = mode)
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

  data class StartServerEvent(val mode: LinkMode = LinkMode.NORMAL) : Event

  class StopServerEvent : Event

  data class StartClientEvent(val host: String) : Event

  class StopClientEvent : Event

  data class ServerStartedEvent(val mode: LinkMode = LinkMode.NORMAL) : Event

  class ServerStoppedEvent : Event

  data class ServerGotConnectionEvent(
      val host: String,
      val mode: LinkMode = LinkMode.NORMAL,
      val player: Int = 0,
  ) : Event

  class ServerLostConnectionEvent : Event

  data class ClientHandshakeCompletedEvent(val mode: LinkMode, val player: Int) : Event

  data class ClientConnectionRejectedEvent(val message: String) : Event

  data class ClientConnectedToServerEvent(
      val mode: LinkMode = LinkMode.NORMAL,
      val player: Int = 1,
  ) : Event

  data class ServerPlayerCountEvent(
      val connected: Int,
      val required: Int,
      val mode: LinkMode,
  ) : Event

  class ClientDisconnectedFromServerEvent : Event
}
