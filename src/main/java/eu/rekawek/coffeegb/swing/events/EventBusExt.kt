package eu.rekawek.coffeegb.swing.events

import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.events.Subscriber
import kotlin.collections.forEach
import kotlin.reflect.KClass

inline fun <reified E : Event> EventBus.register(subscriber: Subscriber<E>) {
  register(subscriber, E::class.java)
}

fun funnel(from: EventBus, to: EventBus, eventTypes: Set<KClass<out Event>>) {
  eventTypes.forEach { et -> from.register({ event -> to.post(event) }, et.java) }
}
