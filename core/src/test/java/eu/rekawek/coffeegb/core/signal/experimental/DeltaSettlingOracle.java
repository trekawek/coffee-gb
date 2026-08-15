package eu.rekawek.coffeegb.core.signal.experimental;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Allocation-heavy, test-only Boolean fixed-point oracle. Cells read immutable old signal/storage
 * vectors and publish atomically; transparent/async storage settles around each atomic DFF edge.
 * Vector clones and delta history aid diagnostics but deliberately disqualify it from the hot path.
 */
final class DeltaSettlingOracle {

    enum Dominance {
        SET,
        CLEAR
    }

    record Wire(DeltaSettlingOracle owner, int index, String name) {}

    static final class SavedState {

        private final DeltaSettlingOracle owner;

        private final boolean[] inputs;

        private final boolean[] storage;

        private SavedState(DeltaSettlingOracle owner, boolean[] inputs, boolean[] storage) {
            this.owner = owner;
            this.inputs = inputs;
            this.storage = storage;
        }

        int inputBitCount() {
            return inputs.length;
        }

        int storageBitCount() {
            return storage.length;
        }
    }

    record EdgeResult(int preEdgeDeltas, int postEdgeDeltas) {}

    static final class NonConvergentException extends IllegalStateException {

        private final int firstSeenDelta;

        private final int stoppedAtDelta;

        private NonConvergentException(String message, int firstSeenDelta, int stoppedAtDelta) {
            super(message);
            this.firstSeenDelta = firstSeenDelta;
            this.stoppedAtDelta = stoppedAtDelta;
        }

        int firstSeenDelta() {
            return firstSeenDelta;
        }

        int stoppedAtDelta() {
            return stoppedAtDelta;
        }
    }

    private enum GateKind { NOT, NOR }

    private abstract static class Cell {

        final int output;

        final int storage;

        Cell(int output, int storage) {
            this.output = output;
            this.storage = storage;
        }

        abstract boolean resolve(boolean[] signals, boolean[] storage);

        boolean edgeTriggered() {
            return false;
        }

        boolean capture(boolean[] signals) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class GateCell extends Cell {

        private final GateKind kind;

        private final int[] inputs;

        GateCell(int output, GateKind kind, int[] inputs) {
            super(output, -1);
            this.kind = kind;
            this.inputs = inputs;
        }

        @Override
        boolean resolve(boolean[] signals, boolean[] ignored) {
            return switch (kind) {
                case NOT -> !signals[inputs[0]];
                case NOR -> !any(signals);
            };
        }

        private boolean any(boolean[] signals) {
            for (int input : inputs) {
                if (signals[input]) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class StorageCell extends Cell {

        private final int data;

        private final int gate;

        private final int set;

        private final int clear;

        private final Dominance dominance;

        private final boolean dff;

        StorageCell(int output, int storage, int data, int gate, int set, int clear,
                Dominance dominance, boolean dff) {
            super(output, storage);
            this.data = data;
            this.gate = gate;
            this.set = set;
            this.clear = clear;
            this.dominance = dominance;
            this.dff = dff;
        }

        @Override
        boolean resolve(boolean[] signals, boolean[] storage) {
            Boolean asynchronous = asynchronous(signals);
            if (asynchronous != null) {
                return asynchronous;
            }
            return !dff && signals[gate] ? signals[data] : storage[this.storage];
        }

        @Override
        boolean edgeTriggered() {
            return dff;
        }

        @Override
        boolean capture(boolean[] signals) {
            Boolean asynchronous = asynchronous(signals);
            return asynchronous != null ? asynchronous : signals[data];
        }

        private Boolean asynchronous(boolean[] signals) {
            boolean setLevel = signals[set];
            boolean clearLevel = signals[clear];
            if (setLevel && clearLevel) {
                return dominance == Dominance.SET;
            }
            if (setLevel) {
                return true;
            }
            return clearLevel ? Boolean.FALSE : null;
        }
    }

    private record Vector(boolean[] signals, boolean[] storage) {

        private Vector {
            signals = signals.clone();
            storage = storage.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Vector vector
                    && Arrays.equals(signals, vector.signals)
                    && Arrays.equals(storage, vector.storage);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(signals) + Arrays.hashCode(storage);
        }

        String compact() {
            return bits(signals) + "/" + bits(storage);
        }

        private static String bits(boolean[] values) {
            StringBuilder result = new StringBuilder(values.length);
            for (boolean value : values) {
                result.append(value ? '1' : '0');
            }
            return result.toString();
        }
    }

    private final int maxChangingDeltas;

    private final List<Cell> cells = new ArrayList<>();

    private boolean[] signals = new boolean[0];

    private boolean[] storage = new boolean[0];

    private boolean[] driven = new boolean[0];

    private int[] order;

    private boolean frozen;

    DeltaSettlingOracle(int maxChangingDeltas) {
        if (maxChangingDeltas < 1) {
            throw new IllegalArgumentException("maxChangingDeltas must be positive");
        }
        this.maxChangingDeltas = maxChangingDeltas;
    }

    Wire wire(String name, boolean initialLevel) {
        mutable();
        if (name == null) {
            throw new NullPointerException("name");
        }
        int index = signals.length;
        signals = Arrays.copyOf(signals, index + 1);
        signals[index] = initialLevel;
        driven = Arrays.copyOf(driven, index + 1);
        return new Wire(this, index, name);
    }

    void driveNot(Wire out, Wire in) {
        driveGate(out, GateKind.NOT, in);
    }

    void driveNor(Wire out, Wire... in) {
        driveGate(out, GateKind.NOR, in);
    }

    void driveTransparentLatch(Wire out, Wire data, Wire gate, Wire set, Wire clear,
            Dominance dominance, boolean initialQ) {
        driveStorage(out, data, gate, set, clear, dominance, initialQ, false);
    }

    void driveDff(Wire out, Wire data, Wire set, Wire clear,
            Dominance dominance, boolean initialQ) {
        driveStorage(out, data, null, set, clear, dominance, initialQ, true);
    }

    void setInput(Wire wire, boolean level) {
        int index = index(wire);
        if (driven[index]) {
            throw new IllegalArgumentException(wire + " is cell-driven");
        }
        signals[index] = level;
    }

    boolean level(Wire wire) {
        return signals[index(wire)];
    }

    /** Returns the number of changing publications; the final stable comparison is not counted. */
    int settle() {
        freeze();
        int delta = 0;
        Map<Vector, Integer> seen = new HashMap<>();
        seen.put(new Vector(signals, storage), 0);
        while (true) {
            boolean[] nextSignals = signals.clone();
            boolean[] nextStorage = storage.clone();
            for (int cellIndex : order) {
                Cell cell = cells.get(cellIndex);
                boolean value = cell.resolve(signals, storage);
                nextSignals[cell.output] = value;
                if (cell.storage >= 0) {
                    nextStorage[cell.storage] = value;
                }
            }
            if (Arrays.equals(signals, nextSignals) && Arrays.equals(storage, nextStorage)) {
                return delta;
            }
            if (delta == maxChangingDeltas) {
                throw new NonConvergentException(
                        "signal network exceeded " + maxChangingDeltas + " changing deltas",
                        -1, delta);
            }
            signals = nextSignals;
            storage = nextStorage;
            Vector vector = new Vector(signals, storage);
            Integer first = seen.putIfAbsent(vector, ++delta);
            if (first != null) {
                throw new NonConvergentException(
                        "signal vector " + vector.compact() + " first seen at delta "
                                + first + " repeated at delta " + delta,
                        first, delta);
            }
        }
    }

    EdgeResult edge() {
        int before = settle();
        boolean[] captured = storage.clone();
        for (Cell cell : cells) {
            if (cell.edgeTriggered()) {
                captured[cell.storage] = cell.capture(signals);
            }
        }
        storage = captured;
        return new EdgeResult(before, settle());
    }

    SavedState save() {
        settle();
        boolean[] inputs = new boolean[signals.length - cells.size()];
        for (int wire = 0, input = 0; wire < signals.length; wire++) {
            if (!driven[wire]) {
                inputs[input++] = signals[wire];
            }
        }
        return new SavedState(this, inputs, storage.clone());
    }

    int restore(SavedState state) {
        freeze();
        if (state == null || state.owner != this) {
            throw new IllegalArgumentException("state belongs to another network");
        }
        storage = state.storage.clone();
        Arrays.fill(signals, false); // Transient combinational outputs are intentionally absent.
        for (int wire = 0, input = 0; wire < signals.length; wire++) {
            if (!driven[wire]) {
                signals[wire] = state.inputs[input++];
            }
        }
        for (Cell cell : cells) {
            if (cell.storage >= 0) {
                signals[cell.output] = storage[cell.storage];
            }
        }
        return settle();
    }

    void useReverseCellOrder() {
        freeze();
        order = naturalOrder();
        for (int left = 0, right = order.length - 1; left < right; left++, right--) {
            int value = order[left];
            order[left] = order[right];
            order[right] = value;
        }
    }

    void useShuffledCellOrder(long seed) {
        freeze();
        order = naturalOrder();
        Random random = new Random(seed);
        for (int i = order.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            int value = order[i];
            order[i] = order[other];
            order[other] = value;
        }
    }

    private void driveGate(Wire out, GateKind kind, Wire... in) {
        mutable();
        int output = freeOutput(out);
        if (in == null || in.length == 0) {
            throw new IllegalArgumentException(kind + " requires an input");
        }
        int[] inputs = new int[in.length];
        for (int i = 0; i < in.length; i++) {
            inputs[i] = index(in[i]);
        }
        cells.add(new GateCell(output, kind, inputs));
        driven[output] = true;
    }

    private void driveStorage(Wire out, Wire data, Wire gate, Wire set, Wire clear,
            Dominance dominance, boolean initialQ, boolean dff) {
        mutable();
        int output = freeOutput(out);
        int dataIndex = index(data);
        int gateIndex = dff ? -1 : index(gate);
        int setIndex = index(set);
        int clearIndex = index(clear);
        if (dominance == null) {
            throw new NullPointerException("dominance");
        }
        int state = storage.length;
        storage = Arrays.copyOf(storage, state + 1);
        storage[state] = initialQ;
        signals[output] = initialQ;
        cells.add(new StorageCell(output, state, dataIndex, gateIndex,
                setIndex, clearIndex, dominance, dff));
        driven[output] = true;
    }

    private int freeOutput(Wire wire) {
        int output = index(wire);
        if (driven[output]) {
            throw new IllegalArgumentException(wire + " already has a driver");
        }
        return output;
    }

    private int index(Wire wire) {
        if (wire == null || wire.owner() != this) {
            throw new IllegalArgumentException("wire belongs to another network");
        }
        return wire.index();
    }

    private void freeze() {
        if (!frozen) {
            frozen = true;
            order = naturalOrder();
        }
    }

    private int[] naturalOrder() {
        int[] result = new int[cells.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = i;
        }
        return result;
    }

    private void mutable() {
        if (frozen) {
            throw new IllegalStateException("topology is frozen");
        }
    }
}
