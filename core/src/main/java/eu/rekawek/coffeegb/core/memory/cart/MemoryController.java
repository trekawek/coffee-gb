package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import java.util.Objects;

public interface MemoryController extends AddressSpace, StatefulComponent<MemoryController> {
    /** Whether this mapper has hardware that must be advanced by every master tick. */
    default boolean isClocked() {
        return false;
    }

    default void tick() {
    }

    default void setClockPaused(boolean paused) {
    }

    /** Rebinds host-time bookkeeping after a historical machine state is restored. */
    default void reanchorClockAfterRestore(boolean paused) {
    }

    /** Suppresses TimeSource access only while an owner-thread state transaction is speculative. */
    default void setStateTimeSourceAccessSuppressed(boolean suppressed) {
    }

    /**
     * Captures the host-time boundary paired with a mapper state without retaining its TimeSource.
     *
     * <p>Only wall-clock-driven mappers implement this seam. The returned timestamp lets an
     * in-process checkpoint apply elapsed time through its capture boundary after restoring the
     * mapper's service-free component state.</p>
     */
    default WallClockRuntimeState captureWallClockRuntimeState() {
        return null;
    }

    default void validateWallClockRuntimeState(WallClockRuntimeState state) {
        if (state != null) {
            throw new IllegalArgumentException(
                    "Wall-clock runtime state supplied for a mapper without a host clock");
        }
    }

    /** Applies only elapsed time up to the captured boundary; this must not consult TimeSource. */
    default void restoreWallClockRuntimeState(WallClockRuntimeState state) {
        validateWallClockRuntimeState(state);
    }

    enum WallClockKind {
        HUC3,
        TAMA5
    }

    /** Detached mapper kind and checkpoint wall-time boundary, never the live clock service. */
    record WallClockRuntimeState(WallClockKind kind, long checkpointSecond) {
        public WallClockRuntimeState {
            Objects.requireNonNull(kind, "kind");
            if (checkpointSecond < 0) {
                throw new IllegalArgumentException(
                        "Wall-clock checkpoint second must not be negative");
            }
        }
    }

    /** Current emulated motor output, used to reconcile host rumble after an atomic restore. */
    default boolean isRumbleActive() {
        return false;
    }

    /** Applies mapper state normally reached while the console boot ROM reads the cartridge. */
    default void skipBoot() {
    }

    default void flushRam() {
    }

    default void init(EventBus eventBus) {
    }

    /** Installs an optional owner-thread observer; unsupported mappers remain silent. */
    default void setDebugHooks(DebugHooks hooks) {
    }
}
