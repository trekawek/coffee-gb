package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

inline fun <reified E : Event> EventBus.register(subscriber: Subscriber<E>) {
  register(subscriber, E::class.java)
}

fun funnel(from: EventBus, to: EventBus, eventTypes: Set<KClass<out Event>>) {
  eventTypes.forEach { et -> from.register({ event -> to.post(event) }, et.java) }
}

/**
 * Keeps a machine's inbound event bus isolated while owning the shared-tree fork used for its
 * selected presentation output. Closing the returned bus closes both sides within one deadline.
 */
fun owningFunnel(
    from: EventBus,
    to: EventBus,
    eventTypes: Set<KClass<out Event>>,
): EventBus {
  funnel(from, to, eventTypes)
  return OwningFunnelEventBus(from, to)
}

private class OwningFunnelEventBus(
    private val inbound: EventBus,
    private val outbound: EventBus,
) : EventBus by inbound {

  override fun close() {
    close(1, TimeUnit.SECONDS)
  }

  override fun close(timeout: Long, unit: TimeUnit) {
    require(timeout > 0) { "Event bus close timeout must be positive" }
    val deadline = System.nanoTime() + unit.toNanos(timeout).coerceAtLeast(1)
    var failure: RuntimeException? = null
    for (bus in listOf(inbound, outbound)) {
      val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
      try {
        bus.close(remaining, TimeUnit.NANOSECONDS)
      } catch (closeFailure: RuntimeException) {
        failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
      }
    }
    failure?.let { throw it }
  }
}
