package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

class EventQueue(
    private val eventBus: EventBus,
    private val maxEvents: Int = Int.MAX_VALUE,
    private val maxBytes: Long = Long.MAX_VALUE,
    private val eventWeight: (Event) -> Long = { 0L },
) {

  private val queue = ArrayDeque<WeightedEvent>()

  private var queuedBytes = 0L

  private val registrations: MutableList<Registration<*>> = CopyOnWriteArrayList()

  inline fun <reified E : Event> register(subscriber: Subscriber<E>) {
    register(subscriber, E::class.java)
  }

  fun <E : Event> register(subscriber: Subscriber<E>, eventType: Class<E>) {
    registrations.add(Registration(subscriber, eventType))
    eventBus.register<E>({ enqueue(it) }, eventType)
  }

  fun dispatch() {
    while (true) {
      val e =
          synchronized(queue) {
            val weighted = queue.pollFirst() ?: return
            queuedBytes -= weighted.weight
            weighted.event
          }
      for (r in registrations) {
        if (r.eventType.isInstance(e)) {
          // Use a safe cast and a cast-then-call pattern to ensure type safety.
          @Suppress("UNCHECKED_CAST") val registration = r as Registration<Event>
          registration.subscriber.onEvent(e)
        }
      }
    }
  }

  private fun enqueue(event: Event) {
    val weight = eventWeight(event)
    if (weight < 0) throw IllegalArgumentException("Negative event weight")
    synchronized(queue) {
      if (queue.size >= maxEvents || weight > maxBytes - queuedBytes) {
        throw EventQueueFullException(maxEvents, maxBytes)
      }
      queue.addLast(WeightedEvent(event, weight))
      queuedBytes += weight
    }
  }

  internal class EventQueueFullException(maxEvents: Int, maxBytes: Long) :
      IllegalStateException("Event queue exceeds $maxEvents events or $maxBytes bytes")

  private data class WeightedEvent(val event: Event, val weight: Long)

  private data class Registration<T : Event>(
      val subscriber: Subscriber<T>,
      val eventType: Class<T>,
  )
}
