package eu.rekawek.coffeegb.controller.state

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ExclusiveWriteRecovery(
    val staleTemporaryFilesRemoved: Int,
) {
  companion object {
    val NONE = ExclusiveWriteRecovery(0)
  }
}

internal data class ExclusiveWriteResult(
    val path: Path,
    val recovery: ExclusiveWriteRecovery,
)

/**
 * Writes a new file without ever replacing an existing destination.
 *
 * Bytes are forced in a same-directory temporary file before a no-replace move. A collision is
 * reported to the caller, which may choose another deterministic name.
 */
internal object ExclusiveFileWriter {
  fun write(target: Path, bytes: ByteArray): ExclusiveWriteResult {
    val normalized = normalizedTarget(target)
    return targetLock(normalized).withLock {
      val recovery = cleanupStaleTemporaryFiles(normalized)
      writePrepared(normalized, bytes, recovery)
    }
  }

  private fun writePrepared(
      normalized: Path,
      bytes: ByteArray,
      recovery: ExclusiveWriteRecovery,
  ): ExclusiveWriteResult {
    require(bytes.isNotEmpty()) { "A new artifact must not be empty" }
    val parent = normalized.parent
        ?: throw IOException("Artifact destination must have a parent")
    if (normalized.fileName == null) {
      throw IOException("Artifact destination must have a file name")
    }
    // Validate existing ancestors before creating anything, then validate again so an existing
    // symlink cannot make directory creation escape the selected root.
    refuseUnsafeParent(parent)
    Files.createDirectories(parent)
    refuseUnsafeParent(parent)
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw FileAlreadyExistsException(normalized.toString())
    }

    val temporary = Files.createTempFile(parent, temporaryPrefix(normalized), TEMP_SUFFIX)
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
              if (count == 0) {
                if (++zeroWrites > 1024) throw IOException("Artifact write made no progress")
              } else {
                zeroWrites = 0
              }
            }
            channel.force(true)
          }
      // Do not request ATOMIC_MOVE here: its specification makes target replacement
      // implementation-specific. The ordinary no-REPLACE move has the collision contract that
      // exports and screenshots require, while the fully written temporary remains recoverable.
      Files.move(temporary, normalized)
      moved = true
      forceDirectoryBestEffort(parent)
      return ExclusiveWriteResult(normalized, recovery)
    } finally {
      if (!moved && Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS) &&
          !Files.isSymbolicLink(temporary)) {
        Files.deleteIfExists(temporary)
      }
    }
  }

  fun collisionSafe(
      directory: Path,
      stem: String,
      extension: String,
      bytes: ByteArray,
      maximumAttempts: Int = DEFAULT_COLLISION_ATTEMPTS,
  ): ExclusiveWriteResult {
    require(stem.matches(SAFE_STEM)) { "Artifact stem is not a safe bounded filename" }
    require(extension.matches(SAFE_EXTENSION)) { "Artifact extension is invalid" }
    require(maximumAttempts in 1..MAX_COLLISION_ATTEMPTS)
    var removed = 0
    repeat(maximumAttempts) { collision ->
      val suffix = if (collision == 0) "" else "-$collision"
      val candidate = directory.resolve("$stem$suffix.$extension")
      val normalized = normalizedTarget(candidate)
      try {
        return targetLock(normalized).withLock {
          val recovery = cleanupStaleTemporaryFiles(normalized)
          removed += recovery.staleTemporaryFilesRemoved
          writePrepared(normalized, bytes, ExclusiveWriteRecovery(removed))
        }
      } catch (_: FileAlreadyExistsException) {
        // Deterministically advance to the next suffix.
      }
    }
    throw IOException("No free artifact name is available after $maximumAttempts attempts")
  }

  internal fun temporaryPrefix(target: Path): String {
    val fileName = normalizedTarget(target).fileName.toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(fileName.toByteArray(Charsets.UTF_8))
    val id = digest.take(TEMP_ID_BYTES).joinToString("") { "%02x".format(it) }
    return "$TEMP_PREFIX$id-"
  }

  private fun normalizedTarget(target: Path): Path =
      target.toAbsolutePath().normalize().also {
        if (it.fileName == null || it.parent == null) {
          throw IOException("Artifact destination must have a file name and parent")
        }
      }

  private fun cleanupStaleTemporaryFiles(target: Path): ExclusiveWriteRecovery {
    val parent = requireNotNull(target.parent)
    refuseUnsafeParent(parent)
    Files.createDirectories(parent)
    refuseUnsafeParent(parent)
    val prefix = temporaryPrefix(target)
    val candidates = ArrayList<Path>()
    var removed = 0
    Files.newDirectoryStream(parent) { child ->
      val name = child.fileName.toString()
      name.startsWith(prefix) && name.endsWith(TEMP_SUFFIX)
    }.use { stream ->
      stream.forEach { candidate ->
        if (candidates.size == MAX_STALE_TEMPORARY_FILES) {
          throw IOException(
              "Too many stale temporary artifacts for destination ${target.fileName}")
        }
        candidates.add(candidate)
      }
    }
    candidates.forEach { candidate ->
      if (!Files.isSymbolicLink(candidate) &&
          Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
          Files.deleteIfExists(candidate)) {
        removed++
      }
    }
    return ExclusiveWriteRecovery(removed)
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

  private fun forceDirectoryBestEffort(parent: Path) {
    try {
      FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
      // Windows and some providers cannot open directories. File bytes were forced before move.
    } catch (_: UnsupportedOperationException) {
      // Same durability fallback as AtomicFileWriter.
    }
  }

  private fun targetLock(target: Path): ReentrantLock =
      TARGET_LOCKS[(target.hashCode() and Int.MAX_VALUE) % TARGET_LOCKS.size]

  private const val TEMP_PREFIX = ".coffeegb-new-"
  private const val TEMP_SUFFIX = ".part"
  private const val TEMP_ID_BYTES = 8
  internal const val MAX_STALE_TEMPORARY_FILES = 32
  private const val DEFAULT_COLLISION_ATTEMPTS = 1000
  private const val MAX_COLLISION_ATTEMPTS = 10_000
  private const val LOCK_STRIPES = 64
  private val TARGET_LOCKS = Array(LOCK_STRIPES) { ReentrantLock() }
  private val SAFE_STEM = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
  private val SAFE_EXTENSION = Regex("[a-z0-9]{1,16}")
}
