package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.battery.Battery
import eu.rekawek.coffeegb.memory.cart.battery.MemoryBattery
import java.io.File

object CartridgeUtils {

  fun createCartridge(file: File): Cartridge {
    return Cartridge(file)
  }

  fun createCartridge(rom: ByteArray, battery: ByteArray?): Cartridge {
    if (battery == null) {
      return Cartridge(rom, Battery.NULL_BATTERY, Cartridge.GameboyType.AUTOMATIC, false)
    } else {
      return Cartridge(rom, MemoryBattery(battery), Cartridge.GameboyType.AUTOMATIC, false)
    }
  }
}
