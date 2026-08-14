package eu.rekawek.coffeegb.core.experimental.dma;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Board.CGB;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Board.DMG;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Bus.CARTRIDGE;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Bus.HRAM;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Bus.OAM;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Bus.VRAM;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Bus.WRAM;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.CpuClaim.INSTRUCTION;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.CpuClaim.RELINQUISH;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Falsifier.CPU_AND_VRAM_DMA_EXECUTE_TOGETHER;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Falsifier.CPU_WRITE_ON_OAM_DMA_SOURCE_WIRE;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Falsifier.PPU_FETCH_DURING_VRAM_DMA_COMMIT;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Lease.CPU_INSTRUCTION;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Lease.CPU_INTERRUPT;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Lease.NONE;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Master.CPU;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Master.OAM_DMA;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Master.PPU;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Master.VRAM_DMA;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Speed.DOUBLE;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.Speed.NORMAL;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.VramTransfer.GENERAL;
import static eu.rekawek.coffeegb.core.experimental.dma.DmaRequestGrantTopology.VramTransfer.HBLANK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Evidence for a bus-intent architecture, not another exhaustive transcription of {@link Dma}
 * and {@link Hdma}. Tests either compare a topology invariant with production or name the profile
 * that would falsify this bounded model.
 */
public class DmaRequestGrantTopologyTest {

    @Test
    public void resolverDoesNotMutateUntilTheCommitEdge() {
        MemoryImage memory = new MemoryImage();
        memory.write(WRAM, 0xc000, 0x42);
        memory.write(OAM, 0xfe00, 0xee);

        var resolution = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc000),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0)));

        assertEquals(0xee, memory.read(OAM, 0xfe00));
        assertEquals(List.of(new DmaRequestGrantTopology.ResolvedWrite(
                OAM_DMA, OAM, 0xfe00, 0x42)), resolution.writes());

        DmaRequestGrantTopology.commit(resolution, memory);
        assertEquals(0x42, memory.read(OAM, 0xfe00));
    }

    @Test
    public void cgbBusSeparationAndDmgMainBusSharingFallOutOfWiring() {
        MemoryImage memory = new MemoryImage();
        memory.write(CARTRIDGE, 0x0100, 0x11);
        memory.write(WRAM, 0xc000, 0x42);
        List<DmaRequestGrantTopology.Intent> intents = List.of(
                DmaRequestGrantTopology.Intent.read(CPU, CARTRIDGE, 0x0100),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc000),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0));

        var cgb = DmaRequestGrantTopology.resolve(CGB, memory, intents);
        assertEquals(Integer.valueOf(0x11), cgb.readValues().get(CPU));
        assertFalse(cgb.denied().contains(CPU));

        var dmg = DmaRequestGrantTopology.resolve(DMG, memory, intents);
        assertEquals(Integer.valueOf(0x42), dmg.readValues().get(CPU));
        assertTrue(dmg.denied().contains(CPU));
    }

    @Test
    public void cgbOamDmaOwnsOnlyItsSelectedSourceWire() {
        MemoryImage memory = new MemoryImage();
        memory.write(CARTRIDGE, 0x0200, 0x62);
        memory.write(WRAM, 0xc000, 0x73);

        var otherWire = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(CPU, WRAM, 0xc000),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, CARTRIDGE, 0x0200),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0)));
        assertEquals(Integer.valueOf(0x73), otherWire.readValues().get(CPU));
        assertFalse(otherWire.denied().contains(CPU));

        var sameWire = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(CPU, CARTRIDGE, 0x0100),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, CARTRIDGE, 0x0200),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0)));
        assertEquals(Integer.valueOf(0x62), sameWire.readValues().get(CPU));
        assertTrue(sameWire.denied().contains(CPU));
    }

    @Test
    public void ppuPortUseRatherThanVisibleModeBlocksTheCpu() {
        MemoryImage memory = new MemoryImage();
        memory.write(VRAM, 0x8000, 0x55);
        memory.write(VRAM, 0x8001, 0x66);
        var noPpuIntent = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(CPU, VRAM, 0x8000)));
        assertEquals(Integer.valueOf(0x55), noPpuIntent.readValues().get(CPU));

        var actualPpuFetch = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(CPU, VRAM, 0x8000),
                DmaRequestGrantTopology.Intent.read(PPU, VRAM, 0x8001)));
        assertEquals(Integer.valueOf(0xff), actualPpuFetch.readValues().get(CPU));
        assertEquals(Integer.valueOf(0x66), actualPpuFetch.readValues().get(PPU));
        assertTrue(actualPpuFetch.denied().contains(CPU));
    }

    @Test
    public void vramDmaSourceStrobeDrivesTheSharedOamCopyMux() {
        MemoryImage memory = new MemoryImage();
        memory.write(WRAM, 0xc00b, 0x4b);
        memory.write(CARTRIDGE, 0x0001, 0x9e);
        memory.write(OAM, 0xfe01, 0xee);
        memory.write(OAM, 0xfe0b, 0xee);

        var resolution = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc00b),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe0b, 0),
                DmaRequestGrantTopology.Intent.read(VRAM_DMA, CARTRIDGE, 0x0001)));

        assertEquals(List.of(new DmaRequestGrantTopology.ResolvedWrite(
                OAM_DMA, OAM, 0xfe01, 0x9e)), resolution.writes());
        assertNull(resolution.readValues().get(OAM_DMA));
        assertEquals(Integer.valueOf(0x9e), resolution.readValues().get(VRAM_DMA));
        DmaRequestGrantTopology.commit(resolution, memory);
        assertEquals(0x9e, memory.read(OAM, 0xfe01));
        assertEquals(0xee, memory.read(OAM, 0xfe0b));
    }

    @Test
    public void ppuOamReadSeesTheDmaDrivenValue() {
        MemoryImage memory = new MemoryImage();
        memory.write(WRAM, 0xc000, 0x44);
        memory.write(OAM, 0xfe00, 0xee);

        var resolution = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(PPU, OAM, 0xfe00),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc000),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0)));

        assertEquals(Integer.valueOf(0x44), resolution.readValues().get(PPU));
    }

    @Test
    public void intentOrderCannotChangeTheResolvedCycle() {
        MemoryImage memory = new MemoryImage();
        memory.write(HRAM, 0xff80, 0x12);
        memory.write(OAM, 0xfe01, 0xee);
        memory.write(WRAM, 0xc00b, 0x4b);
        memory.write(CARTRIDGE, 0x0001, 0x9e);
        List<DmaRequestGrantTopology.Intent> intents = List.of(
                DmaRequestGrantTopology.Intent.read(CPU, HRAM, 0xff80),
                DmaRequestGrantTopology.Intent.read(PPU, OAM, 0xfe01),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc00b),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe0b, 0),
                DmaRequestGrantTopology.Intent.read(VRAM_DMA, CARTRIDGE, 0x0001));
        var expected = DmaRequestGrantTopology.resolve(CGB, memory, intents);
        assertTrue(expected.falsifiers().contains(CPU_AND_VRAM_DMA_EXECUTE_TOGETHER));

        List<List<DmaRequestGrantTopology.Intent>> permutations = new ArrayList<>();
        permute(new ArrayList<>(intents), 0, permutations);
        assertEquals(120, permutations.size());
        for (List<DmaRequestGrantTopology.Intent> permutation : permutations) {
            assertEquals(expected, DmaRequestGrantTopology.resolve(CGB, memory, permutation));
        }
    }

    @Test
    public void requestResolutionIsSeparateFromLatchCommit() {
        DmaRequestGrantTopology.GrantLatch latch = new DmaRequestGrantTopology.GrantLatch();
        var resolved = latch.resolve(DmaRequestGrantTopology.GrantSignals.request(INSTRUCTION));

        assertEquals(NONE, latch.state().lease());
        assertEquals(CPU_INSTRUCTION, resolved.lease());

        latch.commit(resolved);
        assertEquals(CPU_INSTRUCTION, latch.state().lease());
    }

    @Test
    public void cpuLeaseRetiresBeforeVramDmaWithoutOpcodeKnowledge() {
        DmaRequestGrantTopology.GrantLatch latch = new DmaRequestGrantTopology.GrantLatch();
        latch.step(DmaRequestGrantTopology.GrantSignals.request(INSTRUCTION));
        assertEquals(CPU_INSTRUCTION, latch.state().lease());

        latch.step(new DmaRequestGrantTopology.GrantSignals(
                false, DmaRequestGrantTopology.CpuClaim.NONE,
                false, false, true, false, false));
        assertEquals(DmaRequestGrantTopology.Lease.VRAM_DMA, latch.state().lease());
    }

    @Test
    public void haltOrStopRelinquishesTheRequestWithoutAnOpcodeComparison() {
        DmaRequestGrantTopology.GrantLatch latch = new DmaRequestGrantTopology.GrantLatch();
        latch.step(DmaRequestGrantTopology.GrantSignals.request(RELINQUISH));
        assertEquals(DmaRequestGrantTopology.Lease.VRAM_DMA, latch.state().lease());
    }

    @Test
    public void lateInterruptInheritsOnlyAnEligibleCpuLease() {
        DmaRequestGrantTopology.GrantLatch late = new DmaRequestGrantTopology.GrantLatch();
        late.step(DmaRequestGrantTopology.GrantSignals.request(INSTRUCTION));
        late.step(new DmaRequestGrantTopology.GrantSignals(
                false, DmaRequestGrantTopology.CpuClaim.NONE,
                false, false, true, true, false));
        assertEquals(CPU_INTERRUPT, late.state().lease());
        late.step(new DmaRequestGrantTopology.GrantSignals(
                false, DmaRequestGrantTopology.CpuClaim.NONE,
                false, false, true, false, false));
        assertEquals(DmaRequestGrantTopology.Lease.VRAM_DMA, late.state().lease());

        DmaRequestGrantTopology.GrantLatch preexisting = new DmaRequestGrantTopology.GrantLatch();
        preexisting.step(new DmaRequestGrantTopology.GrantSignals(
                true, INSTRUCTION, false, true, false, false, false));
        preexisting.step(new DmaRequestGrantTopology.GrantSignals(
                false, DmaRequestGrantTopology.CpuClaim.NONE,
                false, false, true, true, false));
        assertEquals(DmaRequestGrantTopology.Lease.VRAM_DMA, preexisting.state().lease());
    }

    @Test
    public void frameStartPreemptionIsAControlSignalRatherThanPpuInspection() {
        DmaRequestGrantTopology.GrantLatch latch = new DmaRequestGrantTopology.GrantLatch();
        latch.step(new DmaRequestGrantTopology.GrantSignals(
                true, INSTRUCTION, true, false, false, false, false));
        assertEquals(DmaRequestGrantTopology.Lease.VRAM_DMA, latch.state().lease());
    }

    @Test
    public void threeCellHblankSynchronizerStopsWhenItsClockIsGated() {
        var state = DmaRequestGrantTopology.SyncState.clear();
        state = DmaRequestGrantTopology.resolveSynchronizer(state, true, true);
        assertFalse(state.request());
        state = DmaRequestGrantTopology.resolveSynchronizer(state, true, false);
        assertFalse(state.request());
        state = DmaRequestGrantTopology.resolveSynchronizer(state, true, true);
        assertFalse(state.request());
        state = DmaRequestGrantTopology.resolveSynchronizer(state, true, true);
        assertTrue(state.request());
    }

    @Test
    public void detachedOamSequencerMatchesProductionAtBothSpeeds() {
        assertOamSequencerDifferential(NORMAL);
        assertOamSequencerDifferential(DOUBLE);
    }

    @Test
    public void speedSwitchChangesOnlyFutureOamDmaClockEdges() {
        Ram memory = oamSourceMemory();
        Ram oam = filledOam();
        MutableSpeedMode speedMode = new MutableSpeedMode(1);
        Dma production = new Dma(memory, oam, speedMode);
        production.setByte(0xff46, 0x12);
        var topology = DmaRequestGrantTopology.OamState.started();
        int[] expected = filledBytes(0xee);

        for (int masterTick = 0; topology.active(); masterTick++) {
            if (masterTick == 40) {
                speedMode.speed = 2;
            }
            var speed = speedMode.speed == 1 ? NORMAL : DOUBLE;
            var step = DmaRequestGrantTopology.resolveOam(topology, speed, false);
            topology = step.next();
            applyOamEdges(step, expected);
            production.tick();
            assertOamEquals(expected, oam, "dynamic speed tick " + masterTick);
            assertEquals(topology.active(), production.isTransferInProgress());
        }
    }

    @Test
    public void detachedVramSequencerMatchesProductionSourceSlotsAndCommitEdges() {
        assertVramSequencerDifferential(GENERAL, NORMAL, 0);
        assertVramSequencerDifferential(GENERAL, DOUBLE, 0);
        assertVramSequencerDifferential(HBLANK, NORMAL, 0);
        assertVramSequencerDifferential(HBLANK, DOUBLE, 248);
        assertVramSequencerDifferential(HBLANK, DOUBLE, 253);
    }

    @Test
    public void detachedSynchronizerMatchesTheOrdinaryProductionRequestEdge() {
        Hdma production = hblankProduction(NORMAL, 0);
        var topology = DmaRequestGrantTopology.SyncState.clear();
        for (int edge = 1; edge <= 3; edge++) {
            topology = DmaRequestGrantTopology.resolveSynchronizer(topology, true, true);
            production.advanceHblankRequest();
            assertEquals("edge " + edge, topology.request(), production.isCpuRequestUnresolved());
        }
    }

    @Test
    public void detachedGrantLeaseMatchesRepresentativeProductionArbitration() {
        Hdma cpuWins = synchronizedProductionRequest(1);
        var topology = DmaRequestGrantTopology.resolveGrant(
                DmaRequestGrantTopology.GrantState.idle(),
                DmaRequestGrantTopology.GrantSignals.request(INSTRUCTION));
        cpuWins.resolveCpuRequest(true, false);
        assertEquals(topology.lease() == CPU_INSTRUCTION,
                cpuWins.isCpuInstructionRequestOwner());

        topology = DmaRequestGrantTopology.resolveGrant(topology,
                new DmaRequestGrantTopology.GrantSignals(
                        false, DmaRequestGrantTopology.CpuClaim.NONE,
                        false, false, true, true, false));
        cpuWins.onInterruptEntryAcceptedByCpu();
        assertEquals(topology.lease() == CPU_INTERRUPT,
                cpuWins.isInterruptEntryRequestOwner());

        Hdma haltRelinquishes = synchronizedProductionRequest(1);
        var haltTopology = DmaRequestGrantTopology.resolveGrant(
                DmaRequestGrantTopology.GrantState.idle(),
                DmaRequestGrantTopology.GrantSignals.request(RELINQUISH));
        haltRelinquishes.resolveCpuRequest(false, false);
        assertEquals(haltTopology.lease() == DmaRequestGrantTopology.Lease.VRAM_DMA,
                !haltRelinquishes.isCpuInstructionRequestOwner());

        Hdma frameStart = synchronizedProductionRequest(0);
        var frameTopology = DmaRequestGrantTopology.resolveGrant(
                DmaRequestGrantTopology.GrantState.idle(),
                new DmaRequestGrantTopology.GrantSignals(
                        true, INSTRUCTION, true, false, false, false, false));
        frameStart.resolveCpuRequest(true, false);
        assertEquals(frameTopology.lease() == DmaRequestGrantTopology.Lease.VRAM_DMA,
                !frameStart.isCpuInstructionRequestOwner());
    }

    @Test
    public void sharedDmaSourceMuxMatchesProductionOamCollision() {
        Ram productionMemory = oamSourceMemory();
        Ram productionOam = filledOam();
        Dma production = new Dma(
                productionMemory, productionOam, new MutableSpeedMode(1));
        production.setByte(0xff46, 0x12);
        for (int i = 0; i < 51; i++) {
            production.tick();
        }
        production.setVramDmaBusSample(new Hdma.SourceBusSample(0x0001, 0x9e));
        production.tick();

        MemoryImage topologyMemory = new MemoryImage();
        topologyMemory.write(WRAM, 0xc00b, 0x4b);
        topologyMemory.write(CARTRIDGE, 0x0001, 0x9e);
        topologyMemory.write(OAM, 0xfe01, 0xee);
        topologyMemory.write(OAM, 0xfe0b, 0xee);
        var resolution = DmaRequestGrantTopology.resolve(CGB, topologyMemory, List.of(
                DmaRequestGrantTopology.Intent.read(OAM_DMA, WRAM, 0xc00b),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe0b, 0),
                DmaRequestGrantTopology.Intent.read(VRAM_DMA, CARTRIDGE, 0x0001)));
        DmaRequestGrantTopology.commit(resolution, topologyMemory);

        assertEquals(productionOam.getByte(0xfe01), topologyMemory.read(OAM, 0xfe01));
        assertEquals(productionOam.getByte(0xfe0b), topologyMemory.read(OAM, 0xfe0b));
    }

    @Test
    public void unsupportedElectricalAndVisibilityProfilesAreExplicitFalsifiers() {
        MemoryImage memory = new MemoryImage();
        var writeCollision = DmaRequestGrantTopology.resolve(DMG, memory, List.of(
                DmaRequestGrantTopology.Intent.write(CPU, WRAM, 0xc000, 0x0f),
                DmaRequestGrantTopology.Intent.read(OAM_DMA, CARTRIDGE, 0x1200),
                DmaRequestGrantTopology.Intent.write(OAM_DMA, OAM, 0xfe00, 0)));
        assertTrue(writeCollision.falsifiers().contains(CPU_WRITE_ON_OAM_DMA_SOURCE_WIRE));

        var ppuCommitCollision = DmaRequestGrantTopology.resolve(CGB, memory, List.of(
                DmaRequestGrantTopology.Intent.read(PPU, VRAM, 0x8000),
                DmaRequestGrantTopology.Intent.write(VRAM_DMA, VRAM, 0x8001, 0x42)));
        assertTrue(ppuCommitCollision.falsifiers().contains(PPU_FETCH_DURING_VRAM_DMA_COMMIT));

        assertEquals(EnumSet.of(
                        DmaRequestGrantTopology.UnmodeledProfile.HALT_WAKE_REQUEST_LEVEL_HISTORY,
                        DmaRequestGrantTopology.UnmodeledProfile.STOP_AND_SPEED_SWITCH_REVERSE_PHASE,
                        DmaRequestGrantTopology.UnmodeledProfile.TERMINAL_HBLANK_REQUEST_CUTOFF,
                        DmaRequestGrantTopology.UnmodeledProfile.OVERLAPPING_HBLANK_REQUEST_QUEUE,
                        DmaRequestGrantTopology.UnmodeledProfile.HDMA_DISABLE_DURING_REQUEST_HANDOFF,
                        DmaRequestGrantTopology.UnmodeledProfile.OAM_DMA_HALT_ENTRY_LATENCY,
                        DmaRequestGrantTopology.UnmodeledProfile.OAM_DMA_RESTART_OWNERSHIP,
                        DmaRequestGrantTopology.UnmodeledProfile.PPU_OAM_READER_ACQUIRE_RELEASE_HISTORY,
                        DmaRequestGrantTopology.UnmodeledProfile.DMG_PARTIAL_SOURCE_ADDRESS_DECODE,
                        DmaRequestGrantTopology.UnmodeledProfile.CGB_OAM_SOURCE_A12_WRAM_ALIAS,
                        DmaRequestGrantTopology.UnmodeledProfile.INTERRUPT_STACK_WRITE_DELAYED_COLLISION,
                        DmaRequestGrantTopology.UnmodeledProfile.CPU_OAM_DMA_ANALOG_WRITE_CORRUPTION,
                        DmaRequestGrantTopology.UnmodeledProfile.INVALID_VRAM_SOURCE_OPEN_BUS_DECAY,
                        DmaRequestGrantTopology.UnmodeledProfile.VRAM_BLOCK_DESTINATION_VISIBILITY),
                DmaRequestGrantTopology.unmodeledProfiles());
    }

    private static void assertOamSequencerDifferential(
            DmaRequestGrantTopology.Speed speed) {
        Ram memory = oamSourceMemory();
        Ram oam = filledOam();
        Dma production = new Dma(
                memory, oam, new MutableSpeedMode(speed.cpuClocksPerMasterTick));
        production.setByte(0xff46, 0x12);
        var topology = DmaRequestGrantTopology.OamState.started();
        int[] expected = filledBytes(0xee);

        int masterTick = 0;
        while (topology.active()) {
            var step = DmaRequestGrantTopology.resolveOam(topology, speed, false);
            topology = step.next();
            applyOamEdges(step, expected);
            production.tick();
            assertOamEquals(expected, oam, speed + " tick " + masterTick++);
            assertEquals(topology.active(), production.isTransferInProgress());
        }
        assertEquals(0xa0, topology.nextByte());
    }

    private static void assertVramSequencerDifferential(
            DmaRequestGrantTopology.VramTransfer transfer,
            DmaRequestGrantTopology.Speed speed,
            int hblankStartDot) {
        Ram memory = new Ram(0, 0x10000);
        for (int i = 0; i < 0x10; i++) {
            memory.setByte(0x1200 + i, 0xa0 + i);
        }
        Hdma production = new Hdma(
                memory, new MutableSpeedMode(speed.cpuClocksPerMasterTick));
        production.setByte(0xff51, 0x12);
        production.setByte(0xff52, 0x00);
        production.setByte(0xff53, 0x00);
        production.setByte(0xff54, 0x00);
        if (transfer == HBLANK) {
            production.onLcdSwitch(true);
            primeHblankStartupPhase(production, speed, hblankStartDot);
        }
        production.setByte(0xff55, transfer == HBLANK ? 0x80 : 0x00);
        if (transfer == HBLANK) {
            production.onGpuTiming(1, hblankStartDot - 1);
            production.onGpuUpdate(Mode.HBlank);
            for (int i = 0; i < 3; i++) {
                production.advanceHblankRequest();
            }
        }

        var topology = DmaRequestGrantTopology.VramState.started(
                transfer, speed, hblankStartDot, 0x1200);
        for (int grantedTick = 1; grantedTick <= 40; grantedTick++) {
            var step = DmaRequestGrantTopology.resolveVram(topology, true);
            topology = step.next();
            boolean productionCommit = production.tick();
            Hdma.SourceBusSample productionSample = production.consumeSourceBusSample();

            if (step.sourceSlot() == null) {
                assertNull(profile(transfer, speed, hblankStartDot, grantedTick), productionSample);
            } else {
                assertEquals(profile(transfer, speed, hblankStartDot, grantedTick),
                        new Hdma.SourceBusSample(
                                step.sourceSlot().address(),
                                0xa0 + step.sourceSlot().byteIndex()),
                        productionSample);
            }
            assertEquals(profile(transfer, speed, hblankStartDot, grantedTick),
                    step.blockCommit(), productionCommit);
            if (step.blockCommit()) {
                for (int i = 0; i < 0x10; i++) {
                    assertEquals(0xa0 + i, memory.getByte(0x8000 + i));
                }
                return;
            }
        }
        throw new AssertionError("VRAM DMA never committed: "
                + profile(transfer, speed, hblankStartDot, 40));
    }

    private static String profile(
            DmaRequestGrantTopology.VramTransfer transfer,
            DmaRequestGrantTopology.Speed speed,
            int hblankStartDot,
            int tick) {
        return transfer + "/" + speed + "/dot=" + hblankStartDot + "/tick=" + tick;
    }

    private static void primeHblankStartupPhase(
            Hdma production,
            DmaRequestGrantTopology.Speed speed,
            int hblankStartDot) {
        if (speed == DOUBLE && (hblankStartDot & 1) != 0) {
            // Hdma retains the preceding HBlank edge's half-dot phase when FF55 is armed.
            production.onGpuTiming(0, hblankStartDot - 1);
            production.onGpuUpdate(Mode.HBlank);
            production.onGpuTiming(1, 0);
            production.onGpuUpdate(Mode.OamSearch);
        }
        production.onGpuTiming(1, 200);
        production.onGpuUpdate(Mode.PixelTransfer);
    }

    private static Hdma hblankProduction(
            DmaRequestGrantTopology.Speed speed, int hblankStartDot) {
        Hdma production = new Hdma(
                new Ram(0, 0x10000),
                new MutableSpeedMode(speed.cpuClocksPerMasterTick));
        production.onLcdSwitch(true);
        production.onGpuTiming(1, 200);
        production.onGpuUpdate(Mode.PixelTransfer);
        production.setByte(0xff55, 0x80);
        production.onGpuTiming(1, hblankStartDot - 1);
        production.onGpuUpdate(Mode.HBlank);
        return production;
    }

    private static Hdma synchronizedProductionRequest(int line) {
        Hdma production = new Hdma(new Ram(0, 0x10000), new MutableSpeedMode(1));
        production.onLcdSwitch(true);
        production.onGpuTiming(line, 240);
        production.onGpuUpdate(Mode.PixelTransfer);
        production.setByte(0xff55, 0x80);
        production.onGpuUpdate(Mode.HBlank);
        for (int i = 0; i < 3; i++) {
            production.advanceHblankRequest();
        }
        return production;
    }

    private static Ram oamSourceMemory() {
        Ram memory = new Ram(0, 0x10000);
        for (int i = 0; i < 0xa0; i++) {
            memory.setByte(0x1200 + i, 0x40 + i);
        }
        return memory;
    }

    private static Ram filledOam() {
        Ram oam = new Ram(0xfe00, 0xa0);
        for (int i = 0; i < 0xa0; i++) {
            oam.setByte(0xfe00 + i, 0xee);
        }
        return oam;
    }

    private static int[] filledBytes(int value) {
        int[] bytes = new int[0xa0];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static void applyOamEdges(
            DmaRequestGrantTopology.OamStep step, int[] expected) {
        for (DmaRequestGrantTopology.OamCopyEdge edge : step.copyEdges()) {
            expected[edge.byteIndex()] = 0x40 + edge.byteIndex();
        }
    }

    private static void assertOamEquals(int[] expected, Ram actual, String context) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(context + ", byte " + i, expected[i], actual.getByte(0xfe00 + i));
        }
    }

    private static void permute(
            List<DmaRequestGrantTopology.Intent> values,
            int index,
            List<List<DmaRequestGrantTopology.Intent>> output) {
        if (index == values.size()) {
            output.add(List.copyOf(values));
            return;
        }
        for (int i = index; i < values.size(); i++) {
            java.util.Collections.swap(values, index, i);
            permute(values, index + 1, output);
            java.util.Collections.swap(values, index, i);
        }
    }

    private static final class MemoryImage
            implements DmaRequestGrantTopology.Snapshot, DmaRequestGrantTopology.WriteSink {

        private final EnumMap<DmaRequestGrantTopology.Bus, Map<Integer, Integer>> bytes =
                new EnumMap<>(DmaRequestGrantTopology.Bus.class);

        private MemoryImage() {
            for (DmaRequestGrantTopology.Bus bus : DmaRequestGrantTopology.Bus.values()) {
                bytes.put(bus, new HashMap<>());
            }
        }

        @Override
        public int read(DmaRequestGrantTopology.Bus bus, int address) {
            return bytes.get(bus).getOrDefault(address & 0xffff, 0xff);
        }

        @Override
        public void write(DmaRequestGrantTopology.Bus bus, int address, int value) {
            bytes.get(bus).put(address & 0xffff, value & 0xff);
        }
    }

    private static final class MutableSpeedMode extends SpeedMode {

        private int speed;

        private MutableSpeedMode(int speed) {
            super(true);
            this.speed = speed;
        }

        @Override
        public int getSpeedMode() {
            return speed;
        }
    }
}
