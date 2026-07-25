package eu.rekawek.coffeegb.core.memory.cart.rtc;

import java.util.concurrent.TimeUnit;

public class VirtualTimeSource implements TimeSource {

    private long clock;

    public VirtualTimeSource() {
        // Battery formats reserve zero as "no persisted wall-clock reference".
        // Keep the test clock deterministic while starting beyond that sentinel.
        this(946_684_800_000L);
    }

    public VirtualTimeSource(long initialMillis) {
        this.clock = initialMillis;
    }

    @Override
    public long currentTimeMillis() {
        return clock;
    }

    public void forward(long i, TimeUnit unit) {
        clock += unit.toMillis(i);
    }

    public void setCurrentTimeMillis(long clock) {
        this.clock = clock;
    }
}
