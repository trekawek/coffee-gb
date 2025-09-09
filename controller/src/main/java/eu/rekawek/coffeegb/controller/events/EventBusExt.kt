package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import kotlin.reflect.KClass

inline fun <reified E : Event> EventBus.register(subscriber: Subscriber<E>) {
  register(subscriber, E::class.java)
}

fun funnel(from: EventBus, to: EventBus, eventTypes: Set<KClass<out Event>>) {
  eventTypes.forEach { et -> from.register({ event -> to.post(event) }, et.java) }
}
