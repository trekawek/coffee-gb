package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.util.Random;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

/** Short native register-copy intervals stay scalar without stranding later CPU work. */
public class PerformanceCaptureRecoveryTest {
    @Test
    public void repeatedLycWritesPreservePartitionsAndRestoredContinuation() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB_X2, Profile.CGB0_X2}) {
            try (Gameboy whole = session(Scenario.LYC_WRITES, profile);
                 Gameboy split = session(Scenario.LYC_WRITES, profile)) {
                whole.runTicks(70_224);
                split.runTicks(70_224);
                var saved = whole.captureStateWithoutTimeSource();
                whole.runTicks(37);
                whole.restoreStateSilently(saved);
                split.restoreStateSilently(saved);
                whole.resetPerformanceBulkCounters();
                int expectedFrames = whole.runTicks(70_224);
                int frames = 0;
                int remaining = 70_224;
                Random random = new Random(71823);
                while (remaining > 0) {
                    int ticks = Math.min(remaining, 1 + random.nextInt(64));
                    frames += split.runTicks(ticks);
                    remaining -= ticks;
                }
                assertEquals(profile + " frames", expectedFrames, frames);
                assertStateEquals(profile + " restored write partitions",
                        whole.captureStateWithoutTimeSource(), split.captureStateWithoutTimeSource());
                assertTrue(profile + " recurring captures stranded ordinary work",
                        whole.getPerformanceEpochTicks() > 35_112);
            }
        }
    }

    @Test
    public void finiteCpuWrittenCaptureBurstReturnsToScalarEquivalentQuietWork() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB_X2, Profile.CGB0_X2}) {
            try (Gameboy scalar = finiteBurst(profile); Gameboy batched = finiteBurst(profile)) {
                scalar.setPerformanceBatchingEnabled(false);
                // STOP's established 0x20000 CPU-clock speed-switch delay precedes the burst.
                assertEquals(scalar.runTicks(100_000), batched.runTicks(100_000));
                assertEquals("CPU must finish all 256 writes", 0x5a,
                        batched.getAddressSpace().getByte(0xff80));
                assertStateEquals(profile + " completed burst CPU",
                        scalar.getCpu().captureState(), batched.getCpu().captureState());
                for (int address : new int[]{0xff41, 0xff44, 0xff45, 0xff0f,
                        0xffff, 0xff04, 0xff05, 0xff06, 0xff07, 0xff80}) {
                    assertEquals(profile + " completed burst register " + address,
                            scalar.getAddressSpace().getByte(address),
                            batched.getAddressSpace().getByte(address));
                }
                // The established native write journal publishes after its final GPU dot.
                // It can retain a one-dot historical STAT-write timestamp after the copies
                // have drained (also present before the short-capture profitability change).
                // Start the exact recovery comparison from that same resolved machine state;
                // the active-write test above separately checks partition/restore invariance.
                var recovered = batched.captureStateWithoutTimeSource();
                scalar.restoreStateSilently(recovered);
                batched.restoreStateSilently(recovered);
                batched.resetPerformanceBulkCounters();
                assertEquals(scalar.runTicks(70_224), batched.runTicks(70_224));
                assertTrue(profile + " capture lease survived after its event",
                        batched.getPerformanceEpochTicks() > 60_000);
                assertStateEquals(profile + " recovered quiet frame",
                        scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());
            }
        }
    }

    private static Gameboy finiteBurst(Profile profile) throws Exception {
        byte[] rom = image(Scenario.CPU, profile);
        int[] program = {
                0xf3, 0x31, 0xfe, 0xff, 0xaf, 0xea, 0xff, 0xff, 0xe0, 0x0f,
                0x3e, 1, 0xe0, 0x4d, 0x10, 0,
                0x06, 0, 0xaf,
                0x3c, 0xe0, 0x45, 0x05, 0x20, 0xfa, // 256 CPU-written LYC values.
                0xaf, 0xe0, 0x41, 0xe0, 0x0f,
                0x3e, 0x5a, 0xe0, 0x80,
                0x00, 0xc3, 0x72, 0x01 // Quiet NOP/JP work after every copy has drained.
        };
        for (int i = 0; i < program.length; i++) rom[0x150 + i] = (byte) program[i];
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false)
                .build();
    }
}
