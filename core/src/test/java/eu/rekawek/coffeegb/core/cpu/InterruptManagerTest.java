package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        var memento = interrupts.captureState();

        assertTrue(interrupts.consumeLcdcInterruptFlagWriteClear());
        assertFalse(interrupts.consumeLcdcInterruptFlagWriteClear());

        interrupts.restoreState(memento);
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
    public void cpuFlagWriteReportsOnlyNewlyAssertedInterruptRequests() {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f, 0);
        RecordingDebugHooks hooks = new RecordingDebugHooks();
        interrupts.setDebugHooks(hooks);

        interrupts.setByte(0xff0f, 1 << Timer.ordinal());
        interrupts.setByteFromCpu(0xff0f,
                (1 << VBlank.ordinal())
                        | (1 << Timer.ordinal())
                        | (1 << InterruptManager.InterruptType.Serial.ordinal()));
        interrupts.setByteFromCpu(0xff0f,
                (1 << VBlank.ordinal())
                        | (1 << Timer.ordinal())
                        | (1 << InterruptManager.InterruptType.Serial.ordinal()));

        assertEquals(List.of(DebugInterruptType.VBLANK, DebugInterruptType.SERIAL),
                hooks.requestedInterrupts);
    }

    @Test
    public void cpuReadPreviewDoesNotSetOrEnableTheInterruptLatch() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.setCpuReadInterruptPreview(LCDC, true);
        var memento = interrupts.captureState();

        assertEquals(1 << LCDC.ordinal(), interrupts.getByte(0xff0f) & 0x1f);
        assertFalse(interrupts.isInterruptFlagSet(LCDC));
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        interrupts.clearCpuReadInterruptPreview();
        assertEquals(0, interrupts.getByte(0xff0f) & 0x1f);

        interrupts.restoreState(memento);
        assertEquals(1 << LCDC.ordinal(), interrupts.getByte(0xff0f) & 0x1f);
        assertFalse(interrupts.isInterruptFlagSet(LCDC));
    }

    @Test
    public void ppuPreviewBatchPreservesNonPpuPreviewBits() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.setCpuReadInterruptPreview(Timer, true);
        interrupts.setCpuReadPpuInterruptPreview(true, false);

        assertEquals((1 << LCDC.ordinal()) | (1 << Timer.ordinal()),
                interrupts.getByte(0xff0f) & 0x1f);

        interrupts.setCpuReadPpuInterruptPreview(false, true);

        assertEquals((1 << VBlank.ordinal()) | (1 << Timer.ordinal()),
                interrupts.getByte(0xff0f) & 0x1f);
    }

    @Test
    public void ppuTickSignalsAreConsumedTogether() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestInterrupt(VBlank);
        interrupts.clearInterrupt(VBlank);
        interrupts.requestInterrupt(LCDC);
        interrupts.clearInterrupt(LCDC);
        interrupts.setByteFromCpu(0xff0f, 0);

        int expected = InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_ACKNOWLEDGE
                | InterruptManager.PPU_TICK_SIGNAL_VBLANK_INTERRUPT_ACKNOWLEDGE
                | InterruptManager.PPU_TICK_SIGNAL_LCDC_INTERRUPT_FLAG_WRITE_CLEAR;
        assertEquals(expected, interrupts.consumePpuTickSignals());
        assertEquals(0, interrupts.consumePpuTickSignals());
    }

    @Test
    public void debugFlagViewsDoNotConsumeTransientIfReadMasks() {
        InterruptManager interrupts = new InterruptManager(false);
        interrupts.setByte(0xff0f,
                (1 << VBlank.ordinal()) | (1 << LCDC.ordinal()));
        interrupts.setByte(0xffff, 0x1f);
        interrupts.maskVBlankOnNextRead();
        interrupts.maskLcdcUntilNextPeripheralTick();
        interrupts.setCpuReadInterruptPreview(Timer, true);
        var stateBeforeDebugRead = interrupts.captureState();

        assertEquals(0x03, interrupts.getDebugInterruptFlags());
        assertEquals(0x1f, interrupts.getDebugInterruptEnableFlags());
        assertEquals(0x03, interrupts.getDebugPendingInterruptFlags());
        assertEquals(stateBeforeDebugRead, interrupts.captureState());

        assertEquals(1 << Timer.ordinal(), interrupts.getByte(0xff0f) & 0x1f);
        assertEquals(0x07, interrupts.getByte(0xff0f) & 0x1f);
    }

    @Test
    public void vblankAcknowledgeCaptureSurvivesMementoRestore() {
        InterruptManager interrupts = enabledInterrupt(VBlank);
        interrupts.requestInterrupt(VBlank);
        interrupts.clearInterrupt(VBlank);
        var memento = interrupts.captureState();

        assertTrue(interrupts.consumeVBlankInterruptAcknowledge());
        assertFalse(interrupts.consumeVBlankInterruptAcknowledge());

        interrupts.restoreState(memento);
        assertTrue(interrupts.consumeVBlankInterruptAcknowledge());
        assertFalse(interrupts.consumeVBlankInterruptAcknowledge());
    }

    @Test
    public void everyAcknowledgeCombinationAndConsumptionOrderSurvivesCaptureRestore() {
        for (int pending = 0; pending < 0x10; pending++) {
            for (int first = 0; first < 5; first++) {
                for (int second = 0; second < 5; second++) {
                    for (int third = 0; third < 5; third++) {
                        for (int fourth = 0; fourth < 5; fourth++) {
                            for (int fifth = 0; fifth < 5; fifth++) {
                                int[] order = {first, second, third, fourth, fifth};
                                if (Arrays.stream(order).distinct().count() != order.length) {
                                    continue;
                                }
                                assertEveryCapturePoint(pending, order);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void joypadClearDoesNotCreateAPeripheralAcknowledge() {
        InterruptManager interrupts = new InterruptManager(false);

        interrupts.clearInterrupt(InterruptManager.InterruptType.P10_13);

        assertEquals(0, consumeOutput(interrupts, 0));
        assertEquals(0, consumeOutput(interrupts, 1));
        assertEquals(0, consumeOutput(interrupts, 2));
        assertEquals(0, consumeOutput(interrupts, 3));
        assertEquals(0, consumeOutput(interrupts, 4));
    }

    @Test
    public void interruptStateAndImporterMementoKeepTheirReleasedRecordShapes() {
        List<String> expected = List.of(
                "ime:boolean", "interruptFlag:int", "interruptEnabled:int",
                "pendingEnableInterrupts:int", "haltBlockedInterrupts:int",
                "cpuBlockedInterrupts:int", "cpuPhasedPpuInterrupts:int",
                "cpuPhasedMode2Interrupts:int", "cpuFirstLineMode2Interrupts:int",
                "cpuInstructionBlockedInterrupts:int", "maskVBlankOnNextRead:boolean",
                "maskLcdcUntilNextPeripheralTick:boolean", "maskMode0LcdcReadTicks:int",
                "cpuReadInterruptPreview:int", "serialInterruptAcknowledge:boolean",
                "timerInterruptAcknowledge:boolean", "lcdcInterruptAcknowledge:boolean",
                "vBlankInterruptAcknowledge:boolean", "lcdcInterruptFlagWriteClear:boolean");
        InterruptManager interrupts = new InterruptManager(false);

        assertEquals(expected, recordShape(interrupts.captureState().getClass()));
        Class<?> importerMemento = Arrays.stream(InterruptManager.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("InterruptManagerMemento"))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, recordShape(importerMemento));
    }

    @Test
    public void earlyMode2EdgeIsReadableBeforeCpuCanAcceptIt() {
        InterruptManager interrupts = enabledInterrupt(LCDC);

        interrupts.requestInterruptBeforeCpuAcceptance(LCDC);

        assertTrue((interrupts.getByte(0xff0f) & (1 << LCDC.ordinal())) != 0);
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isInterruptRequestedForHalt());

        var memento = interrupts.captureState();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isInterruptRequested());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.restoreState(memento);
        assertFalse(interrupts.isInterruptRequested());
        interrupts.releaseCpuAcceptance(LCDC);
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void firstLcdLineMode2ClassificationSurvivesMementoRestore() {
        InterruptManager interrupts = enabledInterrupt(LCDC);
        interrupts.requestMode2InterruptBeforeCpuAcceptance(true);

        var memento = interrupts.captureState();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isPhasedMode2InterruptRequested());
        assertTrue(interrupts.isFirstLineMode2InterruptRequested());

        interrupts.restoreState(memento);
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

        var memento = interrupts.captureState();
        interrupts.releaseCpuAcceptance(LCDC);
        assertTrue(interrupts.isInterruptRequested());
        assertTrue(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.restoreState(memento);
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
        var memento = interrupts.captureState();

        assertEquals(0xe0, interrupts.getByte(0xff0f));
        assertTrue(interrupts.isInterruptFlagSet(LCDC));

        interrupts.restoreState(memento);
        interrupts.finishLcdcReadMaskWindow();
        assertEquals(0xe2, interrupts.getByte(0xff0f));
    }

    @Test
    public void mode0ReadMaskSurvivesItsCountdownAndMemento() {
        InterruptManager interrupts = new InterruptManager(true);
        interrupts.setByte(0xff0f, 1 << LCDC.ordinal());
        interrupts.maskMode0LcdcReadForTicks(3);
        var memento = interrupts.captureState();

        interrupts.finishLcdcReadMaskWindow();
        interrupts.finishLcdcReadMaskWindow();
        assertEquals(0xe0, interrupts.getByte(0xff0f));
        assertTrue(interrupts.isInterruptFlagSet(LCDC));

        interrupts.restoreState(memento);
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
        var memento = interrupts.captureState();

        assertTrue(interrupts.isInterruptFlagSet(LCDC));
        assertFalse(interrupts.isInterruptRequested());
        assertFalse(interrupts.isUnphasedPpuInterruptRequested());

        interrupts.onInstructionFinished();
        assertTrue(interrupts.isInterruptRequested());

        interrupts.restoreState(memento);
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

    private static void assertEveryCapturePoint(int pending, int[] order) {
        for (int capturePoint = 0; capturePoint <= order.length; capturePoint++) {
            InterruptManager interrupts = acknowledgements(pending);
            int remaining = pending;
            for (int i = 0; i < capturePoint; i++) {
                int operation = order[i];
                assertEquals(expectedOutput(remaining, operation),
                        consumeOutput(interrupts, operation));
                remaining = remainingAfter(remaining, operation);
            }

            InterruptManager restored = new InterruptManager(false);
            restored.restoreState(interrupts.captureState());
            for (int i = capturePoint; i < order.length; i++) {
                int operation = order[i];
                int expected = expectedOutput(remaining, operation);
                assertEquals(expected, consumeOutput(interrupts, operation));
                assertEquals(expected, consumeOutput(restored, operation));
                remaining = remainingAfter(remaining, operation);
            }
            assertEquals(0, remaining);
        }
    }

    private static InterruptManager acknowledgements(int pending) {
        InterruptManager interrupts = new InterruptManager(false);
        for (InterruptManager.InterruptType type : List.of(
                VBlank, LCDC, Timer, InterruptManager.InterruptType.Serial)) {
            if ((pending & (1 << type.ordinal())) != 0) {
                interrupts.clearInterrupt(type);
            }
        }
        return interrupts;
    }

    private static int expectedOutput(int pending, int operation) {
        if (operation < 4) {
            return (pending & (1 << operation)) != 0 ? 1 : 0;
        }
        int ppuAcknowledges = pending & 0x03;
        return ((ppuAcknowledges & (1 << LCDC.ordinal())) >> 1)
                | ((ppuAcknowledges & (1 << VBlank.ordinal())) << 1);
    }

    private static int remainingAfter(int pending, int operation) {
        return operation < 4 ? pending & ~(1 << operation) : pending & ~0x03;
    }

    private static int consumeOutput(InterruptManager interrupts, int operation) {
        return switch (operation) {
            case 0 -> interrupts.consumeVBlankInterruptAcknowledge() ? 1 : 0;
            case 1 -> interrupts.consumeLcdcInterruptAcknowledge() ? 1 : 0;
            case 2 -> interrupts.consumeTimerInterruptAcknowledge() ? 1 : 0;
            case 3 -> interrupts.consumeSerialInterruptAcknowledge() ? 1 : 0;
            case 4 -> interrupts.consumePpuTickSignals();
            default -> throw new IllegalArgumentException("Unknown operation " + operation);
        };
    }

    private static List<String> recordShape(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName())
                .toList();
    }

    private static final class RecordingDebugHooks implements DebugHooks {

        private final List<DebugInterruptType> requestedInterrupts = new ArrayList<>();

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onMemoryAccess(DebugMemoryAccess access, int address, int value) {
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
            requestedInterrupts.add(interrupt);
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }
}
