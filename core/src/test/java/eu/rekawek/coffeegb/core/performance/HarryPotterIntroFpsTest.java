package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final String JFR_PROPERTY = "harryPotterJfr";

    private static final long WARMUP_FRAMES = 1_200;

    private static final long MEASUREMENT_FRAMES = 600;

    @Test
    public void measuresIntroFps() throws Exception {
        File romFile = HarryPotterIntroHarness.requireRom();

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            AtomicLong publishedFrames = new AtomicLong();
            eventBus.register(e -> publishedFrames.incrementAndGet(),
                    eu.rekawek.coffeegb.core.gpu.Display.DmgFrameReadyEvent.class);
            eventBus.register(e -> publishedFrames.incrementAndGet(),
                    eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent.class);
            gameboy.requestFrameRenderSuppression(HarryPotterIntroHarness.forceFrameSkip());

            Window warmup = runFrames(gameboy, WARMUP_FRAMES, publishedFrames);
            Window measurement = runMeasurement(gameboy, publishedFrames);

            double emulatedFps = measurement.emulatedFrames * 1_000_000_000.0 / measurement.elapsedNanos;
            double renderedFps = measurement.publishedFrames * 1_000_000_000.0 / measurement.elapsedNanos;
            double ticksPerSecond = measurement.ticks * 1_000_000_000.0 / measurement.elapsedNanos;
            double nominalFps = Gameboy.TICKS_PER_SEC / (456.0 * 154.0);

            // Retain the historical line as the emulator/VBlank throughput figure.
            System.out.printf("Harry Potter intro FPS: %.3f%n", emulatedFps);
            System.out.printf("Emulated VBlank FPS: %.3f%n", emulatedFps);
            System.out.printf("Rendered/published FPS: %.3f%n", renderedFps);
            System.out.printf("Ticks/sec: %.0f%n", ticksPerSecond);
            System.out.printf("Frames: %d emulated, %d rendered/published in %.6f s%n",
                    measurement.emulatedFrames, measurement.publishedFrames,
                    measurement.elapsedNanos / 1_000_000_000.0);
            System.out.printf("Warm-up: %d emulated, %d rendered/published frames, %.0f ticks/sec%n",
                    warmup.emulatedFrames, warmup.publishedFrames,
                    warmup.ticks * 1_000_000_000.0 / warmup.elapsedNanos);
            System.out.printf("Nominal-frame headroom: %.1f%%%n", emulatedFps * 100.0 / nominalFps);
            if (HarryPotterIntroHarness.forceFrameSkip()) {
                System.out.println("Forced frame suppression: enabled");
            }
        }
    }

    private static Window runMeasurement(Gameboy gameboy, AtomicLong publishedFrames) throws Exception {
        String jfrPath = System.getProperty(JFR_PROPERTY, "");
        if (jfrPath.isBlank()) {
            return runFrames(gameboy, MEASUREMENT_FRAMES, publishedFrames);
        }

        Path output = Path.of(jfrPath).toAbsolutePath();
        Window measurement;
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            measurement = runFrames(gameboy, MEASUREMENT_FRAMES, publishedFrames);
            recording.stop();
            recording.dump(output);
        }
        System.out.println("JFR recording: " + output);
        return measurement;
    }

    private static Window runFrames(Gameboy gameboy, long targetFrames, AtomicLong publishedFrames) {
        long start = System.nanoTime();
        long publishedAtStart = publishedFrames.get();
        long emulatedFrames = 0;
        long ticks = 0;

        while (emulatedFrames < targetFrames) {
            if (gameboy.tick()) {
                emulatedFrames++;
            }
            ticks++;
        }

        return new Window(emulatedFrames, publishedFrames.get() - publishedAtStart, ticks,
                System.nanoTime() - start);
    }

    private record Window(long emulatedFrames, long publishedFrames, long ticks, long elapsedNanos) {
    }
}
