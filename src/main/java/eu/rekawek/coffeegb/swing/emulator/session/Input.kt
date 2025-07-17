package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.controller.Button

data class Input(val pressedButtons: List<Button>, val releasedButtons: List<Button>)
