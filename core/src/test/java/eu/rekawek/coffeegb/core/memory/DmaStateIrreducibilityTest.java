package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmaStateIrreducibilityTest {

    @Test
    public void fixedRateTicksAndCpuClocksDivergeAcrossSpeedChanges() {
        Fixture normalSix = new Fixture();
        normalSix.start();
        normalSix.tick(6);
        Fixture doubleThree = new Fixture();
        doubleThree.speed.speed = 2;
        doubleThree.start();
        doubleThree.tick(3);
        doubleThree.speed.speed = 1;

        assertEquals(normalSix.state(), withCausalFields(doubleThree.state(), null,
                normalSix.state().ticks(), null, null, null, null));
        assertTrue(normalSix.dma.isOamBlocked());
        assertFalse(doubleThree.dma.isOamBlocked());
    }

    @Test
    public void cpuClockAgeControlsNextCopyEdgeIndependentOfTicks() {
        Fixture normalEight = new Fixture();
        normalEight.start();
        normalEight.tick(8);
        Fixture mixedEight = new Fixture();
        mixedEight.speed.speed = 2;
        mixedEight.start();
        mixedEight.tick(1);
        mixedEight.speed.speed = 1;
        mixedEight.tick(7);

        assertEquals(normalEight.state(), withCausalFields(mixedEight.state(), null,
                null, normalEight.state().transferClocks(), null, null, null));
        normalEight.tick(3);
        mixedEight.tick(3);
        assertEquals(0xee, normalEight.oam.getByte(0xfe01));
        assertEquals(0x41, mixedEight.oam.getByte(0xfe01));
    }

    @Test
    public void restartLatchCarriesInformationAbsentFromEveryOtherComponent() {
        Fixture fresh = new Fixture();
        fresh.start();
        Fixture restarted = new Fixture();
        restarted.start();
        restarted.tick(5);
        restarted.start();

        assertEquals(fresh.state(), withCausalFields(restarted.state(), fresh.state().restarted(),
                null, null, null, null, null));
        assertFalse(fresh.dma.isOamBlocked());
        assertTrue(restarted.dma.isOamBlocked());
    }

    @Test
    public void releasedPpuOwnershipComponentsRemainIndependentlyObservable() {
        Fixture template = new Fixture();
        template.start();
        Dma.DmaState base = template.state();

        Fixture bridgeLow = restored(withCausalFields(base, null, null, null,
                null, null, false));
        Fixture bridgeHigh = restored(withCausalFields(base, null, null, null,
                null, null, true));
        bridgeLow.tick(1);
        bridgeHigh.tick(1);
        assertFalse(bridgeLow.dma.ownsOamForPpu());
        assertTrue(bridgeHigh.dma.ownsOamForPpu());

        Fixture currentLow = restored(withCausalFields(base, null, null, null,
                null, false, null));
        Fixture currentHigh = restored(withCausalFields(base, null, null, null,
                null, true, null));
        assertFalse(currentLow.dma.ownsOamForPpu());
        assertTrue(currentHigh.dma.ownsOamForPpu());

        Fixture beforeLow = restored(withCausalFields(base, null, null, null,
                false, null, null));
        Fixture beforeHigh = restored(withCausalFields(base, null, null, null,
                true, null, null));
        assertFalse(beforeLow.dma.hasPpuOamOwnershipTransitionThisTick());
        assertTrue(beforeHigh.dma.hasPpuOamOwnershipTransitionThisTick());
    }

    private static Fixture restored(Dma.DmaState state) {
        Fixture fixture = new Fixture();
        fixture.dma.restoreState(state);
        return fixture;
    }

    private static Dma.DmaState withCausalFields(Dma.DmaState s, Boolean restarted,
            Integer ticks, Integer transferClocks, Boolean before, Boolean owned,
            Boolean throughRestart) {
        return new Dma.DmaState(s.transferInProgress(),
                restarted == null ? s.restarted() : restarted, s.from(),
                ticks == null ? s.ticks() : ticks,
                transferClocks == null ? s.transferClocks() : transferClocks,
                before == null ? s.oamOwnedForPpuBeforeTick() : before,
                owned == null ? s.oamOwnedForPpu() : owned,
                throughRestart == null ? s.ppuOamOwnedThroughRestart() : throughRestart,
                s.cpuClockPaused(), s.pauseEntryClocks(), s.currentByte(), s.regValue(),
                s.pendingInterruptWriteByte(), s.pendingInterruptWriteValue(),
                s.vramDmaBusCollisionObserved());
    }

    private static class Fixture {
        private final Ram memory = new Ram(0, 0x10000);
        private final Ram oam = new Ram(0xfe00, 0xa0);
        private final MutableSpeedMode speed = new MutableSpeedMode();
        private final Dma dma = new Dma(memory, oam, speed);

        private Fixture() {
            for (int i = 0; i < 0xa0; i++) {
                memory.setByte(0x1200 + i, 0x40 + i);
                oam.setByte(0xfe00 + i, 0xee);
            }
        }

        private void start() { dma.setByte(0xff46, 0x12); }

        private void tick(int count) {
            for (int i = 0; i < count; i++) dma.tick();
        }

        private Dma.DmaState state() { return (Dma.DmaState) dma.captureState(); }
    }

    private static class MutableSpeedMode extends SpeedMode {
        private int speed = 1;

        private MutableSpeedMode() { super(true); }

        @Override
        public int getSpeedMode() { return speed; }
    }
}
