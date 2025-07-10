package eu.rekawek.coffeegb.swing.io.serial

interface ServerEventListener {
  fun onServerStarted() {}

  fun onServerStopped() {}

  fun onNewConnection(host: String?) {}

  fun onConnectionClosed() {}
}
