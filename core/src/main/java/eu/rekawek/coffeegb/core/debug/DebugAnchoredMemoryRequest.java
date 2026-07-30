package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** A bounded memory range resolved relative to a register in the inspection's own snapshot. */
public record DebugAnchoredMemoryRequest(
        DebugInspectionAnchor anchor,
        int offset,
        int length) {

    public DebugAnchoredMemoryRequest {
        Objects.requireNonNull(anchor, "anchor");
        DebugValueChecks.range("offset", offset, -0xffff, 0xffff);
        DebugValueChecks.range("length", length, 1, DebugInspectionRequest.MAX_TOTAL_BYTES);
    }

    /**
     * Resolves this range against the supplied coherent snapshot.
     *
     * <p>Program-counter addresses below {@code 8000} select the parser-corrected physical ROM
     * image. This is a best-effort source view, not the mapper's live banked CPU window. Every
     * other anchored range selects the side-effect-free system-bus view and may still be rejected
     * by the session if it targets an unsafe address.
     */
    public DebugMemoryRequest resolve(DebugSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        int anchorAddress = switch (anchor) {
            case PROGRAM_COUNTER -> snapshot.registers().pc();
            case STACK_POINTER -> snapshot.registers().sp();
        };
        long start = (long) anchorAddress + offset;
        long end = start + length;
        if (start < 0 || end > DebugMemoryRequest.MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Anchored memory request leaves the 16-bit address space");
        }
        DebugAddressSpace addressSpace = anchor == DebugInspectionAnchor.PROGRAM_COUNTER
                && start < 0x8000 ? DebugAddressSpace.ROM : DebugAddressSpace.SYSTEM_BUS;
        if (addressSpace == DebugAddressSpace.ROM && end > 0x8000) {
            throw new IllegalArgumentException(
                    "Program-counter request crosses the best-effort ROM-view boundary");
        }
        return new DebugMemoryRequest(addressSpace, (int) start, length);
    }
}
