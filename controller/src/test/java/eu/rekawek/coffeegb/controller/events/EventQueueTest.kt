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
      assertEquals(emptyList(), received)
      bus.post(WeightedEvent(3))
      queue.dispatch()
      assertEquals(listOf(3L), received)
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

  @Test
  fun aFullProducerBudgetDoesNotConsumeAnotherProducersReservation() {
    val bus = EventBusImpl()
    val received = mutableListOf<WeightedEvent>()
    val queue =
        EventQueue(
            bus,
            maxEvents = 2,
            maxBytes = 10,
            eventWeight = { (it as WeightedEvent).bytes },
            eventSource = { (it as WeightedEvent).source },
        )
    queue.register<WeightedEvent> { received += it }
    try {
      bus.post(WeightedEvent(5, "offender"))
      bus.post(WeightedEvent(5, "offender"))
      val error =
          assertFailsWith<EventQueue.EventQueueFullException> {
            bus.post(WeightedEvent(1, "offender"))
          }
      assertEquals("offender", error.source)

      bus.post(WeightedEvent(10, "honest"))
      queue.dispatch()
      assertEquals(listOf("honest"), received.map { it.source })
    } finally {
      bus.close()
    }
  }

  @Test
  fun saturatedSourcesCannotConsumeAThirdSourcesReservedShare() {
    val bus = EventBusImpl()
    val received = mutableListOf<WeightedEvent>()
    val queue =
        EventQueue(
            bus,
            maxEvents = 6,
            maxBytes = 60,
            eventWeight = { (it as WeightedEvent).bytes },
            eventSource = { (it as WeightedEvent).source },
            maxSourceEvents = 2,
            maxSourceBytes = 20,
        )
    queue.register<WeightedEvent> { received += it }
    val first = Source("first")
    val second = Source("second")
    val third = Source("third")
    try {
      repeat(2) { bus.post(WeightedEvent(10, first)) }
      repeat(2) { bus.post(WeightedEvent(10, second)) }
      bus.post(WeightedEvent(10, third))
      val error =
          assertFailsWith<EventQueue.EventQueueFullException> {
            bus.post(WeightedEvent(1, first))
          }
      assertEquals(first, error.source)
      assertEquals(false, error.global)

      queue.dispatch()
      assertEquals(listOf(second, second, third), received.map { it.source })
    } finally {
      bus.close()
    }
  }

  @Test
  fun dispatchOnlyProcessesTheEventsPresentAtItsStart() {
    val bus = EventBusImpl()
    val received = mutableListOf<Long>()
    val queue = EventQueue(bus, maxEvents = 10, maxBytes = 100, eventWeight = { 1 })
    queue.register<WeightedEvent> {
      received += it.bytes
      if (it.bytes == 1L) bus.post(WeightedEvent(2))
    }
    try {
      bus.post(WeightedEvent(1))

      queue.dispatch()
      assertEquals(listOf(1L), received)

      queue.dispatch()
      assertEquals(listOf(1L, 2L), received)
    } finally {
      bus.close()
    }
  }

  @Test
  fun sourceBudgetsUseConnectionIdentityAndDiscardDoesNotPoisonReplacement() {
    val bus = EventBusImpl()
    val received = mutableListOf<WeightedEvent>()
    val queue =
        EventQueue(
            bus,
            maxEvents = 4,
            maxBytes = 40,
            eventWeight = { (it as WeightedEvent).bytes },
            eventSource = { (it as WeightedEvent).source },
            maxSourceEvents = 2,
            maxSourceBytes = 20,
        )
    queue.register<WeightedEvent> { received += it }
    val old = Source("same-player")
    val replacement = Source("same-player")
    try {
      bus.post(WeightedEvent(10, old))
      bus.post(WeightedEvent(10, old))
      queue.discardSource(old)
      bus.post(WeightedEvent(10, replacement))
      queue.dispatch()

      assertEquals(listOf(replacement), received.map { it.source })
    } finally {
      bus.close()
    }
  }

  private data class WeightedEvent(val bytes: Long, val source: Any = LOCAL) : Event

  private data class Source(val label: String)

  private companion object {
    val LOCAL = Any()
  }
}
