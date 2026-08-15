package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import eu.rekawek.coffeegb.core.gpu.DmgPixelFifo;
import eu.rekawek.coffeegb.core.gpu.Fetcher;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.PixelSource.BACKGROUND;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.PixelSource.WINDOW;
import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelReplayContract.RequiredTraceSignal.WINDOW_SOURCE_DEACTIVATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ForwardDmgPixelReplayContractTest {

    private static final File HARDEST_WINDOW_ROM = new File(
            "src/test/resources/roms/mealybug/m3_lcdc_win_en_change_multiple_wx.gb");

    private static final int LCDC = 0xff40;

    private static final int WINDOW_ENABLE = 0x20;

    private static final int MAX_TRACE_TICKS = 8_000_000;

    @Test
    public void eightDotRawHandoffEmergesFromAFullPreEdgeWindowFifo() {
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.seedRawFifo(8, 1, ForwardDmgPixelPipeline.PixelSource.WINDOW);
        graph.writeVram(0x8000, 0x00);
        graph.writeVram(0x8001, 0x00);

        // The immediate source reset selects BG for the replacement flight. The eight
        // already-committed window tokens remain ordinary data; no control delay is applied.
        graph.launchTile(0, 0, ForwardDmgPixelPipeline.PixelSource.BACKGROUND);
        for (int offset = 0; offset < 8; offset++) {
            graph.tick();
            assertEquals("pre-edge FIFO token " + offset,
                    ForwardDmgPixelPipeline.PixelSource.WINDOW,
                    graph.lastPoppedSource());
        }
        graph.tick();
        assertEquals("the replacement source reaches the raw pop boundary at +8",
                ForwardDmgPixelPipeline.PixelSource.BACKGROUND,
                graph.lastPoppedSource());

        while (graph.lcdX() < 9) {
            graph.tick();
        }
        assertEquals(ForwardDmgPixelPipeline.PixelSource.WINDOW, graph.lcdSource(7));
        assertEquals("the fixed three-dot scanout preserves the same source handoff",
                ForwardDmgPixelPipeline.PixelSource.BACKGROUND, graph.lcdSource(8));
        assertEquals(8 + ForwardDmgPixelPipeline.RAW_TO_LCD_DOTS, graph.lastLcdDot());
    }

    /**
     * Composes the external-netlist-shaped source latch with the fitted forward data path. The
     * gate trace permits one pre-edge transaction to finish in retained data latches, but no new
     * window launch after the source clears. This is a bounded contract, not a silicon timing.
     */
    @Test
    public void immediateSourceResetRetiresOneWindowFlightThenSelectsBackground() {
        DmgWindowSourceLatchCone source = activeSourceCone();
        ForwardDmgPixelPipeline graph = new ForwardDmgPixelPipeline();
        graph.writeVram(0x8010, 0xff);
        graph.writeVram(0x8011, 0xff);
        graph.writeVram(0x8000, 0x00);
        graph.writeVram(0x8001, 0x00);

        List<SourceDot> raw = new ArrayList<>();
        List<SourceDot> lcd = new ArrayList<>();

        var preEdgeSource = source.capture().inWindow()
                ? WINDOW : BACKGROUND;
        assertEquals(WINDOW, preEdgeSource);
        graph.launchTile(1, 0, preEdgeSource);
        recordTick(graph, raw, lcd); // the WINDOW flight has sampled its low byte

        int resetDot = graph.dot();
        var reset = source.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withLcdcWindowEnable(false));
        assertTrue(reset.deactivated());
        assertFalse(reset.inWindow());

        while (graph.tileFlightValid()) {
            recordTick(graph, raw, lcd);
        }
        assertEquals("the pre-edge flight still samples the WINDOW high byte", 1,
                graph.vramReadCount(0x8011));

        var nextSource = source.capture().inWindow()
                ? WINDOW : BACKGROUND;
        assertEquals("the cleared source cannot select a second WINDOW map/flight",
                BACKGROUND, nextSource);
        graph.launchTile(0, 0, nextSource);

        while (lcd.size() < 16) {
            recordTick(graph, raw, lcd);
        }

        assertEquals(1, graph.vramReadCount(0x8010));
        assertEquals(1, graph.vramReadCount(0x8000));
        assertEquals(1, graph.vramReadCount(0x8001));

        assertSourceRun(raw, WINDOW, 0, 8);
        assertSourceRun(raw, BACKGROUND, 8, 16);
        assertSourceRun(lcd, WINDOW, 0, 8);
        assertSourceRun(lcd, BACKGROUND, 8, 16);
        assertEquals("one retained flight reaches the FIFO within a finite handoff", 11,
                raw.get(8).dot() - resetDot);
        assertEquals("scanout adds only its fixed forward latency", 14,
                lcd.get(8).dot() - resetDot);
    }

    /**
     * This is deliberately not a Mealybug image pass. It executes the real ROM only to capture
     * the first causal input which a future old-state trace replay would have to deliver to the
     * candidate. The assertion stops at that boundary instead of fitting an output correction.
     */
    @Test
    public void hardestWindowRomCrossesATypedBoundaryBeforeRawTokensCanBeCompared()
            throws Exception {
        Gameboy gameboy = new GameboyConfiguration(HARDEST_WINDOW_ROM)
                .setBootstrapMode(BootstrapMode.FAST_FORWARD)
                .setGameboyType(GameboyType.DMG)
                .setMealybugDmgBlob(false)
                .setSupportBatterySave(false)
                .build();
        WindowDeactivateObservation observation;
        try {
            gameboy.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, null);
            Gpu gpu = gameboy.getGpu();
            PixelTransfer pixelMachine = physicalPixelMachine(gpu);
            observation = captureWindowPathRetirement(gameboy, gpu, pixelMachine);
        } finally {
            gameboy.closeSilently();
        }

        assertEquals("current trace anchor after FAST_FORWARD construction", 128_703L,
                observation.cpuEdgeTick());
        assertEquals(8L,
                observation.productionPathDeactivateTick() - observation.cpuEdgeTick());
        assertEquals(1, observation.line());
        assertEquals(92, observation.dot());
        assertEquals(101, observation.deactivateDot());
        assertTrue("the CPU edge must clear LCDC.5",
                (observation.lcdcBefore() & WINDOW_ENABLE) != 0);
        assertEquals("the new CPU-visible value must have LCDC.5 low", 0,
                observation.lcdcAfter() & WINDOW_ENABLE);
        assertTrue("the delayed production path was active on the FF40 source edge",
                observation.productionPathActiveOnCpuEdge());
        assertTrue("the production path must retire on the same physical scanline",
                observation.productionPathDeactivateTick() > observation.cpuEdgeTick());
        assertEquals(observation.line(), observation.deactivateLine());
        assertEquals("the falling edge precedes the next tile launch",
                Fetcher.GET_TILE_T1, observation.fetchStateOnCpuEdge());
        assertEquals(Fetcher.GET_TILE_T2, observation.fetchStateAfterCpuEdge());
        assertEquals("one complete pre-edge window row is already committed", 8,
                observation.fifoPixelsOnCpuEdge());
        assertEquals(-6, observation.positionOnCpuEdge());
        assertEquals(observation.cpuEdgeTick() - 3, observation.preEdgeWindowMapTick());
        assertEquals(0x9c00, observation.preEdgeWindowMapAddress());
        assertEquals(observation.cpuEdgeTick() - 1, observation.preEdgeWindowPushTick());

        // The +8 duration is a plausible data-path bound: eight committed window tokens
        // drain in offsets 0..7, so a replacement BG token can pop at +8. Production does
        // not currently take that path, however. It starts a fresh WINDOW transaction on
        // the reset tick, reads its map after the reset, and only selects BG eleven ticks later.
        assertEquals(observation.cpuEdgeTick() + 3,
                observation.postResetWindowMapTick());
        assertEquals(0x9c01, observation.postResetWindowMapAddress());
        assertEquals(observation.cpuEdgeTick() + 7,
                observation.postResetWindowPushTick());
        assertEquals("all eight pre-edge tokens drain before the new row is visible", 8,
                observation.preEdgeTokensDrainedAtPostResetPush());
        assertEquals(observation.cpuEdgeTick() + 11,
                observation.firstBackgroundMapTick());
        assertEquals(0x9801, observation.firstBackgroundMapAddress());

        // The pinned gate path has no clocked receiver after the FF40.D5 storage latch:
        // D5 feeds xofo, which asynchronously resets pynu/in_window. Driving the real ROM's
        // new D5 value into that cone therefore clears the source on the write edge while the
        // production fetch path remains alive for eight ticks. These cannot be the same state.
        DmgWindowSourceLatchCone sourceCone = activeSourceCone();
        var sourceEdge = sourceCone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withLcdcWindowEnable(
                        (observation.lcdcAfter() & WINDOW_ENABLE) != 0));
        assertTrue(sourceEdge.deactivated());
        assertFalse(sourceEdge.inWindow());

        ForwardDmgPixelReplayContract.UnsupportedReplaySignalException failure = assertThrows(
                ForwardDmgPixelReplayContract.UnsupportedReplaySignalException.class,
                () -> ForwardDmgPixelReplayContract.requireRepresentable(
                        WINDOW_SOURCE_DEACTIVATE));
        assertEquals(WINDOW_SOURCE_DEACTIVATE, failure.cone().signal());
        assertEquals(OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION,
                failure.cone().incompleteBehaviorBit());
        assertTrue(failure.cone().requiredCandidateInterface()
                .contains("asynchronous window-source reset"));
    }

    /** The existing enable setter cannot clear a window flight after activation. */
    @Test
    public void loweringCandidateWindowEnableDoesNotRepresentTheRecordedTransition() {
        ForwardDmgPixelPipeline candidate = new ForwardDmgPixelPipeline();
        candidate.seedRawFifo(16, 1);
        candidate.seedWindowCompareX(0);
        candidate.configureWindow(7, 0, 0);
        candidate.setWindowEnabled(true);
        candidate.writeVram(0x8000, 0x80);
        candidate.writeVram(0x8001, 0x00);

        candidate.tick(); // comparator token
        candidate.tick(); // activation
        assertTrue(candidate.windowActive());

        candidate.setWindowEnabled(false);
        for (int dot = 0; dot < 16; dot++) {
            candidate.tick();
        }
        assertTrue("setWindowEnabled is comparator input, not source deactivation",
                candidate.windowActive());
        assertFalse(candidate.windowActivationLastDot());
    }

    private static WindowDeactivateObservation captureWindowPathRetirement(
            Gameboy gameboy, Gpu gpu, PixelTransfer pixelMachine) throws Exception {
        PixelTraceSeam seam = PixelTraceSeam.open(pixelMachine);
        RecordingPpuHooks hooks = new RecordingPpuHooks();
        gpu.setDebugHooks(hooks);
        long pendingCpuEdgeTick = -1;
        long deactivateTick = -1;
        int pendingLine = -1;
        int pendingDot = -1;
        int deactivateLine = -1;
        int deactivateDot = -1;
        int pendingLcdcBefore = -1;
        int pendingLcdcAfter = -1;
        boolean pendingActive = false;
        int edgeFetchState = -1;
        int edgeFetchStateAfter = -1;
        int edgeFifoPixels = -1;
        int edgePosition = Integer.MIN_VALUE;
        long preEdgeWindowMapTick = -1;
        int preEdgeWindowMapAddress = -1;
        long preEdgeWindowPushTick = -1;
        long postResetWindowMapTick = -1;
        int postResetWindowMapAddress = -1;
        long postResetWindowPushTick = -1;
        int drainedAtPostResetPush = 0;
        int drainedSinceEdge = 0;
        long firstBackgroundMapTick = -1;
        int firstBackgroundMapAddress = -1;
        ForwardDmgPixelPipeline.PixelSource flightSource = null;

        int previousLcdc = gpu.getByte(LCDC);
        boolean previousWindowActive = pixelMachine.isWindowActive();
        for (long tick = 0; tick < MAX_TRACE_TICKS; tick++) {
            hooks.beginTick();
            int lineBefore = gpu.getLine();
            int dotBefore = gpu.getTicksInLine();
            FlightSnapshot before = seam.snapshot();
            gameboy.tick();
            FlightSnapshot after = seam.snapshot();
            int currentLcdc = gpu.getByte(LCDC);
            boolean currentWindowActive = pixelMachine.isWindowActive();

            for (int read : hooks.takeReads()) {
                if (read >= 0x9c00 && read <= 0x9fff) {
                    flightSource = ForwardDmgPixelPipeline.PixelSource.WINDOW;
                    if (pendingCpuEdgeTick < 0) {
                        preEdgeWindowMapTick = tick;
                        preEdgeWindowMapAddress = read;
                    } else if (postResetWindowMapTick < 0) {
                        postResetWindowMapTick = tick;
                        postResetWindowMapAddress = read;
                    }
                } else if (read >= 0x9800 && read <= 0x9bff) {
                    flightSource = ForwardDmgPixelPipeline.PixelSource.BACKGROUND;
                    if (pendingCpuEdgeTick >= 0 && firstBackgroundMapTick < 0) {
                        firstBackgroundMapTick = tick;
                        firstBackgroundMapAddress = read;
                    }
                }
            }

            boolean pushedTile = (before.fetchState() == Fetcher.GET_TILE_DATA_HIGH_T2
                    || before.fetchState() == Fetcher.PUSH)
                    && after.fetchState() == Fetcher.GET_TILE_T1;
            int poppedPixels = before.fifoLength() + (pushedTile ? 8 : 0)
                    - after.fifoLength();
            if (pendingCpuEdgeTick >= 0 && poppedPixels > 0
                    && postResetWindowPushTick < 0) {
                drainedSinceEdge += poppedPixels;
            }
            if (pushedTile && flightSource == ForwardDmgPixelPipeline.PixelSource.WINDOW) {
                if (pendingCpuEdgeTick < 0) {
                    preEdgeWindowPushTick = tick;
                } else if (postResetWindowPushTick < 0) {
                    postResetWindowPushTick = tick;
                    drainedAtPostResetPush = drainedSinceEdge;
                }
            }

            boolean windowEnableFell = (previousLcdc & WINDOW_ENABLE) != 0
                    && (currentLcdc & WINDOW_ENABLE) == 0;
            if (windowEnableFell && previousWindowActive) {
                pendingCpuEdgeTick = tick;
                pendingLine = lineBefore;
                pendingDot = dotBefore;
                pendingLcdcBefore = previousLcdc;
                pendingLcdcAfter = currentLcdc;
                pendingActive = true;
                edgeFetchState = before.fetchState();
                edgeFetchStateAfter = after.fetchState();
                edgeFifoPixels = before.fifoLength();
                edgePosition = before.position();
                drainedSinceEdge += Math.max(0, poppedPixels);
            }

            if (pendingCpuEdgeTick >= 0
                    && previousWindowActive
                    && !currentWindowActive
                    && gpu.getLine() == pendingLine
                    && tick - pendingCpuEdgeTick <= 16) {
                deactivateTick = tick;
                deactivateLine = gpu.getLine();
                deactivateDot = gpu.getTicksInLine();
            }

            if (deactivateTick >= 0 && firstBackgroundMapTick >= 0) {
                return new WindowDeactivateObservation(
                        pendingCpuEdgeTick,
                        deactivateTick,
                        pendingLine,
                        pendingDot,
                        deactivateLine,
                        deactivateDot,
                        pendingLcdcBefore,
                        pendingLcdcAfter,
                        pendingActive,
                        edgeFetchState,
                        edgeFetchStateAfter,
                        edgeFifoPixels,
                        edgePosition,
                        preEdgeWindowMapTick,
                        preEdgeWindowMapAddress,
                        preEdgeWindowPushTick,
                        postResetWindowMapTick,
                        postResetWindowMapAddress,
                        postResetWindowPushTick,
                        drainedAtPostResetPush,
                        firstBackgroundMapTick,
                        firstBackgroundMapAddress);
            }

            previousLcdc = currentLcdc;
            previousWindowActive = currentWindowActive;
        }
        throw new AssertionError(
                "real Mealybug trace did not expose an active-window LCDC.5 deactivation "
                        + "within " + MAX_TRACE_TICKS + " ticks");
    }

    private static PixelTransfer physicalPixelMachine(Gpu gpu) throws Exception {
        Field field;
        try {
            field = Gpu.class.getDeclaredField("pixelMachine");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(
                    "Gpu.pixelMachine changed; update the raw-token shadow seam explicitly", e);
        }
        assertEquals("the shadow seam must still be the shifted physical machine",
                PixelTransfer.class, field.getType());
        if (!field.trySetAccessible()) {
            throw new AssertionError("Gpu.pixelMachine is no longer reflectively observable");
        }
        Object value = field.get(gpu);
        if (!(value instanceof PixelTransfer transfer)) {
            throw new AssertionError("Gpu.pixelMachine did not contain PixelTransfer");
        }
        return transfer;
    }

    private static DmgWindowSourceLatchCone activeSourceCone() {
        DmgWindowSourceLatchCone cone = new DmgWindowSourceLatchCone();
        cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .withMatch(true).onMatchEdge());
        var activated = cone.step(DmgWindowSourceLatchCone.Inputs.idleEnabled()
                .onStartEdge());
        if (!activated.inWindow()) {
            throw new AssertionError("window-source audit fixture did not activate");
        }
        return cone;
    }

    private static void recordTick(
            ForwardDmgPixelPipeline graph, List<SourceDot> raw, List<SourceDot> lcd) {
        int previousPopDot = graph.lastFifoPopDot();
        int previousLcdX = graph.lcdX();
        graph.tick();
        if (graph.lastFifoPopDot() != previousPopDot) {
            raw.add(new SourceDot(graph.lastPoppedSource(), graph.lastFifoPopDot()));
        }
        for (int x = previousLcdX; x < graph.lcdX(); x++) {
            lcd.add(new SourceDot(graph.lcdSource(x), graph.lastLcdDot()));
        }
    }

    private static void assertSourceRun(
            List<SourceDot> trace,
            ForwardDmgPixelPipeline.PixelSource expected,
            int from,
            int to) {
        assertTrue("trace is shorter than the asserted source run", trace.size() >= to);
        for (int i = from; i < to; i++) {
            assertEquals("source token " + i, expected, trace.get(i).source());
        }
    }

    private record SourceDot(ForwardDmgPixelPipeline.PixelSource source, int dot) {
    }

    private record WindowDeactivateObservation(
            long cpuEdgeTick,
            long productionPathDeactivateTick,
            int line,
            int dot,
            int deactivateLine,
            int deactivateDot,
            int lcdcBefore,
            int lcdcAfter,
            boolean productionPathActiveOnCpuEdge,
            int fetchStateOnCpuEdge,
            int fetchStateAfterCpuEdge,
            int fifoPixelsOnCpuEdge,
            int positionOnCpuEdge,
            long preEdgeWindowMapTick,
            int preEdgeWindowMapAddress,
            long preEdgeWindowPushTick,
            long postResetWindowMapTick,
            int postResetWindowMapAddress,
            long postResetWindowPushTick,
            int preEdgeTokensDrainedAtPostResetPush,
            long firstBackgroundMapTick,
            int firstBackgroundMapAddress) {
    }

    private record FlightSnapshot(
            int position,
            boolean windowActive,
            boolean windowBeingFetched,
            boolean windowDisplay,
            boolean delayedWindowWrite,
            int fetchState,
            int fifoLength,
            int fifoPops,
            int lcdCommits,
            int delaySize,
            int tileMapAddress,
            int windowTileX,
            boolean data2Pending) {
    }

    private static final class PixelTraceSeam {

        private final PixelTransfer transfer;

        private final Fetcher fetcher;

        private final DmgPixelFifo fifo;

        private final Field delaySize;

        private final Field tileMapAddress;

        private final Field windowTileX;

        private final Field data2Pending;

        private PixelTraceSeam(
                PixelTransfer transfer,
                Fetcher fetcher,
                DmgPixelFifo fifo,
                Field delaySize,
                Field tileMapAddress,
                Field windowTileX,
                Field data2Pending) {
            this.transfer = transfer;
            this.fetcher = fetcher;
            this.fifo = fifo;
            this.delaySize = delaySize;
            this.tileMapAddress = tileMapAddress;
            this.windowTileX = windowTileX;
            this.data2Pending = data2Pending;
        }

        static PixelTraceSeam open(PixelTransfer transfer) throws Exception {
            Field fetcherField = requiredField(PixelTransfer.class, "fetcher", Fetcher.class);
            Field fifoField = requiredField(PixelTransfer.class, "fifo",
                    eu.rekawek.coffeegb.core.gpu.PixelFifo.class);
            Object fetcher = fetcherField.get(transfer);
            Object fifo = fifoField.get(transfer);
            if (!(fetcher instanceof Fetcher typedFetcher)) {
                throw new AssertionError("PixelTransfer.fetcher is not Fetcher");
            }
            if (!(fifo instanceof DmgPixelFifo typedFifo)) {
                throw new AssertionError("physical DMG PixelTransfer fifo changed type");
            }
            return new PixelTraceSeam(
                    transfer,
                    typedFetcher,
                    typedFifo,
                    requiredField(DmgPixelFifo.class, "delaySize", int.class),
                    requiredField(Fetcher.class, "tileMapAddress", int.class),
                    requiredField(Fetcher.class, "windowTileX", int.class),
                    requiredField(Fetcher.class, "data2Pending", boolean.class));
        }

        FlightSnapshot snapshot() throws IllegalAccessException {
            DmgPixelFifo.RuntimeState runtime = fifo.captureRuntimeState();
            return new FlightSnapshot(
                    transfer.getPosition(),
                    transfer.isWindowActive(),
                    transfer.isWindowBeingFetched(),
                    transfer.isWindowDisplayVisible(),
                    transfer.hasDelayedWindowDisplayWrite(),
                    fetcher.getState(),
                    fifo.getLength(),
                    runtime.linePixels(),
                    runtime.outCount(),
                    delaySize.getInt(fifo),
                    tileMapAddress.getInt(fetcher),
                    windowTileX.getInt(fetcher),
                    data2Pending.getBoolean(fetcher));
        }

        private static Field requiredField(Class<?> owner, String name, Class<?> type) {
            Field field;
            try {
                field = owner.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new AssertionError(owner.getSimpleName() + "." + name
                        + " changed; update the source-tag trace seam", e);
            }
            if (field.getType() != type) {
                throw new AssertionError(owner.getSimpleName() + "." + name
                        + " changed type from " + type.getSimpleName()
                        + " to " + field.getType().getSimpleName());
            }
            if (!field.trySetAccessible()) {
                throw new AssertionError(owner.getSimpleName() + "." + name
                        + " is no longer reflectively observable");
            }
            return field;
        }
    }

    private static final class RecordingPpuHooks implements DebugHooks {

        private final List<Integer> reads = new ArrayList<>();

        void beginTick() {
            reads.clear();
        }

        List<Integer> takeReads() {
            return List.copyOf(reads);
        }

        @Override
        public boolean requiresMemoryAccessHooks() {
            return false;
        }

        @Override
        public boolean requiresPpuMemoryAccessHooks() {
            return true;
        }

        @Override
        public void onMemoryAccess(
                DebugAddressSpace addressSpace,
                TraceSource source,
                DebugMemoryAccess access,
                int address,
                int value) {
            if (addressSpace == DebugAddressSpace.VIDEO_RAM
                    && source == TraceSource.PPU
                    && access == DebugMemoryAccess.READ) {
                reads.add(address);
            }
        }

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }
}
