package eu.rekawek.coffeegb.core.integration.support;

import org.junit.runners.Parameterized;
import org.junit.runners.model.RunnerScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs independent JUnit parameter sets with the integration-test thread limit. */
public final class ParallelParameterized extends Parameterized {

    private static final String THREAD_COUNT_PROPERTY = "integration.test.threadCount";

    public ParallelParameterized(Class<?> testClass) throws Throwable {
        super(testClass);
        setScheduler(new FixedThreadPoolScheduler(threadCount()));
    }

    private static int threadCount() {
        String value = System.getProperty(THREAD_COUNT_PROPERTY, "2");
        final int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    THREAD_COUNT_PROPERTY + " must be an integer: " + value, e);
        }
        if (count < 1) {
            throw new IllegalArgumentException(
                    THREAD_COUNT_PROPERTY + " must be at least 1: " + count);
        }
        return count;
    }

    private static final class FixedThreadPoolScheduler implements RunnerScheduler {

        private final ExecutorService executor;

        private final List<Future<?>> tasks = new ArrayList<>();

        private FixedThreadPoolScheduler(int threadCount) {
            executor = Executors.newFixedThreadPool(threadCount);
        }

        @Override
        public void schedule(Runnable childStatement) {
            tasks.add(executor.submit(childStatement));
        }

        @Override
        public void finished() {
            executor.shutdown();
            try {
                for (Future<?> task : tasks) {
                    task.get();
                }
                if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                    throw new IllegalStateException("Parameterized tests did not finish");
                }
            } catch (ExecutionException e) {
                throw new IllegalStateException("Parameterized test worker failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for tests", e);
            }
        }
    }
}
