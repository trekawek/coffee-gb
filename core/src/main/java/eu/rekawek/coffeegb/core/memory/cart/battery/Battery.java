package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public interface Battery extends StatefulComponent<Battery> {

    void loadRam(int[] ram);

    void saveRam(int[] ram);

    void loadRamWithClock(int[] ram, long[] clockData);

    void saveRamWithClock(int[] ram, long[] clockData);

    void flush();

    /** Supplies the session event route after machine construction. */
    default void init(EventBus eventBus) {
    }

    Battery NULL_BATTERY =
            new Battery() {
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
