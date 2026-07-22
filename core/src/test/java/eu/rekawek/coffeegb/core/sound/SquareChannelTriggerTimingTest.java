package eu.rekawek.coffeegb.core.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SquareChannelTriggerTimingTest {

    @Test
    public void channel1DivWriteDoesNotClockSquareTimer() {
        assertDivWriteTiming(SoundMode1::new, 0xff10);
    }

    @Test
    public void channel2DivWriteDoesNotClockSquareTimer() {
        assertDivWriteTiming(SoundMode2::new, 0xff15);
    }

    @Test
    public void sweepUpdatedReloadDelaysAgeThreeRetrigger() {
        SoundMode1 mode = sweepUpdatedMode(true);
        assertLowBetweenTicks(mode, 1, 12);

        // The first pulse reload was on tick 11, so tick 12 leaves its reload
        // pipeline at age 3. Switch duty so the next reload is observable, then
        // retrigger while retaining the frequency written by sweep.
        mode.setByte(0xff11, 0xc0);
        mode.setByte(0xff14, 0x87);
        assertLowBetweenTicks(mode, 13, 80);
        assertEquals("tick 81", 8, mode.tick(false));
    }

    @Test
    public void sweepUpdateDoesNotDelayRetriggerAfterLaterReload() {
        SoundMode1 mode = sweepUpdatedMode(true);
        // The sweep update is consumed by the first reload on tick 11. The next
        // reload is tick 75, whose age-3 retrigger must use the ordinary delay.
        assertLowBetweenTicks(mode, 1, 76);
        mode.setByte(0xff11, 0xc0);
        mode.setByte(0xff14, 0x87);
        assertLowBetweenTicks(mode, 77, 142);
        assertEquals("tick 143", 8, mode.tick(false));
    }

    @Test
    public void dmgDoesNotUseCgbSweepReloadDelay() {
        SoundMode1 mode = sweepUpdatedMode(false);
        assertLowBetweenTicks(mode, 1, 12);
        mode.setByte(0xff11, 0xc0);
        mode.setByte(0xff14, 0x87);
        assertLowBetweenTicks(mode, 13, 78);
        assertEquals("tick 79", 8, mode.tick(false));
    }

    @Test
    public void ordinaryAgeThreeRetriggerKeepsStandardDelay() {
        SoundMode1 mode = new SoundMode1(new FrameSequencer(), true);
        mode.start();
        mode.setByte(0xff10, 0x00);
        mode.setByte(0xff11, 0x00);
        mode.setByte(0xff12, 0x80);
        mode.setByte(0xff13, 0xf0);
        mode.setByte(0xff14, 0x87);

        assertLowBetweenTicks(mode, 1, 72);
        mode.setByte(0xff11, 0xc0);
        mode.setByte(0xff14, 0x87);
        assertLowBetweenTicks(mode, 73, 138);
        assertEquals("tick 139", 8, mode.tick(false));
    }

    private static void assertDivWriteTiming(ModeFactory factory, int offset) {
        assertFirstHighTick(factory.create(new FrameSequencer(), true), offset, -1, 11);
        // Tick 1 is a 2 MHz square-timer phase; tick 2 is the intervening phase.
        // A DIV write on either one must leave the oscillator timing unchanged.
        assertFirstHighTick(factory.create(new FrameSequencer(), true), offset, 1, 11);
        assertFirstHighTick(factory.create(new FrameSequencer(), true), offset, 2, 11);

        // Once the timer reaches zero, a DIV write on the idle half-phase must not
        // clock it early, and one on the next 2 MHz phase must not suppress its edge.
        AbstractSoundMode resetOnIdlePhase = configuredMode(factory, offset);
        assertLowThroughTick(resetOnIdlePhase, 9);
        assertEquals(0, resetOnIdlePhase.tick(true));
        assertEquals(8, resetOnIdlePhase.tick(false));

        AbstractSoundMode resetOnClockPhase = configuredMode(factory, offset);
        assertLowThroughTick(resetOnClockPhase, 10);
        assertEquals(8, resetOnClockPhase.tick(true));
    }

    private static void assertFirstHighTick(AbstractSoundMode mode, int offset,
                                            int divResetTick, int firstHighTick) {
        configure(mode, offset);

        for (int tick = 1; tick < firstHighTick; tick++) {
            assertEquals("tick " + tick, 0, mode.tick(tick == divResetTick));
        }
        assertEquals(8, mode.tick(firstHighTick == divResetTick));
    }

    private static AbstractSoundMode configuredMode(ModeFactory factory, int offset) {
        AbstractSoundMode mode = factory.create(new FrameSequencer(), true);
        configure(mode, offset);
        return mode;
    }

    private static SoundMode1 sweepUpdatedMode(boolean gbc) {
        SoundMode1 mode = new SoundMode1(new FrameSequencer(), gbc);
        mode.start();
        mode.setByte(0xff10, 0x1f); // period 1, negate, shift 7
        mode.setByte(0xff11, 0x00); // 12.5% duty: positions 1 and 2 are low
        mode.setByte(0xff12, 0x80);
        mode.setByte(0xff13, 0xff);
        mode.setByte(0xff14, 0x87);
        mode.tickSweep(); // $7ff - ($7ff >> 7) = $7f0
        assertEquals(0xf0, mode.getByte(0xff13));
        return mode;
    }

    private static void configure(AbstractSoundMode mode, int offset) {
        mode.start();
        mode.setByte(offset + 1, 0xc0); // 75% duty: positions 1-6 are high
        mode.setByte(offset + 2, 0x80); // volume 8, DAC on
        mode.setByte(offset + 3, 0xff);
        mode.setByte(offset + 4, 0x87); // frequency 2047 and trigger
    }

    private static void assertLowThroughTick(AbstractSoundMode mode, int lastTick) {
        assertLowBetweenTicks(mode, 1, lastTick);
    }

    private static void assertLowBetweenTicks(AbstractSoundMode mode, int firstTick, int lastTick) {
        for (int tick = firstTick; tick <= lastTick; tick++) {
            assertEquals("tick " + tick, 0, mode.tick(false));
        }
    }

    @FunctionalInterface
    private interface ModeFactory {
        AbstractSoundMode create(FrameSequencer frameSequencer, boolean gbc);
    }
}
