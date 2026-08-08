package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * Unthrottled throughput probe for the Harry Potter intro after its company logos.
 *
 * <p>The fixed-frame warm-up both lets HotSpot optimize the active emulation paths and advances
 * the ROM to the same emulated point for every run. The following fixed-frame window measures
 * the time required to emit a deterministic number of frames.</p>
 */
public class HarryPotterIntroFpsTest {

    private static final String ROM_PROPERTY = "harryPotterRom";

    private static final String JFR_PROPERTY = "harryPotterJfr";

    private static final String DEFAULT_ROM = "Z:\\emu\\roms\\gbc\\H\\"
            + "Harry Potter and the Sorcerer's Stone (USA, Europe) "
            + "(En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc";

    private static final long WARMUP_FRAMES = 1_200;

    private static final long MEASUREMENT_FRAMES = 600;

    @Test
    public void measuresIntroFps() throws Exception {
        File romFile = new File(System.getProperty(ROM_PROPERTY, DEFAULT_ROM));
        if (!romFile.isFile()) {
            throw new IllegalArgumentException("ROM not found: " + romFile);
        }

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            Window warmup = runFrames(gameboy, WARMUP_FRAMES);
            Window measurement = runMeasurement(gameboy);

            double fps = measurement.frames * 1_000_000_000.0 / measurement.elapsedNanos;
            double ticksPerSecond = measurement.ticks * 1_000_000_000.0 / measurement.elapsedNanos;
            double nominalFps = Gameboy.TICKS_PER_SEC / (456.0 * 154.0);

            System.out.printf("Harry Potter intro FPS: %.3f%n", fps);
            System.out.printf("Ticks/sec: %.0f%n", ticksPerSecond);
            System.out.printf("Frames: %d in %.6f s%n", measurement.frames,
                    measurement.elapsedNanos / 1_000_000_000.0);
            System.out.printf("Warm-up: %d frames, %.0f ticks/sec%n", warmup.frames,
                    warmup.ticks * 1_000_000_000.0 / warmup.elapsedNanos);
            System.out.printf("Nominal-frame headroom: %.1f%%%n", fps * 100.0 / nominalFps);
        }
    }

    private static Window runMeasurement(Gameboy gameboy) throws Exception {
        String jfrPath = System.getProperty(JFR_PROPERTY, "");
        if (jfrPath.isBlank()) {
            return runFrames(gameboy, MEASUREMENT_FRAMES);
        }

        Path output = Path.of(jfrPath).toAbsolutePath();
        Window measurement;
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            measurement = runFrames(gameboy, MEASUREMENT_FRAMES);
            recording.stop();
            recording.dump(output);
        }
        System.out.println("JFR recording: " + output);
        return measurement;
    }

    private static Window runFrames(Gameboy gameboy, long targetFrames) {
        long start = System.nanoTime();
        long frames = 0;
        long ticks = 0;

        while (frames < targetFrames) {
            if (gameboy.tick()) {
                frames++;
            }
            ticks++;
        }

        return new Window(frames, ticks, System.nanoTime() - start);
    }

    private record Window(long frames, long ticks, long elapsedNanos) {
    }
}
