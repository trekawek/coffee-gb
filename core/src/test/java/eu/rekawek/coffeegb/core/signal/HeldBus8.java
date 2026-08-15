package eu.rekawek.coffeegb.core.signal;

/**
 * An eight-bit, low-dominant tri-state bus with a bus keeper and explicit clock phases.
 *
 * <p>Every participant has a stable owner number in {@code 0..63}. During a transaction the
 * participant drives some data lines or releases them. Resolution is bitwise and commutative: a
 * low driver wins an electrical conflict, a driven line takes its resolved level, and a floating
 * line retains the level from the previous commit. Java requester order therefore cannot select a
 * winner.
 *
 * <p>The phase sequence is {@link #beginDrive()}, zero or more calls to {@link #drive(int, int,
 * int)}, {@link #resolve()}, any number of {@link #sample()} calls, and {@link #commit()}. Only the
 * held byte is portable state. {@link #restore(int)} discards a partially resolved transaction.
 * Transaction processing mutates only primitive fields and a constructor-allocated owner table;
 * the normal drive/resolve/sample/commit path allocates no objects.
 *
 * <p>This class is one electrical primitive, not a universal Game Boy memory-bus policy. CPU/main,
 * VRAM and OAM are separate buses and may use different grants or receiving-cell behavior. In
 * particular, a CGB collision that clears a RAM latch and the DMG OAM SRAM's keeper feedback are
 * not equivalent to low-dominant data-line resolution and must not be encoded as special owners
 * here.
 */
public final class HeldBus8 {

    private static final int BYTE_MASK = 0xff;

    private static final int PHASE_COMMITTED = 0;

    private static final int PHASE_DRIVE = 1;

    private static final int PHASE_RESOLVED = 2;

    private final long[] lineOwners = new long[Byte.SIZE];

    private int held;

    private int nextHeld;

    private int drivenHigh;

    private int drivenLow;

    private int driven;

    private int contention;

    private int ownershipContention;

    private int floatingHeld;

    private long activeOwners;

    private long contendingOwners;

    private int phase;

    public HeldBus8(int initialValue) {
        restore(initialValue);
    }

    /** Starts the drive phase of a new transaction with every data line floating. */
    public void beginDrive() {
        requirePhase(PHASE_COMMITTED, "beginDrive");
        drivenHigh = 0;
        drivenLow = 0;
        driven = 0;
        contention = 0;
        ownershipContention = 0;
        floatingHeld = BYTE_MASK;
        activeOwners = 0;
        contendingOwners = 0;
        for (int bit = 0; bit < Byte.SIZE; bit++) {
            lineOwners[bit] = 0;
        }
        nextHeld = held;
        phase = PHASE_DRIVE;
    }

    /** Drives every data line on behalf of one stable owner. */
    public void drive(int owner, int value) {
        drive(owner, BYTE_MASK, value);
    }

    /**
     * Drives only the bits selected by {@code mask} on behalf of {@code owner}.
     *
     * <p>An owner may contribute in more than one call; its contributions are combined as
     * simultaneous signals. A conflicting repeated contribution is electrical contention but not
     * ownership contention.
     */
    public void drive(int owner, int mask, int value) {
        requirePhase(PHASE_DRIVE, "drive");
        long ownerBit = ownerBit(owner);
        int checkedMask = byteValue(mask, "mask");
        int checkedValue = byteValue(value, "value");
        if (checkedMask == 0) {
            return;
        }

        activeOwners |= ownerBit;
        drivenHigh |= checkedMask & checkedValue;
        drivenLow |= checkedMask & ~checkedValue & BYTE_MASK;

        int remaining = checkedMask;
        while (remaining != 0) {
            int lineMask = Integer.lowestOneBit(remaining);
            int bit = Integer.numberOfTrailingZeros(lineMask);
            long previousOwners = lineOwners[bit];
            long otherOwners = previousOwners & ~ownerBit;
            if (otherOwners != 0) {
                ownershipContention |= lineMask;
                contendingOwners |= otherOwners | ownerBit;
            }
            lineOwners[bit] = previousOwners | ownerBit;
            remaining &= ~lineMask;
        }
    }

    /** Resolves all data lines without changing the byte visible at the commit boundary. */
    public void resolve() {
        requirePhase(PHASE_DRIVE, "resolve");
        driven = drivenHigh | drivenLow;
        contention = drivenHigh & drivenLow;
        floatingHeld = ~driven & BYTE_MASK;
        nextHeld = (held & floatingHeld) | (drivenHigh & ~drivenLow);
        phase = PHASE_RESOLVED;
    }

    /** Samples the resolved transaction value. This does not advance the bus keeper. */
    public int sample() {
        requirePhase(PHASE_RESOLVED, "sample");
        return nextHeld;
    }

    /** The value held at the preceding commit boundary. */
    public int held() {
        return held;
    }

    /** The resolved value that will become held at the next commit boundary. */
    public int nextHeld() {
        requirePhase(PHASE_RESOLVED, "nextHeld");
        return nextHeld;
    }

    /** Owners that drive at least one line in this transaction. */
    public long activeOwnerMask() {
        requireResolved("activeOwnerMask");
        return activeOwners;
    }

    /** Owners that share at least one line with a different owner. */
    public long contendingOwnerMask() {
        requireResolved("contendingOwnerMask");
        return contendingOwners;
    }

    /** Owner bit-set for one data line. */
    public long ownerMask(int bit) {
        requireResolved("ownerMask");
        checkBit(bit);
        return lineOwners[bit];
    }

    /** Lines driven by at least one owner. */
    public int drivenMask() {
        requireResolved("drivenMask");
        return driven;
    }

    /** Lines on which at least one high and one low contribution are both present. */
    public int contentionMask() {
        requireResolved("contentionMask");
        return contention;
    }

    /** Lines shared by two or more different owners, even when they drive the same level. */
    public int ownershipContentionMask() {
        requireResolved("ownershipContentionMask");
        return ownershipContention;
    }

    /** Undriven lines whose resolved level came from the bus keeper. */
    public int floatingHeldMask() {
        requireResolved("floatingHeldMask");
        return floatingHeld;
    }

    /** Commits the resolved value to the bus keeper. */
    public void commit() {
        requirePhase(PHASE_RESOLVED, "commit");
        held = nextHeld;
        phase = PHASE_COMMITTED;
    }

    /** Restores portable state at a clock boundary and releases all drivers. */
    public void restore(int restoredValue) {
        held = byteValue(restoredValue, "restoredValue");
        nextHeld = held;
        drivenHigh = 0;
        drivenLow = 0;
        driven = 0;
        contention = 0;
        ownershipContention = 0;
        floatingHeld = BYTE_MASK;
        activeOwners = 0;
        contendingOwners = 0;
        for (int bit = 0; bit < Byte.SIZE; bit++) {
            lineOwners[bit] = 0;
        }
        phase = PHASE_COMMITTED;
    }

    private void requireResolved(String operation) {
        requirePhase(PHASE_RESOLVED, operation);
    }

    private void requirePhase(int required, String operation) {
        if (phase != required) {
            throw new IllegalStateException(operation + " called outside its bus phase");
        }
    }

    private static long ownerBit(int owner) {
        if (owner < 0 || owner >= Long.SIZE) {
            throw new IllegalArgumentException("owner must be in 0..63");
        }
        return 1L << owner;
    }

    private static void checkBit(int bit) {
        if (bit < 0 || bit >= Byte.SIZE) {
            throw new IllegalArgumentException("bit must be in 0..7");
        }
    }

    private static int byteValue(int value, String name) {
        if ((value & ~BYTE_MASK) != 0) {
            throw new IllegalArgumentException(name + " must be an unsigned byte");
        }
        return value;
    }
}
