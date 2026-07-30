/**
 * Detached breakpoint/watchpoint definitions and allocation-free matching primitives.
 *
 * <p>This package is intentionally independent of live emulator objects. Debug-port transports
 * store these immutable values and owner-thread instrumentation feeds primitive observations to
 * the matcher without exposing mutable core state.
 */
package eu.rekawek.coffeegb.core.debug.breakpoint;
