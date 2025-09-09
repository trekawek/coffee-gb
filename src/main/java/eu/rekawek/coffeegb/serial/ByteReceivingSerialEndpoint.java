package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.memento.Memento;

public class ByteReceivingSerialEndpoint implements SerialEndpoint {
    private final ByteReceiver byteReceiver;
    private int sb;
    private int bits;

    public ByteReceivingSerialEndpoint(ByteReceiver byteReceiver) {
        this.byteReceiver = byteReceiver;
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb;
    }

    @Override
    public int recvBit() {
        return -1;
    }

    @Override
    public int recvByte() {
        return -1;
    }

    @Override
    public void startSending() {
        bits = 0;
    }

    @Override
    public int sendBit() {
        if (++bits == 8) {
            byteReceiver.onNewByte(sb);
            bits = 0;
        }
        return 1;
    }

    @Override
    public int sendByte() {
        byteReceiver.onNewByte(sb);
        return 0xFF;
    }

    @Override
    public Memento<SerialEndpoint> saveToMemento() {
        return new ByteReceivingSerialEndpointMemento(sb, bits);
    }

    @Override
    public void restoreFromMemento(Memento<SerialEndpoint> memento) {
        if (!(memento instanceof ByteReceivingSerialEndpointMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.bits = mem.bits;
    }

    private record ByteReceivingSerialEndpointMemento(int sb, int bits) implements Memento<SerialEndpoint> {}
}
