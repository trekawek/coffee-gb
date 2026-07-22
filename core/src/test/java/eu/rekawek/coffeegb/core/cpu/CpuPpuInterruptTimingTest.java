package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
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
    public void normalSpeedIfReadReportsOnlyTheLateSampledBusPhases() {
        Harness h = new Harness(true);
        h.memory.setByte(PROGRAM, 0xf2); // LDH A,(C)
        h.cpu.getRegisters().setC(0x0f);
        h.tickMachineCycle();

        assertNormalSpeedIfReadPhases(h);
    }

    @Test
    public void decodedIfReadAliasesReportTheSameLateSampledBusPhases() {
        Harness immediateHigh = new Harness(true);
        immediateHigh.memory.setByte(PROGRAM, 0xf0); // LDH A,(a8)
        immediateHigh.memory.setByte(PROGRAM + 1, 0x0f);
        immediateHigh.tickMachineCycle();
        immediateHigh.tickMachineCycle();
        assertNormalSpeedIfReadPhases(immediateHigh);

        Harness absolute = new Harness(true);
        absolute.memory.setByte(PROGRAM, 0xfa); // LD A,(a16)
        absolute.memory.setByte(PROGRAM + 1, 0x0f);
        absolute.memory.setByte(PROGRAM + 2, 0xff);
        absolute.tickMachineCycle();
        absolute.tickMachineCycle();
        absolute.tickMachineCycle();
        assertNormalSpeedIfReadPhases(absolute);

        Harness indirect = new Harness(true);
        indirect.memory.setByte(PROGRAM, 0x7e); // LD A,(HL)
        indirect.cpu.getRegisters().setHL(0xff0f);
        indirect.tickMachineCycle();
        assertNormalSpeedIfReadPhases(indirect);
    }

    private static void assertNormalSpeedIfReadPhases(Harness h) {
        assertEquals(Cpu.State.RUNNING, h.cpu.getState());
        assertEquals(0, h.cpu.getInterruptFlagReadMaskTicks(true));
        h.cpu.tick();
        assertEquals(2, h.cpu.getInterruptFlagReadMaskTicks(true));
        h.cpu.tick();
        assertEquals(1, h.cpu.getInterruptFlagReadMaskTicks(true));
        h.cpu.tick();
        assertEquals(0, h.cpu.getInterruptFlagReadMaskTicks(true));
    }

    @Test
    public void eiAndRetiOverlapClassifyTheMode0RequestAsPhased() {
        Harness ei = new Harness(true);
        ei.interrupts.enableInterrupts(true);
        ei.interrupts.onInstructionFinished();
        assertTrue(ei.cpu.isMode0InterruptDispatchPhased(true));
        ei.cpu.tick();
        assertFalse(ei.cpu.isMode0InterruptDispatchPhased(true));

        Harness reti = new Harness(true);
        reti.memory.setByte(PROGRAM, 0xd9);
        reti.tickMachineCycle();
        assertEquals(Cpu.State.RUNNING, reti.cpu.getState());
        assertTrue(reti.cpu.isMode0InterruptDispatchPhased(true));
        reti.cpu.tick();
        assertFalse(reti.cpu.isMode0InterruptDispatchPhased(true));
    }

    @Test
    public void prefetchedDiAndDisablingIeWriteWinMode0Acceptance() {
        Harness di = new Harness(true);
        di.memory.setByte(PROGRAM, 0xf3);
        di.cpu.tick();
        assertTrue(di.cpu.doesMode0InstructionWinInterruptAcceptance(true));

        Harness ieWrite = new Harness(true);
        ieWrite.memory.setByte(PROGRAM, 0xe0);
        ieWrite.memory.setByte(PROGRAM + 1, 0xff);
        ieWrite.cpu.getRegisters().setA(0);
        ieWrite.cpu.tick();
        assertTrue(ieWrite.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        ieWrite.cpu.getRegisters().setA(1 << LCDC.ordinal());
        assertFalse(ieWrite.cpu.doesMode0InstructionWinInterruptAcceptance(true));
    }

    @Test
    public void decodedIeWriteAliasesWinMode0AcceptanceWhenTheyDisableLcdc() {
        Harness highImmediate = new Harness(true);
        highImmediate.memory.setByte(PROGRAM, 0xe0); // LDH (a8),A
        highImmediate.memory.setByte(PROGRAM + 1, 0xff);
        assertDisablingIeWriteWins(highImmediate);

        Harness highC = new Harness(true);
        highC.memory.setByte(PROGRAM, 0xe2); // LDH (C),A
        highC.cpu.getRegisters().setC(0xff);
        assertDisablingIeWriteWins(highC);

        Harness absolute = new Harness(true);
        absolute.memory.setByte(PROGRAM, 0xea); // LD (a16),A
        absolute.memory.setByte(PROGRAM + 1, 0xff);
        absolute.memory.setByte(PROGRAM + 2, 0xff);
        assertDisablingIeWriteWins(absolute);

        Harness indirect = new Harness(true);
        indirect.memory.setByte(PROGRAM, 0x77); // LD (HL),A
        indirect.cpu.getRegisters().setHL(0xffff);
        assertDisablingIeWriteWins(indirect);
    }

    private static void assertDisablingIeWriteWins(Harness h) {
        h.cpu.getRegisters().setA(0);
        h.cpu.tick();
        assertTrue(h.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        h.cpu.getRegisters().setA(1 << LCDC.ordinal());
        assertFalse(h.cpu.doesMode0InstructionWinInterruptAcceptance(true));
    }

    @Test
    public void mode0LookaheadDoesNotReadFromSideEffectfulAddressSpaces() {
        Harness normalSpeed = new Harness(true);
        normalSpeed.memory.setByte(0xa000, 0xf3);
        normalSpeed.cpu.getRegisters().setPC(0xa000);
        normalSpeed.cpu.tick();

        assertFalse(normalSpeed.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        assertEquals(0, normalSpeed.unsafeInstructionReads);

        Harness doubleSpeed = new Harness(true);
        doubleSpeed.enableDoubleSpeed();
        doubleSpeed.memory.setByte(0xa000, 0xf2);
        doubleSpeed.cpu.getRegisters().setPC(0xa000);
        doubleSpeed.cpu.getRegisters().setC(0x0f);
        doubleSpeed.cpu.tick();

        assertEquals(0, doubleSpeed.cpu.getInterruptFlagReadMaskTicks(true));
        assertEquals(0, doubleSpeed.unsafeInstructionReads);

        Harness unsafeOperand = new Harness(true);
        unsafeOperand.memory.setByte(0x7fff, 0xe0);
        unsafeOperand.memory.setByte(0x8000, 0xff);
        unsafeOperand.cpu.getRegisters().setPC(0x7fff);
        unsafeOperand.cpu.getRegisters().setA(0);
        unsafeOperand.cpu.tick();

        assertFalse(unsafeOperand.cpu.doesMode0InstructionWinInterruptAcceptance(true));
        assertEquals(0, unsafeOperand.unsafeInstructionReads);
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
            assertFalse(h.cpu.isSynchronousHaltEntryStatPhase());
            assertTrue(h.cpu.isAsynchronousHaltEntryStatPhase());
            h.tickCpuTicks(2);
            assertEquals(7, h.cpu.getRegisters().getA());
            h.tickCpuTicks(4);
            assertEquals(6, h.cpu.getRegisters().getA());
            assertEquals(PROGRAM + 2, h.cpu.getRegisters().getPC());
        }
    }

    @Test
    public void haltEntryStatPhaseSurvivesMementoRestore() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.enterHalt();
        h.cpu.tick();
        h.interrupts.requestInterrupt(LCDC);
        h.cpu.onPeripheralsTicked();
        var asynchronousPhase = h.cpu.saveToMemento();

        h.memory.setByte(PROGRAM + 1, 0x76);
        h.interrupts.clearInterrupt(LCDC);
        h.tickCpuTicks(8);
        assertFalse(h.cpu.isAsynchronousHaltEntryStatPhase());

        h.cpu.restoreFromMemento(asynchronousPhase);
        assertFalse(h.cpu.isSynchronousHaltEntryStatPhase());
        assertTrue(h.cpu.isAsynchronousHaltEntryStatPhase());
    }

    @Test
    public void ordinaryHaltWakeStatPhaseSurvivesMementoRestore() {
        Harness h = new Harness(true);
        h.enable(LCDC);
        h.enterHalt();

        h.interrupts.requestInterrupt(LCDC);
        h.tickMachineCycle();
        assertTrue(h.cpu.isOrdinaryHaltWakeStatPhase());
        var ordinaryWakePhase = h.cpu.saveToMemento();

        h.memory.setByte(h.cpu.getRegisters().getPC(), 0x76);
        h.interrupts.clearInterrupt(LCDC);
        h.tickMachineCycle();
        assertEquals(Cpu.State.HALTED, h.cpu.getState());
        assertFalse(h.cpu.isOrdinaryHaltWakeStatPhase());

        h.cpu.restoreFromMemento(ordinaryWakePhase);
        assertTrue(h.cpu.isOrdinaryHaltWakeStatPhase());
    }

    @Test
    public void ordinaryHaltWakeTracksTheFirstIdleMachineCycle() {
        Harness firstSlot = new Harness(false);
        firstSlot.enable(LCDC);
        firstSlot.enterHalt();
        firstSlot.tickMachineCycle();
        firstSlot.interrupts.requestInterrupt(LCDC);
        firstSlot.tickMachineCycle();

        assertTrue(firstSlot.cpu.isOrdinaryHaltWakeStatPhase());
        assertTrue(firstSlot.cpu.isOneCycleOrdinaryHaltWakeStatPhase());
        var firstSlotWake = firstSlot.cpu.saveToMemento();
        firstSlot.memory.setByte(firstSlot.cpu.getRegisters().getPC(), 0x76);
        firstSlot.interrupts.clearInterrupt(LCDC);
        firstSlot.tickMachineCycle();
        assertFalse(firstSlot.cpu.isOneCycleOrdinaryHaltWakeStatPhase());
        firstSlot.cpu.restoreFromMemento(firstSlotWake);
        assertTrue(firstSlot.cpu.isOneCycleOrdinaryHaltWakeStatPhase());

        Harness laterSlot = new Harness(false);
        laterSlot.enable(LCDC);
        laterSlot.enterHalt();
        laterSlot.tickMachineCycle();
        laterSlot.tickMachineCycle();
        laterSlot.interrupts.requestInterrupt(LCDC);
        laterSlot.tickMachineCycle();

        assertTrue(laterSlot.cpu.isOrdinaryHaltWakeStatPhase());
        assertFalse(laterSlot.cpu.isOneCycleOrdinaryHaltWakeStatPhase());
    }

    @Test
    public void imeEnabledHaltBlockedInterruptInEntryWindowPushesTheHaltAddress() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.enable(LCDC);
            h.interrupts.enableInterrupts(false);
            h.enterHalt();

            h.cpu.tick();
            h.interrupts.requestInterruptBeforeHaltWake(LCDC);
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
    public void imeEnabledOrdinaryInterruptInEntryWindowPushesTheFollowingAddress() {
        for (boolean gbc : new boolean[] {false, true}) {
            Harness h = new Harness(gbc);
            h.enable(LCDC);
            h.interrupts.enableInterrupts(false);
            h.enterHalt();

            h.cpu.tick();
            h.interrupts.requestInterrupt(LCDC);
            h.cpu.onPeripheralsTicked();

            assertEquals(Cpu.State.HALTED, h.cpu.getState());
            h.advanceToIrqJump();
            assertEquals(0x01, h.memory.getByte(0xfffd));
            assertEquals(0x01, h.memory.getByte(0xfffc));
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
    public void mode3HdmaHaltWakeRetiresTheHeldOpcodeBeforeInterruptDispatch() {
        Harness h = new Harness(true, true);
        h.memory.setByte(PROGRAM + 1, 0xea); // LD (a16), A
        h.memory.setByte(PROGRAM + 2, 0x80);
        h.memory.setByte(PROGRAM + 3, 0x00);
        h.cpu.getRegisters().setA(0x42);
        h.enable(Timer);
        h.interrupts.enableInterrupts(false);
        h.advanceGpuTo(Mode.PixelTransfer);
        h.enterHalt();
        h.cpu.latchHdmaHaltOpcode(true);

        h.interrupts.requestInterrupt(Timer);
        h.tickMachineCycle();

        assertEquals(Cpu.State.OPERAND, h.cpu.getState());
        assertEquals(PROGRAM + 1, h.cpu.getRegisters().getPC());
        h.tickMachineCycle();
        h.tickMachineCycle();
        h.tickMachineCycle();
        assertEquals(0x42, h.memory.getByte(0x80ea));
        assertEquals(PROGRAM + 3, h.cpu.getRegisters().getPC());
        h.tickMachineCycle();
        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
    }

    @Test
    public void mode2HdmaHaltWakeLetsInterruptDispatchPreemptTheHeldOpcode() {
        Harness h = new Harness(true, true);
        h.memory.setByte(PROGRAM + 1, 0xea); // LD (a16), A
        h.memory.setByte(PROGRAM + 2, 0x80);
        h.memory.setByte(PROGRAM + 3, 0x00);
        h.cpu.getRegisters().setA(0x42);
        h.enable(Timer);
        h.interrupts.enableInterrupts(false);
        h.advanceGpuTo(Mode.OamSearch);
        h.enterHalt();
        h.cpu.latchHdmaHaltOpcode(true);

        h.interrupts.requestInterrupt(Timer);
        h.tickMachineCycle();

        assertEquals(Cpu.State.IRQ_WAIT_2, h.cpu.getState());
        assertEquals(0, h.memory.getByte(0x80ea));
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

        private final Gpu gpu;

        private final StatRegister stat;

        private final Cpu cpu;

        private int programReads;

        private int unsafeInstructionReads;

        private Harness(boolean gbc) {
            this(gbc, false);
        }

        private Harness(boolean gbc, boolean withGpu) {
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
                    if (address == 0x8000 || address == 0xa000) {
                        unsafeInstructionReads++;
                    }
                    return interrupts.accepts(address)
                            ? interrupts.getByte(address)
                            : memory.getByte(address);
                }
            };
            Display display = new Display(gbc);
            if (withGpu) {
                Ram oam = new Ram(0xfe00, 0xa0);
                Dma dma = new Dma(bus, oam, speedMode);
                stat = new StatRegister(interrupts);
                gpu = new Gpu(display, dma, oam, new VRamTransfer(NULL_EVENT_BUS),
                        stat, gbc, speedMode);
                stat.init(gpu);
            } else {
                stat = null;
                gpu = null;
            }
            cpu = new Cpu(bus, interrupts, gpu, speedMode, display);
            cpu.getRegisters().setPC(PROGRAM);
            cpu.getRegisters().setSP(0xfffe);
            memory.setByte(PROGRAM, 0x76);
            interrupts.setByte(0xff0f, 0);
        }

        private void advanceGpuTo(Mode mode) {
            gpu.setByte(0xff40, 0x91);
            for (int i = 0; gpu.getMode() != mode && i < 456; i++) {
                gpu.tick();
                stat.tick();
            }
            assertEquals(mode, gpu.getMode());
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
