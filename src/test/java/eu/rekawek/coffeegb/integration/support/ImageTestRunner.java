package eu.rekawek.coffeegb.integration.support;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.sound.SoundOutput;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static eu.rekawek.coffeegb.integration.support.RomTestUtils.isByteSequenceAtPc;

public class ImageTestRunner {

    private static final int MAX_TICKS = 2_000_000;

    private final TestDisplay display;

    private final Gameboy gb;

    private final File imageFile;

    public ImageTestRunner(File romFile) throws IOException {
        EventBus eventBus = new EventBus();
        Cartridge cart = new Cartridge(romFile);
        display = new TestDisplay();
        gb = new Gameboy(cart, eventBus);
        gb.init(display, SoundOutput.NULL_OUTPUT, SerialEndpoint.NULL_ENDPOINT, null);
        imageFile = new File(romFile.getParentFile(), romFile.getName().replace(".gb", ".png"));
    }

    public TestResult runTest() throws Exception {
        int i = 0;
        while (!isByteSequenceAtPc(gb, 0x40)) {
            gb.tick();
            if (i++ > MAX_TICKS) {
                throw new Exception("The test is not finished after " + i + " ticks.");
            }
        }
        return new TestResult(display.getRGB(), getExpectedRGB());
    }

    private int[] getExpectedRGB() throws Exception {
        BufferedImage expectedImg = ImageIO.read(imageFile);
        int[] rgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        for (int y = 0; y < Display.DISPLAY_HEIGHT; y++) {
            for (int x = 0; x < Display.DISPLAY_WIDTH; x++) {
                rgb[y * Display.DISPLAY_WIDTH + x] = expectedImg.getRGB(x, y) & 0xffffff;
            }
        }
        return rgb;
    }

    public static class TestDisplay implements Display {

        private static final int[] COLORS = new int[]{0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};

        private final int[] rgb;

        private int i = 0;

        public TestDisplay() {
            super();
            rgb = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
        }

        @Override
        public void putDmgPixel(int color) {
            rgb[i++] = COLORS[color];
            i = i % rgb.length;
        }

        @Override
        public void putColorPixel(int gbcRgb) {
            rgb[i++] = Display.translateGbcRgb(gbcRgb);
        }

        @Override
        public void frameIsReady() {
            i = 0;
        }

        @Override
        public void enableLcd() {
        }

        @Override
        public void disableLcd() {
        }

        public int[] getRGB() {
            return rgb;
        }
    }


    public static class TestResult {

        private final int[] resultRGB;

        private final int[] expectedRGB;

        public TestResult(int[] resultRGB, int[] expectedRGB) {
            this.resultRGB = resultRGB;
            this.expectedRGB = expectedRGB;
        }

        public int[] getResultRGB() {
            return resultRGB;
        }

        public int[] getExpectedRGB() {
            return expectedRGB;
        }

        public void writeResultToFile(File file) throws IOException {
            BufferedImage img = new BufferedImage(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < Display.DISPLAY_HEIGHT; y++) {
                for (int x = 0; x < Display.DISPLAY_WIDTH; x++) {
                    img.setRGB(x, y, resultRGB[y * Display.DISPLAY_WIDTH + x]);
                }
            }
            ImageIO.write(img, "png", file);
        }
    }
}
