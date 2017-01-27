package eu.rekawek.coffeegb.serial;

public interface SerialEndpoint {

    int transfer(int outgoing);

    SerialEndpoint NULL_ENDPOINT = new SerialEndpoint() {
        @Override
        public int transfer(int outgoing) {
            return 0;
        }
    };
}
