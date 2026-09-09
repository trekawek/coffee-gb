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

/** Pins the composition of DMA ownership, mode-2 scanning and delayed LCDC history. */
public class PerformanceOwnedMode2HistoryIntegrationTest {
    @Test
    public void ownedMode2DrainsNonSizeHistoryAcrossRestoredSpansAndRelease() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (int startDot : new int[]{20, 21}) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    prepare(scalar, startDot);
                    prepare(bulk, startDot);
                    var savedScalar = scalar.captureStateWithoutTimeSource();
                    var savedBulk = bulk.captureStateWithoutTimeSource();
                    for (int ticks : new int[]{8, 23, 54}) {
                        scalar.restoreStateSilently(savedScalar);
                        bulk.restoreStateSilently(savedBulk);
                        // Restore deliberately invalidates STAT's derived evaluation cache.
                        // Settle it on both canonical owners while eight history cells remain.
                        scalar.tick();
                        bulk.tick();
                        scalar.setPerformanceBatchingEnabled(false);
                        bulk.setPerformanceBatchingEnabled(true);
                        Gpu gpu = bulk.getGpu();
                        Lcdc history = lcdc(gpu);
                        gpu.setPerformanceScanlineEnabled(true);
                        assertEquals(Mode.OamSearch, gpu.getMode());
                        assertFalse("entry must retain non-size LCDC history",
                                history.isPerformanceQuietSpanFixedPoint());
                        assertTrue(history.isPerformanceMode2HeightStable());
                        assertFalse(history.hasPendingConflictLatches());
                        assertTrue(dma(bulk).ownsOamForPpu());
                        assertTrue("CPU lifecycle must be settled at fixture entry",
                                bulk.getCpu().performanceEpochEntryEligible());
                        assertEquals("restored STAT cache must be settled", ticks,
                                stat(bulk).performanceSettledHaltSpanLimit(ticks));
                        assertEquals("owned mode-2 plane must admit the requested prefix",
                                ticks, gpu.performanceNativeCgbOamReplayQuietSpanLimit(ticks));

                        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                        bulk.setPerformanceDiagnostics(diagnostics);
                        runPairInsideLine(scalar, bulk, ticks);
                        assertEquals(ticks, diagnostics.snapshot().ticks());
                        assertEquals("positive quiet coverage must still be inside mode 2",
                                Mode.OamSearch, gpu.getMode());
                        assertTrue(profile.id() + " dot=" + startDot + " ticks=" + ticks
                                        + " must use the mode-2 quiet DMA plane: "
                                        + diagnostics.snapshot(),
                                diagnostics.snapshot().subsystemTicks().get(
                                        PerformanceDiagnostics.Subsystem.PPU_QUIET_DURING_DMA) > 0);
                        assertStateEquals(profile.id() + " mode-2 history dot=" + startDot
                                        + " span=" + ticks,
                                scalar.captureStateWithoutTimeSource(),
                                bulk.captureStateWithoutTimeSource());

                        // This continuation crosses both mode-3 entry and DMA's exact release,
                        // but stops before the next line can choose a new scanline compositor.
                        runPairInsideLine(scalar, bulk, 300);
                        assertFalse(dma(bulk).isTransferInProgress());
                        assertFalse(dma(bulk).ownsOamForPpu());
                        assertStateEquals(profile.id() + " restored ownership release dot="
                                        + startDot + " span=" + ticks,
                                scalar.captureStateWithoutTimeSource(),
                                bulk.captureStateWithoutTimeSource());
                    }
                }
            }
        }
    }

    private static void prepare(Gameboy gameboy, int startDot) throws Exception {
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.getAddressSpace().setByte(0xff26, 0);
        for (int i = 0; i < 160; i++) {
            gameboy.getAddressSpace().setByte(0xc000 + i, (i * 37 + 0x12) & 0xff);
        }
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        var onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertEquals(true, onStop.invoke(gameboy.getSpeedMode()));
        advanceTo(gameboy, 0, 400);
        gameboy.getAddressSpace().setByte(0xff46, 0xc0);
        advanceTo(gameboy, 1, startDot - 1);
        Gpu gpu = gameboy.getGpu();
        gpu.setByte(0xff40, gpu.getByte(0xff40) ^ 0x20);
    }

    private static void advanceTo(Gameboy gameboy, int line, int dot) {
        for (int budget = 2 * 70_224; budget > 0; budget--) {
            if (gameboy.getGpu().getLine() == line && gameboy.getGpu().getTicksInLine() == dot) {
                return;
            }
            gameboy.tick();
        }
        fail("fixture did not reach line " + line + " dot " + dot);
    }

    private static void runPairInsideLine(Gameboy scalar, Gameboy bulk, int ticks) {
        long frames = scalar.runTicks(ticks);
        assertEquals("the fixture must remain inside a visible line", 0, frames);
        assertEquals(frames, bulk.runTicks(ticks));
    }

    private static Gameboy session(HardwareProfile profile) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
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

    private static Lcdc lcdc(Gpu gpu) throws Exception {
        var field = Gpu.class.getDeclaredField("lcdc");
        field.setAccessible(true);
        return (Lcdc) field.get(gpu);
    }

    private static Dma dma(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("dma");
        field.setAccessible(true);
        return (Dma) field.get(gameboy);
    }

    private static StatRegister stat(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("statRegister");
        field.setAccessible(true);
        return (StatRegister) field.get(gameboy);
    }
}
