package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.state.ExclusiveFileWriter
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Frame-owner diagnostics, never consulted by emulation or rollback. The bounded pre-roll retains
 * startup inputs even when Record is pressed after connecting. No machine/ROM/save data is read.
 * Only the immutable completed text crosses to the filesystem worker.
 */
internal class NetplayInputLog(
    private val mode: LinkMode,
    private val localPlayer: Int,
    private val publish: (NetplayRecordingStatusEvent) -> Unit,
    private val historyLimit: Int = 4096,
    private val recordingLimit: Int = 100_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val write: (Path, ByteArray) -> Path = { path, bytes ->
      ExclusiveFileWriter.write(path, bytes).path
    },
) {
  val sessionId = nextSession.incrementAndGet()
  var phase = ReplayRecordingPhase.IDLE
    private set
  private val started = nanoTime()
  private val created = Instant.now().toString()
  private val rows = ArrayDeque<String>()
  private var sequence = 0L
  private var dropped = 0L
  private var path: Path? = null
  private var pending: ByteArray? = null
  private var writer: ExecutorService? = null
  private var saving: Future<Path>? = null

  init {
    require(historyLimit > 0 && recordingLimit > historyLimit)
  }

  fun announce() = status()

  fun input(frame: Long, observedFrame: Long, player: Int, input: Input, remote: Boolean) {
    event(
        "input", observedFrame,
        "inputFrame" to frame,
        "player" to player,
        "source" to if (remote) "remote" else "local",
        "pressed" to input.pressedButtons.map { it.name },
        "released" to input.releasedButtons.map { it.name },
    )
  }

  fun event(type: String, frame: Long, vararg fields: Pair<String, Any?>) {
    rows.addLast(json(linkedMapOf(
        "type" to type, "sequence" to sequence++, "elapsedNanos" to nanoTime() - started,
        "frame" to frame, *fields)))
    if (phase == ReplayRecordingPhase.RECORDING) {
      if (rows.size >= recordingLimit) stop(frame, "record_limit")
    } else {
      while (rows.size > historyLimit) {
        rows.removeFirst()
        dropped++
      }
    }
  }

  fun start(request: NetplayRecordingStartEvent, frame: Long) {
    if (request.sessionId != sessionId || phase != ReplayRecordingPhase.IDLE) return
    path = request.path
    phase = ReplayRecordingPhase.RECORDING
    event("recording_started", frame)
    status()
  }

  fun stop(frame: Long, reason: String = "user") {
    if (phase != ReplayRecordingPhase.RECORDING) return
    // Change phase first so the final marker cannot recursively trigger the size limit.
    phase = ReplayRecordingPhase.SAVING
    val header = json(linkedMapOf(
        "type" to "header", "format" to "coffee-gb-netplay-input", "version" to 1,
        "created" to created, "mode" to mode.name, "localPlayer" to localPlayer,
        "playerCount" to mode.playerCount, "droppedEvents" to dropped,
        "initialStateIncluded" to false,
        "producerVersion" to (NetplayInputLog::class.java.`package`?.implementationVersion ?: "development"),
        "os" to System.getProperty("os.name"),
        "osVersion" to System.getProperty("os.version"),
        "architecture" to System.getProperty("os.arch"),
        "javaVersion" to System.getProperty("java.version"),
    ))
    val end = json(linkedMapOf(
        "type" to "recording_stopped", "sequence" to sequence++, "frame" to frame,
        "elapsedNanos" to nanoTime() - started, "reason" to reason,
    ))
    pending = (header + "\n" + rows.joinToString("\n") + "\n" + end + "\n").toByteArray(Charsets.UTF_8)
    save()
  }

  fun retry(request: NetplayRecordingRetryEvent) {
    if (request.sessionId != sessionId || phase != ReplayRecordingPhase.UNSAVED) return
    path = request.path
    save()
  }

  private fun save() {
    check(saving == null)
    val bytes = checkNotNull(pending)
    val target = checkNotNull(path)
    val executor = writer ?: Executors.newSingleThreadExecutor { task ->
      Thread(task, "coffee-gb-netplay-input-log").apply { isDaemon = true }
    }.also { writer = it }
    phase = ReplayRecordingPhase.SAVING
    saving = executor.submit<Path> { write(target, bytes) }
    status()
  }

  fun poll() {
    val task = saving ?: return
    if (!task.isDone) return
    completeSave(task)
  }

  private fun completeSave(task: Future<Path>) {
    try {
      val saved = task.get()
      saving = null
      pending = null
      phase = ReplayRecordingPhase.IDLE
      status(savedPath = saved)
    } catch (failure: Exception) {
      saving = null
      phase = ReplayRecordingPhase.UNSAVED
      status(error = "Could not save the netplay input log. The recording is retained; choose another file to retry.")
      if (failure is InterruptedException) Thread.currentThread().interrupt()
    }
  }

  /** Called only after the frame owner has stopped. Failed/slow writes remain retryable. */
  fun finish(frame: Long, deadlineNanos: Long) {
    stop(frame, "session_closed")
    if (phase == ReplayRecordingPhase.UNSAVED) save()
    saving?.let { task ->
      // A timeout retains the same future, so close retries cannot overlap filesystem writes.
      try {
        task.get((deadlineNanos - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
      } catch (failure: java.util.concurrent.ExecutionException) {
        completeSave(task)
        throw java.io.IOException("Unable to save the netplay input log", failure.cause)
      }
      completeSave(task)
    }
    writer?.shutdown()
    status(available = false)
  }

  private fun status(available: Boolean = true, savedPath: Path? = null, error: String? = null) =
      publish(NetplayRecordingStatusEvent(sessionId, phase, available, savedPath, error))

  private companion object {
    val nextSession = AtomicLong()

    // This small schema deliberately accepts only plain metadata, button names, and numbers.
    fun json(value: Any?): String = when (value) {
      null -> "null"
      is String -> buildString {
        append('"')
        for (c in value) when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
      }
      is Number, is Boolean -> value.toString()
      is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
      is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
      else -> error("Unsupported netplay log value")
    }
  }
}
