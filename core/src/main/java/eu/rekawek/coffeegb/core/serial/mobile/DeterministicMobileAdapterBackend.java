package eu.rekawek.coffeegb.core.serial.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic in-memory Mobile Adapter backend used for offline lifecycle and boundary tests. */
public final class DeterministicMobileAdapterBackend implements MobileAdapterBackendPort {

    private final AtomicReference<BackendState> state = new AtomicReference<>(
            BackendState.empty(BackendGeneration.create()));

    @Override
    public BackendGeneration generation() {
        return state.get().generation();
    }

    @Override
    public OfferResult offer(BackendGeneration offeredGeneration, BackendRequest request) {
        Objects.requireNonNull(offeredGeneration, "generation");
        Objects.requireNonNull(request, "request");
        int payloadBytes = request.payload().length;
        while (true) {
            BackendState current = state.get();
            if (offeredGeneration != current.generation()) return OfferResult.STALE_GENERATION;
            if (containsRequestId(current, request.requestId())) return OfferResult.DUPLICATE_ID;
            if (current.occupiedRequestSlots() >= MAX_REQUEST_SLOTS) {
                return OfferResult.REQUEST_LIMIT;
            }
            if (payloadBytes > MAX_BUFFERED_BYTES - current.bufferedBytes()) {
                return OfferResult.BYTE_LIMIT;
            }

            List<BackendRequest> requests = new ArrayList<>(current.requests());
            requests.add(request);
            BackendState updated = new BackendState(current.generation(), requests,
                    current.completions(), current.bufferedBytes() + payloadBytes);
            if (state.compareAndSet(current, updated)) return OfferResult.ACCEPTED;
        }
    }

    @Override
    public CompletionResult complete(BackendGeneration completedGeneration, long requestId,
                                     byte[] response) {
        Objects.requireNonNull(completedGeneration, "generation");
        Objects.requireNonNull(response, "response");
        while (true) {
            BackendState current = state.get();
            if (completedGeneration != current.generation()) {
                return CompletionResult.STALE_GENERATION;
            }
            int requestIndex = requestIndex(current.requests(), requestId);
            if (requestIndex < 0) return CompletionResult.UNKNOWN_ID;
            BackendRequest request = current.requests().get(requestIndex);
            int retainedWithoutRequest = current.bufferedBytes() - request.payload().length;
            if (response.length > MAX_BUFFERED_BYTES - retainedWithoutRequest) {
                return CompletionResult.BYTE_LIMIT;
            }

            List<BackendRequest> requests = new ArrayList<>(current.requests());
            requests.remove(requestIndex);
            List<BackendCompletion> completions = new ArrayList<>(current.completions());
            completions.add(new BackendCompletion(requestId, response));
            BackendState updated = new BackendState(current.generation(), requests, completions,
                    retainedWithoutRequest + response.length);
            if (state.compareAndSet(current, updated)) return CompletionResult.COMPLETED;
        }
    }

    @Override
    public BackendCompletion poll() {
        while (true) {
            BackendState current = state.get();
            if (current.completions().isEmpty()) return null;
            BackendCompletion completion = current.completions().get(0);
            List<BackendCompletion> completions = new ArrayList<>(current.completions());
            completions.remove(0);
            BackendState updated = new BackendState(current.generation(), current.requests(),
                    completions, current.bufferedBytes() - completion.payload().length);
            if (state.compareAndSet(current, updated)) return completion;
        }
    }

    @Override
    public void cancelAll() {
        state.getAndSet(BackendState.empty(BackendGeneration.create()));
    }

    @Override
    public int occupiedRequestSlots() {
        return state.get().occupiedRequestSlots();
    }

    @Override
    public int bufferedBytes() {
        return state.get().bufferedBytes();
    }

    public int pendingRequests() {
        return state.get().requests().size();
    }

    public int completedResults() {
        return state.get().completions().size();
    }

    private static boolean containsRequestId(BackendState state, long requestId) {
        if (requestIndex(state.requests(), requestId) >= 0) return true;
        for (BackendCompletion completion : state.completions()) {
            if (completion.requestId() == requestId) return true;
        }
        return false;
    }

    private static int requestIndex(List<BackendRequest> requests, long requestId) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).requestId() == requestId) return i;
        }
        return -1;
    }

    private record BackendState(BackendGeneration generation, List<BackendRequest> requests,
                                List<BackendCompletion> completions, int bufferedBytes) {

        private BackendState {
            Objects.requireNonNull(generation, "generation");
            requests = List.copyOf(requests);
            completions = List.copyOf(completions);
        }

        private static BackendState empty(BackendGeneration generation) {
            return new BackendState(generation, List.of(), List.of(), 0);
        }

        private int occupiedRequestSlots() {
            return requests.size() + completions.size();
        }
    }
}
