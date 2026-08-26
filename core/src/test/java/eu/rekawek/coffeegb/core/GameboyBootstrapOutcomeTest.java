package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Focused coverage for session-only bootstrap outcome metadata and its performance gate. */
public final class GameboyBootstrapOutcomeTest {

    @Test
    public void skipIsImmediatelyReadyAndBootStatePreservesTheOutcome() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build()) {
            assertEquals(Gameboy.BootstrapOutcome.SKIPPED, gameboy.getBootstrapOutcome());
            assertTrue(gameboy.isBootstrapReady());

            Gameboy.BootState bootState = gameboy.saveBootState();
            assertEquals(Gameboy.BootstrapOutcome.SKIPPED,
                    bootState.getBootstrapOutcome());
            try (Gameboy restored = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                    .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setSupportBatterySave(false)
                    .build()) {
                restored.restoreBootState(bootState);
                assertEquals(Gameboy.BootstrapOutcome.SKIPPED,
                        restored.getBootstrapOutcome());
                assertTrue(restored.isBootstrapReady());
            }
        }
    }

    @Test
    public void normalStartsPendingAndUsesOnlyScalarExecutionUntilHandoff() throws Exception {
        try (Gameboy performance = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            assertEquals(Gameboy.BootstrapOutcome.PENDING,
                    performance.getBootstrapOutcome());
            assertFalse(performance.isBootstrapReady());

            performance.runTicks(2_048);

            assertEquals(Gameboy.BootstrapOutcome.PENDING,
                    performance.getBootstrapOutcome());
            assertFalse(performance.isBootstrapReady());
            assertEquals("PERFORMANCE epochs must stay disabled before the BIOS handoff",
                    0L, performance.getPerformanceEpochTicks());
            assertEquals("PERFORMANCE spans must stay disabled before the BIOS handoff",
                    0L, performance.getPerformanceBulkTicks());
            assertEquals("scanline shortcuts must stay disabled before the BIOS handoff",
                    0L, performance.getGpu().getPerformanceScanlineFastTicks());
            assertEquals("steady raster shortcuts must stay disabled before the BIOS handoff",
                    0L, performance.getGpu().getPerformanceSteadyFastTicks());
            assertThrows("an arbitrary NORMAL in-progress state is not a boot template",
                    IllegalStateException.class, performance::saveBootState);
        }
    }

    @Test
    public void fastForwardPendingBoundaryCanBeCachedAndRestoredWithoutPerformanceTail()
            throws Exception {
        try (Gameboy source = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
                Gameboy restored = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                        // Production BootState materialization uses a SKIP shell and then
                        // restores the producer's BIOS/CPU state.
                        .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                        .setExecutionMode(ExecutionMode.PERFORMANCE)
                        .setSupportBatterySave(false)
                        .build()) {
            assertEquals(Gameboy.BootstrapOutcome.PENDING, source.getBootstrapOutcome());
            assertEquals(0x100, source.getCpu().getRegisters().getPC());
            assertTrue(source.getAddressSpace().getByte(0xff50) != 0xff);
            Gameboy.BootState bootState = source.saveBootState();
            assertEquals(Gameboy.BootstrapOutcome.PENDING, bootState.getBootstrapOutcome());

            restored.restoreBootState(bootState);
            assertEquals(Gameboy.BootstrapOutcome.PENDING, restored.getBootstrapOutcome());
            assertFalse(restored.isBootstrapReady());
            assertEquals(0L, restored.getPerformanceEpochTicks());
            assertEquals(0L, restored.getPerformanceBulkTicks());
            assertDeepStateEquals("pending FF boundary",
                    source.captureStateWithoutTimeSource(),
                    restored.captureStateWithoutTimeSource());

            advanceToBootstrapReady(source);
            advanceToBootstrapReady(restored);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    source.getBootstrapOutcome());
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    restored.getBootstrapOutcome());
            assertDeepStateEquals("authentic FF handoff",
                    source.captureStateWithoutTimeSource(),
                    restored.captureStateWithoutTimeSource());
            assertEquals(0L, source.getPerformanceEpochTicks());
            assertEquals(0L, restored.getPerformanceEpochTicks());
            assertEquals(0L, source.getPerformanceBulkTicks());
            assertEquals(0L, restored.getPerformanceBulkTicks());

            for (int i = 0; i < 32; i++) {
                source.tick();
                restored.tick();
            }
            assertDeepStateEquals("identical scalar tail after the pending handoff",
                    source.captureStateWithoutTimeSource(),
                    restored.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void normalAuthenticBootTransitionsToAuthenticHandoff() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setSupportBatterySave(false)
                .build()) {
            long ticks = 0;
            while (!gameboy.isBootstrapReady() && ticks++ < 20_000_000L) {
                gameboy.tick();
            }

            assertTrue("authentic CGB boot did not reach the cartridge handoff",
                    gameboy.isBootstrapReady());
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    gameboy.getBootstrapOutcome());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
            assertTrue("non-color CGB fixture must enter DMG compatibility",
                    gameboy.getSpeedMode().isDmgCompat());
        }
    }

    @Test
    public void portableRestoreReopensPendingGateAndRequiresBootstrapSidecarForReadiness()
            throws Exception {
        Gameboy.GameboyConfiguration normalConfiguration =
                new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                        .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                        .setExecutionMode(ExecutionMode.PERFORMANCE)
                        .setSupportBatterySave(false);
        try (Gameboy source = normalConfiguration.build();
                Gameboy target = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                        .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                        .setExecutionMode(ExecutionMode.PERFORMANCE)
                        .setSupportBatterySave(false)
                        .build()) {
            var pending = source.captureState();
            assertEquals(Gameboy.BootstrapOutcome.PENDING, source.getBootstrapOutcome());
            target.runTicks(2_048);
            target.restoreState(pending);
            assertEquals(Gameboy.BootstrapOutcome.PENDING, target.getBootstrapOutcome());
            assertFalse(target.isBootstrapReady());
            assertEquals(0L, target.getPerformanceEpochTicks());
            assertEquals(0L, target.getPerformanceBulkTicks());

            while (!source.isBootstrapReady()) {
                source.tick();
            }
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    source.getBootstrapOutcome());
            for (int i = 0; i < 8; i++) {
                source.tick();
            }
            assertTrue("fixture must execute beyond the cartridge entry point",
                    source.getCpu().getRegisters().getPC() != 0x0100);
            var ready = source.captureState();
            target.restoreState(ready);
            // Portable snapshots intentionally carry no bootstrap provenance. Even a state
            // captured after the cartridge has advanced must not reopen the performance gate;
            // the BootState sidecar below is the exact cross-instance handoff contract.
            assertEquals(Gameboy.BootstrapOutcome.PENDING,
                    target.getBootstrapOutcome());
            assertFalse(target.isBootstrapReady());
            assertEquals(0xff, target.getAddressSpace().getByte(0xff50));

            Gameboy.BootState readyBootState = source.saveBootState();
            target.restoreBootState(readyBootState);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    target.getBootstrapOutcome());
            assertTrue(target.isBootstrapReady());
            assertEquals(0xff, target.getAddressSpace().getByte(0xff50));
        }
    }

    @Test
    public void warmResetFromPendingBootstrapStaysFailClosed() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.requestWarmReset(true);
            gameboy.tick();
            assertEquals(Gameboy.BootstrapOutcome.PENDING,
                    gameboy.getBootstrapOutcome());
            assertFalse(gameboy.isBootstrapReady());
            var resetState = gameboy.captureState();
            try (Gameboy restored = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                    .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                    .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setSupportBatterySave(false)
                    .build()) {
                restored.restoreState(resetState);
                assertEquals(Gameboy.BootstrapOutcome.PENDING,
                        restored.getBootstrapOutcome());
                assertFalse(restored.isBootstrapReady());
                assertEquals(0L, restored.getPerformanceEpochTicks());
                assertEquals(0L, restored.getPerformanceBulkTicks());
            }
        }
    }

    @Test
    public void earlyBiosReleaseStaysPendingAndPortableRestoreRemainsFailClosed() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            var pending = gameboy.captureState();
            gameboy.getAddressSpace().setByte(0xff50, 1);
            gameboy.tick();
            assertEquals(Gameboy.BootstrapOutcome.PENDING,
                    gameboy.getBootstrapOutcome());
            assertFalse(gameboy.isBootstrapReady());
            var rejected = gameboy.captureState();
            try (Gameboy restored = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                    .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                    .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setSupportBatterySave(false)
                    .build()) {
                restored.restoreState(rejected);
                assertEquals(Gameboy.BootstrapOutcome.PENDING,
                        restored.getBootstrapOutcome());
                assertFalse(restored.isBootstrapReady());
                assertEquals(0L, restored.getPerformanceEpochTicks());
                assertEquals(0L, restored.getPerformanceBulkTicks());
            }
            gameboy.restoreState(pending);

            while (!gameboy.isBootstrapReady()) {
                gameboy.tick();
            }
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    gameboy.getBootstrapOutcome());
        }
    }

    @Test
    public void fastForwardAuthenticBootIsNotReportedAsFallback() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build()) {
            advanceToBootstrapReady(gameboy);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    gameboy.getBootstrapOutcome());
            assertTrue(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
            assertTrue("non-color CGB fixture must enter DMG compatibility",
                    gameboy.getSpeedMode().isDmgCompat());
        }
    }

    @Test
    public void fastForwardAuthenticDmgBootIsNotReportedAsFallback() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build()) {
            advanceToBootstrapReady(gameboy);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    gameboy.getBootstrapOutcome());
            assertTrue(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
        }
    }

    @Test
    public void fastForwardDmgPreservesPendingMappedBiosBoundaryAndScalarHandoff() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(validDmgRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            assertEquals(Gameboy.BootstrapOutcome.PENDING, gameboy.getBootstrapOutcome());
            assertFalse(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertTrue("FAST_FORWARD must retain the BIOS overlay at its PC=$0100 boundary",
                    gameboy.getAddressSpace().getByte(0xff50) != 0xff);
            long scalarHandoffTicks = advanceToBootstrapReady(gameboy);
            assertEquals("DMG FAST_FORWARD scalar handoff length", 4L, scalarHandoffTicks);
        }
    }

    @Test
    public void fastForwardAndNormalHandoffAgreeOnCompatibilityResidue() throws Exception {
        try (Gameboy normal = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
                Gameboy fastForward = new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                        .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                        .setSupportBatterySave(false)
                        .build()) {
            long ticks = 0;
            while (!normal.isBootstrapReady() && ticks++ < 20_000_000L) {
                normal.tick();
            }
            assertTrue("NORMAL bootstrap did not reach the cartridge handoff",
                    normal.isBootstrapReady());
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    normal.getBootstrapOutcome());
            advanceToBootstrapReady(fastForward);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    fastForward.getBootstrapOutcome());
            assertEquals(normal.getHardwareProfileIdentity(), fastForward.getHardwareProfileIdentity());
            assertEquals(normal.getCpu().getRegisters().getAF(),
                    fastForward.getCpu().getRegisters().getAF());
            assertEquals(normal.getCpu().getRegisters().getBC(),
                    fastForward.getCpu().getRegisters().getBC());
            assertEquals(normal.getCpu().getRegisters().getDE(),
                    fastForward.getCpu().getRegisters().getDE());
            assertEquals(normal.getCpu().getRegisters().getHL(),
                    fastForward.getCpu().getRegisters().getHL());
            assertEquals(normal.getCpu().getRegisters().getSP(),
                    fastForward.getCpu().getRegisters().getSP());
            assertEquals(normal.getCpu().getRegisters().getPC(),
                    fastForward.getCpu().getRegisters().getPC());
            assertEquals(0xff, normal.getAddressSpace().getByte(0xff50));
            assertEquals(0xff, fastForward.getAddressSpace().getByte(0xff50));
            assertEquals(normal.getSpeedMode().isDmgCompat(),
                    fastForward.getSpeedMode().isDmgCompat());
            assertEquals("NORMAL bootstrap must remain scalar until handoff", 0L,
                    normal.getPerformanceEpochTicks());
            assertEquals("NORMAL bootstrap must not use bulk shortcuts", 0L,
                    normal.getPerformanceBulkTicks());
            assertEquals(normal.getGpu().captureDebugGraphicsInspection(),
                    fastForward.getGpu().captureDebugGraphicsInspection());
        }
    }

    @Test
    public void dmgNormalAndFastForwardReachAnIdenticalAuthenticHandoff() throws Exception {
        try (Gameboy normal = new Gameboy.GameboyConfiguration(new Rom(validDmgRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
                Gameboy fastForward = new Gameboy.GameboyConfiguration(new Rom(validDmgRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.DMG)
                        .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                        .setExecutionMode(ExecutionMode.PERFORMANCE)
                        .setSupportBatterySave(false)
                        .build()) {
            assertEquals(Gameboy.BootstrapOutcome.PENDING, fastForward.getBootstrapOutcome());
            advanceNormalToBootstrapReady(normal);
            advanceToBootstrapReady(fastForward);
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    normal.getBootstrapOutcome());
            assertEquals(Gameboy.BootstrapOutcome.AUTHENTIC_HANDOFF,
                    fastForward.getBootstrapOutcome());
            assertDeepStateEquals("DMG authentic handoff",
                    normal.captureStateWithoutTimeSource(),
                    fastForward.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void missingAuthenticBiosFailsClosed() throws Exception {
        Gameboy.GameboyConfiguration configuration =
                new Gameboy.GameboyConfiguration(new Rom(validCgbRom()))
                        .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.MGB)
                        .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                        .setSupportBatterySave(false);

        assertThrows(IllegalArgumentException.class, configuration::build);
    }

    @Test
    public void fastForwardTimeoutUsesAnExplicitFallbackOutcome() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(invalidLogoRom()))
                .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build()) {
            assertEquals(Gameboy.BootstrapOutcome.TIMED_OUT_FALLBACK,
                    gameboy.getBootstrapOutcome());
            assertTrue(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
            assertTrue("fallback must still install the non-color CGB compatibility state",
                    gameboy.getSpeedMode().isDmgCompat());

            Gameboy.BootState bootState = gameboy.saveBootState();
            assertEquals(Gameboy.BootstrapOutcome.TIMED_OUT_FALLBACK,
                    bootState.getBootstrapOutcome());
            try (Gameboy restored = new Gameboy.GameboyConfiguration(new Rom(invalidLogoRom()))
                    .setHardwareProfile(eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry.CGB)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setSupportBatterySave(false)
                    .build()) {
                restored.restoreBootState(bootState);
                assertEquals(Gameboy.BootstrapOutcome.TIMED_OUT_FALLBACK,
                        restored.getBootstrapOutcome());
                assertTrue(restored.isBootstrapReady());
            }
        }
    }

    private static byte[] validCgbRom() {
        byte[] rom = new byte[0x8000];
        // A tiny post-boot loop advances beyond PC=$0100 so portable restore tests exercise
        // the durable handoff invariant rather than relying on the exact entry-point value.
        rom[0x0100] = 0x00; // NOP
        rom[0x0101] = 0x00; // NOP
        rom[0x0102] = 0x18; // JR $0100
        rom[0x0103] = (byte) 0xfc;
        int[] logo = {
                0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
                0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
                0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
                0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
                0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
                0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
        };
        for (int i = 0; i < logo.length; i++) {
            rom[0x0104 + i] = (byte) logo[i];
        }
        rom[0x0143] = 0x00; // authentic CGB boot takes the DMG-compatibility path
        rom[0x0147] = 0x00;
        rom[0x0148] = 0x00;
        rom[0x0149] = 0x00;
        int checksum = 0;
        for (int address = 0x0134; address <= 0x014c; address++) {
            checksum = (checksum - (rom[address] & 0xff) - 1) & 0xff;
        }
        rom[0x014d] = (byte) checksum;
        return rom;
    }

    private static byte[] validDmgRom() {
        return validCgbRom();
    }

    private static long advanceToBootstrapReady(Gameboy gameboy) {
        long ticks = 0;
        while (!gameboy.isBootstrapReady() && ticks < 64) {
            gameboy.tick();
            ticks++;
        }
        assertTrue("bootstrap did not complete its scalar handoff tail",
                gameboy.isBootstrapReady());
        return ticks;
    }

    private static long advanceNormalToBootstrapReady(Gameboy gameboy) {
        long ticks = 0;
        while (!gameboy.isBootstrapReady() && ticks < 40_000_000L) {
            gameboy.tick();
            ticks++;
        }
        assertTrue("NORMAL bootstrap did not complete its authentic handoff",
                gameboy.isBootstrapReady());
        return ticks;
    }

    /** Record/array-aware equality for the private immutable component-state graph. */
    private static void assertDeepStateEquals(String path, Object expected, Object actual) {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        assertEquals(path + " type", expected.getClass(), actual.getClass());
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(path + " length", length, Array.getLength(actual));
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        Array.get(expected, i), Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof List<?> expectedList) {
            List<?> actualList = (List<?>) actual;
            assertEquals(path + " size", expectedList.size(), actualList.size());
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        expectedList.get(i), actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            assertEquals(path, expected, actual);
            return;
        }
        try {
            for (RecordComponent component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepStateEquals(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot compare " + path, error);
        }
    }

    private static byte[] invalidLogoRom() {
        byte[] rom = validCgbRom();
        rom[0x0104] ^= 1;
        return rom;
    }
}
