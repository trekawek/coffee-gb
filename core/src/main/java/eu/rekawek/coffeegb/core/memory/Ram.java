package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class Ram implements AddressSpace, StatefulComponent<Ram> {

    private final int[] space;

    private final int length;

    private final int offset;

    public Ram(int offset, int length) {
        this.space = new int[length];
        this.length = length;
        this.offset = offset;
    }

    @Override
    public boolean accepts(int address) {
        return address >= offset && address < offset + length;
    }

    @Override
    public void setByte(int address, int value) {
        space[address - offset] = value;
    }

    @Override
    public int getByte(int address) {
        int index = address - offset;
        if (index < 0 || index >= space.length) {
            throw new IndexOutOfBoundsException("Address: " + address);
        }
        return space[index];
    }

    public int[] getSpace() {
        return space;
    }

    @Override
    public ComponentState<Ram> captureState() {
        return new RamState(space.clone());
    }

    @Override
    public ComponentState<Ram> captureState(MachineStateCapture capture) {
        return new RamState(capture.ints(space));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts(space);
    }

    @Override
    public void restoreState(ComponentState<Ram> state) {
        if (!(state instanceof RamState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.space.length != mem.space.length) {
            throw new IllegalArgumentException("ComponentState space length doesn't match");
        }
        System.arraycopy(mem.space, 0, this.space, 0, this.space.length);
    }

    public record RamState(int[] space) implements ComponentState<Ram> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    public record RamMemento(int[] space) implements Memento<Ram> {
    }
}
