package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** A real native LCDC capture resolves on the first detailed dot; only a proven suffix batches. */
public class PerformancePostDotPpuRecoveryTest {
    @Test
    public void pendingWindowWriteRecoversIdleHblankAfterFirstDotAcrossRestore() throws Exception {
        checkPendingCapture(Mode.HBlank);
    }

    @Test
    public void pendingWindowWriteRecoversMode2AfterFirstDotAcrossRestore() throws Exception {
        checkPendingCapture(Mode.OamSearch);
    }

    @Test
    public void unresolvedDetailedMode3KeepsTheCanonicalDotLoop() throws Exception {
        checkPendingCapture(Mode.PixelTransfer);
    }

    private static void checkPendingCapture(Mode expectedMode) throws Exception {
        boolean recoverable = expectedMode != Mode.PixelTransfer;
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (int phase = 0; phase < 2; phase++) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    int startDot = (expectedMode == Mode.HBlank ? 300
                            : expectedMode == Mode.OamSearch ? 20 : 120) + phase;
                    prepare(scalar, startDot - 1);
                    prepare(bulk, startDot - 1);
                    var savedScalar = scalar.captureStateWithoutTimeSource();
                    var savedBulk = bulk.captureStateWithoutTimeSource();
                    for (int ticks : new int[]{8, 23, 54}) {
                        scalar.restoreStateSilently(savedScalar);
                        bulk.restoreStateSilently(savedBulk);
                        // Restore invalidates the derived STAT cache. Settle both owners before
                        // issuing the new CPU transaction, so its pending capture is still live
                        // at epoch entry rather than consumed by a restore-recovery scalar tick.
                        scalar.tick();
                        bulk.tick();
                        scalar.setPerformanceBatchingEnabled(false);
                        bulk.setPerformanceBatchingEnabled(true);
                        scalar.getGpu().setPerformanceScanlineEnabled(true);
                        bulk.getGpu().setPerformanceScanlineEnabled(true);
                        int before = scalar.getGpu().getByte(0xff40);
                        int written = before ^ 0x20;
                        for (Gameboy gameboy : new Gameboy[]{scalar, bulk}) {
                            Gpu gpu = gameboy.getGpu();
                            assertEquals(expectedMode, gpu.getMode());
                            gpu.setByteFromCpu(0xff40, written);
                            assertEquals("CPU readback precedes the PPU capture", written,
                                    gpu.getByte(0xff40));
                            assertEquals("the delayed window bit must still await its first dot",
                                    before & 0x20, lcdc(gpu).get() & 0x20);
                        }
                        Gpu gpu = bulk.getGpu();
                        String label = profile.id() + " mode=" + expectedMode
                                + " phase=" + phase + " span=" + ticks;
                        assertStateEquals(label + " identical pending transaction entry",
                                scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
                        assertTrue(bulk.getCpu().performanceEpochEntryEligible());
                        assertEquals("pending capture must reject an ordinary GPU packet", 0,
                                gpu.performanceEpochSpanLimit(ticks));
                        assertEquals("the detailed owner must retain the whole prefix", ticks,
                                gpu.performanceNativeCgbDetailedReplaySpanLimit(ticks));
                        assertEquals("STAT checkpoints must be outside the fixture", ticks,
                                stat(bulk).performanceSettledHaltSpanLimit(ticks));
                        if (!recoverable) {
                            assertFalse("mode 3 must use the detailed pixel machines",
                                    gpu.isPerformanceScanlineCursorActive());
                        }

                        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                        bulk.setPerformanceDiagnostics(diagnostics);
                        assertEquals(0, scalar.runTicks(ticks));
                        assertEquals(0, bulk.runTicks(ticks));
                        assertEquals(ticks, diagnostics.snapshot().ticks());
                        long recovered = diagnostics.snapshot().subsystemTicks().get(
                                PerformanceDiagnostics.Subsystem.PPU_QUIET_AFTER_REPLAY);
                        if (recoverable) {
                            assertTrue(label + " must recover a quiet suffix: " + diagnostics.snapshot(),
                                    recovered > 0);
                            assertTrue("the first dot must remain canonical", recovered < ticks);
                        } else {
                            assertEquals(label + " has no direct mode-3 cursor to recover", 0, recovered);
                            assertTrue("the negative case must still exercise detailed replay",
                                    diagnostics.snapshot().subsystemTicks().get(
                                            PerformanceDiagnostics.Subsystem.PPU_REPLAY) > 0);
                        }
                        assertStateEquals(label + " complete pending-write state",
                                scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
                        assertEquals(written & 0x20, lcdc(gpu).get() & 0x20);
                        for (int address : new int[]{0xff40, 0xff41, 0xff44, 0xff0f}) {
                            assertEquals(label + " register readback " + address,
                                    scalar.getAddressSpace().getByte(address),
                                    bulk.getAddressSpace().getByte(address));
                        }
                        // A second real CPU write and scalar continuation expose retained history,
                        // FIFO and read-mux state after the packet; both stay inside this line.
                        scalar.getGpu().setByteFromCpu(0xff40, before);
                        bulk.getGpu().setByteFromCpu(0xff40, before);
                        bulk.setPerformanceBatchingEnabled(false);
                        assertEquals(0, scalar.runTicks(80));
                        assertEquals(0, bulk.runTicks(80));
                        assertStateEquals(label + " second write and scalar continuation",
                                scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
                    }
                }
            }
        }
    }

    private static void prepare(Gameboy gameboy, int dot) throws Exception {
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.getAddressSpace().setByte(0xff26, 0);
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        var onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertEquals(true, onStop.invoke(gameboy.getSpeedMode()));
        for (int budget = 2 * 70_224; budget > 0; budget--) {
            if (gameboy.getGpu().getLine() == 1 && gameboy.getGpu().getTicksInLine() == dot) return;
            gameboy.tick();
        }
        fail("fixture did not reach the requested phase");
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

    private static Lcdc lcdc(Gpu gpu) throws Exception {
        var field = Gpu.class.getDeclaredField("lcdc");
        field.setAccessible(true);
        return (Lcdc) field.get(gpu);
    }

    private static StatRegister stat(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("statRegister");
        field.setAccessible(true);
        return (StatRegister) field.get(gameboy);
    }
}
