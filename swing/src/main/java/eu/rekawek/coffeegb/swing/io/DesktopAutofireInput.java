package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.Collection;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Converts held desktop turbo controls into frame-paced A/B presses. */
public final class DesktopAutofireInput {

    /** Two frames down and two frames up produces roughly 15 presses per second. */
    private static final int FRAMES_PER_PHASE = 2;

    private final DesktopPlayerInput input;
    private final Map<Object, Registration> sources = new IdentityHashMap<>();

    public DesktopAutofireInput(DesktopPlayerInput input, EventBus eventBus) {
        this(input, eventBus, null);
    }

    public DesktopAutofireInput(DesktopPlayerInput input, EventBus eventBus, String callerFilter) {
        this.input = input;
        if (callerFilter == null) {
            eventBus.register(ignored -> advanceFrame(), Display.DmgFrameReadyEvent.class);
            eventBus.register(ignored -> advanceFrame(), Display.GbcFrameReadyEvent.class);
        } else {
            eventBus.register(
                    ignored -> advanceFrame(), Display.DmgFrameReadyEvent.class, callerFilter);
            eventBus.register(
                    ignored -> advanceFrame(), Display.GbcFrameReadyEvent.class, callerFilter);
        }
    }

    public synchronized void update(Object identity, int player, Collection<Button> buttons) {
        EnumSet<Button> held = EnumSet.noneOf(Button.class);
        for (Button button : buttons) {
            if (button != Button.A && button != Button.B) {
                throw new IllegalArgumentException("Autofire supports only A and B");
            }
            held.add(button);
        }
        if (held.isEmpty()) {
            disconnect(identity);
            return;
        }

        Registration registration = sources.get(identity);
        if (registration != null && registration.player != player) {
            input.disconnect(identity);
            registration = null;
        }
        if (registration == null) {
            registration = new Registration(player);
            sources.put(identity, registration);
        }
        registration.buttons.clear();
        registration.buttons.addAll(held);
        publish(identity, registration);
    }

    public synchronized void disconnect(Object identity) {
        if (sources.remove(identity) != null) {
            input.disconnect(identity);
        }
    }

    public synchronized void releaseAll() {
        for (Object identity : Set.copyOf(sources.keySet())) {
            input.disconnect(identity);
        }
        sources.clear();
    }

    synchronized void advanceFrame() {
        sources.forEach((identity, registration) -> {
            registration.framesInPhase++;
            if (registration.framesInPhase == FRAMES_PER_PHASE) {
                registration.framesInPhase = 0;
                registration.downPhase = !registration.downPhase;
                publish(identity, registration);
            }
        });
    }

    private void publish(Object identity, Registration registration) {
        input.update(identity, registration.player,
                registration.downPhase ? registration.buttons : Set.of());
    }

    private static final class Registration {
        private final int player;
        private final EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
        private boolean downPhase = true;
        private int framesInPhase;

        private Registration(int player) {
            if (player < 0 || player > 3) {
                throw new IllegalArgumentException("Logical player index must be in 0..3");
            }
            this.player = player;
        }
    }
}
