package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** The existing exact mode-3 cursor remains batchable after an OAM copy releases its bus. */
public class PerformanceReleasedOamSteadyIntegrationTest {
    @Test
    public void releasedTransferRetainsExactHramCpuAndRasterAcrossRestoredPrefixes() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (int fineScroll : new int[]{0, 3, 7}) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    prepareBeforeTransfer(scalar, fineScroll);
                    prepareBeforeTransfer(bulk, fineScroll);
                    // Captures deliberately precede mode-3 entry: an in-flight cursor is
                    // materialized by capture and must never be fabricated by a test restore.
                    var scalarBase = scalar.captureStateWithoutTimeSource();
                    var bulkBase = bulk.captureStateWithoutTimeSource();
                    for (int ticks : new int[]{1, 2, 7, 8, 23, 54}) {
                        scalar.restoreStateSilently(scalarBase);
                        bulk.restoreStateSilently(bulkBase);
                        startTransferAndAdvance(scalar, 180);
                        startTransferAndAdvance(bulk, 180);
                        assertFalse(dma(bulk).isTransferInProgress());
                        assertFalse(dma(bulk).ownsOamForPpu());
                        Gpu gpu = bulk.getGpu();
                        assertTrue("release must preserve the exact deferred cursor",
                                gpu.isPerformanceSteadyCursorActive());
                        assertTrue("the full prefix must fit the existing exact raster proof",
                                gpu.performanceSteadyQuietSpanLimit() >= ticks);
                        gpu.setPerformanceScanlineEnabled(true);
                        assertEquals("ordinary CPU rights remain unavailable for this cursor", 0,
                                gpu.performanceEpochSpanLimit(ticks));
                        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                        bulk.setPerformanceDiagnostics(diagnostics);
                        bulk.setPerformanceBatchingEnabled(true);
                        scalar.setPerformanceBatchingEnabled(false);
                        assertEquals(0, scalar.runTicks(ticks));
                        assertEquals(0, bulk.runTicks(ticks));
                        String label = profile.id() + " SCX=" + fineScroll + " prefix=" + ticks;
                        long quiet = diagnostics.snapshot().subsystemTicks().get(
                                PerformanceDiagnostics.Subsystem.PPU_QUIET_STEADY);
                        if (ticks >= 8) {
                            assertTrue(label + " must positively use exact quiet continuation: "
                                    + diagnostics.snapshot(), quiet > 0);
                        }
                        assertEquals(label + " retains HRAM execution", 0xff80,
                                bulk.getCpu().getRegisters().getPC() & 0xfff0);
                        assertStateEquals(label, scalar.captureStateWithoutTimeSource(),
                                bulk.captureStateWithoutTimeSource());
                        // A subsequent real PPU write and scalar tail expose every saved
                        // Fetcher/FIFO, register-latch, OAM-reader and output-ring field.
                        scalar.getGpu().setByteFromCpu(0xff43, fineScroll ^ 7);
                        bulk.getGpu().setByteFromCpu(0xff43, fineScroll ^ 7);
                        bulk.setPerformanceBatchingEnabled(false);
                        assertEquals(0, scalar.runTicks(100));
                        assertEquals(0, bulk.runTicks(100));
                        assertStateEquals(label + " write continuation",
                                scalar.captureStateWithoutTimeSource(),
                                bulk.captureStateWithoutTimeSource());
                    }
                }
            }
        }
    }

    @Test
    public void oneOwnerCallCrossesReleaseAndRecoversTheRemainingSteadyLine() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                prepareBeforeTransfer(scalar, 5);
                prepareBeforeTransfer(bulk, 5);
                startTransferAndAdvance(scalar, 150);
                startTransferAndAdvance(bulk, 150);
                assertTrue(dma(bulk).isTransferInProgress());
                assertTrue(bulk.getGpu().isPerformanceSteadyCursorActive());
                PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                bulk.setPerformanceDiagnostics(diagnostics);
                bulk.setPerformanceBatchingEnabled(true);
                scalar.setPerformanceBatchingEnabled(false);
                assertEquals(0, scalar.runTicks(80));
                assertEquals(0, bulk.runTicks(80));
                assertFalse(dma(bulk).isTransferInProgress());
                assertEquals(Mode.PixelTransfer, bulk.getGpu().getMode());
                var work = diagnostics.snapshot().subsystemTicks();
                assertTrue("the call begins in the already-owned quiet plane",
                        work.get(PerformanceDiagnostics.Subsystem.PPU_QUIET_DURING_DMA) > 0);
                assertTrue("the same call must recover after scalar ownership release",
                        work.get(PerformanceDiagnostics.Subsystem.PPU_QUIET_STEADY) > 0);
                assertStateEquals(profile.id() + " one-call release",
                        scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
            }
        }
    }

    private static final HardwareProfile[] PROFILES = {
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0};

    private static void prepareBeforeTransfer(Gameboy gameboy, int fineScroll) throws Exception {
        gameboy.setPerformanceBatchingEnabled(false);
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xff26, 0);
        bus.setByte(0xff43, fineScroll);
        bus.setByte(0xff4a, 0xff);
        for (int i = 0; i < 160; i++) bus.setByte(0xc000 + i, 0);
        // Register-only work fetched from HRAM continues across the WRAM DMA ownership seam.
        int[] hramLoop = {0x04, 0x0c, 0x18, 0xfc}; // INC B; INC C; JR FF80.
        for (int i = 0; i < hramLoop.length; i++) bus.setByte(0xff80 + i, hramLoop[i]);
        gameboy.getCpu().getRegisters().setPC(0xff80);
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        var onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertEquals(true, onStop.invoke(gameboy.getSpeedMode()));
        advanceTo(gameboy, 0, 300);
    }

    private static void startTransferAndAdvance(Gameboy gameboy, int dot) {
        gameboy.getAddressSpace().setByte(0xff46, 0xc0);
        advanceTo(gameboy, 1, dot);
    }

    private static void advanceTo(Gameboy gameboy, int line, int dot) {
        for (int budget = 2 * 70_224; budget > 0; budget--) {
            if (gameboy.getGpu().getLine() == line && gameboy.getGpu().getTicksInLine() == dot) return;
            gameboy.tick();
        }
        fail("fixture did not reach line=" + line + " dot=" + dot);
    }

    private static Gameboy session(HardwareProfile profile) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .build();
    }

    private static Dma dma(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("dma");
        field.setAccessible(true);
        return (Dma) field.get(gameboy);
    }
}
