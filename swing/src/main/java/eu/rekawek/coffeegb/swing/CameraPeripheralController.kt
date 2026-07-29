package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import org.slf4j.LoggerFactory

internal sealed interface CameraPeripheralUiState {
  object Opening : CameraPeripheralUiState

  object Enabled : CameraPeripheralUiState

  object Disabled : CameraPeripheralUiState

  object OpenFailed : CameraPeripheralUiState
}

/**
 * Owns the asynchronous desktop-camera lifecycle.
 *
 * Native discovery, device open, and device close are confined to one daemon worker. An operation
 * token and a synchronized source claim ensure that only the current request can publish to the
 * emulator. Cancellation or disposal closes every stale source on a worker before it can escape.
 */
internal class CameraPeripheralController<T : CameraSource>(
    private val opener: (Int) -> T?,
    initialDeviceIndex: Int = 0,
    private val sourceCloser: (T) -> Unit,
    private val publisher: (CameraSource?) -> Unit,
    private val stateConsumer: (CameraPeripheralUiState) -> Unit,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
          Thread(task, "camera-device").also { it.isDaemon = true }
        },
    private val uiDispatcher: (() -> Unit) -> Unit = { action ->
      SwingUtilities.invokeLater(action)
    },
    private val edtOwnership: () -> Boolean = SwingUtilities::isEventDispatchThread,
) : Closeable {

  init {
    require(initialDeviceIndex >= 0) { "camera device index must not be negative" }
  }

  private val stateLock = Any()

  private var operation = 0L

  private var deviceIndex = initialDeviceIndex

  private var desired = false

  private var closed = false

  private var active: T? = null

  private var pending: Future<*>? = null

  fun requestEnabled(enabled: Boolean) {
    requireEdt()
    if (!enabled) {
      disable()
      return
    }

    startOpening(failIfClosed = true)
  }

  /** Selects a source without enabling a disabled camera; an active source is replaced safely. */
  fun selectDevice(deviceIndex: Int) {
    requireEdt()
    require(deviceIndex >= 0) { "camera device index must not be negative" }
    val restart =
        synchronized(stateLock) {
          if (closed || this.deviceIndex == deviceIndex) return
          this.deviceIndex = deviceIndex
          desired
        }
    if (restart) {
      // close() is intentionally allowed off the EDT. If it wins this race, selection is already
      // terminal and this internal restart becomes a no-op rather than surfacing an Apply failure.
      startOpening(failIfClosed = false)
    }
  }

  private fun startOpening(failIfClosed: Boolean) {
    val current: Long
    val requestedDeviceIndex: Int
    val previous: T?
    synchronized(stateLock) {
      if (closed) {
        check(!failIfClosed) { "camera peripheral controller is closed" }
        return
      }
      operation++
      current = operation
      requestedDeviceIndex = deviceIndex
      desired = true
      pending?.cancel(true)
      pending = null
      previous = active
      active = null
    }
    if (previous != null) {
      publisher(null)
      closeAsync(previous)
    }

    val submitted =
        try {
          executor.submit { openOnWorker(current, requestedDeviceIndex) }
        } catch (_: RejectedExecutionException) {
          failSubmission(current)
          return
        }
    synchronized(stateLock) {
      if (!closed && desired && operation == current) {
        pending = submitted
      } else {
        submitted.cancel(true)
      }
    }
    if (isCurrent(current)) {
      stateConsumer(CameraPeripheralUiState.Opening)
    }
  }

  private fun disable() {
    val source: T?
    synchronized(stateLock) {
      if (closed) return
      operation++
      desired = false
      pending?.cancel(true)
      pending = null
      source = active
      active = null
    }
    publisher(null)
    source?.let(::closeAsync)
    stateConsumer(CameraPeripheralUiState.Disabled)
  }

  private fun openOnWorker(expectedOperation: Long, requestedDeviceIndex: Int) {
    check(!SwingUtilities.isEventDispatchThread()) { "camera open must not run on the EDT" }
    val source =
        try {
          opener(requestedDeviceIndex)
        } catch (failure: Throwable) {
          LOG.warn("Failed to open the camera peripheral", failure)
          null
        }

    if (source == null) {
      publishFailure(expectedOperation)
      return
    }

    val accepted =
        synchronized(stateLock) {
          if (!closed && desired && operation == expectedOperation) {
            active = source
            true
          } else {
            false
          }
        }
    if (!accepted) {
      closeOnCurrentWorker(source)
      return
    }
    uiDispatcher {
      val current =
          synchronized(stateLock) {
            val matches =
                !closed &&
                    desired &&
                    operation == expectedOperation &&
                    active === source
            if (matches) pending = null
            matches
          }
      if (current) {
        publisher(source)
        stateConsumer(CameraPeripheralUiState.Enabled)
      }
    }
  }

  private fun publishFailure(expectedOperation: Long) {
    uiDispatcher {
      val current =
          synchronized(stateLock) {
            val matches = !closed && desired && operation == expectedOperation
            if (matches) {
              desired = false
              pending = null
            }
            matches
          }
      if (current) {
        publisher(null)
        stateConsumer(CameraPeripheralUiState.OpenFailed)
      }
    }
  }

  private fun failSubmission(expectedOperation: Long) {
    val current =
        synchronized(stateLock) {
          val matches = !closed && desired && operation == expectedOperation
          if (matches) desired = false
          matches
        }
    if (current) {
      publisher(null)
      stateConsumer(CameraPeripheralUiState.OpenFailed)
    }
  }

  private fun closeAsync(source: T) {
    try {
      executor.execute { closeOnCurrentWorker(source) }
    } catch (_: RejectedExecutionException) {
      Thread(
              { closeOnCurrentWorker(source) },
              "camera-device-cleanup",
          )
          .also { it.isDaemon = true }
          .start()
    }
  }

  private fun closeOnCurrentWorker(source: T) {
    check(!SwingUtilities.isEventDispatchThread()) { "camera close must not run on the EDT" }
    try {
      sourceCloser(source)
    } catch (failure: RuntimeException) {
      LOG.warn("Failed to close the camera peripheral", failure)
    }
  }

  private fun isCurrent(expectedOperation: Long): Boolean =
      synchronized(stateLock) { !closed && desired && operation == expectedOperation }

  override fun close() {
    val source: T?
    synchronized(stateLock) {
      if (closed) return
      closed = true
      operation++
      desired = false
      pending?.cancel(true)
      pending = null
      source = active
      active = null
    }
    try {
      if (edtOwnership()) {
        publisher(null)
      } else {
        uiDispatcher { publisher(null) }
      }
    } finally {
      try {
        source?.let(::closeAsync)
      } finally {
        executor.shutdown()
      }
    }
  }

  fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
    check(!edtOwnership()) { "camera worker must not be awaited on the EDT" }
    return executor.awaitTermination(timeout, unit)
  }

  private fun requireEdt() {
    check(edtOwnership()) { "camera UI requests must run on the EDT" }
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(CameraPeripheralController::class.java)
  }
}

/**
 * Starts one non-blocking camera close and bounds every wait against the caller's remaining
 * shutdown allocation. A timeout keeps the close claimed so a retry only awaits the same worker.
 */
internal class BoundedCameraShutdown(
    private val close: () -> Unit,
    private val awaitTermination: (Long, TimeUnit) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val edtOwnership: () -> Boolean = SwingUtilities::isEventDispatchThread,
) {
  private val closeRequested = AtomicBoolean()

  @Throws(IOException::class)
  fun closeAndAwait(timeoutMillis: Long) {
    require(timeoutMillis > 0) { "Camera shutdown timeout must be positive" }
    check(!edtOwnership()) { "camera worker must not be awaited on the EDT" }
    val budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val started = nanoTime()
    if (closeRequested.compareAndSet(false, true)) {
      try {
        close()
      } catch (failure: Exception) {
        closeRequested.set(false)
        throw failure
      }
    }
    val elapsed = (nanoTime() - started).coerceAtLeast(0)
    val remaining = budgetNanos - elapsed
    val completed =
        try {
          remaining > 0 && awaitTermination(remaining, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("Camera shutdown was interrupted", interrupted)
        }
    if (!completed) {
      throw IOException("Camera shutdown exceeded $timeoutMillis ms")
    }
  }
}
