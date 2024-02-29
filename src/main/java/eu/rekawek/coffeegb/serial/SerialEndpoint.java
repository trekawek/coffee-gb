package eu.rekawek.coffeegb.serial;

public interface SerialEndpoint {
    /**
     * Listener waiting for any updates of the SB byte, so it can be shared with the other side.
     */
    void setSb(int sb);

    /**
     * Returns the bit transferred from the active side or -1 if no bit has been received.
     */
    int recvBit();

    /**
     * Starts byte transfer, should reset the index of bit to send.
     */
    void startSending();

    /**
     * Sends following SB bit. Returns the received bit.
     */
    int sendBit();

    SerialEndpoint NULL_ENDPOINT = new SerialEndpoint() {
        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            return 1;
        }
    };
}
