package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import java.util.concurrent.BlockingDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque

class EventQueue(private val eventBus: EventBus) {

  private val queue: BlockingDeque<Event> = LinkedBlockingDeque()

  private val registrations: MutableList<Registration<*>> = CopyOnWriteArrayList()

  inline fun <reified E : Event> register(subscriber: Subscriber<E>) {
    register(subscriber, E::class.java)
  }

  fun <E : Event> register(subscriber: Subscriber<E>, eventType: Class<E>) {
    registrations.add(Registration(subscriber, eventType))
    eventBus.register<E>({ queue.add(it) }, eventType)
  }

  fun dispatch() {
    while (queue.isNotEmpty()) {
      val e = queue.poll()
      for (r in registrations) {
        if (r.eventType.isInstance(e)) {
          // Use a safe cast and a cast-then-call pattern to ensure type safety.
          @Suppress("UNCHECKED_CAST") val registration = r as Registration<Event>
          registration.subscriber.onEvent(e)
        }
      }
    }
  }

  private data class Registration<T : Event>(
      val subscriber: Subscriber<T>,
      val eventType: Class<T>,
  )
}
