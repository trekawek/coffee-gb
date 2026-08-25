package eu.rekawek.coffeegb.controller

import java.util.function.BooleanSupplier

/**
 * Owner-thread stop gate shared by measured controller execution and disposable warmup.
 *
 * The value is deliberately non-volatile: both callers read and write it on their owning
 * emulation thread, and the Java BooleanSupplier shape keeps the stop predicate identical at
 * both call sites.
 */
internal class BenchmarkCoreFrozenGate : BooleanSupplier {
  private var frozen = false

  override fun getAsBoolean(): Boolean = frozen

  fun setFrozen(nextFrozen: Boolean) {
    frozen = nextFrozen
  }
}
