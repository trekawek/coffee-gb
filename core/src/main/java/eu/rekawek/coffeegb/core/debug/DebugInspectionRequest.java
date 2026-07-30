package eu.rekawek.coffeegb.core.debug;

import java.util.List;
import java.util.Objects;

/** Bounded set of register-relative and explicit ranges copied at one debugger safe point. */
public record DebugInspectionRequest(
        List<DebugAnchoredMemoryRequest> anchoredRequests,
        List<DebugMemoryRequest> memoryRequests) {

    public static final int MAX_BLOCKS = 16;

    public static final int MAX_TOTAL_BYTES = 4096;

    public DebugInspectionRequest {
        Objects.requireNonNull(anchoredRequests, "anchoredRequests");
        Objects.requireNonNull(memoryRequests, "memoryRequests");
        anchoredRequests = List.copyOf(anchoredRequests);
        memoryRequests = List.copyOf(memoryRequests);
        if (anchoredRequests.size() + memoryRequests.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Inspection request exceeds the block limit");
        }
        long totalBytes = 0;
        for (DebugAnchoredMemoryRequest request : anchoredRequests) {
            totalBytes += Objects.requireNonNull(request, "anchored request").length();
        }
        for (DebugMemoryRequest request : memoryRequests) {
            int length = Objects.requireNonNull(request, "memory request").length();
            if (length == 0) {
                throw new IllegalArgumentException("Inspection memory ranges must not be empty");
            }
            totalBytes += length;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException(
                    "Inspection request exceeds the aggregate byte limit");
        }
    }

    public int blockCount() {
        return anchoredRequests.size() + memoryRequests.size();
    }

    public int totalBytes() {
        return anchoredRequests.stream().mapToInt(DebugAnchoredMemoryRequest::length).sum()
                + memoryRequests.stream().mapToInt(DebugMemoryRequest::length).sum();
    }
}
