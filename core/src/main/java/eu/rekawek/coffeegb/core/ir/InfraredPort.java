package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

/**
 * The CGB infrared port - the RP register at 0xFF56 (issue #94).
 *
 * <p>Bit 0 drives the console's own IR LED (write). Bit 1 reads the light sensor,
 * inverted: 0 means IR light is being received. The sensor only reports light while both
 * read-enable bits 6-7 are set; otherwise bit 1 reads 1. The register does not exist in
 * DMG-compatibility mode (reads 0xFF), matching the other CGB-only registers.
 *
 * <p>Received light comes from a pluggable external device. Supported sources are another
 * linked Game Boy and the {@link FullChanger} (Zok Zok Heroes).
 */
public class InfraredPort implements AddressSpace, StatefulComponent<InfraredPort> {

    private static final int PERFORMANCE_MAX_QUIET_SPAN = 3;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private final FullChanger fullChanger = new FullChanger();

    private transient boolean fullChangerActive;

    private transient InfraredEndpoint endpoint = InfraredEndpoint.NULL_ENDPOINT;

    private transient SerialEndpoint serialEndpoint = SerialEndpoint.NULL_ENDPOINT;

    // the written bits of RP: bit 0 (own LED) and bits 6-7 (read enable)
    private int rp;

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    /**
     * Last physical IR signal mask: bit 0 is the locally driven LED and bit 1 is
     * received light. Upper bits are reserved. Attachment realigns it before observation.
     */
    private transient int observedDebugSignal;

    public InfraredPort(boolean gbc, SpeedMode speedMode) {
        this.gbc = gbc;
        this.speedMode = speedMode;
    }

    public void init(EventBus eventBus) {
        init(eventBus, InfraredEndpoint.NULL_ENDPOINT);
    }

    public void init(EventBus eventBus, InfraredEndpoint endpoint) {
        this.endpoint.setLightOn(false);
        this.endpoint = endpoint;
        endpoint.setLightOn((rp & 0x01) != 0);
        alignDebugSignal();
        eventBus.register(e -> {
            fullChanger.transform(e.characterId());
            fullChangerActive = true;
        }, FullChanger.TransformEvent.class);
    }

    /** Connects RP bit 4 to the CGB link port's serial-input pin. */
    public void setSerialEndpoint(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint == null
                ? SerialEndpoint.NULL_ENDPOINT
                : serialEndpoint;
    }

    public void close() {
        endpoint.setLightOn(false);
        endpoint = InfraredEndpoint.NULL_ENDPOINT;
        serialEndpoint = SerialEndpoint.NULL_ENDPOINT;
        alignDebugSignal();
    }

    public void tick() {
        if (fullChangerActive) {
            // the pulse timings are defined in double-speed cycles; advance twice as fast in
            // double speed so a game sees the same delays regardless of its speed setting
            fullChangerActive = fullChanger.tick(speedMode.getSpeedMode());
        }
        if (debugHooks != null) {
            notifyDebugSignalChange();
        }
    }

    /**
     * Returns the exact idle CGB IR span.  External endpoints and the FullChanger are deliberately
     * fail-closed: their callbacks or pulse edges must remain visible in the scalar ordering.
     */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0 || !gbc || speedMode.getSpeedMode() != 1
                || fullChangerActive
                || endpoint != InfraredEndpoint.NULL_ENDPOINT
                || serialEndpoint != SerialEndpoint.NULL_ENDPOINT
                || debugHooks != null) {
            return 0;
        }
        return Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN);
    }

    public boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    public boolean tickPerformanceQuietSpan(int ticks) {
        return canTickPerformanceQuietSpan(ticks);
    }

    public void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        // The packet preflight proves a disabled/null-endpoint IR state.  There is no arithmetic
        // state to advance in that state, so the trusted commit is intentionally just a no-op.
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff56;
    }

    @Override
    public void setByte(int address, int value) {
        rp = value & 0xc1;
        endpoint.setLightOn((rp & 0x01) != 0);
        notifyDebugSignalChange();
    }

    @Override
    public int getByte(int address) {
        if (!gbc || speedMode.isDmgCompat()) {
            return 0xff;
        }
        // an armed device starts transmitting at a poll of the register, so the polling
        // loop observes the first pulse from its beginning
        fullChanger.onRpRead();
        notifyDebugSignalChange();
        // Bits 2, 3 and 5 are pulled high. Bit 4 is not unused on CGB hardware: it
        // exposes link-port pin 4 as a raw digital input for software UARTs.
        int result = rp | 0x2c | 0x02;
        if (serialEndpoint.isSerialInputHigh()) {
            result |= 0x10;
        }
        int readMode = rp & 0xc0;
        // The intermediate $80 mode pulls the sensor bit low even without a
        // light source. In the normal $C0 receive mode it is active-low only
        // while infrared light is present.
        if (readMode == 0x80
                || (readMode == 0xc0 && (fullChanger.isLightOn() || endpoint.isLightOn()))) {
            result &= ~0x02;
        }
        return result;
    }

    /**
     * Captures RP and its physical inputs without calling {@code FullChanger.onRpRead()}.
     * Ordinary FF56 reads intentionally arm/advance some infrared peripherals.
     */
    public DebugHardwareInspection.Infrared captureDebugInfraredInspection(boolean available) {
        if (!available) {
            return new DebugHardwareInspection.Infrared(false, -1, false, false, false);
        }
        boolean receivedLight = fullChanger.isLightOn() || endpoint.isLightOn();
        boolean serialInputHigh = serialEndpoint.isSerialInputHigh();
        int result = rp | 0x2c | 0x02;
        if (serialInputHigh) {
            result |= 0x10;
        }
        int readMode = rp & 0xc0;
        if (readMode == 0x80 || (readMode == 0xc0 && receivedLight)) {
            result &= ~0x02;
        }
        return new DebugHardwareInspection.Infrared(
                true, result, (rp & 0x01) != 0, receivedLight, serialInputHigh);
    }

    @Override
    public ComponentState<InfraredPort> captureState() {
        return new InfraredPortState(rp, fullChanger.captureState());
    }

    @Override
    public ComponentState<InfraredPort> captureState(MachineStateCapture capture) {
        return new InfraredPortState(rp, fullChanger.captureState(capture));
    }

    @Override
    public void restoreState(ComponentState<InfraredPort> state) {
        if (!(state instanceof InfraredPortState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.rp = mem.rp;
        endpoint.setLightOn((rp & 0x01) != 0);
        fullChanger.restoreState(mem.fullChangerMemento);
        fullChangerActive = fullChanger.isActive();
        alignDebugSignal();
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        alignDebugSignal();
        this.debugHooks = debugHooks;
    }

    private void notifyDebugSignalChange() {
        DebugHooks hooks = debugHooks;
        if (hooks == null) {
            return;
        }
        int signal = getDebugSignal();
        if (signal == observedDebugSignal) {
            return;
        }
        observedDebugSignal = signal;
        hooks.onSerialIrEvent(
                SerialIrTrace.Endpoint.INFRARED,
                SerialIrTrace.Kind.SIGNAL_CHANGED,
                signal);
    }

    private void alignDebugSignal() {
        observedDebugSignal = getDebugSignal();
    }

    private int getDebugSignal() {
        int localOutput = rp & 0x01;
        int receivedLight = fullChanger.isLightOn() || endpoint.isLightOn() ? 0x02 : 0;
        return localOutput | receivedLight;
    }

    private record InfraredPortState(int rp, ComponentState<FullChanger> fullChangerMemento)
            implements ComponentState<InfraredPort> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record InfraredPortMemento(int rp, Memento<FullChanger> fullChangerMemento)
            implements Memento<InfraredPort> {
    }
}
