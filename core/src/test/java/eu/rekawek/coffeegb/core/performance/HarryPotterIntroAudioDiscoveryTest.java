package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Scans audio output from boot to locate intervals in which the ROM is producing
 * non-silent audio. This is intentionally a discovery test, not a performance
 * or quality assertion.
 */
public class HarryPotterIntroAudioDiscoveryTest {

    private static final int DEFAULT_SCAN_EVENTS = 3600;
    private static final Gameboy.BootstrapMode DEFAULT_BOOTSTRAP_MODE = Gameboy.BootstrapMode.NORMAL;
    private static final int NO_START_PRESS = -1;
    private static final int START_HOLD_FRAMES = 10;
    private static final double ACTIVE_RMS_THRESHOLD = 1.0;
    private static final int BUCKET_SIZE = 60;

    @Test
    public void scanAudioActivity() throws Exception {
        File rom = HarryPotterIntroHarness.requireRom();
        int scanEvents = Integer.getInteger("harryPotterAudioScanEvents", DEFAULT_SCAN_EVENTS);
        int pressStartAtEvent = Integer.getInteger("harryPotterAudioPressStartAtEvent", NO_START_PRESS);
        Gameboy.BootstrapMode bootstrapMode = Gameboy.BootstrapMode.valueOf(
                System.getProperty("harryPotterAudioBootstrapMode", DEFAULT_BOOTSTRAP_MODE.name()));

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(bootstrapMode)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            AudioScan scan = new AudioScan(scanEvents);
            eventBus.register(event -> scan.accept(event.buffer()), Sound.SoundSampleEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            if (pressStartAtEvent >= 0) {
                while (scan.eventCount() < pressStartAtEvent) {
                    gameboy.tick();
                }
                eventBus.post(new ButtonPressEvent(Button.START));
                for (int tick = 0; tick < Gameboy.TICKS_PER_FRAME * START_HOLD_FRAMES; tick++) {
                    gameboy.tick();
                }
                eventBus.post(new ButtonReleaseEvent(Button.START));
            }
            while (scan.eventCount() < scanEvents) {
                gameboy.tick();
            }
            scan.printReport();
            assertTrue(scan.eventCount() >= scanEvents);
        }
    }

    private static final class AudioScan {

        private final double[] rms;
        private int eventCount;

        private AudioScan(int eventCapacity) {
            if (eventCapacity < 1) {
                throw new IllegalArgumentException("eventCapacity must be positive");
            }
            this.rms = new double[eventCapacity];
        }

        private void accept(int[] buffer) {
            if (eventCount == rms.length) {
                return;
            }
            rms[eventCount++] = calculateRms(buffer);
        }

        private int eventCount() {
            return eventCount;
        }

        private void printReport() {
            List<Run> activeRuns = findActiveRuns();
            Run longestRun = activeRuns.stream()
                    .max((left, right) -> Integer.compare(left.length(), right.length()))
                    .orElse(null);

            System.out.println("Audio scan events: " + eventCount);
            System.out.println("Audio active threshold RMS: " + ACTIVE_RMS_THRESHOLD);
            System.out.println("Audio active events: " + countActiveEvents());
            if (longestRun == null) {
                System.out.println("Audio longest active run: none");
            } else {
                System.out.println("Audio longest active run: " + longestRun.start + "-" + longestRun.end
                        + " (" + longestRun.length() + " events)");
            }

            for (Run run : activeRuns) {
                System.out.println("AUDIO_ACTIVE_RUN start=" + run.start + " end=" + run.end
                        + " length=" + run.length() + " maxRms=" + format(run.maxRms));
            }

            for (int start = 0; start < eventCount; start += BUCKET_SIZE) {
                int end = Math.min(eventCount, start + BUCKET_SIZE);
                int active = 0;
                double sum = 0;
                double max = 0;
                for (int index = start; index < end; index++) {
                    double value = rms[index];
                    sum += value;
                    max = Math.max(max, value);
                    if (value > ACTIVE_RMS_THRESHOLD) {
                        active++;
                    }
                }
                System.out.println("AUDIO_BUCKET start=" + start + " end=" + (end - 1)
                        + " active=" + active + " avgRms=" + format(sum / (end - start))
                        + " maxRms=" + format(max));
            }
        }

        private List<Run> findActiveRuns() {
            List<Run> runs = new ArrayList<>();
            int start = -1;
            double maxRms = 0;
            for (int index = 0; index <= eventCount; index++) {
                boolean active = index < eventCount && rms[index] > ACTIVE_RMS_THRESHOLD;
                if (active && start < 0) {
                    start = index;
                    maxRms = rms[index];
                } else if (active) {
                    maxRms = Math.max(maxRms, rms[index]);
                } else if (start >= 0) {
                    runs.add(new Run(start, index - 1, maxRms));
                    start = -1;
                    maxRms = 0;
                }
            }
            return runs;
        }

        private int countActiveEvents() {
            int active = 0;
            for (int index = 0; index < eventCount; index++) {
                if (rms[index] > ACTIVE_RMS_THRESHOLD) {
                    active++;
                }
            }
            return active;
        }

        private static double calculateRms(int[] source) {
            double sumSquares = 0;
            for (int sample : source) {
                sumSquares += (double) sample * sample;
            }
            return Math.sqrt(sumSquares / source.length);
        }

        private static String format(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }
    }

    private record Run(int start, int end, double maxRms) {
        private int length() {
            return end - start + 1;
        }
    }
}
