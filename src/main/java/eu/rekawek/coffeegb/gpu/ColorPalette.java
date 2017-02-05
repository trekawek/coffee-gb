package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;

public class ColorPalette implements AddressSpace {

    private final int indexAddr;

    private final int dataAddr;

    private int[] data = new int[0x40];

    private int index;

    public ColorPalette(int offset) {
        this.indexAddr = offset;
        this.dataAddr = offset + 1;
    }

    @Override
    public boolean accepts(int address) {
        return address == indexAddr || address == dataAddr;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == indexAddr) {
            index = value;
        } else if (address == dataAddr) {
            data[getIndex()] = value;
            if (isIncrementAfterWrite()) {
                index = (index & 0b11000000) | ((getIndex() + 1) & 0b00111111);
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public int getByte(int address) {
        if (address == indexAddr) {
            return index;
        } else if (address == dataAddr) {
            return data[getIndex()];
        } else {
            throw new IllegalArgumentException();
        }
    }

    public int[] getPalette(int index) {
        if (index < 0 || index >= 8) {
            throw new IllegalArgumentException();
        }
        int[] palette = new int[4];
        for (int i = 0; i < palette.length; i++) {
            int b1 = data[index * 8 + i];
            int b2 = data[index * 8 + i + 1];
            palette[i] = (b2 << 8) | b1;
        }
        return palette;
    }

    private boolean isIncrementAfterWrite() {
        return (index & (1 << 7)) != 0;
    }

    private int getIndex() {
        return index & 0x3f;
    }
}
