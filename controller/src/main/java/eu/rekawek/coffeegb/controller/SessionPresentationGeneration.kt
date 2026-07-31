package eu.rekawek.coffeegb.controller

import java.util.concurrent.atomic.AtomicLong

/** Process-wide identity for lifecycle events that can outlive their controller on a UI queue. */
internal object SessionPresentationGeneration {
  private val counter = AtomicLong()

  fun next(): Long = counter.updateAndGet { current -> Math.addExact(current, 1L) }
}
