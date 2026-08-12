package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.LogicalPlayerButtonReleaseEvent;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Desktop-only source-identity bridge into the platform-neutral core input hub. */
public final class DesktopPlayerInput {

    private final PlayerInputHub hub;
    private final EventBus eventBus;
    private final Map<Object, Registration> sources = new IdentityHashMap<>();

    private DesktopMenuInputCapture menuCapture;

    /** True after a menu capture until every physical source has returned to neutral. */
    private boolean gameplaySuppressed;

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
        registration.physical.clear();
        for (Button button : buttons) {
            registration.physical.add(button);
        }

        boolean menuConsumed = menuCapture != null && player == 0
                && menuCapture.updatePlayerButtons(playerZeroButtonsLocked());
        boolean menuVisible = menuCapture != null && menuCapture.visible();
        if (menuConsumed || menuVisible || gameplaySuppressed) {
            gameplaySuppressed = true;
            suppressGameplayLocked();
            // A released physical source is deliberately not replayed into the hub here. The
            // next non-neutral sample must be a new press, which prevents a button held while
            // closing the menu from becoming a gameplay latch.
            if (!anyPhysicalButtonHeldLocked() && !menuVisible) {
                gameplaySuppressed = false;
            }
            return;
        }

        emit(registration.handle.update(focused ? registration.physical : Set.of()));
    }

    /** Installs or replaces the optional Proposal 3 controller capture. */
    public synchronized void setMenuCapture(DesktopMenuInputCapture capture) {
        menuCapture = capture;
        if (capture == null) {
            gameplaySuppressed = false;
            return;
        }
        suppressGameplayLocked();
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
        List<Registration> registrations = List.copyOf(sources.values());
        registrations.forEach(registration -> emit(registration.handle.closeAndGetChange()));
        sources.clear();
        emit(hub.releaseAll());
    }

    private EnumSet<Button> playerZeroButtonsLocked() {
        EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
        for (Registration registration : sources.values()) {
            if (registration.player == 0) {
                buttons.addAll(registration.physical);
            }
        }
        return buttons;
    }

    private boolean anyPhysicalButtonHeldLocked() {
        return sources.values().stream().anyMatch(registration -> !registration.physical.isEmpty());
    }

    private void suppressGameplayLocked() {
        List<Registration> registrations = List.copyOf(sources.values());
        registrations.forEach(registration -> {
            // A menu callback may release a keyboard source synchronously. Avoid updating a
            // handle that was removed during that callback.
            if (sources.containsValue(registration)) {
                emit(registration.handle.update(Set.of()));
            }
        });
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
        eventBus.post(new LogicalPlayerButtonPressEvent(player, button));
    }

    private void postRelease(int player, Button button) {
        eventBus.post(new LogicalPlayerButtonReleaseEvent(player, button));
    }

    private static final class Registration {

        private final int player;

        private final PlayerInputHub.SourceHandle handle;

        private final EnumSet<Button> physical = EnumSet.noneOf(Button.class);

        private Registration(int player, PlayerInputHub.SourceHandle handle) {
            this.player = player;
            this.handle = handle;
        }
    }
}
