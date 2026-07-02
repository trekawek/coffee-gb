package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless screenshot runner. Usage:
 * ShotMain rom.gb outdir totalFrames shotFrame1,shotFrame2,... [frame:BUTTON,frame:BUTTON,...]
 * Buttons are pressed at the given frame and released 20 frames later.
 */
public class ShotMain {

    public static void main(String[] args) throws Exception {
        File romFile = new File(args[0]);
        File outDir = new File(args[1]);
        outDir.mkdirs();
        int totalFrames = Integer.parseInt(args[2]);
        java.util.Set<Integer> shotFrames = new java.util.TreeSet<>();
        for (String s : args[3].split(",")) {
            shotFrames.add(Integer.parseInt(s));
        }
        Map<Integer, Button> presses = new HashMap<>();
        Map<Integer, Button> releases = new HashMap<>();
        if (args.length > 4 && !args[4].isEmpty()) {
            for (String s : args[4].split(",")) {
                String[] parts = s.split(":");
                int frame = Integer.parseInt(parts[0]);
                Button b = Button.valueOf(parts[1].toUpperCase());
                presses.put(frame, b);
                releases.put(frame + 20, b);
            }
        }

        GameboyConfiguration config = new GameboyConfiguration(romFile).setSupportBatterySave(false);
        Gameboy gb = config.build();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicReference<int[]> lastFrame = new AtomicReference<>();
        eventBus.register(e -> {
            int[] px = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            e.toRgb(px, false);
            lastFrame.set(px);
        }, Display.DmgFrameReadyEvent.class);
        eventBus.register(e -> {
            int[] px = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            e.toRgb(px);
            lastFrame.set(px);
        }, Display.GbcFrameReadyEvent.class);
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

        String base = romFile.getName().replaceAll("[^A-Za-z0-9]+", "_");
        for (int frame = 0; frame <= totalFrames; frame++) {
            if (presses.containsKey(frame)) {
                eventBus.post(new ButtonPressEvent(presses.get(frame)));
            }
            if (releases.containsKey(frame)) {
                eventBus.post(new ButtonReleaseEvent(releases.get(frame)));
            }
            long ticks = 0;
            while (!gb.tick() && ticks++ < 200_000) {
                // run until next frame
            }
            if (shotFrames.contains(frame) && lastFrame.get() != null) {
                BufferedImage img = new BufferedImage(
                        Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                        lastFrame.get(), 0, Display.DISPLAY_WIDTH);
                File out = new File(outDir, base + "_f" + frame + ".png");
                ImageIO.write(img, "png", out);
                System.out.println("wrote " + out);
            }
        }
        System.exit(0);
    }
}
