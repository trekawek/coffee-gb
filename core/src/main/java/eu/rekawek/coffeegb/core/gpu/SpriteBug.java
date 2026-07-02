package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;

/**
 * The DMG OAM corruption bug: putting a 16-bit value in the 0xFE00-0xFEFF range on the
 * internal bus (16-bit increment/decrement, push/pop, read/write) while the PPU is scanning
 * OAM corrupts the 8-byte row currently being accessed by the PPU.
 */
public final class SpriteBug {

    public enum CorruptionType {
        INC_DEC,
        POP_1,
        POP_2,
        PUSH_1,
        PUSH_2,
        LD_HL
    }

    private SpriteBug() {
    }

    public static void corruptOam(AddressSpace oamRam, CorruptionType type, int ticksInLine) {
        int row = ticksInLine / 4;
        switch (type) {
            case INC_DEC:
            case PUSH_1:
            case PUSH_2:
                writeCorruption(oamRam, row);
                break;

            case POP_1:
            case POP_2:
            case LD_HL:
                bothCorruption(oamRam, row);
                break;
        }
    }

    /**
     * A write (or a plain 16-bit increment/decrement) corrupts the first word of the
     * currently accessed row and copies the rest from the preceding row.
     */
    private static void writeCorruption(AddressSpace oam, int row) {
        if (row < 1 || row > 19) {
            return;
        }
        int a = word(oam, row, 0);
        int b = word(oam, row - 1, 0);
        int c = word(oam, row - 1, 2);
        setWord(oam, row, 0, ((a ^ c) & (b ^ c)) ^ c);
        copyWords(oam, row - 1, row);
    }

    private static void readCorruption(AddressSpace oam, int row) {
        if (row < 1 || row > 19) {
            return;
        }
        int a = word(oam, row, 0);
        int b = word(oam, row - 1, 0);
        int c = word(oam, row - 1, 2);
        setWord(oam, row, 0, b | (a & c));
        copyWords(oam, row - 1, row);
    }

    /**
     * A read combined with an increment/decrement in the same machine cycle (pop, ldi/ldd)
     * triggers an extra corruption of the preceding row first.
     */
    private static void bothCorruption(AddressSpace oam, int row) {
        if (row >= 4 && row < 19) {
            int a = word(oam, row - 2, 0);
            int b = word(oam, row - 1, 0);
            int c = word(oam, row, 0);
            int d = word(oam, row - 1, 2);
            setWord(oam, row - 1, 0, (b & (a | c | d)) | (a & c & d));
            for (int i = 0; i < 8; i++) {
                int v = oam.getByte(0xfe00 + (row - 1) * 8 + i);
                oam.setByte(0xfe00 + (row - 2) * 8 + i, v);
                oam.setByte(0xfe00 + row * 8 + i, v);
            }
        }
        readCorruption(oam, row);
    }

    // copies words 1..3
    private static void copyWords(AddressSpace oam, int fromRow, int toRow) {
        for (int i = 2; i < 8; i++) {
            oam.setByte(0xfe00 + toRow * 8 + i, oam.getByte(0xfe00 + fromRow * 8 + i));
        }
    }

    private static int word(AddressSpace oam, int row, int wordIndex) {
        int address = 0xfe00 + row * 8 + wordIndex * 2;
        return oam.getByte(address) | (oam.getByte(address + 1) << 8);
    }

    private static void setWord(AddressSpace oam, int row, int wordIndex, int value) {
        int address = 0xfe00 + row * 8 + wordIndex * 2;
        oam.setByte(address, value & 0xff);
        oam.setByte(address + 1, (value >> 8) & 0xff);
    }
}
