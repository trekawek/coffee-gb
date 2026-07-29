package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.SessionState
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusTeardownTimeoutException
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class Session(
    val config: Gameboy.GameboyConfiguration,
    val eventBus: EventBus,
    private val console: Console?,
    serialEndpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
    infraredEndpoint: InfraredEndpoint = InfraredEndpoint.NULL_ENDPOINT,
    prebuiltGameboy: Gameboy? = null,
    serialEndpointDisconnect: () -> Unit = {},
) : AutoCloseable {

  internal val gameboy: Gameboy = prebuiltGameboy ?: config.build()

  private val staged = eventBus is StagedEventBus

  private val cleanupScheduled = AtomicBoolean()

  @Volatile private var resourcesClosed = false

  @Volatile private var consoleAttached = !staged && console != null

  internal var serialEndpoint: SerialEndpoint = serialEndpoint
    private set

  /**
   * Controller-owned host/backend cleanup. It deliberately lives outside captured endpoint state
   * and runs immediately after core endpoint ownership has moved or been detached.
   */
  private var serialEndpointDisconnect: () -> Unit = serialEndpointDisconnect

  internal val infraredEndpoint: InfraredEndpoint = infraredEndpoint

  init {
    try {
      gameboy.init(eventBus, serialEndpoint, this.infraredEndpoint, if (staged) null else console)
    } catch (failure: Throwable) {
      disconnectSerialEndpointSafely(serialEndpoint, this.serialEndpointDisconnect)
      this.serialEndpoint = SerialEndpoint.NULL_ENDPOINT
      this.serialEndpointDisconnect = NO_SERIAL_ENDPOINT_DISCONNECT
      throw failure
    }
  }

  /**
   * Publishes a fully initialized candidate into the shared event tree after ownership commits.
   */
  internal fun activate() {
    if (!staged) {
      return
    }
    gameboy.attachConsole(console)
    consoleAttached = console != null
    (eventBus as StagedEventBus).activate()
  }

  /**
   * Hot-swaps the link-port device without a reset.
   *
   * The caller must prepare [endpoint] and its backend before entering this method. Installation
   * commits before the previous endpoint is disconnected, so a failed core handoff leaves the old
   * endpoint and controller selection usable. The disconnect callback must be idempotent and
   * non-blocking; failures are contained so teardown cannot strand a half-replaced session.
   */
  @Synchronized
  fun setSerialEndpoint(
      endpoint: SerialEndpoint,
      disconnect: () -> Unit = NO_SERIAL_ENDPOINT_DISCONNECT,
  ) {
    if (resourcesClosed) {
      disconnectSerialEndpointSafely(endpoint, disconnect)
      throw IllegalStateException("Cannot attach a serial endpoint to a closed session")
    }

    val previousEndpoint = serialEndpoint
    val previousDisconnect = serialEndpointDisconnect
    try {
      gameboy.setSerialEndpoint(endpoint)
    } catch (failure: Throwable) {
      try {
        gameboy.setSerialEndpoint(previousEndpoint)
      } catch (rollbackFailure: Throwable) {
        failure.addSuppressed(rollbackFailure)
      }
      disconnectSerialEndpointSafely(endpoint, disconnect)
      throw failure
    }
    serialEndpoint = endpoint
    serialEndpointDisconnect = disconnect
    disconnectSerialEndpointSafely(previousEndpoint, previousDisconnect)
  }

  /** Disconnects the selected device and leaves the emulated link port physically empty. */
  @Synchronized
  internal fun detachSerialEndpoint() {
    val previousEndpoint = serialEndpoint
    val previousDisconnect = serialEndpointDisconnect
    gameboy.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT)
    serialEndpoint = SerialEndpoint.NULL_ENDPOINT
    serialEndpointDisconnect = NO_SERIAL_ENDPOINT_DISCONNECT
    disconnectSerialEndpointSafely(previousEndpoint, previousDisconnect)
  }

  override fun close() {
    closeNonFinal(CleanupMode.FLUSH_CARTRIDGE)
  }

  internal fun closeAfterCartridgeFlush() {
    closeNonFinal(CleanupMode.AFTER_CARTRIDGE_FLUSH)
  }

  internal fun closeAfterCartridgeFlush(timeout: Long, unit: TimeUnit) {
    require(timeout > 0) { "Session close timeout must be positive" }
    val deadlineNanos = System.nanoTime() + unit.toNanos(timeout).coerceAtLeast(1)
    gameboy.stop()
    detachSerialEndpoint()
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0) {
      throw EventBusTeardownTimeoutException(
          "Controller close deadline expired before session event-bus teardown")
    }
    // The final controller close owns retry presentation, so retain the complete machine until
    // its bus has actually quiesced and propagate the bounded failure to that caller.
    eventBus.close(remainingNanos, TimeUnit.NANOSECONDS)
    finishCleanup(CleanupMode.AFTER_CARTRIDGE_FLUSH, detachConsole = true)
  }

  internal fun discardUnstarted() {
    closeNonFinal(CleanupMode.DISCARD_UNSTARTED)
  }

  private fun closeNonFinal(mode: CleanupMode) {
    gameboy.stop()
    detachSerialEndpoint()
    try {
      // No irreversible machine cleanup happens while an event subscriber can still be running.
      eventBus.close()
    } catch (failure: EventBusTeardownTimeoutException) {
      // Replacement/stop/discard are controller-internal boundaries. Once ownership has moved,
      // a tardy presentation subscriber must not roll the already prepared candidate back.
      LOG.warn(
          "Session event subscribers did not stop in time; deferring machine cleanup",
          failure,
      )
      scheduleDeferredCleanup(mode)
      return
    }
    finishCleanup(mode, detachConsole = true)
  }

  private fun scheduleDeferredCleanup(mode: CleanupMode) {
    if (!cleanupScheduled.compareAndSet(false, true)) {
      return
    }
    Thread(
            {
              while (!resourcesClosed) {
                try {
                  eventBus.close()
                  // A replacement may already have attached its candidate to the same console.
                  // The owner performs any safe console detach before returning to its caller.
                  finishCleanup(mode, detachConsole = false)
                  return@Thread
                } catch (failure: EventBusTeardownTimeoutException) {
                  LOG.debug("Still waiting for a session event subscriber to return", failure)
                } catch (failure: RuntimeException) {
                  LOG.error("Unable to finish deferred session cleanup", failure)
                  return@Thread
                }
              }
            },
            "coffee-gb-session-cleanup",
        )
        .apply {
          isDaemon = true
          start()
        }
  }

  @Synchronized
  private fun finishCleanup(mode: CleanupMode, detachConsole: Boolean) {
    if (resourcesClosed) {
      return
    }
    detachSerialEndpoint()
    when (mode) {
      CleanupMode.FLUSH_CARTRIDGE -> gameboy.closeSilently()
      CleanupMode.AFTER_CARTRIDGE_FLUSH -> gameboy.closeAfterCartridgeFlushSilently()
      CleanupMode.DISCARD_UNSTARTED -> gameboy.discardUnstarted()
    }
    resourcesClosed = true
    if (detachConsole && consoleAttached) {
      console?.setGameboy(null)
      consoleAttached = false
    }
  }

  // Held buttons live outside machine state (the joypad keeps physical input across a
  // single-player rewind); netplay snapshots them separately so a held button survives a rebase
  var heldButtons: Set<Button>
    get() = gameboy.legacyPressedButtons
    set(value) {
      gameboy.setPressedButtons(value)
    }

  /** Captures a detached, deeply owned session state at the controller frame safe point. */
  internal fun captureDetachedState(): SessionState = DetachedStateAdapter.capture(this)

  /** Applies a fully validated detached state or rolls the complete session back on failure. */
  internal fun restoreDetachedState(state: SessionState) = DetachedStateAdapter.apply(this, state)

  private enum class CleanupMode {
    FLUSH_CARTRIDGE,
    AFTER_CARTRIDGE_FLUSH,
    DISCARD_UNSTARTED,
  }

  private companion object {
    val LOG = LoggerFactory.getLogger(Session::class.java)

    val NO_SERIAL_ENDPOINT_DISCONNECT: () -> Unit = {}

    fun disconnectSerialEndpointSafely(
        endpoint: SerialEndpoint,
        disconnect: () -> Unit,
    ) {
      try {
        endpoint.disconnect()
      } catch (_: RuntimeException) {
        // A backend exception may carry a host, path, account, payload, or credential. Cleanup
        // diagnostics deliberately expose only this stable category.
        LOG.warn("Unable to disconnect a serial endpoint cleanly")
      }
      try {
        disconnect()
      } catch (_: RuntimeException) {
        LOG.warn("Unable to disconnect a serial endpoint backend cleanly")
      }
    }
  }
}

/** Module-private bridge that lets controller subpackages stage a candidate without exposing it. */
internal fun stagedEventBus(delegate: EventBus): EventBus = StagedEventBus(delegate)
