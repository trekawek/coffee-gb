package eu.rekawek.coffeegb.controller.mobile.config

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stable, presentation-safe storage failures. No exception text or path is exposed. */
enum class MobileAdapterConfigurationError(
    val code: String,
    val userMessage: String,
) {
  MALFORMED_FILE(
      "MALFORMED_FILE",
      "The saved Mobile Adapter configuration has an invalid format.",
  ),
  UNSUPPORTED_VERSION(
      "UNSUPPORTED_VERSION",
      "The saved Mobile Adapter configuration uses an unsupported version.",
  ),
  INTEGRITY_CHECK_FAILED(
      "INTEGRITY_CHECK_FAILED",
      "The saved Mobile Adapter configuration failed its integrity check.",
  ),
  NON_REGULAR_FILE(
      "NON_REGULAR_FILE",
      "The Mobile Adapter configuration is not stored in a regular file.",
  ),
  STORAGE_READ_FAILED(
      "STORAGE_READ_FAILED",
      "The saved Mobile Adapter configuration could not be read.",
  ),
  STORAGE_WRITE_FAILED(
      "STORAGE_WRITE_FAILED",
      "The Mobile Adapter configuration could not be saved.",
  ),
  PERMISSION_HARDENING_FAILED(
      "PERMISSION_HARDENING_FAILED",
      "Private permissions could not be applied to the Mobile Adapter configuration.",
  ),
}

enum class MobileAdapterConfigurationSource {
  PERSISTED,
  RECOVERED_BACKUP,
  LAST_GOOD,
  SYNTHETIC_FALLBACK,
}

data class MobileAdapterConfigurationLoadResult(
    val configuration: MobileAdapterConfiguration,
    val source: MobileAdapterConfigurationSource,
    val error: MobileAdapterConfigurationError? = null,
    val recoveryPerformed: Boolean = false,
) {
  init {
    require(source != MobileAdapterConfigurationSource.LAST_GOOD || error != null) {
      "A last-good result must explain why persisted data was rejected"
    }
    require(source != MobileAdapterConfigurationSource.RECOVERED_BACKUP || recoveryPerformed) {
      "A recovered-backup result must report recovery"
    }
  }
}

data class MobileAdapterConfigurationSaveResult(
    val saved: Boolean,
    val error: MobileAdapterConfigurationError? = null,
) {
  init {
    require(saved == (error == null)) { "Save success and typed failure must be exclusive" }
  }
}

/**
 * Blocking durable storage for Mobile Adapter configuration.
 *
 * Callers own scheduling and must invoke [load] and [save] away from the emulator and Swing EDT.
 * Every operation is serialized with the in-memory last-good value. Writes use
 * [AtomicFileWriter], then restrict the committed file to owner read/write on POSIX filesystems.
 */
class MobileAdapterConfigurationStore(
    target: Path,
    private val persistence: AtomicFileWriter = AtomicFileWriter.system(),
) {
  val path: Path = target.toAbsolutePath().normalize()

  private val operationLock = ReentrantLock()
  private var lastGood = MobileAdapterConfiguration.syntheticFallback()
  private var hasPersistedLastGood = false

  init {
    require(path.fileName != null && path.parent != null) {
      "Mobile Adapter configuration path must have a file name and parent directory"
    }
  }

  fun current(): MobileAdapterConfiguration = operationLock.withLock { lastGood }

  /**
   * Loads one exact-size record. Missing storage uses the deterministic synthetic configuration;
   * invalid storage uses either the last accepted value or that same fallback.
   */
  fun load(): MobileAdapterConfigurationLoadResult =
      operationLock.withLock {
        try {
          val read =
              persistence.readWithRecovery(path) { candidate ->
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                  null
                } else {
                  readAndHarden(candidate)
                }
              }
          val configuration = read.value()
          if (configuration == null) {
            return@withLock missingResult()
          }
          lastGood = configuration
          hasPersistedLastGood = true
          val recovery = read.recovery()
          MobileAdapterConfigurationLoadResult(
              configuration = configuration,
              source =
                  if (recovery.backupRestored()) {
                    MobileAdapterConfigurationSource.RECOVERED_BACKUP
                  } else {
                    MobileAdapterConfigurationSource.PERSISTED
                  },
              recoveryPerformed = recovery.recoveredAnything(),
          )
        } catch (failure: MobileAdapterConfigurationFormatException) {
          failedLoad(failure.error)
        } catch (failure: MobileAdapterConfigurationStoreException) {
          failedLoad(failure.error)
        } catch (failure: IOException) {
          failedLoad(MobileAdapterConfigurationError.STORAGE_READ_FAILED)
        } catch (failure: SecurityException) {
          failedLoad(MobileAdapterConfigurationError.STORAGE_READ_FAILED)
        }
      }

  /** Commits a fully validated immutable configuration through crash-recoverable replacement. */
  fun save(configuration: MobileAdapterConfiguration): MobileAdapterConfigurationSaveResult =
      operationLock.withLock {
        try {
          refuseNonRegularTarget()
          val encoded = MobileAdapterConfigurationCodec.encode(configuration)
          // The temporary inode is owner-only before AtomicFileWriter can enter either rename
          // path. A permission failure therefore leaves the prior target and last-good value
          // untouched instead of committing private bytes and reporting a split result.
          persistence.writeOwnerOnly(path, encoded)
          lastGood = configuration
          hasPersistedLastGood = true
          MobileAdapterConfigurationSaveResult(saved = true)
        } catch (failure: AtomicFileWriter.OwnerOnlyPermissionsException) {
          MobileAdapterConfigurationSaveResult(
              saved = false,
              error = MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED,
          )
        } catch (failure: MobileAdapterConfigurationStoreException) {
          MobileAdapterConfigurationSaveResult(saved = false, error = failure.error)
        } catch (failure: IOException) {
          // A post-rename failure remains a conservative caller-visible failure. The in-memory
          // last-good value is retained and an explicit retry completes cleanup/verification.
          MobileAdapterConfigurationSaveResult(
              saved = false,
              error = MobileAdapterConfigurationError.STORAGE_WRITE_FAILED,
          )
        } catch (failure: SecurityException) {
          MobileAdapterConfigurationSaveResult(
              saved = false,
              error = MobileAdapterConfigurationError.STORAGE_WRITE_FAILED,
          )
        }
      }

  private fun readAndHarden(candidate: Path): MobileAdapterConfiguration {
    if (Files.isSymbolicLink(candidate) ||
        !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw MobileAdapterConfigurationStoreException(
          MobileAdapterConfigurationError.NON_REGULAR_FILE)
    }
    // Restrict an existing record before inspecting any of its contents. A malformed or
    // unsupported record can still contain private dial-up/account material, so decode failure
    // must not leave broadly readable permissions behind.
    hardenPermissions(candidate)
    val encoded = ByteArray(MobileAdapterConfigurationCodec.ENCODED_SIZE)
    FileChannel.open(candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
      if (channel.size() != encoded.size.toLong()) {
        throw MobileAdapterConfigurationFormatException(
            MobileAdapterConfigurationError.MALFORMED_FILE)
      }
      val output = ByteBuffer.wrap(encoded)
      var zeroReads = 0
      while (output.hasRemaining()) {
        val count = channel.read(output)
        if (count < 0) {
          throw MobileAdapterConfigurationFormatException(
              MobileAdapterConfigurationError.MALFORMED_FILE)
        }
        if (count == 0) {
          if (++zeroReads > MAX_ZERO_READS) {
            throw IOException("Configuration channel made no read progress")
          }
        } else {
          zeroReads = 0
        }
      }
      val extra = ByteBuffer.allocate(1)
      if (channel.read(extra) >= 0) {
        throw MobileAdapterConfigurationFormatException(
            MobileAdapterConfigurationError.MALFORMED_FILE)
      }
    }
    return MobileAdapterConfigurationCodec.decode(encoded)
  }

  private fun refuseNonRegularTarget() {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
        (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
      throw MobileAdapterConfigurationStoreException(
          MobileAdapterConfigurationError.NON_REGULAR_FILE)
    }
  }

  private fun hardenPermissions(candidate: Path) {
    val view =
        try {
          Files.getFileAttributeView(
              candidate,
              PosixFileAttributeView::class.java,
              LinkOption.NOFOLLOW_LINKS,
          )
        } catch (unsupported: UnsupportedOperationException) {
          null
        }
    if (view == null) return
    try {
      view.setPermissions(PRIVATE_FILE_PERMISSIONS)
      if (view.readAttributes().permissions() != PRIVATE_FILE_PERMISSIONS) {
        throw IOException("Private file permissions were not retained")
      }
    } catch (unsupported: UnsupportedOperationException) {
      // Windows and non-POSIX providers have no portable owner-only permission representation.
    } catch (failure: IOException) {
      throw MobileAdapterConfigurationStoreException(
          MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED,
          failure,
      )
    } catch (failure: SecurityException) {
      throw MobileAdapterConfigurationStoreException(
          MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED,
          failure,
      )
    }
  }

  private fun missingResult(): MobileAdapterConfigurationLoadResult =
      if (hasPersistedLastGood) {
        MobileAdapterConfigurationLoadResult(
            configuration = lastGood,
            source = MobileAdapterConfigurationSource.LAST_GOOD,
            error = MobileAdapterConfigurationError.STORAGE_READ_FAILED,
        )
      } else {
        MobileAdapterConfigurationLoadResult(
            configuration = lastGood,
            source = MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK,
        )
      }

  private fun failedLoad(
      error: MobileAdapterConfigurationError
  ): MobileAdapterConfigurationLoadResult =
      MobileAdapterConfigurationLoadResult(
          configuration = lastGood,
          source =
              if (hasPersistedLastGood) {
                MobileAdapterConfigurationSource.LAST_GOOD
              } else {
                MobileAdapterConfigurationSource.SYNTHETIC_FALLBACK
              },
          error = error,
      )

  companion object {
    /** Default private adapter record, deliberately separate from general desktop preferences. */
    @JvmStatic
    fun defaultPath(): Path =
        Path.of(System.getProperty("user.home")).resolve(".coffeegb-mobile-adapter.bin")

    private const val MAX_ZERO_READS = 1_024
    private val PRIVATE_FILE_PERMISSIONS: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
  }
}

private class MobileAdapterConfigurationStoreException(
    val error: MobileAdapterConfigurationError,
    cause: Throwable? = null,
) : IOException(error.userMessage, cause)
