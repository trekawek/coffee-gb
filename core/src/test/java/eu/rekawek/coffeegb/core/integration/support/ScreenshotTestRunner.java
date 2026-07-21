package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Runs a ROM for a bounded emulated runtime and compares every frame with a reference. */
public class ScreenshotTestRunner {

    private static final int[] DMG_COLORS = {0xffffff, 0xaaaaaa, 0x555555, 0x000000};

    private static final int PIXELS = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;

    private final Gameboy gameboy;

    private final long maxTicks;

    private final int[] expected;

    private TestResult currentResult;

    private int frames;

    public ScreenshotTestRunner(File romFile, File expectedFile, GameboyType gameboyType,
                                int runtimeMillis) throws IOException {
        this(romFile, expectedFile, gameboyType, runtimeMillis, false);
    }

    public ScreenshotTestRunner(File romFile, File expectedFile, GameboyType gameboyType,
                                int runtimeMillis, boolean cgb0Revision) throws IOException {
        expected = readImage(expectedFile);
        maxTicks = (long) Gameboy.TICKS_PER_SEC * runtimeMillis / 1000;

        gameboy = new GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setGameboyType(gameboyType)
                .setCgb0Revision(cgb0Revision)
                .setSupportBatterySave(false)
                .build();

        EventBus eventBus = new EventBusImpl();
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class);
    }

    private void onDmgFrame(Display.DmgFrameReadyEvent event) {
        int[] frame = new int[PIXELS];
        event.toRgb(frame, DMG_COLORS);
        capture(frame);
    }

    private void onGbcFrame(Display.GbcFrameReadyEvent event) {
        int[] frame = new int[PIXELS];
        int[] pixels = event.pixels();
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = expandFiveBits(color);
            int green = expandFiveBits(color >> 5);
            int blue = expandFiveBits(color >> 10);
            frame[i] = (red << 16) | (green << 8) | blue;
        }
        capture(frame);
    }

    private static int expandFiveBits(int value) {
        value &= 0x1f;
        return (value << 3) | (value >> 2);
    }

    private void capture(int[] frame) {
        frames++;
        currentResult = compare(frame, expected);
    }

    public TestResult runTest() {
        for (long ticks = 0; ticks < maxTicks; ticks++) {
            gameboy.tick();
            if (currentResult != null && currentResult.getMismatchedPixels() == 0) {
                return currentResult;
            }
        }
        if (frames == 0) {
            throw new IllegalStateException("The emulator did not produce a frame");
        }
        return currentResult;
    }

    private static TestResult compare(int[] actual, int[] expected) {
        int mismatchedPixels = 0;
        int maxChannelDelta = 0;
        for (int i = 0; i < PIXELS; i++) {
            int actualColor = actual[i];
            int expectedColor = expected[i];
            if (actualColor != expectedColor) {
                mismatchedPixels++;
            }
            for (int shift = 0; shift <= 16; shift += 8) {
                int delta = Math.abs(((actualColor >> shift) & 0xff)
                        - ((expectedColor >> shift) & 0xff));
                maxChannelDelta = Math.max(maxChannelDelta, delta);
            }
        }
        return new TestResult(actual.clone(), mismatchedPixels, maxChannelDelta);
    }

    private static int[] readImage(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Unsupported expected image: " + file);
        }
        if (image.getWidth() != Display.DISPLAY_WIDTH || image.getHeight() != Display.DISPLAY_HEIGHT) {
            throw new IOException("Expected a 160x144 image: " + file);
        }
        int[] rgb = new int[PIXELS];
        image.getRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                rgb, 0, Display.DISPLAY_WIDTH);
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] &= 0xffffff;
        }
        return rgb;
    }

    public static class TestResult {

        private final int[] actual;

        private final int mismatchedPixels;

        private final int maxChannelDelta;

        private TestResult(int[] actual, int mismatchedPixels, int maxChannelDelta) {
            this.actual = actual;
            this.mismatchedPixels = mismatchedPixels;
            this.maxChannelDelta = maxChannelDelta;
        }

        public TestResult compareAgainst(File expectedFile) throws IOException {
            return compare(actual, readImage(expectedFile));
        }

        public int getMismatchedPixels() {
            return mismatchedPixels;
        }

        public int getMaxChannelDelta() {
            return maxChannelDelta;
        }

        public void writeResultToFile(File file) throws IOException {
            BufferedImage image = new BufferedImage(
                    Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                    actual, 0, Display.DISPLAY_WIDTH);
            ImageIO.write(image, "png", file);
        }
    }
}
