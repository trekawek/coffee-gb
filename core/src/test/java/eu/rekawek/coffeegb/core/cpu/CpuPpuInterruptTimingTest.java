package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CpuPpuInterruptTimingTest {

    private static final int PROGRAM = 0x100;

    @Test
    public void runningCgbCpuAcceptsPpuRequestOneWaitCycleEarlier() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.interrupts.requestInterrupt(LCDC);

        h.tickMachineCycle();

        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());
        assertFalse(h.interrupts.isIme());
    }

    @Test
    public void cpuPhasedPpuRequestUsesNormalWaitCycles() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.interrupts.requestInterruptBeforeCpuAcceptance(LCDC);
        h.interrupts.releaseCpuAcceptance(LCDC);

        h.tickMachineCycle();

        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
    }

    @Test
    public void mode2EdgeOnTheEarlyNormalSpeedCpuPhaseSkipsOneWaitCycle() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.tickCpuTicks(1);
        h.interrupts.requestMode2InterruptBeforeCpuAcceptance(false);
        h.interrupts.releaseCpuAcceptance(LCDC);

        h.tickCpuTicks(3);

        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());
    }

    @Test
    public void firstLcdLineMode2EdgeKeepsItsNormalSpeedSubcycle() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.interrupts.requestMode2InterruptBeforeCpuAcceptance(true);
        h.interrupts.releaseCpuAcceptance(LCDC);

        h.tickMachineCycle();
        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());
        h.tickCpuTicks(4);
        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());
        h.tickCpuTicks(1);
        assertEquals(Cpu.State.IRQ_PUSH_2, h.cpu.getState());
    }

    @Test
    public void firstLcdLineMode2EdgeSkipsBothWaitsAtDoubleSpeed() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.enableDoubleSpeed();
        h.interrupts.requestMode2InterruptBeforeCpuAcceptance(true);
        h.interrupts.releaseCpuAcceptance(LCDC);

        h.tickCpuTicks(2);

        assertEquals(Cpu.State.IRQ_PUSH_2, h.cpu.getState());
    }

    @Test
    public void sampledMode2CpuPhaseSurvivesMementoRestore() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.tickCpuTicks(1);
        h.interrupts.requestMode2InterruptBeforeCpuAcceptance(false);
        h.interrupts.releaseCpuAcceptance(LCDC);
        h.tickCpuTicks(1);
        var cpuMemento = h.cpu.saveToMemento();
        var interruptMemento = h.interrupts.saveToMemento();

        h.tickCpuTicks(2);
        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());

        h.cpu.restoreFromMemento(cpuMemento);
        h.interrupts.restoreFromMemento(interruptMemento);
        h.tickCpuTicks(2);
        assertEquals(Cpu.State.IRQ_PUSH_1, h.cpu.getState());
    }

    @Test
    public void cpuPhasedPpuRequestCanRemainBlockedOnlyForHaltWake() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.interrupts.requestPhasedInterruptBeforeHaltWake(LCDC);

        h.tickMachineCycle();

        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
        assertFalse(h.interrupts.isIme());
    }

    @Test
    public void haltWakeBlockRetainsPhaseAcrossMementoRestore() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.enterHalt();

        h.interrupts.requestInterruptBeforeHaltWake(LCDC);
        h.tickMachineCycle();
        assertEquals(Cpu.State.HALTED, h.cpu.getState());

        var blockedCpu = h.cpu.saveToMemento();
        var blockedInterrupt = h.interrupts.saveToMemento();
        h.interrupts.releaseHaltWake(LCDC);
        h.tickMachineCycle();
        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());

        h.cpu.restoreFromMemento(blockedCpu);
        h.interrupts.restoreFromMemento(blockedInterrupt);
        h.tickMachineCycle();
        assertEquals(Cpu.State.HALTED, h.cpu.getState());
        h.interrupts.releaseHaltWake(LCDC);
        h.tickMachineCycle();
        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
    }

    @Test
    public void imeDisabledPpuWakeDoesNotDelayFollowingTimerInterrupt() {
        Harness h = new Harness(true);
        h.enable(LCDC, Timer);
        h.enterHalt();

        h.interrupts.requestInterrupt(LCDC);
        h.tickMachineCycle();
        assertEquals(Cpu.State.OPCODE, h.cpu.getState());

        h.interrupts.clearInterrupt(LCDC);
        h.interrupts.requestInterrupt(Timer);
        h.interrupts.enableInterrupts(false);
        h.tickMachineCycle();

        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
    }

    @Test
    public void hdmaDoesNotPrefetchPastAnEnabledPendingInterrupt() {
        Harness h = new Harness(true);
        h.memory.setByte(PROGRAM, 0x00);
        h.enable(LCDC);
        h.interrupts.enableInterrupts(false);
        h.interrupts.requestInterrupt(LCDC);

        h.cpu.prefetchOpcodeForHdma();

        assertEquals(PROGRAM, h.cpu.getRegisters().getPC());
        assertEquals(Cpu.State.OPCODE, h.cpu.getState());
    }

    @Test
    public void hdmaDistinguishesAnOpcodeCycleFromAFetchedInstruction() {
        Harness h = new Harness(true);
        h.memory.setByte(PROGRAM, 0x06);
        h.memory.setByte(PROGRAM + 1, 0x42);

        h.tickCpuTicks(2);
        assertTrue(h.cpu.hasInFlightInstructionForHdma());
        assertFalse(h.cpu.hasFetchedInstructionForHdma());

        h.tickCpuTicks(2);
        assertEquals(Cpu.State.OPERAND, h.cpu.getState());
        assertTrue(h.cpu.hasFetchedInstructionForHdma());

        Harness prefetched = new Harness(true);
        prefetched.memory.setByte(PROGRAM, 0x06);
        prefetched.cpu.prefetchOpcodeForHdma();
        assertEquals(Cpu.State.OPERAND, prefetched.cpu.getState());
        assertFalse(prefetched.cpu.hasFetchedInstructionForHdma());
    }

    @Test
    public void hdmaPrefetchReplaysTheHeldStopPaddingWithoutAdvancingPcAgain() {
        Harness h = new Harness(true);
        h.memory.setByte(PROGRAM, 0x10);
        h.memory.setByte(PROGRAM + 1, 0x3c);
        h.memory.setByte(PROGRAM + 2, 0x00);
        h.memory.setByte(0xff00, 0x0f);
        h.speedMode.setByte(0xff4d, 1);

        for (int i = 0; i < 16 && !h.cpu.isSpeedSwitching(); i++) {
            h.cpu.tick();
        }
        assertTrue(h.cpu.isSpeedSwitching());
        assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
        h.cpu.replaySpeedSwitchPaddingByte();

        for (int i = 0; i < 70_000 && h.cpu.isSpeedSwitching(); i++) {
            h.cpu.tick();
        }
        assertFalse(h.cpu.isSpeedSwitching());
        assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());

        h.cpu.prefetchOpcodeForHdma();
        assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
        h.tickMachineCycle();

        assertEquals(1, h.cpu.getRegisters().getA());
        assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
    }

    private static class Harness {

        private final Ram memory = new Ram(0, 0x10000);

        private final InterruptManager interrupts;

        private final SpeedMode speedMode;

        private final Cpu cpu;

        private Harness(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            speedMode = new SpeedMode(gbc);
            cpu = new Cpu(memory, interrupts, null, speedMode, new Display(gbc));
            cpu.getRegisters().setPC(PROGRAM);
            memory.setByte(PROGRAM, 0x76);
            interrupts.setByte(0xff0f, 0);
        }

        private void enable(InterruptManager.InterruptType... types) {
            int mask = 0;
            for (InterruptManager.InterruptType type : types) {
                mask |= 1 << type.ordinal();
            }
            interrupts.setByte(0xffff, mask);
        }

        private void enterHalt() {
            tickMachineCycle();
            assertEquals(Cpu.State.HALTED, cpu.getState());
        }

        private void enableDoubleSpeed() {
            speedMode.setByte(0xff4d, 1);
            speedMode.onStop();
        }

        private void tickMachineCycle() {
            tickCpuTicks(4 / speedMode.getSpeedMode());
        }

        private void tickCpuTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                cpu.tick();
            }
        }
    }
}
