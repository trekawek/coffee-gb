package eu.rekawek.coffeegb.gpu;

import java.util.NoSuchElementException;

public class IntQueue {

    private final int[] array;

    private int size;

    private int offset = 0;

    public IntQueue(int capacity) {
        this.array = new int[capacity];
        this.size = 0;
        this.offset = 0;
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
}
