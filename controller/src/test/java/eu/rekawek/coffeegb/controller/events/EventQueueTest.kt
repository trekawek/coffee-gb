package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBusImpl
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class EventQueueTest {

  @Test
  fun countLimitRejectsBoundaryPlusOneAndRecoversAfterDispatch() {
    val bus = EventBusImpl()
    val received = mutableListOf<Long>()
    val queue = EventQueue(bus, maxEvents = 2, maxBytes = 100, eventWeight = { 1 })
    queue.register<WeightedEvent> { received += it.bytes }
    try {
      bus.post(WeightedEvent(1))
      bus.post(WeightedEvent(2))
      assertFailsWith<EventQueue.EventQueueFullException> { bus.post(WeightedEvent(3)) }

      queue.dispatch()
      assertEquals(listOf(1L, 2L), received)
      bus.post(WeightedEvent(3))
      queue.dispatch()
      assertEquals(listOf(1L, 2L, 3L), received)
    } finally {
      bus.close()
    }
  }

  @Test
  fun byteLimitAcceptsExactBoundaryAndRejectsBoundaryPlusOne() {
    val bus = EventBusImpl()
    val queue =
        EventQueue(
            bus,
            maxEvents = 10,
            maxBytes = 10,
            eventWeight = { (it as WeightedEvent).bytes },
        )
    queue.register<WeightedEvent> {}
    try {
      bus.post(WeightedEvent(4))
      bus.post(WeightedEvent(6))
      assertFailsWith<EventQueue.EventQueueFullException> { bus.post(WeightedEvent(1)) }
    } finally {
      bus.close()
    }
  }

  private data class WeightedEvent(val bytes: Long) : Event
}
