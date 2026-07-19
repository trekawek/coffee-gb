package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

    private static void tick(Timer timer, int ticks) {
        for (int i = 0; i < ticks; i++) {
            timer.tick();
        }
    }
}
