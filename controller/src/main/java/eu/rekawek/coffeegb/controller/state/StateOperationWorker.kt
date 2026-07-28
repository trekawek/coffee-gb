package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private val executor: ExecutorService = createExecutor(),
    private val clock: Clock = Clock.systemUTC(),
    private val externalActions: StateExternalActions = StateExternalActions.UNSUPPORTED,
) : AutoCloseable {

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
    executor.execute {
      val result =
          try {
            task()
          } catch (failure: Throwable) {
            if (failure is InterruptedException) {
              Thread.currentThread().interrupt()
            }
            LOG.warn("State worker {} request {} failed", operation, requestId, failure)
            StateWorkerResult.Failure(userError(operation, failure))
          }
      eventBus.post(
          StateWorkerCompletedEvent(
              context,
              requestId,
              operation,
              purpose,
              result,
          ))
    }
  }

  override fun close() {
    executor.shutdown()
    try {
      if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    } catch (_: InterruptedException) {
      executor.shutdownNow()
      Thread.currentThread().interrupt()
    }
  }

  private fun userError(operation: StateOperation, failure: Throwable): StateUserError {
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
          val message =
              throwable.message
                  ?.replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
                  ?.trim()
                  ?.take(MAX_CAUSE_MESSAGE_CHARS)
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
    private const val SHUTDOWN_SECONDS = 5L
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_CAUSE_MESSAGE_CHARS = 900

    private fun createExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-state-worker").apply { isDaemon = true }
        }
  }
}
