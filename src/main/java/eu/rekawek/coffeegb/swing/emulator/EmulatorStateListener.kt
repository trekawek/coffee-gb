package eu.rekawek.coffeegb.swing.emulator

interface EmulatorStateListener {
    fun onEmulationStart(cartTitle: String) {}
    fun onEmulationStop() {}
}
