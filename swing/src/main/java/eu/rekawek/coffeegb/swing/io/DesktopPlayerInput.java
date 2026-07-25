package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonReleaseEvent;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;

import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Desktop-only source-identity bridge into the platform-neutral core input hub. */
public final class DesktopPlayerInput {

    private final PlayerInputHub hub;
    private final EventBus eventBus;
    private final Map<Object, Registration> sources = new IdentityHashMap<>();

    private boolean focused = true;

    public DesktopPlayerInput(PlayerInputHub hub, EventBus eventBus) {
        this.hub = hub;
        this.eventBus = eventBus;
    }

    public synchronized void update(Object identity, int player, Collection<Button> buttons) {
        Registration registration = sources.get(identity);
        if (registration != null && registration.player != player) {
            emit(registration.handle.closeAndGetChange());
            sources.remove(identity);
            registration = null;
        }
        if (registration == null) {
            registration = new Registration(player, hub.openSource(player));
            sources.put(identity, registration);
        }
        emit(registration.handle.update(focused ? buttons : Set.of()));
    }

    public synchronized void disconnect(Object identity) {
        Registration registration = sources.remove(identity);
        if (registration != null) {
            emit(registration.handle.closeAndGetChange());
        }
    }

    public synchronized void setFocused(boolean focused) {
        if (this.focused == focused) {
            return;
        }
        this.focused = focused;
        if (!focused) {
            emit(hub.releaseAll());
        }
    }

    /** Clears every current latch without changing focus or physical assignments. */
    public synchronized void releaseAll() {
        emit(hub.releaseAll());
    }

    public synchronized boolean isFocused() {
        return focused;
    }

    public synchronized void close() {
        sources.values().forEach(registration -> emit(registration.handle.closeAndGetChange()));
        sources.clear();
        emit(hub.releaseAll());
    }

    private void emit(PlayerInputHub.InputChange change) {
        change.pressed().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().stream().sorted(Comparator.naturalOrder())
                        .forEach(button -> postPress(entry.getKey(), button)));
        change.released().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().stream().sorted(Comparator.naturalOrder())
                        .forEach(button -> postRelease(entry.getKey(), button)));
    }

    private void postPress(int player, Button button) {
        if (player == 0) {
            eventBus.post(new ButtonPressEvent(button));
        } else {
            eventBus.post(new LogicalPlayerButtonPressEvent(player, button));
        }
    }

    private void postRelease(int player, Button button) {
        if (player == 0) {
            eventBus.post(new ButtonReleaseEvent(button));
        } else {
            eventBus.post(new LogicalPlayerButtonReleaseEvent(player, button));
        }
    }

    private record Registration(int player, PlayerInputHub.SourceHandle handle) {
    }
}
