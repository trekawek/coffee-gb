package eu.rekawek.coffeegb.core.ir;

/** Couples the infrared LEDs and sensors of two locally emulated Game Boys. */
public class Peer2PeerInfraredEndpoint implements InfraredEndpoint {

    private volatile Peer2PeerInfraredEndpoint peer;

    private volatile boolean lightOn;

    public void init(Peer2PeerInfraredEndpoint peer) {
        this.peer = peer;
        peer.peer = this;
    }

    @Override
    public void setLightOn(boolean lightOn) {
        this.lightOn = lightOn;
    }

    @Override
    public boolean isLightOn() {
        Peer2PeerInfraredEndpoint peer = this.peer;
        return peer != null && peer.lightOn;
    }
}
