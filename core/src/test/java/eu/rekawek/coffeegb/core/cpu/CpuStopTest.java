package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CpuStopTest {

    private static final int PROGRAM = 0x100;

    @Test
    public void heldJoypadLineMakesStopFallThroughWithoutConsumingKey1() {
        Ram memory = stopProgram(0xce);
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setByte(0xff4d, 0x01);
        Cpu cpu = cpu(memory, speedMode);

        runUntilNotExecutingStop(cpu);

        assertEquals(Cpu.State.OPCODE, cpu.getState());
        assertEquals(PROGRAM + 2, cpu.getRegisters().getPC());
        assertEquals(1, speedMode.getSpeedMode());
        assertEquals(0x7f, speedMode.getByte(0xff4d));
    }

    @Test
    public void joypadLineWakesStoppedCpuEvenWithInterruptsDisabled() {
        Ram memory = stopProgram(0xcf);
        Cpu cpu = cpu(memory, new SpeedMode(false));

        runUntilNotExecutingStop(cpu);
        assertEquals(Cpu.State.STOPPED, cpu.getState());

        memory.setByte(0xff00, 0xce);
        tickMachineCycle(cpu);

        assertEquals(Cpu.State.OPCODE, cpu.getState());
        // The wake-up cycle immediately executes the following NOP.
        assertEquals(PROGRAM + 3, cpu.getRegisters().getPC());
    }

    private static Ram stopProgram(int joyp) {
        Ram memory = new Ram(0, 0x10000);
        memory.setByte(PROGRAM, 0x10);
        memory.setByte(PROGRAM + 1, 0x00);
        memory.setByte(0xff00, joyp);
        return memory;
    }

    private static Cpu cpu(AddressSpace memory, SpeedMode speedMode) {
        Cpu cpu = new Cpu(memory, new InterruptManager(false), null, speedMode, new Display(false));
        cpu.getRegisters().setPC(PROGRAM);
        return cpu;
    }

    private static void runUntilNotExecutingStop(Cpu cpu) {
        for (int i = 0; i < 20 && cpu.getState() != Cpu.State.STOPPED
                && (cpu.getState() != Cpu.State.OPCODE || cpu.getRegisters().getPC() == PROGRAM); i++) {
            cpu.tick();
        }
    }

    private static void tickMachineCycle(Cpu cpu) {
        for (int i = 0; i < 4; i++) {
            cpu.tick();
        }
    }
}
