package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;

public class ByteReceivingSerialEndpoint implements SerialEndpoint {
    private final ByteReceiver byteReceiver;
    private int sb;
    private int bits;

    public ByteReceivingSerialEndpoint(ByteReceiver byteReceiver) {
        this.byteReceiver = byteReceiver;
    }

    /** The byte callback runs only on the eighth supplied clock edge, never on a master tick. */
    @Override
    public int performanceQuietSpanLimit(int requested) {
        return Math.max(0, requested);
    }

    @Override
    public int performanceClockCapabilities() {
        return PERFORMANCE_CLOCK_IDLE | PERFORMANCE_CLOCK_INTERNAL | PERFORMANCE_CLOCK_EXTERNAL_WAIT;
    }

    @Override
    public int performanceExternalClockWaitSpanLimit(int requested) {
        return Math.max(0, requested);
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
    public ComponentState<SerialEndpoint> captureState() {
        return new ByteReceivingSerialEndpointState(sb, bits);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof ByteReceivingSerialEndpointState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.sb = mem.sb;
        this.bits = mem.bits;
    }

    private record ByteReceivingSerialEndpointState(int sb, int bits) implements ComponentState<SerialEndpoint> {}

    /** Importer-only compatibility record for released local snapshots. */
    private record ByteReceivingSerialEndpointMemento(int sb, int bits) implements Memento<SerialEndpoint> {}
}
