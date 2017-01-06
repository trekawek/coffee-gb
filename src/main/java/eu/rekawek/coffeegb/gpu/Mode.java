package eu.rekawek.coffeegb.gpu;

public enum Mode {
    H_Blank(51), V_Blank(114), OAM_Search(20), PixelTransfer(43);

    private final int cycles;

    Mode(int cycles) {
        this.cycles = cycles;
    }

    public int getCycles() {
        return cycles;
    }
}
