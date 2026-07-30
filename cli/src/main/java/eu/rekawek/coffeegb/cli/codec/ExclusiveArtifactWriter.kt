package eu.rekawek.coffeegb.cli.codec

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Writes a forced, same-directory temporary file and publishes it without replacing a target. */
object ExclusiveArtifactWriter {
  fun write(target: Path, bytes: ByteArray): Path {
    require(bytes.isNotEmpty()) { "A new artifact must not be empty" }
    val normalized = target.toAbsolutePath().normalize()
    val parent = normalized.parent
        ?: throw IOException("Artifact destination must have a parent")
    val fileName = normalized.fileName
        ?: throw IOException("Artifact destination must have a file name")

    refuseUnsafeParent(parent)
    Files.createDirectories(parent)
    refuseUnsafeParent(parent)
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw FileAlreadyExistsException(fileName.toString())
    }

    val temporary = Files.createTempFile(parent, ".${safePrefix(fileName.toString())}-", ".part")
    var moved = false
    try {
      FileChannel.open(
              temporary,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING,
              LinkOption.NOFOLLOW_LINKS,
          )
          .use { channel ->
            val source = ByteBuffer.wrap(bytes)
            var zeroWrites = 0
            while (source.hasRemaining()) {
              val count = channel.write(source)
              if (count < 0) throw IOException("Artifact channel ended during write")
              if (count == 0 && ++zeroWrites > MAX_ZERO_WRITES) {
                throw IOException("Artifact write made no progress")
              }
              if (count > 0) zeroWrites = 0
            }
            channel.force(true)
          }
      // ATOMIC_MOVE has implementation-specific replacement semantics. The ordinary move has
      // the no-REPLACE_EXISTING collision contract needed by CLI artifacts.
      Files.move(temporary, normalized)
      moved = true
      forceDirectoryBestEffort(parent)
      return normalized
    } finally {
      if (!moved &&
          !Files.isSymbolicLink(temporary) &&
          Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(temporary)
      }
    }
  }

  private fun refuseUnsafeParent(parent: Path) {
    var cursor = parent.root
    for (component in parent) {
      cursor = if (cursor == null) component else cursor.resolve(component)
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
          (Files.isSymbolicLink(cursor) ||
              !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
        throw IOException("Artifact path component is not a safe directory: ${cursor.fileName}")
      }
    }
  }

  private fun safePrefix(fileName: String): String {
    val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
    return safe.padEnd(3, '_')
  }

  private fun forceDirectoryBestEffort(parent: Path) {
    try {
      FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
      // Windows and some providers cannot open directories; file bytes were forced before move.
    } catch (_: UnsupportedOperationException) {
      // Same durability fallback.
    }
  }

  private const val MAX_ZERO_WRITES = 1024
}
