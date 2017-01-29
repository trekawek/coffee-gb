package eu.rekawek.coffeegb.memory.cart.battery;

public interface Battery {

    void loadRam(int[] ram);

    void saveRam(int[] ram);

    long[] loadClock();

    void saveClock(long[] clockData);

    Battery NULL_BATTERY = new Battery() {
        @Override
        public void loadRam(int[] ram) {
        }

        @Override
        public void saveRam(int[] ram) {
        }

        @Override
        public long[] loadClock() {
            return new long[2];
        }

        @Override
        public void saveClock(long[] clockData) {
        }
    };
}
