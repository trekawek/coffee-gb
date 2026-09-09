package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MobileAdapterPerformanceSpanTest {

    private static final byte[] BEGIN = packet(0x10,
            "NINTENDO".getBytes(StandardCharsets.US_ASCII));

    @Test
    public void exactAndRationalTimeoutPhasesPreserveStateAndKeepBothEdgesScalar()
            throws Exception {
        for (ClockSpec clock : new ClockSpec[]{ClockSpec.LEGACY, ClockSpec.SGB, ClockSpec.SGB2}) {
            long floor = clock.ticksForMilliseconds(3_000, ClockSpec.Rounding.FLOOR);
            for (boolean partial : new boolean[]{false, true}) {
                for (int offset = 0; offset <= 54; offset++) {
                    MobileAdapterEngine seed = engine(clock);
                    if (partial) seed.acceptByte(0x99);
                    else seed.observeSerialActivity();
                    seed.advanceTicks(floor - offset);
                    for (int budget = 1; budget <= 54; budget++) {
                        MobileAdapterEngine scalar = engine(clock);
                        MobileAdapterEngine bulk = engine(clock);
                        scalar.restoreState(seed.captureState());
                        bulk.restoreState(seed.captureState());
                        int span = bulk.performanceQuietSpanLimit(budget);
                        if (span > 0) {
                            var outcome = scalar.snapshot().outcome();
                            for (int tick = 0; tick < span; tick++) {
                                scalar.tick();
                                assertEquals("admitted a visible timeout edge", outcome,
                                        scalar.snapshot().outcome());
                            }
                            bulk.tickPerformanceQuietSpanTrusted(span);
                            assertStateEquals(scalar.captureState(), bulk.captureState());
                        }
                        // The first excluded tick owns boundary outcome and timeout cancellation.
                        scalar.tick();
                        bulk.tick();
                        assertStateEquals(scalar.captureState(), bulk.captureState());
                        assertEquals(scalar.performanceQuietSpanLimit(54),
                                bulk.performanceQuietSpanLimit(54));
                    }
                }
            }
        }
    }

    @Test
    public void everyWirePhaseAndPartialByteRestoreRetainsRepliesWithSustainedSpans()
            throws Exception {
        MobileAdapterSerialEndpoint scalar = endpoint(ClockSpec.LEGACY);
        MobileAdapterSerialEndpoint bulk = endpoint(ClockSpec.LEGACY);
        List<Integer> outgoing = new ArrayList<>();
        for (byte value : BEGIN) outgoing.add(value & 0xff);
        outgoing.addAll(List.of(0x80, 0, 0, 0x4b));
        for (int i = 0; i < BEGIN.length; i++) outgoing.add(0x4b);
        outgoing.addAll(List.of(0x80, 0x10));
        for (byte value : packet(0x1a, new byte[]{7, 0x55})) outgoing.add(value & 0xff);
        outgoing.addAll(List.of(0x80, 0, 0, 0x4b));
        for (int i = 0; i < 8; i++) outgoing.add(0x4b);
        outgoing.addAll(List.of(0x80, 0x1a));
        int batched = 0;
        for (int index = 0; index < outgoing.size(); index++) {
            scalar.setSb(outgoing.get(index));
            bulk.setSb(outgoing.get(index));
            scalar.startSending();
            bulk.startSending();
            for (int bit = 0; bit < 8; bit++) {
                int remaining = 1 + (index * 19 + bit * 37) % 512;
                while (remaining > 0) {
                    int span = bulk.performanceInternalClockSpanLimit(Math.min(remaining, 54));
                    assertTrue("synchronous byte wait unexpectedly went scalar", span > 0);
                    for (int tick = 0; tick < span; tick++) scalar.tick();
                    bulk.tickPerformanceInternalClockSpanTrusted(span);
                    remaining -= span;
                    batched += span;
                }
                if (bit == 3) {
                    var state = bulk.captureState();
                    MobileAdapterSerialEndpoint restored = endpoint(ClockSpec.LEGACY);
                    restored.restoreState(state);
                    bulk = restored;
                }
                assertStateEquals(scalar.captureState(), bulk.captureState());
                assertEquals(scalar.sendBit(), bulk.sendBit());
                assertStateEquals(scalar.captureState(), bulk.captureState());
            }
        }
        assertEquals(0x55, bulk.configurationCopy()[7] & 0xff);
        assertTrue("wire activity should not force per-tick endpoint work", batched > 70_000);
    }

    @Test
    public void timeoutNormalizesOnlyAfterFinishingTheQuietPrefixOfARestoredPartialByte()
            throws Exception {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        MobileAdapterSerialEndpoint scalar = endpoint(clock);
        MobileAdapterSerialEndpoint bulk = endpoint(clock);
        for (MobileAdapterSerialEndpoint endpoint : new MobileAdapterSerialEndpoint[]{scalar, bulk}) {
            endpoint.setSb(0x99);
            endpoint.startSending();
            for (int bit = 0; bit < 3; bit++) endpoint.sendBit();
        }
        assertEquals(3_000, bulk.performanceQuietSpanLimit(Integer.MAX_VALUE));
        bulk.tickPerformanceQuietSpanTrusted(2_997);
        for (int tick = 0; tick < 2_997; tick++) scalar.tick();
        var state = bulk.captureState();
        bulk = endpoint(clock);
        bulk.restoreState(state);
        assertEquals(3, bulk.performanceQuietSpanLimit(54));
        bulk.tickPerformanceQuietSpanTrusted(3);
        for (int tick = 0; tick < 3; tick++) scalar.tick();
        assertStateEquals(scalar.captureState(), bulk.captureState());
        assertEquals(0, bulk.performanceQuietSpanLimit(1));
        scalar.tick();
        bulk.tick();
        assertStateEquals(scalar.captureState(), bulk.captureState());
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET, bulk.snapshot().outcome());
        // Timeout invalidates the transaction while the already-latched D2 reply finishes.
        int reply = 0b110;
        for (int bit = 3; bit < 8; bit++) {
            int scalarBit = scalar.sendBit();
            int bulkBit = bulk.sendBit();
            assertEquals(scalarBit, bulkBit);
            reply = reply << 1 | bulkBit;
        }
        assertEquals(0xd2, reply);
        assertStateEquals(scalar.captureState(), bulk.captureState());
        assertEquals(0, bulk.snapshot().retainedBytes());
        assertEquals(54, bulk.performanceQuietSpanLimit(54));
    }

    @Test
    public void publishedBackendCompletionWaitsForByteBoundaryAndTimeoutCancellationStaysScalar() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        for (boolean complete : new boolean[]{false, true}) {
            CountingBackend backend = new CountingBackend();
            MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                    clock, 8, new byte[256], backend);
            beginSession(endpoint);
            sendRequest(endpoint, packet(0x28,
                    "fixture.test".getBytes(StandardCharsets.US_ASCII)));
            exchange(endpoint, 0); // turnaround
            exchange(endpoint, 0x4b); // validated response gate
            assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                    endpoint.snapshot().outcome());
            int calls = backend.calls;
            int cancels = backend.cancels;
            assertTrue(endpoint.tickPerformanceQuietSpan(1_000));
            if (complete) {
                assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                        backend.delegate.complete(backend.delegate.generation(), 0,
                                new byte[]{127, 0, 0, 1}));
            }
            assertEquals(2_000, endpoint.performanceExternalClockWaitSpanLimit(Integer.MAX_VALUE));
            assertTrue(endpoint.tickPerformanceQuietSpan(2_000));
            assertEquals("span queried or consumed backend state", calls, backend.calls);
            assertEquals(0, endpoint.performanceQuietSpanLimit(1));
            assertFalse(endpoint.tickPerformanceQuietSpan(1));
            assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                    endpoint.snapshot().outcome());
            if (complete) {
                // A byte start resets the idle clock and consumes the already-published result.
                endpoint.setSb(0x4b);
                endpoint.startSending();
                assertTrue(backend.calls > calls);
                assertEquals(cancels, backend.cancels);
                assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                        endpoint.snapshot().outcome());
                int incoming = 0;
                for (int bit = 0; bit < 8; bit++) incoming = incoming << 1 | endpoint.sendBit();
                assertEquals(0x99, incoming);
                assertEquals(54, endpoint.performanceQuietSpanLimit(54));
            } else {
                endpoint.tick();
                assertEquals(cancels + 1, backend.cancels);
                assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                        endpoint.snapshot().outcome());
                assertEquals(Integer.MAX_VALUE,
                        endpoint.performanceQuietSpanLimit(Integer.MAX_VALUE));
            }
        }
    }

    private static void beginSession(MobileAdapterSerialEndpoint endpoint) {
        sendRequest(endpoint, BEGIN);
        exchange(endpoint, 0);
        exchange(endpoint, 0x4b);
        for (int i = 0; i < BEGIN.length; i++) exchange(endpoint, 0x4b);
        exchange(endpoint, 0x80);
        exchange(endpoint, 0x10);
        assertEquals(MobileAdapterEngine.Phase.SESSION, endpoint.snapshot().phase());
    }

    private static void sendRequest(MobileAdapterSerialEndpoint endpoint, byte[] request) {
        for (byte value : request) exchange(endpoint, value & 0xff);
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertEquals((request[2] & 0xff) ^ 0x80, exchange(endpoint, 0));
    }

    private static int exchange(MobileAdapterSerialEndpoint endpoint, int value) {
        endpoint.setSb(value);
        endpoint.startSending();
        int result = 0;
        for (int bit = 0; bit < 8; bit++) result = result << 1 | endpoint.sendBit();
        return result;
    }

    private static byte[] packet(int command, byte[] data) {
        byte[] result = new byte[data.length + 8];
        result[0] = (byte) 0x99;
        result[1] = 0x66;
        result[2] = (byte) command;
        result[4] = (byte) (data.length >>> 8);
        result[5] = (byte) data.length;
        System.arraycopy(data, 0, result, 6, data.length);
        int checksum = 0;
        for (int i = 2; i < result.length - 2; i++) checksum += result[i] & 0xff;
        result[result.length - 2] = (byte) (checksum >>> 8);
        result[result.length - 1] = (byte) checksum;
        return result;
    }

    private static MobileAdapterEngine engine(ClockSpec clock) {
        return new MobileAdapterEngine(clock, 8, new byte[256]);
    }

    private static MobileAdapterSerialEndpoint endpoint(ClockSpec clock) {
        return new MobileAdapterSerialEndpoint(clock, 8, new byte[256]);
    }

    private static void assertStateEquals(Object expected, Object actual) throws Exception {
        assertEquals(expected.getClass(), actual.getClass());
        if (expected instanceof byte[] bytes) {
            assertArrayEquals(bytes, (byte[]) actual);
        } else if (expected.getClass().isRecord()) {
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertStateEquals(accessor.invoke(expected), accessor.invoke(actual));
            }
        } else {
            assertEquals(expected, actual);
        }
    }

    private static final class CountingBackend implements MobileAdapterBackendPort {
        private final DeterministicMobileAdapterBackend delegate =
                new DeterministicMobileAdapterBackend();
        private int calls;
        private int cancels;

        @Override
        public BackendGeneration generation() {
            calls++;
            return delegate.generation();
        }

        @Override
        public OfferResult offer(BackendGeneration generation, BackendRequest request) {
            calls++;
            return delegate.offer(generation, request);
        }

        @Override
        public CompletionResult complete(BackendGeneration generation, long requestId,
                BackendStatus status, byte[] response) {
            calls++;
            return delegate.complete(generation, requestId, status, response);
        }

        @Override
        public BackendCompletion poll(BackendGeneration expectedGeneration) {
            calls++;
            return delegate.poll(expectedGeneration);
        }

        @Override
        public void cancelAll() {
            calls++;
            cancels++;
            delegate.cancelAll();
        }

        @Override
        public int occupiedRequestSlots() {
            calls++;
            return delegate.occupiedRequestSlots();
        }

        @Override
        public int bufferedBytes() {
            calls++;
            return delegate.bufferedBytes();
        }
    }
}
