package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

enum class ApplicationSettingsWarningKind {
  CORRUPT_FILE_RECOVERED,
  CORRUPT_FILE_PRESERVATION_FAILED,
  READ_FAILED,
  FUTURE_SCHEMA,
  MIGRATION_SAVE_FAILED,
}

data class ApplicationSettingsLoadWarning(
    val kind: ApplicationSettingsWarningKind,
    val message: String,
    val preservedFile: Path? = null,
)

/**
 * Injectable owner of the settings file. Updates are immutable in memory, coalesced on one daemon
 * writer, and committed through [AtomicFileWriter].
 */
class ApplicationSettingsStore(
    settingsPath: Path,
    private val persistence: AtomicFileWriter = AtomicFileWriter.system(),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
) : AutoCloseable {
  val path: Path = settingsPath.toAbsolutePath().normalize()

  private val lock = Any()
  private val writeLock = ReentrantLock()
  private val closeLock = ReentrantLock()
  private val warning = AtomicReference<ApplicationSettingsLoadWarning?>()
  private var document: ApplicationSettingsDocument
  private var readOnly = false
  private var dirty = false
  private var revision = 0L
  private var committedRevision = 0L
  private var closing = false
  private var closed = false
  private var executor: ScheduledExecutorService? = null
  private var scheduled: ScheduledFuture<*>? = null

  @Volatile private var lastWriteFailure: IOException? = null

  init {
    require(debounceMillis >= 0) { "Settings debounce must not be negative" }
    require(closeTimeoutMillis > 0) { "Settings close timeout must be positive" }
    require(path.fileName != null && path.parent != null) {
      "Settings path must have a file name and parent directory"
    }
    val loaded = load()
    document = loaded.document
    readOnly = loaded.readOnly
    loaded.warning?.let(warning::set)
    if (loaded.migrated && !readOnly) {
      try {
        writeDocument(document)
      } catch (failure: IOException) {
        lastWriteFailure = failure
        dirty = true
        committedRevision = -1
        warning.compareAndSet(
            null,
            ApplicationSettingsLoadWarning(
                ApplicationSettingsWarningKind.MIGRATION_SAVE_FAILED,
                "Legacy settings were loaded, but the schema migration could not be saved: " +
                    (failure.message ?: failure.javaClass.simpleName),
            ),
        )
      }
    }
  }

  fun current(): ApplicationSettingsDocument = synchronized(lock) { document }

  fun isReadOnly(): Boolean = synchronized(lock) { readOnly }

  /** Each load warning is returned at most once to the UI. */
  fun consumeLoadWarning(): ApplicationSettingsLoadWarning? = warning.getAndSet(null)

  fun lastWriteFailure(): IOException? = lastWriteFailure

  fun update(updated: ApplicationSettingsDocument) {
    // Construction plus canonical encoding validate all typed and bounded fields before mutation.
    val canonical = ApplicationSettingsCodec.decode(ApplicationSettingsCodec.encode(updated))
    encodeProperties(ApplicationSettingsCodec.encode(canonical))
    synchronized(lock) {
      check(!closing && !closed) { "Settings store is closing or closed" }
      check(!readOnly) { "Settings use an unsupported or unpreserved schema and are read-only" }
      document = canonical
      dirty = true
      revision++
      scheduled?.cancel(false)
      scheduled =
          writerExecutor().schedule(::writePendingInBackground, debounceMillis, TimeUnit.MILLISECONDS)
    }
  }

  @Throws(IOException::class)
  fun flush() {
    while (true) {
      val pending: Pair<Long, ApplicationSettingsDocument> =
          synchronized(lock) {
            check(!closed) { "Settings store is closed" }
            scheduled?.cancel(false)
            scheduled = null
            if (readOnly) return
            dirty = false
            revision to document
          }
      try {
        writeRevision(pending.first, pending.second)
        lastWriteFailure = null
      } catch (failure: IOException) {
        synchronized(lock) {
          if (committedRevision < revision) dirty = true
        }
        lastWriteFailure = failure
        throw failure
      }
      synchronized(lock) {
        if (revision == pending.first && committedRevision >= pending.first) return
      }
    }
  }

  override fun close() {
    closeLock.withLock {
      val started = System.nanoTime()
      val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
      val ownedExecutor =
          synchronized(lock) {
            if (closed) return
            closing = true
            scheduled?.cancel(false)
            scheduled = null
            writerExecutor()
          }
      val finalFlush = ownedExecutor.submit { flush() }
      var interrupted = false
      var failure: IOException? = null
      try {
        finalFlush.get(remainingNanos(started, timeoutNanos), TimeUnit.NANOSECONDS)
      } catch (timeout: TimeoutException) {
        finalFlush.cancel(true)
        failure =
            IOException(
                "Timed out after $closeTimeoutMillis ms while flushing application settings",
                timeout,
            )
      } catch (execution: ExecutionException) {
        val cause = execution.cause ?: execution
        failure =
            if (cause is IOException) cause
            else IOException("Application settings flush failed while closing", cause)
      } catch (caught: InterruptedException) {
        interrupted = true
        finalFlush.cancel(true)
        failure = IOException("Interrupted while flushing application settings", caught)
      } finally {
        ownedExecutor.shutdownNow()
        val remaining = remainingNanos(started, timeoutNanos)
        if (remaining > 0) {
          try {
            if (!ownedExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS) && failure == null) {
              failure =
                  IOException(
                      "Settings writer did not stop within $closeTimeoutMillis ms while closing")
            }
          } catch (caught: InterruptedException) {
            interrupted = true
            if (failure == null) {
              failure = IOException("Interrupted while stopping the settings writer", caught)
            } else {
              failure.addSuppressed(caught)
            }
          }
        }
        synchronized(lock) {
          // A failed/timed-out close is a retained shutdown attempt. Keep the newest revision
          // dirty and allow a later close to create a fresh writer after the interrupted one
          // physically unwinds. Only a fully successful flush makes the store terminal.
          executor = null
          if (failure == null) {
            closed = true
          } else {
            if (committedRevision < revision) dirty = true
            closing = false
          }
        }
        failure?.let { lastWriteFailure = it }
        if (interrupted) Thread.currentThread().interrupt()
      }
      if (failure != null) {
        throw IllegalStateException("Unable to close application settings safely", failure)
      }
    }
  }

  private fun writePendingInBackground() {
    val pending: Pair<Long, ApplicationSettingsDocument> =
        synchronized(lock) {
          scheduled = null
          if (!dirty || readOnly || closed) return
          dirty = false
          revision to document
        }
    try {
      writeRevision(pending.first, pending.second)
      lastWriteFailure = null
    } catch (failure: IOException) {
      LOG.error("Can't store application settings", failure)
      lastWriteFailure = failure
      synchronized(lock) {
        if (committedRevision < revision) dirty = true
      }
    }
  }

  private fun writerExecutor(): ScheduledExecutorService {
    executor?.let { return it }
    return Executors.newSingleThreadScheduledExecutor { task ->
          Thread(task, "coffee-gb-settings-writer").apply { isDaemon = true }
        }
        .also { executor = it }
  }

  @Throws(IOException::class)
  private fun writeDocument(value: ApplicationSettingsDocument) {
    val bytes =
        try {
          encodeProperties(ApplicationSettingsCodec.encode(value))
        } catch (failure: IllegalArgumentException) {
          throw IOException("Application settings cannot be encoded safely", failure)
        }
    persistence.write(path, bytes)
  }

  @Throws(IOException::class)
  private fun writeRevision(targetRevision: Long, value: ApplicationSettingsDocument) {
    try {
      writeLock.lockInterruptibly()
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IOException("Interrupted while waiting to write application settings", interrupted)
    }
    try {
      if (synchronized(lock) { committedRevision >= targetRevision }) return
      writeDocument(value)
      synchronized(lock) { committedRevision = maxOf(committedRevision, targetRevision) }
    } finally {
      writeLock.unlock()
    }
  }

  private fun remainingNanos(started: Long, timeoutNanos: Long): Long =
      (timeoutNanos - (System.nanoTime() - started)).coerceAtLeast(0)

  private fun load(): Loaded {
    val bytes =
        try {
          persistence.read(path) { recovered ->
            if (Files.notExists(recovered, LinkOption.NOFOLLOW_LINKS)) return@read null
            Files.newInputStream(recovered).use { input ->
              input.readNBytes(MAX_SETTINGS_BYTES + 1).also {
                if (it.size > MAX_SETTINGS_BYTES) {
                  throw OversizedSettingsException(
                      "Settings file exceeds the $MAX_SETTINGS_BYTES-byte limit")
                }
              }
            }
          }
        } catch (failure: OversizedSettingsException) {
          return recoverCorrupt(failure)
        } catch (failure: IOException) {
          return readFailure(failure)
        }
    if (bytes == null) return Loaded(ApplicationSettingsDocument(ApplicationSettings()))

    val raw =
        try {
          decodeProperties(bytes)
        } catch (failure: Exception) {
          return recoverCorrupt(failure)
        }
    val needsMigration =
        raw[ApplicationSettingsCodec.SCHEMA_VERSION_KEY] !=
            ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()
    return try {
      Loaded(ApplicationSettingsCodec.decode(raw), migrated = needsMigration)
    } catch (future: UnsupportedApplicationSettingsVersionException) {
      Loaded(
          ApplicationSettingsDocument(ApplicationSettings()),
          readOnly = true,
          warning =
              ApplicationSettingsLoadWarning(
                  ApplicationSettingsWarningKind.FUTURE_SCHEMA,
                  future.message ?: "Settings were written by a newer Coffee GB version",
              ),
      )
    } catch (failure: RuntimeException) {
      recoverCorrupt(failure)
    }
  }

  private fun recoverCorrupt(failure: Throwable): Loaded {
    val preserved =
        try {
          preserveCorruptFile()
        } catch (preserveFailure: IOException) {
          LOG.error("Unable to preserve corrupt application settings", preserveFailure)
          return Loaded(
              ApplicationSettingsDocument(ApplicationSettings()),
              readOnly = true,
              warning =
                  ApplicationSettingsLoadWarning(
                      ApplicationSettingsWarningKind.CORRUPT_FILE_PRESERVATION_FAILED,
                      "Settings are invalid and could not be preserved; defaults are active " +
                          "and settings writes are disabled: " +
                          (failure.message ?: failure.javaClass.simpleName),
                  ),
          )
        }
    return Loaded(
        ApplicationSettingsDocument(ApplicationSettings()),
        warning =
            ApplicationSettingsLoadWarning(
                ApplicationSettingsWarningKind.CORRUPT_FILE_RECOVERED,
                "Invalid settings were preserved as ${preserved.fileName}; safe defaults are active: " +
                    (failure.message ?: failure.javaClass.simpleName),
                preserved,
            ),
    )
  }

  private fun readFailure(failure: IOException): Loaded =
      Loaded(
          ApplicationSettingsDocument(ApplicationSettings()),
          readOnly = true,
          warning =
              ApplicationSettingsLoadWarning(
                  ApplicationSettingsWarningKind.READ_FAILED,
                  "Settings could not be read; safe defaults are active and settings writes are " +
                      "disabled: " + (failure.message ?: failure.javaClass.simpleName),
              ),
      )

  @Throws(IOException::class)
  private fun preserveCorruptFile(): Path {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("The invalid settings file is not accessible")
    }
    val timestamp = CORRUPT_TIME_FORMAT.format(clock.instant().atZone(ZoneOffset.UTC))
    for (suffix in 0..999) {
      val suffixText = if (suffix == 0) "" else "-$suffix"
      val candidate = path.resolveSibling("${path.fileName}.corrupt-$timestamp$suffixText")
      if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue
      try {
        Files.move(path, candidate, StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(path, candidate)
      }
      return candidate
    }
    throw IOException("No unique corrupt-settings backup name is available")
  }

  private data class Loaded(
      val document: ApplicationSettingsDocument,
      val migrated: Boolean = false,
      val readOnly: Boolean = false,
      val warning: ApplicationSettingsLoadWarning? = null,
  )

  private class OversizedSettingsException(message: String) : IOException(message)

  companion object {
    const val DEFAULT_DEBOUNCE_MILLIS = 250L
    const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L
    const val MAX_SETTINGS_BYTES = 1_048_576
    private val LOG = LoggerFactory.getLogger(ApplicationSettingsStore::class.java)
    private val CORRUPT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun defaultPath(): Path =
        Paths.get(System.getProperty("user.home")).resolve(".coffeegb.properties")

    internal fun decodeProperties(
        bytes: ByteArray,
        legacyCharset: Charset = Charset.defaultCharset(),
    ): Map<String, String> {
      require(bytes.size <= MAX_SETTINGS_BYTES) {
        "Settings file exceeds the $MAX_SETTINGS_BYTES-byte limit"
      }
      val text = if (hasAsciiSchemaMarker(bytes)) {
        // Versioned settings are emitted as ASCII-safe UTF-8. Never silently replace malformed
        // bytes in a versioned file, because a later write would otherwise destroy an unknown
        // value.
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
      } else {
        // Version 0 was read through FileReader, so its contract is the platform default charset
        // (including the decoder's replacement behavior), not UTF-8.
        String(bytes, legacyCharset)
      }
      val properties = Properties()
      StringReader(text).use(properties::load)
      return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    private fun hasAsciiSchemaMarker(bytes: ByteArray): Boolean {
      val marker = ApplicationSettingsCodec.SCHEMA_VERSION_KEY.toByteArray(StandardCharsets.US_ASCII)
      var lineStart = 0
      var continued = false
      while (lineStart <= bytes.size) {
        var lineEnd = lineStart
        while (lineEnd < bytes.size && bytes[lineEnd] != '\n'.code.toByte() &&
            bytes[lineEnd] != '\r'.code.toByte()) {
          lineEnd++
        }
        if (!continued) {
          var offset = lineStart
          while (offset < lineEnd &&
              (bytes[offset] == ' '.code.toByte() ||
                  bytes[offset] == '\t'.code.toByte() ||
                  bytes[offset] == '\u000c'.code.toByte())) {
            offset++
          }
          val comment =
              offset < lineEnd &&
                  (bytes[offset] == '#'.code.toByte() || bytes[offset] == '!'.code.toByte())
          if (!comment && lineEnd - offset >= marker.size &&
              marker.indices.all { bytes[offset + it] == marker[it] }) {
            val after = offset + marker.size
            if (after == lineEnd ||
                bytes[after] == '='.code.toByte() ||
                bytes[after] == ':'.code.toByte() ||
                bytes[after] == ' '.code.toByte() ||
                bytes[after] == '\t'.code.toByte() ||
                bytes[after] == '\u000c'.code.toByte()) {
              return true
            }
          }
        }

        var backslashes = 0
        var cursor = lineEnd - 1
        while (cursor >= lineStart && bytes[cursor] == '\\'.code.toByte()) {
          backslashes++
          cursor--
        }
        continued = backslashes % 2 == 1
        if (lineEnd >= bytes.size) break
        lineStart = lineEnd + 1
        if (bytes[lineEnd] == '\r'.code.toByte() && lineStart < bytes.size &&
            bytes[lineStart] == '\n'.code.toByte()) {
          lineStart++
        }
      }
      return false
    }

    internal fun encodeProperties(values: Map<String, String>): ByteArray {
      require(values.size <= 2_048) { "Settings contain more than 2048 properties" }
      val output = StringBuilder()
      values.toSortedMap().forEach { (key, value) ->
        require(key.isNotEmpty() && key.length <= 256) { "Invalid settings property name" }
        require(value.length <= 65_536) { "Settings property $key is too long" }
        output.append(escape(key)).append('=').append(escape(value)).append('\n')
        require(output.length <= MAX_SETTINGS_BYTES) {
          "Encoded settings exceed the $MAX_SETTINGS_BYTES-byte limit"
        }
      }
      return output.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    private fun escape(value: String): String =
        buildString(value.length) {
          value.forEach { char ->
            when (char) {
              '\\' -> append("\\\\")
              '\t' -> append("\\t")
              '\n' -> append("\\n")
              '\r' -> append("\\r")
              '\u000c' -> append("\\f")
              ' ', '=', ':', '#', '!' -> append('\\').append(char)
              else ->
                  if (char.code < 0x20 || char.code > 0x7e) {
                    append("\\u").append(char.code.toString(16).uppercase().padStart(4, '0'))
                  } else {
                    append(char)
                  }
            }
          }
        }
  }
}
