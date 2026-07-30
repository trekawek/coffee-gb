package eu.rekawek.coffeegb.core.debug.breakpoint;

import java.util.Objects;

/**
 * A serial-link transfer transition, optionally constrained by its observed byte.
 *
 * <p>{@link Event#TRANSFER_STARTED} observes the outgoing SB byte when a write asserts SC.7.
 * {@link Event#BYTE_TRANSFERRED} observes the final received SB byte after the eighth shift.
 * A value constraint matches when {@code (observed & mask) == (value & mask)}.
 */
public final class DebugSerialCondition implements DebugBreakpointCondition {

    public enum Event {
        TRANSFER_STARTED,
        BYTE_TRANSFERRED
    }

    private final Event event;

    private final boolean valueConstrained;

    private final int value;

    private final int valueMask;

    public DebugSerialCondition(Event event) {
        this(event, false, 0, 0);
    }

    public DebugSerialCondition(Event event, int value) {
        this(event, true, value, 0xff);
    }

    public DebugSerialCondition(Event event, int value, int valueMask) {
        this(event, true, value, valueMask);
    }

    private DebugSerialCondition(
            Event event, boolean valueConstrained, int value, int valueMask) {
        this.event = Objects.requireNonNull(event, "event");
        if (valueConstrained) {
            DebugBreakpointChecks.unsignedByte("value", value);
            DebugBreakpointChecks.range("valueMask", valueMask, 1, 0xff);
        }
        this.valueConstrained = valueConstrained;
        this.value = value;
        this.valueMask = valueMask;
    }

    public Event event() {
        return event;
    }

    public boolean hasValueConstraint() {
        return valueConstrained;
    }

    public int value() {
        return value;
    }

    public int valueMask() {
        return valueMask;
    }

    @Override
    public DebugBreakpointKind kind() {
        return DebugBreakpointKind.SERIAL;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugSerialCondition that)) return false;
        return event == that.event
                && valueConstrained == that.valueConstrained
                && value == that.value
                && valueMask == that.valueMask;
    }

    @Override
    public int hashCode() {
        int result = event.hashCode();
        result = 31 * result + Boolean.hashCode(valueConstrained);
        result = 31 * result + value;
        result = 31 * result + valueMask;
        return result;
    }

    @Override
    public String toString() {
        return "DebugSerialCondition[event=" + event
                + ", valueConstrained=" + valueConstrained
                + ", value=" + value
                + ", valueMask=" + valueMask + ']';
    }
}
