package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.AddPatches;
import eu.rekawek.coffeegb.core.genie.GameGenieCheat;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.BasicRom;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class BasicRomPerformanceAccessTest {
    @Test
    public void physicalLeaseMatchesEveryCpuByteIncludingPartialImagesAndOpenBus() throws Exception {
        for (int length : new int[]{0x150, 0x200, 0x4001, 0x7fff, 0x8000, 0x9000}) {
            Rom rom = new Rom(image(length));
            BasicRom mapper = new BasicRom(rom);
            PerformanceRomAccess lease = mapper.acquirePerformanceRomAccess();
            assertNotNull(lease);
            assertSame("acquisition allocates no new view", lease, mapper.acquirePerformanceRomAccess());
            assertTrue(lease.canAccessRam());
            for (int address = 0; address < 0x8000; address++) {
                assertEquals(address, lease.physicalOffset(address));
                assertEquals(mapper.getByte(address), lease.readCpuByte(address));
                assertEquals(mapper.getByte(address), lease.peekCpuByte(address));
                assertEquals(mapper.getByte(address), lease.readPhysicalByte(address));
            }
            for (int invalid : new int[]{-1, 0x8000, 0xa000, 0xffff, 0x10000, Integer.MAX_VALUE}) {
                assertEquals(-1, lease.physicalOffset(invalid));
                assertEquals(-1, lease.readCpuByte(invalid));
                assertEquals(-1, lease.peekCpuByte(invalid));
            }
            assertEquals(0xff, lease.readPhysicalByte(-1));
            assertEquals(0xff, lease.readPhysicalByte(rom.getRom().length));
            assertEquals(0xff, lease.readPhysicalByte(Integer.MAX_VALUE));
            // Test-only mutation proves this is the mapper's live backing, not another ROM copy
            // or decoded instruction cache. Normal cartridge execution never mutates this array.
            rom.getRom()[0x100] ^= 0x5a;
            assertEquals(mapper.getByte(0x100), lease.readCpuByte(0x100));
            assertEquals(mapper.getByte(0x100), lease.peekCpuByte(0x100));
        }
    }

    @Test
    public void derivedMapperAndCartridgeStillRequireExplicitReadProof() throws Exception {
        int[] reads = {0};
        BasicRom derived = new BasicRom(new Rom(image(0x8000))) {
            @Override public int getByte(int address) { reads[0]++; return 0x62; }
        };
        assertNull(derived.acquirePerformanceRomAccess());
        assertFalse(derived.isPerformanceRomPeekSafe());
        assertFalse(derived.isPerformanceRamAccessSafe());
        assertEquals(0, reads[0]);
        Cartridge cartridge = new Cartridge(new Rom(image(0x8000)), Battery.NULL_BATTERY) {};
        assertNull(cartridge.acquirePerformanceRomAccess());
    }

    @Test
    public void productionOuterBusKeepsBootCheatDebugAndDmaFences() throws Exception {
        Cartridge cartridge = new Cartridge(new Rom(image(0x8000)), Battery.NULL_BATTERY);
        BiosShadow bios = new BiosShadow(new Bios(HardwareProfileRegistry.CGB), cartridge);
        Mmu mmu = new Mmu(true);
        mmu.addAddressSpace(bios);
        mmu.addAddressSpace(new Ram(0xc000, 0x2000));
        mmu.addAddressSpace(new Ram(0xff80, 0x7f));
        mmu.indexSpaces();
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        var stop = SpeedMode.class.getDeclaredMethod("onStop"); stop.setAccessible(true);
        assertEquals(true, stop.invoke(speed));
        Dma dma = new Dma(mmu, new Ram(0xfe00, 0xa0), speed);
        DmaCpuAddressSpace dmaBus = new DmaCpuAddressSpace(mmu, dma, true);
        Genie outer = new Genie(dmaBus, true);
        assertNull(outer.acquirePerformanceRomAccess());
        assertNull(outer.acquirePerformanceDetailedPpuRomAccess());
        bios.setByte(0xff50, 1);
        PerformanceRomAccess lease = outer.acquirePerformanceRomAccess();
        assertNotNull(lease);
        assertEquals("plain mapper exposes the physical tier through the unchanged chain",
                0x4100, lease.physicalOffset(0x4100));
        assertEquals(outer.getByte(0x4100), lease.readCpuByte(0x4100));
        Cpu cpu = new Cpu(outer, new InterruptManager(true), null, speed, new Display(false));
        cpu.setDebugHooks(new TestDebugHooks());
        assertEquals("debug owner remains scalar", 0, cpu.runNativeCgbPerformanceEpoch(54));
        assertEquals(0, cpu.runNativeCgbDetailedPpuPerformanceEpoch(54));
        var idle = dma.captureState();
        dma.setByte(0xff46, 0x40);
        for (int n = 0; n < 8; n++) dma.tick();
        assertNull(outer.acquirePerformanceRomAccess());
        assertNull("ROM-source DMA retains cartridge conflict routing",
                outer.acquirePerformanceDetailedPpuRomAccess());
        dma.restoreState(idle);
        dma.setByte(0xff46, 0xc0);
        for (int n = 0; n < 8; n++) dma.tick();
        assertNull(outer.acquirePerformanceRomAccess());
        assertNotNull("existing native WRAM DMA proof remains available",
                outer.acquirePerformanceDetailedPpuRomAccess());
        dma.restoreState(idle);
        try (EventBusImpl events = new EventBusImpl(null, null, false)) {
            outer.init(events);
            events.post(new AddPatches(List.of(new GameGenieCheat(0x55, 0x4100, -1))));
            assertEquals(0x55, outer.getByte(0x4100));
            assertNull(outer.acquirePerformanceRomAccess());
            assertNull(outer.acquirePerformanceDetailedPpuRomAccess());
        }
    }

    @Test
    public void physicalFetchesRetainCanonicalMachineStateAcrossProfilesAndRestoredTails() throws Exception {
        for (PerformanceWorkloads.Profile profile : PerformanceWorkloads.Profile.values()) {
            try (Gameboy scalar = PerformanceWorkloads.session(PerformanceWorkloads.Scenario.CPU, profile);
                 Gameboy batch = PerformanceWorkloads.session(PerformanceWorkloads.Scenario.CPU, profile)) {
                scalar.setPerformanceBatchingEnabled(false); batch.setPerformanceBatchingEnabled(false);
                scalar.runTicks(210_000); batch.runTicks(210_000);
                batch.setPerformanceBatchingEnabled(true);
                for (int tail = 1; tail <= 54; tail++) {
                    scalar.restoreStateSilently(scalar.captureStateWithoutTimeSource());
                    batch.restoreStateSilently(batch.captureStateWithoutTimeSource());
                    assertEquals(scalar.runTicks(tail), batch.runTicks(tail));
                    assertStateEquals(profile + " restored tail " + tail,
                            scalar.captureStateWithoutTimeSource(), batch.captureStateWithoutTimeSource());
                }
                long before = batch.getCpu().getPerformanceEpochTicks();
                for (int frame = 0; frame < 2; frame++) {
                    assertEquals(scalar.runTicks(70_224), batch.runTicks(70_224));
                    assertStateEquals(profile + " full frame " + frame,
                            scalar.captureStateWithoutTimeSource(), batch.captureStateWithoutTimeSource());
                }
                assertTrue(profile + " sustained physical ROM epoch coverage",
                        batch.getCpu().getPerformanceEpochTicks() - before > 70_224);
            }
        }
    }

    private static byte[] image(int length) {
        byte[] image = new byte[length];
        for (int i = 0; i < image.length; i++) image[i] = (byte) (i * 37 + (i >>> 8));
        image[0x143] = (byte) 0x80;
        image[0x147] = 0;
        image[0x148] = 0;
        image[0x149] = 0;
        return image;
    }
}
