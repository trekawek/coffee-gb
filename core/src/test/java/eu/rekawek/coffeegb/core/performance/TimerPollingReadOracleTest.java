package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

public class TimerPollingReadOracleTest {
    @Test
    public void recordedTimerReadsKeepTheOneDotBoundAndFullMachineStateAcrossFormsAndRestore()
            throws Exception {
        long oldSamples = 0;
        for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB_X2, Profile.CGB0_COMPAT}) {
            for (Scenario scenario : new Scenario[]{Scenario.DIV_POLL, Scenario.TIMA_POLL}) {
                for (PollingForm form : PollingForm.values()) {
                    byte[] image = pollingImage(scenario, profile, form, 0);
                    try (Gameboy scalar = session(image, profile); Gameboy candidate = session(image, profile)) {
                        prepare(scalar, candidate);
                        try (TimerPollingReadOracle oracle = new TimerPollingReadOracle(scalar, candidate)) {
                            String label = scenario + "/" + profile + "/" + form;
                            oracle.runTicks(label, 6_000);
                            assertStateEquals(label, scalar.captureStateWithoutTimeSource(),
                                    candidate.captureStateWithoutTimeSource());
                            assertTrue(label + " active CPU reads", oracle.timerReads() > 100);
                            assertTrue(label + " real result stores", oracle.writes() > 100);
                            assertTrue(label + " retained batched work", candidate.getPerformanceEpochTicks() > 300);
                            var a = scalar.captureStateWithoutTimeSource();
                            var b = candidate.captureStateWithoutTimeSource();
                            scalar.runTicks(37); candidate.runTicks(37);
                            scalar.restoreStateSilently(a); candidate.restoreStateSilently(b);
                            for (int n : new int[]{1, 2, 3, 7, 23, 54, 97, 509}) {
                                oracle.runTicks(label + " restored " + n, n);
                                assertStateEquals(label + " restored " + n,
                                        scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                            }
                            oldSamples += oracle.previousDotReads();
                        }
                    }
                }
            }
        }
        assertTrue("test must observe the historical old-dot sampling seam", oldSamples > 0);
    }

    @Test
    public void oracleRejectsAnOutOfContractTimerValueAndAnyShiftedStoreDot() throws Exception {
        for (boolean damageWriteDot : new boolean[]{false, true}) {
            byte[] image = pollingImage(Scenario.TIMA_POLL, Profile.DMG, PollingForm.MIXED, 0);
            try (Gameboy scalar = session(image, Profile.DMG); Gameboy candidate = session(image, Profile.DMG)) {
                prepare(scalar, candidate);
                try (TimerPollingReadOracle oracle = new TimerPollingReadOracle(scalar, candidate)) {
                    AssertionError failure = assertThrows(AssertionError.class,
                            () -> oracle.runTicks("intentional oracle counterexample", 2_000, trace -> {
                                for (int i = 0; i < trace.size(); i++) {
                                    var event = trace.get(i);
                                    if (damageWriteDot && event.write()) {
                                        trace.set(i, new TimerPollingReadOracle.Transaction(event.dot() + 1,
                                                event.address(), event.value(), true));
                                        return;
                                    } else if (!damageWriteDot && !event.write() && event.address() == 0xff05) {
                                        trace.set(i, new TimerPollingReadOracle.Transaction(event.dot(),
                                                event.address(), (event.value() + 0x40) & 255, false));
                                        return;
                                    }
                                }
                                fail("counterexample did not find its real CPU transaction");
                            }));
                    assertTrue(failure.getMessage(), failure.getMessage().contains(damageWriteDot
                            ? "write dot" : "current/previous-dot contract"));
                }
            }
        }
    }

    private static Gameboy session(byte[] image, Profile profile) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L).setPlayerInputSource(new PlayerInputHub())
                .setSupportBatterySave(false).build();
    }

    private static void prepare(Gameboy scalar, Gameboy candidate) {
        scalar.setPerformanceBatchingEnabled(false); candidate.setPerformanceBatchingEnabled(false);
        assertEquals(scalar.runTicks(210_000), candidate.runTicks(210_000));
        assertStateEquals("common scalar entry", scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
        candidate.setPerformanceBatchingEnabled(true); candidate.resetPerformanceBulkCounters();
    }
}
