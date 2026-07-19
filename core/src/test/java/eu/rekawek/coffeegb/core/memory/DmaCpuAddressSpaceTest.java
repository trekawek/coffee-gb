package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        Fixture fixture = new Fixture(true, 0x02);

        assertEquals(0x42, fixture.cpu.getByte(0x0100));
        assertEquals(0x22, fixture.cpu.getByte(0xc001));
        assertEquals(0x22, fixture.cpu.getByte(0xd001));
    }

    @Test
    public void cgbCartridgeDmaSourceA12SelectsCpuWramHalf() {
        Fixture lowHalf = new Fixture(true, 0x02);
        assertEquals(0x22, lowHalf.cpu.getByte(0xd001));
        lowHalf.cpu.setByte(0xd001, 0x66);
        assertEquals(0x66, lowHalf.memory.getByte(0xc001));

        Fixture highHalf = new Fixture(true, 0xb0);
        assertEquals(0x44, highHalf.cpu.getByte(0xc001));
        highHalf.cpu.setByte(0xc001, 0x77);
        assertEquals(0x77, highHalf.memory.getByte(0xd001));

        Fixture aliasedHighSource = new Fixture(true, 0xe0);
        assertEquals(0x22, aliasedHighSource.cpu.getByte(0xd001));
    }

    @Test
    public void cgbCanUseTheCartridgeBusDuringWramDma() {
        Fixture fixture = new Fixture(true, 0xc0);

        assertEquals(0x11, fixture.cpu.getByte(0x0100));
        assertEquals(0x42, fixture.cpu.getByte(0xc001));
    }

    @Test
    public void compatibilityModeReturnsFFForBlockedReads() {
        Fixture fixture = new Fixture(true, 0xc0, true);

        assertEquals(0xff, fixture.cpu.getByte(0xc001));
        assertEquals(0x11, fixture.cpu.getByte(0x0100));
    }

    @Test
    public void dmgInvalidHighSourceCopiesEchoRamButSynthesizesCpuBusValue() {
        Fixture fixture = new Fixture(false, 0xe0);
        fixture.memory.setByte(0xc000, 0x66);

        assertEquals(0x80, fixture.cpu.getByte(0xe000));
        assertEquals(0x80, fixture.cpu.getByte(0xfd00));

        fixture.tick(3); // clock 8 copies the first source byte
        assertEquals(0x66, fixture.oam.getByte(0xfe00));
    }

    @Test
    public void dmgVramDmaExposesPartiallyDecodedHighAddress() {
        Fixture fixture = new Fixture(false, 0x90);

        assertEquals(0x80, fixture.cpu.getByte(0xe000));
        assertEquals(0x9d, fixture.cpu.getByte(0xfd00));
    }

    @Test
    public void dmgVramSourceBusReleasesBeforeOam() {
        Fixture fixture = new Fixture(false, 0x90);
        fixture.memory.setByte(0xfd00, 0x77);

        fixture.tick(630); // 5 constructor clocks + 630 = clock 635
        assertEquals(0x9d, fixture.cpu.getByte(0xfd00));

        fixture.tick(1); // clock 636 releases the source bus
        assertEquals(0x77, fixture.cpu.getByte(0xfd00));
        assertTrue(fixture.dma.isOamBlocked());
    }

    @Test
    public void unblockedCpuWriteRetainsItsBusOrigin() {
        TestAddressSpace memory = new TestAddressSpace();
        Ram oam = new Ram(0xfe00, 0xa0);
        Dma dma = new Dma(memory, oam, new SpeedMode(false));
        DmaCpuAddressSpace cpu = new DmaCpuAddressSpace(memory, dma, false);

        cpu.setByte(0xff47, 0xe4);

        assertTrue(memory.cpuWrite);
        assertEquals(0xe4, memory.getByte(0xff47));
    }

    private static class Fixture {

        private final TestAddressSpace memory = new TestAddressSpace();

        private final DmaCpuAddressSpace cpu;

        private final Dma dma;

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private Fixture(boolean gbc, int sourceHigh) {
            this(gbc, sourceHigh, false);
        }

        private Fixture(boolean gbc, int sourceHigh, boolean blockedReadsReturnFF) {
            memory.setByte(0x0100, 0x11);
            memory.setByte(0x8000, 0x55);
            memory.setByte(0xc001, 0x22);
            memory.setByte(0xd001, 0x44);
            memory.setByte(0xff80, 0x33);
            memory.setByte(sourceHigh << 8, 0x42);

            dma = new Dma(memory, oam, new SpeedMode(gbc));
            cpu = new DmaCpuAddressSpace(memory, dma, gbc, blockedReadsReturnFF);
            dma.setByte(0xff46, sourceHigh);
            tick(5);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                dma.tick();
            }
        }
    }

    private static class TestAddressSpace implements AddressSpace {

        private final int[] data = new int[0x10000];

        private boolean cpuWrite;

        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            data[address] = value;
        }

        @Override
        public void setByteFromCpu(int address, int value) {
            cpuWrite = true;
            data[address] = value;
        }

        @Override
        public int getByte(int address) {
            return data[address];
        }
    }
}
