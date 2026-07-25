package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.type.AccelerometerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Last-writer desktop tilt bridge and the single focus/lifecycle recenter authority.
 *
 * <p>Keyboard, mouse, and the P1 gamepad publish through opaque source identities. Focus loss,
 * ROM/session replacement, and shutdown reset producer-local state and emit at most one center
 * event. Once stopped, retained AWT listeners cannot relatch cartridge tilt.
 */
public final class DesktopTiltInput implements WindowFocusListener {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopTiltInput.class);

    private final EventBus eventBus;
    private final List<Runnable> resetters = new ArrayList<>();

    private Object currentSource;
    private double x;
    private double y;
    private boolean focused = true;
    private boolean releasing;
    private boolean stopped;

    public DesktopTiltInput(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /** Registers producer-local state that must be cleared before the shared center event. */
    public synchronized void registerResetter(Runnable resetter) {
        if (stopped) {
            throw new IllegalStateException("Tilt input is stopped");
        }
        resetters.add(Objects.requireNonNull(resetter, "resetter"));
    }

    public synchronized void update(Object source, double x, double y) {
        Objects.requireNonNull(source, "source");
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Tilt coordinates must be finite");
        }
        if (stopped || !focused || releasing) {
            return;
        }
        boolean alreadyCentered = this.x == 0 && this.y == 0 && x == 0 && y == 0;
        currentSource = source;
        this.x = x;
        this.y = y;
        if (!alreadyCentered) {
            eventBus.post(new AccelerometerEvent(x, y));
        }
    }

    /** Clears a producer only if it was the most recent tilt writer. */
    public synchronized void clear(Object source) {
        if (currentSource != source) {
            return;
        }
        currentSource = null;
        if (x != 0 || y != 0) {
            x = 0;
            y = 0;
            eventBus.post(new AccelerometerEvent(0, 0));
        }
    }

    /** Recenters transient state while leaving a focused desktop ready for the next input. */
    public void releaseForLifecycleChange() {
        release(false, false);
    }

    /** Permanently disables input and recenters it; repeated calls are idempotent. */
    public void stop() {
        release(true, true);
    }

    @Override
    public void windowGainedFocus(WindowEvent event) {
        synchronized (this) {
            if (!stopped) {
                focused = true;
            }
        }
    }

    @Override
    public void windowLostFocus(WindowEvent event) {
        release(true, false);
    }

    private void release(boolean loseFocus, boolean stop) {
        List<Runnable> callbacks;
        synchronized (this) {
            if (loseFocus) {
                focused = false;
            }
            if (stop) {
                stopped = true;
            }
            releasing = true;
            callbacks = List.copyOf(resetters);
        }
        try {
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (Throwable failure) {
                    LOG.warn("Unable to clear one desktop tilt producer", failure);
                }
            }
        } finally {
            synchronized (this) {
                currentSource = null;
                if (x != 0 || y != 0) {
                    x = 0;
                    y = 0;
                    eventBus.post(new AccelerometerEvent(0, 0));
                }
                releasing = false;
            }
        }
    }

    synchronized double currentX() {
        return x;
    }

    synchronized double currentY() {
        return y;
    }
}
