package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;

/**
 * The DMG OAM corruption bug: putting a 16-bit value in the 0xFE00-0xFEFF range on the
 * internal bus (16-bit increment/decrement, push/pop, read/write) while the PPU is scanning
 * OAM corrupts the 8-byte row currently being accessed by the PPU.
 *
 * <p>The corruption patterns follow SameBoy's hardware-verified model: writes (and plain
 * increments/decrements) glitch the first word of the accessed row and copy the rest from
 * the preceding row; reads combined with an increment (pop, ldi/ldd) additionally corrupt
 * the preceding rows with row-dependent patterns.
 */
public final class SpriteBug {

    public enum CorruptionType {
        INC_DEC,
        POP_1,
        POP_2,
        PUSH_INTERNAL,
        PUSH_1,
        PUSH_2,
        LD_HL
    }

    private SpriteBug() {
    }

    public static void corruptOam(AddressSpace oamRam, CorruptionType type, int row) {
        switch (type) {
            case INC_DEC:
            case PUSH_INTERNAL:
            case PUSH_1:
            case PUSH_2:
                writeCorruption(oamRam, row);
                break;

            case POP_1:
            case POP_2:
            case LD_HL:
                readCorruption(oamRam, row);
                break;
        }
    }

    /**
     * Applies the corruption caused by a CPU read from locked OAM. Rows 0 and 20 are
     * the two address-dependent latch states immediately before and after the regular
     * 19 scan rows.
     */
    public static void corruptOamRead(AddressSpace oamRam, int address, int row) {
        if (row == 0) {
            firstRowReadCorruption(oamRam, address);
        } else if (row == 20) {
            lastRowReadCorruption(oamRam, address);
        } else {
            readCorruption(oamRam, row);
        }
    }

    /** Applies the corruption caused by a CPU write to locked OAM. */
    public static void corruptOamWrite(AddressSpace oamRam, int row) {
        writeCorruption(oamRam, row);
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

    /**
     * A read combined with an increment/decrement in the same machine cycle (pop, ldi/ldd)
     * corrupts the preceding row (and more) with row-dependent glitch patterns.
     */
    private static void readCorruption(AddressSpace oam, int row) {
        if (row < 1 || row > 19) {
            return;
        }
        if (row % 4 == 2) {
            if (row < 19) {
                int a = word(oam, row - 2, 0);
                int b = word(oam, row - 1, 0);
                int c = word(oam, row, 0);
                int d = word(oam, row - 1, 2);
                setWord(oam, row - 1, 0, (b & (a | c | d)) | (a & c & d));
                copyRow(oam, row - 1, row - 2);
            }
        } else if (row % 4 == 0) {
            if (row < 19) {
                if (row == 8) {
                    int b = word(oam, row, 0);
                    int c = word(oam, row - 1, 2);
                    int d = word(oam, row - 1, 1);
                    int e = word(oam, row - 1, 0);
                    int f = word(oam, row - 2, 1);
                    int g = word(oam, row - 2, 0);
                    int h = word(oam, row - 4, 0);
                    setWord(oam, row - 1, 0, (e & (h | g | (~d & 0xffff & f) | c | b)) | (c & g & h));
                } else {
                    int a = word(oam, row, 0);
                    int b = word(oam, row - 1, 2);
                    int c = word(oam, row - 1, 0);
                    int d = word(oam, row - 2, 0);
                    int e = word(oam, row - 4, 0);
                    int v;
                    if (row == 4) {
                        v = (c & (a | b | d | e)) | (a & b & d & e);
                    } else if (row == 12) {
                        v = (c & (a | b | d | e)) | (b & d & e);
                    } else {
                        v = c | (a & b & d & e);
                    }
                    setWord(oam, row - 1, 0, v);
                }
                copyRow(oam, row - 1, row - 2);
                copyRow(oam, row - 1, row - 4);
            }
        } else {
            int a = word(oam, row, 0);
            int b = word(oam, row - 1, 0);
            int c = word(oam, row - 1, 2);
            int v = b | (a & c);
            setWord(oam, row - 1, 0, v);
            setWord(oam, row, 0, v);
        }
        copyRow(oam, row - 1, row);
        if (row == 16) {
            copyRow(oam, 16, 0);
        }
    }

    private static void firstRowReadCorruption(AddressSpace oam, int address) {
        int relativeAddress = address - 0xfe00;
        int addressedRow = (relativeAddress & 0xf8) / 8;
        int addressedWord = (relativeAddress & 0x06) / 2;
        int a = word(oam, 0, 0);
        int b = word(oam, addressedRow, 0);
        int c = word(oam, addressedRow, addressedWord);
        int value = b | (a & c);
        setWord(oam, addressedRow, 0, value);
        setWord(oam, 0, 0, value);
        for (int i = 2; i < 8; i++) {
            oam.setByte(0xfe00 + i, oam.getByte(0xfe00 + addressedRow * 8 + i));
        }
    }

    private static void lastRowReadCorruption(AddressSpace oam, int address) {
        int relativeAddress = address - 0xfe00;
        int addressedRow = (relativeAddress & 0xf8) / 8;
        int byteOffset = relativeAddress & 7;
        int targetWord = byteOffset / 2;
        int a = word(oam, 19, 2);
        int b = word(oam, 19, targetWord);
        int c = word(oam, addressedRow, 0);
        int value;
        switch (byteOffset) {
            case 0:
            case 1:
                value = (a & b) | (a & c) | (b & c);
                setWord(oam, 19, targetWord, value);
                break;
            case 2:
            case 3:
                c = word(oam, addressedRow, targetWord);
                value = (a & b) | (a & c) | (b & c);
                setWord(oam, 19, targetWord, value);
                break;
            case 4:
            case 5:
                break;
            case 6:
            case 7:
                value = b | (a & c);
                setWord(oam, 19, targetWord, value);
                break;
            default:
                throw new AssertionError();
        }
        copyRow(oam, 19, addressedRow);
    }

    private static void copyRow(AddressSpace oam, int fromRow, int toRow) {
        for (int i = 0; i < 8; i++) {
            oam.setByte(0xfe00 + toRow * 8 + i, oam.getByte(0xfe00 + fromRow * 8 + i));
        }
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
