package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Captures the ROM display every five seconds of emulated time. */
public class HarryPotterIntroScreenshotTest {

    private static final int FRAMES_PER_SECOND = 60;
    private static final int SCREENSHOT_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_DURATION_SECONDS = 60;

    @Test
    public void capturesScreenshots() throws Exception {
        File romFile = HarryPotterIntroHarness.requireRom();
        File outputDir = new File(System.getProperty(
                "harryPotterScreenshotDir", "target/harry-potter-screenshots"));
        outputDir.mkdirs();
        int durationSeconds = Integer.getInteger(
                "harryPotterScreenshotDurationSeconds", DEFAULT_DURATION_SECONDS);

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            AtomicReference<int[]> lastFrame = new AtomicReference<>();
            eventBus.register(event -> {
                int[] rgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
                event.toRgb(rgb, false);
                lastFrame.set(rgb);
            }, Display.DmgFrameReadyEvent.class);
            eventBus.register(event -> {
                int[] rgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
                event.toRgb(rgb, false);
                lastFrame.set(rgb);
            }, Display.GbcFrameReadyEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            int totalFrames = durationSeconds * FRAMES_PER_SECOND;
            int intervalFrames = SCREENSHOT_INTERVAL_SECONDS * FRAMES_PER_SECOND;
            for (int frame = 0; frame <= totalFrames; frame++) {
                while (!gameboy.tick()) {
                    // Advance until one complete video frame has been produced.
                }
                if (frame % intervalFrames == 0 && lastFrame.get() != null) {
                    File output = new File(outputDir, String.format("frame-%04d-%02ds.png",
                            frame, frame / FRAMES_PER_SECOND));
                    BufferedImage image = new BufferedImage(
                            Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
                    image.setRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                            lastFrame.get(), 0, Display.DISPLAY_WIDTH);
                    ImageIO.write(image, "png", output);
                    System.out.println("Screenshot " + (frame / FRAMES_PER_SECOND) + "s: "
                            + output.getAbsolutePath());
                }
            }
        }
    }
}
