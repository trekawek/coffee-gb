package eu.rekawek.coffeegb.core.signal;

/**
 * An eight-bit tri-state bus whose floating lines retain their last committed level.
 *
 * <p>Drivers contribute masks during the drive phase. Resolution is bitwise and independent of
 * driver order: a low driver wins contention, a singly-driven line takes that value, and an
 * undriven line keeps its held value. The held value changes only at {@link #commit()}.
 */
public final class HeldBus8 {

    private int held;

    private int nextHeld;

    private int drivenHigh;

    private int drivenLow;

    public HeldBus8(int initialValue) {
        held = byteValue(initialValue, "initialValue");
        nextHeld = held;
    }

    /** Starts a new drive phase with every line floating. */
    public void beginDrive() {
        drivenHigh = 0;
        drivenLow = 0;
        nextHeld = held;
    }

    public void drive(int value) {
        drive(0xff, value);
    }

    /**
     * Drives only bits selected by {@code mask}. Values on unselected bits are ignored.
     */
    public void drive(int mask, int value) {
        int checkedMask = byteValue(mask, "mask");
        int checkedValue = byteValue(value, "value");
        drivenHigh |= checkedMask & checkedValue;
        drivenLow |= checkedMask & ~checkedValue & 0xff;
    }

    /** Resolves the driven lines and captures the value that the bus will hold after commit. */
    public int resolve() {
        int driven = drivenHigh | drivenLow;
        nextHeld = (held & ~driven) | (drivenHigh & ~drivenLow);
        return nextHeld;
    }

    /** The value held at the previous commit boundary. */
    public int held() {
        return held;
    }

    /** The last resolved value, which is not yet visible through {@link #held()}. */
    public int nextHeld() {
        return nextHeld;
    }

    public int contentionMask() {
        return drivenHigh & drivenLow;
    }

    public int drivenMask() {
        return drivenHigh | drivenLow;
    }

    public void commit() {
        held = nextHeld;
    }

    /** Restores portable state at a clock boundary and releases all drivers. */
    public void restore(int restoredValue) {
        held = byteValue(restoredValue, "restoredValue");
        nextHeld = held;
        drivenHigh = 0;
        drivenLow = 0;
    }

    private static int byteValue(int value, String name) {
        if ((value & ~0xff) != 0) {
            throw new IllegalArgumentException(name + " must be an unsigned byte");
        }
        return value;
    }
}
