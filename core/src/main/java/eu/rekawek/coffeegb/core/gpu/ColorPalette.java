package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class ColorPalette implements AddressSpace, Serializable, Originator<ColorPalette> {

    // Object palette RAM observed immediately after the CGB boot ROM. Some early
    // homebrew, including Vila Caldan, relies on these values for its sprites.
    private static final int[] CGB_OBJECT_PALETTE_BOOT_VALUES = {
            0x0000, 0xabf2, 0xc261, 0xbad9,
            0x6e88, 0x63dd, 0x2728, 0x9ffb,
            0x4235, 0xd4d6, 0x4850, 0x5e57,
            0x3e23, 0xca3d, 0x2171, 0xc037,
            0xb3c6, 0xf9fb, 0x0008, 0x298d,
            0x20a3, 0x87db, 0x0562, 0xd45d,
            0x080e, 0xaffe, 0x0220, 0xffd7,
            0x6a07, 0xec55, 0x4083, 0x770b
    };

    private final int indexAddr;

    private final int dataAddr;

    private final int[][] palettes = new int[8][4];

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

    public boolean isDataAddress(int address) {
        return address == dataAddr;
    }

    /**
     * A palette-data (BGPD/OCPD) write while the CGB PPU is in mode 3 is dropped by the
     * hardware, but the auto-increment of the index still happens (SameBoy's
     * cgb_palettes_blocked). The index register (BGPI/OCPI) itself is never blocked.
     */
    public void blockedDataWrite() {
        if (autoIncrement) {
            index = (index + 1) & 0x3f;
        }
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

    void initializeCgbBootValues() {
        for (int i = 0; i < CGB_OBJECT_PALETTE_BOOT_VALUES.length; i++) {
            palettes[i / 4][i % 4] = CGB_OBJECT_PALETTE_BOOT_VALUES[i];
        }
    }

    @Override
    public Memento<ColorPalette> saveToMemento() {
        int[][] palettesCopy = new int[palettes.length][];
        for (int i = 0; i < palettes.length; i++) {
            palettesCopy[i] = palettes[i].clone();
        }
        return new ColorPaletteMemento(palettesCopy, index, autoIncrement);
    }

    @Override
    public void restoreFromMemento(Memento<ColorPalette> memento) {
        if (!(memento instanceof ColorPaletteMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.palettes.length != mem.palettes.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        for (int i = 0; i < this.palettes.length; i++) {
            if (this.palettes[i].length != mem.palettes[i].length) {
                throw new IllegalArgumentException("Memento array length doesn't match");
            }
            System.arraycopy(mem.palettes[i], 0, this.palettes[i], 0, this.palettes[i].length);
        }
        this.index = mem.index;
        this.autoIncrement = mem.autoIncrement;
    }

    private record ColorPaletteMemento(int[][] palettes, int index,
                                       boolean autoIncrement) implements Memento<ColorPalette> {
    }
}
