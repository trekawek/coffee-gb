package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
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
    public void lateHigherPriorityInterruptRedirectsTheFinalVectorCycle() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.memory.setByte(PROGRAM, 0x00);
            h.enable(LCDC, Timer);
            h.interrupts.enableInterrupts(false);
            h.interrupts.requestInterrupt(Timer);
            h.advanceToIrqJump();

            h.interrupts.requestInterrupt(LCDC);
            h.tickMachineCycle();

            assertEquals(LCDC.getHandler(), h.cpu.getRegisters().getPC());
            assertFalse(h.interrupts.isInterruptFlagSet(LCDC));
            assertTrue(h.interrupts.isInterruptFlagSet(Timer));
        }
    }

    @Test
    public void lateLowerPriorityInterruptCannotRedirectTheFinalVectorCycle() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.memory.setByte(PROGRAM, 0x00);
            h.enable(LCDC, Timer);
            h.interrupts.enableInterrupts(false);
            h.interrupts.requestInterrupt(LCDC);
            h.advanceToIrqJump();

            h.interrupts.requestInterrupt(Timer);
            h.tickMachineCycle();

            assertEquals(LCDC.getHandler(), h.cpu.getRegisters().getPC());
            assertFalse(h.interrupts.isInterruptFlagSet(LCDC));
            assertTrue(h.interrupts.isInterruptFlagSet(Timer));
        }
    }

    @Test
    public void interruptInHaltEntryWindowRephasesTheHaltBug() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.memory.setByte(PROGRAM + 1, 0x3d); // DEC A
            h.cpu.getRegisters().setA(8);
            h.enable(LCDC);
            h.enterHalt();
            var entryWindow = h.cpu.saveToMemento();
            h.tickMachineCycle();
            h.cpu.restoreFromMemento(entryWindow);

            h.cpu.tick();
            h.interrupts.requestInterrupt(LCDC);
            h.cpu.onPeripheralsTicked();

            assertEquals(Cpu.State.OPCODE, h.cpu.getState());
            h.tickCpuTicks(2);
            assertEquals(7, h.cpu.getRegisters().getA());
            h.tickCpuTicks(4);
            assertEquals(6, h.cpu.getRegisters().getA());
            assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
        }
    }

    @Test
    public void imeEnabledInterruptInHaltEntryWindowPushesTheHaltAddress() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.enable(LCDC);
            h.interrupts.enableInterrupts(false);
            h.enterHalt();

            h.cpu.tick();
            h.interrupts.requestInterrupt(LCDC);
            h.cpu.onPeripheralsTicked();

            assertEquals(Cpu.State.OPCODE, h.cpu.getState());
            assertEquals(PROGRAM, h.cpu.getRegisters().getPC());
            h.tickCpuTicks(2);
            assertEquals(Cpu.State.OPCODE, h.cpu.getState());
            h.cpu.tick();
            assertEquals(gbc ? Cpu.State.IRQ_PUSH_1 : Cpu.State.IRQ_WAIT_2,
                    h.cpu.getState());
            h.advanceToIrqJump();
            assertEquals(0x01, h.memory.getByte(0xfffd));
            assertEquals(0x00, h.memory.getByte(0xfffc));
        }
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
        assertTrue(h.cpu.isCpuRequestSlotInProgressForHdma());
        assertFalse(h.cpu.isInstructionRetiringForHdma());

        h.tickCpuTicks(2);
        assertEquals(Cpu.State.OPERAND, h.cpu.getState());
        assertTrue(h.cpu.isInstructionRetiringForHdma());

        Harness prefetched = new Harness(true);
        prefetched.memory.setByte(PROGRAM, 0x06);
        prefetched.cpu.prefetchOpcodeForHdma();
        assertEquals(Cpu.State.OPERAND, prefetched.cpu.getState());
        assertFalse(prefetched.cpu.isInstructionRetiringForHdma());
    }

    @Test
    public void hdmaArbitrationFetchIsTheOnlyOpcodeBusRead() {
        for (int opcode : new int[] {0x04, 0x76}) { // INC B; HALT
            Harness h = new Harness(true);
            h.memory.setByte(PROGRAM, opcode);
            h.tickCpuTicks(2);

            assertEquals(opcode != 0x76, h.cpu.claimCpuRequestSlotForHdma());
            assertEquals(Cpu.State.OPCODE, h.cpu.getState());
            assertEquals(PROGRAM, h.cpu.getRegisters().getPC());
            assertEquals(opcode, h.cpu.getBusValueForHdma());
            h.cpu.prefetchOpcodeForHdma();

            assertEquals(1, h.programReads);
        }
    }

    @Test
    public void hdmaArbitrationOpcodeLatchSurvivesMementoRestore() {
        Harness h = new Harness(true);
        h.memory.setByte(PROGRAM, 0xcb);
        h.memory.setByte(PROGRAM + 1, 0x00); // RLC B
        h.cpu.getRegisters().setB(0x80);
        h.tickCpuTicks(2);

        assertTrue(h.cpu.claimCpuRequestSlotForHdma());
        var latched = h.cpu.saveToMemento();
        h.cpu.tick();
        h.cpu.restoreFromMemento(latched);

        assertEquals(Cpu.State.OPCODE, h.cpu.getState());
        assertEquals(PROGRAM, h.cpu.getRegisters().getPC());
        assertEquals(0xcb, h.cpu.getBusValueForHdma());
        assertEquals(1, h.programReads);

        h.tickCpuTicks(2);
        assertEquals(Cpu.State.EXT_OPCODE, h.cpu.getState());
        assertEquals(PROGRAM + 1, h.cpu.getRegisters().getPC());
        assertEquals(1, h.programReads);
        h.tickMachineCycle();

        assertEquals(1, h.cpu.getRegisters().getB());
        assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
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

        private int programReads;

        private Harness(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            speedMode = new SpeedMode(gbc);
            AddressSpace bus = new AddressSpace() {
                @Override
                public boolean accepts(int address) {
                    return true;
                }

                @Override
                public void setByte(int address, int value) {
                    if (interrupts.accepts(address)) {
                        interrupts.setByte(address, value);
                    } else {
                        memory.setByte(address, value);
                    }
                }

                @Override
                public int getByte(int address) {
                    if (address == PROGRAM) {
                        programReads++;
                    }
                    return interrupts.accepts(address)
                            ? interrupts.getByte(address)
                            : memory.getByte(address);
                }
            };
            cpu = new Cpu(bus, interrupts, null, speedMode, new Display(gbc));
            cpu.getRegisters().setPC(PROGRAM);
            cpu.getRegisters().setSP(0xfffe);
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

        private void advanceToIrqJump() {
            for (int i = 0; i < 6 && cpu.getState() != Cpu.State.IRQ_JUMP; i++) {
                tickMachineCycle();
            }
            assertEquals(Cpu.State.IRQ_JUMP, cpu.getState());
        }

        private void tickCpuTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                cpu.tick();
            }
        }
    }
}
