package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

/**
 * Focused authored coverage for fences whose changing operation must recover to a quiet path.
 * Every pair uses the production {@link Gameboy#runTicks(long)} entry point; the scalar side is
 * the same PERFORMANCE representation with batching disabled.
 */
public class PerformanceRetainedFenceWorkloadTest {
    private static final int RECOVERY_TICKS = 20_000;
    private static final int MAX_SETUP_TICKS = 500_000;
    private static final int MIN_FINITE_WINDOW = 54;
    private static final int MAX_FINITE_WINDOW = 1_024;
    private static final Field DMA_FIELD = field(Gameboy.class, "dma");
    private static final Field HDMA_FIELD = field(Gameboy.class, "hdma");

    private record Case(Scenario scenario, Profile profile, int finiteBurst) {}

    private static final Case[] PERSISTENT = {
            new Case(Scenario.CONTROL_LINK_IO, Profile.DMG, 2),
            new Case(Scenario.CONTROL_LINK_IO, Profile.CGB_X2, 2),
            new Case(Scenario.CONTROL_LINK_IO, Profile.CGB_COMPAT, 2),
            new Case(Scenario.OAM_ROM_DMA, Profile.DMG, 2),
            new Case(Scenario.OAM_ROM_DMA, Profile.CGB_X2, 2),
            new Case(Scenario.OAM_ROM_DMA, Profile.CGB0, 2),
            new Case(Scenario.OVERLAP_DMA, Profile.CGB, 1),
            new Case(Scenario.OVERLAP_DMA, Profile.CGB_X2, 1),
            new Case(Scenario.OVERLAP_DMA, Profile.CGB0_X2, 1),
            new Case(Scenario.MBC3_RTC_WINDOW, Profile.DMG, 2),
            new Case(Scenario.MBC3_RTC_WINDOW, Profile.CGB_X2, 2),
            new Case(Scenario.MBC3_RTC_WINDOW, Profile.CGB0_COMPAT, 2)
    };

    private static final Case[] FINITE = {
            new Case(Scenario.CONTROL_LINK_IO, Profile.DMG, 1),
            new Case(Scenario.OAM_ROM_DMA, Profile.CGB_X2, 1),
            new Case(Scenario.OVERLAP_DMA, Profile.CGB_X2, 1),
            new Case(Scenario.MBC3_RTC_WINDOW, Profile.CGB0_COMPAT, 2)
    };

    @Test
    public void persistentFixturesMakeProgressAndRetainTheirChangingFence() throws Exception {
        for (Case workload : PERSISTENT) {
            try (Gameboy scalar = session(workload.scenario, workload.profile, -1);
                 Gameboy candidate = session(workload.scenario, workload.profile, -1)) {
                scalar.setPerformanceBatchingEnabled(false);
                candidate.setPerformanceBatchingEnabled(false);
                seekFirstCompletion(scalar, candidate, workload.scenario, workload.profile);
                assertSpeed(workload.scenario, workload.profile, scalar, candidate);
                int before = read(candidate, PerformanceWorkloads.RETAINED_PROGRESS);

                PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(4_096);
                candidate.setPerformanceDiagnostics(diagnostics);
                candidate.resetPerformanceBulkCounters();
                candidate.setPerformanceBatchingEnabled(true);
                assertEquals(workload + " frame events",
                        scalar.runTicks(30_000), candidate.runTicks(30_000));

                int after = read(candidate, PerformanceWorkloads.RETAINED_PROGRESS);
                assertTrue(workload + " did not make loop progress", ((after - before) & 0xff) != 0);
                assertEquals(workload + " scalar/candidate progress", read(scalar,
                        PerformanceWorkloads.RETAINED_PROGRESS), after);
                assertEquals(workload + " scalar/candidate result",
                        read(scalar, PerformanceWorkloads.RETAINED_RESULT),
                        read(candidate, PerformanceWorkloads.RETAINED_RESULT));
                assertTrue(workload + " lost every positive scheduler path",
                        candidate.getPerformanceEpochTicks() + candidate.getPerformanceBulkTicks() > 0);
                assertFence(workload, diagnostics.snapshot());
                if (workload.scenario == Scenario.MBC3_RTC_WINDOW) {
                    assertTrue(workload + " did not attempt a cartridge-window fence",
                            candidate.getPerformanceEpochCartWindowFenceAttemptCount() > 0);
                }
                assertStateEquals(workload + " canonical persistent window",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void finiteBurstsRecoverToQuietPerformanceWithCanonicalState() throws Exception {
        for (Case workload : FINITE) {
            try (Gameboy scalar = session(workload.scenario, workload.profile, workload.finiteBurst);
                 Gameboy candidate = session(workload.scenario, workload.profile, workload.finiteBurst)) {
                scalar.setPerformanceBatchingEnabled(false);
                var scalarStart = scalar.captureStateWithoutTimeSource();
                var candidateStart = candidate.captureStateWithoutTimeSource();

                // Use uninterrupted, seeded windows to observe the changing operation on the
                // PERFORMANCE scheduler. The fine-step replay below is a separate owner witness.
                candidate.setPerformanceBatchingEnabled(true);
                candidate.resetPerformanceBulkCounters();
                BatchedEvidence batched = driveBatchedFiniteBurst(
                        scalar, candidate, workload.scenario, workload.profile, workload.finiteBurst);
                assertSpeed(workload.scenario, workload.profile, scalar, candidate);
                assertTrue(workload + " no epoch/bulk path before explicit quiet-loop marker",
                        batched.beforeQuietMarkerEpochTicks + batched.beforeQuietMarkerBulkTicks > 0);
                if (workload.scenario == Scenario.OVERLAP_DMA) {
                    assertTrue(workload + " no HDMA-owned span before explicit quiet-loop marker",
                            batched.beforeQuietMarkerHdmaTicks > 0);
                }

                // Restore the common start checkpoint and run one-tick scalar witnesses only to
                // prove that the authored DMA owners were actually entered and later released.
                scalar.restoreStateSilently(scalarStart);
                candidate.restoreStateSilently(candidateStart);
                scalar.setPerformanceBatchingEnabled(false);
                candidate.setPerformanceBatchingEnabled(false);
                ProgressEvidence evidence = driveFiniteBurst(
                        scalar, candidate, workload.scenario, workload.profile, workload.finiteBurst);
                assertSpeed(workload.scenario, workload.profile, scalar, candidate);
                assertEquals(workload + " completed count", workload.finiteBurst,
                        read(candidate, PerformanceWorkloads.RETAINED_PROGRESS));
                assertEquals(workload + " reached the quiet phase", 2,
                        read(candidate, PerformanceWorkloads.RETAINED_PHASE));
                assertEquals(workload + " scalar/candidate quiet phase",
                        read(scalar, PerformanceWorkloads.RETAINED_PHASE),
                        read(candidate, PerformanceWorkloads.RETAINED_PHASE));
                assertEquals(workload + " scalar/candidate result",
                        read(scalar, PerformanceWorkloads.RETAINED_RESULT),
                        read(candidate, PerformanceWorkloads.RETAINED_RESULT));
                assertStateEquals(workload + " canonical changing window",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                if (workload.scenario == Scenario.OAM_ROM_DMA
                        || workload.scenario == Scenario.OVERLAP_DMA) {
                    assertTrue(workload + " never entered the OAM DMA owner", evidence.oamDma);
                    assertFalse(workload + " left OAM DMA active at quiet phase",
                            dma(candidate).isTransferInProgress());
                    assertFalse(workload + " left VRAM DMA active at quiet phase",
                            hdma(candidate).isTransferInProgress());
                    assertFalse(workload + " left HBlank DMA armed at quiet phase",
                            hdma(candidate).hasPendingHblankTransfer());
                }
                if (workload.scenario == Scenario.OVERLAP_DMA) {
                    assertTrue(workload + " never observed concurrent DMA owners", evidence.overlap);
                    assertTrue(workload + " never observed an armed HBlank request",
                            evidence.armedHdma);
                }

                // The changing operation is over. From this checkpoint the candidate is allowed
                // to batch the ROM quiet loop while the scalar side remains the same fixture.
                candidate.resetPerformanceBulkCounters();
                candidate.setPerformanceBatchingEnabled(true);
                assertEquals(workload + " recovery frame events",
                        scalar.runTicks(RECOVERY_TICKS), candidate.runTicks(RECOVERY_TICKS));
                assertTrue(workload + " did not recover a steady epoch",
                        candidate.getPerformanceEpochTicks() > 1_000);
                assertStateEquals(workload + " canonical recovery",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

                assertPayload(workload, scalar, candidate);
            }
        }
    }

    @Test
    public void overlapFixtureUsesGdmaAndArmedHblankDmaWhileOamOwnsItsBus() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB, Profile.CGB_X2, Profile.CGB0,
                Profile.CGB0_X2}) {
            try (Gameboy scalar = session(Scenario.OVERLAP_DMA, profile, 1);
                Gameboy candidate = session(Scenario.OVERLAP_DMA, profile, 1)) {
                scalar.setPerformanceBatchingEnabled(false);
                candidate.setPerformanceBatchingEnabled(false);
                ProgressEvidence evidence = driveFiniteBurst(
                        scalar, candidate, Scenario.OVERLAP_DMA, profile, 1);
                assertSpeed(Scenario.OVERLAP_DMA, profile, scalar, candidate);
                assertTrue(profile + " did not overlap OAM with a VRAM-DMA request",
                        evidence.overlap);
                assertTrue(profile + " did not arm the HBlank-DMA half of the fixture",
                        evidence.armedHdma);
                assertTrue(profile + " did not complete the authored overlap loop",
                        read(candidate, PerformanceWorkloads.RETAINED_PROGRESS) != 0);
                assertEquals(profile + " did not enter quiet recovery phase", 2,
                        read(candidate, PerformanceWorkloads.RETAINED_PHASE));
                assertFalse(profile + " left OAM DMA active at quiet phase",
                        dma(candidate).isTransferInProgress());
                assertFalse(profile + " left VRAM DMA active at quiet phase",
                        hdma(candidate).isTransferInProgress());
                assertFalse(profile + " left HBlank DMA armed at quiet phase",
                        hdma(candidate).hasPendingHblankTransfer());
                assertEquals(profile + " destination payload", 0x31,
                        candidate.getGpu().readSelectedVideoRamForCore(0x8000));
                assertStateEquals(profile + " overlap setup",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
            }
        }
    }

    private static void assertFence(Case workload, PerformanceDiagnostics.Snapshot snapshot) {
        PerformanceDiagnostics.Fence fence = switch (workload.scenario) {
            case CONTROL_LINK_IO -> PerformanceDiagnostics.Fence.SERIAL;
            case OAM_ROM_DMA, OVERLAP_DMA -> PerformanceDiagnostics.Fence.PPU;
            case MBC3_RTC_WINDOW -> PerformanceDiagnostics.Fence.CARTRIDGE;
            default -> throw new AssertionError(workload.scenario);
        };
        assertTrue(workload + " did not retain its " + fence + " fence",
                snapshot.fences().getOrDefault(fence, 0L) > 0);
        if (workload.scenario == Scenario.CONTROL_LINK_IO) {
            assertTrue(workload + " did not expose a timer event fence",
                    snapshot.fences().getOrDefault(PerformanceDiagnostics.Fence.TIMER, 0L) > 0);
            assertTrue(workload + " did not expose the IF completion read",
                    snapshot.fences().getOrDefault(PerformanceDiagnostics.Fence.INTERRUPT, 0L) > 0);
        }
    }

    /**
     * Runs the finite fixture in uninterrupted seeded windows. Phase 1 is deliberately held
     * long enough to be observed at a window boundary before phase 2 enters the ROM quiet loop.
     */
    private static BatchedEvidence driveBatchedFiniteBurst(Gameboy scalar, Gameboy candidate,
                                                             Scenario scenario, Profile profile,
                                                             int finiteBurst) {
        Random random = new Random(41_021L + 31L * scenario.ordinal() + profile.ordinal());
        long beforeQuietMarkerEpochTicks = 0;
        long beforeQuietMarkerBulkTicks = 0;
        long beforeQuietMarkerHdmaTicks = 0;
        boolean observedPhaseOne = false;
        int elapsed = 0;
        while (elapsed < MAX_SETUP_TICKS) {
            int window = Math.min(MAX_FINITE_WINDOW,
                    MIN_FINITE_WINDOW + random.nextInt(MAX_FINITE_WINDOW - MIN_FINITE_WINDOW + 1));
            window = Math.min(window, MAX_SETUP_TICKS - elapsed);
            assertEquals(scenario + "/" + profile + " batched frame window",
                    scalar.runTicks(window), candidate.runTicks(window));
            elapsed += window;
            int scalarPhase = read(scalar, PerformanceWorkloads.RETAINED_PHASE);
            int candidatePhase = read(candidate, PerformanceWorkloads.RETAINED_PHASE);
            assertEquals(scenario + "/" + profile + " batched phase", scalarPhase, candidatePhase);
            if (candidatePhase == 1) {
                observedPhaseOne = true;
                // Phase 1 includes the authored gap before the explicit quiet-loop marker;
                // persistent windows above establish batching while the fence is continuously
                // exercised.
                beforeQuietMarkerEpochTicks = candidate.getPerformanceEpochTicks();
                beforeQuietMarkerBulkTicks = candidate.getPerformanceBulkTicks();
                beforeQuietMarkerHdmaTicks = candidate.getPerformanceHdmaOwnedTicks();
                assertStateEquals(scenario + "/" + profile + " batched phase-one checkpoint",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
            } else if (candidatePhase == 2) {
                assertTrue(scenario + "/" + profile + " phase one was hidden by a window",
                        observedPhaseOne);
                assertEquals(finiteBurst,
                        read(candidate, PerformanceWorkloads.RETAINED_PROGRESS));
                assertStateEquals(scenario + "/" + profile + " batched quiet checkpoint",
                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                return new BatchedEvidence(beforeQuietMarkerEpochTicks, beforeQuietMarkerBulkTicks,
                        beforeQuietMarkerHdmaTicks, elapsed);
            }
        }
        fail(scenario + "/" + profile + " batched finite burst did not reach quiet phase");
        return new BatchedEvidence(beforeQuietMarkerEpochTicks, beforeQuietMarkerBulkTicks,
                beforeQuietMarkerHdmaTicks, elapsed); // unreachable
    }

    private static ProgressEvidence driveFiniteBurst(Gameboy scalar, Gameboy candidate,
                                                       Scenario scenario, Profile profile,
                                                       int finiteBurst) {
        int start = read(candidate, PerformanceWorkloads.RETAINED_PROGRESS);
        boolean oamDma = false;
        boolean overlap = false;
        boolean armedHdma = false;
        for (int tick = 0; tick < MAX_SETUP_TICKS; tick++) {
            assertEquals(scenario + "/" + profile + " scalar/candidate frame seam",
                    scalar.runTicks(1), candidate.runTicks(1));
            Dma dma = dma(candidate);
            Hdma hdma = hdma(candidate);
            oamDma |= dma.isTransferInProgress();
            overlap |= dma.isTransferInProgress() && hdma.isTransferInProgress();
            armedHdma |= hdma.hasPendingHblankTransfer();
            // Read both sides before accepting completion. The CPU-facing HRAM view remains
            // legal during OAM DMA, but requiring the scalar witness too avoids mistaking a
            // transient bus value for the authored loop counter.
            if (read(candidate, PerformanceWorkloads.RETAINED_PROGRESS) == finiteBurst
                    && read(scalar, PerformanceWorkloads.RETAINED_PROGRESS) == finiteBurst
                    && read(candidate, PerformanceWorkloads.RETAINED_PHASE) == 2
                    && read(scalar, PerformanceWorkloads.RETAINED_PHASE) == 2) {
                return new ProgressEvidence(oamDma, overlap, armedHdma);
            }
        }
        fail(scenario + "/" + profile + " finite burst did not complete from " + start);
        return new ProgressEvidence(oamDma, overlap, armedHdma); // unreachable
    }

    /** Seek an actual completed loop; no fixed 210k setup budget can hide a finite burst. */
    private static void seekFirstCompletion(Gameboy scalar, Gameboy candidate,
                                             Scenario scenario, Profile profile) {
        int before = read(candidate, PerformanceWorkloads.RETAINED_PROGRESS);
        for (int tick = 0; tick < MAX_SETUP_TICKS; tick++) {
            assertEquals(scenario + "/" + profile + " setup frame seam",
                    scalar.runTicks(1), candidate.runTicks(1));
            if (read(candidate, PerformanceWorkloads.RETAINED_PROGRESS) != before) return;
        }
        fail(scenario + "/" + profile + " never completed its first authored loop");
    }

    private static void assertPayload(Case workload, Gameboy scalar, Gameboy candidate) {
        switch (workload.scenario) {
            case CONTROL_LINK_IO -> {
                assertEquals(workload + " serial completion", 0xff,
                        read(candidate, PerformanceWorkloads.RETAINED_RESULT));
                assertEquals(workload + " completed SC", 0,
                        read(candidate, 0xc002) & 0x80);
                assertEquals(workload + " timer/serial IF", 0x0c,
                        read(candidate, 0xc003) & 0x0c);
                assertEquals(workload + " TMA readback", 0xa5, read(candidate, 0xc005));
                assertEquals(workload + " TAC readback", 0x05, read(candidate, 0xc006) & 0x07);
            }
            case OAM_ROM_DMA -> {
                assertEquals(workload + " ROM source witness", 0x5a,
                        read(candidate, PerformanceWorkloads.RETAINED_RESULT));
                for (int offset = 0; offset < 4; offset++) {
                    int expected = (offset * 37 + 0x5a) & 0xff;
                    assertEquals(workload + " OAM payload witness " + offset, expected,
                            read(candidate, 0xc005 + offset));
                    assertEquals(workload + " scalar/candidate OAM payload witness " + offset,
                            read(scalar, 0xc005 + offset), read(candidate, 0xc005 + offset));
                }
            }
            case OVERLAP_DMA -> assertEquals(workload + " VRAM payload", 0x31,
                    candidate.getGpu().readSelectedVideoRamForCore(0x8000));
            case MBC3_RTC_WINDOW -> {
                assertEquals(workload + " SRAM RMW", 0x20 + workload.finiteBurst,
                        read(candidate, PerformanceWorkloads.RETAINED_RESULT));
                int expectedMinute = workload.finiteBurst > 1 ? 0x12 : 0;
                assertEquals(workload + " RTC minute before write", expectedMinute,
                        read(candidate, 0xc002));
                assertEquals(workload + " fixed RTC seconds", 0,
                        read(candidate, 0xc003));
            }
            default -> throw new AssertionError(workload.scenario);
        }
        assertStateEquals(workload + " payload state", scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static Gameboy session(Scenario scenario, Profile profile, int finiteBurst)
            throws Exception {
        byte[] image = retainedFenceImage(scenario, profile, finiteBurst);
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L)
                .setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false)
                .build();
        return gameboy;
    }

    private static void assertSpeed(Scenario scenario, Profile profile,
                                    Gameboy scalar, Gameboy candidate) {
        int expected = profile.doubleSpeed ? 2 : 1;
        assertEquals(scenario + "/" + profile + " scalar speed after setup",
                expected, scalar.getSpeedMode().getSpeedMode());
        assertEquals(scenario + "/" + profile + " candidate speed after setup",
                expected, candidate.getSpeedMode().getSpeedMode());
    }

    private static int read(Gameboy gameboy, int address) {
        return gameboy.getAddressSpace().getByte(address) & 0xff;
    }

    private static Dma dma(Gameboy gameboy) {
        try {
            return (Dma) DMA_FIELD.get(gameboy);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Hdma hdma(Gameboy gameboy) {
        try {
            return (Hdma) HDMA_FIELD.get(gameboy);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record BatchedEvidence(long beforeQuietMarkerEpochTicks,
                                   long beforeQuietMarkerBulkTicks,
                                   long beforeQuietMarkerHdmaTicks, int elapsedTicks) {}

    private record ProgressEvidence(boolean oamDma, boolean overlap, boolean armedHdma) {}
}
