package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import org.junit.Test;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** Repeated polling must retain useful epochs for every STAT interrupt-source combination. */
public class PerformanceStatMaskWorkloadTest {
    @Test
    public void everyStatSourceCombinationRetainsPollingAcrossPhysicalTimingFamilies() throws Exception {
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB, Profile.CGB_X2,
                Profile.CGB0_COMPAT, Profile.SGB}) {
            for (int enables = 0; enables < 16; enables++) {
                for (int lyc : new int[]{0, 143, 153}) {
                    String label = profile + "/" + enables + "/" + lyc;
                    try (Gameboy scalar = session(Scenario.STAT_POLL, profile, enables << 3, lyc);
                         Gameboy batched = session(Scenario.STAT_POLL, profile, enables << 3, lyc)) {
                        scalar.setPerformanceBatchingEnabled(false);
                        batched.setPerformanceBatchingEnabled(false);
                        scalar.runTicks(70_224);
                        batched.runTicks(70_224);
                        batched.setPerformanceBatchingEnabled(true);
                        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(70_224);
                        batched.setPerformanceDiagnostics(diagnostics);
                        assertEquals(label, scalar.runTicks(140_448), batched.runTicks(140_448));
                        assertStateEquals(label, scalar.captureStateWithoutTimeSource(),
                                batched.captureStateWithoutTimeSource());
                        for (var window : diagnostics.snapshot().windows()) {
                            assertTrue(label + " sustained polling lost useful epochs: " + window,
                                    window.epochTicks() >= window.ticks() / 10);
                        }
                    }
                }
            }
        }
    }
    @Test
    public void everyStatMaskKeepsPollingWhenImeIsOffButStatRequestsRemainEnabled() throws Exception {
        for (Profile profile : Profile.values()) {
            for (int enables = 0; enables < 16; enables++) {
                String label = "IME-off enabled STAT " + profile + '/' + enables;
                try (Gameboy scalar = session(Scenario.STAT_POLL, profile, enables << 3, 0);
                     Gameboy batched = session(Scenario.STAT_POLL, profile, enables << 3, 0)) {
                    scalar.setPerformanceBatchingEnabled(false);
                    batched.setPerformanceBatchingEnabled(false);
                    scalar.runTicks(70_224);
                    batched.runTicks(70_224);
                    for (Gameboy gameboy : new Gameboy[]{scalar, batched}) {
                        gameboy.getAddressSpace().setByte(0xffff, 2);
                        gameboy.getAddressSpace().setByte(0xff0f, 0);
                        gameboy.runTicks(16);
                    }
                    batched.setPerformanceBatchingEnabled(true);
                    for (int frame = 0; frame < 3; frame++) {
                        long before = batched.getPerformanceEpochTicks();
                        assertEquals(label, scalar.runTicks(70_224), batched.runTicks(70_224));
                        assertStateEquals(label + " frame " + frame,
                                scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());
                        assertTrue(label + " permanently lost epochs at frame " + frame,
                                batched.getPerformanceEpochTicks() - before > 7_000);
                    }
                }
            }
        }
    }

}
