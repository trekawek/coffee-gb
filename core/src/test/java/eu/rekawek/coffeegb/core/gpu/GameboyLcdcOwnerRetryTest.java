package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.*;
import eu.rekawek.coffeegb.core.hardware.*;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.lang.reflect.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

/** The retry is a scheduling hint; all state and bus permissions are proved afresh. */
public class GameboyLcdcOwnerRetryTest {
    private static Method method(String name) throws Exception {
        Method m = Gameboy.class.getDeclaredMethod(name, long.class);
        m.setAccessible(true);
        return m;
    }

    private static Field hint() throws Exception {
        Field f = Gameboy.class.getDeclaredField("performanceLcdcWriteReplayRetry");
        f.setAccessible(true);
        return f;
    }

    @Test public void writePacketsRetryAcrossUndecodedInstructionBoundaries() throws Exception {
        int retries = 0;
        for (HardwareProfile profile : new HardwareProfile[]{HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (int dot : new int[]{20, 68, 120, 240, 300}) {
                for (int budget : new int[]{8, 9, 23, 53, 54}) {
                    try (Gameboy a = GameboyLcdcWritePacketTest.session(profile);
                         Gameboy b = GameboyLcdcWritePacketTest.session(profile)) {
                        GameboyLcdcWritePacketTest.prepare(a, dot);
                        GameboyLcdcWritePacketTest.prepare(b, dot);
                        int seed = (int) method("tryPerformanceLcdcWriteReplayEpoch").invoke(b, (long) budget);
                        assertTrue(seed > 0);
                        for (int t = 0; t < seed; t++) a.tick();
                        assertStateEquals("seed", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
                        if (hint().getBoolean(b) && !b.getCpu().hasPendingLcdcWriteReplayStore()) {
                            long before = b.getPerformanceLcdcWriteReplayTicks();
                            int next = (int) method("tryPerformanceEpochOrDetailedReplay").invoke(b, 54L);
                            assertTrue("retry cannot exceed its budget", next <= 54);
                            // A fresh peripheral/checkpoint boundary may reject the hint.
                            if (next <= 0) assertFalse(hint().getBoolean(b));
                            for (int t = 0; t < Math.max(0, next); t++) a.tick();
                            assertStateEquals("retry at " + dot + "/" + budget,
                                    a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
                            if (b.getPerformanceLcdcWriteReplayTicks() > before) retries++;
                            for (int t = 0; t < 81; t++) { a.tick(); b.tick(); }
                            assertStateEquals("canonical tail", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
                        }
                    }
                }
            }
        }
        assertTrue("must exercise a retry without a decoded LCDC store", retries > 0);
    }

    @Test public void staleHintExpiresWithoutWideningBusOrLifecyclePermissions() throws Exception {
        int[][] programs = {
                {0x00}, {0xf0, 0x41}, {0xf0, 0x0f}, {0xf0, 0x26}, {0xf0, 0x00},
                {0x3e, 0x08, 0xe0, 0x42}, {0xfb, 0xf3}, {0x76},
        };
        for (int[] program : programs) {
            try (Gameboy a = session(program); Gameboy b = session(program)) {
                GameboyLcdcWritePacketTest.prepare(a, 300);
                GameboyLcdcWritePacketTest.prepare(b, 300);
                hint().setBoolean(b, true);
                int next = (int) method("tryPerformanceEpochOrDetailedReplay").invoke(b, 54L);
                for (int t = 0; t < Math.max(0, next); t++) a.tick();
                assertStateEquals("stale selection", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
                assertFalse("a no-write or rejected packet expires the selection", hint().getBoolean(b));
                for (int t = 0; t < 81; t++) { a.tick(); b.tick(); }
                assertStateEquals("bus/lifecycle continuation", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
            }
        }
    }

    @Test public void restoreDiscardsTheHintAndRetainsCanonicalState() throws Exception {
        try (Gameboy a = GameboyLcdcWritePacketTest.session(HardwareProfileRegistry.CGB);
             Gameboy b = GameboyLcdcWritePacketTest.session(HardwareProfileRegistry.CGB)) {
            GameboyLcdcWritePacketTest.prepare(a, 300);
            GameboyLcdcWritePacketTest.prepare(b, 300);
            var state = b.captureStateWithoutTimeSource();
            hint().setBoolean(b, true);
            b.restoreStateSilently(state);
            a.restoreStateSilently(a.captureStateWithoutTimeSource());
            assertFalse(hint().getBoolean(b));
            for (int t = 0; t < 128; t++) { a.tick(); b.tick(); }
            assertStateEquals("restored tail", a.captureStateWithoutTimeSource(), b.captureStateWithoutTimeSource());
        }
    }

    private static Gameboy session(int[] program) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3; image[0x101] = 0x50; image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        int pc = 0x150;
        for (int opcode : program) image[pc++] = (byte) opcode;
        image[pc++] = (byte) 0xc3; image[pc++] = 0x50; image[pc] = 1;
        return new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L).setSupportBatterySave(false).build();
    }
}
