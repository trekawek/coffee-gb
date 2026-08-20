package eu.rekawek.coffeegb.core.gpu;

/**
 * Small allocation-free structural helpers used by the timing-only PPU FIFO.
 *
 * <p>The visible FIFO keeps the pixel payload and object metadata.  The unshifted timing
 * machine only needs the occupancy and alignment state that is observable by the fetcher.
 * These helpers deliberately have no dependency on rendering, diagnostics, or host output.</p>
 */
final class ScalarTimingFifoSupport {

    static final int BACKGROUND_CAPACITY = 16;

    static final int SPRITE_CAPACITY = 8;

    private ScalarTimingFifoSupport() {
    }

    static final class Queue {

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

        void clear() {
            size = 0;
        }

        void copyTo(Queue target) {
            if (target.size + size > BACKGROUND_CAPACITY) {
                throw new IllegalStateException("Queue is full");
            }
            target.size += size;
        }

        State captureState() {
            return new State(size);
        }

        void restoreState(State state) {
            if (state == null || state.size < 0 || state.size > BACKGROUND_CAPACITY) {
                throw new IllegalArgumentException("Invalid timing queue length");
            }
            size = state.size;
        }

        private void ensureCapacity(int added) {
            if (size + added > BACKGROUND_CAPACITY) {
                throw new IllegalStateException("Queue is full");
            }
        }

        record State(int size) {
        }
    }

    static final class Sprite {

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
            } else if (size < SPRITE_CAPACITY) {
                size++;
            }
        }

        void overlay() {
            // A new object fetch establishes an eight-pixel alignment.  Values and object
            // priority are payload-only for the timing skeleton.
            underflow = 0;
            if (size < SPRITE_CAPACITY) {
                size = SPRITE_CAPACITY;
            }
        }

        void refresh() {
            // Object refresh only changes payload in the production FIFO.
        }

        State captureState() {
            return new State(size, underflow);
        }

        void restoreState(State state) {
            if (state == null
                    || state.size < 0
                    || state.size > SPRITE_CAPACITY
                    || state.underflow < 0) {
                throw new IllegalArgumentException("Invalid timing sprite FIFO state");
            }
            size = state.size;
            underflow = state.underflow;
        }

        record State(int size, int underflow) {
        }
    }
}
