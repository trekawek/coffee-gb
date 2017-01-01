package eu.rekawek.coffeegb.cpu;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.clearBit;
import static eu.rekawek.coffeegb.cpu.BitUtils.getBit;
import static eu.rekawek.coffeegb.cpu.BitUtils.setBit;

class Flags {

    private static int Z_POS = 7;

    private static int N_POS = 6;

    private static int H_POS = 5;

    private static int C_POS = 4;

    private int flags;

    public int getFlagsByte() {
        return flags;
    }

    public boolean isZ() {
        return getBit(flags, Z_POS);
    }

    public boolean isN() {
        return getBit(flags, N_POS);
    }

    public boolean isH() {
        return getBit(flags, H_POS);
    }

    public boolean isC() {
        return getBit(flags, C_POS);
    }

    public void setZ() {
        setBit(flags, Z_POS);
    }

    public void setN() {
        setBit(flags, N_POS);
    }

    public void setH() {
        setBit(flags, H_POS);
    }

    public void setC() {
        setBit(flags, C_POS);
    }

    public void clearZ() {
        clearBit(flags, Z_POS);
    }

    public void clearN() {
        clearBit(flags, N_POS);
    }

    public void clearH() {
        clearBit(flags, H_POS);
    }

    public void clearC() {
        clearBit(flags, C_POS);
    }

    public void setFlagsByte(int flags) {
        checkByteArgument("flags", flags);
        this.flags = flags;
    }
}
