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
 * linked Game Boy, the {@link FullChanger} (Zok Zok Heroes), and a generic
 * {@link TvRemote}.
 */
public class InfraredPort implements AddressSpace, StatefulComponent<InfraredPort> {

    private static final int PERFORMANCE_MAX_QUIET_SPAN = 3;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private final FullChanger fullChanger = new FullChanger();

    private transient boolean fullChangerActive;

    private final TvRemote tvRemote = new TvRemote();

    private transient boolean tvRemoteActive;

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
        eventBus.register(e -> {
            if (gbc && !speedMode.isDmgCompat()) {
                tvRemote.sendSignal();
                tvRemoteActive = true;
            }
        }, TvRemote.SendSignalEvent.class);
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
        if (tvRemoteActive) {
            tvRemoteActive = tvRemote.tick(speedMode.getSpeedMode());
        }
        if (debugHooks != null) {
            notifyDebugSignalChange();
        }
    }

    /**
     * Returns the exact normal-speed interval before an IR light or serial-input pin transition.
     * Accessories may count down inside a constant pulse; actual edges remain scalar.
     */
    public int performanceQuietSpanLimit(int requested) {
        if (speedMode.getSpeedMode() != 1) {
            return 0;
        }
        return performanceEventHorizon(Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN));
    }

    /** Same exact event horizon for a settled normal-speed HALT packet, without the three-dot cap. */
    public int performanceSettledHaltSpanLimit(int requested) {
        if (speedMode.getSpeedMode() != 1) {
            return 0;
        }
        return performanceEventHorizon(requested);
    }

    public boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    public boolean tickPerformanceQuietSpan(int ticks) {
        if (!canTickPerformanceQuietSpan(ticks)) {
            return false;
        }
        tickPerformanceEventSpanTrusted(ticks);
        return true;
    }

    public void tickPerformanceQuietSpanTrusted(int ticks) {
        tickPerformanceEventSpanTrusted(ticks);
    }

    /** Compatibility guard for a fixed-x2 epoch inside the IR event horizon. */
    public boolean performanceEpochIdle(int requested) {
        return gbc
                && speedMode.getSpeedMode() == 2
                && requested > 0
                && performanceEventHorizon(requested) >= requested;
    }

    /** Advances a preflighted epoch without crossing an IR pulse boundary. */
    public void tickPerformanceEpochIdle(int ticks) {
        tickPerformanceEventSpanTrusted(ticks);
    }

    /** State-only master-tick horizon shared by normal and double-speed schedulers. */
    public int performanceEventHorizon(int requested) {
        if (requested <= 0 || !gbc || debugHooks != null) {
            return 0;
        }
        int span = Math.min(requested, endpoint.performanceQuietSpanLimit(requested));
        if (span <= 0) {
            return 0;
        }
        span = Math.min(span, serialEndpoint.performanceInputPinSpanLimit(span));
        int speed = speedMode.getSpeedMode();
        if (fullChangerActive) {
            span = fullChanger.performanceSpanLimit(span, speed);
        }
        if (tvRemoteActive) {
            span = tvRemote.performanceSpanLimit(span, speed);
        }
        return Math.max(0, span);
    }

    /** Arithmetic countdown only; callbacks and light transitions belong to the next scalar tick. */
    public void tickPerformanceEventSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        int speed = speedMode.getSpeedMode();
        if (fullChangerActive) {
            fullChanger.tickPerformanceSpanTrusted(ticks, speed);
        }
        if (tvRemoteActive) {
            tvRemote.tickPerformanceSpanTrusted(ticks, speed);
        }
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
        tvRemote.onRpRead();
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
                || (readMode == 0xc0 && receivedLight())) {
            result &= ~0x02;
        }
        return result;
    }

    /**
     * Captures RP and its physical inputs without polling emulated infrared accessories.
     * Ordinary FF56 reads intentionally start an armed accessory transmission.
     */
    public DebugHardwareInspection.Infrared captureDebugInfraredInspection(boolean available) {
        if (!available) {
            return new DebugHardwareInspection.Infrared(false, -1, false, false, false);
        }
        boolean receivedLight = receivedLight();
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
        return new InfraredPortState(rp, fullChanger.captureState(), tvRemote.captureState());
    }

    @Override
    public ComponentState<InfraredPort> captureState(MachineStateCapture capture) {
        return new InfraredPortState(
                rp, fullChanger.captureState(capture), tvRemote.captureState(capture));
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
        tvRemote.restoreState(mem.tvRemoteMemento);
        tvRemoteActive = tvRemote.isActive();
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
        int receivedLight = receivedLight() ? 0x02 : 0;
        return localOutput | receivedLight;
    }

    private boolean receivedLight() {
        return fullChanger.isLightOn() || tvRemote.isLightOn() || endpoint.isLightOn();
    }

    private record InfraredPortState(
            int rp,
            ComponentState<FullChanger> fullChangerMemento,
            ComponentState<TvRemote> tvRemoteMemento)
            implements ComponentState<InfraredPort> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record InfraredPortMemento(
            int rp,
            Memento<FullChanger> fullChangerMemento)
            implements Memento<InfraredPort> {
    }
}
