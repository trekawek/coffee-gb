package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimerDoubleSpeedTest {

    @Test
    public void overflowPipelineAdvancesOnEveryDoubleSpeedCpuClock() {
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setByte(0xff4d, 0x01);
        assertTrue(speedMode.onStop());

        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xff0f, 0);
        Timer timer = new Timer(interrupts, speedMode);
        timer.setByte(0xff06, 0x42);
        timer.setByte(0xff07, 0x05);

        // Prime the timer's falling-edge detector with DIV bit 3 high. One
        // emulator tick advances two CPU clocks in CGB double-speed mode.
        timer.presetDiv(0x000d);
        timer.tick();
        timer.setByte(0xff05, 0xff);

        timer.tick();
        assertEquals(0x00, timer.getByte(0xff05));
        assertFalse(isTimerInterruptSet(interrupts));

        timer.tick();
        assertEquals(0x42, timer.getByte(0xff05));
        assertTrue(isTimerInterruptSet(interrupts));

        timer.tick();
        assertEquals(0x42, timer.getByte(0xff05));
    }

    private static boolean isTimerInterruptSet(InterruptManager interrupts) {
        return (interrupts.getByte(0xff0f)
                & (1 << InterruptManager.InterruptType.Timer.ordinal())) != 0;
    }
}
