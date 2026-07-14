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
import java.util.ArrayList;
import java.util.List;

/** Runs Daid's screenshot-only tests for their upstream half-second runtime. */
public class DaidTestRunner {

    private static final int[] DMG_COLORS = {0xffffff, 0xaaaaaa, 0x555555, 0x000000};

    private static final int PIXELS = Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT;

    private static final int TICKS_PER_TEST = Gameboy.TICKS_PER_SEC / 2;

    private static final int MAX_LUMINANCE_DELTA = 50;

    private final Gameboy gameboy;

    private final List<int[]> expectedImages = new ArrayList<>();

    private final int[] frame = new int[PIXELS];

    private int frames;

    public DaidTestRunner(File romFile, GameboyType gameboyType, List<File> expectedFiles) throws IOException {
        gameboy = new GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setGameboyType(gameboyType)
                .setSupportBatterySave(false)
                .build();

        EventBus eventBus = new EventBusImpl();
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class);

        for (File expectedFile : expectedFiles) {
            expectedImages.add(readImage(expectedFile));
        }
        if (expectedImages.isEmpty()) {
            throw new IllegalArgumentException("At least one expected image is required");
        }
    }

    private void onDmgFrame(Display.DmgFrameReadyEvent event) {
        event.toRgb(frame, DMG_COLORS);
        onFrameReady();
    }

    private void onGbcFrame(Display.GbcFrameReadyEvent event) {
        event.toRgb(frame);
        onFrameReady();
    }

    private void onFrameReady() {
        frames++;
    }

    public TestResult runTest() {
        for (int tick = 0; tick < TICKS_PER_TEST; tick++) {
            gameboy.tick();
        }
        if (frames == 0) {
            throw new IllegalStateException("The emulator did not produce a frame");
        }

        TestResult best = null;
        for (int expectedIndex = 0; expectedIndex < expectedImages.size(); expectedIndex++) {
            TestResult candidate = compare(frame, expectedImages.get(expectedIndex), expectedIndex);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static TestResult compare(int[] actual, int[] expected, int expectedIndex) {
        int violatingPixels = 0;
        int maxDelta = 0;
        long totalDelta = 0;
        for (int i = 0; i < PIXELS; i++) {
            int delta = Math.abs(luminance(actual[i]) - luminance(expected[i]));
            if (delta > MAX_LUMINANCE_DELTA) {
                violatingPixels++;
            }
            maxDelta = Math.max(maxDelta, delta);
            totalDelta += delta;
        }
        return new TestResult(actual.clone(), expectedIndex, violatingPixels, maxDelta, totalDelta);
    }

    private static int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (299 * red + 587 * green + 114 * blue + 500) / 1000;
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
        image.getRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, rgb, 0, Display.DISPLAY_WIDTH);
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] &= 0xffffff;
        }
        return rgb;
    }

    public static class TestResult {

        private final int[] actual;

        private final int expectedIndex;

        private final int violatingPixels;

        private final int maxDelta;

        private final long totalDelta;

        private TestResult(int[] actual, int expectedIndex, int violatingPixels, int maxDelta, long totalDelta) {
            this.actual = actual;
            this.expectedIndex = expectedIndex;
            this.violatingPixels = violatingPixels;
            this.maxDelta = maxDelta;
            this.totalDelta = totalDelta;
        }

        private boolean isBetterThan(TestResult other) {
            if (violatingPixels != other.violatingPixels) {
                return violatingPixels < other.violatingPixels;
            }
            if (maxDelta != other.maxDelta) {
                return maxDelta < other.maxDelta;
            }
            return totalDelta < other.totalDelta;
        }

        public int getExpectedIndex() {
            return expectedIndex;
        }

        public int getViolatingPixels() {
            return violatingPixels;
        }

        public int getMaxDelta() {
            return maxDelta;
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
