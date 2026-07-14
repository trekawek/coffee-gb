package eu.rekawek.coffeegb.core.cpu;

import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InterruptManagerTest {

    @Test
    public void earlyMode2EdgeIsReadableBeforeCpuCanAcceptIt() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestInterruptBeforeCpuAcceptance(LCDC);

        assertTrue((interrupts.getByte(0xff0f) & (1 << LCDC.ordinal())) != 0);
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        var memento = interrupts.saveToMemento();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isInterruptRequested());

        interrupts.restoreFromMemento(memento);
        assertFalse(interrupts.isInterruptRequested());
    }

    @Test
    public void timerEdgeCanReachRunningCpuBeforeItWakesHalt() {
        InterruptManager interrupts = enabledInterrupt(Timer);

        interrupts.requestInterruptBeforeHaltWake(Timer);

        assertTrue(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        interrupts.releaseHaltWake(Timer);
        assertTrue(interrupts.isInterruptRequestedForHalt());
    }

    private static InterruptManager enabledInterrupt(InterruptManager.InterruptType type) {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        interrupts.setByte(0xffff, 1 << type.ordinal());
        return interrupts;
    }
}
