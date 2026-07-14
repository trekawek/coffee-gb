package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.isByteSequenceAtPc;

/** Captures CGB-ACID2 at its software breakpoint and compares it with the reference image. */
public class CgbAcid2TestRunner {

    private static final int MAX_TICKS = 40_000_000;

    private final Gameboy gb;

    private final File referenceImage;

    private final int[] resultRgb = new int[Display.DISPLAY_HEIGHT * Display.DISPLAY_WIDTH];

    public CgbAcid2TestRunner(File romFile, File referenceImage) throws IOException {
        EventBus eventBus = new EventBusImpl();
        gb = new Gameboy.GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.CGB)
                .setSupportBatterySave(false)
                .build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        this.referenceImage = referenceImage;
        eventBus.register(this::onFrame, Display.GbcFrameReadyEvent.class);
    }

    public TestResult runTest() throws Exception {
        int ticks = 0;
        while (!isByteSequenceAtPc(gb, 0x40, 0x3e, 0x10)) { // ld b,b; ld a,$10
            if (++ticks > MAX_TICKS) {
                throw new Exception("CGB-ACID2 did not reach its screenshot breakpoint after " + ticks + " ticks");
            }
            gb.tick();
        }
        return new TestResult(resultRgb, readReferenceImage());
    }

    private void onFrame(Display.GbcFrameReadyEvent event) {
        int[] pixels = event.pixels();
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int red = expandFiveBits(color);
            int green = expandFiveBits(color >> 5);
            int blue = expandFiveBits(color >> 10);
            resultRgb[i] = (red << 16) | (green << 8) | blue;
        }
    }

    private static int expandFiveBits(int value) {
        value &= 0x1f;
        return (value << 3) | (value >> 2);
    }

    private int[] readReferenceImage() throws IOException {
        BufferedImage expectedImage = ImageIO.read(referenceImage);
        if (expectedImage.getWidth() != Display.DISPLAY_WIDTH
                || expectedImage.getHeight() != Display.DISPLAY_HEIGHT) {
            throw new IOException("Unexpected CGB-ACID2 reference image dimensions");
        }

        int[] expectedRgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        for (int y = 0; y < Display.DISPLAY_HEIGHT; y++) {
            for (int x = 0; x < Display.DISPLAY_WIDTH; x++) {
                expectedRgb[y * Display.DISPLAY_WIDTH + x] = expectedImage.getRGB(x, y) & 0xffffff;
            }
        }
        return expectedRgb;
    }

    public static class TestResult {

        private final int[] resultRgb;

        private final int[] expectedRgb;

        public TestResult(int[] resultRgb, int[] expectedRgb) {
            this.resultRgb = resultRgb;
            this.expectedRgb = expectedRgb;
        }

        public int[] getResultRgb() {
            return resultRgb;
        }

        public int[] getExpectedRgb() {
            return expectedRgb;
        }

        public void writeResultToFile(File file) throws IOException {
            BufferedImage image = new BufferedImage(
                    Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < Display.DISPLAY_HEIGHT; y++) {
                for (int x = 0; x < Display.DISPLAY_WIDTH; x++) {
                    image.setRGB(x, y, resultRgb[y * Display.DISPLAY_WIDTH + x]);
                }
            }
            ImageIO.write(image, "png", file);
        }
    }
}
