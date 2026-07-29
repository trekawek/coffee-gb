package eu.rekawek.coffeegb.core.serial.mobile;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendCompletion;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendGeneration;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendRequest;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.CompletionResult;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.OfferResult;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeterministicMobileAdapterBackendConcurrencyTest {

    private static final int RACE_ROUNDS = 2_048;

    // Large enough to exercise the former check-then-copy race without stressing the full suite.
    private static final byte[] RACE_PAYLOAD = new byte[4_096];

    @Test(timeout = 30_000)
    public void cancellationIsAtomicWithOfferCompleteAndPoll() throws Exception {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < RACE_ROUNDS; round++) {
                int requestId = round;
                backend.cancelAll();
                BackendGeneration offeredGeneration = backend.generation();
                BackendRequest offeredRequest =
                        new BackendRequest(requestId, 0x12, RACE_PAYLOAD);
                OfferResult offerResult = raceCancellation(executor, round,
                        () -> backend.offer(offeredGeneration, offeredRequest),
                        backend);
                assertTrue(offerResult == OfferResult.ACCEPTED
                        || offerResult == OfferResult.STALE_GENERATION);
                assertEmpty(backend);
                assertEquals(OfferResult.STALE_GENERATION,
                        backend.offer(offeredGeneration,
                                new BackendRequest(requestId, 0x12, new byte[0])));

                BackendGeneration completedGeneration = backend.generation();
                assertEquals(OfferResult.ACCEPTED,
                        backend.offer(completedGeneration,
                                new BackendRequest(requestId, 0x12, RACE_PAYLOAD)));
                CompletionResult completionResult = raceCancellation(executor, round,
                        () -> backend.complete(completedGeneration, requestId, RACE_PAYLOAD),
                        backend);
                assertTrue(completionResult == CompletionResult.COMPLETED
                        || completionResult == CompletionResult.STALE_GENERATION);
                assertEmpty(backend);
                assertEquals(CompletionResult.STALE_GENERATION,
                        backend.complete(completedGeneration, requestId, new byte[0]));

                BackendGeneration polledGeneration = backend.generation();
                assertEquals(OfferResult.ACCEPTED,
                        backend.offer(polledGeneration,
                                new BackendRequest(requestId, 0x12, new byte[0])));
                assertEquals(CompletionResult.COMPLETED,
                        backend.complete(polledGeneration, requestId, RACE_PAYLOAD));
                BackendCompletion completion = raceCancellation(executor, round,
                        backend::poll, backend);
                assertTrue(completion == null || completion.requestId() == requestId);
                assertEmpty(backend);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        BackendGeneration generation = backend.generation();
        assertEquals(OfferResult.ACCEPTED,
                backend.offer(generation, new BackendRequest(9000, 0x12,
                        new byte[]{1, 2, 3})));
        assertEquals(1, backend.occupiedRequestSlots());
        assertEquals(1, backend.pendingRequests());
        assertEquals(0, backend.completedResults());
        assertEquals(3, backend.bufferedBytes());

        assertEquals(CompletionResult.COMPLETED,
                backend.complete(generation, 9000, new byte[]{4, 5}));
        assertEquals(1, backend.occupiedRequestSlots());
        assertEquals(0, backend.pendingRequests());
        assertEquals(1, backend.completedResults());
        assertEquals(2, backend.bufferedBytes());

        BackendCompletion completion = backend.poll();
        assertNotNull(completion);
        assertEquals(9000, completion.requestId());
        assertArrayEquals(new byte[]{4, 5}, completion.payload());
        assertNull(backend.poll());
        assertEmpty(backend);
    }

    private static <T> T raceCancellation(ExecutorService executor, int round,
                                          Callable<T> operation,
                                          DeterministicMobileAdapterBackend backend)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<T> operationFuture = executor.submit(() -> {
            ready.countDown();
            start.await();
            return operation.call();
        });
        Future<?> cancellationFuture = executor.submit(() -> {
            ready.countDown();
            start.await();
            int spins = 1 << (round & 7);
            for (int i = 0; i < spins; i++) Thread.onSpinWait();
            backend.cancelAll();
            return null;
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        T result = operationFuture.get(10, TimeUnit.SECONDS);
        cancellationFuture.get(10, TimeUnit.SECONDS);
        return result;
    }

    private static void assertEmpty(DeterministicMobileAdapterBackend backend) {
        assertEquals(0, backend.occupiedRequestSlots());
        assertEquals(0, backend.pendingRequests());
        assertEquals(0, backend.completedResults());
        assertEquals(0, backend.bufferedBytes());
        assertNull(backend.poll());
    }
}
