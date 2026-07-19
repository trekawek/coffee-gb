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
        assertEquals(0x99, fixture.oam.getByte(0xfe00));
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
    public void cgbCartridgeDmaNormalizesEchoBeforeSelectingWramHalf() {
        Fixture fixture = new Fixture(true, 0x7f);

        fixture.cpu.setByte(0xefff, 0xaa);

        assertEquals(0xaa, fixture.memory.getByte(0xdfff));
        assertEquals(0x00, fixture.memory.getByte(0xffff));
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
    public void dmgHighSourceReleasesPartialDecodeForCopiedByteTail() {
        Fixture fixture = new Fixture(false, 0xe0);
        fixture.memory.setByte(0xc09d, 0x66);

        assertEquals(0x80, fixture.cpu.getByte(0xe000));
        assertEquals(0x80, fixture.cpu.getByte(0xfd00));
        assertEquals(0x42, fixture.oam.getByte(0xfe00));

        fixture.tick(628); // clock 636 releases the partial page decode
        assertEquals(0x66, fixture.cpu.getByte(0xe000));
        assertEquals(0x66, fixture.cpu.getByte(0xfd00));
    }

    @Test
    public void sourceBusConflictStartsWithTheFirstCopiedByte() {
        Fixture fixture = new Fixture(false, 0x12, false, 7);

        assertEquals(0x11, fixture.cpu.getByte(0x0100));
        fixture.tick(1);
        assertEquals(0x42, fixture.cpu.getByte(0x0100));
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

        fixture.tick(627); // 8 constructor clocks + 627 = clock 635
        assertEquals(0x9d, fixture.cpu.getByte(0xfd00));

        fixture.tick(1); // clock 636 releases the source bus
        assertEquals(0x77, fixture.cpu.getByte(0xfd00));
        assertTrue(fixture.dma.isOamBlocked());
    }

    @Test
    public void dmgWramWriteCollisionAndsTheCurrentOamByte() {
        Fixture fixture = new Fixture(false, 0xc0);

        fixture.cpu.setByte(0xc001, 0x0f);

        assertEquals(0x02, fixture.oam.getByte(0xfe00));
        assertEquals(0x22, fixture.memory.getByte(0xc001));
    }

    @Test
    public void cgbVramReadCollisionReturnsAndThenClearsTheCurrentOamByte() {
        Fixture fixture = new Fixture(true, 0x80);

        assertEquals(0x42, fixture.cpu.getByte(0x8000));
        assertEquals(0x00, fixture.oam.getByte(0xfe00));
    }

    @Test
    public void cgbVramWriteCollisionClearsTheCurrentOamByte() {
        Fixture fixture = new Fixture(true, 0x80);

        fixture.cpu.setByte(0x8000, 0x99);

        assertEquals(0x00, fixture.oam.getByte(0xfe00));
        assertEquals(0x42, fixture.memory.getByte(0x8000));
    }

    @Test
    public void cgbWramWriteCollisionIsDropped() {
        Fixture fixture = new Fixture(true, 0xc0);

        fixture.cpu.setByte(0xc001, 0x99);

        assertEquals(0x42, fixture.oam.getByte(0xfe00));
        assertEquals(0x22, fixture.memory.getByte(0xc001));
    }

    @Test
    public void unusableOamRangeIsDisconnectedDuringDma() {
        Fixture fixture = new Fixture(true, 0x02);
        fixture.memory.setByte(0xfeff, 0x34);

        fixture.cpu.setByte(0xfeff, 0xaa);

        assertEquals(0xff, fixture.cpu.getByte(0xfeff));
        assertEquals(0x34, fixture.memory.getByte(0xfeff));
    }

    @Test
    public void interruptStackWritesDriveTheFollowingDmaSlots() {
        Fixture fixture = new Fixture(false, 0x12);
        fixture.memory.setByte(0x129e, 0x76);
        fixture.memory.setByte(0x129f, 0x87);
        fixture.tick(628); // clock 636; byte 158 has not reached OAM yet

        fixture.dma.setCpuInterruptStackWrite(true);
        fixture.cpu.setByte(0x0001, 0xff);
        Dma restored = new Dma(fixture.memory, fixture.oam, new SpeedMode(false));
        restored.restoreFromMemento(fixture.dma.saveToMemento());
        DmaCpuAddressSpace restoredCpu =
                new DmaCpuAddressSpace(fixture.memory, restored, false);
        for (int i = 0; i < 4; i++) {
            restored.tick();
        }
        assertEquals(0xff, fixture.oam.getByte(0xfe9e));

        restored.setCpuInterruptStackWrite(true);
        restoredCpu.setByte(0x0000, 0x94);
        for (int i = 0; i < 4; i++) {
            restored.tick();
        }
        assertEquals(0x94, fixture.oam.getByte(0xfe9f));
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
            this(gbc, sourceHigh, blockedReadsReturnFF, 8);
        }

        private Fixture(boolean gbc, int sourceHigh, boolean blockedReadsReturnFF,
                        int initialTicks) {
            memory.setByte(0x0100, 0x11);
            memory.setByte(0x8000, 0x55);
            memory.setByte(0xc001, 0x22);
            memory.setByte(0xd001, 0x44);
            memory.setByte(0xff80, 0x33);
            memory.setByte(DmaAddressSpace.mapAddress(sourceHigh << 8, gbc), 0x42);

            dma = new Dma(memory, oam, new SpeedMode(gbc));
            cpu = new DmaCpuAddressSpace(memory, dma, gbc, blockedReadsReturnFF);
            dma.setByte(0xff46, sourceHigh);
            tick(initialTicks);
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
