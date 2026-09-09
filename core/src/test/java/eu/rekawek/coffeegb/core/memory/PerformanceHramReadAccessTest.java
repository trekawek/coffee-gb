package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.AddPatches;
import eu.rekawek.coffeegb.core.genie.GameGenieCheat;
import eu.rekawek.coffeegb.core.genie.Genie;
import org.junit.Test;

import java.util.List;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class PerformanceHramReadAccessTest {
    @Test
    public void ownedLiveViewIsIndependentOfRomMappingAndSurvivesCanonicalWritesAndRestore() {
        Mmu mmu = new Mmu(true);
        assertNull(mmu.acquirePerformanceDetailedPpuHramReadAccess(54));
        mmu.addAddressSpace(new Ram(0, 0x4000));
        mmu.addAddressSpace(new Ram(0x4000, 0x4000));
        mmu.indexSpaces(); // The split ROM proof returns early; HRAM ownership must still be known.
        assertNull(mmu.acquirePerformanceRomAccess());
        PerformanceHramReadAccess view = mmu.acquirePerformanceDetailedPpuHramReadAccess(54);
        assertNotNull(view);
        assertSame(view, mmu.acquirePerformanceDetailedPpuHramReadAccess(1));
        assertNull(mmu.acquirePerformanceDetailedPpuHramReadAccess(0));
        for (int address : new int[]{-1, 0, 0xff7f, 0xfffe, 0xffff, 0x1ff80}) {
            assertEquals("unmapped address must not wrap or enter the bus", -1, view.readCpuByte(address));
        }
        mmu.setByte(0xff80, 0x12);
        mmu.setByteFromCpu(0xfffd, 0x34);
        assertEquals(0x12, view.readCpuByte(0xff80));
        assertEquals(0x34, view.readCpuByte(0xfffd));
        var saved = mmu.captureState();
        mmu.setByteFromCpu(0xff80, 0xab);
        assertEquals("the view must not cache content", 0xab, view.readCpuByte(0xff80));
        mmu.restoreState(saved);
        assertEquals(0x12, mmu.acquirePerformanceDetailedPpuHramReadAccess(54).readCpuByte(0xff80));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unknownMappingsAndDerivedWrappersFailClosed() throws Exception {
        Mmu derived = new Mmu(true) { @Override public int getByte(int address) { return 0x55; } };
        derived.indexSpaces();
        assertNull(derived.acquirePerformanceDetailedPpuHramReadAccess(54));

        Mmu replaced = new Mmu(true);
        var spacesField = Mmu.class.getDeclaredField("spaces");
        spacesField.setAccessible(true);
        ((List<AddressSpace>) spacesField.get(replaced)).add(0, new Ram(0xff80, 2));
        replaced.indexSpaces();
        assertNull("every indexed byte must belong to the exact owned Ram",
                replaced.acquirePerformanceDetailedPpuHramReadAccess(54));

        Mmu mmu = indexedMmu();
        assertNull(new RomOnlyWrapper(mmu).acquirePerformanceDetailedPpuHramReadAccess(54));
        assertNull(new Genie(mmu, true) { }.acquirePerformanceDetailedPpuHramReadAccess(54));
        SpeedMode speed = doubleSpeed();
        Dma dma = new Dma(mmu, new Ram(0xfe00, 0xa0), speed);
        assertNull(new DmaCpuAddressSpace(mmu, dma, true) { }
                .acquirePerformanceDetailedPpuHramReadAccess(54));
        Dma derivedDma = new Dma(mmu, new Ram(0xfe00, 0xa0), speed) { };
        assertNull(new DmaCpuAddressSpace(mmu, derivedDma, true)
                .acquirePerformanceDetailedPpuHramReadAccess(54));
    }

    @Test
    public void cheatChangesBetweenPacketsDisableTheBypass() {
        Mmu mmu = indexedMmu();
        mmu.setByte(0xff80, 0x04);
        Genie genie = new Genie(mmu, true);
        assertEquals(0x04, genie.acquirePerformanceDetailedPpuHramReadAccess(54).readCpuByte(0xff80));
        try (EventBusImpl events = new EventBusImpl(null, null, false)) {
            genie.init(events);
            events.post(new AddPatches(List.of(new GameGenieCheat(0x0c, 0xff80, -1))));
            assertNull(genie.acquirePerformanceDetailedPpuHramReadAccess(54));
            assertEquals("patched reads retain the real wrapper", 0x0c, genie.getByte(0xff80));
        }
    }

    @Test
    public void nativeOwnedWramAndIdleProofCoversTheWholeRequestedPrefixOnly() throws Exception {
        Mmu mmu = indexedMmu();
        Genie genie = new Genie(mmu, true);
        SpeedMode speed = doubleSpeed();
        Dma dma = new Dma(genie, new Ram(0xfe00, 0xa0), speed);
        DmaCpuAddressSpace bus = new DmaCpuAddressSpace(genie, dma, true);
        assertNotNull(bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        assertNull(bus.acquirePerformanceDetailedPpuHramReadAccess(0));
        dma.setByte(0xff46, 0xc0);
        assertNull("acquisition stays canonical", bus.acquirePerformanceDetailedPpuHramReadAccess(1));
        for (int i = 0; i < 5; i++) dma.tick();
        var before = dma.captureState();
        assertNotNull(bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        assertStateEquals("acquisition must not advance the copy", before, dma.captureState());
        while (dma.performanceNativeCgbWramReplaySpanLimit(54) == 54) dma.tick();
        assertNull("a one-dot interior proof cannot certify a 54-dot packet",
                bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        assertNotNull(bus.acquirePerformanceDetailedPpuHramReadAccess(1));
        while (dma.isTransferInProgress()) dma.tick();
        dma.tick(); // Settle the retained release/clock flags before the idle view is borrowed.
        assertNotNull(bus.acquirePerformanceDetailedPpuHramReadAccess(54));

        dma.setDebugHooks(new TestDebugHooks());
        assertNull(bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        dma.setDebugHooks(null);
        speed.setDmgCompat(true);
        assertNull(bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        SpeedMode normalSpeed = new SpeedMode(true);
        Dma normalDma = new Dma(mmu, new Ram(0xfe00, 0xa0), normalSpeed);
        assertNull(new DmaCpuAddressSpace(mmu, normalDma, true)
                .acquirePerformanceDetailedPpuHramReadAccess(54));
    }

    @Test
    public void otherDmaSourcesRestartsAndPausedClocksReject() throws Exception {
        for (int source : new int[]{0x00, 0x40, 0x80, 0xa0, 0xe0, 0xff}) {
            Mmu mmu = indexedMmu();
            Dma dma = new Dma(mmu, new Ram(0xfe00, 0xa0), doubleSpeed());
            DmaCpuAddressSpace bus = new DmaCpuAddressSpace(mmu, dma, true);
            dma.setByte(0xff46, source);
            for (int i = 0; i < 5; i++) dma.tick();
            assertNull("unproved source " + source, bus.acquirePerformanceDetailedPpuHramReadAccess(54));
        }
        Mmu mmu = indexedMmu();
        Dma dma = new Dma(mmu, new Ram(0xfe00, 0xa0), doubleSpeed());
        DmaCpuAddressSpace bus = new DmaCpuAddressSpace(mmu, dma, true);
        dma.setByte(0xff46, 0xc0);
        for (int i = 0; i < 5; i++) dma.tick();
        dma.tick(true, false);
        assertNull(bus.acquirePerformanceDetailedPpuHramReadAccess(1));
        dma.tick(false, false);
        dma.setByte(0xff46, 0xc0);
        assertNull(bus.acquirePerformanceDetailedPpuHramReadAccess(1));
    }

    private static Mmu indexedMmu() { Mmu mmu = new Mmu(true); mmu.indexSpaces(); return mmu; }

    private static SpeedMode doubleSpeed() throws Exception {
        SpeedMode speed = new SpeedMode(true);
        speed.setByte(0xff4d, 1);
        var onStop = SpeedMode.class.getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertEquals(true, onStop.invoke(speed));
        return speed;
    }

    /** Advertising a ROM reader never implicitly advertises the new HRAM read capability. */
    private record RomOnlyWrapper(Mmu delegate) implements AddressSpace, PerformanceRomAccessProvider {
        @Override public boolean accepts(int address) { return true; }
        @Override public int getByte(int address) { return delegate.getByte(address); }
        @Override public void setByte(int address, int value) { delegate.setByte(address, value); }
        @Override public PerformanceRomAccess acquirePerformanceRomAccess() { return delegate.acquirePerformanceRomAccess(); }
    }
}
