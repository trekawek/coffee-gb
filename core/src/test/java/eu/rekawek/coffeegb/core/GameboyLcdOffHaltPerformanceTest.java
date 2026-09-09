package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.Profile;
import eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.Scenario;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class GameboyLcdOffHaltPerformanceTest {
    @Test
    public void everyNormalSpeedFamilyBatchesHaltAndKeepsExactBlankCallbacksAcrossRestore()
            throws Exception {
        for (Profile profile : Profile.values()) {
            if (profile.doubleSpeed) continue;
            try (Gameboy scalar = settled(profile); Gameboy candidate = settled(profile)) {
                candidate.setPerformanceBatchingEnabled(true);
                var scalarStart = scalar.captureStateWithoutTimeSource();
                var candidateStart = candidate.captureStateWithoutTimeSource();
                int toInitialBlank = Gameboy.LCD_OFF_BLANK_DELAY - lcdOffTicks(candidate);
                for (int tail = -1; tail <= 1; tail++) {
                    scalar.restoreStateSilently(scalarStart);
                    candidate.restoreStateSilently(candidateStart);
                    int ticks = toInitialBlank + tail;
                    int expectedFrames = tail < 0 ? 0 : 1;
                    assertEquals(profile + " scalar initial blank " + tail,
                            expectedFrames, scalar.runTicks(ticks));
                    assertEquals(profile + " batched initial blank " + tail,
                            expectedFrames, candidate.runTicks(ticks));
                    same(profile + " initial blank " + tail, scalar, candidate);
                }
                scalar.restoreStateSilently(scalarStart);
                candidate.restoreStateSilently(candidateStart);
                assertEquals(1, scalar.runTicks(toInitialBlank));
                assertEquals(1, candidate.runTicks(toInitialBlank));
                for (int frame = 0; frame < 3; frame++) {
                    int preceding = candidate.getClockSpec().controllerTicksPerFrame() - 1;
                    assertEquals(0, scalar.runTicks(preceding));
                    assertEquals(0, candidate.runTicks(preceding));
                    same(profile + " before recurring blank " + frame, scalar, candidate);
                    var checkpoint = candidate.captureStateWithoutTimeSource();
                    candidate.restoreStateSilently(checkpoint);
                    assertEquals(1, scalar.runTicks(1));
                    assertEquals(1, candidate.runTicks(1));
                    same(profile + " recurring blank " + frame, scalar, candidate);
                }
                assertTrue(profile + " lost sustained LCD-off HALT batching",
                        candidate.getPerformanceBulkTicks()
                                > 2L * candidate.getClockSpec().controllerTicksPerFrame());
            }
        }
    }

    @Test
    public void arbitraryHaltPacketTailsAndClockPhasesMatchScalarAfterRestore() throws Exception {
        for (Profile profile : Profile.values()) {
            if (profile.doubleSpeed) continue;
            try (Gameboy scalar = settled(profile); Gameboy candidate = settled(profile)) {
                candidate.setPerformanceBatchingEnabled(true);
                for (int phase = 0; phase < 4; phase++) {
                    var scalarStart = scalar.captureStateWithoutTimeSource();
                    var candidateStart = candidate.captureStateWithoutTimeSource();
                    for (int tail = 1; tail <= 64; tail++) {
                        scalar.restoreStateSilently(scalarStart);
                        candidate.restoreStateSilently(candidateStart);
                        assertEquals(scalar.runTicks(tail), candidate.runTicks(tail));
                        same(profile + " phase=" + phase + " tail=" + tail, scalar, candidate);
                    }
                    scalar.restoreStateSilently(scalarStart);
                    candidate.restoreStateSilently(candidateStart);
                    scalar.runTicks(1);
                    candidate.runTicks(1);
                }
                assertTrue(profile + " tails never batched", candidate.getPerformanceBulkTicks() > 0);
            }
        }
    }

    @Test
    public void timerOverflowWakeAndFollowingHaltBugRemainScalarAtEveryBoundary() throws Exception {
        for (Profile profile : Profile.values()) {
            if (profile.doubleSpeed) continue;
            try (Gameboy scalar = settled(profile); Gameboy candidate = settled(profile)) {
                for (Gameboy gameboy : new Gameboy[]{scalar, candidate}) {
                    var bus = gameboy.getAddressSpace();
                    bus.setByte(0xffff, 4);
                    bus.setByte(0xff0f, 0);
                    bus.setByte(0xff06, 0x73);
                    bus.setByte(0xff05, 0xfe);
                    bus.setByte(0xff07, 5);
                }
                candidate.setPerformanceBatchingEnabled(true);
                var scalarStart = scalar.captureStateWithoutTimeSource();
                var candidateStart = candidate.captureStateWithoutTimeSource();
                for (int tail = 1; tail <= 96; tail++) {
                    scalar.restoreStateSilently(scalarStart);
                    candidate.restoreStateSilently(candidateStart);
                    assertEquals(scalar.runTicks(tail), candidate.runTicks(tail));
                    same(profile + " timer wake tail=" + tail, scalar, candidate);
                }
                assertTrue("fixture failed to request timer interrupt",
                        (candidate.getAddressSpace().getByte(0xff0f) & 4) != 0);
                assertTrue(profile + " timer prefixes never batched", candidate.getPerformanceBulkTicks() > 0);
            }
        }
    }

    private static Gameboy settled(Profile profile) throws Exception {
        Gameboy gameboy = PerformanceWorkloads.session(Scenario.LCD_OFF_HALT, profile);
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.runTicks(400);
        assertFalse(gameboy.getGpu().isLcdEnabled());
        assertTrue(profile + " HALT fixture never settled", gameboy.getCpu().performanceSettledHaltSpanEligible());
        return gameboy;
    }

    private static int lcdOffTicks(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("lcdOffTicks");
        field.setAccessible(true);
        return field.getInt(gameboy);
    }

    private static void same(String label, Gameboy scalar, Gameboy candidate) {
        assertStateEquals(label, scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }
}
