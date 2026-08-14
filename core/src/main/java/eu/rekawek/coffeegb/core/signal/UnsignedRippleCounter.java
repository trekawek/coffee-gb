package eu.rekawek.coffeegb.core.signal;

/**
 * An unsigned clocked counter that exposes the individual bit transitions caused by a tick.
 *
 * <p>Increment and asynchronous clear are resolved from the same committed value. Clear is
 * dominant when both wires are asserted. Until {@link #commit()}, {@link #value()} remains the
 * old value while {@link #nextValue()}, {@link #risingMask()}, and {@link #fallingMask()} describe
 * the pending transition. Divider consumers can therefore react to the actual transition of a
 * tapped bit, including the falling edge produced by clearing the counter, without reconstructing
 * its phase.
 */
public final class UnsignedRippleCounter {

    private final int width;

    private final long valueMask;

    private long value;

    private long nextValue;

    private long risingMask;

    private long fallingMask;

    public UnsignedRippleCounter(int width, long initialValue) {
        if (width < 1 || width > Integer.SIZE) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        this.width = width;
        this.valueMask = (1L << width) - 1;
        restore(initialValue);
    }

    public int width() {
        return width;
    }

    public long value() {
        return value;
    }

    public long nextValue() {
        return nextValue;
    }

    /** A bit is set for every counter stage that will transition from zero to one. */
    public long risingMask() {
        return risingMask;
    }

    /** A bit is set for every counter stage that will transition from one to zero. */
    public long fallingMask() {
        return fallingMask;
    }

    public boolean rose(int bit) {
        return (risingMask & bitMask(bit)) != 0;
    }

    public boolean fell(int bit) {
        return (fallingMask & bitMask(bit)) != 0;
    }

    /**
     * Resolves a counter-clock pulse and the asynchronous clear wire.
     *
     * <p>The clear wire dominates an increment pulse asserted in the same resolution step.
     */
    public void resolve(boolean increment, boolean asynchronousClear) {
        if (asynchronousClear) {
            nextValue = 0;
        } else if (increment) {
            nextValue = (value + 1) & valueMask;
        } else {
            nextValue = value;
        }
        risingMask = ~value & nextValue & valueMask;
        fallingMask = value & ~nextValue & valueMask;
    }

    public void commit() {
        value = nextValue;
        risingMask = 0;
        fallingMask = 0;
    }

    /** Restores portable state at a clock boundary and clears all derived transitions. */
    public void restore(long restoredValue) {
        if ((restoredValue & ~valueMask) != 0) {
            throw new IllegalArgumentException("value does not fit the counter width");
        }
        value = restoredValue;
        nextValue = restoredValue;
        risingMask = 0;
        fallingMask = 0;
    }

    private long bitMask(int bit) {
        if (bit < 0 || bit >= width) {
            throw new IllegalArgumentException("bit must be in 0.." + (width - 1));
        }
        return 1L << bit;
    }
}
