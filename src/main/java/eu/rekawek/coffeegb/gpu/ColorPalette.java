package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;

public class ColorPalette implements AddressSpace {

    private final int indexAddr;

    private final int dataAddr;

    private int[][] palettes = new int[8][4];

    private int index;

    private boolean autoIncrement;

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
            index = value & 0x3f;
            autoIncrement = (value & (1 << 7)) != 0;
        } else if (address == dataAddr) {
            int color = palettes[index / 8][(index % 8) / 2];
            if (index % 2 == 0) {
                color = (color & 0xff00) | value;
            } else {
                color = (color & 0x00ff) | (value << 8);
            }
            palettes[index / 8][(index % 8) / 2] = color;
            if (autoIncrement) {
                index = (index + 1) & 0x3f;
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public int getByte(int address) {
        if (address == indexAddr) {
            return index | (autoIncrement ? 0x80 : 0x00) | 0x40;
        } else if (address == dataAddr) {
            int color = palettes[index / 8][(index % 8) / 2];
            if (index % 2 == 0) {
                return color & 0xff;
            } else {
                return (color >> 8) & 0xff;
            }
        } else {
            throw new IllegalArgumentException();
        }
    }

    public int[] getPalette(int index) {
        return palettes[index];
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(i).append(": ");
            int[] palette = getPalette(i);
            for (int c : palette) {
                b.append(String.format("%04X", c)).append(' ');
            }
            b.setCharAt(b.length() - 1, '\n');
        }
        return b.toString();
    }

    public void fillWithFF() {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 4; j++) {
                palettes[i][j] = 0x7fff;
            }
        }
    }
}
