package eu.rekawek.coffeegb.gpu;

public enum Mode {
    HBlank(3),
    VBlank(4),
    OamSearch(5),
    PixelTransfer(-1);

    final int statBit;

    Mode(int statBit) {
        this.statBit = statBit;
    }
}
