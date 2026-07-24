package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SnapshotManager(private val rom: File) {

  fun snapshotAvailable(slot: Int) = getSnapshotFile(slot).exists()

  fun saveSnapshot(slot: Int, gameboy: Gameboy) {
    val snapshotFile = getSnapshotFile(slot)
    gameboy.saveToMemento().serialize().let { snapshotFile.writeBytes(it) }
  }

  fun loadSnapshot(slot: Int, gameboy: Gameboy): Boolean {
    val snapshotFile = getSnapshotFile(slot)
    if (!snapshotFile.exists()) {
      return false
    }

    if (snapshotFile.length() > StateLimits.GAME_SNAPSHOT.decodedBytes) {
      throw IOException(
          "Snapshot exceeds the ${StateLimits.GAME_SNAPSHOT.decodedBytes}-byte legacy limit")
    }
    val memento = readSnapshotBytes(snapshotFile).deserializeToGameboyMemento()
    gameboy.restoreFromMemento(memento)
    return true
  }

  private fun getSnapshotFile(slot: Int): File {
    val parentDir = rom.parentFile
    val name = rom.nameWithoutExtension + ".sn${slot}"
    return parentDir.resolve(name)
  }

  private fun readSnapshotBytes(snapshotFile: File): ByteArray {
    val limit = StateLimits.GAME_SNAPSHOT.decodedBytes
    val initialSize = snapshotFile.length().coerceAtMost(limit.toLong()).toInt()
    val output = ByteArrayOutputStream(initialSize)
    Files.newInputStream(snapshotFile.toPath()).use { input ->
      val buffer = ByteArray(8192)
      var total = 0
      while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        if (count > limit - total) {
          throw IOException("Snapshot exceeds the $limit-byte legacy limit")
        }
        output.write(buffer, 0, count)
        total += count
      }
    }
    return output.toByteArray()
  }
}

fun Memento<Gameboy>.serialize(): ByteArray {
  return LegacyMementoCodec.serializeGameboy(this)
}

fun ByteArray.deserializeToGameboyMemento(): Memento<Gameboy> {
  return LegacyMementoCodec.deserializeGameboy(this)
}

fun Memento<Session>.serializeSessionMemento(): ByteArray {
  return LegacyMementoCodec.serializeSession(this)
}

fun ByteArray.deserializeToSessionMemento(): Memento<Session> {
  return LegacyMementoCodec.deserializeSession(this)
}
