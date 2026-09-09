package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.AddPatches;
import eu.rekawek.coffeegb.core.genie.GameGenieCheat;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc5;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc7;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PerformanceRomAccessTest {

    @Test
    public void plainMbc7LeaseSnapshotsBothWindowsAndIsReused() throws IOException {
        Mbc7 mapper = plainMbc7();

        PerformanceRomAccess lease = mapper.acquirePerformanceRomAccess();

        assertNotNull(lease);
        assertEquals(0x20, lease.readCpuByte(0x0000));
        assertEquals(0x40, lease.readCpuByte(0x3fff));
        assertEquals(0x21, lease.readCpuByte(0x4000));
        assertEquals(0x41, lease.readCpuByte(0x7fff));
        assertEquals(0x80, lease.readCpuByte(0x0100));
        assertEquals(0x81, lease.readCpuByte(0x4100));
        assertEquals(0x0100, lease.physicalOffset(0x0100));
        assertEquals(0x4100, lease.physicalOffset(0x4100));
        assertEquals(-1, lease.physicalOffset(0x8000));
        assertEquals(-1, lease.readCpuByte(-1));
        assertEquals(0xff, lease.readPhysicalByte(Integer.MAX_VALUE));

        mapper.setByte(0x2000, 0x03);
        assertEquals("borrowed mapping remains stable until reacquisition",
                0x81, lease.readCpuByte(0x4100));

        PerformanceRomAccess reacquired = mapper.acquirePerformanceRomAccess();
        assertSame("plain MBC7 acquisition must not allocate", lease, reacquired);
        assertEquals(0x83, reacquired.readCpuByte(0x4100));
        assertEquals(0xc100, reacquired.physicalOffset(0x4100));
    }

    @Test
    public void plainMbc7LeaseFailsClosedForDerivedMapperAndDebugHooks() throws IOException {
        Mbc7 derivedMapper = new Mbc7(new Rom(mbc7Rom()), Battery.NULL_BATTERY) {
        };
        assertNull(derivedMapper.acquirePerformanceRomAccess());

        Mbc7 debuggedMapper = plainMbc7();
        debuggedMapper.setDebugHooks(new TestDebugHooks());
        assertNull(debuggedMapper.acquirePerformanceRomAccess());
    }

    @Test
    public void mbc7LeaseUsesNinthBankBitModuloAndOpenBusForPartialFinalBank()
            throws IOException {
        byte[] rom = new byte[0x9000];
        rom[0x0000] = 0x50;
        rom[0x8000] = 0x52;
        rom[0x8fff] = 0x5f;
        rom[0x0147] = 0x22;
        rom[0x0148] = 0x00;
        Mbc7 mapper = new Mbc7(new Rom(rom), Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 0x02);
        PerformanceRomAccess partialBank = mapper.acquirePerformanceRomAccess();
        assertEquals(0x52, partialBank.readCpuByte(0x4000));
        assertEquals(0x5f, partialBank.readCpuByte(0x4fff));
        assertEquals(0xff, partialBank.readCpuByte(0x5000));
        assertEquals(0x9000, partialBank.physicalOffset(0x5000));

        mapper.setByte(0x3000, 0x01);
        PerformanceRomAccess ninthBitBank = mapper.acquirePerformanceRomAccess();
        assertSame(partialBank, ninthBitBank);
        assertEquals("0x102 wraps over the physical three-bank image to bank zero",
                0x50, ninthBitBank.readCpuByte(0x4000));
        assertEquals(0x0000, ninthBitBank.physicalOffset(0x4000));
    }

    @Test
    public void productionCartridgeExposesPlainMbc7Lease() throws IOException {
        Cartridge cartridge = new Cartridge(new Rom(mbc7Rom()), Battery.NULL_BATTERY);

        PerformanceRomAccess lease = cartridge.acquirePerformanceRomAccess();

        assertNotNull(lease);
        assertEquals(0x20, lease.readCpuByte(0x0000));
        assertEquals(0x21, lease.readCpuByte(0x4000));
    }

    @Test
    public void plainMbc5LeaseSnapshotsBothWindowsAndIsReused() throws IOException {
        Mbc5 mapper = plainMbc5();

        PerformanceRomAccess lease = mapper.acquirePerformanceRomAccess();

        assertNotNull(lease);
        assertEquals(0x20, lease.readCpuByte(0x0000));
        assertEquals(0x40, lease.readCpuByte(0x3fff));
        assertEquals(0x21, lease.readCpuByte(0x4000));
        assertEquals(0x41, lease.readCpuByte(0x7fff));
        assertEquals(0x80, lease.readCpuByte(0x0100));
        assertEquals(0x81, lease.readCpuByte(0x4100));
        assertEquals(0x0100, lease.physicalOffset(0x0100));
        assertEquals(0x4100, lease.physicalOffset(0x4100));
        assertEquals(-1, lease.physicalOffset(0x8000));
        assertEquals(-1, lease.readCpuByte(-1));
        assertEquals(0xff, lease.readPhysicalByte(Integer.MAX_VALUE));

        mapper.setByte(0x2000, 0x03);
        assertEquals("borrowed mapping remains stable until reacquisition",
                0x81, lease.readCpuByte(0x4100));

        PerformanceRomAccess reacquired = mapper.acquirePerformanceRomAccess();
        assertSame("plain MBC5 acquisition must not allocate", lease, reacquired);
        assertEquals(0x83, reacquired.readCpuByte(0x4100));
        assertEquals(0xc100, reacquired.physicalOffset(0x4100));
    }

    @Test
    public void mapperUsesNinthBankBitModuloAndOpenBusForPartialFinalBank()
            throws IOException {
        byte[] rom = new byte[0x9000];
        rom[0x0000] = 0x50;
        rom[0x8000] = 0x52;
        rom[0x8fff] = 0x5f;
        rom[0x0147] = 0x19;
        rom[0x0148] = 0x00;
        Mbc5 mapper = new Mbc5(new Rom(rom), Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 0x02);
        PerformanceRomAccess partialBank = mapper.acquirePerformanceRomAccess();
        assertEquals(0x52, partialBank.readCpuByte(0x4000));
        assertEquals(0x5f, partialBank.readCpuByte(0x4fff));
        assertEquals(0xff, partialBank.readCpuByte(0x5000));
        assertEquals(0x9000, partialBank.physicalOffset(0x5000));

        mapper.setByte(0x3000, 0x01);
        PerformanceRomAccess ninthBitBank = mapper.acquirePerformanceRomAccess();
        assertSame(partialBank, ninthBitBank);
        assertEquals("0x102 wraps over the physical three-bank image to bank zero",
                0x50, ninthBitBank.readCpuByte(0x4000));
        assertEquals(0x0000, ninthBitBank.physicalOffset(0x4000));
    }

    @Test
    public void plainMbc5LeaseFailsClosedForDerivedMapperAndDebugHooks() throws IOException {
        Mbc5 derivedMapper = new Mbc5(new Rom(mbc5Rom()), Battery.NULL_BATTERY) {
        };
        assertNull(derivedMapper.acquirePerformanceRomAccess());

        Mbc5 debuggedMapper = plainMbc5();
        debuggedMapper.setDebugHooks(new TestDebugHooks());
        assertNull(debuggedMapper.acquirePerformanceRomAccess());
    }

    @Test
    public void productionWrapperPathRejectsBootOverlayCheatsAndActiveDma() throws IOException {
        Cartridge cartridge = new Cartridge(new Rom(mbc5Rom()), Battery.NULL_BATTERY);
        BiosShadow biosShadow = new BiosShadow(
                new Bios(HardwareProfileRegistry.CGB), cartridge);
        Mmu mmu = new Mmu(true);
        mmu.addAddressSpace(biosShadow);
        mmu.indexSpaces();

        Genie genie = new Genie(mmu, true);
        SpeedMode speedMode = new SpeedMode(true);
        Dma dma = new Dma(genie, new Ram(0xfe00, 0xa0), speedMode);
        DmaCpuAddressSpace cpuBus = new DmaCpuAddressSpace(genie, dma, true);

        assertNull("boot ROM overlay remains authoritative", cpuBus.acquirePerformanceRomAccess());

        biosShadow.setByte(0xff50, 0x01);
        PerformanceRomAccess lease = cpuBus.acquirePerformanceRomAccess();
        assertNotNull(lease);
        assertEquals(0x20, lease.readCpuByte(0x0000));
        assertEquals(0x40, lease.readCpuByte(0x3fff));
        assertEquals(0x21, lease.readCpuByte(0x4000));
        assertEquals(0x41, lease.readCpuByte(0x7fff));
        assertEquals(0x81, lease.readCpuByte(0x4100));

        try (EventBusImpl eventBus = new EventBusImpl(null, null, false)) {
            genie.init(eventBus);
            eventBus.post(new AddPatches(List.of(new GameGenieCheat(0x55, 0x4100, -1))));
            assertNull("active cheat must retain ordinary bus reads",
                    cpuBus.acquirePerformanceRomAccess());
        }

        Genie unpatchedGenie = new Genie(mmu, true);
        Dma activeDma = new Dma(unpatchedGenie, new Ram(0xfe00, 0xa0), speedMode);
        DmaCpuAddressSpace activeDmaBus =
                new DmaCpuAddressSpace(unpatchedGenie, activeDma, true);
        assertNotNull(activeDmaBus.acquirePerformanceRomAccess());

        activeDma.setByte(0xff46, 0x40);
        assertNull("active OAM DMA must retain conflict routing",
                activeDmaBus.acquirePerformanceRomAccess());
    }

    @Test
    public void detailedPpuLeaseCrossesDmaOnlyForNativeDoubleSpeedWramAndKeepsOverlayGuards()
            throws Exception {
        for (int topology = 0; topology < 4; topology++) {
            for (int source : new int[]{0x40, 0x80, 0xc0, 0xdf, 0xe0}) {
                boolean color = topology != 3;
                SpeedMode speed = new SpeedMode(color);
                if (topology == 0) {
                    speed.setByte(0xff4d, 1);
                    var onStop = SpeedMode.class.getDeclaredMethod("onStop");
                    onStop.setAccessible(true);
                    assertEquals(true, onStop.invoke(speed));
                } else if (topology == 2) {
                    speed.setDmgCompat(true);
                }
                Cartridge cartridge = new Cartridge(new Rom(mbc5Rom()), Battery.NULL_BATTERY);
                BiosShadow shadow = new BiosShadow(new Bios(HardwareProfileRegistry.CGB), cartridge);
                Mmu mmu = new Mmu(color);
                mmu.addAddressSpace(shadow);
                mmu.addAddressSpace(new Ram(0xc000, 0x2000));
                mmu.indexSpaces();
                Dma dma = new Dma(mmu, new Ram(0xfe00, 0xa0), speed);
                DmaCpuAddressSpace dmaBus = new DmaCpuAddressSpace(mmu, dma, color);
                Genie outer = new Genie(dmaBus, color);
                assertNull("boot overlay cannot be bypassed", outer.acquirePerformanceDetailedPpuRomAccess());
                shadow.setByte(0xff50, 1);
                var inactive = dma.captureState();
                dma.setByte(0xff46, source);
                assertNull("acquisition remains scalar", outer.acquirePerformanceDetailedPpuRomAccess());
                for (int tick = 0; tick < 8; tick++) dma.tick(false, false);
                assertNull("ordinary leases remain disabled for all active DMA", outer.acquirePerformanceRomAccess());
                boolean permitted = topology == 0 && source >= 0xc0 && source < 0xe0;
                PerformanceRomAccess lease = outer.acquirePerformanceDetailedPpuRomAccess();
                if (permitted) {
                    assertNotNull(lease);
                    assertEquals(0x80, lease.readCpuByte(0x100));
                    dma.setDebugHooks(new TestDebugHooks());
                    assertNull("DMA observation remains authoritative", outer.acquirePerformanceDetailedPpuRomAccess());
                    dma.setDebugHooks(null);
                    try (EventBusImpl events = new EventBusImpl(null, null, false)) {
                        outer.init(events);
                        events.post(new AddPatches(List.of(new GameGenieCheat(0x55, 0x4100, -1))));
                        assertNull("outer cheat cannot be bypassed", outer.acquirePerformanceDetailedPpuRomAccess());
                    }
                } else {
                    assertNull("unsupported source/topology " + topology + '/' + source, lease);
                }
                dma.restoreState(inactive);
                assertNotNull("ordinary reader restores after DMA ends", dmaBus.acquirePerformanceRomAccess());
            }
        }
    }

    @Test
    public void mmuRejectsAProviderWhenAnyRomAddressHasAnotherOwner() {
        PerformanceRomAccess access = new ConstantPerformanceRomAccess();
        Mmu mmu = new Mmu(true);
        mmu.addAddressSpace(new SingleAddressSpace(0x2345));
        mmu.addAddressSpace(new RomProviderAddressSpace(access));
        mmu.indexSpaces();

        assertNull(mmu.acquirePerformanceRomAccess());
    }

    @Test
    public void basicRomCartridgeUsesReusedExactPhysicalReader() throws IOException {
        byte[] rom = new byte[0x8000];
        rom[0x0000] = 0x21;
        rom[0x3fff] = 0x43;
        rom[0x4000] = 0x65;
        rom[0x7fff] = (byte) 0x87;
        rom[0x0147] = 0x00;

        Cartridge cartridge = new Cartridge(new Rom(rom), Battery.NULL_BATTERY);

        PerformanceRomAccess access = cartridge.acquirePerformanceRomAccess();
        assertNotNull(access);
        assertSame(access, cartridge.acquirePerformanceRomAccess());
        assertEquals(0, access.physicalOffset(0x0000));
        assertEquals(0x7fff, access.physicalOffset(0x7fff));
        assertEquals(0x21, access.readPhysicalByte(0));
        assertEquals(0x87, access.readPhysicalByte(0x7fff));
        assertEquals(0xff, access.readPhysicalByte(0x8000));
        assertEquals(0x21, access.readCpuByte(0x0000));
        assertEquals(0x43, access.readCpuByte(0x3fff));
        assertEquals(0x65, access.readCpuByte(0x4000));
        assertEquals(0x87, access.readCpuByte(0x7fff));
        assertEquals(-1, access.readCpuByte(-1));
        assertEquals(-1, access.readCpuByte(0x8000));
    }

    @Test
    public void productionCartridgeFallsBackWhenSpecializedMapperDeclines() throws IOException {
        Cartridge cartridge = new Cartridge(new Rom(mbc5Rom()), Battery.NULL_BATTERY);
        cartridge.setDebugHooks(new TestDebugHooks());

        PerformanceRomAccess access = cartridge.acquirePerformanceRomAccess();

        assertNotNull(access);
        assertEquals(-1, access.physicalOffset(0x0000));
        assertEquals(0x20, access.readCpuByte(0x0000));
        assertEquals(0x21, access.readCpuByte(0x4000));
    }

    @Test
    public void derivedCartridgeStillFailsClosed() throws IOException {
        Cartridge derived = new Cartridge(new Rom(mbc5Rom()), Battery.NULL_BATTERY) {
            @Override
            public int getByte(int address) {
                return 0x5a;
            }
        };

        assertNull(derived.acquirePerformanceRomAccess());
    }

    private static Mbc5 plainMbc5() throws IOException {
        return new Mbc5(new Rom(mbc5Rom()), Battery.NULL_BATTERY);
    }

    private static Mbc7 plainMbc7() throws IOException {
        return new Mbc7(new Rom(mbc7Rom()), Battery.NULL_BATTERY);
    }

    private static byte[] mbc7Rom() {
        byte[] rom = bankedRom();
        rom[0x0147] = 0x22;
        return rom;
    }

    private static byte[] mbc5Rom() {
        byte[] rom = bankedRom();
        rom[0x0147] = 0x19;
        return rom;
    }

    private static byte[] bankedRom() {
        byte[] rom = new byte[0x40000];
        for (int bank = 0; bank < 16; bank++) {
            rom[bank * 0x4000] = (byte) (0x20 + bank);
            rom[bank * 0x4000 + 0x3fff] = (byte) (0x40 + bank);
            rom[bank * 0x4000 + 0x0100] = (byte) (0x80 + bank);
        }
        rom[0x0148] = 0x04;
        return rom;
    }

    private static final class ConstantPerformanceRomAccess implements PerformanceRomAccess {

        @Override
        public int physicalOffset(int cpuAddress) {
            return cpuAddress >= 0 && cpuAddress < 0x8000 ? cpuAddress : -1;
        }

        @Override
        public int readPhysicalByte(int physicalOffset) {
            return 0x42;
        }
    }

    private static final class RomProviderAddressSpace
            implements AddressSpace, PerformanceRomAccessProvider {

        private final PerformanceRomAccess access;

        private RomProviderAddressSpace(PerformanceRomAccess access) {
            this.access = access;
        }

        @Override
        public boolean accepts(int address) {
            return address >= 0 && address < 0x8000;
        }

        @Override
        public void setByte(int address, int value) {
        }

        @Override
        public int getByte(int address) {
            return access.readCpuByte(address);
        }

        @Override
        public PerformanceRomAccess acquirePerformanceRomAccess() {
            return access;
        }
    }

    private static final class SingleAddressSpace implements AddressSpace {

        private final int address;

        private SingleAddressSpace(int address) {
            this.address = address;
        }

        @Override
        public boolean accepts(int address) {
            return address == this.address;
        }

        @Override
        public void setByte(int address, int value) {
        }

        @Override
        public int getByte(int address) {
            return 0xff;
        }
    }
}
