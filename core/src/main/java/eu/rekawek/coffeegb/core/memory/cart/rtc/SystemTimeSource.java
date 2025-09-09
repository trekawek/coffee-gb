package eu.rekawek.coffeegb.core.memory.cart.rtc;

import java.io.Serializable;

public class SystemTimeSource implements TimeSource, Serializable {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
