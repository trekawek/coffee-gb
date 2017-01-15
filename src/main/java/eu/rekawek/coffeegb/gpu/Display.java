package eu.rekawek.coffeegb.gpu;

public interface Display {

    void setPixel(int x, int y, int color);

    void requestRefresh();

    void waitForRefresh();

    void enableLcd();

    void disableLcd();

    Display NULL_DISPLAY = new Display() {

        @Override
        public void setPixel(int x, int y, int color) {
        }

        @Override
        public void requestRefresh() {
        }

        @Override
        public void waitForRefresh() {
        }

        @Override
        public void enableLcd() {
        }

        @Override
        public void disableLcd() {
        }
    };

}
