package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.events.SynchronousBorrowedEvent;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class StagedEventBusTest {

    @Test
    public void isolatesRootEventsAndFlushesCandidateEventsInOrderOnActivation() {
        EventBusImpl root = new EventBusImpl(null, null, false);
        List<Integer> rootDeliveries = new ArrayList<>();
        List<Integer> candidateDeliveries = new ArrayList<>();
        AtomicInteger candidateLifecycleDeliveries = new AtomicInteger();
        AtomicInteger candidateInputDeliveries = new AtomicInteger();
        root.register(event -> rootDeliveries.add(event.value()), ProbeEvent.class);
        StagedEventBus candidate = new StagedEventBus(root.fork("candidate"));
        candidate.register(
                event -> candidateDeliveries.add(event.value()),
                ProbeEvent.class);
        candidate.register(
                event -> candidateLifecycleDeliveries.incrementAndGet(),
                Controller.EmulationStoppedEvent.class);
        candidate.register(
                event -> candidateInputDeliveries.incrementAndGet(),
                ButtonPressEvent.class);

        root.post(new ProbeEvent(1));
        root.post(new Controller.EmulationStoppedEvent());
        root.post(new ButtonPressEvent(Button.A));
        candidate.post(new ProbeEvent(2));
        candidate.post(new ProbeEvent(3));

        assertEquals(List.of(1), rootDeliveries);
        assertEquals(List.of(), candidateDeliveries);
        assertEquals(0, candidateLifecycleDeliveries.get());
        assertEquals(0, candidateInputDeliveries.get());

        candidate.activate();
        root.post(new ProbeEvent(4));
        root.post(new Controller.EmulationStoppedEvent());
        root.post(new ButtonPressEvent(Button.A));

        assertEquals(List.of(1, 2, 3, 4), rootDeliveries);
        assertEquals(List.of(2, 3, 4), candidateDeliveries);
        assertEquals(1, candidateLifecycleDeliveries.get());
        assertEquals(1, candidateInputDeliveries.get());

        candidate.close();
        root.close();
    }

    @Test
    public void boundsStagedPostsAndDiscardNeverFlushesThem() {
        EventBusImpl root = new EventBusImpl(null, null, false);
        List<Integer> rootDeliveries = new ArrayList<>();
        root.register(event -> rootDeliveries.add(event.value()), ProbeEvent.class);
        StagedEventBus candidate = new StagedEventBus(root.fork("candidate"));

        for (int i = 0; i < 256; i++) {
            candidate.post(new ProbeEvent(i));
        }
        assertThrows(
                IllegalStateException.class,
                () -> candidate.post(new ProbeEvent(256)));

        candidate.close();

        assertEquals(List.of(), rootDeliveries);
        candidate.post(new ProbeEvent(257));
        assertEquals(List.of(), rootDeliveries);
        assertThrows(IllegalStateException.class, candidate::activate);
        root.close();
    }

    @Test
    public void borrowedEventsCannotBeStagedOrQueuedButCanPostAfterActivation() {
        EventBusImpl root = new EventBusImpl(null, null, false);
        StagedEventBus candidate = new StagedEventBus(root.fork("candidate-borrowed"));
        AtomicInteger deliveries = new AtomicInteger();
        candidate.register(event -> deliveries.incrementAndGet(), BorrowedProbe.class);

        assertThrows(IllegalArgumentException.class,
                () -> candidate.post(new BorrowedProbe()));
        assertThrows(IllegalArgumentException.class,
                () -> candidate.postAsync(new BorrowedProbe()));
        candidate.activate();
        assertThrows(IllegalArgumentException.class,
                () -> candidate.postAsync(new BorrowedProbe()));
        candidate.post(new BorrowedProbe());
        assertEquals(1, deliveries.get());

        candidate.close();
        root.close();
    }

    private record ProbeEvent(int value) implements Event {
    }

    private static final class BorrowedProbe implements SynchronousBorrowedEvent {
    }
}
