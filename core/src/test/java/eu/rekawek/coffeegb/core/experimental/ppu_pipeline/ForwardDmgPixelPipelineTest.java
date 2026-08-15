package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.ColorPalette;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.CONTROL_TO_FIFO_DOTS;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_CGB;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_DISABLED_WINDOW_INSERTION;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_MIDLINE_FINE_SCX_REPHASE;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_MODE3_END_AND_STAT;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_OVERLAPPING_OBJECT_PRIORITY;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_PALETTE_AND_LCDC_OUTPUT_MUX;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_WINDOW_TRIGGER_AFTER_SCANOUT_COMMIT;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.RAW_TO_LCD_DOTS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ForwardDmgPixelPipelineTest {

    @Test
    public void controlFifoAndLcdCoordinatesAreOneForwardSevenDotPath() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.writeVram(0x8000, 0x80);
        graph.writeVram(0x8001, 0x00);

        graph.requestFifoFlush();
        graph.launchTile(0, 0);
        assertEquals(8, graph.fetchX());
        assertEquals(0, graph.fifoPopX());
        assertEquals(0, graph.lcdX());
        assertTrue(graph.tileFlightValid());

        tick(graph, CONTROL_TO_FIFO_DOTS);
        assertEquals(0, graph.fifoPopX());
        graph.tick();

        assertEquals(CONTROL_TO_FIFO_DOTS, graph.lastFifoPopDot());
        assertFalse(graph.tileFlightValid());
        assertTrue(graph.fifoValid());
        assertEquals(1, graph.fifoPopX());
        assertEquals(1, graph.lastPoppedRaw());
        assertTrue(graph.scanoutValid());
        assertEquals(0, graph.lcdX());

        int firstPopDot = graph.lastFifoPopDot();
        tick(graph, RAW_TO_LCD_DOTS);
        assertEquals(firstPopDot + RAW_TO_LCD_DOTS, graph.lastLcdDot());
        assertEquals(1, graph.lcdRaw(0));
    }

    @Test
    public void firstWindowHighByteIsSampledLateOnceInsteadOfReadThenRefreshed() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.setUnsignedTileData(true);
        graph.writeVram(0x8000, 0x80); // low plane under unsigned LCDC.4
        graph.writeVram(0x8001, 0x00);
        graph.writeVram(0x9001, 0x80); // high plane under signed LCDC.4

        graph.requestFifoFlush();
        graph.launchTile(0, 0);
        graph.tick(); // launch dot: the already-scheduled low byte is sampled
        assertEquals(0x8000, graph.tileLowAddress());
        assertEquals(1, graph.vramReadCount(0x8000));

        graph.tick();
        graph.tick();
        graph.setUnsignedTileData(false); // late LCDC.4 edge, before the physical high sample
        graph.tick();
        assertEquals(0x9001, graph.tileHighAddress());
        assertEquals(graph.tileLowSampleDot() + 3, graph.tileHighSampleDot());
        assertEquals(0, graph.vramReadCount(0x8001));
        assertEquals(1, graph.vramReadCount(0x9001));

        graph.tick();
        assertEquals(3, graph.lastPoppedRaw() & 3);
        assertEquals("the high plane had one physical transaction", 1,
                graph.vramReadCount(graph.tileHighAddress()));
    }

    @Test
    public void aWriteAfterThePhysicalWindowSampleCannotPatchForwardPixels() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.writeVram(0x8000, 0x80);
        graph.writeVram(0x8001, 0x00);
        graph.requestFifoFlush();
        graph.launchTile(0, 0);

        tick(graph, 4); // dot 3 sampled the only high-byte transaction
        graph.writeVram(0x8001, 0x80);
        graph.tick();

        assertEquals(1, graph.lastPoppedRaw() & 3);
        assertEquals(1, graph.vramReadCount(0x8001));
    }

    @Test
    public void objectLowAndHighBytesAreIndependentTransactionsAroundTheResumeEdge() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(16, 0);
        graph.seedBackgroundFetchPhase(5);
        graph.setTallObjects(false);
        graph.writeVram(0x8030, 0x80); // tile 3, line 0, low byte in 8px mode
        graph.writeVram(0x8031, 0x00);
        graph.writeVram(0x8021, 0x80); // tile 2, line 0, high byte in 16px mode
        graph.requestObject(3, 0, false);

        int stalledDots = drainObjectStall(graph);
        assertEquals(6, stalledDots);
        assertTrue(graph.objectHighPending());
        assertEquals(0x8030, graph.objectLowAddress());
        assertEquals(1, graph.vramReadCount(0x8030));
        assertEquals(0, graph.fifoPopX());

        graph.setTallObjects(true); // LCDC.2 changes between the low and high samples
        graph.tick();
        assertEquals(0x8021, graph.objectHighAddress());
        assertEquals(graph.objectLowSampleDot() + 1, graph.objectHighSampleDot());
        assertEquals(0, graph.vramReadCount(0x8031));
        assertEquals(1, graph.vramReadCount(0x8021));
        assertEquals(3, (graph.lastPoppedRaw() >>> 2) & 3);
        assertEquals("the resumed dot both samples object high and pops raw", 1,
                graph.fifoPopX());
    }

    @Test
    public void spriteWaitConeDifferentiallyMatchesCurrentPixelTransferForEveryPhase() {
        int productionBackgroundOnly = productionLineTicks();
        assertEquals(168, productionBackgroundOnly);

        for (int phase = 0; phase < 8; phase++) {
            ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
            graph.seedRawFifo(16, 0);
            graph.seedBackgroundFetchPhase(phase);
            graph.requestObject(0, 0, false);
            int graphStall = drainObjectStall(graph);

            int productionStall = productionLineTicks(phase) - productionBackgroundOnly;
            assertEquals("fetch phase " + phase, productionStall, graphStall);
            assertEquals("fetch phase " + phase, Math.max(11 - phase, 6), graphStall);
        }
    }

    @Test
    public void sameXObjectLeavesTheWaitConeReadyForAnotherSixDotFetch() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(16, 0);
        graph.seedBackgroundFetchPhase(0);
        graph.requestObject(0, 0, false);
        assertEquals(11, drainObjectStall(graph));
        graph.tick(); // high sample and forward resume
        assertEquals(6, graph.backgroundFetchPhase());

        graph.requestObject(0, 0, false);
        assertEquals(6, drainObjectStall(graph));
        assertEquals(11 + 6, productionLineTicks(0, 0) - productionLineTicks());
    }

    @Test
    public void objectEnableFallingEdgeInvalidatesFutureStagesAndReleasesPopThisDot() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(16, 1);
        graph.seedBackgroundFetchPhase(0);
        graph.requestObject(0, 0, false);

        graph.tick();
        graph.tick();
        assertEquals(0, graph.fifoPopX());
        assertTrue(graph.popStalled());

        graph.setObjectsEnabled(false);
        graph.tick();
        assertTrue(graph.objectAbortLastDot());
        assertFalse(graph.stallLastDot());
        assertFalse(graph.objectHighPending());
        assertEquals("the one forward machine resumes without a catch-up loop", 1,
                graph.fifoPopX());
        assertEquals("the invalid object transaction never reaches VRAM", 0,
                graph.vramReadCount(0x8000));

        assertEquals("same focused abort phase as current PixelTransfer", 2,
                productionAbortPenalty(1));
    }

    @Test
    public void fineScxGatesRawValidityWithoutMovingTheWindowComparator() {
        int[] triggerDots = new int[2];
        int[] activationDots = new int[2];
        int i = 0;
        for (int fineScx : new int[] {0, 3}) {
            ForwardDmgPixelPipeline graph = windowGraph(fineScx, true);
            graph.tick();
            assertTrue(graph.windowTriggerLastDot());
            assertEquals(0, graph.windowTriggerDot());
            assertEquals(1, graph.fifoPopX());
            assertEquals(1, graph.windowCompareX());
            assertEquals(0, graph.lcdX());

            graph.tick();
            assertTrue(graph.windowActivationLastDot());
            assertTrue(graph.windowActive());
            assertEquals("the matching background token is invalidated, not rewound", 1,
                    graph.fifoPopX());
            assertFalse(graph.scanoutValid());
            triggerDots[i] = graph.windowTriggerDot();
            activationDots[i] = graph.windowActivationDot();
            i++;
        }
        assertEquals(triggerDots[0], triggerDots[1]);
        assertEquals(activationDots[0], activationDots[1]);
    }

    @Test
    public void disabledWindowMatchCanResolveWhileRawTokensRemainInScanout() {
        ForwardDmgPixelPipeline graph = windowGraph(0, false);
        graph.tick(); // capture the disabled WX match and pop its background token
        graph.tick(); // its successor also travels forward while the trigger is retained
        assertEquals(2, graph.fifoPopX());
        assertEquals(0, graph.lcdX());
        assertTrue(graph.windowTriggerValid());

        graph.setWindowEnabled(true);
        graph.tick();
        assertTrue(graph.windowActive());
        assertFalse(graph.scanoutValid());
        assertEquals("FIFO-pop stays monotonic", 2, graph.fifoPopX());
        assertEquals("no invalidated token crossed the panel boundary", 0, graph.lcdX());
    }

    @Test
    public void windowTriggerExpiresAtTheIrreversibleScanoutBoundary() {
        ForwardDmgPixelPipeline graph = windowGraph(0, false);
        graph.tick();
        tick(graph, RAW_TO_LCD_DOTS);
        assertEquals(1, graph.lcdX());
        assertFalse(graph.windowTriggerValid());

        graph.setWindowEnabled(true);
        graph.tick();
        assertFalse("the graph refuses to reconstruct a committed pixel", graph.windowActive());
    }

    @Test
    public void midlineFineScxRephaseIsAnExecutableFalsifierNotARepairPath() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(2, 0);
        graph.setFineScx(3);
        graph.tick();

        assertThrows(UnsupportedOperationException.class, () -> graph.setFineScx(1));
    }

    @Test
    public void stallAndFlushNeverRecallARawPixelAlreadyInScanout() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(4, 2);
        graph.tick();
        assertEquals(1, graph.fifoPopX());
        assertTrue(graph.scanoutValid());

        graph.requestFifoFlush();
        graph.seedBackgroundFetchPhase(0);
        graph.requestObject(0, 0, false);
        graph.tick();
        assertTrue(graph.flushLastDot());
        assertTrue(graph.stallLastDot());
        assertFalse(graph.fifoValid());

        tick(graph, 2);
        assertEquals(1, graph.lcdX());
        assertEquals(2, graph.lcdRaw(0));
        assertEquals("flush and stall do not rewind either coordinate", 1, graph.fifoPopX());
    }

    @Test
    public void intentionallyIncompleteConesAreNamedAndHaveNoFallbackBranches() {
        assertEquals(
                OUTSIDE_MIDLINE_FINE_SCX_REPHASE
                        | OUTSIDE_DISABLED_WINDOW_INSERTION
                        | OUTSIDE_PALETTE_AND_LCDC_OUTPUT_MUX
                        | OUTSIDE_OVERLAPPING_OBJECT_PRIORITY
                        | OUTSIDE_CGB
                        | OUTSIDE_MODE3_END_AND_STAT
                        | OUTSIDE_WINDOW_TRIGGER_AFTER_SCANOUT_COMMIT
                        | OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION,
                ForwardDmgPixelPipeline.incompleteBehaviorMask());
    }

    private static ForwardDmgPixelPipeline windowGraph(int fineScx, boolean enabled) {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(16, 1);
        graph.seedWindowCompareX(0);
        graph.setFineScx(fineScx);
        graph.configureWindow(7, 0, 0);
        graph.setWindowEnabled(enabled);
        graph.writeVram(0x8000, 0x80);
        graph.writeVram(0x8001, 0x00);
        return graph;
    }

    private static int drainObjectStall(ForwardDmgPixelPipeline graph) {
        int dots = 0;
        while (graph.popStalled()) {
            graph.tick();
            dots++;
            if (dots > 32) {
                throw new AssertionError("object wait did not settle");
            }
        }
        return dots;
    }

    private static void tick(ForwardDmgPixelPipeline graph, int dots) {
        for (int i = 0; i < dots; i++) {
            graph.tick();
        }
    }

    private static int productionLineTicks(int... spriteXs) {
        ProductionRig rig = productionRig(spriteXs);
        rig.transfer.start();
        int ticks = 0;
        do {
            ticks++;
            if (ticks > 1000) {
                throw new AssertionError("production PixelTransfer did not finish");
            }
        } while (rig.transfer.tick());
        return ticks;
    }

    private static int productionAbortPenalty(int ticksAfterDetection) {
        ProductionRig rig = productionRig(0);
        rig.transfer.start();
        int limit = 1000;
        while (!rig.transfer.isObjectFetchInProgress() && limit-- > 0) {
            rig.transfer.tick();
        }
        if (limit <= 0) {
            throw new AssertionError("production object fetch did not start");
        }
        for (int i = 0; i < ticksAfterDetection; i++) {
            rig.transfer.tick();
        }
        rig.lcdc.set(0x91);
        while (rig.transfer.tick() && limit-- > 0) {
            // finish the line so objectTimingPenalty includes the abort edge
        }
        if (limit <= 0) {
            throw new AssertionError("production PixelTransfer did not finish after abort");
        }
        return rig.transfer.getObjectTimingPenalty();
    }

    private static ProductionRig productionRig(int... spriteXs) {
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(false);
        registers.setSpeedMode(new SpeedMode(false));
        registers.put(GpuRegister.LY, 0);
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(false);
        lcdc.set(0x93);

        SpritePosition[] sprites = new SpritePosition[10];
        Ram oam = new Ram(0xfe00, 0xa0);
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new SpritePosition();
            if (i < spriteXs.length) {
                sprites[i].enable(spriteXs[i], 16, 0xfe00 + 4 * i);
            }
        }

        PixelTransfer transfer = new PixelTransfer(
                new Display(false),
                new Ram(0x8000, 0x2000),
                null,
                oam,
                lcdc,
                registers,
                false,
                new ColorPalette(0xff68),
                new ColorPalette(0xff6a),
                sprites,
                null,
                new SpeedMode(false),
                0);
        return new ProductionRig(transfer, lcdc);
    }

    private record ProductionRig(PixelTransfer transfer, Lcdc lcdc) {
    }
}
