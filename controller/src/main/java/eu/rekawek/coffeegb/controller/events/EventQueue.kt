package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import eu.rekawek.coffeegb.core.events.SynchronousBorrowedEvent
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

class EventQueue(
    private val eventBus: EventBus,
    private val maxEvents: Int = Int.MAX_VALUE,
    private val maxBytes: Long = Long.MAX_VALUE,
    private val eventWeight: (Event) -> Long = { 0L },
    private val eventSource: (Event) -> Any? = { null },
    private val maxSourceEvents: Int = maxEvents,
    private val maxSourceBytes: Long = maxBytes,
    private val maxDispatchEvents: Int = maxEvents,
    private val eventOrder: (() -> Long)? = null,
) {

  private val queue = ArrayDeque<WeightedEvent>()

  private val queuedBySource = IdentityHashMap<Any, SourceBudget>()

  private var queuedEvents = 0

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
    val dispatchEvents = synchronized(queue) { minOf(queue.size, maxDispatchEvents) }
    repeat(dispatchEvents) {
      if (!dispatchOne()) return
    }
  }

  fun dispatchOne(): Boolean {
    val event =
        synchronized(queue) {
          val weighted = queue.pollFirst() ?: return false
          releaseBudgetLocked(weighted)
          weighted.event
        }
    dispatch(event)
    return true
  }

  /**
   * Dispatches the first matching control event without reordering the events that remain queued.
   *
   * This is reserved for decisions that unblock/cancel an already-frozen worker. Ordinary state
   * mutation continues to use [dispatchOne] and therefore preserves queue order.
   */
  fun dispatchFirstMatching(predicate: (Event) -> Boolean): Boolean {
    val event =
        synchronized(queue) {
          val iterator = queue.iterator()
          var selected: WeightedEvent? = null
          while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (predicate(candidate.event)) {
              iterator.remove()
              releaseBudgetLocked(candidate)
              selected = candidate
              break
            }
          }
          selected?.event
        } ?: return false
    dispatch(event)
    return true
  }

  fun anyEvent(predicate: (Event) -> Boolean): Boolean =
      synchronized(queue) { queue.any { predicate(it.event) } }

  private fun dispatch(event: Event) {
    for (registrationValue in registrations) {
      if (registrationValue.eventType.isInstance(event)) {
        // Use a safe cast and a cast-then-call pattern to ensure type safety.
        @Suppress("UNCHECKED_CAST")
        val registration = registrationValue as Registration<Event>
        registration.subscriber.onEvent(event)
      }
    }
  }

  fun nextOrder(): Long? = synchronized(queue) { queue.peekFirst()?.order }

  fun nextEvent(): Event? = synchronized(queue) { queue.peekFirst()?.event }

  fun discardSource(source: Any) {
    synchronized(queue) { discardSourceLocked(source) }
  }

  private fun enqueue(event: Event) {
    if (event is SynchronousBorrowedEvent) {
      throw IllegalArgumentException(
          "Borrowed events cannot be retained by an asynchronous event queue")
    }
    val weight = eventWeight(event)
    if (weight < 0) throw IllegalArgumentException("Negative event weight")
    val source = eventSource(event) ?: LOCAL_SOURCE
    synchronized(queue) {
      val budget = queuedBySource.getOrPut(source, ::SourceBudget)
      if (budget.events >= maxSourceEvents || weight > maxSourceBytes - budget.bytes) {
        discardSourceLocked(source)
        throw EventQueueFullException(source, maxSourceEvents, maxSourceBytes, false)
      }
      if (queuedEvents >= maxEvents || weight > maxBytes - queuedBytes) {
        if (budget.events == 0) queuedBySource.remove(source)
        throw EventQueueFullException(source, maxEvents, maxBytes, true)
      }
      queue.addLast(WeightedEvent(event, weight, source, eventOrder?.invoke() ?: 0L))
      budget.events++
      budget.bytes += weight
      queuedEvents++
      queuedBytes += weight
    }
  }

  private fun discardSourceLocked(source: Any) {
    val iterator = queue.iterator()
    while (iterator.hasNext()) {
      val event = iterator.next()
      if (event.source === source) {
        iterator.remove()
        queuedEvents--
        queuedBytes -= event.weight
      }
    }
    queuedBySource.remove(source)
  }

  private fun releaseBudgetLocked(weighted: WeightedEvent) {
    val budget = checkNotNull(queuedBySource[weighted.source])
    budget.events--
    budget.bytes -= weighted.weight
    queuedEvents--
    queuedBytes -= weighted.weight
    if (budget.events == 0) queuedBySource.remove(weighted.source)
  }

  internal class EventQueueFullException(
      val source: Any,
      maxEvents: Int,
      maxBytes: Long,
      val global: Boolean,
  ) : IllegalStateException(
      if (global) {
        "Event queue exceeds $maxEvents queued events or $maxBytes bytes"
      } else {
        "Event source exceeds $maxEvents queued events or $maxBytes bytes"
      })

  private data class WeightedEvent(
      val event: Event,
      val weight: Long,
      val source: Any,
      val order: Long,
  )

  private data class SourceBudget(var events: Int = 0, var bytes: Long = 0)

  private data class Registration<T : Event>(
      val subscriber: Subscriber<T>,
      val eventType: Class<T>,
  )

  private companion object {
    val LOCAL_SOURCE = Any()
  }
}
