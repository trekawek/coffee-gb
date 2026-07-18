package eu.rekawek.coffeegb.core.rumble;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

/**
 * The vibration motor built into the Game Boy CodeBreaker pass-through accessory.
 *
 * <p>CodeBreaker-aware games drive the motor with bit 7 of writes to HRAM address
 * 0xFFFE. The other seven bits may carry a duration counter and remain ordinary HRAM.
 */
public class CodeBreakerRumble implements Serializable, Originator<CodeBreakerRumble> {

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

    private void setMotorOn(boolean on) {
        if (on == motorOn) {
            return;
        }
        motorOn = on;
        eventBus.post(new RumbleEvent(on));
    }

    @Override
    public Memento<CodeBreakerRumble> saveToMemento() {
        return new CodeBreakerRumbleMemento(motorOn);
    }

    @Override
    public void restoreFromMemento(Memento<CodeBreakerRumble> memento) {
        if (!(memento instanceof CodeBreakerRumbleMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        setMotorOn(mem.motorOn);
    }

    private record CodeBreakerRumbleMemento(boolean motorOn)
            implements Memento<CodeBreakerRumble> {
    }
}
