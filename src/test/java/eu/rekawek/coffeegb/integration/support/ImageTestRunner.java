package eu.rekawek.coffeegb.integration.support;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.GameboyType;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.events.EventBusImpl;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.serial.SerialEndpoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static eu.rekawek.coffeegb.Gameboy.BootstrapMode.SKIP;
import static eu.rekawek.coffeegb.integration.support.RomTestUtils.isByteSequenceAtPc;

public class ImageTestRunner {

    private static final int[] COLORS = new int[]{0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000};

    private static final int MAX_TICKS = 2_000_000;

    private final Gameboy gb;

    private final File imageFile;

    private final int[] resultRGB = new int[Display.DISPLAY_HEIGHT * Display.DISPLAY_WIDTH];

    public ImageTestRunner(File romFile, GameboyType gameboyType) throws IOException {
        EventBus eventBus = new EventBusImpl();
        gb = new GameboyConfiguration(romFile).setBootstrapMode(SKIP).setGameboyType(gameboyType).setSupportBatterySave(false).build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        imageFile = new File(romFile.getParentFile(), romFile.getName().replace(".gb", ".png"));
        eventBus.register(this::onDmgFrame, Display.DmgFrameReadyEvent.class);
        eventBus.register(this::onGbcFrame, Display.GbcFrameReadyEvent.class);
    }

    private void onGbcFrame(Display.GbcFrameReadyEvent gbcFrameReadyEvent) {
        gbcFrameReadyEvent.toRgb(resultRGB);
    }

    private void onDmgFrame(Display.DmgFrameReadyEvent dmgFrameReady) {
        dmgFrameReady.toRgb(resultRGB, COLORS);
    }

    public TestResult runTest() throws Exception {
        int i = 0;
        while (!isByteSequenceAtPc(gb, 0x40)) {
            gb.tick();
            if (i++ > MAX_TICKS) {
                throw new Exception("The test is not finished after " + i + " ticks.");
            }
        }

        return new TestResult(resultRGB, getExpectedRGB());
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
