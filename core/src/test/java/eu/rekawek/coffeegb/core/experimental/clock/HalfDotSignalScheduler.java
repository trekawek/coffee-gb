package eu.rekawek.coffeegb.core.experimental.clock;

/**
 * Detached scheduler for testing a signal-island contract at the 8.388608 MHz half-dot lattice.
 *
 * <p>Each half-dot has three component callbacks. DRIVE may contribute only commutative raw
 * wires. RESOLVE samples the completed wire plane and computes private next state from committed
 * state. COMMIT publishes that next state and must not inspect another island. Supplying separate
 * permutations for all three phases makes accidental Java callback-order dependencies testable.
 */
final class HalfDotSignalScheduler {

    interface Island {

        void drive(boolean fixedClockEnable, boolean cpuClockEnable, WirePlane wires);

        void resolve(boolean fixedClockEnable, boolean cpuClockEnable, WirePlane wires);

        void commit();
    }

    static final class WirePlane {

        private static final int IDLE = 0;

        private static final int DRIVE = 1;

        private static final int RESOLVED = 2;

        private int phase;

        private int requestDrives;

        private int acknowledgeDrives;

        private int requests;

        private int acknowledgements;

        void driveRequest(int mask) {
            require(DRIVE, "driveRequest");
            requestDrives |= mask;
        }

        void driveAcknowledge(int mask) {
            require(DRIVE, "driveAcknowledge");
            acknowledgeDrives |= mask;
        }

        int requests() {
            require(RESOLVED, "requests");
            return requests;
        }

        int acknowledgements() {
            require(RESOLVED, "acknowledgements");
            return acknowledgements;
        }

        private void beginDrive() {
            require(IDLE, "beginDrive");
            requestDrives = 0;
            acknowledgeDrives = 0;
            phase = DRIVE;
        }

        private void resolve() {
            require(DRIVE, "resolve");
            requests = requestDrives;
            acknowledgements = acknowledgeDrives;
            phase = RESOLVED;
        }

        private void finishCommit() {
            require(RESOLVED, "finishCommit");
            phase = IDLE;
        }

        private void require(int expected, String operation) {
            if (phase != expected) {
                throw new IllegalStateException(operation + " called outside its signal phase");
            }
        }
    }

    private final Island[] islands;

    private final int[] driveOrder;

    private final int[] resolveOrder;

    private final int[] commitOrder;

    private final WirePlane wires = new WirePlane();

    private long halfDot;

    private boolean doubleSpeed;

    private boolean fixedClockEnable;

    private boolean cpuClockEnable;

    HalfDotSignalScheduler(
            boolean doubleSpeed,
            Island[] islands,
            int[] driveOrder,
            int[] resolveOrder,
            int[] commitOrder) {
        if (islands == null) {
            throw new NullPointerException("islands");
        }
        this.islands = islands.clone();
        this.driveOrder = checkedPermutation(driveOrder, islands.length, "driveOrder");
        this.resolveOrder = checkedPermutation(resolveOrder, islands.length, "resolveOrder");
        this.commitOrder = checkedPermutation(commitOrder, islands.length, "commitOrder");
        this.doubleSpeed = doubleSpeed;
    }

    void setDoubleSpeed(boolean doubleSpeed) {
        this.doubleSpeed = doubleSpeed;
    }

    void tick() {
        fixedClockEnable = (halfDot & 1) == 0;
        cpuClockEnable = doubleSpeed || fixedClockEnable;

        wires.beginDrive();
        for (int index : driveOrder) {
            islands[index].drive(fixedClockEnable, cpuClockEnable, wires);
        }
        wires.resolve();
        for (int index : resolveOrder) {
            islands[index].resolve(fixedClockEnable, cpuClockEnable, wires);
        }
        for (int index : commitOrder) {
            islands[index].commit();
        }
        wires.finishCommit();
        halfDot++;
    }

    long halfDot() {
        return halfDot;
    }

    boolean fixedClockEnable() {
        return fixedClockEnable;
    }

    boolean cpuClockEnable() {
        return cpuClockEnable;
    }

    int lastRequestWires() {
        return wires.requests;
    }

    int lastAcknowledgeWires() {
        return wires.acknowledgements;
    }

    private static int[] checkedPermutation(int[] order, int size, String name) {
        if (order == null || order.length != size) {
            throw new IllegalArgumentException(name + " must contain every island once");
        }
        int[] copy = order.clone();
        boolean[] seen = new boolean[size];
        for (int index : copy) {
            if (index < 0 || index >= size || seen[index]) {
                throw new IllegalArgumentException(name + " must contain every island once");
            }
            seen[index] = true;
        }
        return copy;
    }
}
