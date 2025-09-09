package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public interface Battery extends Serializable, Originator<Battery> {

    void loadRam(int[] ram);

    void saveRam(int[] ram);

    void loadRamWithClock(int[] ram, long[] clockData);

    void saveRamWithClock(int[] ram, long[] clockData);

    void flush();

    Battery NULL_BATTERY =
            new Battery() {
                @Override
                public Memento<Battery> saveToMemento() {
                    return null;
                }

                @Override
                public void restoreFromMemento(Memento<Battery> memento) {
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
