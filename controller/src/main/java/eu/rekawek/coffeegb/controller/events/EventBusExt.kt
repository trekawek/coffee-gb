package eu.rekawek.coffeegb.controller.events

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.Subscriber
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.rumble.RumbleEvent
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sound.Sound
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

inline fun <reified E : Event> EventBus.register(subscriber: Subscriber<E>) {
  register(subscriber, E::class.java)
}

fun funnel(from: EventBus, to: EventBus, eventTypes: Set<KClass<out Event>>) {
  eventTypes.forEach { et -> from.register({ event -> to.post(event) }, et.java) }
}

/**
 * An owning event funnel whose selected event classes can be silenced for one synchronous scope.
 * Suppression is nestable and is always released when [suppressForwarding] returns or throws.
 */
interface SuppressibleEventFunnel : EventBus {
  fun <T> suppressForwarding(
      eventTypes: Set<KClass<out Event>>,
      action: () -> T,
  ): T
}

private val PRESENTATION_EVENT_TYPES: Set<KClass<out Event>> =
    setOf(
        Display.DmgFrameReadyEvent::class,
        Display.GbcFrameReadyEvent::class,
        SgbDisplay.SgbFrameReadyEvent::class,
        Sound.SoundSampleEvent::class,
        RumbleEvent::class,
    )

/**
 * Runs [action] while a local owning funnel drops frame, sample, and rumble presentation. Other
 * events, including joypad observation, continue to flow. An isolated/non-local bus simply runs
 * the action because it has no shared presentation route to suppress.
 */
fun <T> EventBus.withPresentationSuppressed(action: () -> T): T =
    if (this is SuppressibleEventFunnel) {
      suppressForwarding(PRESENTATION_EVENT_TYPES, action)
    } else {
      action()
    }

/**
 * Keeps a machine's inbound event bus isolated while owning the shared-tree fork used for its
 * selected presentation output. Closing the returned bus closes both sides within one deadline.
 */
fun owningFunnel(
    from: EventBus,
    to: EventBus,
    eventTypes: Set<KClass<out Event>>,
): EventBus = OwningFunnelEventBus(from, to, eventTypes)

private class OwningFunnelEventBus(
    private val inbound: EventBus,
    private val outbound: EventBus,
    eventTypes: Set<KClass<out Event>>,
) : SuppressibleEventFunnel, EventBus by inbound {

  private val forwardedEventTypes = eventTypes.toSet()

  private val suppressionLock = Any()

  private val suppressionDepth = mutableMapOf<KClass<out Event>, Int>()

  init {
    forwardedEventTypes.forEach { eventType ->
      inbound.register(
          { event ->
            val suppressed = synchronized(suppressionLock) {
              suppressionDepth.getOrDefault(eventType, 0) > 0
            }
            if (!suppressed) {
              outbound.post(event)
            }
          },
          eventType.java,
      )
    }
  }

  override fun <T> suppressForwarding(
      eventTypes: Set<KClass<out Event>>,
      action: () -> T,
  ): T {
    val selectedEventTypes = eventTypes.toSet()
    require(forwardedEventTypes.containsAll(selectedEventTypes)) {
      "Cannot suppress event types which are not forwarded by this funnel"
    }
    synchronized(suppressionLock) {
      selectedEventTypes.forEach { eventType ->
        suppressionDepth[eventType] = suppressionDepth.getOrDefault(eventType, 0) + 1
      }
    }
    return try {
      action()
    } finally {
      synchronized(suppressionLock) {
        selectedEventTypes.forEach { eventType ->
          val depth = checkNotNull(suppressionDepth[eventType]) - 1
          if (depth == 0) {
            suppressionDepth.remove(eventType)
          } else {
            suppressionDepth[eventType] = depth
          }
        }
      }
    }
  }

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
