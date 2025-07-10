package eu.rekawek.coffeegb.swing.events

import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.events.Subscriber

inline fun <reified E : Event> EventBus.register(subscriber: Subscriber<E>) {
  register(subscriber, E::class.java)
}
