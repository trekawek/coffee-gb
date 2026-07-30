package eu.rekawek.coffeegb.core.serial.mobile;

import java.util.Objects;

/**
 * Nonblocking request/result ownership seam between the deterministic engine and a backend.
 *
 * <p>Controller code may enqueue host work behind this interface, but no implementation may block
 * the emulator thread and no port object is part of captured engine state. Every request and
 * completion belongs to one identity-only generation. Expected-generation polling makes removing
 * a completion atomic with respect to cancellation; the generation carried by the returned value
 * lets the engine reject ownership that became stale before it was applied.
 */
public interface MobileAdapterBackendPort {

    int MAX_REQUEST_SLOTS = 8;

    int MAX_BUFFERED_BYTES = 65_536;

    BackendGeneration DISCONNECTED_GENERATION = BackendGeneration.create();

    MobileAdapterBackendPort DISCONNECTED = new MobileAdapterBackendPort() {
        @Override
        public BackendGeneration generation() {
            return DISCONNECTED_GENERATION;
        }

        @Override
        public OfferResult offer(BackendGeneration generation, BackendRequest request) {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(request, "request");
            return OfferResult.UNAVAILABLE;
        }

        @Override
        public CompletionResult complete(BackendGeneration generation, long requestId,
                                         BackendStatus status, byte[] response) {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(response, "response");
            return CompletionResult.UNAVAILABLE;
        }

        @Override
        public BackendCompletion poll(BackendGeneration expectedGeneration) {
            Objects.requireNonNull(expectedGeneration, "expectedGeneration");
            return null;
        }

        @Override
        public void cancelAll() {
        }

        @Override
        public int occupiedRequestSlots() {
            return 0;
        }

        @Override
        public int bufferedBytes() {
            return 0;
        }
    };

    /** Opaque ownership token captured by a worker before a request is admitted. */
    BackendGeneration generation();

    OfferResult offer(BackendGeneration generation, BackendRequest request);

    CompletionResult complete(BackendGeneration generation, long requestId, BackendStatus status,
                              byte[] response);

    /** Source-compatible success shortcut for Phase-1 callers and simple fake backends. */
    default CompletionResult complete(BackendGeneration generation, long requestId,
                                      byte[] response) {
        return complete(generation, requestId, BackendStatus.SUCCESS, response);
    }

    /**
     * Returns and releases the oldest completion only while {@code expectedGeneration} still owns
     * the backend, or {@code null} when none is ready or cancellation replaced that generation.
     */
    BackendCompletion poll(BackendGeneration expectedGeneration);

    /** Source-compatible atomic poll using the generation visible at method entry. */
    default BackendCompletion poll() {
        return poll(generation());
    }

    /** Idempotently cancels and releases all queued requests and results. */
    void cancelAll();

    int occupiedRequestSlots();

    int bufferedBytes();

    enum OfferResult {
        ACCEPTED,
        UNAVAILABLE,
        DUPLICATE_ID,
        REQUEST_LIMIT,
        BYTE_LIMIT,
        STALE_GENERATION
    }

    enum CompletionResult {
        COMPLETED,
        UNAVAILABLE,
        UNKNOWN_ID,
        BYTE_LIMIT,
        STALE_GENERATION
    }

    /**
     * Sanitized controller-to-core result categories. Detailed host exceptions, addresses and
     * payload descriptions never cross this boundary. The deterministic engine maps these coarse
     * categories into command-specific Mobile Adapter response codes.
     */
    enum BackendStatus {
        SUCCESS,
        CONNECTION_LIMIT,
        INVALID_CONNECTION,
        LOOKUP_FAILED,
        CONNECTION_FAILED,
        COMMUNICATION_FAILED,
        REMOTE_CLOSED,
        CANCELLED
    }

    /**
     * Identity-only cancellation generation. It contains no host handle and is never serialized.
     * A backend replaces the token on every cancellation boundary, so an old worker cannot
     * complete a newly admitted request that happens to reuse the same numeric request ID.
     */
    final class BackendGeneration {

        private BackendGeneration() {
        }

        public static BackendGeneration create() {
            return new BackendGeneration();
        }
    }

    /** Immutable backend request with an opaque, bounded payload. */
    record BackendRequest(long requestId, int command, byte[] payload) {

        public BackendRequest {
            if (requestId < 0) throw new IllegalArgumentException("Request ID must not be negative");
            if (command < 0 || command > 0xff) {
                throw new IllegalArgumentException("Backend command must be in 0..255");
            }
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_BUFFERED_BYTES) {
                throw new IllegalArgumentException("Backend request payload exceeds 65,536 bytes");
            }
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** Immutable typed completion returned only while its generation still owns one request. */
    record BackendCompletion(BackendGeneration generation, long requestId, BackendStatus status,
                             byte[] payload) {

        public BackendCompletion {
            Objects.requireNonNull(generation, "generation");
            if (requestId < 0) throw new IllegalArgumentException("Request ID must not be negative");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_BUFFERED_BYTES) {
                throw new IllegalArgumentException("Backend completion payload exceeds 65,536 bytes");
            }
            payload = payload.clone();
        }

        /** Source-compatible detached success value used by Phase-1 boundary tests. */
        public BackendCompletion(long requestId, byte[] payload) {
            this(DISCONNECTED_GENERATION, requestId, BackendStatus.SUCCESS, payload);
        }

        /** Convenience success value for generation-aware fake workers. */
        public BackendCompletion(BackendGeneration generation, long requestId, byte[] payload) {
            this(generation, requestId, BackendStatus.SUCCESS, payload);
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
