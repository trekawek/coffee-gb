package eu.rekawek.coffeegb.cpu;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.getBit;
import static eu.rekawek.coffeegb.cpu.BitUtils.setBit;

public class Flags {

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

    public void setZ(boolean z) {
        flags = setBit(flags, Z_POS, z);
    }

    public void setN(boolean n) {
        flags = setBit(flags, N_POS, n);
    }

    public void setH(boolean h) {
        flags = setBit(flags, H_POS, h);
    }

    public void setC(boolean c) {
        flags = setBit(flags, C_POS, c);
    }

    public void setFlagsByte(int flags) {
        checkByteArgument("flags", flags);
        this.flags = flags & 0xf0;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(isZ() ? 'Z' : '-');
        result.append(isN() ? 'N' : '-');
        result.append(isH() ? 'H' : '-');
        result.append(isC() ? 'C' : '-');
        result.append("----");
        return result.toString();
    }
}
