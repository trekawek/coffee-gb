package eu.rekawek.coffeegb.memory.cart.rtc;

import java.util.concurrent.TimeUnit;

public class VirtualClock implements Clock {

    private long clock = System.currentTimeMillis();

    @Override
    public long currentTimeMillis() {
        return clock;
    }

    public void forward(long i, TimeUnit unit) {
        clock += unit.toMillis(i);
    }
}
