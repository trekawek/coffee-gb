package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;
import java.util.NoSuchElementException;

public class IntQueue implements Serializable, Originator<IntQueue> {

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
    public Memento<IntQueue> saveToMemento() {
        return new IntQueueMemento(array.clone(), size, offset);
    }

    @Override
    public void restoreFromMemento(Memento<IntQueue> memento) {
        if (!(memento instanceof IntQueueMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.array.length != mem.array.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(mem.array, 0, this.array, 0, this.array.length);
        this.size = mem.size;
        this.offset = mem.offset;
    }

    private record IntQueueMemento(int[] array, int size, int offset) implements Memento<IntQueue> {
    }
}