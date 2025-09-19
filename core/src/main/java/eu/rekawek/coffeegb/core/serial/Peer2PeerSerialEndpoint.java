package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

public class Peer2PeerSerialEndpoint implements SerialEndpoint, Serializable, Originator<SerialEndpoint> {

    private Peer2PeerSerialEndpoint peer;

    private int sb;

    private final AtomicInteger bitsReceived = new AtomicInteger();

    private int bitIndex = 7;

    public void init(Peer2PeerSerialEndpoint peer) {
        this.peer = peer;
        peer.peer = this;
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
            return 0;
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
    public Memento<SerialEndpoint> saveToMemento() {
        return new Peer2PeerSerialEndpointMemento(sb, bitsReceived.get(), bitIndex);
    }

    @Override
    public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        if (!(memento instanceof Peer2PeerSerialEndpointMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.bitsReceived.set(mem.bitsReceived);
        this.bitIndex = mem.bitIndex;
    }

    private record Peer2PeerSerialEndpointMemento(int sb, int bitsReceived,
                                                  int bitIndex) implements Memento<SerialEndpoint> {
    }
}
