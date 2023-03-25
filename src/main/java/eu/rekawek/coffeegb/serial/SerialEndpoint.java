package eu.rekawek.coffeegb.serial;

public interface SerialEndpoint {

    /**
     * Transfer the bit in the external clock mode (passive). Return the incoming bit or -1 if there's no bit to transfer.
     */
    int receive(int bitToTransfer);

    /**
     * Transfer the bit in the internal clock mode (active). Return the incoming bit.
     */
    int send(int bitToTransfer);

    SerialEndpoint NULL_ENDPOINT = new SerialEndpoint() {
        @Override
        public int receive(int bitToTransfer) {
            return -1;
        }

        @Override
        public int send(int bitToTransfer) {
            return 0;
        }
    };
}
