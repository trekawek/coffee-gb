package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimerTest {

    @Test
    public void dmgHaltBugExposesTheFirstDividerCarryRippleForOneTick() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.setByte(0xff04, 0);
        tick(timer, 4);
        timer.onHaltBug();

        tick(timer, 252);

        assertEquals(0x55, timer.getByte(0xff04));
        timer.tick();
        assertEquals(0x01, timer.getByte(0xff04));
    }

    @Test
    public void ordinaryDividerCarrySettlesWithoutTheHaltBugRipple() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.setByte(0xff04, 0);

        tick(timer, 256);

        assertEquals(0x01, timer.getByte(0xff04));
    }

    @Test
    public void cgbDoesNotExposeTheDmgDividerRipple() {
        Timer timer = new Timer(new InterruptManager(true), new SpeedMode(true));
        timer.setByte(0xff04, 0);
        tick(timer, 4);
        timer.onHaltBug();

        tick(timer, 252);

        assertEquals(0x01, timer.getByte(0xff04));
    }

    @Test
    public void cgbTacMuxFallingEdgeIsAppliedAtTheWrite() {
        Timer timer = new Timer(new InterruptManager(true), new SpeedMode(true));
        timer.presetDiv(0x0007);
        timer.setByte(0xff07, 0x05);
        timer.tick();
        timer.setByte(0xff05, 0x20);

        timer.setByte(0xff07, 0x04);

        assertEquals(0x21, timer.getByte(0xff05));
    }

    @Test
    public void dmgTacMuxFallingEdgeSettlesOnTheFollowingClock() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.presetDiv(0x0007);
        timer.setByte(0xff07, 0x05);
        timer.tick();
        timer.setByte(0xff05, 0x20);

        timer.setByte(0xff07, 0x04);
        assertEquals(0x20, timer.getByte(0xff05));

        timer.tick();
        assertEquals(0x21, timer.getByte(0xff05));
    }

    @Test
    public void cgbSpeedSwitchAdvancesEnabledTimerMuxBeforeDivReset() {
        Timer timer = new Timer(new InterruptManager(true), new SpeedMode(true));
        timer.presetDiv(0x000c);
        timer.setByte(0xff05, 0x20);
        timer.setByte(0xff07, 0x05);

        timer.onSpeedSwitch();

        assertEquals(0x21, timer.getByte(0xff05));
    }

    @Test
    public void overflowReloadStartsThreeClocksAfterFallingEdge() {
        Timer timer = overflowingTimer();

        tick(timer, 2);
        assertEquals(0x00, timer.getByte(0xff05));
        timer.tick();

        assertEquals(0xf0, timer.getByte(0xff05));
    }

    @Test
    public void timaWriteBeforeReloadCancelsOverflow() {
        Timer timer = overflowingTimer();
        tick(timer, 2);

        timer.setByte(0xff05, 0x10);
        tick(timer, 8);

        assertEquals(0x10, timer.getByte(0xff05));
    }

    @Test
    public void timaWriteDuringReloadWindowIsIgnored() {
        Timer timer = overflowingTimer();
        tick(timer, 3);

        timer.setByte(0xff05, 0x10);
        tick(timer, 3);

        assertEquals(0xf0, timer.getByte(0xff05));
    }

    @Test
    public void tmaWriteDuringReloadWindowFeedsTima() {
        Timer timer = overflowingTimer();
        tick(timer, 3);

        timer.setByte(0xff06, 0x10);
        timer.tick();

        assertEquals(0x10, timer.getByte(0xff05));
    }

    @Test
    public void cgbInterruptAcknowledgeConsumesTimerIrqEightClocksAhead() {
        InterruptManager interruptManager = new InterruptManager(true);
        Timer timer = new Timer(interruptManager, new SpeedMode(true));
        timer.presetDiv(0x000b);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff07, 0x05);
        interruptManager.requestInterrupt(InterruptManager.InterruptType.Timer);
        interruptManager.clearInterrupt(InterruptManager.InterruptType.Timer);

        tick(timer, 8);

        assertFalse(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Timer));
    }

    @Test
    public void cgbInterruptAcknowledgeDoesNotConsumeTimerIrqNineClocksAhead() {
        InterruptManager interruptManager = new InterruptManager(true);
        Timer timer = new Timer(interruptManager, new SpeedMode(true));
        timer.presetDiv(0x000a);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff07, 0x05);
        interruptManager.requestInterrupt(InterruptManager.InterruptType.Timer);
        interruptManager.clearInterrupt(InterruptManager.InterruptType.Timer);

        tick(timer, 9);

        assertTrue(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Timer));
    }

    @Test
    public void mementoPreservesAcknowledgedFutureTimerIrq() {
        InterruptManager interruptManager = new InterruptManager(true);
        Timer timer = new Timer(interruptManager, new SpeedMode(true));
        timer.presetDiv(0x000b);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff07, 0x05);
        interruptManager.requestInterrupt(InterruptManager.InterruptType.Timer);
        interruptManager.clearInterrupt(InterruptManager.InterruptType.Timer);
        timer.tick();

        Timer restored = new Timer(interruptManager, new SpeedMode(true));
        restored.restoreFromMemento(timer.saveToMemento());
        tick(restored, 7);

        assertFalse(interruptManager.isInterruptFlagSet(InterruptManager.InterruptType.Timer));
    }

    private static Timer overflowingTimer() {
        Timer timer = new Timer(new InterruptManager(true), new SpeedMode(true));
        timer.presetDiv(0x000f);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff06, 0xf0);
        timer.setByte(0xff07, 0x05);
        timer.tick();
        assertEquals(0x00, timer.getByte(0xff05));
        return timer;
    }

    private static void tick(Timer timer, int ticks) {
        for (int i = 0; i < ticks; i++) {
            timer.tick();
        }
    }
}
