package eu.rekawek.coffeegb.memory.cart.battery;

public interface Battery {

    void loadRam(int[] ram);

    void saveRam(int[] ram);

    void loadRamWithClock(int[] ram, long[] clockData);

    void saveRamWithClock(int[] ram, long[] clockData);

    Battery NULL_BATTERY = new Battery() {
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
    };
}
