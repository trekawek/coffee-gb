package eu.rekawek.coffeegb.core.cpu;

import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.VBlank;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InterruptManagerTest {

    @Test
    public void lcdcFlagWriteClearCaptureSurvivesMementoRestore() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.setByteFromCpu(0xff0f, 0);
        var memento = interrupts.saveToMemento();

        assertTrue(interrupts.consumeLcdcInterruptFlagWriteClear());
        assertFalse(interrupts.consumeLcdcInterruptFlagWriteClear());

        interrupts.restoreFromMemento(memento);
        assertTrue(interrupts.consumeLcdcInterruptFlagWriteClear());
        assertFalse(interrupts.consumeLcdcInterruptFlagWriteClear());
    }

    @Test
    public void nonCpuFlagWriteDoesNotCaptureLcdcClearCollision() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.setByte(0xff0f, 0);

        assertFalse(interrupts.consumeLcdcInterruptFlagWriteClear());
    }

    @Test
    public void cpuReadPreviewDoesNotSetOrEnableTheInterruptLatch() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.setCpuReadInterruptPreview(LCDC, true);
        var memento = interrupts.saveToMemento();

        assertEquals(1 << LCDC.ordinal(), interrupts.getByte(0xff0f) & 0x1f);
        assertFalse(interrupts.isInterruptFlagSet(LCDC));
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        interrupts.clearCpuReadInterruptPreview();
        assertEquals(0, interrupts.getByte(0xff0f) & 0x1f);

        interrupts.restoreFromMemento(memento);
        assertEquals(1 << LCDC.ordinal(), interrupts.getByte(0xff0f) & 0x1f);
        assertFalse(interrupts.isInterruptFlagSet(LCDC));
    }

    @Test
    public void vblankAcknowledgeCaptureSurvivesMementoRestore() {
        InterruptManager interrupts = enabledInterrupt(VBlank);
        interrupts.requestInterrupt(VBlank);
        interrupts.clearInterrupt(VBlank);
        var memento = interrupts.saveToMemento();

        assertTrue(interrupts.consumeVBlankInterruptAcknowledge());
        assertFalse(interrupts.consumeVBlankInterruptAcknowledge());

        interrupts.restoreFromMemento(memento);
        assertTrue(interrupts.consumeVBlankInterruptAcknowledge());
        assertFalse(interrupts.consumeVBlankInterruptAcknowledge());
    }

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
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.restoreFromMemento(memento);
        assertFalse(interrupts.isInterruptRequested());
        interrupts.releaseCpuAcceptance(LCDC);
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void firstLcdLineMode2ClassificationSurvivesMementoRestore() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestMode2InterruptBeforeCpuAcceptance(true);

        var memento = interrupts.saveToMemento();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isPhasedMode2InterruptRequested());
        assertTrue(interrupts.isFirstLineMode2InterruptRequested());

        interrupts.restoreFromMemento(memento);
        assertFalse(interrupts.isPhasedMode2InterruptRequested());
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isPhasedMode2InterruptRequested());
        assertTrue(interrupts.isFirstLineMode2InterruptRequested());
    }

    @Test
    public void directPpuEdgeCanRemainBlockedUntilCpuAcceptance() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestInterruptBeforeCpuAcceptanceUnphased(LCDC);

        assertTrue((interrupts.getByte(0xff0f) & (1 << LCDC.ordinal())) != 0);
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        var memento = interrupts.saveToMemento();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isInterruptRequested());
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.restoreFromMemento(memento);
        assertFalse(interrupts.isInterruptRequested());
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
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

    @Test
    public void ppuEdgeDelayedOnlyForHaltRemainsEarlyForRunningCpu() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestInterruptBeforeHaltWake(LCDC);

        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        interrupts.releaseHaltWake(LCDC);
        assertTrue(interrupts.isInterruptRequestedForHalt());
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void phasedPpuEdgeCanRemainBlockedOnlyForHaltWake() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestPhasedInterruptBeforeHaltWake(LCDC);

        assertTrue(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.releaseHaltWake(LCDC);
        assertTrue(interrupts.isInterruptRequestedForHalt());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void directPpuEdgeNeedsRunningCpuPhaseAdjustment() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestInterrupt(LCDC);

        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void repeatedPpuRequestDoesNotReclassifyAssertedIfLatch() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestInterruptBeforeCpuAcceptance(LCDC);

        interrupts.requestInterrupt(LCDC);

        assertTrue(interrupts.isInterruptRequested());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void repeatedMode2RequestDoesNotReclassifyAssertedIfLatch() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestInterrupt(LCDC);

        interrupts.requestMode2InterruptBeforeCpuAcceptance(true);

        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
        assertFalse(interrupts.isPhasedMode2InterruptRequested());
        assertFalse(interrupts.isFirstLineMode2InterruptRequested());
    }

    @Test
    public void blockedMode2RequestCanBeCancelledBeforeCpuAcceptance() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestMode2InterruptBeforeCpuAcceptance(false);

        interrupts.cancelMode2InterruptBeforeCpuAcceptance();

        assertFalse(interrupts.isInterruptFlagSet(LCDC));
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void mode2CancellationDoesNotClearAnUnphasedLcdcRequest() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestInterrupt(LCDC);
        interrupts.requestMode2InterruptBeforeCpuAcceptance(false);

        interrupts.cancelMode2InterruptBeforeCpuAcceptance();

        assertTrue(interrupts.isInterruptFlagSet(LCDC));
        assertTrue(interrupts.isInterruptRequested());
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void mode2CancellationDoesNotClearAnAcceptedRequest() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestMode2InterruptBeforeCpuAcceptance(false);
        interrupts.releaseCpuAcceptance(LCDC);

        interrupts.cancelMode2InterruptBeforeCpuAcceptance();

        assertTrue(interrupts.isInterruptFlagSet(LCDC));
        assertTrue(interrupts.isInterruptRequested());
        assertTrue(interrupts.isPhasedMode2InterruptRequested());
    }

    @Test
    public void retiringVBlankCanBeMaskedForOneIfReadWithoutClearingTheLatch() {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 1 << VBlank.ordinal());

        interrupts.maskVBlankOnNextRead();

        assertEquals(0xe0, interrupts.getByte(0xff0f));
        assertEquals(0xe1, interrupts.getByte(0xff0f));
        assertTrue(interrupts.isInterruptFlagSet(VBlank));
    }

    @Test
    public void lcdcBusMaskExpiresAtTheNextPeripheralTick() {
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xff0f, 1 << LCDC.ordinal());
        interrupts.maskLcdcUntilNextPeripheralTick();
        var memento = interrupts.saveToMemento();

        assertEquals(0xe0, interrupts.getByte(0xff0f));
        assertTrue(interrupts.isInterruptFlagSet(LCDC));

        interrupts.restoreFromMemento(memento);
        interrupts.finishLcdcReadMaskWindow();
        assertEquals(0xe2, interrupts.getByte(0xff0f));
    }

    @Test
    public void mode0ReadMaskSurvivesItsCountdownAndMemento() {
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xff0f, 1 << LCDC.ordinal());
        interrupts.maskMode0LcdcReadForTicks(3);
        var memento = interrupts.saveToMemento();

        interrupts.finishLcdcReadMaskWindow();
        interrupts.finishLcdcReadMaskWindow();
        assertEquals(0xe0, interrupts.getByte(0xff0f));
        assertTrue(interrupts.isInterruptFlagSet(LCDC));

        interrupts.restoreFromMemento(memento);
        interrupts.finishLcdcReadMaskWindow();
        interrupts.finishLcdcReadMaskWindow();
        interrupts.finishLcdcReadMaskWindow();
        assertEquals(0xe2, interrupts.getByte(0xff0f));
    }

    @Test
    public void phasedPpuRequestCanWaitForThePrefetchedInstruction() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.enableInterrupts(false);
        interrupts.requestPhasedInterruptAfterInstruction(LCDC);
        var memento = interrupts.saveToMemento();

        assertTrue(interrupts.isInterruptFlagSet(LCDC));
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.onInstructionFinished();
        assertTrue(interrupts.isInterruptRequested());

        interrupts.restoreFromMemento(memento);
        assertFalse(interrupts.isInterruptRequested());
        interrupts.onInstructionFinished();
        assertTrue(interrupts.isInterruptRequested());
    }

    @Test
    public void prefetchedInstructionCannotDelayAnOlderPpuRequest() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.enableInterrupts(false);
        interrupts.requestInterrupt(LCDC);

        interrupts.requestPhasedInterruptAfterInstruction(LCDC);

        assertTrue(interrupts.isInterruptRequested());
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void eiEnablesImeAfterTheFollowingInstruction() {
        InterruptManager interrupts = new InterruptManager(true);

        interrupts.enableInterrupts(true);
        interrupts.onInstructionFinished();
        assertFalse(interrupts.isIme());

        interrupts.onInstructionFinished();
        assertTrue(interrupts.isIme());
    }

    @Test
    public void diDisablesImeImmediatelyOnCgb() {
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.enableInterrupts(false);

        interrupts.disableInterrupts(true);

        assertFalse(interrupts.isIme());
    }

    @Test
    public void diCancelsAnEiThatHasNotYetTakenEffect() {
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.enableInterrupts(true);
        interrupts.onInstructionFinished();

        interrupts.disableInterrupts(true);
        interrupts.onInstructionFinished();

        assertFalse(interrupts.isIme());
    }

    private static InterruptManager enabledInterrupt(InterruptManager.InterruptType type) {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        interrupts.setByte(0xffff, 1 << type.ordinal());
        return interrupts;
    }
}
