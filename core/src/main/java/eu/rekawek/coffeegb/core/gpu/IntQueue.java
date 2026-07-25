package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.util.NoSuchElementException;

public class IntQueue implements StatefulComponent<IntQueue> {

    private final int[] array;

    private int size;

    private int offset;

    public IntQueue(int capacity) {
        this.array = new int[capacity];
    }

    public int size() {
        return size;
    }

    public void enqueue(int value) {
        if (size == array.length) {
            throw new IllegalStateException("Queue is full");
        }
        array[(offset + size) % array.length] = value;
        size++;
    }

    public int dequeue() {
        if (size == 0) {
            throw new NoSuchElementException("Queue is empty");
        }
        size--;
        int value = array[offset++];
        if (offset == array.length) {
            offset = 0;
        }
        return value;
    }

    public int get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return array[(offset + index) % array.length];
    }

    public void set(int index, int value) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        array[(offset + index) % array.length] = value;
    }

    public void clear() {
        size = 0;
        offset = 0;
    }

    @Override
    public ComponentState<IntQueue> captureState() {
        return new IntQueueState(array.clone(), size, offset);
    }

    @Override
    public ComponentState<IntQueue> captureState(MachineStateCapture capture) {
        return new IntQueueState(capture.ints(array), size, offset);
    }

    @Override
    public void restoreState(ComponentState<IntQueue> state) {
        if (!(state instanceof IntQueueState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.array.length != mem.array.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        System.arraycopy(mem.array, 0, this.array, 0, this.array.length);
        this.size = mem.size;
        this.offset = mem.offset;
    }

    private record IntQueueState(int[] array, int size, int offset) implements ComponentState<IntQueue> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record IntQueueMemento(int[] array, int size, int offset) implements Memento<IntQueue> {
    }
}
