package eu.rekawek.coffeegb.core.serial.mobile;

import java.util.Objects;

/**
 * Nonblocking request/result ownership seam between the deterministic engine and a backend.
 *
 * <p>Phase #351 provides only a no-op port and a deterministic in-memory fake. Later controller
 * code may enqueue host work behind this interface, but no implementation may block the emulator
 * thread and no port object is part of captured engine state.
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
                                         byte[] response) {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(response, "response");
            return CompletionResult.UNAVAILABLE;
        }

        @Override
        public BackendCompletion poll() {
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

    CompletionResult complete(BackendGeneration generation, long requestId, byte[] response);

    /** Returns and releases the oldest completion, or {@code null} when none is ready. */
    BackendCompletion poll();

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

    /** Immutable completion returned only after a corresponding request occupied one slot. */
    record BackendCompletion(long requestId, byte[] payload) {

        public BackendCompletion {
            if (requestId < 0) throw new IllegalArgumentException("Request ID must not be negative");
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_BUFFERED_BYTES) {
                throw new IllegalArgumentException("Backend completion payload exceeds 65,536 bytes");
            }
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
