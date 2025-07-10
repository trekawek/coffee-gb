package eu.rekawek.coffeegb.swing.gui.properties

import eu.rekawek.coffeegb.memory.cart.Cartridge.GameboyType

class GameboyProperties(private val properties: EmulatorProperties) {
  val gameboyType
    get() =
        GameboyType.valueOf(
            properties.getProperty(EmulatorProperties.Key.GameboyType, GameboyType.AUTOMATIC.name))
}
