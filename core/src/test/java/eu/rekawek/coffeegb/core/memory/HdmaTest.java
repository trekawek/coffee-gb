package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HdmaTest {

    @Test
    public void generalPurposeDmaIncludesStartupTicks() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        fixture.tick(37);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void generalPurposeDmaPaysStartupCostOnlyOnce() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x01);

        fixture.tick(38);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(31);
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(1);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
        assertEquals(0xbf, fixture.memory.getByte(0x801f));
    }

    @Test
    public void sourceBusSlotsAndSampledBlockSurviveMementoRestore() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        fixture.tick(7);
        assertEquals(new Hdma.SourceBusSample(0x1200, 0xa0),
                fixture.hdma.consumeSourceBusSample());
        assertEquals(0, fixture.memory.getByte(0x8000));
        var sampledBlock = fixture.hdma.saveToMemento();

        fixture.memory.setByte(0x1201, 0x55);
        fixture.tick(1);
        assertNull(fixture.hdma.consumeSourceBusSample());
        fixture.tick(1);
        assertEquals(new Hdma.SourceBusSample(0x1201, 0x55),
                fixture.hdma.consumeSourceBusSample());
        fixture.tick(29);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0x55, fixture.memory.getByte(0x8001));

        fixture.hdma.restoreFromMemento(sampledBlock);
        fixture.memory.setByte(0x8000, 0);
        fixture.memory.setByte(0x8001, 0);
        fixture.memory.setByte(0x1200, 0x66);
        fixture.memory.setByte(0x1201, 0x77);
        fixture.tick(31);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0x77, fixture.memory.getByte(0x8001));
    }

    @Test
    public void hblankDmaIncludesStartupTicksForEveryBurst() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);

        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);
        fixture.tick(35);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);
        fixture.tick(35);
        assertEquals(0, fixture.memory.getByte(0x8010));

        fixture.tick(1);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
        assertEquals(0xbf, fixture.memory.getByte(0x801f));
    }

    @Test
    public void hblankEdgeCanQueueNextBlockWhileCurrentBlockFinishes() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);

        fixture.tick(32);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        for (int i = 0; i < 3; i++) {
            fixture.hdma.tick();
            fixture.hdma.advanceHblankRequest();
        }
        assertEquals(0, fixture.memory.getByte(0x8000));

        assertTrue(fixture.hdma.tick());
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertTrue(fixture.hdma.isTransferInProgress());

        fixture.tick(36);
        assertEquals(0xb0, fixture.memory.getByte(0x8010));
    }

    @Test
    public void interruptEntryCanWinTheSynchronizedHblankRequestSlot() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);

        fixture.hdma.advanceHblankRequest(false, false, false);
        fixture.hdma.advanceHblankRequest(false, false, false);
        fixture.hdma.advanceHblankRequest(false, false, true);

        assertTrue(fixture.hdma.yieldsToInterruptEntry());
    }

    @Test
    public void fetchedInstructionOwnsARequestUntilItRetires() {
        Fixture fixture = synchronizedHblankRequest(1);

        assertTrue(fixture.hdma.yieldsToFetchedCpuInstruction(true));
        var cpuOwnedRequest = fixture.hdma.saveToMemento();
        assertTrue(fixture.hdma.yieldsToFetchedCpuInstruction(false));

        fixture.hdma.onFetchedCpuInstructionFinished();
        assertFalse(fixture.hdma.yieldsToFetchedCpuInstruction(true));

        fixture.hdma.restoreFromMemento(cpuOwnedRequest);
        assertTrue(fixture.hdma.yieldsToFetchedCpuInstruction(false));
    }

    @Test
    public void frameStartHblankRequestPreemptsAFetchedInstruction() {
        Fixture fixture = synchronizedHblankRequest(0);

        assertFalse(fixture.hdma.yieldsToFetchedCpuInstruction(true));
    }

    @Test
    public void generalDmaAtLineZeroStillLetsAFetchedInstructionRetire() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        assertTrue(fixture.hdma.yieldsToFetchedCpuInstruction(true));
    }

    @Test
    public void requestAssertedDuringStopOwnsTheWakeBoundary() {
        Fixture fixture = synchronizedHblankRequest(1);

        fixture.hdma.onStoppedCpuRequest();

        assertFalse(fixture.hdma.yieldsToFetchedCpuInstruction(true));
    }

    private Fixture synchronizedHblankRequest(int line) {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(line, 240);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);
        return fixture;
    }

    @Test
    public void haltWakeArbitrationWindowSurvivesMementoRestore() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(0, 100);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuTiming(0, 249);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onGpuTiming(0, 252);
        fixture.hdma.onCpuHaltState(false);
        var memento = fixture.hdma.saveToMemento();

        fixture.hdma.advanceHblankRequest(false, false, true);
        assertTrue(fixture.hdma.yieldsToInterruptEntry());

        fixture.hdma.restoreFromMemento(memento);
        fixture.hdma.advanceHblankRequest(false, false, true);
        assertTrue(fixture.hdma.yieldsToInterruptEntry());
    }

    @Test
    public void doubleSpeedHblankDmaUsesThreeSchedulerStartupTicks() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);

        fixture.tick(34);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
    }

    @Test
    public void haltEnteredWhileHblankRequestIsHighDoesNotCreateRequestOnWake() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);
        fixture.tick(36);
        assertEquals(0x00, fixture.hdma.getByte(0xff55));

        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onGpuTiming(1, 0);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onCpuHaltState(false);

        assertEquals(0x00, fixture.hdma.getByte(0xff55));
        fixture.tick(40);
        assertEquals(0, fixture.memory.getByte(0x8010));
    }

    @Test
    public void haltPreservesAlreadyLatchedHblankRequest() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);

        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onCpuHaltState(false);

        fixture.tick(36);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
    }

    @Test
    public void haltPreservesRequestCrossingOnTheSameTick() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(2);

        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onCpuHaltState(false);

        fixture.tick(36);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
    }

    @Test
    public void hblankEdgeOnHaltEntryIsRememberedAsRequested() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);

        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onGpuTiming(0, 250);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onCpuHaltState(false);

        fixture.tick(36);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
    }

    @Test
    public void normalSpeedHaltSamplesTheLateHblankRequestLevel() {
        assertFalse(blockRestartsAfterLateHblankHalt(1, 448));
        assertTrue(blockRestartsAfterLateHblankHalt(1, 449));
    }

    @Test
    public void doubleSpeedHaltSamplesTheLaterHblankRequestLevel() {
        assertFalse(blockRestartsAfterLateHblankHalt(2, 451));
        assertTrue(blockRestartsAfterLateHblankHalt(2, 452));
    }

    private boolean blockRestartsAfterLateHblankHalt(int speed, int haltTick) {
        Fixture fixture = new Fixture(speed);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 200);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);

        fixture.hdma.onGpuTiming(1, haltTick);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuTiming(2, 250);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onCpuHaltState(false);
        fixture.tick(40);

        return fixture.memory.getByte(0x8000) == 0xa0;
    }

    @Test
    public void disableFromGrantedCpuCycleRetractsHblankRequest() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.advanceHblankRequest(true);
        fixture.hdma.advanceHblankRequest(true);
        fixture.hdma.advanceHblankRequest(true);

        fixture.hdma.setByte(0xff55, 0x00);
        fixture.tick(40);

        assertEquals(0, fixture.memory.getByte(0x8000));
        assertEquals(0x80, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void disableAfterDmaWinsArbitrationKeepsCurrentBurst() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);

        fixture.hdma.setByte(0xff55, 0x00);
        fixture.tick(36);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void switchingLcdOffReleasesOnePendingHblankBurst() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);

        fixture.hdma.onLcdSwitch(false);
        fixture.tick(36);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
        assertEquals(0, fixture.memory.getByte(0x8010));
        assertEquals(0x00, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void normalToDoubleSpeedSwitchPreservesLengthAfterGrantedBurst() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(6);

        assertTrue(fixture.hdma.onSpeedSwitch());
        fixture.tick(40);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0, fixture.memory.getByte(0x8010));
        assertEquals(0x81, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void grantedSpeedSwitchBurstOwnsOamDmaClockUntilCompletionAndSurvivesMemento() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x81);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(6);

        assertTrue(fixture.hdma.onSpeedSwitch());
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
        var grantedBurst = fixture.hdma.saveToMemento();

        fixture.tick(10);
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());

        fixture.hdma.restoreFromMemento(grantedBurst);
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
        for (int i = 0; i < 34; i++) {
            assertFalse(fixture.hdma.tick());
            assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
        }

        assertTrue(fixture.hdma.tick());
        assertFalse(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
    }

    @Test
    public void speedSwitchDropsARequestThatHasNotWonArbitration() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);

        assertFalse(fixture.hdma.onSpeedSwitch());
        fixture.tick(40);

        assertEquals(0, fixture.memory.getByte(0x8000));
        assertEquals(0x00, fixture.hdma.getByte(0xff55));

        fixture.hdma.onSpeedSwitchComplete();
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);
        fixture.tick(36);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void speedSwitchResamplesCurrentHblankWhenClockResumes() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);

        fixture.hdma.onSpeedSwitch();
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);
        fixture.tick(36);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.hdma.onSpeedSwitchComplete();
        fixture.advanceHblankRequest(3);
        fixture.tick(36);

        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    @Test
    public void firstHblankAfterSpeedSwitchUsesTheRephasedArbiter() {
        Fixture preempted = speedSwitchWaitingForNextHblank();
        preempted.hdma.advanceHblankRequest(false, true);
        preempted.hdma.advanceHblankRequest(false, true);
        preempted.hdma.advanceHblankRequest(false, true);
        assertTrue(preempted.hdma.preemptsCpuInstructionForSpeedSwitchWake());
        assertFalse(preempted.hdma.yieldsSpeedSwitchWakeRequestToCpu());

        Fixture yielded = speedSwitchWaitingForNextHblank();
        yielded.hdma.advanceHblankRequest(false, false);
        yielded.hdma.advanceHblankRequest(false, false);
        yielded.hdma.advanceHblankRequest(false, false);
        assertFalse(yielded.hdma.preemptsCpuInstructionForSpeedSwitchWake());
        assertTrue(yielded.hdma.yieldsSpeedSwitchWakeRequestToCpu());
        yielded.hdma.onSpeedSwitchWakeCpuInstructionFinished();
        assertFalse(yielded.hdma.yieldsSpeedSwitchWakeRequestToCpu());
    }

    private Fixture speedSwitchWaitingForNextHblank() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(0, 120);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        assertFalse(fixture.hdma.onSpeedSwitch());
        fixture.hdma.onSpeedSwitchComplete();
        fixture.hdma.onGpuTiming(0, 248);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        return fixture;
    }

    @Test
    public void highSourceRangeAliasesCartridgeRam() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 0x10; i++) {
            fixture.memory.setByte(0xa000 + i, 0x60 + i);
        }
        fixture.hdma.setByte(0xff51, 0xe0);
        fixture.startTransfer(0x00);

        fixture.tick(38);

        assertEquals(0x60, fixture.memory.getByte(0x8000));
        assertEquals(0x6f, fixture.memory.getByte(0x800f));
    }

    @Test
    public void vramSourceRangeStartsWithCpuBusResidue() {
        Fixture fixture = new Fixture();
        fixture.hdma.setByte(0xff51, 0x80);
        fixture.hdma.setCpuBusValue(0xa5);
        fixture.startTransfer(0x00);

        fixture.tick(38);

        assertEquals(0xa5, fixture.memory.getByte(0x8000));
        assertEquals(0xa5, fixture.memory.getByte(0x8001));
        assertEquals(0xff, fixture.memory.getByte(0x8002));
        assertEquals(0xff, fixture.memory.getByte(0x800f));
    }

    @Test
    public void destinationCounterWrapsThroughVramWithoutLosingHighBits() {
        Fixture fixture = new Fixture();
        fixture.hdma.setByte(0xff53, 0xdf);
        fixture.hdma.setByte(0xff54, 0xf0);
        fixture.startTransfer(0x01);

        fixture.tick(70);

        assertEquals(0xa0, fixture.memory.getByte(0x9ff0));
        assertEquals(0xb0, fixture.memory.getByte(0x8000));
    }

    @Test
    public void destinationCounterStopsAtAddressSpaceEnd() {
        Fixture fixture = new Fixture();
        fixture.hdma.setByte(0xff53, 0xff);
        fixture.hdma.setByte(0xff54, 0xf0);
        fixture.startTransfer(0x01);

        fixture.tick(70);

        assertEquals(0xa0, fixture.memory.getByte(0x9ff0));
        assertEquals(0, fixture.memory.getByte(0x8000));
        assertEquals(0xff, fixture.hdma.getByte(0xff55));
    }

    private static class Fixture {

        private final Ram memory = new Ram(0, 0x10000);

        private final Hdma hdma;

        private Fixture() {
            this(1);
        }

        private Fixture(int speed) {
            SpeedMode speedMode = new SpeedMode(true) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            hdma = new Hdma(memory, speedMode);
            for (int i = 0; i < 0x20; i++) {
                memory.setByte(0x1200 + i, 0xa0 + i);
            }
            hdma.setByte(0xff51, 0x12);
            hdma.setByte(0xff52, 0x00);
            hdma.setByte(0xff53, 0x00);
            hdma.setByte(0xff54, 0x00);
        }

        private void startTransfer(int control) {
            hdma.setByte(0xff55, control);
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                hdma.tick();
            }
        }

        private void advanceHblankRequest(int count) {
            for (int i = 0; i < count; i++) {
                hdma.advanceHblankRequest();
            }
        }
    }
}
