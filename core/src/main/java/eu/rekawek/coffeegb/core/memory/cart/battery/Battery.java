package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public interface Battery extends StatefulComponent<Battery> {

    enum DebugHistoryReplayKind {
        NULL,
        MEMORY,
        FILE_STATE
    }

    /** Immutable factory description preserving the live battery's machine-state record shape. */
    record DebugHistoryReplayShape(DebugHistoryReplayKind kind, int byteSize) {
        public DebugHistoryReplayShape {
            if (kind == null) {
                throw new NullPointerException("Debug-history battery kind is required");
            }
            if (byteSize < 0 || kind == DebugHistoryReplayKind.NULL && byteSize != 0) {
                throw new IllegalArgumentException("Debug-history battery size is invalid");
            }
        }

        public Battery createServiceFreeBattery() {
            return switch (kind) {
                case NULL -> NULL_BATTERY;
                case MEMORY -> new MemoryBattery(new byte[byteSize]);
                case FILE_STATE -> new StateReplayBattery(byteSize);
            };
        }
    }

    void loadRam(int[] ram);

    void saveRam(int[] ram);

    void loadRamWithClock(int[] ram, long[] clockData);

    void saveRamWithClock(int[] ram, long[] clockData);

    void flush();

    /** Describes a fresh service-free battery with this implementation's state-record shape. */
    default DebugHistoryReplayShape debugHistoryReplayShape() {
        throw new UnsupportedOperationException(
                "Battery implementation cannot be cloned for debug-history replay");
    }

    /**
     * Captures mapper RAM/RTC at the caller's emulation safe point without performing file I/O.
     */
    default BatteryFlush prepareFlush(Runnable captureMapperState) {
        captureMapperState.run();
        return BatteryFlush.none();
    }

    /** Supplies the session event route after machine construction. */
    default void init(EventBus eventBus) {
    }

    Battery NULL_BATTERY =
            new Battery() {
                @Override
                public DebugHistoryReplayShape debugHistoryReplayShape() {
                    return new DebugHistoryReplayShape(DebugHistoryReplayKind.NULL, 0);
                }

                @Override
                public ComponentState<Battery> captureState() {
                    return null;
                }

                @Override
                public void restoreState(ComponentState<Battery> state) {
                }

                @Override
                public void loadRam(int[] ram) {
                }

                @Override
                public void saveRam(int[] ram) {
                }

                @Override
                public void loadRamWithClock(int[] ram, long[] clockData) {
                }

                @Override
                public void saveRamWithClock(int[] ram, long[] clockData) {
                }

                @Override
                public void flush() {
                }
            };
}
