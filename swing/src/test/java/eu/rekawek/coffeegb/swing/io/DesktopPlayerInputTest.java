package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonReleaseEvent;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DesktopPlayerInputTest {

    @Test
    public void primaryAggregateSwapIsAtomicBeforeLogicalEventsArePublished() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        Object keyboard = new Object();
        List<Set<Button>> observed = new ArrayList<>();
        List<Event> legacy = new ArrayList<>();
        bus.register(ignored -> observed.add(hub.sample().buttons(0)),
                LogicalPlayerButtonPressEvent.class);
        bus.register(ignored -> observed.add(hub.sample().buttons(0)),
                LogicalPlayerButtonReleaseEvent.class);
        bus.register(legacy::add, ButtonPressEvent.class);
        bus.register(legacy::add, ButtonReleaseEvent.class);

        input.update(keyboard, 0, Set.of(Button.A));
        input.update(keyboard, 0, Set.of(Button.B));

        assertEquals(Set.of(Button.B), hub.sample().buttons(0));
        assertEquals(List.of(Set.of(Button.A), Set.of(Button.B), Set.of(Button.B)), observed);
        assertTrue(observed.stream().noneMatch(buttons ->
                buttons.contains(Button.A) && buttons.contains(Button.B)));
        assertTrue(legacy.isEmpty());
    }

    @Test
    public void sourceUnionAutorepeatFocusAndReassignmentHaveExactTransitions() {
        EventBusImpl bus = new EventBusImpl(null, null, false);
        PlayerInputHub hub = new PlayerInputHub();
        DesktopPlayerInput input = new DesktopPlayerInput(hub, bus);
        List<Event> events = new ArrayList<>();
        List<Event> legacyEvents = new ArrayList<>();
        bus.register(legacyEvents::add, ButtonPressEvent.class);
        bus.register(legacyEvents::add, ButtonReleaseEvent.class);
        bus.register(events::add, LogicalPlayerButtonPressEvent.class);
        bus.register(events::add, LogicalPlayerButtonReleaseEvent.class);
        Object keyboard = new Object();
        Object gamepad = new Object();

        input.update(keyboard, 0, Set.of(Button.A));
        input.update(keyboard, 0, Set.of(Button.A)); // keyboard autorepeat
        input.update(gamepad, 0, Set.of(Button.A, Button.B));
        input.disconnect(keyboard);
        assertEquals(Set.of(Button.A, Button.B), hub.sample().buttons(0));
        assertEquals(List.of(
                new LogicalPlayerButtonPressEvent(0, Button.A),
                new LogicalPlayerButtonPressEvent(0, Button.B)
        ), events);
        assertTrue("desktop P1 must not mirror into legacy ownership", legacyEvents.isEmpty());

        input.update(gamepad, 2, Set.of(Button.START));
        assertTrue(hub.sample().buttons(0).isEmpty());
        assertEquals(Set.of(Button.START), hub.sample().buttons(2));
        assertEquals(new LogicalPlayerButtonReleaseEvent(0, Button.A), events.get(2));
        assertEquals(new LogicalPlayerButtonReleaseEvent(0, Button.B), events.get(3));
        assertEquals(new LogicalPlayerButtonPressEvent(2, Button.START), events.get(4));

        input.setFocused(false);
        assertTrue(hub.sample().buttons(2).isEmpty());
        input.update(gamepad, 2, Set.of(Button.START));
        assertTrue(hub.sample().buttons(2).isEmpty());
        input.setFocused(true);
        input.update(gamepad, 2, Set.of(Button.START));
        assertEquals(Set.of(Button.START), hub.sample().buttons(2));
        input.close();
        assertTrue(hub.sample().players().stream().allMatch(Set::isEmpty));
    }
}
