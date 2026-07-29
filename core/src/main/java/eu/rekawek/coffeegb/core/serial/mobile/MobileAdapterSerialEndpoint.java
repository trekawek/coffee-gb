package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;

/**
 * Link-port wrapper for the deterministic Mobile Adapter protocol engine.
 *
 * <p>Incoming Game Boy bytes are delivered to {@link MobileAdapterEngine}. The frozen clean-room
 * contract does not specify how response-packet and acknowledgement channels are interleaved with
 * link-clock transfers, so this endpoint intentionally keeps serial input idle-high. Callers can
 * inspect the two immutable channels through {@link #snapshot()} without inventing wire behavior.
 */
public final class MobileAdapterSerialEndpoint implements SerialEndpoint {

    private final MobileAdapterEngine engine;

    private int sb = 0xff;

    private int sendBitIndex;

    public MobileAdapterSerialEndpoint(ClockSpec clockSpec, int deviceId, byte[] configuration) {
        this(clockSpec, deviceId, configuration, MobileAdapterBackendPort.DISCONNECTED);
    }

    public MobileAdapterSerialEndpoint(ClockSpec clockSpec, int deviceId, byte[] configuration,
                                       MobileAdapterBackendPort backendPort) {
        engine = new MobileAdapterEngine(clockSpec, deviceId, configuration, backendPort);
    }

    @Override
    public void tick() {
        engine.tick();
    }

    @Override
    public void disconnect() {
        engine.cancelOrReplace();
        sb = 0xff;
        sendBitIndex = 0;
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb & 0xff;
    }

    @Override
    public int recvBit() {
        return -1;
    }

    @Override
    public void startSending() {
        sendBitIndex = 0;
    }

    @Override
    public int sendBit() {
        sendBitIndex = (sendBitIndex + 1) & 7;
        if (sendBitIndex == 0) {
            engine.acceptByte(sb);
        }
        return 1;
    }

    public MobileAdapterEngine.EngineResult snapshot() {
        return engine.snapshot();
    }

    public byte[] configurationCopy() {
        return engine.configurationCopy();
    }

    public void replaceConfiguration(byte[] replacement) {
        engine.replaceConfiguration(replacement);
    }

    public boolean reservePendingPacketSlot() {
        return engine.reservePendingPacketSlot();
    }

    public void completePendingPacketSlot() {
        engine.completePendingPacketSlot();
    }

    @Override
    public ComponentState<SerialEndpoint> captureState() {
        return new MobileAdapterSerialEndpointState(
                (MobileAdapterEngine.MobileAdapterEngineState) engine.captureState(),
                sb,
                sendBitIndex);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof MobileAdapterSerialEndpointState restored)) {
            throw new IllegalArgumentException("Invalid Mobile Adapter serial endpoint state type");
        }
        if (restored.sb < 0 || restored.sb > 0xff) {
            throw new IllegalArgumentException("Mobile Adapter SB value must be in 0..255");
        }
        if (restored.sendBitIndex < 0 || restored.sendBitIndex > 7) {
            throw new IllegalArgumentException("Mobile Adapter send-bit index must be in 0..7");
        }
        engine.restoreState(restored.engineState);
        sb = restored.sb;
        sendBitIndex = restored.sendBitIndex;
    }

    /** Complete endpoint/engine state; both contained byte arrays and accessors are defensive. */
    public record MobileAdapterSerialEndpointState(
            MobileAdapterEngine.MobileAdapterEngineState engineState,
            int sb,
            int sendBitIndex) implements ComponentState<SerialEndpoint> {

        public MobileAdapterSerialEndpointState {
            if (engineState == null) {
                throw new NullPointerException("engineState");
            }
        }
    }
}
