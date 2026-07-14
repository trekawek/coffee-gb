package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DmaCpuAddressSpaceTest {

    @Test
    public void dmgSharesTheCartridgeAndWramBusButNotVram() {
        Fixture fixture = new Fixture(false, 0x12);

        assertEquals(0x42, fixture.cpu.getByte(0x0100));
        assertEquals(0x42, fixture.cpu.getByte(0xc001));
        assertEquals(0x55, fixture.cpu.getByte(0x8000));
        assertEquals(0x33, fixture.cpu.getByte(0xff80));

        fixture.cpu.setByte(0xc001, 0x99);
        fixture.cpu.setByte(0xff80, 0x99);
        assertEquals(0x22, fixture.memory.getByte(0xc001));
        assertEquals(0x99, fixture.memory.getByte(0xff80));
    }

    @Test
    public void cgbBlocksOnlyTheCartridgeBusDuringRomDma() {
        Fixture fixture = new Fixture(true, 0x12);

        assertEquals(0x42, fixture.cpu.getByte(0x0100));
        assertEquals(0x22, fixture.cpu.getByte(0xc001));
    }

    @Test
    public void cgbCanUseTheCartridgeBusDuringWramDma() {
        Fixture fixture = new Fixture(true, 0xc0);

        assertEquals(0x11, fixture.cpu.getByte(0x0100));
        assertEquals(0x42, fixture.cpu.getByte(0xc001));
    }

    private static class Fixture {

        private final TestAddressSpace memory = new TestAddressSpace();

        private final DmaCpuAddressSpace cpu;

        private Fixture(boolean gbc, int sourceHigh) {
            memory.setByte(0x0100, 0x11);
            memory.setByte(0x8000, 0x55);
            memory.setByte(0xc001, 0x22);
            memory.setByte(0xff80, 0x33);
            memory.setByte(sourceHigh << 8, 0x42);

            Dma dma = new Dma(memory, new Ram(0xfe00, 0xa0), new SpeedMode(gbc));
            cpu = new DmaCpuAddressSpace(memory, dma, gbc);
            dma.setByte(0xff46, sourceHigh);
            for (int i = 0; i < 5; i++) {
                dma.tick();
            }
        }
    }

    private static class TestAddressSpace implements AddressSpace {

        private final int[] data = new int[0x10000];

        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            data[address] = value;
        }

        @Override
        public int getByte(int address) {
            return data[address];
        }
    }
}
