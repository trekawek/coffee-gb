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
    selectClock(clockSpec)
    if (++ticks < clockSpec.controllerTicksPerFrame()) {
      return
    }
    ticks = 0
    paceCompletedFrame()
  }

  /**
   * Accounts for one already-completed controller frame.
   *
   * Ordinary controller loops advance the machine in one tight frame-sized batch, so they do not
   * need to invoke the tick-granular entry point for every master tick. Debug continuations still
   * use [run], because they can stop at any master-tick boundary. Calling this method establishes
   * a frame boundary and discards any incomplete tick cadence left by a previous clock domain.
   */
  fun runFrame(clockSpec: ClockSpec) {
    if (disabled) {
      hasPacingDebt = false
      return
    }
    selectClock(clockSpec)
    ticks = 0
    paceCompletedFrame()
  }

  private fun selectClock(clockSpec: ClockSpec) {
    if (activeClock != clockSpec) {
      activeClock = clockSpec
      frameNanos = clockSpec.newFrameNanosecondAccumulator()
      ticks = 0
      deadline = nanoTime.asLong
      hasPacingDebt = false
    }
  }

  private fun paceCompletedFrame() {
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
    // Sleep the bulk of the wait and park the final stretch as well. Android API 26 does not
    // expose Java 9's onSpinWait; replacing it with Thread.yield caused a scheduler-yield storm
    // on slower devices. LockSupport.parkNanos remains available on API 26, does not allocate,
    // and the absolute deadline below preserves the existing cadence/debt semantics. Keep the
    // final wait bounded: a full-duration park oversleeps on some Android/desktop schedulers,
    // while a final park of at most 1.5 ms retains frame cadence without Thread.yield polling.
    // A normal frame therefore needs two park calls, not a repeated 1.5 ms scheduler storm.
    while (true) {
      val remaining = deadline - nanoTime.asLong
      if (remaining <= 0) {
        break
      }
      if (remaining > FINAL_PARK_THRESHOLD_NANOS) {
        parkNanos.accept(remaining - FINAL_PARK_THRESHOLD_NANOS)
      } else {
        parkNanos.accept(remaining)
      }
    }
  }

  private companion object {
    const val FINAL_PARK_THRESHOLD_NANOS = 1_500_000L
    const val MAX_CATCH_UP_NANOS = 50_000_000L
  }
}
