package eu.rekawek.coffeegb.core.rtc;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RealTimeTimeSourceTest {

    private RealTimeClock rtc;

    private VirtualTimeSource wallClock;

    @Before
    public void setup() {
        wallClock = new VirtualTimeSource();
        rtc = new RealTimeClock(wallClock);
    }

    @Test
    public void activeClockUsesGameBoyTicksRatherThanHostTime() {
        wallClock.forward(10, TimeUnit.SECONDS);
        assertEquals(0, rtc.getSeconds());

        tick(Gameboy.TICKS_PER_SEC - 1L);
        assertEquals(0, rtc.getSeconds());
        rtc.tick();
        assertEquals(1, rtc.getSeconds());
    }

    @Test
    public void latchFreezesReadsWithoutStoppingTheClock() {
        rtc.setDayCounter(2);
        rtc.setHours(12);
        rtc.setMinutes(8);
        rtc.setSeconds(5);

        rtc.latch();
        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(2, 12, 8, 5);
        rtc.unlatch();

        assertClockEquals(2, 12, 8, 6);
    }

    @Test
    public void counterOverflowIsSticky() {
        rtc.setDayCounter(511);
        rtc.setHours(23);
        rtc.setMinutes(59);
        rtc.setSeconds(59);

        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(0, 0, 0, 0);
        assertTrue(rtc.isCounterOverflow());

        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(0, 0, 0, 1);
        assertTrue(rtc.isCounterOverflow());

        rtc.clearCounterOverflow();
        assertFalse(rtc.isCounterOverflow());
    }

    @Test
    public void writesAreMaskedAndAllowedWhileRunning() {
        rtc.setSeconds(0xff);
        rtc.setMinutes(0xff);
        rtc.setHours(0xff);
        rtc.setDayCounter(0x3ff);
        rtc.setCounterOverflow(true);

        assertClockEquals(511, 31, 63, 63);
        assertTrue(rtc.isCounterOverflow());
    }

    @Test
    public void secondsWriteResetsOnlyTheSubSecondCounter() {
        long partialSecond = Gameboy.TICKS_PER_SEC * 3L / 5;
        tick(partialSecond);
        rtc.setMinutes(12);
        tick(Gameboy.TICKS_PER_SEC - partialSecond - 1);
        assertClockEquals(0, 0, 12, 0);
        rtc.tick();
        assertClockEquals(0, 0, 12, 1);

        tick(partialSecond);
        rtc.setSeconds(20);
        tick(Gameboy.TICKS_PER_SEC - 1L);
        assertClockEquals(0, 0, 12, 20);
        rtc.tick();
        assertClockEquals(0, 0, 12, 21);
    }

    @Test
    public void haltPreservesTheSubSecondCounter() {
        long partialSecond = Gameboy.TICKS_PER_SEC * 3L / 5;
        tick(partialSecond);
        rtc.setHalt(true);
        tick(2L * Gameboy.TICKS_PER_SEC);
        rtc.setHalt(false);

        tick(Gameboy.TICKS_PER_SEC - partialSecond - 1);
        assertEquals(0, rtc.getSeconds());
        rtc.tick();
        assertEquals(1, rtc.getSeconds());
    }

    @Test
    public void explicitEmulatorPauseUsesWallTimeAndPreservesTheSubSecondPhase() {
        tick(Gameboy.TICKS_PER_SEC / 2L);
        rtc.setEmulationPaused(true);
        tick(Gameboy.TICKS_PER_SEC); // defensive: paused emulation must not double-count
        wallClock.forward(1500, TimeUnit.MILLISECONDS);
        rtc.setEmulationPaused(false);

        assertEquals(2, rtc.getSeconds());
        tick(Gameboy.TICKS_PER_SEC - 1L);
        assertEquals(2, rtc.getSeconds());
        rtc.tick();
        assertEquals(3, rtc.getSeconds());
    }

    @Test
    public void serializationWhilePausedDoesNotDiscardOrDoubleCountThePause() {
        rtc.setSeconds(5);
        rtc.setEmulationPaused(true);
        wallClock.forward(7, TimeUnit.SECONDS);

        long[] batteryClock = rtc.serialize();
        assertEquals(12, batteryClock[0]);

        RealTimeClock reloaded = new RealTimeClock(wallClock);
        reloaded.deserialize(batteryClock);
        assertEquals(12, reloaded.getSeconds());
    }

    @Test
    public void hardwareHaltStillStopsTheClockDuringAnEmulatorPause() {
        rtc.setSeconds(5);
        rtc.setHalt(true);
        rtc.setEmulationPaused(true);
        wallClock.forward(7, TimeUnit.SECONDS);
        rtc.setEmulationPaused(false);

        assertEquals(5, rtc.getSeconds());
    }

    @Test
    public void invalidRegisterValuesUseHardwareRolloverRules() {
        rtc.setMinutes(10);
        rtc.setSeconds(63);
        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(0, 0, 10, 0);

        rtc.setHours(5);
        rtc.setMinutes(63);
        rtc.setSeconds(59);
        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(0, 5, 0, 0);

        rtc.setHours(31);
        rtc.setMinutes(59);
        rtc.setSeconds(59);
        tick(Gameboy.TICKS_PER_SEC);
        assertClockEquals(0, 0, 0, 0);
    }

    @Test
    public void deserializeAppliesWallTimeElapsedWhileTheCartridgeWasOffline() {
        rtc.setHours(3);
        rtc.setMinutes(4);
        rtc.setSeconds(5);
        long[] batteryClock = rtc.serialize();

        wallClock.forward(7, TimeUnit.SECONDS);
        RealTimeClock reloaded = new RealTimeClock(wallClock);
        reloaded.deserialize(batteryClock);

        assertEquals(12, reloaded.getSeconds());
        assertEquals(4, reloaded.getMinutes());
        assertEquals(3, reloaded.getHours());
    }

    @Test
    public void haltedClockDoesNotAdvanceWhileTheCartridgeIsOffline() {
        rtc.setSeconds(5);
        rtc.setHalt(true);
        long[] batteryClock = rtc.serialize();

        wallClock.forward(7, TimeUnit.SECONDS);
        RealTimeClock reloaded = new RealTimeClock(wallClock);
        reloaded.deserialize(batteryClock);

        assertEquals(5, reloaded.getSeconds());
        assertTrue(reloaded.isHalt());
    }

    private void tick(long ticks) {
        while (ticks-- > 0) {
            rtc.tick();
        }
    }

    private void assertClockEquals(int days, int hours, int minutes, int seconds) {
        assertEquals("days", days, rtc.getDayCounter());
        assertEquals("hours", hours, rtc.getHours());
        assertEquals("minutes", minutes, rtc.getMinutes());
        assertEquals("seconds", seconds, rtc.getSeconds());
    }
}
