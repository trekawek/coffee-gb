package eu.rekawek.coffeegb.core.ir;

/**
 * A device connected to the CGB infrared port.
 *
 * <p>The port reports its own LED state to the endpoint and samples light received from it.
 */
public interface InfraredEndpoint {

    void setLightOn(boolean lightOn);

    boolean isLightOn();

    InfraredEndpoint NULL_ENDPOINT = new InfraredEndpoint() {
        @Override
        public void setLightOn(boolean lightOn) {
        }

        @Override
        public boolean isLightOn() {
            return false;
        }
    };
}
