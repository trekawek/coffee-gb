package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HalfDotClockRouterTest {

    @Test
    public void constantsDescribeTheHalfDotAndFixedDomainRates() {
        assertEquals(8_388_608, HalfDotClockRouter.HALF_DOT_QUANTA_PER_SECOND);
        assertEquals(4_194_304, HalfDotClockRouter.FIXED_DOMAIN_CLOCKS_PER_SECOND);
        assertEquals(
                HalfDotClockRouter.HALF_DOT_QUANTA_PER_SECOND,
                2 * HalfDotClockRouter.FIXED_DOMAIN_CLOCKS_PER_SECOND);
    }

    @Test
    public void completePeriodsHaveExactDmgAndCgbClockRatesFromEitherInitialPhase() {
        for (HalfDotClockRouter.Phase initial : HalfDotClockRouter.Phase.values()) {
            // false covers both DMG and CGB normal speed: their routing is intentionally identical.
            assertPeriodCounts(initial, false, 1_024, 512, 512);
            assertPeriodCounts(initial, true, 1_024, 512, 1_024);
        }
    }

    @Test
    public void resolvedStrobesDoNotAdvanceOrHideTheCommittedPhase() {
        HalfDotClockRouter router = new HalfDotClockRouter(
                HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE);

        router.resolve(false);

        assertEquals(HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE, router.phase());
        assertEquals(HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES,
                router.nextPhase());
        assertTrue(router.fixedDomainClockEnable());
        assertTrue(router.cpuDomainClockEnable());
        router.commit();
        assertEquals(HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES,
                router.phase());
        assertFalse(router.fixedDomainClockEnable());
        assertFalse(router.cpuDomainClockEnable());
    }

    @Test
    public void speedInputChangesOnlyCpuRoutingAtTheSameResolvedQuantum() {
        HalfDotClockRouter router = new HalfDotClockRouter(
                HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES);

        router.resolve(false);
        assertFalse(router.fixedDomainClockEnable());
        assertFalse(router.cpuDomainClockEnable());
        assertEquals(HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES,
                router.phase());

        router.resolve(true);
        assertFalse(router.fixedDomainClockEnable());
        assertTrue(router.cpuDomainClockEnable());
        assertEquals(HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES,
                router.phase());
    }

    @Test
    public void everyPossibleShortSpeedWindowLeavesFixedPhaseFreeRunning() {
        int quanta = 32;
        for (HalfDotClockRouter.Phase initial : HalfDotClockRouter.Phase.values()) {
            for (int doubleStart = 0; doubleStart <= quanta; doubleStart++) {
                for (int doubleEnd = doubleStart; doubleEnd <= quanta; doubleEnd++) {
                    HalfDotClockRouter switched = new HalfDotClockRouter(initial);
                    HalfDotClockRouter fixedReference = new HalfDotClockRouter(initial);
                    int fixedEdges = 0;
                    int cpuEdges = 0;
                    for (int quantum = 0; quantum < quanta; quantum++) {
                        boolean doubleSpeed = quantum >= doubleStart && quantum < doubleEnd;
                        switched.resolve(doubleSpeed);
                        fixedReference.resolve(false);

                        assertEquals(fixedReference.phase(), switched.phase());
                        assertEquals(fixedReference.nextPhase(), switched.nextPhase());
                        assertEquals(fixedReference.fixedDomainClockEnable(),
                                switched.fixedDomainClockEnable());
                        assertEquals(doubleSpeed || switched.fixedDomainClockEnable(),
                                switched.cpuDomainClockEnable());
                        if (switched.fixedDomainClockEnable()) {
                            fixedEdges++;
                        }
                        if (switched.cpuDomainClockEnable()) {
                            cpuEdges++;
                        }
                        switched.commit();
                        fixedReference.commit();
                    }

                    assertEquals(quanta / 2, fixedEdges);
                    int doubleQuanta = doubleEnd - doubleStart;
                    int normalFixedEdges = countFixedEdgesOutsideWindow(
                            initial, quanta, doubleStart, doubleEnd);
                    assertEquals(doubleQuanta + normalFixedEdges, cpuEdges);
                    assertEquals(initial, switched.phase());
                }
            }
        }
    }

    @Test
    public void restoringEitherPhaseReplaysAnIdenticalMixedSpeedTrace() {
        boolean[] speedTrace = {
                false, false, true, true, true, false, true, false, false, true
        };
        for (HalfDotClockRouter.Phase restored : HalfDotClockRouter.Phase.values()) {
            HalfDotClockRouter reference = new HalfDotClockRouter(restored);
            HalfDotClockRouter replay = new HalfDotClockRouter(restored == HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE
                    ? HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES
                    : HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE);
            replay.resolve(true);
            replay.restore(restored);

            for (boolean doubleSpeed : speedTrace) {
                reference.resolve(doubleSpeed);
                replay.resolve(doubleSpeed);
                assertEquals(reference.phase(), replay.phase());
                assertEquals(reference.nextPhase(), replay.nextPhase());
                assertEquals(reference.fixedDomainClockEnable(), replay.fixedDomainClockEnable());
                assertEquals(reference.cpuDomainClockEnable(), replay.cpuDomainClockEnable());
                reference.commit();
                replay.commit();
            }
        }
    }

    @Test
    public void initialAndRestoredPhaseMustBeExplicit() {
        assertThrows(NullPointerException.class, () -> new HalfDotClockRouter(null));
        HalfDotClockRouter router = new HalfDotClockRouter(
                HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE);
        assertThrows(NullPointerException.class, () -> router.restore(null));
    }

    private static void assertPeriodCounts(
            HalfDotClockRouter.Phase initial,
            boolean doubleSpeed,
            int quanta,
            int expectedFixedEdges,
            int expectedCpuEdges) {
        HalfDotClockRouter router = new HalfDotClockRouter(initial);
        int fixedEdges = 0;
        int cpuEdges = 0;
        for (int quantum = 0; quantum < quanta; quantum++) {
            router.resolve(doubleSpeed);
            if (router.fixedDomainClockEnable()) {
                fixedEdges++;
            }
            if (router.cpuDomainClockEnable()) {
                cpuEdges++;
            }
            router.commit();
        }
        assertEquals(expectedFixedEdges, fixedEdges);
        assertEquals(expectedCpuEdges, cpuEdges);
        assertEquals(initial, router.phase());
    }

    private static int countFixedEdgesOutsideWindow(
            HalfDotClockRouter.Phase initial, int quanta, int windowStart, int windowEnd) {
        int count = 0;
        for (int quantum = 0; quantum < quanta; quantum++) {
            boolean fixedEdge = (quantum & 1) == (initial == HalfDotClockRouter.Phase.FIXED_DOMAIN_EDGE ? 0 : 1);
            if (fixedEdge && (quantum < windowStart || quantum >= windowEnd)) {
                count++;
            }
        }
        return count;
    }
}
