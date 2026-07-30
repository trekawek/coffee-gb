package eu.rekawek.coffeegb.core.rumble;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

/**
 * The vibration motor built into the Game Boy CodeBreaker pass-through accessory.
 *
 * <p>CodeBreaker-aware games drive the motor with bit 7 of writes to HRAM address
 * 0xFFFE. The other seven bits may carry a duration counter and remain ordinary HRAM.
 */
public class CodeBreakerRumble implements StatefulComponent<CodeBreakerRumble> {

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private boolean motorOn;

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
        if (motorOn) {
            eventBus.post(new RumbleEvent(true));
        }
    }

    public void onHramWrite(int value) {
        setMotorOn((value & 0x80) != 0);
    }

    public void close() {
        setMotorOn(false);
        eventBus = EventBus.NULL_EVENT_BUS;
    }

    /**
     * Silences the emulated accessory after its owner has already reset host/UI output state.
     *
     * <p>No subscriber is invoked: this is the resource-cleanup half of a quiesced session
     * teardown, not a live hardware transition.
     */
    public void quiesce() {
        motorOn = false;
        eventBus = EventBus.NULL_EVENT_BUS;
    }

    private void setMotorOn(boolean on) {
        if (on == motorOn) {
            return;
        }
        motorOn = on;
        eventBus.post(new RumbleEvent(on));
    }

    @Override
    public ComponentState<CodeBreakerRumble> captureState() {
        return new CodeBreakerRumbleState(motorOn);
    }

    @Override
    public void restoreState(ComponentState<CodeBreakerRumble> state) {
        if (!(state instanceof CodeBreakerRumbleState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        setMotorOn(mem.motorOn);
    }

    /** Restores the emulated latch without exposing a speculative transaction to the host. */
    public void restoreStateSilently(ComponentState<CodeBreakerRumble> state) {
        if (!(state instanceof CodeBreakerRumbleState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        motorOn = mem.motorOn;
    }

    /** Current accessory-owned motor output, without invoking host services. */
    public boolean isMotorOn() {
        return motorOn;
    }

    private record CodeBreakerRumbleState(boolean motorOn)
            implements ComponentState<CodeBreakerRumble> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record CodeBreakerRumbleMemento(boolean motorOn)
            implements Memento<CodeBreakerRumble> {
    }
}
