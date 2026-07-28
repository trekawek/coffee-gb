package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

internal enum class StateWorkerPurpose {
  MANUAL,
  AUTOSAVE_ROM_SWITCH,
  AUTOSAVE_CLOSE,
  RESUME_SCAN,
}

internal data class StateWorkerContext(
    val sessionId: Long,
    val workspace: StateWorkspace,
    val identity: MachineIdentity,
    val hardwareProfileId: String,
)

internal sealed interface StateWorkerResult {
  data class Catalog(val catalog: StateBrowserCatalog) : StateWorkerResult

  data class Saved(
      val ref: StateRef,
      val result: StateAssetSaveResult,
  ) : StateWorkerResult

  data class Loaded(
      val key: StateEntryKey,
      val result: StateReadResult,
  ) : StateWorkerResult

  data class Deleted(
      val key: StateEntryKey,
      val result: StateDeleteResult,
  ) : StateWorkerResult

  data class Exported(
      val key: StateEntryKey,
      val result: StateExportResult,
  ) : StateWorkerResult

  data class Screenshot(val result: StateScreenshotResult) : StateWorkerResult

  data class Folder(
      val path: Path,
      val opened: Boolean,
  ) : StateWorkerResult

  data class Resume(val located: Pair<StateEntryKey, StateReadResult>?) : StateWorkerResult

  data class Failure(val error: StateUserError) : StateWorkerResult
}

internal data class StateWorkerCompletedEvent(
    val context: StateWorkerContext,
    val requestId: Long,
    val operation: StateOperation,
    val purpose: StateWorkerPurpose,
    val result: StateWorkerResult,
) : Event

/**
 * The single state I/O worker. Live machine capture/apply never occurs here; BasicController
 * supplies detached captures at a frame boundary and consumes decoded results at a later boundary.
 */
internal class StateOperationWorker(
    private val eventBus: EventBus,
    executor: ExecutorService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val externalActions: StateExternalActions = StateExternalActions.UNSUPPORTED,
) : AutoCloseable {
  private val scheduler: StateTaskScheduler =
      executor?.let(::ExecutorStateTaskScheduler) ?: BoundedPriorityStateTaskScheduler()

  fun catalog(context: StateWorkerContext, requestId: Long) =
      submit(context, requestId, StateOperation.CATALOG, StateWorkerPurpose.MANUAL) {
        StateWorkerResult.Catalog(context.workspace.catalog(context.identity))
      }

  fun save(
      context: StateWorkerContext,
      requestId: Long,
      purpose: StateWorkerPurpose,
      ref: StateRef,
      state: StateFile,
      label: String?,
      playDurationNanos: Long?,
      thumbnail: StateImage?,
  ) =
      submit(
          context,
          requestId,
          if (purpose == StateWorkerPurpose.MANUAL) StateOperation.SAVE else StateOperation.AUTOSAVE,
          purpose,
      ) {
        val encoded = StateCodec.encode(state, StateCompression.DEFLATE)
        val thumbnailPng =
            thumbnail?.thumbnail()?.let { StatePngCodec.encode(it) }
        StateWorkerResult.Saved(
            ref,
            context.workspace.save(
                ref,
                encoded,
                StateSaveMetadata(
                    label = label,
                    savedAt = clock.instant(),
                    playDurationNanos = playDurationNanos,
                ),
                thumbnailPng,
            ),
        )
      }

  fun load(
      context: StateWorkerContext,
      requestId: Long,
      purpose: StateWorkerPurpose,
      key: StateEntryKey,
  ) =
      submit(
          context,
          requestId,
          if (purpose == StateWorkerPurpose.RESUME_SCAN) StateOperation.RESUME
          else StateOperation.LOAD,
          purpose,
      ) {
        StateWorkerResult.Loaded(key, context.workspace.read(key))
      }

  fun delete(context: StateWorkerContext, requestId: Long, key: StateEntryKey) =
      submit(context, requestId, StateOperation.DELETE, StateWorkerPurpose.MANUAL) {
        StateWorkerResult.Deleted(key, context.workspace.delete(key))
      }

  fun export(
      context: StateWorkerContext,
      requestId: Long,
      key: StateEntryKey,
      destination: Path,
  ) =
      submit(context, requestId, StateOperation.EXPORT, StateWorkerPurpose.MANUAL) {
        StateWorkerResult.Exported(key, context.workspace.export(key, destination))
      }

  fun screenshot(
      context: StateWorkerContext,
      requestId: Long,
      image: StateImage,
  ) =
      submit(context, requestId, StateOperation.SCREENSHOT, StateWorkerPurpose.MANUAL) {
        StateWorkerResult.Screenshot(
            StateScreenshotStore(context.workspace.paths.screenshotsDirectory, clock)
                .save(image, context.hardwareProfileId))
      }

  fun openFolder(context: StateWorkerContext, requestId: Long) =
      submit(context, requestId, StateOperation.OPEN_FOLDER, StateWorkerPurpose.MANUAL) {
        val directory = context.workspace.activeGameDirectory().toAbsolutePath().normalize()
        ensureSafeDirectory(directory)
        StateWorkerResult.Folder(directory, externalActions.openDirectory(directory))
      }

  fun scanResume(context: StateWorkerContext, requestId: Long) =
      submit(context, requestId, StateOperation.RESUME, StateWorkerPurpose.RESUME_SCAN) {
        StateWorkerResult.Resume(context.workspace.firstAutosave(context.identity))
      }

  private fun submit(
      context: StateWorkerContext,
      requestId: Long,
      operation: StateOperation,
      purpose: StateWorkerPurpose,
      task: () -> StateWorkerResult,
  ) {
    val work =
        StateQueuedWork(
            context = context,
            requestId = requestId,
            operation = operation,
            purpose = purpose,
        ) {
          val result =
              try {
                task()
              } catch (failure: Throwable) {
                if (failure is InterruptedException) {
                  Thread.currentThread().interrupt()
                }
                LOG.warn("State worker {} request {} failed", operation, requestId, failure)
                StateWorkerResult.Failure(userError(context, operation, failure))
              }
          postCompletion(context, requestId, operation, purpose, result)
        }
    val admission = scheduler.submit(work)
    admission.displaced.forEach {
      postAdmissionFailure(it, "A newer equivalent state request replaced this queued request.")
    }
    if (!admission.accepted) {
      postAdmissionFailure(
          work,
          "The bounded state queue is full or is shutting down. Wait for current work and retry.",
      )
    }
  }

  override fun close() {
    close(WORKER_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
  }

  fun close(timeout: Long, unit: TimeUnit) {
    require(timeout > 0) { "State worker close timeout must be positive" }
    val abandoned = scheduler.closeAndAwait(timeout, unit)
    abandoned.forEach {
      postAdmissionFailure(it, "The queued state request was cancelled during worker shutdown.")
    }
  }

  private fun postAdmissionFailure(work: StateQueuedWork, detail: String) {
    postCompletion(
        work.context,
        work.requestId,
        work.operation,
        work.purpose,
        StateWorkerResult.Failure(
            StateUserError(
                "State request could not be queued.",
                detail,
                "Wait for current state work to finish, then retry.",
            )),
    )
  }

  private fun postCompletion(
      context: StateWorkerContext,
      requestId: Long,
      operation: StateOperation,
      purpose: StateWorkerPurpose,
      result: StateWorkerResult,
  ) {
    eventBus.post(StateWorkerCompletedEvent(context, requestId, operation, purpose, result))
  }

  private fun userError(
      context: StateWorkerContext,
      operation: StateOperation,
      failure: Throwable,
  ): StateUserError {
    val summary =
        when (operation) {
          StateOperation.CATALOG -> "State list could not be refreshed."
          StateOperation.SAVE, StateOperation.AUTOSAVE -> "State could not be saved."
          StateOperation.LOAD, StateOperation.RESUME -> "State could not be loaded."
          StateOperation.DELETE -> "State could not be deleted."
          StateOperation.EXPORT -> "State could not be exported."
          StateOperation.SCREENSHOT -> "Screenshot could not be saved."
          StateOperation.OPEN_FOLDER -> "Save folder could not be prepared."
          StateOperation.PREPARE_CLOSE -> "Close autosave could not be completed."
        }
    val messages = generateSequence(failure) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .map { throwable ->
          val message = throwable.message
              ?.let {
                StateDiagnosticRedactor.redact(
                    it,
                    context.workspace.sensitivePaths(),
                    MAX_CAUSE_MESSAGE_CHARS,
                )
              }
              ?.takeIf(String::isNotEmpty)
          "${throwable.javaClass.simpleName}${message?.let { ": $it" } ?: ""}"
        }
        .joinToString("\n")
        .take(StateUserError.MAX_DETAIL_CHARS)
    val action =
        when (operation) {
          StateOperation.SAVE, StateOperation.AUTOSAVE, StateOperation.SCREENSHOT ->
            "Check that the configured save directory is writable and has free space. " +
                "The previous complete state was preserved."
          StateOperation.LOAD, StateOperation.RESUME ->
            "Keep the running game open, inspect or export the state, and delete it only if it " +
                "is no longer needed."
          StateOperation.DELETE, StateOperation.EXPORT ->
            "Check destination permissions and retry. No existing export was overwritten."
          StateOperation.CATALOG ->
            "Check the save directory and retry. Stable slots and autosave remain independent."
          StateOperation.OPEN_FOLDER ->
            "Copy the path shown by Coffee GB and open it with your file manager."
          StateOperation.PREPARE_CLOSE ->
            "Retry, choose another save directory, or explicitly close without a new autosave."
        }
    return StateUserError(summary, messages.ifBlank { failure.javaClass.name }, action)
  }

  private fun ensureSafeDirectory(directory: Path) {
    var cursor = directory.root
    for (component in directory) {
      cursor = if (cursor == null) component else cursor.resolve(component)
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
          (Files.isSymbolicLink(cursor) ||
              !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
        throw IOException("Save-folder path component is not a safe directory: ${cursor.fileName}")
      }
    }
    Files.createDirectories(directory)
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(StateOperationWorker::class.java)
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_CAUSE_MESSAGE_CHARS = 900
  }
}

private data class StateQueuedWork(
    val context: StateWorkerContext,
    val requestId: Long,
    val operation: StateOperation,
    val purpose: StateWorkerPurpose,
    val action: () -> Unit,
) : Runnable {
  val priority: Int
    get() =
        when (purpose) {
          StateWorkerPurpose.AUTOSAVE_CLOSE -> 0
          StateWorkerPurpose.AUTOSAVE_ROM_SWITCH -> 1
          StateWorkerPurpose.MANUAL, StateWorkerPurpose.RESUME_SCAN -> 2
        }

  val coalescingKey: Pair<Long, StateOperation>?
    get() =
        when (operation) {
          StateOperation.CATALOG, StateOperation.SCREENSHOT, StateOperation.RESUME ->
            context.sessionId to operation
          else -> null
        }

  override fun run() = action()
}

private data class StateTaskAdmission(
    val accepted: Boolean,
    val displaced: List<StateQueuedWork> = emptyList(),
)

private interface StateTaskScheduler {
  fun submit(work: StateQueuedWork): StateTaskAdmission

  /** Returns queued work that could not be completed before shutdown. */
  fun closeAndAwait(timeout: Long, unit: TimeUnit): List<StateQueuedWork>
}

/** Adapter retained for deterministic controller tests that inject their own executor. */
private class ExecutorStateTaskScheduler(
    private val executor: ExecutorService,
) : StateTaskScheduler {
  override fun submit(work: StateQueuedWork): StateTaskAdmission =
      try {
        executor.execute(work)
        StateTaskAdmission(true)
      } catch (_: RejectedExecutionException) {
        StateTaskAdmission(false)
      }

  override fun closeAndAwait(timeout: Long, unit: TimeUnit): List<StateQueuedWork> {
    val deadlineNanos = closeDeadline(timeout, unit)
    executor.shutdown()
    var interrupted = false
    try {
      if (!executor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
        executor.shutdownNow()
        if (!executor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
          throw IllegalStateException("Injected state worker executor did not terminate")
        }
      }
    } catch (_: InterruptedException) {
      interrupted = true
      executor.shutdownNow()
      try {
        if (!executor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
          throw IllegalStateException("Injected state worker executor did not terminate")
        }
      } catch (_: InterruptedException) {
        interrupted = true
        throw IllegalStateException("Interrupted while awaiting state worker termination")
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
    return emptyList()
  }
}

/**
 * Single-thread state scheduler with a hard queue bound. Close and ROM-switch autosaves jump ahead
 * of queued manual work, while repeatable refresh/screenshot/resume requests are coalesced.
 */
private class BoundedPriorityStateTaskScheduler(
    private val capacity: Int = MAX_QUEUED_STATE_TASKS,
) : StateTaskScheduler {
  private val monitor = Object()
  private val queues = Array(3) { ArrayDeque<StateQueuedWork>() }
  private var accepting = true
  private var stoppingImmediately = false
  private val worker =
      Thread(::runWorker, "coffee-gb-state-worker").apply {
        isDaemon = true
        start()
      }

  override fun submit(work: StateQueuedWork): StateTaskAdmission =
      synchronized(monitor) {
        if (!accepting) return@synchronized StateTaskAdmission(false)

        val displaced = mutableListOf<StateQueuedWork>()
        work.coalescingKey?.let { key ->
          queues.forEach { queue ->
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
              val queued = iterator.next()
              if (queued.coalescingKey == key) {
                iterator.remove()
                displaced += queued
              }
            }
          }
        }

        if (queuedCount() >= capacity && work.priority < ORDINARY_PRIORITY) {
          val ordinary = queues[ORDINARY_PRIORITY]
          if (ordinary.isNotEmpty()) displaced += ordinary.removeFirst()
        }
        if (queuedCount() >= capacity) {
          return@synchronized StateTaskAdmission(false, displaced)
        }
        queues[work.priority].addLast(work)
        monitor.notifyAll()
        StateTaskAdmission(true, displaced)
      }

  override fun closeAndAwait(timeout: Long, unit: TimeUnit): List<StateQueuedWork> {
    val deadlineNanos = closeDeadline(timeout, unit)
    synchronized(monitor) {
      accepting = false
      monitor.notifyAll()
    }
    var interrupted = false
    try {
      joinUntil(worker, deadlineNanos)
    } catch (_: InterruptedException) {
      interrupted = true
    }
    if (worker.isAlive) {
      synchronized(monitor) {
        stoppingImmediately = true
        monitor.notifyAll()
      }
      worker.interrupt()
      try {
        joinUntil(worker, deadlineNanos)
      } catch (_: InterruptedException) {
        interrupted = true
      }
    }
    if (interrupted) Thread.currentThread().interrupt()
    if (worker.isAlive) {
      throw IllegalStateException("State worker did not terminate after interruption")
    }
    return synchronized(monitor) {
      queues.flatMap { queue -> queue.toList().also { queue.clear() } }
    }
  }

  private fun runWorker() {
    while (true) {
      val work =
          synchronized(monitor) {
            while (!stoppingImmediately && accepting && queuedCount() == 0) {
              try {
                monitor.wait()
              } catch (_: InterruptedException) {
                if (stoppingImmediately) return
              }
            }
            if (stoppingImmediately || (!accepting && queuedCount() == 0)) return
            queues.firstNotNullOfOrNull { it.pollFirst() }
          }
      work?.run()
    }
  }

  private fun queuedCount(): Int = queues.sumOf(ArrayDeque<StateQueuedWork>::size)
}

private const val MAX_QUEUED_STATE_TASKS = 32
private const val ORDINARY_PRIORITY = 2
private const val WORKER_SHUTDOWN_SECONDS = 5L

private fun closeDeadline(timeout: Long, unit: TimeUnit): Long {
  val timeoutNanos = unit.toNanos(timeout).coerceAtLeast(1)
  val now = System.nanoTime()
  return if (Long.MAX_VALUE - now < timeoutNanos) Long.MAX_VALUE else now + timeoutNanos
}

private fun remainingNanos(deadlineNanos: Long): Long =
    (deadlineNanos - System.nanoTime()).coerceAtLeast(1)

@Throws(InterruptedException::class)
private fun joinUntil(thread: Thread, deadlineNanos: Long) {
  val remaining = remainingNanos(deadlineNanos)
  thread.join(
      TimeUnit.NANOSECONDS.toMillis(remaining),
      (remaining % 1_000_000).toInt(),
  )
}
