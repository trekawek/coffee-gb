package eu.rekawek.coffeegb.controller;

public interface ButtonListener {

    enum Button {
        RIGHT(0x01, 0x10), LEFT(0x02, 0x10), UP(0x04, 0x10), DOWN(0x08, 0x10),
        A(0x01, 0x20), B(0x02, 0x20), SELECT(0x04, 0x20), START(0x08, 0x20);

        private final int mask;

        private final int line;

        Button(int mask, int line) {
            this.mask = mask;
            this.line = line;
        }

        public int getMask() {
            return mask;
        }

        public int getLine() {
            return line;
        }
    }

    void onButtonPress(Button button);

    void onButtonRelease(Button button);

}
