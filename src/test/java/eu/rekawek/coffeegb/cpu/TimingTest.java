package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TimingTest {

    private static final int OFFSET = 0x100;

    private final Cpu cpu;

    private final AddressSpace memory;

    public TimingTest() {
        memory = new Ram(0x00, 0x10000);
        cpu = new Cpu(memory, new InterruptManager(false), null, Display.NULL_DISPLAY, new SpeedMode());
    }

    @Test
    public void testTiming() {
        assertTiming(16, 0xc9, 0, 0); // RET
        assertTiming(16, 0xd9, 0, 0); // RETI
        cpu.getRegisters().getFlags().setZ(false);
        assertTiming(20, 0xc0, 0, 0); // RET NZ
        cpu.getRegisters().getFlags().setZ(true);
        assertTiming(8, 0xc0, 0, 0); // RET NZ
        assertTiming(24, 0xcd, 0, 0); // CALL a16
        assertTiming(16, 0xc5); // PUSH BC
        assertTiming(12, 0xf1); // POP AF

        assertTiming(8, 0xd6, 00); // SUB A,d8

        cpu.getRegisters().getFlags().setC(true);
        assertTiming(8, 0x30, 00); // JR nc,r8

        cpu.getRegisters().getFlags().setC(false);
        assertTiming(12, 0x30, 00); // JR nc,r8

        cpu.getRegisters().getFlags().setC(true);
        assertTiming(12, 0xd2, 00); // JP nc,a16

        cpu.getRegisters().getFlags().setC(false);
        assertTiming(16, 0xd2, 00); // JP nc,a16

        assertTiming(16, 0xc3, 00, 00); // JP a16

        assertTiming(4, 0xaf); // XOR a
        assertTiming(12, 0xe0, 0x05); // LD (ff00+05),A
        assertTiming(12, 0xf0, 0x05); // LD A,(ff00+05)
        assertTiming(4, 0xb7); // OR

        assertTiming(4, 0x7b); // LDA A,E
        assertTiming(8, 0xd6, 0x00); // SUB A,d8
        assertTiming(8, 0xcb, 0x12); // RL D
        assertTiming(4, 0x87); // ADD A
        assertTiming(4, 0xf3); // DI
        assertTiming(8, 0x32); // LD (HL-),A
        assertTiming(12, 0x36); // LD (HL),d8
        assertTiming(16, 0xea, 0x00, 0x00); // LD (a16),A
        assertTiming(8, 0x09); // ADD HL,BC
        assertTiming(16, 0xc7); // RST 00H


        assertTiming(8, 0x3e, 0x51); // LDA A,51
        assertTiming(4, 0x1f); // RRA
        assertTiming(8, 0xce, 0x01); // ADC A,01
        assertTiming(4, 0x00); // NOP
    }

    private void assertTiming(int expectedTiming, int... opcodes) {
        for (int i = 0; i < opcodes.length; i++) {
            memory.setByte(OFFSET + i, opcodes[i]);
        }
        cpu.clearState();
        cpu.getRegisters().setPC(OFFSET);

        int ticks = 0;
        Opcode opcode = null;
        do {
            cpu.tick();
            if (opcode == null && cpu.getCurrentOpcode() != null) {
                opcode = cpu.getCurrentOpcode();
            }
            ticks++;
        } while (cpu.getState() != Cpu.State.OPCODE || ticks < 4);

        if (opcode == null) {
            assertEquals("Invalid timing value for " + hexArray(opcodes), expectedTiming, ticks);
        } else {
            assertEquals("Invalid timing value for [" + opcode.toString() + "]", expectedTiming, ticks);
        }
    }

    private static String hexArray(int[] data) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < data.length; i++) {
            b.append(String.format("%02x", data[i]));
            if (i < data.length - 1) {
                b.append(" ");
            }
        }
        b.append(']');
        return b.toString();
    }

}
