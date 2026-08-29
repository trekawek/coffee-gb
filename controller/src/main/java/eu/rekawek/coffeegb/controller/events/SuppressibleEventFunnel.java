package eu.rekawek.coffeegb.controller.events;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import kotlin.jvm.functions.Function0;
import kotlin.reflect.KClass;

import java.util.Set;

/**
 * An owning event funnel whose selected event classes can be silenced for one synchronous scope.
 * Suppression is nestable and is always released when {@link #suppressForwarding} returns or
 * throws. This interface is Java-owned because {@code StagedEventBus} must compile before the
 * controller module's Kotlin sources in clean Maven builds.
 */
public interface SuppressibleEventFunnel extends EventBus {

    <T> T suppressForwarding(
            Set<? extends KClass<? extends Event>> eventTypes,
            Function0<? extends T> action);
}
