package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.cpu.BitUtils;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

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
        if (bitsReceived.get() == 0) {
            return -1;
        }
        bitsReceived.decrementAndGet();
        return shift();
    }

    @Override
    public void startSending() {
        bitIndex = 7;
        peer.bitsReceived.set(0);
    }

    @Override
    public int sendBit() {
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
