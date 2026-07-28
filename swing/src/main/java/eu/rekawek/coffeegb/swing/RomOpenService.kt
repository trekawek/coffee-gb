package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.RecentRoms
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.RomSourceException
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory

enum class RomOpenSource {
  CHOOSER,
  INITIAL_ARGUMENT,
  DROP,
  RECENT,
  DESKTOP_OPEN_FILE,
}

sealed interface RomOpenInput {
  data class LocalPath(val path: Path) : RomOpenInput

  data class RemoteUrl(val description: String) : RomOpenInput
}

data class RomOpenRequest(
    val inputs: List<RomOpenInput>,
    val source: RomOpenSource,
) {
  constructor(path: Path, source: RomOpenSource) :
      this(listOf(RomOpenInput.LocalPath(path)), source)
}

enum class RomOpenStage {
  QUEUED,
  SNAPSHOTTING,
  INSPECTING,
  AWAITING_ARCHIVE_SELECTION,
  PREPARING_CORE,
  AWAITING_PERSISTENCE_DECISION,
}

enum class RomOpenFailureKind {
  NO_INPUT,
  MULTIPLE_INPUTS,
  REMOTE_URL,
  MISSING,
  NOT_A_FILE,
  UNSUPPORTED_TYPE,
  UNSUPPORTED_SEVEN_Z,
  UNREADABLE,
  LIMIT_EXCEEDED,
  INVALID_ARCHIVE,
  UNSAFE_ARCHIVE_ENTRY,
  NO_ROM_CANDIDATES,
  INVALID_HEADER,
  INVALID_ARCHIVE_SELECTION,
  CORE_STARTUP,
  INTERNAL,
}

data class RomOpenFailure(
    val kind: RomOpenFailureKind,
    val message: String,
    val technicalDetails: String,
)

sealed interface RomOpenUpdate {
  val requestId: Long
  val source: RomOpenSource

  data class Progress(
      override val requestId: Long,
      override val source: RomOpenSource,
      val path: Path?,
      val stage: RomOpenStage,
      val copiedBytes: Long = 0,
      val candidates: List<RomSourceSnapshot.ArchiveCandidate> = emptyList(),
      val persistenceFileName: String? = null,
  ) : RomOpenUpdate

  data class Opened(
      override val requestId: Long,
      override val source: RomOpenSource,
      val recentPath: Path,
      val origin: RomOrigin,
      val title: String,
  ) : RomOpenUpdate

  data class Failed(
      override val requestId: Long,
      override val source: RomOpenSource,
      val path: Path?,
      val failure: RomOpenFailure,
  ) : RomOpenUpdate

  data class Cancelled(
      override val requestId: Long,
      override val source: RomOpenSource,
  ) : RomOpenUpdate
}

/**
 * Single asynchronous desktop ROM-opening pipeline.
 *
 * File/archive work and controller posts run on the worker. All observer calls are dispatched
 * through [uiExecutor], and callbacks queued for a superseded request are discarded.
 */
class RomOpenService
internal constructor(
    private val eventBus: EventBus,
    private val recentStore: RomRecentStore,
    private val listener: (RomOpenUpdate) -> Unit,
    executor: ExecutorService? = null,
    private val uiExecutor: Executor =
        Executor { task -> SwingUtilities.invokeLater(task) },
) : Closeable {

  constructor(
      eventBus: EventBus,
      recentRoms: RecentRoms,
      listener: (RomOpenUpdate) -> Unit,
  ) : this(eventBus, PreferencesRomRecentStore(recentRoms), listener)

  private val executor =
      executor
          ?: Executors.newSingleThreadExecutor { task ->
            Thread(task, "coffee-gb-rom-open").apply { isDaemon = true }
          }

  private val ownedExecutor = if (executor == null) this.executor else null

  private val nextRequestId = AtomicLong(1)

  /** Request whose already-queued UI callbacks are still allowed to become visible. */
  private val visibleRequestId = AtomicLong()

  private val closed = AtomicBoolean()

  private val lock = Any()

  private var active: Operation? = null

  init {
    eventBus.register<Controller.RomLoadingEvent> { event ->
      event.openRequestId?.let { id ->
        withCurrent(id) { operation ->
          publish(
              operation,
              RomOpenUpdate.Progress(
                  id,
                  operation.request.source,
                  operation.path,
                  RomOpenStage.PREPARING_CORE,
              ))
        }
      }
    }
    eventBus.register<Controller.RomReplacementPersistenceFailedEvent> { event ->
      event.openRequestId?.let { id ->
        withCurrent(id) { operation ->
          operation.persistenceRequestId = event.requestId
          publish(
              operation,
              RomOpenUpdate.Progress(
                  id,
                  operation.request.source,
                  operation.path,
                  RomOpenStage.AWAITING_PERSISTENCE_DECISION,
                  persistenceFileName = event.fileName,
              ))
        }
      }
    }
    eventBus.register<Controller.EmulationStartedEvent> { event ->
      event.openRequestId?.let { id ->
        val operation = current(id) ?: return@register
        val path = operation.path ?: return@register
        val origin = event.origin ?: operation.origin ?: return@register
        if (!operation.terminal.compareAndSet(false, true)) {
          return@register
        }
        submitLifecycle {
          try {
            recentStore.recordSuccessfulOpen(path)
          } catch (failure: RuntimeException) {
            LOG.warn("ROM opened but its recent-file entry could not be updated", failure)
          }
          completeTerminal(
              operation,
              RomOpenUpdate.Opened(
                  id,
                  operation.request.source,
                  path,
                  origin,
                  event.romName,
              ))
        }
      }
    }
    eventBus.register<Controller.LoadRomFailedEvent> { event ->
      event.openRequestId?.let { id ->
        submitLifecycle {
          current(id)?.let { operation ->
            terminal(
                operation,
                RomOpenUpdate.Failed(
                    id,
                    operation.request.source,
                    operation.path,
                    RomOpenFailure(
                        when (event.kind) {
                          Controller.RomLoadFailureKind.CORE_STARTUP ->
                              RomOpenFailureKind.CORE_STARTUP
                          Controller.RomLoadFailureKind.PERSISTENCE ->
                              RomOpenFailureKind.INTERNAL
                          Controller.RomLoadFailureKind.INTERNAL ->
                              RomOpenFailureKind.INTERNAL
                        },
                        "Coffee GB could not start this ROM. The current game was kept open.",
                        event.technicalDetails,
                    ),
                ))
          }
        }
      }
    }
    eventBus.register<Controller.RomLoadingCancelledEvent> { event ->
      event.openRequestId?.let { id ->
        submitLifecycle {
          current(id)?.let { operation ->
            terminal(
                operation,
                RomOpenUpdate.Cancelled(id, operation.request.source),
            )
          }
        }
      }
    }
  }

  fun open(request: RomOpenRequest): Long {
    val operation = Operation(nextRequestId.getAndIncrement(), request)
    synchronized(lock) {
      check(!closed.get()) { "ROM-open service is closed" }
      visibleRequestId.set(operation.id)
      active?.let { prior ->
        prior.superseded.set(true)
        prior.cancelled.set(true)
        prior.future?.cancel(true)
        executor.execute { cleanupSuperseded(prior) }
      }
      active = operation
      operation.future = executor.submit { prepare(operation) }
    }
    publish(
        operation,
        RomOpenUpdate.Progress(
            operation.id,
            request.source,
            null,
            RomOpenStage.QUEUED,
        ))
    return operation.id
  }

  fun selectArchive(requestId: Long, candidateToken: Long) {
    val operation = current(requestId) ?: return
    synchronized(operation) {
      if (operation.selectionSubmitted || operation.snapshot == null) {
        return
      }
      operation.selectionSubmitted = true
    }
    operation.future = executor.submit { loadSelection(operation, candidateToken) }
  }

  fun retryPersistence(requestId: Long) {
    val operation = current(requestId) ?: return
    val persistenceId = operation.persistenceRequestId ?: return
    executor.execute {
      if (current(requestId) === operation) {
        operation.persistenceRequestId = null
        eventBus.post(Controller.RetryRomReplacementEvent(persistenceId))
        publish(
            operation,
            RomOpenUpdate.Progress(
                operation.id,
                operation.request.source,
                operation.path,
                RomOpenStage.PREPARING_CORE,
            ))
      }
    }
  }

  fun cancel(requestId: Long) {
    val operation = current(requestId) ?: return
    operation.cancelled.set(true)
    operation.future?.cancel(true)
    executor.execute {
      if (operation.controllerDispatched) {
        eventBus.post(Controller.CancelRomOpenEvent(operation.id))
      } else {
        terminal(
            operation,
            RomOpenUpdate.Cancelled(operation.id, operation.request.source),
        )
      }
    }
  }

  fun removeRecent(path: Path) {
    recentStore.remove(path)
  }

  fun recentPaths(): List<Path> = recentStore.getPaths()

  override fun close() {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    visibleRequestId.set(NO_VISIBLE_REQUEST)
    val operation =
        synchronized(lock) {
          val current = active
          current?.also {
            it.superseded.set(true)
            it.cancelled.set(true)
          }
          active = null
          current
        }
    operation?.future?.cancel(true)
    operation?.let {
      runCatching { it.snapshot?.close() }
      if (it.controllerDispatched) {
        runCatching { eventBus.post(Controller.CancelRomOpenEvent(it.id)) }
            .onFailure { failure ->
              LOG.warn("Unable to cancel ROM-open request while closing", failure)
            }
      }
    }
    ownedExecutor?.shutdownNow()
  }

  private fun prepare(operation: Operation) {
    if (!isCurrent(operation)) {
      return
    }
    val inputs = operation.request.inputs
    when {
      inputs.isEmpty() -> {
        fail(
            operation,
            RomOpenFailureKind.NO_INPUT,
            "No ROM file was provided.",
            "ROM-open request ${operation.id} contained no input",
        )
        return
      }
      inputs.size > 1 -> {
        fail(
            operation,
            RomOpenFailureKind.MULTIPLE_INPUTS,
            "Open one ROM or ZIP at a time.",
            "ROM-open request ${operation.id} contained ${inputs.size} inputs",
        )
        return
      }
    }
    val input = inputs.single()
    if (input is RomOpenInput.RemoteUrl) {
      fail(
          operation,
          RomOpenFailureKind.REMOTE_URL,
          "Coffee GB opens local files only; URLs are not downloaded.",
          "Rejected remote input (${input.description.take(80)})",
      )
      return
    }
    val path = (input as RomOpenInput.LocalPath).path.toAbsolutePath().normalize()
    operation.path = path
    publish(
        operation,
        RomOpenUpdate.Progress(
            operation.id,
            operation.request.source,
            path,
            RomOpenStage.SNAPSHOTTING,
        ))
    try {
      val snapshot =
          RomSourceSnapshot.open(
              path,
              { operation.cancelled.get() || !isCurrent(operation) },
              { copied ->
                if (operation.shouldPublishCopyProgress(copied)) {
                  publish(
                      operation,
                      RomOpenUpdate.Progress(
                          operation.id,
                          operation.request.source,
                          path,
                          RomOpenStage.SNAPSHOTTING,
                          copiedBytes = copied,
                      ))
                }
              },
          )
      if (!isCurrent(operation) || operation.cancelled.get()) {
        snapshot.close()
        return
      }
      operation.snapshot = snapshot
      publish(
          operation,
          RomOpenUpdate.Progress(
              operation.id,
              operation.request.source,
              path,
              RomOpenStage.INSPECTING,
          ))
      if (!snapshot.isArchive) {
        dispatch(operation, snapshot.loadSingle())
      } else if (snapshot.candidates().size == 1) {
        dispatch(operation, snapshot.loadSingle())
      } else {
        publish(
            operation,
            RomOpenUpdate.Progress(
                operation.id,
                operation.request.source,
                path,
                RomOpenStage.AWAITING_ARCHIVE_SELECTION,
                candidates = snapshot.candidates(),
            ))
      }
    } catch (_: CancellationException) {
      if (isCurrent(operation)) {
        terminal(
            operation,
            RomOpenUpdate.Cancelled(operation.id, operation.request.source),
        )
      }
    } catch (failure: RomSourceException) {
      fail(operation, failure)
    } catch (failure: Exception) {
      fail(
          operation,
          RomOpenFailureKind.INTERNAL,
          "Coffee GB could not inspect this ROM.",
          technicalDetails(failure),
      )
    } finally {
      cleanupAbandonedSnapshot(operation)
    }
  }

  private fun loadSelection(operation: Operation, candidateToken: Long) {
    try {
      val snapshot = operation.snapshot ?: throw IllegalStateException("Archive snapshot missing")
      dispatch(
          operation,
          snapshot.load(candidateToken) {
            operation.cancelled.get() || !isCurrent(operation)
          },
      )
    } catch (_: CancellationException) {
      if (isCurrent(operation)) {
        terminal(
            operation,
            RomOpenUpdate.Cancelled(operation.id, operation.request.source),
        )
      }
    } catch (failure: RomSourceException) {
      fail(operation, failure)
    } catch (failure: Exception) {
      fail(
          operation,
          RomOpenFailureKind.INVALID_ARCHIVE_SELECTION,
          "The selected archive entry could not be opened.",
          technicalDetails(failure),
      )
    } finally {
      cleanupAbandonedSnapshot(operation)
    }
  }

  private fun dispatch(
      operation: Operation,
      image: eu.rekawek.coffeegb.core.memory.cart.RomImage,
  ) {
    if (!isCurrent(operation) || operation.cancelled.get()) {
      return
    }
    operation.origin = image.origin()
    runCatching { operation.snapshot?.close() }
        .onFailure { LOG.warn("Unable to remove consumed ROM snapshot", it) }
    operation.snapshot = null
    synchronized(lock) {
      if (!isCurrentLocked(operation) || operation.cancelled.get()) {
        return
      }
      operation.controllerDispatched = true
    }
    eventBus.post(
        Controller.LoadRomEvent(
            image,
            state = null,
            openRequestId = operation.id,
        ))
  }

  private fun fail(operation: Operation, failure: RomSourceException) {
    fail(
        operation,
        mapFailure(failure.reason()),
        friendlyMessage(failure),
        technicalDetails(failure),
    )
  }

  private fun fail(
      operation: Operation,
      kind: RomOpenFailureKind,
      message: String,
      technicalDetails: String,
  ) {
    if (!isCurrent(operation)) {
      return
    }
    terminal(
        operation,
        RomOpenUpdate.Failed(
            operation.id,
            operation.request.source,
            operation.path,
            RomOpenFailure(kind, message, technicalDetails),
        ))
  }

  private fun terminal(operation: Operation, update: RomOpenUpdate) {
    if (!operation.terminal.compareAndSet(false, true)) {
      return
    }
    completeTerminal(operation, update)
  }

  private fun completeTerminal(operation: Operation, update: RomOpenUpdate) {
    runCatching { operation.snapshot?.close() }
        .onFailure { LOG.warn("Unable to remove ROM snapshot", it) }
    operation.snapshot = null
    synchronized(lock) {
      if (active === operation) {
        active = null
      }
    }
    publish(operation, update)
  }

  private fun publish(operation: Operation, update: RomOpenUpdate) {
    if (!mayPublish(operation)) {
      return
    }
    uiExecutor.execute {
      if (mayPublish(operation)) {
        listener(update)
      }
    }
  }

  private fun mayPublish(operation: Operation): Boolean =
      !operation.superseded.get() &&
          !closed.get() &&
          visibleRequestId.get() == operation.id

  private fun current(requestId: Long): Operation? =
      synchronized(lock) { active?.takeIf { it.id == requestId && !it.terminal.get() } }

  private inline fun withCurrent(requestId: Long, action: (Operation) -> Unit) {
    current(requestId)?.let(action)
  }

  private fun isCurrent(operation: Operation): Boolean =
      synchronized(lock) { isCurrentLocked(operation) }

  private fun isCurrentLocked(operation: Operation): Boolean =
      active === operation &&
          !operation.superseded.get() &&
          !operation.terminal.get() &&
          !closed.get()

  private fun cleanupSuperseded(operation: Operation) {
    runCatching { operation.snapshot?.close() }
    operation.snapshot = null
    if (operation.controllerDispatched) {
      eventBus.post(Controller.CancelRomOpenEvent(operation.id))
    }
  }

  private fun cleanupAbandonedSnapshot(operation: Operation) {
    if (isCurrent(operation)) {
      return
    }
    val snapshot = operation.snapshot ?: return
    runCatching { snapshot.close() }
        .onSuccess {
          if (operation.snapshot === snapshot) {
            operation.snapshot = null
          }
        }
        .onFailure { LOG.warn("Unable to remove abandoned ROM snapshot", it) }
  }

  private fun submitLifecycle(action: () -> Unit) {
    try {
      executor.execute(action)
    } catch (_: RuntimeException) {
      if (!closed.get()) {
        Thread(action, "coffee-gb-rom-open-lifecycle").apply {
          isDaemon = true
          start()
        }
      }
    }
  }

  private class Operation(
      val id: Long,
      val request: RomOpenRequest,
  ) {
    val cancelled = AtomicBoolean()
    val superseded = AtomicBoolean()
    val terminal = AtomicBoolean()
    @Volatile var path: Path? = null
    @Volatile var snapshot: RomSourceSnapshot? = null
    @Volatile var future: Future<*>? = null
    @Volatile var controllerDispatched = false
    @Volatile var origin: RomOrigin? = null
    @Volatile var persistenceRequestId: Long? = null
    @Volatile var selectionSubmitted = false
    @Volatile private var lastCopyProgress = 0L

    fun shouldPublishCopyProgress(copiedBytes: Long): Boolean {
      if (copiedBytes - lastCopyProgress < COPY_PROGRESS_INTERVAL_BYTES) {
        return false
      }
      lastCopyProgress = copiedBytes
      return true
    }
  }

  private companion object {
    val LOG = LoggerFactory.getLogger(RomOpenService::class.java)
    const val NO_VISIBLE_REQUEST = -1L
    const val COPY_PROGRESS_INTERVAL_BYTES = 1024L * 1024L

    fun mapFailure(reason: RomSourceException.Reason): RomOpenFailureKind =
        when (reason) {
          RomSourceException.Reason.MISSING -> RomOpenFailureKind.MISSING
          RomSourceException.Reason.NOT_A_FILE -> RomOpenFailureKind.NOT_A_FILE
          RomSourceException.Reason.UNSUPPORTED_TYPE -> RomOpenFailureKind.UNSUPPORTED_TYPE
          RomSourceException.Reason.UNSUPPORTED_SEVEN_Z ->
              RomOpenFailureKind.UNSUPPORTED_SEVEN_Z
          RomSourceException.Reason.UNREADABLE -> RomOpenFailureKind.UNREADABLE
          RomSourceException.Reason.ROM_TOO_LARGE -> RomOpenFailureKind.LIMIT_EXCEEDED
          RomSourceException.Reason.CONTAINER_TOO_LARGE -> RomOpenFailureKind.LIMIT_EXCEEDED
          RomSourceException.Reason.INVALID_ARCHIVE -> RomOpenFailureKind.INVALID_ARCHIVE
          RomSourceException.Reason.UNSAFE_ARCHIVE_ENTRY ->
              RomOpenFailureKind.UNSAFE_ARCHIVE_ENTRY
          RomSourceException.Reason.NO_ROM_CANDIDATES ->
              RomOpenFailureKind.NO_ROM_CANDIDATES
          RomSourceException.Reason.INVALID_HEADER -> RomOpenFailureKind.INVALID_HEADER
          RomSourceException.Reason.INVALID_SELECTION ->
              RomOpenFailureKind.INVALID_ARCHIVE_SELECTION
        }

    fun friendlyMessage(failure: RomSourceException): String =
        when (failure.reason()) {
          RomSourceException.Reason.MISSING ->
              "The selected ROM no longer exists."
          RomSourceException.Reason.NOT_A_FILE ->
              "Select one ROM file or ZIP archive, not a directory."
          RomSourceException.Reason.UNSUPPORTED_TYPE ->
              "Coffee GB opens .gb, .gbc, .rom, and .zip files."
          RomSourceException.Reason.UNSUPPORTED_SEVEN_Z ->
              "7z cannot be opened safely. Extract the ROM or create a ZIP archive."
          RomSourceException.Reason.UNREADABLE ->
              "The selected ROM could not be read."
          RomSourceException.Reason.ROM_TOO_LARGE ->
              "The selected ROM exceeds Coffee GB's safety limit."
          RomSourceException.Reason.CONTAINER_TOO_LARGE ->
              "The selected archive exceeds Coffee GB's safety limits."
          RomSourceException.Reason.INVALID_ARCHIVE ->
              "The ZIP archive is invalid, corrupt, or exceeds a safety limit."
          RomSourceException.Reason.UNSAFE_ARCHIVE_ENTRY ->
              "The ZIP archive contains an unsafe entry path."
          RomSourceException.Reason.NO_ROM_CANDIDATES ->
              "The ZIP archive contains no supported ROM files."
          RomSourceException.Reason.INVALID_HEADER ->
              "The file does not contain a recognizable Game Boy cartridge header."
          RomSourceException.Reason.INVALID_SELECTION ->
              "The archive selection is no longer valid."
        }

    fun technicalDetails(failure: Throwable): String {
      val details = mutableListOf<String>()
      var current: Throwable? = failure
      while (current != null && details.size < 6) {
        val message =
            current.message
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.trim()
                ?.take(320)
                ?.takeIf(String::isNotEmpty)
        details +=
            if (message == null) current.javaClass.name
            else "${current.javaClass.name}: $message"
        current = current.cause
      }
      return details.joinToString("\nCaused by: ")
    }
  }
}

internal interface RomRecentStore {
  fun getPaths(): List<Path>

  fun recordSuccessfulOpen(path: Path)

  fun remove(path: Path)
}

private class PreferencesRomRecentStore(
    private val recentRoms: RecentRoms,
) : RomRecentStore {
  override fun getPaths(): List<Path> = recentRoms.getPaths()

  override fun recordSuccessfulOpen(path: Path) = recentRoms.recordSuccessfulOpen(path)

  override fun remove(path: Path) = recentRoms.remove(path)
}
