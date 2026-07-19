package eu.rekawek.coffeegb.core;

public interface AddressSpace {

    boolean accepts(int address);

    void setByte(int address, int value);

    /**
     * Writes a byte through the CPU bus. Most devices observe CPU and non-CPU writes
     * identically, so the default delegates to {@link #setByte(int, int)}. Devices with
     * a separate CPU-to-peripheral synchronizer can override this entry point without
     * changing debugger, DMA, boot-state, or test-fixture writes.
     */
    default void setByteFromCpu(int address, int value) {
        setByte(address, value);
    }

    int getByte(int address);
}
