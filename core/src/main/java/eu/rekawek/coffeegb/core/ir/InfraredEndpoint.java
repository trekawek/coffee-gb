package eu.rekawek.coffeegb.core.ir;

/**
 * A device connected to the CGB infrared port.
 *
 * <p>The port reports its own LED state to the endpoint and samples light received from it.
 */
public interface InfraredEndpoint {

    void setLightOn(boolean lightOn);

    boolean isLightOn();

    /**
     * Explicit guarantee that received light is stable for the next master ticks. Endpoint
     * topology must be configured by the emulation owner between ticks. Unknown or concurrently
     * driven devices keep the default zero; the query itself must not sample or mutate input.
     */
    default int performanceQuietSpanLimit(int requested) {
        return 0;
    }

    InfraredEndpoint NULL_ENDPOINT = new InfraredEndpoint() {
        @Override
        public void setLightOn(boolean lightOn) {
        }

        @Override
        public boolean isLightOn() {
            return false;
        }

        @Override
        public int performanceQuietSpanLimit(int requested) {
            return Math.max(0, requested);
        }
    };
}
