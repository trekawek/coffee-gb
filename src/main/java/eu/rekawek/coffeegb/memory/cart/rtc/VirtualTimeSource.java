package eu.rekawek.coffeegb.memory.cart.rtc;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class VirtualTimeSource implements TimeSource, Serializable {

    private long clock = System.currentTimeMillis();

    @Override
    public long currentTimeMillis() {
        return clock;
    }

    public void forward(long i, TimeUnit unit) {
        clock += unit.toMillis(i);
    }
}
