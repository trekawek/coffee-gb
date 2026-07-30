package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Snapshot and requested views captured without an intervening emulation tick. */
public record DebugInspectionResult(
        DebugSnapshot snapshot,
        DebugInspectionRequest request,
        List<DebugMemoryBlock> anchoredBlocks,
        List<DebugMemoryBlock> memoryBlocks,
        Optional<DebugGraphicsInspection> graphics,
        Optional<DebugAudioInspection> audio,
        Optional<TraceReadResult> trace) {

    public DebugInspectionResult {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(anchoredBlocks, "anchoredBlocks");
        Objects.requireNonNull(memoryBlocks, "memoryBlocks");
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(trace, "trace");
        anchoredBlocks = List.copyOf(anchoredBlocks);
        memoryBlocks = List.copyOf(memoryBlocks);
        if (anchoredBlocks.size() != request.anchoredRequests().size()
                || memoryBlocks.size() != request.memoryRequests().size()) {
            throw new IllegalArgumentException("Inspection result block counts do not match request");
        }
        for (int i = 0; i < anchoredBlocks.size(); i++) {
            requireMatchingBlock(
                    request.anchoredRequests().get(i).resolve(snapshot), anchoredBlocks.get(i));
        }
        for (int i = 0; i < memoryBlocks.size(); i++) {
            requireMatchingBlock(request.memoryRequests().get(i), memoryBlocks.get(i));
        }
        requirePresence(
                "graphics", request.sections().contains(DebugInspectionSection.GRAPHICS),
                graphics.isPresent());
        requirePresence(
                "audio", request.sections().contains(DebugInspectionSection.AUDIO),
                audio.isPresent());
        requirePresence("trace", request.traceRequest().isPresent(), trace.isPresent());
        if (trace.isPresent()) {
            var traceRequest = request.traceRequest().orElseThrow();
            var traceResult = trace.orElseThrow();
            if (traceResult.entries().size() > traceRequest.maxEntries()) {
                throw new IllegalArgumentException(
                        "Inspection trace result exceeds the requested page length");
            }
            for (var entry : traceResult.entries()) {
                if (entry.sequence() <= traceRequest.afterSequence()) {
                    throw new IllegalArgumentException(
                            "Inspection trace entries must be newer than the requested cursor");
                }
            }
            if (traceResult.nextAfterSequence() < traceRequest.afterSequence()) {
                throw new IllegalArgumentException(
                        "Inspection trace cursor must not move backwards");
            }
        }
    }

    /** Compatibility constructor for snapshot-and-memory-only inspection results. */
    public DebugInspectionResult(
            DebugSnapshot snapshot,
            DebugInspectionRequest request,
            List<DebugMemoryBlock> anchoredBlocks,
            List<DebugMemoryBlock> memoryBlocks) {
        this(snapshot, request, anchoredBlocks, memoryBlocks,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void requirePresence(String name, boolean requested, boolean present) {
        if (requested != present) {
            throw new IllegalArgumentException(
                    "Inspection " + name + " presence does not match request");
        }
    }

    private static void requireMatchingBlock(
            DebugMemoryRequest request, DebugMemoryBlock block) {
        Objects.requireNonNull(block, "memory block");
        if (block.addressSpace() != request.addressSpace()
                || block.startAddress() != request.address()
                || block.length() != request.length()) {
            throw new IllegalArgumentException("Inspection result block does not match request");
        }
    }
}
