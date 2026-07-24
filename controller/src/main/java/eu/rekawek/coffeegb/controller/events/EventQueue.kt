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
    private val eventSource: (Event) -> Any? = { null },
) {

  private val queue = ArrayDeque<WeightedEvent>()

  private val queuedBySource = mutableMapOf<Any, SourceBudget>()

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
            val budget = checkNotNull(queuedBySource[weighted.source])
            budget.events--
            budget.bytes -= weighted.weight
            if (budget.events == 0) queuedBySource.remove(weighted.source)
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

  fun discardSource(source: Any) {
    synchronized(queue) { discardSourceLocked(source) }
  }

  private fun enqueue(event: Event) {
    val weight = eventWeight(event)
    if (weight < 0) throw IllegalArgumentException("Negative event weight")
    val source = eventSource(event) ?: LOCAL_SOURCE
    synchronized(queue) {
      val budget = queuedBySource.getOrPut(source, ::SourceBudget)
      if (budget.events >= maxEvents || weight > maxBytes - budget.bytes) {
        discardSourceLocked(source)
        throw EventQueueFullException(source, maxEvents, maxBytes)
      }
      queue.addLast(WeightedEvent(event, weight, source))
      budget.events++
      budget.bytes += weight
    }
  }

  private fun discardSourceLocked(source: Any) {
    queue.removeIf { it.source == source }
    queuedBySource.remove(source)
  }

  internal class EventQueueFullException(
      val source: Any,
      maxEvents: Int,
      maxBytes: Long,
  ) : IllegalStateException("Event source exceeds $maxEvents queued events or $maxBytes bytes")

  private data class WeightedEvent(val event: Event, val weight: Long, val source: Any)

  private data class SourceBudget(var events: Int = 0, var bytes: Long = 0)

  private data class Registration<T : Event>(
      val subscriber: Subscriber<T>,
      val eventType: Class<T>,
  )

  private companion object {
    val LOCAL_SOURCE = Any()
  }
}
