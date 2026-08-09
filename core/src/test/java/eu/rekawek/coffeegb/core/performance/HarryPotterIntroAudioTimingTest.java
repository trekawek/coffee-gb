package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

/**
 * Real-time audio cadence probe for the Harry Potter intro.
 *
 * <p>The emulator is paced at the same 60 Hz cadence as the controller while the synchronous
 * sound events are timestamped. Average FPS can remain healthy while occasional producer stalls
 * drain the frontend's audio queue, so this test reports event jitter and simulated queue debt.</p>
 */
public class HarryPotterIntroAudioTimingTest {

    private static final int WARMUP_FRAMES = 1_200;

    private static final int MEASUREMENT_FRAMES = 600;

    private static final long FRAME_NANOS = 1_000_000_000L / 60;

    private static final long SPIN_THRESHOLD_NANOS = 1_500_000L;

    private static final double MAX_AUDIO_HEADROOM_MS = 95.0;

    @Test
    public void measuresIntroAudioCadence() throws Exception {
        File romFile = HarryPotterIntroHarness.requireRom();

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        EventBusImpl rootBus = new EventBusImpl();
        try (rootBus; EventBus eventBus = rootBus.fork("main");
             Gameboy gameboy = configuration.build()) {
            AudioCadence capture = new AudioCadence(WARMUP_FRAMES, MEASUREMENT_FRAMES);
            eventBus.register(event -> capture.accept(), Sound.SoundSampleEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            paceFrames(gameboy, capture);
            capture.printReport();
        }
    }

    private static void paceFrames(Gameboy gameboy, AudioCadence capture) {
        long deadline = System.nanoTime();
        int frames = 0;
        while (frames < WARMUP_FRAMES + MEASUREMENT_FRAMES) {
            for (int tick = 0; tick < Gameboy.TICKS_PER_FRAME; tick++) {
                gameboy.tick();
            }
            frames++;
            deadline += FRAME_NANOS;
            waitUntil(deadline);
            if (capture.isComplete()) {
                return;
            }
        }
    }

    private static void waitUntil(long deadline) {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            if (remaining > SPIN_THRESHOLD_NANOS) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static final class AudioCadence {
        private final int warmupEvents;
        private final int measurementEvents;
        private final long[] intervalsNanos;

        private int receivedEvents;
        private int capturedEvents;
        private long lastEventNanos;
        private double queueMs = MAX_AUDIO_HEADROOM_MS;
        private double maximumQueueDebtMs;
        private int gapsOver25Ms;
        private int gapsOver50Ms;

        private AudioCadence(int warmupEvents, int measurementEvents) {
            this.warmupEvents = warmupEvents;
            this.measurementEvents = measurementEvents;
            this.intervalsNanos = new long[measurementEvents];
        }

        private void accept() {
            long now = System.nanoTime();
            int event = receivedEvents++;
            if (event < warmupEvents) {
                lastEventNanos = now;
                return;
            }
            if (capturedEvents >= measurementEvents) {
                return;
            }

            long interval = now - lastEventNanos;
            lastEventNanos = now;
            intervalsNanos[capturedEvents++] = interval;

            double intervalMs = interval / 1_000_000.0;
            if (intervalMs > 25.0) {
                gapsOver25Ms++;
            }
            if (intervalMs > 50.0) {
                gapsOver50Ms++;
            }
            queueMs = Math.min(MAX_AUDIO_HEADROOM_MS, queueMs - intervalMs);
            if (queueMs < 0) {
                maximumQueueDebtMs = Math.max(maximumQueueDebtMs, -queueMs);
                queueMs = 0;
            }
            queueMs += 1000.0 / 60.0;
        }

        private boolean isComplete() {
            return capturedEvents >= measurementEvents;
        }

        private void printReport() {
            if (receivedEvents < warmupEvents + measurementEvents) {
                throw new IllegalStateException("Audio events missing: received " + receivedEvents
                        + ", expected at least " + (warmupEvents + measurementEvents));
            }
            if (capturedEvents != measurementEvents) {
                throw new IllegalStateException("Audio timing events: " + capturedEvents
                        + ", expected " + measurementEvents);
            }

            long[] sorted = intervalsNanos.clone();
            Arrays.sort(sorted);
            System.out.printf("Timed audio events: %d%n", capturedEvents);
            System.out.printf("Audio event interval p50/p95/p99/max ms: %.3f / %.3f / %.3f / %.3f%n",
                    percentileMs(sorted, 0.50), percentileMs(sorted, 0.95), percentileMs(sorted, 0.99),
                    sorted[sorted.length - 1] / 1_000_000.0);
            System.out.printf("Audio gaps >25ms/>50ms: %d / %d%n", gapsOver25Ms, gapsOver50Ms);
            System.out.printf("Maximum simulated audio queue debt ms: %.3f%n", maximumQueueDebtMs);
        }

        private static double percentileMs(long[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1_000_000.0;
        }
    }
}
