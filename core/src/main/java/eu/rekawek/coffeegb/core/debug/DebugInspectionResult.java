package eu.rekawek.coffeegb.core.debug;

import java.util.List;
import java.util.Objects;

/** Snapshot and caller-owned memory blocks captured without an intervening emulation tick. */
public record DebugInspectionResult(
        DebugSnapshot snapshot,
        DebugInspectionRequest request,
        List<DebugMemoryBlock> anchoredBlocks,
        List<DebugMemoryBlock> memoryBlocks) {

    public DebugInspectionResult {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(anchoredBlocks, "anchoredBlocks");
        Objects.requireNonNull(memoryBlocks, "memoryBlocks");
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
