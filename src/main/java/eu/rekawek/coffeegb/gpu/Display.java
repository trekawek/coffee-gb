package eu.rekawek.coffeegb.gpu;

public interface Display {

    void setPixel(int x, int y, int color);

    void refresh();

    void enableLcd();

    void disableLcd();

    Display NULL_DISPLAY = new Display() {

        @Override
        public void setPixel(int x, int y, int color) {
        }

        @Override
        public void refresh() {
        }

        @Override
        public void enableLcd() {
        }

        @Override
        public void disableLcd() {
        }
    };
}
