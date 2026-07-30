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

    /** Applies at most one already-published backend result without performing host I/O or waiting. */
    public MobileAdapterEngine.EngineResult pollBackendCompletion() {
        return engine.pollBackendCompletion();
    }

    /** Runtime-only ownership hint for controller warnings; it is never captured as a host handle. */
    public boolean hasExternalIo() {
        return engine.hasExternalIo();
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
        ComponentState<MobileAdapterEngine> engineState = engine.captureState();
        if (engineState instanceof MobileAdapterEngine.MobileAdapterEngineNetworkState networkState) {
            return new MobileAdapterSerialEndpointNetworkState(networkState, sb, sendBitIndex);
        }
        return new MobileAdapterSerialEndpointState(
                (MobileAdapterEngine.MobileAdapterEngineState) engineState, sb, sendBitIndex);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (state instanceof MobileAdapterSerialEndpointNetworkState networkState) {
            restoreEndpointState(networkState.engineState, networkState.sb, networkState.sendBitIndex);
        } else if (state instanceof MobileAdapterSerialEndpointState legacyState) {
            restoreEndpointState(legacyState.engineState, legacyState.sb, legacyState.sendBitIndex);
        } else {
            throw new IllegalArgumentException("Invalid Mobile Adapter serial endpoint state type");
        }
    }

    private void restoreEndpointState(ComponentState<MobileAdapterEngine> restoredEngineState,
                                      int restoredSb, int restoredSendBitIndex) {
        if (restoredSb < 0 || restoredSb > 0xff) {
            throw new IllegalArgumentException("Mobile Adapter SB value must be in 0..255");
        }
        if (restoredSendBitIndex < 0 || restoredSendBitIndex > 7) {
            throw new IllegalArgumentException("Mobile Adapter send-bit index must be in 0..7");
        }
        engine.restoreState(restoredEngineState);
        sb = restoredSb;
        sendBitIndex = restoredSendBitIndex;
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

    /** Additive Phase-2 endpoint state used only when its engine observed external I/O. */
    public record MobileAdapterSerialEndpointNetworkState(
            MobileAdapterEngine.MobileAdapterEngineNetworkState engineState,
            int sb,
            int sendBitIndex) implements ComponentState<SerialEndpoint> {

        public MobileAdapterSerialEndpointNetworkState {
            if (engineState == null) {
                throw new NullPointerException("engineState");
            }
        }
    }
}
