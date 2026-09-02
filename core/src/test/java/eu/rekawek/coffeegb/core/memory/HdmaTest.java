package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
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
    public void inactiveCompletedGdmaRequestClockBulkMatchesScalarAcrossPpuModes() {
        Fixture scalar = new Fixture();
        Fixture bulk = new Fixture();
        scalar.startTransfer(0x00);
        bulk.startTransfer(0x00);
        scalar.tick(38);
        bulk.tick(38);
        scalar.hdma.onGpuUpdate(Mode.PixelTransfer);
        bulk.hdma.onGpuUpdate(Mode.PixelTransfer);

        Hdma.HdmaState completed = hdmaState(bulk);
        assertEquals(0, completed.hblankRequestTicks());
        assertFalse(completed.transferInProgress());
        assertTrue(bulk.hdma.isPerformanceInactiveRequestClockStable());
        assertFalse("mode-specific wrapper crossed a PPU mode boundary",
                bulk.hdma.isPerformanceOamSearchPhaseClockStable());

        scalar.advanceHblankRequest(17);
        bulk.hdma.advancePerformanceInactiveRequestClockTrusted(17);
        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals(17, hdmaState(bulk).hblankRequestAge());

        bulk.hdma.onGpuUpdate(Mode.OamSearch);
        assertTrue(bulk.hdma.isPerformanceOamSearchPhaseClockStable());
    }

    @Test
    public void inactiveRequestClockBulkPreservesScalarHaltAsymmetry() {
        Fixture scalar = new Fixture();
        Fixture bulk = new Fixture();
        scalar.startTransfer(0x00);
        bulk.startTransfer(0x00);
        scalar.tick(38);
        bulk.tick(38);
        scalar.hdma.onCpuHaltState(true);
        bulk.hdma.onCpuHaltState(true);

        scalar.advanceHblankRequest(11);
        bulk.hdma.advancePerformanceInactiveRequestClockTrusted(11);
        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals("halted current request clock must not age", 0,
                hdmaState(bulk).hblankRequestAge());
    }

    @Test
    public void nativeCgbEpochMayBorrowOnlyTheStableWaitBetweenHblankBursts() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 0);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.startTransfer(0x81);

        assertTrue(fixture.hdma.isPerformanceArmedHblankWaitStable());
        assertTrue(fixture.hdma.isPerformanceNativeCgbRunningEpochStable());
        Hdma.HdmaState initialWait = hdmaState(fixture);
        fixture.hdma.advancePerformanceNativeCgbRunningEpochClockTrusted(54);
        assertSameHdmaState(initialWait, hdmaState(fixture));

        fixture.hdma.onGpuTiming(1, 248);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        assertEquals(3, hdmaState(fixture).hblankRequestTicks());
        assertFalse(fixture.hdma.isPerformanceNativeCgbRunningEpochStable());
        fixture.advanceHblankRequest(1);
        assertEquals(2, hdmaState(fixture).hblankRequestTicks());
        fixture.advanceHblankRequest(1);
        assertEquals(1, hdmaState(fixture).hblankRequestTicks());
        fixture.advanceHblankRequest(1);
        assertEquals(0, hdmaState(fixture).hblankRequestTicks());
        assertFalse(fixture.hdma.isPerformanceArmedHblankWaitStable());

        fixture.hdma.resolveCpuRequest(false, false);
        int guard = 0;
        while (!fixture.hdma.tick() && guard++ < 64) {
            // Complete exactly one 16-byte burst.
        }
        assertTrue("first HBlank burst did not complete", guard < 64);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertTrue("second block was not left armed",
                fixture.hdma.hasPendingHblankTransfer());
        assertTrue("post-burst wait did not reopen the native x2 lease",
                fixture.hdma.isPerformanceArmedHblankWaitStable());

        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        assertTrue("armed wait should span OAM search",
                fixture.hdma.isPerformanceArmedHblankWaitStable());
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        assertTrue("armed wait should span pixel transfer",
                fixture.hdma.isPerformanceArmedHblankWaitStable());
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        assertEquals(3, hdmaState(fixture).hblankRequestTicks());
        assertFalse("next HBlank edge must close the lease",
                fixture.hdma.isPerformanceNativeCgbRunningEpochStable());
    }

    @Test
    public void nativeCgbOwnedWramDataInteriorMatchesScalarAndLeavesCommitScalar() {
        Fixture scalar = ownedHblankDataFixture(0xc800);
        Fixture bulk = ownedHblankDataFixture(0xc800);

        int span = bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(64);
        assertEquals(31, span);
        assertEquals("a partial budget must leave the whole interior scalar", 0,
                bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(span - 1));
        for (int i = 0; i < span; i++) {
            assertFalse(scalar.hdma.tick());
            scalar.hdma.consumeSourceBusSample();
            scalar.hdma.advanceHblankRequest();
        }
        bulk.hdma.advancePerformanceNativeCgbOwnedHblankDataTrusted(span);

        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals(scalar.hdma.getPpuBusGeneration(), bulk.hdma.getPpuBusGeneration());
        assertEquals("tick-32 destination publication must not be folded into the packet", 0,
                bulk.memory.getByte(0x8000));
        assertEquals(0, bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(1));

        assertTrue(scalar.hdma.tick());
        scalar.hdma.consumeSourceBusSample();
        scalar.hdma.advanceHblankRequest();
        assertTrue(bulk.hdma.tick());
        bulk.hdma.consumeSourceBusSample();
        bulk.hdma.advanceHblankRequest();

        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals(scalar.hdma.getPpuBusGeneration(), bulk.hdma.getPpuBusGeneration());
        for (int i = 0; i < 0x10; i++) {
            assertEquals(0x40 + i, bulk.memory.getByte(0x8000 + i));
        }
    }

    @Test
    public void nativeCgbOwnedRomDataInteriorMatchesScalarThroughPhysicalLease() {
        Fixture scalar = ownedHblankDataFixture(0x4000);
        Fixture bulk = ownedHblankDataFixture(0x4000);
        int[] physicalReads = {0};
        PerformanceRomAccess physicalRom = new PerformanceRomAccess() {
            @Override
            public int physicalOffset(int cpuAddress) {
                return cpuAddress >= 0 && cpuAddress < 0x8000 ? cpuAddress : -1;
            }

            @Override
            public int readPhysicalByte(int physicalOffset) {
                assertEquals("physical ROM reads lost their source-slot order",
                        0x4000 + physicalReads[0]++, physicalOffset);
                return bulk.memory.getByte(physicalOffset);
            }
        };

        int span = bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(64, physicalRom);
        assertEquals(31, span);
        assertEquals("ROM must not enter the lease-free WRAM tier", 0,
                bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(64));
        for (int i = 0; i < span; i++) {
            assertFalse(scalar.hdma.tick());
            scalar.hdma.consumeSourceBusSample();
            scalar.hdma.advanceHblankRequest();
        }
        bulk.hdma.advancePerformanceNativeCgbOwnedHblankDataTrusted(span, physicalRom);

        assertEquals("the complete ROM block was not sampled exactly once", 0x10,
                physicalReads[0]);
        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals(scalar.hdma.getPpuBusGeneration(), bulk.hdma.getPpuBusGeneration());
        assertEquals("tick-32 destination publication must remain scalar", 0,
                bulk.memory.getByte(0x8000));
    }

    @Test
    public void nativeCgbOwnedRomDataRequiresAllSixteenImmutablePhysicalOffsets() {
        Fixture fixture = ownedHblankDataFixture(0x7ff0);
        PerformanceRomAccess completePhysicalRom = physicalRomAccess(fixture.memory, -1);
        PerformanceRomAccess physicalRomWithHole = physicalRomAccess(fixture.memory, 0x7ff8);
        PerformanceRomAccess logicalOnlyRom = new PerformanceRomAccess() {
            @Override
            public int physicalOffset(int cpuAddress) {
                return -1;
            }

            @Override
            public int readPhysicalByte(int physicalOffset) {
                throw new AssertionError("logical-only ROM must remain scalar");
            }

            @Override
            public int readCpuByte(int cpuAddress) {
                return fixture.memory.getByte(cpuAddress);
            }
        };

        assertTrue(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStructurallyStable());
        assertTrue(fixture.hdma.requiresPerformanceNativeCgbOwnedHblankRomAccess());
        assertFalse(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStable());
        assertTrue(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStable(completePhysicalRom));
        assertFalse(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStable(null));
        assertFalse(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStable(logicalOnlyRom));
        assertFalse(fixture.hdma.isPerformanceNativeCgbOwnedHblankDataStable(physicalRomWithHole));
        assertEquals(0,
                fixture.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(31, logicalOnlyRom));
        assertEquals(0,
                fixture.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(
                        31, physicalRomWithHole));
    }

    @Test
    public void nativeCgbOwnedDataInteriorAdmitsOnlyAlignedWramOrRomSourceBlocks() {
        assertTrue(ownedHblankDataFixture(0xc000).hdma
                .isPerformanceNativeCgbOwnedHblankDataStable());
        assertTrue(ownedHblankDataFixture(0xdff0).hdma
                .isPerformanceNativeCgbOwnedHblankDataStable());
        assertTrue(ownedHblankDataFixture(0x0000).hdma
                .isPerformanceNativeCgbOwnedHblankDataStructurallyStable());
        assertTrue(ownedHblankDataFixture(0x7ff0).hdma
                .isPerformanceNativeCgbOwnedHblankDataStructurallyStable());
        assertFalse(ownedHblankDataFixture(0xbff0).hdma
                .isPerformanceNativeCgbOwnedHblankDataStructurallyStable());
        assertFalse(ownedHblankDataFixture(0xe000).hdma
                .isPerformanceNativeCgbOwnedHblankDataStructurallyStable());
    }

    @Test
    public void nativeCgbOwnedWramDataInteriorMatchesScalarFromEveryDataTick() {
        for (int scalarPrefix = 0; scalarPrefix < 31; scalarPrefix++) {
            Fixture scalar = ownedHblankDataFixture(0xcf00);
            Fixture bulk = ownedHblankDataFixture(0xcf00);
            for (int i = 0; i < scalarPrefix; i++) {
                assertFalse(scalar.hdma.tick());
                scalar.hdma.consumeSourceBusSample();
                scalar.hdma.advanceHblankRequest();
                assertFalse(bulk.hdma.tick());
                bulk.hdma.consumeSourceBusSample();
                bulk.hdma.advanceHblankRequest();
            }

            int span = 31 - scalarPrefix;
            for (int i = 0; i < span; i++) {
                assertFalse(scalar.hdma.tick());
                scalar.hdma.consumeSourceBusSample();
                scalar.hdma.advanceHblankRequest();
            }
            assertEquals(span,
                    bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(span));
            bulk.hdma.advancePerformanceNativeCgbOwnedHblankDataTrusted(span);

            assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
            assertEquals(scalar.hdma.getPpuBusGeneration(), bulk.hdma.getPpuBusGeneration());
        }
    }

    @Test
    public void nativeCgbOwnedPhysicalRomDataInteriorMatchesScalarFromEveryDataTick() {
        for (int scalarPrefix = 0; scalarPrefix < 31; scalarPrefix++) {
            Fixture scalar = ownedHblankDataFixture(0x4000);
            Fixture bulk = ownedHblankDataFixture(0x4000);
            PerformanceRomAccess physicalRom = physicalRomAccess(bulk.memory, -1);
            for (int i = 0; i < scalarPrefix; i++) {
                assertFalse(scalar.hdma.tick());
                scalar.hdma.consumeSourceBusSample();
                scalar.hdma.advanceHblankRequest();
                assertFalse(bulk.hdma.tick());
                bulk.hdma.consumeSourceBusSample();
                bulk.hdma.advanceHblankRequest();
            }

            int span = 31 - scalarPrefix;
            for (int i = 0; i < span; i++) {
                assertFalse(scalar.hdma.tick());
                scalar.hdma.consumeSourceBusSample();
                scalar.hdma.advanceHblankRequest();
            }
            assertEquals(span,
                    bulk.hdma.performanceNativeCgbOwnedHblankDataSpanLimit(
                            span, physicalRom));
            bulk.hdma.advancePerformanceNativeCgbOwnedHblankDataTrusted(
                    span, physicalRom);

            assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
            assertEquals(scalar.hdma.getPpuBusGeneration(), bulk.hdma.getPpuBusGeneration());
        }
    }

    @Test
    public void restoredArmedHblankWaitRetainsTheNativeCgbEpochLease() {
        Fixture original = new Fixture(2);
        original.hdma.onLcdSwitch(true);
        original.hdma.onGpuTiming(7, 120);
        original.hdma.onGpuUpdate(Mode.PixelTransfer);
        original.startTransfer(0x82);
        assertTrue(original.hdma.isPerformanceArmedHblankWaitStable());

        Fixture restored = new Fixture(2);
        restored.hdma.restoreState(original.hdma.captureState());

        assertTrue(restored.hdma.isPerformanceArmedHblankWaitStable());
        restored.hdma.advancePerformanceNativeCgbRunningEpochClockTrusted(31);
        assertSameHdmaState(hdmaState(original), hdmaState(restored));
    }

    @Test
    public void normalSpeedArmedHblankWaitRemainsOutsideTheNativeCgbEpochLease() {
        Fixture fixture = new Fixture(1);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(3, 120);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x81);

        assertTrue(fixture.hdma.hasPendingHblankTransfer());
        assertFalse(fixture.hdma.isPerformanceArmedHblankWaitStable());
        assertFalse(fixture.hdma.isPerformanceNativeCgbRunningEpochStable());
    }

    @Test
    public void runningEpochHaltReconciliationExcludesOnlyTheTerminalCurrentAge() {
        Fixture scalar = new Fixture();
        Fixture bulk = new Fixture();
        Hdma.HdmaState clocks = withRequestClockState(
                hdmaState(scalar), 0, 40, 0, 70);
        scalar.hdma.restoreState(clocks);
        bulk.hdma.restoreState(clocks);

        for (int tick = 0; tick < 4; tick++) {
            scalar.advanceHblankRequest(1);
        }
        scalar.hdma.onCpuHaltState(true);
        scalar.advanceHblankRequest(1);

        bulk.hdma.advancePerformanceInactiveRequestClockTrusted(5);
        bulk.hdma.reconcilePerformanceRunningEpochHaltEntryTrusted();
        bulk.hdma.onCpuHaltState(true);

        assertSameHdmaState(hdmaState(scalar), hdmaState(bulk));
        assertEquals("terminal HALT dot must not age the current request", 44,
                hdmaState(bulk).hblankRequestAge());
        assertEquals("terminal HALT dot must still age the next request", 75,
                hdmaState(bulk).nextHblankRequestAge());
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
    public void backToBackDoubleSpeedGeneralDmaRetainsTwoSequencerTicks() {
        Fixture fixture = new Fixture(2);
        fixture.startTransfer(0x00);
        fixture.tick(34);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));

        fixture.startTransfer(0x00);
        fixture.tick(35);
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
        var sampledBlock = fixture.hdma.captureState();

        fixture.memory.setByte(0x1201, 0x55);
        fixture.tick(1);
        assertNull(fixture.hdma.consumeSourceBusSample());
        fixture.tick(1);
        assertEquals(new Hdma.SourceBusSample(0x1201, 0x55),
                fixture.hdma.consumeSourceBusSample());
        fixture.tick(29);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0x55, fixture.memory.getByte(0x8001));

        fixture.hdma.restoreState(sampledBlock);
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

        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());
    }

    @Test
    public void interruptOwnedRequestRetainsItsIndependentLatchAfterStateRestore() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 240);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.advanceHblankRequest(false, false, false);
        fixture.hdma.advanceHblankRequest(false, false, false);
        fixture.hdma.advanceHblankRequest(false, false, true);
        var interruptOwnedRequest = fixture.hdma.captureState();

        fixture.hdma.restoreState(interruptOwnedRequest);
        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());
        assertTrue(fixture.hdma.isCpuRequestUnresolved());
        fixture.hdma.resolveCpuRequest(true, false);

        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());
        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());
    }

    @Test
    public void cpuRequestStateRestoreMatchesLegacyMappingForAllFourLatchInputs() {
        for (Hdma.HdmaState arbitrationState : cpuRequestArbitrationStates()) {
            String arbitration = String.valueOf(arbitrationState.cpuRequestArbitration());
            for (int flags = 0; flags < 4; flags++) {
                boolean interruptOwner = (flags & 1) != 0;
                boolean lateInterrupt = (flags & 2) != 0;
                var state = withCpuRequestFlags(arbitrationState, interruptOwner, lateInterrupt);

                Fixture restored = new Fixture();
                restored.hdma.restoreState(state);

                assertLegacyCpuRequestState(arbitration + "/" + flags, restored,
                        LegacyCpuRequestState.restored(arbitration,
                                interruptOwner, lateInterrupt));
            }
        }
    }

    @Test
    public void cpuRequestTransitionsMatchTheTwoIndependentLegacyLatchesExhaustively() {
        for (Hdma.HdmaState arbitrationState : cpuRequestArbitrationStates()) {
            String arbitration = String.valueOf(arbitrationState.cpuRequestArbitration());
            for (int flags = 0; flags < 4; flags++) {
                boolean interruptOwner = (flags & 1) != 0;
                boolean lateInterrupt = (flags & 2) != 0;
                var state = withCpuRequestFlags(arbitrationState, interruptOwner, lateInterrupt);
                var legacy = LegacyCpuRequestState.restored(arbitration,
                        interruptOwner, lateInterrupt);

                assertCpuRequestTransition("resolve CPU/open", state,
                        legacy.resolve(true, false), h -> h.resolveCpuRequest(true, false));
                assertCpuRequestTransition("resolve CPU/pending", state,
                        legacy.resolve(true, true), h -> h.resolveCpuRequest(true, true));
                assertCpuRequestTransition("resolve DMA", state,
                        legacy.resolve(false, false), h -> h.resolveCpuRequest(false, false));
                assertCpuRequestTransition("retire CPU slot", state,
                        legacy.retireCpuSlot(), Hdma::onCpuRequestSlotRetired);
                assertCpuRequestTransition("accept interrupt", state,
                        legacy.acceptInterrupt(), Hdma::onInterruptEntryAcceptedByCpu);
                assertCpuRequestTransition("STOP request", state,
                        legacy.stopRequest(), Hdma::onStoppedCpuRequest);
            }
        }
    }

    @Test
    public void cpuHdmaPhaseFlagsAreSampledOnlyAtObservableRequestEdges() {
        Fixture inactive = new Fixture();
        assertFalse(inactive.hdma.requiresCpuHdmaPhaseFlags());

        Fixture countdown = hblankRequestCountdown();
        assertFalse(countdown.hdma.requiresCpuHdmaPhaseFlags());
        countdown.advanceHblankRequest(2);
        assertTrue(countdown.hdma.requiresCpuHdmaPhaseFlags());
        countdown.hdma.advanceHblankRequest(true, true, true);
        var capturedEdge = (Hdma.HdmaState) countdown.hdma.captureState();
        assertTrue(capturedEdge.requestOverlappedCpuWrite());
        assertTrue(capturedEdge.interruptEntryWonArbitration());

        Fixture unresolved = hblankRequestCountdown();
        unresolved.advanceHblankRequest(3);
        assertTrue(unresolved.hdma.requiresCpuHdmaPhaseFlags());
        unresolved.hdma.advanceHblankRequest(false, false, true);
        assertTrue(unresolved.hdma.isInterruptEntryRequestOwner());

        Fixture aged = hblankRequestCountdown();
        aged.advanceHblankRequest(4);
        assertFalse(aged.hdma.requiresCpuHdmaPhaseFlags());

        Fixture outsideWindow = hblankRequestCountdown();
        outsideWindow.advanceHblankRequest(3);
        outsideWindow.hdma.onGpuTiming(1, 252);
        assertFalse(outsideWindow.hdma.requiresCpuHdmaPhaseFlags());

        Fixture halted = hblankRequestCountdown();
        halted.advanceHblankRequest(2);
        halted.hdma.onCpuHaltState(true);
        assertFalse(halted.hdma.requiresCpuHdmaPhaseFlags());
    }

    @Test
    public void cpuInstructionPhaseIsCapturedAtCountdownEdgeAfterSpeedSwitch() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(0, 120);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        assertFalse(fixture.hdma.onSpeedSwitch());
        fixture.hdma.onGpuTiming(0, 448);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onSpeedSwitchComplete();

        fixture.advanceHblankRequest(2);
        assertTrue(fixture.hdma.requiresCpuHdmaPhaseFlags());
        fixture.hdma.advanceHblankRequest(false, true, false);
        assertTrue(fixture.hdma.preemptsCpuInstructionForSpeedSwitchWake());
    }

    @Test
    public void omittedCpuHdmaPhaseFlagsDoNotChangeAnyNonObservableRequestState() {
        assertIgnoredCpuPhaseFlags(hdmaState(new Fixture()));

        Fixture countdown = hblankRequestCountdown();
        assertIgnoredCpuPhaseFlags(hdmaState(countdown));

        Fixture finalCountdownTick = hblankRequestCountdown();
        finalCountdownTick.advanceHblankRequest(1);
        assertIgnoredCpuPhaseFlags(hdmaState(finalCountdownTick));

        Fixture aged = hblankRequestCountdown();
        aged.advanceHblankRequest(4);
        assertIgnoredCpuPhaseFlags(hdmaState(aged));

        Fixture queuedNextRequest = hblankRequestCountdown(0x81);
        queuedNextRequest.advanceHblankRequest(4);
        queuedNextRequest.hdma.onGpuUpdate(Mode.HBlank);
        assertTrue(hdmaState(queuedNextRequest).nextHblankRequestTicks() > 0);
        assertFalse(queuedNextRequest.hdma.requiresCpuHdmaPhaseFlags());
        assertIgnoredCpuPhaseFlags(hdmaState(queuedNextRequest));

        Fixture outsideWindow = hblankRequestCountdown();
        outsideWindow.advanceHblankRequest(3);
        outsideWindow.hdma.onGpuTiming(1, 252);
        assertIgnoredCpuPhaseFlags(hdmaState(outsideWindow));

        Fixture halted = hblankRequestCountdown();
        halted.advanceHblankRequest(2);
        halted.hdma.onCpuHaltState(true);
        assertIgnoredCpuPhaseFlags(hdmaState(halted));
    }

    @Test
    public void fetchedInstructionOwnsARequestUntilItRetires() {
        Fixture fixture = synchronizedHblankRequest(1);

        fixture.hdma.resolveCpuRequest(true, false);
        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());
        var cpuOwnedRequest = fixture.hdma.captureState();
        fixture.hdma.resolveCpuRequest(false, false);
        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());

        fixture.hdma.onCpuRequestSlotRetired();
        fixture.hdma.resolveCpuRequest(true, false);
        assertFalse(fixture.hdma.isCpuInstructionRequestOwner());

        fixture.hdma.restoreState(cpuOwnedRequest);
        fixture.hdma.resolveCpuRequest(false, false);
        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());
        fixture.hdma.onInterruptEntryAcceptedByCpu();
        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());
    }

    @Test
    public void reportsTheCpuEdgeImmediatelyBeforeHblankArbitration() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);

        fixture.advanceHblankRequest(1);
        assertFalse(fixture.hdma.isHblankRequestArrivingAfterCpuTick());
        fixture.advanceHblankRequest(1);
        assertTrue(fixture.hdma.isHblankRequestArrivingAfterCpuTick());
        fixture.advanceHblankRequest(1);
        assertFalse(fixture.hdma.isHblankRequestArrivingAfterCpuTick());
    }

    @Test
    public void interruptPendingWhenCpuClaimsDoesNotSupersedeDma() {
        Fixture fixture = synchronizedHblankRequest(1);
        var unresolvedRequest = fixture.hdma.captureState();

        fixture.hdma.resolveCpuRequest(true, true);
        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());
        var preexistingInterrupt = fixture.hdma.captureState();

        fixture.hdma.restoreState(unresolvedRequest);
        fixture.hdma.resolveCpuRequest(true, false);
        fixture.hdma.restoreState(preexistingInterrupt);
        fixture.hdma.onInterruptEntryAcceptedByCpu();

        assertFalse(fixture.hdma.isCpuInstructionRequestOwner());
        assertFalse(fixture.hdma.isInterruptEntryRequestOwner());
    }

    @Test
    public void frameStartHblankRequestPreemptsAFetchedInstruction() {
        Fixture fixture = synchronizedHblankRequest(0);

        fixture.hdma.resolveCpuRequest(true, false);
        assertFalse(fixture.hdma.isCpuInstructionRequestOwner());
    }

    @Test
    public void generalDmaAtLineZeroStillLetsAFetchedInstructionRetire() {
        Fixture fixture = new Fixture();
        fixture.startTransfer(0x00);

        fixture.hdma.resolveCpuRequest(true, false);
        assertTrue(fixture.hdma.isCpuInstructionRequestOwner());
    }

    @Test
    public void requestAssertedDuringStopOwnsTheWakeBoundary() {
        Fixture fixture = synchronizedHblankRequest(1);

        fixture.hdma.onStoppedCpuRequest();

        fixture.hdma.resolveCpuRequest(true, false);
        assertFalse(fixture.hdma.isCpuInstructionRequestOwner());
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

    private Fixture hblankRequestCountdown() {
        return hblankRequestCountdown(0x80);
    }

    private Fixture hblankRequestCountdown(int control) {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 240);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(control);
        fixture.hdma.onGpuTiming(1, 248);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onGpuTiming(1, 249);
        return fixture;
    }

    private Fixture ownedHblankDataFixture(int source) {
        Fixture fixture = new Fixture(2);
        for (int i = 0; i < 0x10; i++) {
            fixture.memory.setByte((source + i) & 0xffff, 0x40 + i);
        }
        fixture.hdma.setByte(0xff51, source >>> 8);
        fixture.hdma.setByte(0xff52, source & 0xf0);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 248);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.startTransfer(0x80);
        fixture.hdma.resolveCpuRequest(false, false);
        while (hdmaState(fixture).tick() < 0) {
            assertFalse(fixture.hdma.tick());
            fixture.hdma.consumeSourceBusSample();
            fixture.hdma.advanceHblankRequest();
        }
        return fixture;
    }

    private PerformanceRomAccess physicalRomAccess(Ram memory, int missingCpuAddress) {
        return new PerformanceRomAccess() {
            @Override
            public int physicalOffset(int cpuAddress) {
                return cpuAddress >= 0 && cpuAddress < 0x8000
                        && cpuAddress != missingCpuAddress ? cpuAddress : -1;
            }

            @Override
            public int readPhysicalByte(int physicalOffset) {
                return memory.getByte(physicalOffset);
            }
        };
    }

    private Hdma.HdmaState hdmaState(Fixture fixture) {
        return (Hdma.HdmaState) fixture.hdma.captureState();
    }

    private Hdma.HdmaState[] cpuRequestArbitrationStates() {
        Fixture none = new Fixture();
        Fixture unresolved = synchronizedHblankRequest(1);
        Fixture cpu = synchronizedHblankRequest(1);
        cpu.hdma.resolveCpuRequest(true, false);
        Fixture dma = synchronizedHblankRequest(1);
        dma.hdma.resolveCpuRequest(false, false);
        return new Hdma.HdmaState[]{hdmaState(none), hdmaState(unresolved),
                hdmaState(cpu), hdmaState(dma)};
    }

    private Hdma.HdmaState withCpuRequestFlags(Hdma.HdmaState state,
                                                boolean interruptOwner,
                                                boolean lateInterrupt) {
        try {
            RecordComponent[] components = Hdma.HdmaState.class.getRecordComponents();
            Class<?>[] parameterTypes = Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = switch (components[i].getName()) {
                    case "interruptEntryWonArbitration" -> interruptOwner;
                    case "cpuRequestAllowsLateInterrupt" -> lateInterrupt;
                    default -> components[i].getAccessor().invoke(state);
                };
            }
            var constructor = Hdma.HdmaState.class.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return (Hdma.HdmaState) constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Hdma.HdmaState withRequestClockState(
            Hdma.HdmaState state, int currentTicks, int currentAge,
            int nextTicks, int nextAge) {
        try {
            RecordComponent[] components = Hdma.HdmaState.class.getRecordComponents();
            Class<?>[] parameterTypes = Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = switch (components[i].getName()) {
                    case "hblankRequestTicks" -> currentTicks;
                    case "hblankRequestAge" -> currentAge;
                    case "nextHblankRequestTicks" -> nextTicks;
                    case "nextHblankRequestAge" -> nextAge;
                    default -> components[i].getAccessor().invoke(state);
                };
            }
            var constructor = Hdma.HdmaState.class.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return (Hdma.HdmaState) constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void assertCpuRequestTransition(String transition,
                                            Hdma.HdmaState initialState,
                                            LegacyCpuRequestState expected,
                                            Consumer<Hdma> operation) {
        Fixture fixture = new Fixture();
        fixture.hdma.restoreState(initialState);
        operation.accept(fixture.hdma);
        assertLegacyCpuRequestState(transition + " from "
                + initialState.cpuRequestArbitration(), fixture, expected);
    }

    private void assertLegacyCpuRequestState(String message,
                                             Fixture fixture,
                                             LegacyCpuRequestState expected) {
        Hdma.HdmaState actual = hdmaState(fixture);
        assertEquals(message, expected.arbitration,
                String.valueOf(actual.cpuRequestArbitration()));
        assertEquals(message, expected.interruptOwner,
                actual.interruptEntryWonArbitration());
        assertEquals(message, expected.lateInterrupt,
                actual.cpuRequestAllowsLateInterrupt());
        assertEquals(message, expected.interruptOwner,
                fixture.hdma.isInterruptEntryRequestOwner());
        assertEquals(message, "UNRESOLVED".equals(expected.arbitration),
                fixture.hdma.isCpuRequestUnresolved());
        assertEquals(message, "CPU".equals(expected.arbitration),
                fixture.hdma.isCpuInstructionRequestOwner());
    }

    private record LegacyCpuRequestState(String arbitration,
                                         boolean interruptOwner,
                                         boolean lateInterrupt) {

        static LegacyCpuRequestState restored(String arbitration,
                                              boolean interruptOwner,
                                              boolean lateInterrupt) {
            return new LegacyCpuRequestState(arbitration, interruptOwner,
                    "CPU".equals(arbitration) && lateInterrupt);
        }

        LegacyCpuRequestState resolve(boolean cpuClaimedSlot, boolean interruptPending) {
            if (!"UNRESOLVED".equals(arbitration)) {
                return this;
            }
            String owner = cpuClaimedSlot ? "CPU" : "DMA";
            return new LegacyCpuRequestState(owner, interruptOwner,
                    "CPU".equals(owner) && !interruptPending);
        }

        LegacyCpuRequestState retireCpuSlot() {
            return "CPU".equals(arbitration)
                    ? new LegacyCpuRequestState("DMA", interruptOwner, false)
                    : this;
        }

        LegacyCpuRequestState acceptInterrupt() {
            return "CPU".equals(arbitration)
                    ? new LegacyCpuRequestState("DMA", lateInterrupt, false)
                    : this;
        }

        LegacyCpuRequestState stopRequest() {
            return "UNRESOLVED".equals(arbitration)
                    ? new LegacyCpuRequestState("DMA", interruptOwner, false)
                    : this;
        }
    }

    private void assertIgnoredCpuPhaseFlags(Hdma.HdmaState initialState) {
        Fixture predicate = new Fixture();
        predicate.hdma.restoreState(initialState);
        assertFalse(predicate.hdma.requiresCpuHdmaPhaseFlags());

        for (int flags = 0; flags < 8; flags++) {
            Fixture expected = new Fixture();
            Fixture actual = new Fixture();
            expected.hdma.restoreState(initialState);
            actual.hdma.restoreState(initialState);

            expected.hdma.advanceHblankRequest(false, false, false);
            actual.hdma.advanceHblankRequest((flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0);

            assertSameHdmaState(hdmaState(expected), hdmaState(actual));
        }
    }

    private void assertSameHdmaState(Hdma.HdmaState expected, Hdma.HdmaState actual) {
        assertEquals(expected.gpuMode(), actual.gpuMode());
        assertEquals(expected.transferInProgress(), actual.transferInProgress());
        assertEquals(expected.hblankTransfer(), actual.hblankTransfer());
        assertEquals(expected.lcdEnabled(), actual.lcdEnabled());
        assertEquals(expected.length(), actual.length());
        assertEquals(expected.src(), actual.src());
        assertEquals(expected.dst(), actual.dst());
        assertEquals(expected.tick(), actual.tick());
        assertArrayEquals(expected.blockData(), actual.blockData());
        assertEquals(expected.hblankRequestTicks(), actual.hblankRequestTicks());
        assertEquals(expected.hblankRequestAge(), actual.hblankRequestAge());
        assertEquals(expected.nextHblankRequestTicks(), actual.nextHblankRequestTicks());
        assertEquals(expected.nextHblankRequestAge(), actual.nextHblankRequestAge());
        assertEquals(expected.sourceBytesTransferred(), actual.sourceBytesTransferred());
        assertEquals(expected.cpuBusValue(), actual.cpuBusValue());
        assertEquals(expected.stopAfterCurrentBlock(), actual.stopAfterCurrentBlock());
        assertEquals(expected.preserveLengthAfterCurrentBlock(), actual.preserveLengthAfterCurrentBlock());
        assertEquals(expected.speedSwitchInProgress(), actual.speedSwitchInProgress());
        assertEquals(expected.speedSwitchStartedWithoutRequest(), actual.speedSwitchStartedWithoutRequest());
        assertEquals(expected.pauseOamDmaForSpeedSwitchBurst(), actual.pauseOamDmaForSpeedSwitchBurst());
        assertEquals(String.valueOf(expected.wakeRequestArbitration()),
                String.valueOf(actual.wakeRequestArbitration()));
        assertEquals(expected.gpuLine(), actual.gpuLine());
        assertEquals(expected.gpuTicksInLine(), actual.gpuTicksInLine());
        assertEquals(expected.gpuCpuClockRephased(), actual.gpuCpuClockRephased());
        assertEquals(expected.hblankStartTicksInLine(), actual.hblankStartTicksInLine());
        assertEquals(expected.cpuHalted(), actual.cpuHalted());
        assertEquals(String.valueOf(expected.haltHdmaState()), String.valueOf(actual.haltHdmaState()));
        assertEquals(expected.haltEnteredThisTick(), actual.haltEnteredThisTick());
        assertEquals(expected.requestOverlappedCpuWrite(), actual.requestOverlappedCpuWrite());
        assertEquals(expected.interruptEntryWonArbitration(), actual.interruptEntryWonArbitration());
        assertEquals(String.valueOf(expected.cpuRequestArbitration()),
                String.valueOf(actual.cpuRequestArbitration()));
        assertEquals(expected.cpuRequestAllowsLateInterrupt(), actual.cpuRequestAllowsLateInterrupt());
        assertEquals(expected.haltOpcodeRequestLatched(), actual.haltOpcodeRequestLatched());
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
        var memento = fixture.hdma.captureState();

        fixture.hdma.advanceHblankRequest(false, false, true);
        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());

        fixture.hdma.restoreState(memento);
        fixture.hdma.advanceHblankRequest(false, false, true);
        assertTrue(fixture.hdma.isInterruptEntryRequestOwner());
    }

    @Test
    public void doubleSpeedHblankDmaUsesThreeStartupTicksAfterAnEvenHblankEdge() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(1, 200);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuTiming(1, 247);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);

        fixture.tick(34);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
        assertEquals(0xa0, fixture.memory.getByte(0x8000));
        assertEquals(0xaf, fixture.memory.getByte(0x800f));
    }

    @Test
    public void doubleSpeedHblankDmaUsesTwoStartupTicksAfterAnOddHblankEdge() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(0, 252);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.hdma.onGpuTiming(1, 0);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onGpuTiming(1, 200);
        fixture.hdma.onGpuUpdate(Mode.PixelTransfer);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuTiming(1, 252);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(4);

        fixture.tick(33);
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
    public void lateMode2WakeRetainsOneLatchedHblankStartupTick() {
        Fixture fixture = new Fixture();
        fixture.hdma.onLcdSwitch(true);
        fixture.startTransfer(0x80);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.advanceHblankRequest(3);
        fixture.hdma.onCpuHaltState(true);
        fixture.hdma.onGpuUpdate(Mode.OamSearch);
        fixture.hdma.onGpuTiming(1, 64);
        fixture.hdma.onCpuHaltState(false);

        fixture.tick(34);
        assertEquals(0, fixture.memory.getByte(0x8000));

        fixture.tick(1);
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

    @Test
    public void rephasedNormalSpeedClosesTheLastHblankSlotOneDotEarlier() {
        Fixture unrephased = new Fixture();
        unrephased.hdma.onLcdSwitch(true);
        unrephased.hdma.onGpuTiming(1, 450, false);
        unrephased.hdma.onGpuUpdate(Mode.HBlank);
        unrephased.startTransfer(0x80);
        unrephased.tick(36);
        assertEquals(0xa0, unrephased.memory.getByte(0x8000));

        Fixture rephased = new Fixture();
        rephased.hdma.onLcdSwitch(true);
        rephased.hdma.onGpuTiming(1, 450, true);
        rephased.hdma.onGpuUpdate(Mode.HBlank);
        rephased.startTransfer(0x80);
        rephased.tick(40);
        assertEquals(0, rephased.memory.getByte(0x8000));
        assertTrue(rephased.hdma.hasPendingHblankTransfer());
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
        var grantedBurst = fixture.hdma.captureState();

        fixture.tick(10);
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());

        fixture.hdma.restoreState(grantedBurst);
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
        for (int i = 0; i < 34; i++) {
            assertFalse(fixture.hdma.tick());
            assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());
        }

        assertTrue(fixture.hdma.tick());
        assertTrue(fixture.hdma.completedHblankSpeedSwitchBurst());
        assertTrue(fixture.hdma.pausesOamDmaForSpeedSwitchBurst());

        fixture.hdma.onSpeedSwitchComplete();
        assertFalse(fixture.hdma.completedHblankSpeedSwitchBurst());
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
    public void dormantVblankTransferDoesNotAlignTheSpeedSwitchTail() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(144, 120);
        fixture.hdma.onGpuUpdate(Mode.VBlank);
        fixture.startTransfer(0x80);

        fixture.hdma.onSpeedSwitch();

        assertTrue(fixture.hdma.hasPendingHblankTransfer());
        assertFalse(fixture.hdma.alignsPendingHblankSpeedSwitchTail());
    }

    @Test
    public void retainedVisibleRequestStillAlignsTheTailAfterEnteringVblank() {
        Fixture fixture = new Fixture(2);
        fixture.hdma.onLcdSwitch(true);
        fixture.hdma.onGpuTiming(2, 250);
        fixture.hdma.onGpuUpdate(Mode.HBlank);
        fixture.startTransfer(0x80);

        fixture.hdma.onSpeedSwitch();
        fixture.hdma.onGpuUpdate(Mode.VBlank);

        assertTrue(fixture.hdma.alignsPendingHblankSpeedSwitchTail());
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
        assertFalse(yielded.hdma.isTransferInProgress());
        assertTrue(yielded.hdma.hasPendingHblankTransfer());
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
