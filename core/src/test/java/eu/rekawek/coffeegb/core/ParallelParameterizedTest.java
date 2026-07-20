package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParallelParameterizedTest {

    private static final String THREAD_COUNT_PROPERTY = "integration.test.threadCount";

    @Test
    public void runsParametersOnBoundedConcurrentWorkers() {
        String previousThreadCount = System.getProperty(THREAD_COUNT_PROPERTY);
        System.setProperty(THREAD_COUNT_PROPERTY, "2");
        ConcurrentFixture.reset();
        try {
            Result result = JUnitCore.runClasses(ConcurrentFixture.class);

            assertTrue(result.getFailures().toString(), result.wasSuccessful());
            assertEquals(2, ConcurrentFixture.maxRunning.get());
        } finally {
            if (previousThreadCount == null) {
                System.clearProperty(THREAD_COUNT_PROPERTY);
            } else {
                System.setProperty(THREAD_COUNT_PROPERTY, previousThreadCount);
            }
        }
    }

    @RunWith(ParallelParameterized.class)
    public static class ConcurrentFixture {

        private static final AtomicInteger running = new AtomicInteger();

        private static final AtomicInteger maxRunning = new AtomicInteger();

        private static CountDownLatch bothStarted;

        public ConcurrentFixture(int ignored) {
        }

        @Parameterized.Parameters
        public static List<Object[]> parameters() {
            return List.of(new Object[]{0}, new Object[]{1});
        }

        private static void reset() {
            running.set(0);
            maxRunning.set(0);
            bothStarted = new CountDownLatch(2);
        }

        @Test
        public void overlapsWithOtherParameter() throws InterruptedException {
            int active = running.incrementAndGet();
            maxRunning.accumulateAndGet(active, Math::max);
            bothStarted.countDown();
            assertTrue("parameter instances did not overlap",
                    bothStarted.await(5, TimeUnit.SECONDS));
            running.decrementAndGet();
        }
    }
}
