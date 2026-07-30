package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded memory, peripheral, and trace views copied at one debugger safe point. */
public record DebugInspectionRequest(
        List<DebugAnchoredMemoryRequest> anchoredRequests,
        List<DebugMemoryRequest> memoryRequests,
        Set<DebugInspectionSection> sections,
        Optional<TraceReadRequest> traceRequest) {

    public static final int MAX_BLOCKS = 16;

    public static final int MAX_TOTAL_BYTES = 4096;

    public DebugInspectionRequest {
        Objects.requireNonNull(anchoredRequests, "anchoredRequests");
        Objects.requireNonNull(memoryRequests, "memoryRequests");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(traceRequest, "traceRequest");
        anchoredRequests = List.copyOf(anchoredRequests);
        memoryRequests = List.copyOf(memoryRequests);
        EnumSet<DebugInspectionSection> sectionCopy = sections.isEmpty()
                ? EnumSet.noneOf(DebugInspectionSection.class)
                : EnumSet.copyOf(sections);
        sections = Collections.unmodifiableSet(sectionCopy);
        traceRequest.ifPresent(request -> Objects.requireNonNull(request, "trace request"));
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

    /** Compatibility constructor for callers that only request snapshot-relative memory. */
    public DebugInspectionRequest(
            List<DebugAnchoredMemoryRequest> anchoredRequests,
            List<DebugMemoryRequest> memoryRequests) {
        this(anchoredRequests, memoryRequests, Set.of(), Optional.empty());
    }

    /** Convenience constructor for peripheral inspection without a trace cursor read. */
    public DebugInspectionRequest(
            List<DebugAnchoredMemoryRequest> anchoredRequests,
            List<DebugMemoryRequest> memoryRequests,
            Set<DebugInspectionSection> sections) {
        this(anchoredRequests, memoryRequests, sections, Optional.empty());
    }

    public int blockCount() {
        return anchoredRequests.size() + memoryRequests.size();
    }

    public int totalBytes() {
        return anchoredRequests.stream().mapToInt(DebugAnchoredMemoryRequest::length).sum()
                + memoryRequests.stream().mapToInt(DebugMemoryRequest::length).sum();
    }
}
