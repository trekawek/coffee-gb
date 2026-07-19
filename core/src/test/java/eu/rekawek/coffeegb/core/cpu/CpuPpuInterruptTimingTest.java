package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Timer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

    private static class Harness {

        private final Ram memory = new Ram(0, 0x10000);

        private final InterruptManager interrupts;

        private final Cpu cpu;

        private Harness(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            cpu = new Cpu(memory, interrupts, null, new SpeedMode(gbc), new Display(gbc));
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

        private void tickMachineCycle() {
            for (int i = 0; i < 4; i++) {
                cpu.tick();
            }
        }
    }
}
