package eu.rekawek.coffeegb.core.experimental.ppu_pipeline;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;

import static eu.rekawek.coffeegb.core.experimental.ppu_pipeline.ForwardDmgPixelPipeline.OUTSIDE_ACTIVE_WINDOW_SOURCE_DEACTIVATION;
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
            Gameboy gameboy, Gpu gpu, PixelTransfer pixelMachine) {
        long pendingCpuEdgeTick = -1;
        int pendingLine = -1;
        int pendingDot = -1;
        int pendingLcdcBefore = -1;
        int pendingLcdcAfter = -1;
        boolean pendingActive = false;

        int previousLcdc = gpu.getByte(LCDC);
        boolean previousWindowActive = pixelMachine.isWindowActive();
        for (long tick = 0; tick < MAX_TRACE_TICKS; tick++) {
            int lineBefore = gpu.getLine();
            int dotBefore = gpu.getTicksInLine();
            gameboy.tick();
            int currentLcdc = gpu.getByte(LCDC);
            boolean currentWindowActive = pixelMachine.isWindowActive();

            boolean windowEnableFell = (previousLcdc & WINDOW_ENABLE) != 0
                    && (currentLcdc & WINDOW_ENABLE) == 0;
            if (windowEnableFell && previousWindowActive) {
                pendingCpuEdgeTick = tick;
                pendingLine = lineBefore;
                pendingDot = dotBefore;
                pendingLcdcBefore = previousLcdc;
                pendingLcdcAfter = currentLcdc;
                pendingActive = true;
            }

            if (pendingCpuEdgeTick >= 0
                    && previousWindowActive
                    && !currentWindowActive
                    && gpu.getLine() == pendingLine
                    && tick - pendingCpuEdgeTick <= 16) {
                return new WindowDeactivateObservation(
                        pendingCpuEdgeTick,
                        tick,
                        pendingLine,
                        pendingDot,
                        gpu.getLine(),
                        gpu.getTicksInLine(),
                        pendingLcdcBefore,
                        pendingLcdcAfter,
                        pendingActive);
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

    private record WindowDeactivateObservation(
            long cpuEdgeTick,
            long productionPathDeactivateTick,
            int line,
            int dot,
            int deactivateLine,
            int deactivateDot,
            int lcdcBefore,
            int lcdcAfter,
            boolean productionPathActiveOnCpuEdge) {
    }
}
