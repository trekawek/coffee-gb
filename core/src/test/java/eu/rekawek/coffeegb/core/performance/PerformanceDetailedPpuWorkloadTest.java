package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

public class PerformanceDetailedPpuWorkloadTest {
    @Test
    public void continuousOamCopiesRetainCpuBatchesAndCanonicalMachineState() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB_X2, Profile.CGB0_X2}) {
            try (Gameboy reference = session(Scenario.OAM_DMA, profile);
                 Gameboy candidate = session(Scenario.OAM_DMA, profile)) {
                reference.setPerformanceBatchingEnabled(false);
                candidate.setPerformanceBatchingEnabled(false);
                reference.runTicks(210_000);
                candidate.runTicks(210_000);
                candidate.setPerformanceBatchingEnabled(true);
                PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                candidate.setPerformanceDiagnostics(diagnostics);
                for (int frame = 0; frame < 3; frame++) {
                    assertEquals(reference.runTicks(70_224), candidate.runTicks(70_224));
                    assertStateEquals(profile + " OAM frame " + frame,
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                }
                for (var window : diagnostics.snapshot().windows()) {
                    assertTrue(profile + " continuous DMA CPU coverage " + window,
                            window.epochTicks() > window.ticks() / 2);
                }
                var dmaWork = diagnostics.snapshot().subsystemTicks();
                assertTrue(dmaWork.get(PerformanceDiagnostics.Subsystem.DMA_REPLAY)
                        + dmaWork.get(PerformanceDiagnostics.Subsystem.DMA_BATCHED_COPY) > 70_224);
                assertTrue(profile + " canonical byte copies must use the bulk DMA plane",
                        dmaWork.get(PerformanceDiagnostics.Subsystem.DMA_BATCHED_COPY) > 10_000);
                assertTrue(profile + " quiet PPU during active DMA",
                        diagnostics.snapshot().subsystemTicks()
                                .get(PerformanceDiagnostics.Subsystem.PPU_QUIET_DURING_DMA) > 10_000);
            }
        }
    }

    @Test
    public void detailedPacketsKeepPartialAndRestoredContinuations() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB_X2, Profile.CGB0_X2}) {
            try (Gameboy reference = session(Scenario.OAM_DMA, profile);
                 Gameboy candidate = session(Scenario.OAM_DMA, profile)) {
                reference.setPerformanceBatchingEnabled(false);
                candidate.setPerformanceBatchingEnabled(false);
                reference.runTicks(210_000);
                candidate.runTicks(210_000);
                candidate.setPerformanceBatchingEnabled(true);
                var savedReference = reference.captureStateWithoutTimeSource();
                var savedCandidate = candidate.captureStateWithoutTimeSource();
                for (int tail = 1; tail <= 64; tail++) {
                    reference.restoreStateSilently(savedReference);
                    candidate.restoreStateSilently(savedCandidate);
                    assertEquals(reference.runTicks(tail), candidate.runTicks(tail));
                    assertStateEquals(profile + " OAM tail " + tail,
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                    assertEquals(reference.runTicks(3_701), candidate.runTicks(3_701));
                    assertStateEquals(profile + " OAM continuation " + tail,
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                }
            }
        }
    }
}
