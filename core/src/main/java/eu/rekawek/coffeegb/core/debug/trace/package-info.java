/**
 * Detached typed trace payloads and an owner-thread-confined bounded history ring.
 *
 * <p>The package contains no live emulator references. Transports configure and read the ring at
 * emulation safe points, then expose only immutable request/result values to clients.
 */
package eu.rekawek.coffeegb.core.debug.trace;
