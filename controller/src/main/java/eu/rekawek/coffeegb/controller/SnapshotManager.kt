package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SnapshotManager(private val rom: File) {

  fun snapshotAvailable(slot: Int) = getSnapshotFile(slot).exists()

  fun saveSnapshot(slot: Int, gameboy: Gameboy) {
    val snapshotFile = getSnapshotFile(slot)
    gameboy.saveToMemento().serialize().let { snapshotFile.writeBytes(it) }
  }

  fun loadSnapshot(slot: Int, gameboy: Gameboy) {
    val snapshotFile = getSnapshotFile(slot)
    if (!snapshotFile.exists()) {
      return
    }

    val memento = snapshotFile.readBytes().deserializeToGameboyMemento()
    gameboy.restoreFromMemento(memento)
  }

  private fun getSnapshotFile(slot: Int): File {
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }
}

fun Memento<Gameboy>.serialize(): ByteArray {
  val baos = ByteArrayOutputStream()
  ObjectOutputStream(baos).use { it.writeObject(this) }
  return baos.toByteArray()
}

fun ByteArray.deserializeToGameboyMemento(): Memento<Gameboy> {
  return ObjectInputStream(inputStream()).use { it.readObject() as Memento<Gameboy> }
}
