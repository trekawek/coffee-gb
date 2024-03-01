package eu.rekawek.coffeegb.swing.io.serial

interface ClientEventListener {
    fun onConnectedToServer() {}
    fun onDisconnectedFromServer() {}
}
