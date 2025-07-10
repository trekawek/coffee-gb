package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.events.register
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SnapshotManager(eventBus: EventBus) {

  private var currentRom: File? = null

  init {
    eventBus.register<SwingEmulator.StartEmulationEvent> { this.currentRom = it.rom }
  }

  fun snapshotAvailable(slot: Int) = getSnapshotFile(slot)?.exists() ?: false

  fun saveSnapshot(slot: Int, gameboy: Gameboy) {
    val originalPauseState = gameboy.isPaused
    if (!gameboy.isPaused) {
      gameboy.pause()
    }
    val snapshotFile = getSnapshotFile(slot) ?: return
    ObjectOutputStream(FileOutputStream(snapshotFile)).use { it.writeObject(gameboy) }
    if (!originalPauseState) {
      gameboy.resume()
    }
  }

  fun loadSnapshot(slot: Int): Gameboy? {
    if (currentRom == null) {
      return null
    }
    val snapshotFile = getSnapshotFile(slot) ?: return null
    if (!snapshotFile.exists()) {
      return null
    }
    return ObjectInputStream(FileInputStream(snapshotFile)).use { it.readObject() as Gameboy }
  }

  private fun getSnapshotFile(slot: Int): File? {
    val rom = currentRom ?: return null
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }
}
