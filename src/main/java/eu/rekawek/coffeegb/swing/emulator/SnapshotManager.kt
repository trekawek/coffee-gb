package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SnapshotManager(private val rom: File) {

  fun snapshotAvailable(slot: Int) = getSnapshotFile(slot).exists()

  fun saveSnapshot(slot: Int, gameboy: Gameboy) {
    val originalPauseState = gameboy.isPaused
    if (!gameboy.isPaused) {
      gameboy.pause()
    }
    val snapshotFile = getSnapshotFile(slot)
    ObjectOutputStream(FileOutputStream(snapshotFile)).use { it.writeObject(gameboy) }
    if (!originalPauseState) {
      gameboy.resume()
    }
  }

  fun loadSnapshot(slot: Int): Gameboy? {
    val snapshotFile = getSnapshotFile(slot)
    if (!snapshotFile.exists()) {
      return null
    }
    return ObjectInputStream(FileInputStream(snapshotFile)).use { it.readObject() as Gameboy }
  }

  private fun getSnapshotFile(slot: Int): File {
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }
}
