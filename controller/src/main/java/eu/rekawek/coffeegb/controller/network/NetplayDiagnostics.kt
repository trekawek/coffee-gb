package eu.rekawek.coffeegb.controller.network

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Listener for immutable, detached netplay diagnostic snapshots. */
fun interface NetplaySnapshotListener<T> {
  fun onSnapshot(snapshot: T)
}

/** Platform-neutral source used by controller, v9, and Swing presentation adapters. */
interface NetplaySnapshotSource<T> {
  fun snapshot(): T

  fun addListener(listener: NetplaySnapshotListener<T>): Closeable
}

/** Pure bounded numeric-address validation; it never performs DNS or interface lookup. */
internal object NetplayNumericAddress {
  fun isValid(value: String): Boolean {
    if (value.length !in 2..64) return false
    val address = value.substringBefore('%')
    val scope = value.substringAfter('%', "")
    if ('%' in value && (scope.length !in 1..16 || scope.any {
          !it.isLetterOrDigit() && it !in "._-"
        })) return false
    if ('.' in address && ':' !in address) {
      if ('%' in value) return false
      val octets = address.split('.')
      return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) &&
            (octet == "0" || !octet.startsWith('0')) &&
            octet.toIntOrNull() in 0..255
      }
    }
    if (':' !in address || address.any { !it.isDigit() && it !in ":abcdefABCDEF" }) return false
    if (address.count { it == ':' } < 2 || ":::" in address) return false
    val compressed = "::" in address
    if (!compressed && (address.startsWith(':') || address.endsWith(':'))) return false
    val pieces = address.split("::")
    if (pieces.size > 2) return false
    var groups = 0
    for (side in pieces) {
      if (side.isEmpty()) continue
      val values = side.split(':')
      if (values.any { it.length !in 1..4 }) return false
      groups += values.size
    }
    return if (compressed) groups < 8 else groups == 8
  }
}

/** Stable local-only rollback causes. Declaration order has no persisted or wire meaning. */
enum class NetplayRollbackReason(val stableId: String) {
  NONE("none"),
  REMOTE_INPUT("remote-input"),
  HISTORY_EXHAUSTED("history-exhausted"),
  CHECKPOINT("checkpoint"),
  RESYNCHRONIZATION("resynchronization"),
  RESET("reset"),
  TOPOLOGY_CHANGE("topology-change"),
}

/**
 * Immutable bounded rollback/history diagnostics. The rolling average is represented in
 * thousandths of a frame and is rounded down deterministically. Zero-rewind forward replays add
 * to re-simulated frames but are not counted as rollbacks or rolling rewind samples.
 */
data class NetplayRollbackMetricsSnapshot(
    val rollbackCount: Long,
    val lastFramesRewound: Long,
    val maximumFramesRewound: Long,
    val rollingAverageFramesRewoundMilli: Long,
    val totalFramesResimulated: Long,
    val historyEntries: Int,
    val historyCapacity: Int,
    val tooOldInputs: Long,
    val checkpointResynchronizations: Long,
    val lastReason: NetplayRollbackReason,
)

/** Bounded diagnostics tracker; no emulator state or replay decision depends on these counters. */
class NetplayRollbackMetrics(
    historyCapacity: Int,
) : NetplaySnapshotSource<NetplayRollbackMetricsSnapshot>, Closeable {
  private val lock = Any()
  private val samples = LongArray(ROLLING_SAMPLES)
  private var sampleIndex = 0
  private var sampleCount = 0
  private var sampleTotal = 0L
  private var rollbackCount = 0L
  private var lastFramesRewound = 0L
  private var maximumFramesRewound = 0L
  private var totalFramesResimulated = 0L
  private var historyEntries = 0
  private val historyCapacity = historyCapacity.also { require(it > 0) }
  private var tooOldInputs = 0L
  private var checkpointResynchronizations = 0L
  private var lastReason = NetplayRollbackReason.NONE
  private var closed = false
  private val publisher =
      BoundedSnapshotPublisher(snapshotLocked(), "netplay-rollback-diagnostics")

  fun recordRollback(framesRewound: Long, framesResimulated: Long) {
    require(framesRewound in 0..historyCapacity.toLong() && framesResimulated >= 0)
    val value = synchronized(lock) {
      if (closed) return
      totalFramesResimulated = saturatingAdd(totalFramesResimulated, framesResimulated)
      if (framesRewound > 0) {
        rollbackCount = saturatingAdd(rollbackCount, 1)
        lastFramesRewound = framesRewound
        maximumFramesRewound = maxOf(maximumFramesRewound, framesRewound)
        if (sampleCount == samples.size) {
          sampleTotal = (sampleTotal - samples[sampleIndex]).coerceAtLeast(0)
        } else {
          sampleCount++
        }
        samples[sampleIndex] = framesRewound
        sampleIndex = (sampleIndex + 1) % samples.size
        sampleTotal = saturatingAdd(sampleTotal, framesRewound)
      }
      lastReason = NetplayRollbackReason.REMOTE_INPUT
      snapshotLocked()
    }
    publisher.update(value)
  }

  fun recordHistoryExhausted() {
    val value = synchronized(lock) {
      if (closed) return
      tooOldInputs = saturatingAdd(tooOldInputs, 1)
      lastReason = NetplayRollbackReason.HISTORY_EXHAUSTED
      snapshotLocked()
    }
    publisher.update(value)
  }

  fun recordCheckpoint(reason: NetplayRollbackReason = NetplayRollbackReason.CHECKPOINT) {
    require(
        reason in
            setOf(
                NetplayRollbackReason.CHECKPOINT,
                NetplayRollbackReason.RESYNCHRONIZATION,
                NetplayRollbackReason.RESET,
                NetplayRollbackReason.TOPOLOGY_CHANGE,
            ),
    )
    val value = synchronized(lock) {
      if (closed) return
      checkpointResynchronizations = saturatingAdd(checkpointResynchronizations, 1)
      lastReason = reason
      snapshotLocked()
    }
    publisher.update(value)
  }

  fun updateHistory(entries: Int) {
    require(entries in 0..historyCapacity)
    val value = synchronized(lock) {
      if (closed) return
      historyEntries = entries
      snapshotLocked()
    }
    publisher.update(value)
  }

  override fun snapshot(): NetplayRollbackMetricsSnapshot = synchronized(lock) { snapshotLocked() }

  override fun addListener(
      listener: NetplaySnapshotListener<NetplayRollbackMetricsSnapshot>,
  ): Closeable = publisher.addListener(listener)

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
    }
    publisher.close()
  }

  internal fun retainedSampleCount(): Int = synchronized(lock) { sampleCount }

  internal fun seedCountersForTest(
      rollbacks: Long,
      resimulated: Long,
      tooOld: Long,
      checkpoints: Long,
  ) {
    require(rollbacks >= 0 && resimulated >= 0 && tooOld >= 0 && checkpoints >= 0)
    val value = synchronized(lock) {
      if (closed) return
      rollbackCount = rollbacks
      totalFramesResimulated = resimulated
      tooOldInputs = tooOld
      checkpointResynchronizations = checkpoints
      snapshotLocked()
    }
    publisher.update(value)
  }

  private fun snapshotLocked(): NetplayRollbackMetricsSnapshot {
    val average =
        if (sampleCount == 0) 0
        else if (sampleTotal > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE
        else sampleTotal * 1_000L / sampleCount
    return NetplayRollbackMetricsSnapshot(
        rollbackCount,
        lastFramesRewound,
        maximumFramesRewound,
        average,
        totalFramesResimulated,
        historyEntries,
        historyCapacity,
        tooOldInputs,
        checkpointResynchronizations,
        lastReason,
    )
  }

  companion object {
    const val ROLLING_SAMPLES = 64

    internal fun saturatingAdd(left: Long, right: Long): Long {
      require(left >= 0 && right >= 0)
      return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }
  }
}

/**
 * Latest-value publisher with one retained snapshot, at most 16 observers, and one lazily-created
 * daemon. Producers only replace/offer a value and therefore never wait for presentation code.
 */
internal class BoundedSnapshotPublisher<T>(
    initial: T,
    private val threadName: String,
) : NetplaySnapshotSource<T>, Closeable {
  private val current = AtomicReference(initial)
  private val queue = ArrayBlockingQueue<T>(1)
  private val closed = AtomicBoolean(false)
  private val listenerLock = Any()
  private val listeners = LinkedHashSet<NetplaySnapshotListener<T>>()
  private val terminated = CountDownLatch(1)
  private var worker: Thread? = null

  override fun snapshot(): T = current.get()

  override fun addListener(listener: NetplaySnapshotListener<T>): Closeable {
    synchronized(listenerLock) {
      check(!closed.get()) { "netplay diagnostics source is closed" }
      check(listeners.size < MAX_LISTENERS) { "netplay diagnostics listener limit reached" }
      listeners += listener
      if (worker == null) {
        worker = thread(isDaemon = true, name = threadName, block = ::dispatchLoop)
      }
    }
    offerLatest(current.get())
    val active = AtomicBoolean(true)
    return Closeable {
      if (active.compareAndSet(true, false)) {
        synchronized(listenerLock) { listeners.remove(listener) }
      }
    }
  }

  fun update(value: T) {
    if (closed.get()) return
    current.set(value)
    val observed = synchronized(listenerLock) { listeners.isNotEmpty() }
    if (observed) offerLatest(value)
  }

  private fun offerLatest(value: T) {
    if (closed.get()) return
    if (!queue.offer(value)) {
      queue.poll()
      queue.offer(value)
    }
  }

  private fun dispatchLoop() {
    try {
      while (true) {
        val value = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (value == null) {
          if (closed.get()) return
          continue
        }
        val snapshot = synchronized(listenerLock) { listeners.toList() }
        snapshot.forEach { listener ->
          try {
            listener.onSnapshot(value)
          } catch (_: RuntimeException) {
            // A presentation observer cannot affect networking or emulation ownership.
          }
        }
        if (closed.get() && queue.isEmpty()) return
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } finally {
      synchronized(listenerLock) {
        listeners.clear()
        worker = null
      }
      terminated.countDown()
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(listenerLock) {
      if (worker == null) {
        listeners.clear()
        terminated.countDown()
      } else if (!queue.offer(current.get())) {
        queue.poll()
        queue.offer(current.get())
      }
    }
  }

  internal fun activeWorkerCount(): Int =
      synchronized(listenerLock) { if (worker?.isAlive == true) 1 else 0 }

  internal fun activeListenerCount(): Int = synchronized(listenerLock) { listeners.size }

  internal fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
      terminated.await(timeout, unit)

  companion object {
    const val MAX_LISTENERS = 16
  }
}
