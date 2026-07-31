package eu.rekawek.coffeegb.swing

import java.util.concurrent.TimeUnit

/** Monotonic, allocation-free cadence gate for expensive realtime debugger panes. */
internal class DebuggerHeavyPaneCadence(
    intervalMillis: Long,
) {
  private val intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis)
  private var nextDeadlineNanos: Long? = null

  init {
    require(intervalMillis > 0) { "Heavy-pane cadence interval must be positive" }
  }

  /** Returns true for the first request and once per interval thereafter. */
  fun acquire(nowNanos: Long): Boolean {
    val deadline = nextDeadlineNanos
    if (deadline != null && nowNanos - deadline < 0L) return false
    nextDeadlineNanos = nowNanos + intervalNanos
    return true
  }

  /** Makes the next interested request immediate, for example after attach or un-hold. */
  fun reset() {
    nextDeadlineNanos = null
  }
}
