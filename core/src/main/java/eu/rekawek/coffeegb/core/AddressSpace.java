package eu.rekawek.coffeegb.core;

public interface AddressSpace {

    boolean accepts(int address);

    void setByte(int address, int value);

    int getByte(int address);
}
