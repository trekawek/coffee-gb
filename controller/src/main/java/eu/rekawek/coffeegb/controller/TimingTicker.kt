package eu.rekawek.coffeegb.controller

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import java.util.concurrent.locks.LockSupport
import java.util.function.LongConsumer
import java.util.function.LongSupplier

class TimingTicker
@VisibleForTesting
internal constructor(
    private val nanoTime: LongSupplier,
    private val parkNanos: LongConsumer,
) : Runnable {
  constructor() : this(LongSupplier(System::nanoTime), LongConsumer(LockSupport::parkNanos))

  private var deadline = nanoTime.asLong
  private var ticks: Long = 0
  private var activeClock = ClockSpec.LEGACY
  private var frameNanos = activeClock.newFrameNanosecondAccumulator()
  @VisibleForTesting
  var disabled = false

  @VisibleForTesting
  internal var completedFrames = 0L
    private set

  /**
   * Whether the last completed controller cadence arrived after its pacing deadline.
   *
   * This is intentionally sampled only at the existing cadence boundary: querying it from a
   * controller does not introduce a clock read, allocation, or per-master-tick work.
   */
  @VisibleForTesting
  internal var hasPacingDebt = false
    private set

  override fun run() {
    run(ClockSpec.LEGACY)
  }

  fun run(clockSpec: ClockSpec) {
    if (disabled) {
      hasPacingDebt = false
      return
    }
    if (activeClock != clockSpec) {
      activeClock = clockSpec
      frameNanos = clockSpec.newFrameNanosecondAccumulator()
      ticks = 0
      deadline = nanoTime.asLong
      hasPacingDebt = false
    }
    if (++ticks < clockSpec.controllerTicksPerFrame()) {
      return
    }
    ticks = 0
    val frameDurationNanos = frameNanos.advance(1)
    completedFrames++
    deadline =
        try {
          Math.addExact(deadline, frameDurationNanos)
        } catch (_: ArithmeticException) {
          nanoTime.asLong
        }
    val now = nanoTime.asLong
    hasPacingDebt = now > deadline
    if (now - deadline > MAX_CATCH_UP_NANOS) {
      // Preserve short scheduling/GC delays so subsequent frames can repay them instead of
      // permanently losing emulated audio time. A long pause retains only a small bounded debt:
      // this avoids a prolonged burst of unpaced execution after a breakpoint or suspended host.
      deadline = now - MAX_CATCH_UP_NANOS
    }
    // Sleep the bulk of the wait and yield only the last stretch: parkNanos wakes with
    // millisecond-ish slack depending on the OS timer, and yielding keeps fine-grained pacing
    // without relying on Java 9's unavailable spin-wait hint or pegging a core for the whole frame.
    while (true) {
      val remaining = deadline - nanoTime.asLong
      if (remaining <= 0) {
        break
      }
      if (remaining > SPIN_THRESHOLD_NANOS) {
        parkNanos.accept(remaining - SPIN_THRESHOLD_NANOS)
      } else {
        Thread.yield()
      }
    }
  }

  private companion object {
    const val SPIN_THRESHOLD_NANOS = 1_500_000L
    const val MAX_CATCH_UP_NANOS = 50_000_000L
  }
}
