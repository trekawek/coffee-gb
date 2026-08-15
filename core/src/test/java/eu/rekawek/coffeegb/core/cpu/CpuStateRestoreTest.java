package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CpuStateRestoreTest {

    private static final int PROGRAM = 0xc000;

    @Test
    public void restoredCbPrefixFetchStillDecodesTheFollowingByte() {
        AddressSpace memory = new Ram(0x0000, 0x10000);
        memory.setByte(PROGRAM, 0xcb);
        memory.setByte(PROGRAM + 1, 0x11); // RL C

        Cpu source = cpu(memory);
        source.getRegisters().setPC(PROGRAM);
        source.getRegisters().setB(0x80);
        source.getRegisters().setC(0x80);
        source.getRegisters().getFlags().setC(false);

        tick(source, 4);
        assertEquals(Cpu.State.EXT_OPCODE, source.getState());
        assertEquals(PROGRAM + 1, source.getRegisters().getPC());

        var prefixFetched = source.captureState();
        Cpu restored = cpu(memory);
        restored.restoreState(prefixFetched);
        tick(restored, 4);

        assertEquals(Cpu.State.OPCODE, restored.getState());
        assertEquals(PROGRAM + 2, restored.getRegisters().getPC());
        assertEquals(0x80, restored.getRegisters().getB());
        assertEquals(0x00, restored.getRegisters().getC());
        assertTrue(restored.getRegisters().getFlags().isZ());
        assertTrue(restored.getRegisters().getFlags().isC());
    }

    private static Cpu cpu(AddressSpace memory) {
        return new Cpu(memory, new InterruptManager(false), null,
                new SpeedMode(false), new Display(false));
    }

    private static void tick(Cpu cpu, int count) {
        for (int i = 0; i < count; i++) {
            cpu.tick();
        }
    }
}
