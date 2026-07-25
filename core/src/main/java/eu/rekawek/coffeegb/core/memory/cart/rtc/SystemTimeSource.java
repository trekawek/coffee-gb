package eu.rekawek.coffeegb.core.memory.cart.rtc;

public class SystemTimeSource implements TimeSource {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
