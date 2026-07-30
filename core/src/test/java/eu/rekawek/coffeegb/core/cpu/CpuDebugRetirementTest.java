package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Mmu;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.VBlank;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CpuDebugRetirementTest {

    private static final int PROGRAM = 0xc000;

    @Test
    public void baseInstructionRetiresOnlyAtItsExactBoundary() {
        Fixture fixture = fixture(0x00);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 3);
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 1);
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
    }

    @Test
    public void extendedInstructionRetiresAfterItsSecondFetchCycle() {
        Fixture fixture = fixture(0xcb, 0x00);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 7);
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 1);
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
    }

    @Test
    public void haltAndStopEachRetireOnceWhenTheyEnterTheirIdleState() {
        Fixture halt = fixture(0x76, 0x00);
        halt.cpu.enableDebugRetirementTracking();
        tick(halt.cpu, 4);
        assertEquals(Cpu.State.HALTED, halt.cpu.getState());
        assertEquals(1, halt.cpu.getDebugRetirementSequence());
        tick(halt.cpu, 12);
        assertEquals(1, halt.cpu.getDebugRetirementSequence());

        Fixture stop = fixture(0x10, 0x00);
        stop.cpu.enableDebugRetirementTracking();
        tick(stop.cpu, 7);
        assertEquals(0, stop.cpu.getDebugRetirementSequence());
        tick(stop.cpu, 1);
        assertEquals(Cpu.State.STOPPED, stop.cpu.getState());
        assertEquals(1, stop.cpu.getDebugRetirementSequence());
    }

    @Test
    public void haltBugStillHasOneHaltRetirement() {
        Fixture fixture = fixture(0x76, 0x00);
        fixture.interrupts.setByte(0xffff, 1 << VBlank.ordinal());
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 4);

        assertEquals(Cpu.State.OPCODE, fixture.cpu.getState());
        assertTrue(fixture.cpu.isDebugHaltBugActive());
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
    }

    @Test
    public void eiHaltAndItsAcceptedInterruptHaveSeparateRetirements() {
        Fixture fixture = fixture(0xfb, 0x76, 0x00);
        fixture.cpu.getRegisters().setSP(0xd000);
        fixture.interrupts.setByte(0xffff, 1 << VBlank.ordinal());
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 4);
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 4);
        assertEquals(2, fixture.cpu.getDebugRetirementSequence());
        assertEquals(PROGRAM + 1, fixture.cpu.getRegisters().getPC());

        tick(fixture.cpu, 19);
        assertEquals(2, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 1);
        assertEquals(3, fixture.cpu.getDebugRetirementSequence());
        assertEquals(VBlank.getHandler(), fixture.cpu.getRegisters().getPC());
    }

    @Test
    public void illegalOpcodeRetiresIntoLockedStateAtFetchBoundary() {
        Fixture fixture = fixture(0xd3);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 3);
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 1);

        assertEquals(Cpu.State.LOCKED, fixture.cpu.getState());
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 8);
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
    }

    @Test
    public void interruptEntryRetiresOnlyAfterTheVectorCycle() {
        Fixture fixture = fixture(0x00);
        fixture.cpu.getRegisters().setSP(0xd000);
        fixture.interrupts.setByte(0xffff, 1 << VBlank.ordinal());
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.interrupts.enableInterrupts(false);
        fixture.cpu.enableDebugRetirementTracking();

        tick(fixture.cpu, 19);
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());
        tick(fixture.cpu, 1);

        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
        assertEquals(VBlank.getHandler(), fixture.cpu.getRegisters().getPC());
    }

    @Test
    public void disabledTrackerDoesNothingAndIsNotPartOfCpuState() {
        Fixture fixture = fixture(0x00, 0x00, 0x00);
        tick(fixture.cpu, 4);
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());

        fixture.cpu.enableDebugRetirementTracking();
        tick(fixture.cpu, 4);
        assertEquals(1, fixture.cpu.getDebugRetirementSequence());
        var machineState = fixture.cpu.captureState();
        tick(fixture.cpu, 4);
        assertEquals(2, fixture.cpu.getDebugRetirementSequence());

        fixture.cpu.restoreState(machineState);
        assertEquals(2, fixture.cpu.getDebugRetirementSequence());
        fixture.cpu.disableDebugRetirementTracking();
        assertEquals(0, fixture.cpu.getDebugRetirementSequence());
    }

    private static Fixture fixture(int... program) {
        InterruptManager interrupts = new InterruptManager(false);
        Mmu mmu = new Mmu(false);
        mmu.addAddressSpace(interrupts);
        mmu.indexSpaces();
        for (int i = 0; i < program.length; i++) {
            mmu.setByte(PROGRAM + i, program[i]);
        }
        Cpu cpu = new Cpu(mmu, interrupts, null, new SpeedMode(false), new Display(false));
        cpu.getRegisters().setPC(PROGRAM);
        return new Fixture(cpu, interrupts);
    }

    private static void tick(Cpu cpu, int count) {
        for (int i = 0; i < count; i++) {
            cpu.tick();
        }
    }

    private record Fixture(Cpu cpu, InterruptManager interrupts) {
    }
}
