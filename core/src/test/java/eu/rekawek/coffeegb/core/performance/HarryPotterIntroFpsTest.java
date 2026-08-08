package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Unthrottled, wall-clock throughput probe for the Harry Potter intro after its company logos.
 *
 * <p>The 20-second warm-up both lets HotSpot optimize the active emulation paths and advances
 * the ROM beyond its company popups. The following 10-second window counts frames emitted by
 * {@link Gameboy#tick()}, which is the core's frame-boundary signal.</p>
 */
public class HarryPotterIntroFpsTest {

    private static final String ROM_PROPERTY = "harryPotterRom";

    private static final String DEFAULT_ROM = "/mnt/nas/emu/roms/gbc/H/"
            + "Harry Potter and the Sorcerer's Stone (USA, Europe) "
            + "(En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc";

    private static final long WARMUP_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static final long MEASUREMENT_NANOS = TimeUnit.SECONDS.toNanos(10);

    // Avoid a clock read on every master tick; even at the slowest expected result this keeps
    // the wall-clock endpoint within a fraction of a millisecond.
    private static final int CLOCK_CHECK_INTERVAL_TICKS = 1_024;

    @Test
    public void measuresIntroFps() throws Exception {
        File romFile = new File(System.getProperty(ROM_PROPERTY, DEFAULT_ROM));
        if (!romFile.isFile()) {
            throw new IllegalArgumentException("ROM not found: " + romFile);
        }

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            Window warmup = runFor(gameboy, WARMUP_NANOS);
            Window measurement = runFor(gameboy, MEASUREMENT_NANOS);

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

    private static Window runFor(Gameboy gameboy, long targetNanos) {
        long start = System.nanoTime();
        long deadline = start + targetNanos;
        long frames = 0;
        long ticks = 0;

        do {
            for (int i = 0; i < CLOCK_CHECK_INTERVAL_TICKS; i++) {
                if (gameboy.tick()) {
                    frames++;
                }
            }
            ticks += CLOCK_CHECK_INTERVAL_TICKS;
        } while (System.nanoTime() < deadline);

        return new Window(frames, ticks, System.nanoTime() - start);
    }

    private record Window(long frames, long ticks, long elapsedNanos) {
    }
}
