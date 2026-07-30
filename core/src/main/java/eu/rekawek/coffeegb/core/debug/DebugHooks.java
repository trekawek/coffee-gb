package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.debug.trace.DmaTrace;
import eu.rekawek.coffeegb.core.debug.trace.InputTrace;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;

/**
 * Optional primitive-only observations installed while a debug session needs core hooks.
 *
 * <p>Implementations run on the emulation owner. Call sites must retain a null/absent fast path
 * so ordinary execution constructs no debug values.
 */
public interface DebugHooks {

    /** Whether the CPU should install its active bus wrapper for read/write/fetch observations. */
    default boolean requiresMemoryAccessHooks() {
        return true;
    }

    /** Whether the PPU should swap its raw fetch delegates for observing wrappers. */
    default boolean requiresPpuMemoryAccessHooks() {
        return false;
    }

    void onInstructionFetch(int programCounter);

    void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode);

    void onInstructionRetired(
            boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode);

    default void onMemoryAccess(DebugMemoryAccess access, int address, int value) {
    }

    /** Memory observation with the producer and stable debug address-space view identified. */
    default void onMemoryAccess(
            DebugAddressSpace addressSpace,
            TraceSource source,
            DebugMemoryAccess access,
            int address,
            int value) {
        onMemoryAccess(access, address, value);
    }

    void onInterruptRequested(DebugInterruptType interrupt);

    void onInterruptAccepted(DebugInterruptType interrupt);

    default void onInterruptCleared(DebugInterruptType interrupt) {
    }

    default void onPpuEvent(
            PpuTrace.Kind kind,
            long ppuFrame,
            int line,
            int dot,
            DebugPpuMode mode) {
    }

    default void onDmaEvent(
            DmaTrace.Engine engine,
            DmaTrace.Kind kind,
            int sourceAddress,
            int destinationAddress,
            int length,
            int bytesTransferred) {
    }

    default void onTimerEvent(
            TimerTrace.Kind kind,
            int divider,
            int counter,
            int modulo,
            int control) {
    }

    default void onSerialIrEvent(
            SerialIrTrace.Endpoint endpoint,
            SerialIrTrace.Kind kind,
            int value) {
    }

    default void onInputEvent(
            InputTrace.Kind kind,
            int buttonMask,
            int changedMask) {
    }

    default void onMapperRtcEvent(
            MapperRtcTrace.Kind kind,
            int register,
            long value) {
    }

    default void onApuEvent(
            ApuTrace.Kind kind,
            int channel,
            int register,
            int value) {
    }
}
