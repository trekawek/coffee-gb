package eu.rekawek.coffeegb.core.experimental.clock;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Executable reason a central IRQ strobe cannot yet live on the master-tick scheduler. */
public class InterruptAcknowledgeSchedulerFalsificationTest {

    @Test
    public void countingTheBoundaryMasterTickPreservesSerialButShortensTimer() {
        assertWindows(new Config("DMG", 1, 3), new Windows(2, 3));
        assertWindows(new Config("CGB normal", 1, 8), new Windows(7, 8));
        assertWindows(new Config("CGB double", 2, 8), new Windows(6, 8));
    }

    @Test
    public void deferringUntilTheNextMasterTickPreservesTimerButLengthensSerial() {
        assertWindows(new Config("DMG", 1, 3), new Windows(3, 4), true);
        assertWindows(new Config("CGB normal", 1, 8), new Windows(8, 9), true);
        assertWindows(new Config("CGB double", 2, 8), new Windows(8, 10), true);
    }

    private static void assertWindows(Config config, Windows expected) {
        assertWindows(config, expected, false);
    }

    private static void assertWindows(
            Config config, Windows expected, boolean deferBoundaryTick) {
        Windows actual = simulateCurrentMasterTickOrder(config, deferBoundaryTick);
        assertEquals(config.name, expected, actual);
        assertNotEquals(config.name + " cannot preserve both source windows",
                new Windows(config.acknowledgeClocks, config.acknowledgeClocks), actual);
    }

    /**
     * Current order is Timer -> CPU -> Serial. The CPU starts its schedule between the two
     * peripherals, while a central resolver can run only at a 4.19 MHz master-tick boundary.
     */
    private static Windows simulateCurrentMasterTickOrder(
            Config config, boolean deferBoundaryTick) {
        int remaining = config.acknowledgeClocks;
        int timerClocksInsideWindow = 0;
        int serialClocksInsideWindow = 0;
        boolean boundaryTick = true;

        while (remaining > 0) {
            // Timer's clocks on the boundary tick have already run before IRQ_PUSH_2.
            if (!boundaryTick) {
                timerClocksInsideWindow += config.cpuClocksPerMasterTick;
            }

            // Serial's clocks on that same tick run after IRQ_PUSH_2.
            serialClocksInsideWindow += config.cpuClocksPerMasterTick;

            if (!boundaryTick || !deferBoundaryTick) {
                remaining -= config.cpuClocksPerMasterTick;
            }
            boundaryTick = false;
        }
        return new Windows(timerClocksInsideWindow, serialClocksInsideWindow);
    }

    private record Config(String name, int cpuClocksPerMasterTick, int acknowledgeClocks) {
    }

    private record Windows(int timerClocks, int serialClocks) {
    }
}
