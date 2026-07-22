package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SnapshotManager(private val rom: File) {

  fun snapshotAvailable(slot: Int) = getSnapshotFile(slot).exists()

  fun saveSnapshot(slot: Int, gameboy: Gameboy, achievementProgress: ByteArray? = null) {
    val snapshotFile = getSnapshotFile(slot)
    val gameboyMemento = gameboy.saveToMemento()
    // Keep the legacy on-disk shape when no achievement runtime is active. This allows
    // snapshots created without RetroAchievements to remain readable by older releases.
    val snapshot: Any =
        if (achievementProgress == null) gameboyMemento
        else SnapshotEnvelope(gameboyMemento, achievementProgress)
    snapshot.serialize().let { snapshotFile.writeBytes(it) }
  }

  fun loadSnapshot(
      slot: Int,
      gameboy: Gameboy,
      restoreAchievementProgress: (ByteArray?) -> Unit = {},
  ): Boolean {
    val snapshotFile = getSnapshotFile(slot)
    if (!snapshotFile.exists()) {
      return false
    }

    val contents = snapshotFile.readBytes().deserializeSnapshot()
    gameboy.restoreFromMemento(contents.gameboy)
    restoreAchievementProgress(contents.achievementProgress)
    return true
  }

  private fun getSnapshotFile(slot: Int): File {
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }

}

private data class SnapshotEnvelope(
    val gameboy: Memento<Gameboy>,
    val achievementProgress: ByteArray?,
) : java.io.Serializable

private data class SnapshotContents(
    val gameboy: Memento<Gameboy>,
    val achievementProgress: ByteArray?,
)

fun Memento<Gameboy>.serialize(): ByteArray {
  val baos = ByteArrayOutputStream()
  ObjectOutputStream(baos).use { it.writeObject(this) }
  return baos.toByteArray()
}

private fun Any.serialize(): ByteArray {
  val baos = ByteArrayOutputStream()
  ObjectOutputStream(baos).use { it.writeObject(this) }
  return baos.toByteArray()
}

fun ByteArray.deserializeToGameboyMemento(): Memento<Gameboy> {
  return deserializeSnapshot().gameboy
}

private fun ByteArray.deserializeSnapshot(): SnapshotContents {
  val value = ObjectInputStream(inputStream()).use { it.readObject() }
  return when (value) {
    is SnapshotEnvelope -> SnapshotContents(value.gameboy, value.achievementProgress)
    is Memento<*> -> SnapshotContents(value as Memento<Gameboy>, null)
    else -> throw IllegalArgumentException("Unsupported snapshot format: ${value.javaClass.name}")
  }
}
