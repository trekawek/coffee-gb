package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
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
    ObjectOutputStream(FileOutputStream(snapshotFile)).use {
      it.writeObject(gameboy.saveToMemento())
    }
    if (!originalPauseState) {
      gameboy.resume()
    }
  }

  fun loadSnapshot(slot: Int, gameboy: Gameboy) {
    val snapshotFile = getSnapshotFile(slot)
    if (!snapshotFile.exists()) {
      return
    }

    val originalPauseState = gameboy.isPaused
    if (!gameboy.isPaused) {
      gameboy.pause()
    }
    val memento =
        ObjectInputStream(FileInputStream(snapshotFile)).use { it.readObject() as Memento<Gameboy> }
    gameboy.restoreFromMemento(memento)
    if (!originalPauseState) {
      gameboy.resume()
    }
  }

  private fun getSnapshotFile(slot: Int): File {
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }
}
