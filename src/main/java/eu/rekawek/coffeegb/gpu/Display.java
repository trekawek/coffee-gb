package eu.rekawek.coffeegb.gpu;

public interface Display {

    int DISPLAY_WIDTH = 160;

    int DISPLAY_HEIGHT = 144;

    void putDmgPixel(int color);

    void putColorPixel(int gbcRgb);

    void frameIsReady();

    void enableLcd();

    void disableLcd();

    static int translateGbcRgb(int gbcRgb) {
        int r = (gbcRgb >> 0) & 0x1f;
        int g = (gbcRgb >> 5) & 0x1f;
        int b = (gbcRgb >> 10) & 0x1f;
        int result = (r * 8) << 16;
        result |= (g * 8) << 8;
        result |= (b * 8) << 0;
        return result;
    }

    Display NULL_DISPLAY = new Display() {

        @Override
        public void putDmgPixel(int color) {
        }

        @Override
        public void putColorPixel(int gbcRgb) {
        }

        @Override
        public void frameIsReady() {
        }

        @Override
        public void enableLcd() {
        }

        @Override
        public void disableLcd() {
        }
    };

}
