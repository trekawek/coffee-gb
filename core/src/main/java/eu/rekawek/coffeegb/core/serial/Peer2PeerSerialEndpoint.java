package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.util.concurrent.atomic.AtomicInteger;

public class Peer2PeerSerialEndpoint implements SerialEndpoint, StatefulComponent<SerialEndpoint> {

    private Peer2PeerSerialEndpoint peer;

    private int sb;

    private final AtomicInteger bitsReceived = new AtomicInteger();

    private int bitIndex = 7;

    /** Pairs two endpoints before session installation; this is not a concurrent hot-plug API. */
    public void init(Peer2PeerSerialEndpoint peer) {
        this.peer = peer;
        peer.peer = this;
    }

    /** True only while this endpoint has a live peer whose traffic must be replayed jointly. */
    public boolean isConnected() {
        return peer != null;
    }

    /** An unconnected cable is stably high and has no peer work; a connected cable stays scalar. */
    @Override
    public int performanceQuietSpanLimit(int requested) {
        return requested > 0 && peer == null ? requested : 0;
    }

    /** A disconnected cable also has no device which can observe an armed external wait. */
    @Override
    public int performanceExternalClockWaitSpanLimit(int requested) {
        return requested > 0 && peer == null ? requested : 0;
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb;
    }

    @Override
    public int recvBit() {
        if (peer == null) {
            return -1;
        }
        if (bitsReceived.get() == 0) {
            return -1;
        }
        bitsReceived.decrementAndGet();
        return shift();
    }

    @Override
    public void startSending() {
        if (peer == null) {
            return;
        }
        bitIndex = 7;
        peer.bitsReceived.set(0);
    }

    @Override
    public int sendBit() {
        if (peer == null) {
            // an unconnected link cable reads high, so an internal-clock transfer with no
            // peer receives all ones (SB becomes 0xFF), like the null endpoint. Returning
            // 0 here made link-aware games see a phantom partner and hang waiting for it
            // (Alleyway freezes with the paddle stuck, issue #63).
            return 1;
        }
        peer.bitsReceived.incrementAndGet();
        return shift();
    }

    private int shift() {
        var bit = BitUtils.getBit(peer.sb, bitIndex) ? 1 : 0;
        if (--bitIndex == -1) {
            bitIndex = 7;
        }
        return bit;
    }

    @Override
    public ComponentState<SerialEndpoint> captureState() {
        return new Peer2PeerSerialEndpointState(sb, bitsReceived.get(), bitIndex);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof Peer2PeerSerialEndpointState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.sb = mem.sb;
        this.bitsReceived.set(mem.bitsReceived);
        this.bitIndex = mem.bitIndex;
    }

    private record Peer2PeerSerialEndpointState(int sb, int bitsReceived,
                                                  int bitIndex) implements ComponentState<SerialEndpoint> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record Peer2PeerSerialEndpointMemento(int sb, int bitsReceived,
                                                  int bitIndex) implements Memento<SerialEndpoint> {
    }
}
