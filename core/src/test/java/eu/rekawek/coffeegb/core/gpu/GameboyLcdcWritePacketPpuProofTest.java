package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Method;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** Independent proof of all LCDC low-bit writes under the staged queued-write owner. */
public class GameboyLcdcWritePacketPpuProofTest {
    private static final HardwareProfile[] PROFILES = {HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0};

    @Test
    public void everyLowBitWithSelectedSpritesAndBackgroundOrWindowMatchesScalar() throws Exception {
        boolean[] multiWrite = new boolean[7];
        for (HardwareProfile profile : PROFILES) {
            for (int bit = 0; bit < 7; bit++) {
                for (boolean window : new boolean[]{false, true}) {
                    int first = window ? 0xb3 : 0x93;
                    int second = first ^ (1 << bit);
                    for (int dot : new int[]{20, 120, 240}) {
                        for (int budget : new int[]{8, 23, 54}) {
                            // Fresh sessions ensure no restored mutable array can change the
                            // saved entry used by another budget in this matrix.
                            try (Gameboy expected = session(profile, first, second);
                                    Gameboy actual = session(profile, first, second)) {
                                prepare(expected, 2, dot);
                                prepare(actual, 2, dot);
                                if (dot >= 120) {
                                    assertTrue("selected sprite fixture", timing(actual).hasObjectsOnLine());
                                    assertFalse("fixture starts from real detailed rendering", actual.getGpu().isPerformanceScanlineCursorActive());
                                }
                                expected.restoreStateSilently(expected.captureStateWithoutTimeSource());
                                actual.restoreStateSilently(actual.captureStateWithoutTimeSource());
                                // Restore conservatively dirties the derived STAT evaluator.
                                // Give both machines the same real dot before owner admission.
                                expected.tick(); actual.tick();
                                expected.getGpu().setPerformanceScanlineEnabled(true);
                                actual.getGpu().setPerformanceScanlineEnabled(true);
                                long writes = actual.getPerformanceLcdcWriteReplayWrites();
                                int elapsed = packet(actual, budget);
                                assertTrue("positive low-bit packet bit=" + bit + " dot=" + dot, elapsed > 0);
                                for (int t = 0; t < elapsed; t++) expected.tick();
                                String label = profile.id() + " bit=" + bit + " window=" + window
                                        + " dot=" + dot + " budget=" + budget;
                                same(label + " packet", expected, actual);
                                if (budget == 54 && elapsed == 54) {
                                    assertTrue(label + " must contain multiple actual write dots",
                                            actual.getPerformanceLcdcWriteReplayWrites() - writes >= 3);
                                    multiWrite[bit] = true;
                                }
                                // Restore the complete state after replay and verify later live
                                // fetch, output queues, mode handoffs and MMIO readback too.
                                expected.restoreStateSilently(expected.captureStateWithoutTimeSource());
                                actual.restoreStateSilently(actual.captureStateWithoutTimeSource());
                                for (int t = 0; t < 300; t++) { expected.tick(); actual.tick(); }
                                same(label + " restored continuation", expected, actual);
                                readback(label, expected, actual);
                            }
                        }
                    }
                }
            }
        }
        for (int bit = 0; bit < multiWrite.length; bit++) assertTrue("positive multiple-write coverage bit=" + bit, multiWrite[bit]);
    }

    @Test
    public void tileDataSelectBothPolaritiesCrossLiveFetchesInBothCpuPhases() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (boolean window : new boolean[]{false, true}) {
                int first = window ? 0xb3 : 0x93;
                for (boolean high : new boolean[]{false, true}) {
                    for (int phase = 0; phase < 2; phase++) {
                        try (Gameboy expected = session(profile, first, first ^ 0x10);
                                Gameboy actual = session(profile, first, first ^ 0x10)) {
                            prepare(expected, 2, 84); prepare(actual, 2, 84);
                            expected.getGpu().setPerformanceScanlineEnabled(false);
                            actual.getGpu().setPerformanceScanlineEnabled(false);
                            boolean found = false;
                            // The 28-dot write loop shifts by eight dots on each 456-dot
                            // scanline. Search four lines so both write polarities and CPU
                            // phases can coincide with an already active window fetch.
                            for (int dots = 0; dots < 4 * 456; dots++) {
                                Cpu cpu = actual.getCpu();
                                PixelTransfer machine = timing(actual);
                                if (actual.getGpu().getMode() == Mode.PixelTransfer
                                        && machine.getPosition() >= 0 && machine.getPosition() < 144
                                        && machine.hasObjectsOnLine()
                                        && machine.isWindowBeingFetched() == window
                                        && cpu.hasPendingLcdcWriteReplayStore()
                                        && (((cpu.getRegisters().getA() & 0x10) != 0) == high)
                                        && ((cpu.getRegisters().getA() ^ actual.getGpu().getLcdcValueForCore()) & 0x10) != 0
                                        && ((Integer) field(cpu, "clockCycle")) == phase) {
                                    found = true;
                                    if (window) assertTrue("active window fetch fixture", machine.isWindowBeingFetched());
                                    else assertFalse("active background fetch fixture", machine.isWindowBeingFetched());
                                    break;
                                }
                                expected.tick(); actual.tick();
                            }
                            assertTrue("find live fetch polarity=" + high + " phase=" + phase + " window=" + window, found);
                            expected.getGpu().setPerformanceScanlineEnabled(true);
                            actual.getGpu().setPerformanceScanlineEnabled(true);
                            assertFalse(actual.getGpu().isPerformanceScanlineCursorActive());
                            long writes = actual.getPerformanceLcdcWriteReplayWrites();
                            int elapsed = packet(actual, 54);
                            assertEquals(54, elapsed);
                            assertTrue(actual.getPerformanceLcdcWriteReplayWrites() - writes >= 3);
                            for (int t = 0; t < elapsed; t++) expected.tick();
                            String label = profile.id() + " live bit4 high=" + high + " phase=" + phase + " window=" + window;
                            same(label, expected, actual);
                            for (int t = 0; t < 300; t++) { expected.tick(); actual.tick(); }
                            same(label + " continuation", expected, actual);
                            readback(label, expected, actual);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void exactLineAndDotRailsRemainClosedAndShortTailNeverPartiallyCommits() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (int line : new int[]{0, 1, 142, 143}) {
                for (int dot : new int[]{12, 13, 432, 439, 440}) {
                    try (Gameboy expected = session(profile, 0x93, 0xb3);
                            Gameboy actual = session(profile, 0x93, 0xb3)) {
                        prepare(expected, line, dot); prepare(actual, line, dot);
                        boolean admittedLine = line >= 1 && line <= 152;
                        int horizon = admittedLine && dot >= 13 && dot < 440 ? Math.min(54, 440 - dot) : 0;
                        assertEquals(profile.id() + " proof line=" + line + " dot=" + dot,
                                horizon, actual.getGpu().performanceNativeCgbLcdcWriteReplaySpanLimit(54));
                        var before = actual.captureStateWithoutTimeSource();
                        long writes = actual.getPerformanceLcdcWriteReplayWrites();
                        int elapsed = packet(actual, 54);
                        if (horizon < 8) {
                            assertEquals(0, elapsed);
                            assertEquals(writes, actual.getPerformanceLcdcWriteReplayWrites());
                            assertStateEquals("rejected rail is inert", before, actual.captureStateWithoutTimeSource());
                        } else {
                            assertEquals(horizon, elapsed);
                            for (int t = 0; t < elapsed; t++) expected.tick();
                            same("accepted rail line=" + line + " dot=" + dot, expected, actual);
                            assertTrue(actual.getGpu().getTicksInLine() <= 440);
                        }
                    }
                }
            }
        }
    }

    private static int packet(Gameboy gameboy, int budget) throws Exception {
        Method owner = Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch", long.class);
        owner.setAccessible(true);
        return (int) owner.invoke(gameboy, (long) budget);
    }

    private static void same(String label, Gameboy expected, Gameboy actual) throws Exception {
        assertStateEquals(label, expected.captureStateWithoutTimeSource(), actual.captureStateWithoutTimeSource());
    }

    private static void readback(String label, Gameboy expected, Gameboy actual) {
        for (int address : new int[]{0xff40, 0xff41, 0xff44, 0xff0f}) {
            assertEquals(label + " readback " + address, expected.getAddressSpace().getByte(address), actual.getAddressSpace().getByte(address));
        }
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static PixelTransfer timing(Gameboy gameboy) throws Exception {
        return (PixelTransfer) field(gameboy.getGpu(), "pixelTransferPhase");
    }

    private static void prepare(Gameboy gameboy, int line, int dot) throws Exception {
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.getAddressSpace().setByte(0xff26, 0);
        gameboy.getAddressSpace().setByte(0xff41, 0);
        gameboy.getAddressSpace().setByte(0xff45, 255);
        gameboy.getAddressSpace().setByte(0xff0f, 0);
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        var stop = SpeedMode.class.getDeclaredMethod("onStop"); stop.setAccessible(true);
        assertEquals(true, stop.invoke(gameboy.getSpeedMode()));
        Gpu gpu = gameboy.getGpu();
        gpu.setPerformanceScanlineEnabled(false);
        // Retain real selected sprites and fetch state before the tested packet. Also wait
        // past the suppressed first LCD-enable frame so the display buffer carries output.
        boolean sawVblank = false;
        for (int budget = 160_000; budget > 0; budget--) {
            sawVblank |= gpu.getLine() == 144;
            if (sawVblank && gpu.getLine() == line && gpu.getTicksInLine() == dot) {
                gpu.setPerformanceScanlineEnabled(true);
                return;
            }
            gameboy.tick();
        }
        fail("fixture phase line=" + line + " dot=" + dot);
    }

    private static Gameboy session(HardwareProfile profile, int first, int second) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3; image[0x101] = 0x50; image[0x102] = 1; image[0x143] = (byte) 0x80;
        int[] loop = {0x3e, first, 0xe0, 0x40, 0x3e, second, 0xe0, 0x40, 0xc3, 0x50, 1};
        for (int i = 0; i < loop.length; i++) image[0x150 + i] = (byte) loop[i];
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L).setSupportBatterySave(false).build();
        Gpu gpu = gameboy.getGpu();
        gpu.setByte(0xff40, 0);
        for (int bank = 0; bank < 2; bank++) {
            gpu.setByte(0xff4f, bank);
            for (int address = 0x8000; address < 0xa000; address++) {
                gpu.setByte(address, (address * 37 ^ address >>> 3 ^ 0x5a ^ bank * 0x3b) & 255);
            }
        }
        gpu.setByte(0xff4f, 0);
        for (int i = 0; i < 40; i++) {
            gpu.setByte(0xfe00 + i * 4, i < 10 ? 16 : 0);
            gpu.setByte(0xfe01 + i * 4, 16 + (i % 10) * 12);
            gpu.setByte(0xfe02 + i * 4, 2 + i);
            gpu.setByte(0xfe03 + i * 4, (i & 7) | ((i & 1) << 7));
        }
        gpu.setByte(0xff42, 5); gpu.setByte(0xff43, 3);
        gpu.setByte(0xff4a, 0); gpu.setByte(0xff4b, 7);
        gpu.setByte(0xff40, first);
        return gameboy;
    }
}
