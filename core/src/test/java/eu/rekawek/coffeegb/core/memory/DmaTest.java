package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DmaTest {

    @Test
    public void copiesOamProgressively() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.tick(7);
        assertEquals(0xee, fixture.oam.getByte(0xfe00));

        fixture.tick(1);
        assertEquals(0x40, fixture.oam.getByte(0xfe00));
        assertEquals(0xee, fixture.oam.getByte(0xfe01));

        fixture.tick(4);
        assertEquals(0x41, fixture.oam.getByte(0xfe01));
        assertEquals(0xee, fixture.oam.getByte(0xfe02));

        fixture.tick(632);
        assertEquals(0xdf, fixture.oam.getByte(0xfe9f));
        assertTrue(fixture.dma.isTransferInProgress());

        fixture.tick(4);
        assertFalse(fixture.dma.isTransferInProgress());
    }

    @Test
    public void ppuReadsTheOamWordCurrentlyDrivenByDma() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.tick(8);
        assertEquals(0x40, fixture.dma.getOamByteForPpu(0xfe04));
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe05));

        fixture.tick(4);
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe00));
        assertEquals(0xee, fixture.dma.getOamByteForPpu(0xfe01));
    }

    @Test
    public void ppuOamOwnershipStartsWithTheFirstCopyEdge() {
        Fixture fixture = new Fixture();
        fixture.start();

        fixture.tick(7);
        assertFalse(fixture.dma.ownsOamForPpu());
        fixture.tick(1);
        assertTrue(fixture.dma.ownsOamForPpu());
    }

    @Test
    public void settledInactiveOwnershipKeepsBothPpuReaderSnapshotsFalse() {
        Fixture fixture = new Fixture();

        for (int i = 0; i < 4; i++) {
            fixture.dma.tick();

            assertFalse(fixture.dma.ownedOamForPpuBeforeTick());
            assertFalse(fixture.dma.ownsOamForPpu());
            assertFalse(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
        }
    }

    @Test
    public void settledInactiveDmaDoesNotRequireAClockTick() {
        Fixture fixture = new Fixture();

        assertFalse(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void inactivePauseTransitionsRequireOneClockTick() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.dma.requiresClockTick(true));
        fixture.dma.tick(true);
        assertFalse(fixture.dma.requiresClockTick(true));

        assertTrue(fixture.dma.requiresClockTick(false));
        fixture.dma.tick(false);
        assertFalse(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void inactiveVramDmaBusSampleRequiresOneClockTickToClear() {
        Fixture fixture = new Fixture();
        fixture.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0x1234, 0x56));

        assertTrue(fixture.dma.requiresClockTick(false));
        fixture.dma.tick();
        assertFalse(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void activeDmaAlwaysRequiresAClockTick() {
        Fixture fixture = new Fixture();
        fixture.start();

        assertTrue(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void completedDmaRequiresExactlyOneOwnershipDrainTick() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(648);

        assertFalse(fixture.dma.isTransferInProgress());
        assertTrue(fixture.dma.requiresClockTick(false));
        fixture.dma.tick();
        assertFalse(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void skippingSettledDmaClockPreservesMementoState() {
        Fixture fixture = new Fixture();
        fixture.dma.tick(true);
        assertFalse(fixture.dma.requiresClockTick(true));
        var settledState = fixture.dma.captureState();

        Fixture ticked = new Fixture();
        ticked.dma.restoreState(settledState);
        ticked.dma.tick(true);

        assertEquals(settledState, ticked.dma.captureState());
    }

    @Test
    public void restoredRestartedStateRequiresAClockTick() {
        Fixture fixture = new Fixture();
        Dma.DmaState settled = (Dma.DmaState) fixture.dma.captureState();
        fixture.dma.restoreState(new Dma.DmaState(false, true,
                settled.from(), settled.ticks(), settled.transferClocks(),
                settled.oamOwnedForPpuBeforeTick(), settled.oamOwnedForPpu(),
                settled.ppuOamOwnedThroughRestart(), settled.cpuClockPaused(),
                settled.pauseEntryClocks(), settled.currentByte(), settled.regValue(),
                settled.pendingInterruptWriteByte(), settled.pendingInterruptWriteValue(),
                settled.vramDmaBusCollisionObserved()));

        assertTrue(fixture.dma.requiresClockTick(false));
    }

    @Test
    public void ppuOamOwnershipTransitionIsObservableAtAcquireAndRelease() {
        Fixture fixture = new Fixture(true);
        fixture.start();

        fixture.tick(6);
        assertFalse(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
        fixture.tick(1);
        assertFalse(fixture.dma.ownedOamForPpuBeforeTick());
        assertTrue(fixture.dma.ownsOamForPpu());
        assertTrue(fixture.dma.hasPpuOamOwnershipTransitionThisTick());

        fixture.tick(639);
        assertFalse(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
        fixture.tick(1);
        assertTrue(fixture.dma.ownedOamForPpuBeforeTick());
        assertFalse(fixture.dma.ownsOamForPpu());
        assertTrue(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
    }

    @Test
    public void normalSpeedCgbUsesItsOamReaderClockPhase() {
        Fixture fixture = new Fixture(true);
        fixture.start();

        fixture.tick(6);
        assertFalse(fixture.dma.ownsOamForPpu());
        fixture.tick(1);
        assertTrue(fixture.dma.ownsOamForPpu());

        fixture.tick(639);
        assertTrue(fixture.dma.ownsOamForPpu());
        fixture.tick(1);
        assertFalse(fixture.dma.ownsOamForPpu());
        assertTrue(fixture.dma.isTransferInProgress());
    }

    @Test
    public void ppuOamOwnershipRoundTripsThroughMemento() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(8);
        var state = fixture.dma.captureState();

        Fixture restored = new Fixture();
        restored.dma.restoreState(state);

        assertFalse(restored.dma.ownedOamForPpuBeforeTick());
        assertTrue(restored.dma.ownsOamForPpu());
        restored.tick(1);
        assertTrue(restored.dma.ownedOamForPpuBeforeTick());
        assertTrue(restored.dma.ownsOamForPpu());
    }

    @Test
    public void restartDuringWarmupWaitsForTheNewPpuAcquisitionEdge() {
        Fixture fixture = new Fixture(true);
        fixture.start();
        fixture.tick(5);
        assertFalse(fixture.dma.ownsOamForPpu());

        fixture.start();
        fixture.tick(6);
        assertFalse(fixture.dma.ownsOamForPpu());
        fixture.tick(1);
        assertTrue(fixture.dma.ownsOamForPpu());
    }

    @Test
    public void doubleSpeedRestartKeepsAnOwnedPpuReaderConnected() {
        Fixture fixture = new Fixture(true, 2);
        fixture.start();
        fixture.tick(4);
        assertTrue(fixture.dma.ownsOamForPpu());

        fixture.start();
        fixture.tick(1);
        assertTrue(fixture.dma.ownsOamForPpu());
        assertFalse(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
    }

    @Test
    public void restartStillUsesTheNormalCgbPpuReleaseEdge() {
        Fixture fixture = new Fixture(true);
        fixture.start();
        fixture.tick(7);
        fixture.start();

        fixture.tick(646);
        assertTrue(fixture.dma.ownsOamForPpu());
        fixture.tick(1);
        assertFalse(fixture.dma.ownsOamForPpu());
        assertTrue(fixture.dma.isTransferInProgress());
    }

    @Test
    public void haltPausesAfterTwoMachineCycleEntryLatency() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(8);

        for (int i = 0; i < 8; i++) {
            fixture.dma.tick(true);
        }
        for (int i = 0; i < 32; i++) {
            fixture.dma.tick(true);
        }

        assertFalse(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
        assertEquals(0x40, fixture.oam.getByte(0xfe00));
        assertEquals(0x41, fixture.oam.getByte(0xfe01));
        assertEquals(0x42, fixture.oam.getByte(0xfe02));
        assertEquals(0xee, fixture.oam.getByte(0xfe03));

        fixture.dma.tick(false);
        fixture.tick(3);
        assertEquals(0x43, fixture.oam.getByte(0xfe03));
    }

    @Test
    public void vramDmaBusSampleUsesItsSourceLowByteAsTheOamDestination() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(51);

        fixture.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0x0001, 0x9e));
        fixture.dma.tick();

        assertEquals(0x40, fixture.oam.getByte(0xfe00));
        assertEquals(0x9e, fixture.oam.getByte(0xfe01));
        assertEquals(0x4a, fixture.oam.getByte(0xfe0a));
        assertEquals(0xee, fixture.oam.getByte(0xfe0b));
    }

    @Test
    public void transientVramDmaBusSampleIsClearedByMementoRestore() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(51);
        var beforeCollision = fixture.dma.captureState();
        fixture.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0x0001, 0x9e));

        fixture.dma.restoreState(beforeCollision);
        fixture.dma.tick();

        assertEquals(0x41, fixture.oam.getByte(0xfe01));
        assertEquals(0x4b, fixture.oam.getByte(0xfe0b));
    }

    @Test
    public void vramDmaCollisionBusPhaseSurvivesMementoRestore() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(51);
        fixture.dma.setVramDmaBusSample(new Hdma.SourceBusSample(0x0001, 0x9e));
        fixture.dma.tick();
        var afterCollision = fixture.dma.captureState();

        assertEquals(0x4a, fixture.dma.getCpuBusValue());
        fixture.start();
        fixture.dma.restoreState(afterCollision);

        assertEquals(0x4a, fixture.dma.getCpuBusValue());
    }

    @Test
    public void speedSwitchPausesWithoutHaltEntryLatency() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.tick(8);

        for (int i = 0; i < 40; i++) {
            fixture.dma.tick(true, false);
        }
        assertEquals(0x40, fixture.oam.getByte(0xfe00));
        assertEquals(0xee, fixture.oam.getByte(0xfe01));

        fixture.dma.tick(false);
        fixture.tick(3);
        assertEquals(0x41, fixture.oam.getByte(0xfe01));
    }

    @Test
    public void speedSwitchReleasesOnlyAnOamDmaWhoseFinalByteWasCopied() {
        Fixture pendingFinalByte = new Fixture(true);
        pendingFinalByte.start();
        pendingFinalByte.tick(640);
        pendingFinalByte.dma.onSpeedSwitch();
        pendingFinalByte.dma.tick(true, false);

        assertTrue(pendingFinalByte.dma.isTransferInProgress());
        assertEquals(0xee, pendingFinalByte.oam.getByte(0xfe9f));

        Fixture copiedFinalByte = new Fixture(true);
        copiedFinalByte.start();
        copiedFinalByte.tick(644);
        copiedFinalByte.dma.onSpeedSwitch();

        assertFalse(copiedFinalByte.dma.isTransferInProgress());
        assertEquals(0xdf, copiedFinalByte.oam.getByte(0xfe9f));
        copiedFinalByte.dma.tick(true, false);
        assertTrue(copiedFinalByte.dma.ownedOamForPpuBeforeTick());
        assertFalse(copiedFinalByte.dma.ownsOamForPpu());
        assertTrue(copiedFinalByte.dma.hasPpuOamOwnershipTransitionThisTick());
    }

    @Test
    public void dmaRegisterPowersUpAsZeroOnCgb() {
        assertEquals(0x00, createDma(true).getByte(0xff46));
    }

    @Test
    public void dmaRegisterPowersUpAsFfOnDmg() {
        assertEquals(0xff, createDma(false).getByte(0xff46));
    }

    @Test
    public void dmgHighSourceRangeUsesEchoRamForTheCopy() {
        assertEquals(0xc000, DmaAddressSpace.mapAddress(0xe000, false));
        assertEquals(0xdf9f, DmaAddressSpace.mapAddress(0xff9f, false));
    }

    @Test
    public void cgbHighSourceRangeAliasesCartridgeRam() {
        assertEquals(0xa000, DmaAddressSpace.mapAddress(0xe000, true));
        assertEquals(0xbf9f, DmaAddressSpace.mapAddress(0xff9f, true));
    }

    @Test
    public void cgbHighSourceCopyReturnsOpenBusData() {
        Ram memory = new Ram(0, 0x10000);
        memory.setByte(0xa000, 0x42);

        DmaAddressSpace dmaMemory = new DmaAddressSpace(memory, true);

        assertEquals(0xff, dmaMemory.getByte(0xe000));
        assertEquals(0xff, dmaMemory.getByte(0xff9f));
    }

    private static Dma createDma(boolean gbc) {
        return new Dma(new Ram(0, 0x10000), new Ram(0xfe00, 0xa0), new SpeedMode(gbc));
    }

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Dma dma;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean gbc) {
            this(gbc, 1);
        }

        private Fixture(boolean gbc, int speed) {
            dma = new Dma(memory, oam, new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            });
            for (int i = 0; i < 0xa0; i++) {
                memory.setByte(0x1200 + i, 0x40 + i);
                oam.setByte(0xfe00 + i, 0xee);
            }
        }

        private void start() {
            dma.setByte(0xff46, 0x12);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                dma.tick();
            }
        }
    }
}
