package eu.rekawek.coffeegb.core.gpu;

/** Scalar queue lengths used only by the E2a timing-only FIFO models. */
final class TimingQueueLength {

    static final int CAPACITY = 16;

    private int size;

    int size() {
        return size;
    }

    void enqueue() {
        ensureCapacity(1);
        size++;
    }

    void enqueue8() {
        ensureCapacity(8);
        size += 8;
    }

    void dequeue() {
        if (size == 0) {
            throw new IllegalStateException("Queue is empty");
        }
        size--;
    }

    void drop() {
        dequeue();
    }

    void clear() {
        size = 0;
    }

    void copyTo(TimingQueueLength target) {
        if (target.size + size > CAPACITY) {
            throw new IllegalStateException("Queue is full");
        }
        target.size += size;
    }

    State captureState() {
        return new State(size);
    }

    void restoreState(State state) {
        if (state.size < 0 || state.size > CAPACITY) {
            throw new IllegalArgumentException("Invalid timing queue length");
        }
        size = state.size;
    }

    TimingQueueProjection projection() {
        return new TimingQueueProjection(CAPACITY, size);
    }

    private void ensureCapacity(int added) {
        if (size + added > CAPACITY) {
            throw new IllegalStateException("Queue is full");
        }
    }

    record State(int size) {
    }
}

/** Structural copy of the object FIFO's occupancy and overlay alignment rules. */
final class TimingSpriteFifo {

    private static final int OVERLAY_LENGTH = 8;

    private int size;

    private int underflow;

    void clear() {
        size = 0;
        underflow = 0;
    }

    void pop() {
        if (size == 0) {
            underflow++;
        } else {
            size--;
        }
    }

    void rewind() {
        if (underflow > 0) {
            underflow--;
        } else if (size < OVERLAY_LENGTH) {
            size++;
        }
    }

    void overlay() {
        // A fresh object fetch restores an eight-pixel alignment and discards any
        // underflow caused by left-edge pops. Pixel values and priority are irrelevant
        // to the timing skeleton, so the payload merge is intentionally omitted.
        underflow = 0;
        if (size < OVERLAY_LENGTH) {
            size = OVERLAY_LENGTH;
        }
    }

    /** Object refresh changes payload only; it cannot change structural occupancy. */
    void refresh() {
    }

    State captureState() {
        return new State(size, underflow);
    }

    void restoreState(State state) {
        if (state.size < 0 || state.size > OVERLAY_LENGTH || state.underflow < 0) {
            throw new IllegalArgumentException("Invalid timing sprite FIFO state");
        }
        size = state.size;
        underflow = state.underflow;
    }

    TimingSpriteProjection projection() {
        return new TimingSpriteProjection(size, underflow);
    }

    record State(int size, int underflow) {
    }
}

record TimingQueueProjection(int capacity, int size) {
}

record TimingSpriteProjection(int size, int underflow) {
}

record TimingFifoProjection(
        TimingQueueProjection activeBg,
        TimingQueueProjection retainedBg,
        TimingSpriteProjection sprite,
        long[] delayStamp,
        int delayHead,
        int delaySize,
        long outputTicks,
        int linePixels,
        int outCount,
        boolean firstEntryPresent) {

    TimingFifoProjection {
        delayStamp = delayStamp.clone();
    }

    @Override
    public long[] delayStamp() {
        return delayStamp.clone();
    }
}
