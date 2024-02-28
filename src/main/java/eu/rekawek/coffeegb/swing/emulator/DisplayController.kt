package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.swing.io.SwingDisplay

class DisplayController(private val display: SwingDisplay) {
    var scale: Int
        get() = display.scale
        set(value) {
            display.scale = value
        }
    var grayscale: Boolean
        get() = display.isGrayscale
        set(value) {
            display.isGrayscale = value
        }
}