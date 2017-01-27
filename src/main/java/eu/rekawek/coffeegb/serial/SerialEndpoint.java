package eu.rekawek.coffeegb.serial;

import java.io.IOException;

public interface SerialEndpoint {

    int transfer(int outgoing) throws IOException;

    SerialEndpoint NULL_ENDPOINT = new SerialEndpoint() {
        @Override
        public int transfer(int outgoing) {
            return 0;
        }
    };
}
