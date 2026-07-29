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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

  data class RemoteUrl(val description: String) : RomOpenInput {
    override fun toString(): String = "RemoteUrl(<redacted>)"
  }

  data class Rejected(val failure: RomOpenFailure) : RomOpenInput
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
  INPUT_LIMIT_EXCEEDED,
  SHUTTING_DOWN,
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

  @Volatile private var quiesced = false

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
        // EmulationStarted is the controller's synchronous ownership-commit acknowledgement.
        // Claim it before queueing preferences work so a controller transition cannot abandon an
        // already-committed open merely because the single disk worker is busy.
        val operation = claimSuccessfulTerminal(id) ?: return@register
        val path = operation.path
        val origin = event.origin ?: operation.origin
        submitLifecycle {
          // The controller event and this disk/preferences work are intentionally separated.
          if (path == null || origin == null) {
            LOG.error("Committed ROM-open request {} is missing its source identity", id)
            cleanupAbandonedSnapshot(operation)
            return@submitLifecycle
          }
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
    eventBus.register<ControllerOwnershipChangingEvent> {
      abandonForControllerTransition()
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
                        redactRomOpenTechnicalDetails(
                            event.technicalDetails,
                            operation.path,
                        ),
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
    // Snapshot and cap caller-owned input before the asynchronous worker observes it. Keeping two
    // entries preserves the typed MULTIPLE_INPUTS result without retaining an arbitrary external
    // collection supplied by drag/drop or an OS open-file callback.
    val boundedRequest =
        RomOpenRequest(
            request.inputs.asSequence().take(MAX_EXTERNAL_INPUTS).toList(),
            request.source,
        )
    val operation = Operation(nextRequestId.getAndIncrement(), boundedRequest)
    var unavailable = false
    val prior =
        synchronized(lock) {
          check(!closed.get()) { "ROM-open service is closed" }
          visibleRequestId.set(operation.id)
          if (quiesced) {
            operation.terminal.set(true)
            unavailable = true
            return@synchronized null
          }
          val previous = active
          previous?.also {
            it.superseded.set(true)
            it.cancelled.set(true)
            it.future?.cancel(true)
          }
          active = operation
          previous
        }
    if (unavailable) {
      publish(
          operation,
          RomOpenUpdate.Failed(
              operation.id,
              operation.request.source,
              null,
              RomOpenFailure(
                  RomOpenFailureKind.SHUTTING_DOWN,
                  "Coffee GB is paused for a retained quit attempt. Close again to retry.",
                  "ROM-open service is temporarily quiesced for bounded shutdown",
              ),
          ))
      return operation.id
    }
    prior?.let { superseded ->
      try {
        executor.execute { cleanupSuperseded(superseded) }
      } catch (failure: RuntimeException) {
        Thread(
                { cleanupSuperseded(superseded) },
                "coffee-gb-rom-open-superseded-cleanup",
            )
            .apply {
              isDaemon = true
              start()
            }
        if (!closed.get()) {
          throw failure
        }
      }
    }
    // QUEUED is enqueued before any worker can publish SNAPSHOTTING or later stages.
    publish(
        operation,
        RomOpenUpdate.Progress(
            operation.id,
            request.source,
            null,
            RomOpenStage.QUEUED,
        ))
    synchronized(lock) {
      if (isCurrentLocked(operation)) {
        operation.future = executor.submit { prepare(operation) }
      }
    }
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

  internal fun ownsVisibleRequest(requestId: Long): Boolean =
      !closed.get() && visibleRequestId.get() == requestId

  internal fun hasActiveRequest(): Boolean =
      synchronized(lock) { active != null && !closed.get() }

  /**
   * Synchronously detaches an uncommitted request before Swing replaces the controller that owns
   * its correlated lifecycle events. Disk cleanup remains off the transition callback.
   */
  internal fun abandonForControllerTransition() {
    visibleRequestId.set(NO_VISIBLE_REQUEST)
    val operation =
        synchronized(lock) {
          val current = active ?: return@synchronized null
          current.superseded.set(true)
          current.cancelled.set(true)
          current.terminal.set(true)
          active = null
          current
        } ?: return
    operation.future?.cancel(true)
    if (operation.controllerDispatched) {
      eventBus.post(Controller.CancelRomOpenEvent(operation.id))
    }
    submitLifecycle { cleanupAbandonedSnapshot(operation) }
  }

  /**
   * Reversibly prevents new controller dispatches and drains cancellation/temporary-file cleanup.
   *
   * Unlike [close], this keeps the worker and event subscriptions available for a shutdown retry
   * or a retained window. The desktop must call [close] only after emulator teardown succeeds.
   */
  internal fun quiesce() {
    if (closed.get()) {
      return
    }
    visibleRequestId.set(NO_VISIBLE_REQUEST)
    val operation =
        synchronized(lock) {
          if (closed.get()) {
            return
          }
          quiesced = true
          val current = active
          current?.also {
            it.superseded.set(true)
            it.cancelled.set(true)
          }
          active = null
          current
        }
    operation?.future?.cancel(true)
    val completion = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val drain =
        Runnable {
          try {
            cancelControllerAndCleanup(operation)
          } catch (problem: Throwable) {
            failure.set(problem)
          } finally {
            completion.countDown()
          }
        }
    try {
      executor.execute(drain)
    } catch (_: RuntimeException) {
      Thread(drain, "coffee-gb-rom-open-quiesce").apply {
        isDaemon = true
        start()
      }
    }
    try {
      if (!completion.await(QUIESCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw IllegalStateException(
            "ROM-open work did not quiesce within $QUIESCE_TIMEOUT_MILLIS ms")
      }
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Interrupted while quiescing ROM-open work", interrupted)
    }
    failure.get()?.let { problem ->
      throw IllegalStateException("Unable to quiesce ROM-open work cleanly", problem)
    }
  }

  /** Re-enables requests after a retained shutdown attempt once its worker has fully unwound. */
  internal fun resume() {
    synchronized(lock) {
      if (!closed.get()) {
        quiesced = false
      }
    }
  }

  internal fun isQuiesced(): Boolean = synchronized(lock) { quiesced && !closed.get() }

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
    val cleanupFailure = AtomicReference<Throwable?>()
    val cleanup =
        Thread(
                {
                  val failures = mutableListOf<Throwable>()
                  try {
                    if (operation?.controllerDispatched == true) {
                      if (!operation.controllerDispatchComplete.await(
                          CONTROLLER_DISPATCH_CLOSE_TIMEOUT_MILLIS,
                          TimeUnit.MILLISECONDS,
                      )) {
                        throw IllegalStateException(
                            "Controller ROM dispatch did not finish within " +
                                "$CONTROLLER_DISPATCH_CLOSE_TIMEOUT_MILLIS ms")
                      }
                      eventBus.post(Controller.CancelRomOpenEvent(operation.id))
                    }
                  } catch (failure: Throwable) {
                    failures += failure
                  }
                  try {
                    ownedExecutor?.let { worker ->
                      worker.shutdownNow()
                      if (!worker.awaitTermination(
                          WORKER_CLOSE_TIMEOUT_MILLIS,
                          TimeUnit.MILLISECONDS,
                      )) {
                        throw IllegalStateException(
                            "ROM-open worker did not stop within " +
                                "$WORKER_CLOSE_TIMEOUT_MILLIS ms")
                      }
                    }
                  } catch (failure: Throwable) {
                    failures += failure
                  }
                  try {
                    operation?.snapshot?.close()
                    operation?.snapshot = null
                  } catch (failure: Throwable) {
                    failures += failure
                  }
                  failures.firstOrNull()?.let { primary ->
                    failures.drop(1).forEach(primary::addSuppressed)
                    cleanupFailure.set(primary)
                  }
                },
                "coffee-gb-rom-open-close",
            )
            .apply {
              isDaemon = true
              start()
            }
    try {
      cleanup.join(CLOSE_TIMEOUT_MILLIS)
    } catch (failure: InterruptedException) {
      cleanup.interrupt()
      Thread.currentThread().interrupt()
      throw IllegalStateException("Interrupted while closing ROM-open service", failure)
    }
    if (cleanup.isAlive) {
      cleanup.interrupt()
      throw IllegalStateException(
          "ROM-open cleanup did not finish within $CLOSE_TIMEOUT_MILLIS ms")
    }
    cleanupFailure.get()?.let { failure ->
      throw IllegalStateException("Unable to close ROM-open service cleanly", failure)
    }
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
    if (input is RomOpenInput.Rejected) {
      fail(
          operation,
          input.failure.kind,
          input.failure.message,
          input.failure.technicalDetails,
      )
      return
    }
    if (input is RomOpenInput.RemoteUrl) {
      fail(
          operation,
          RomOpenFailureKind.REMOTE_URL,
          "Coffee GB opens local files only; URLs are not downloaded.",
          remoteInputTechnicalDetails(input.description),
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
          technicalDetails(failure, operation.path),
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
          technicalDetails(failure, operation.path),
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
    try {
      eventBus.post(
          Controller.LoadRomEvent(
              image,
              state = null,
              openRequestId = operation.id,
          ))
    } finally {
      operation.controllerDispatchComplete.countDown()
    }
  }

  private fun fail(operation: Operation, failure: RomSourceException) {
    fail(
        operation,
        mapFailure(failure.reason()),
        friendlyMessage(failure),
        technicalDetails(failure, operation.path),
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
    val cancellationSensitive = update is RomOpenUpdate.Progress
    if (!mayPublish(operation) || (cancellationSensitive && operation.cancelled.get())) {
      return
    }
    val sequence = operation.nextUpdateSequence.incrementAndGet()
    uiExecutor.execute {
      if (mayPublish(operation) &&
          (!cancellationSensitive || !operation.cancelled.get()) &&
          operation.claimDelivery(sequence)) {
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

  private fun claimSuccessfulTerminal(requestId: Long): Operation? =
      synchronized(lock) {
        val operation =
            active?.takeIf {
              it.id == requestId &&
                  !it.superseded.get() &&
                  !closed.get()
            } ?: return@synchronized null
        // EmulationStarted is the controller's ownership-commit acknowledgement. A cancellation
        // requested before this lifecycle worker ran can no longer roll that commit back; claim
        // success and let the already-queued controller cancellation become a stale no-op.
        if (!operation.terminal.compareAndSet(false, true)) {
          return@synchronized null
        }
        active = null
        operation
      }

  private inline fun withCurrent(requestId: Long, action: (Operation) -> Unit) {
    current(requestId)?.takeUnless { it.cancelled.get() }?.let(action)
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

  private fun cancelControllerAndCleanup(operation: Operation?) {
    if (operation == null) {
      return
    }
    var failure: Throwable? = null
    try {
      if (operation.controllerDispatched) {
        if (!operation.controllerDispatchComplete.await(
            CONTROLLER_DISPATCH_CLOSE_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS,
        )) {
          throw IllegalStateException(
              "Controller ROM dispatch did not finish within " +
                  "$CONTROLLER_DISPATCH_CLOSE_TIMEOUT_MILLIS ms")
        }
        eventBus.post(Controller.CancelRomOpenEvent(operation.id))
      }
    } catch (problem: Throwable) {
      if (problem is InterruptedException) {
        Thread.currentThread().interrupt()
      }
      failure = problem
    }
    try {
      operation.snapshot?.close()
      operation.snapshot = null
    } catch (cleanupProblem: Throwable) {
      failure?.addSuppressed(cleanupProblem) ?: run { failure = cleanupProblem }
    }
    failure?.let { throw it }
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
    val controllerDispatchComplete = CountDownLatch(1)
    @Volatile var origin: RomOrigin? = null
    @Volatile var persistenceRequestId: Long? = null
    @Volatile var selectionSubmitted = false
    @Volatile private var lastCopyProgress = 0L
    val nextUpdateSequence = AtomicLong()
    private val deliveredUpdateSequence = AtomicLong()

    fun shouldPublishCopyProgress(copiedBytes: Long): Boolean {
      if (copiedBytes - lastCopyProgress < COPY_PROGRESS_INTERVAL_BYTES) {
        return false
      }
      lastCopyProgress = copiedBytes
      return true
    }

    fun claimDelivery(sequence: Long): Boolean {
      while (true) {
        val delivered = deliveredUpdateSequence.get()
        if (sequence <= delivered) {
          return false
        }
        if (deliveredUpdateSequence.compareAndSet(delivered, sequence)) {
          return true
        }
      }
    }
  }

  private companion object {
    val LOG = LoggerFactory.getLogger(RomOpenService::class.java)
    const val NO_VISIBLE_REQUEST = -1L
    const val MAX_EXTERNAL_INPUTS = 2
    const val COPY_PROGRESS_INTERVAL_BYTES = 1024L * 1024L
    const val CONTROLLER_DISPATCH_CLOSE_TIMEOUT_MILLIS = 1_000L
    const val QUIESCE_TIMEOUT_MILLIS = 5_000L
    const val WORKER_CLOSE_TIMEOUT_MILLIS = 3_000L
    const val CLOSE_TIMEOUT_MILLIS = 5_000L

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
              "The file does not contain a complete Game Boy ROM header."
          RomSourceException.Reason.INVALID_SELECTION ->
              "The archive selection is no longer valid."
        }

    fun technicalDetails(failure: Throwable, sourcePath: Path?): String {
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
            else {
              "${current.javaClass.name}: " +
                  redactRomOpenTechnicalDetails(message, sourcePath)
            }
        current = current.cause
      }
      return details.joinToString("\nCaused by: ")
    }
  }
}

internal fun redactRomOpenTechnicalDetails(value: String, sourcePath: Path?): String {
  val withoutRemoteUrls =
      value.replace(
          Regex("(?i)\\b[a-z][a-z0-9+.-]{0,15}://[^\\s]+"),
          "<redacted-remote-url>",
      )
  val directories =
      buildList {
            sourcePath
                ?.toAbsolutePath()
                ?.normalize()
                ?.parent
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            runCatching { Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() }
                .getOrNull()
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
            runCatching { Path.of(System.getProperty("user.home")).toAbsolutePath().normalize() }
                .getOrNull()
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
          }
          .distinct()
          .sortedByDescending(String::length)
  val withoutKnownDirectories = directories.fold(withoutRemoteUrls) { redacted, directory ->
    redacted.replace(directory, "<redacted-directory>")
  }
  // Controller/plugin failures can mention a Datel slot ROM, recovery file, or other absolute
  // path unrelated to the selected ROM. A path may contain unquoted spaces, so redact from the
  // first Unix, UNC, or drive root through the end of that diagnostic line. Losing a harmless
  // suffix is preferable to exposing the remainder of a private path.
  val absolutePathStart =
      Regex(
          """(?<!<redacted-directory>)(?<![A-Za-z0-9])(?:[A-Za-z]:[\\/]|[/\\]{2}|/)""")
  return withoutKnownDirectories.lineSequence().joinToString("\n") { line ->
    absolutePathStart.find(line)?.let { match ->
      line.substring(0, match.range.first) + "<redacted-path>"
    } ?: line
  }
}

private fun remoteInputTechnicalDetails(value: String): String {
  val scheme =
      runCatching { java.net.URI(value).scheme }
          .getOrNull()
          ?.lowercase()
          ?.takeIf { it.matches(Regex("[a-z][a-z0-9+.-]{0,15}")) }
          ?: "remote"
  return "Rejected $scheme URL; address and credentials redacted"
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
