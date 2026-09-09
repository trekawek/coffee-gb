package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

/**
 * A generic NEC television-remote transmission for games that use the CGB infrared sensor.
 *
 * <p>The receiver sees the demodulated carrier envelope: a 9 ms leader burst and 4.5 ms
 * space, followed by 32 LSB-first bits. Each bit starts with a 562.5 us burst; a zero has a
 * 562.5 us space and a one a 1.6875 ms space. The frame carries the conventional address,
 * inverse-address, command, and inverse-command bytes. A user action arms the remote and the
 * first RP read starts it, so a polling loop observes the leader from its beginning.
 */
public class TvRemote implements StatefulComponent<TvRemote> {

    /** The player points a generic television remote at the CGB and presses a button. */
    public record SendSignalEvent() implements Event {
    }

    // Durations in CGB CPU T-cycles at normal speed, rounded from the NEC timings.
    static final int LEADER_BURST_CYCLES = 37_749;

    static final int LEADER_SPACE_CYCLES = 18_874;

    static final int BIT_BURST_CYCLES = 2_359;

    static final int ZERO_SPACE_CYCLES = 2_359;

    static final int ONE_SPACE_CYCLES = 7_078;

    // A valid, deterministic NEC frame. Games generally classify the envelope rather than the
    // particular key; complements make the frame acceptable to decoders that validate bytes.
    private static final int ADDRESS = 0x00;

    private static final int COMMAND = 0x10;

    private static final int[] SCHEDULE = buildSchedule();

    private boolean armed;

    private boolean running;

    private int index;

    private int remaining;

    /** Queues one complete NEC frame, replacing any pending or running frame. */
    public void sendSignal() {
        armed = true;
        running = false;
        index = 0;
        remaining = 0;
    }

    void onRpRead() {
        if (armed) {
            armed = false;
            running = true;
            index = 0;
            remaining = SCHEDULE[0];
        }
    }

    boolean tick(int cycles) {
        if (!running) {
            return armed;
        }
        remaining -= cycles;
        while (remaining <= 0) {
            if (++index >= SCHEDULE.length) {
                running = false;
                return false;
            }
            remaining += SCHEDULE[index];
        }
        return true;
    }

    boolean isActive() {
        return armed || running;
    }

    /** Constant-pulse interval; the tick which changes the light remains scalar. */
    int performanceSpanLimit(int requested, int speed) {
        return requested <= 0 ? 0 : running
                ? Math.min(requested, Math.max(0, (remaining - 1) / speed)) : requested;
    }

    void tickPerformanceSpanTrusted(int ticks, int speed) {
        if (running) {
            remaining -= ticks * speed;
        }
    }

    boolean isLightOn() {
        return running && (index & 1) == 0;
    }

    private static int[] buildSchedule() {
        int[] schedule = new int[2 + 32 * 2 + 1];
        int offset = 0;
        schedule[offset++] = LEADER_BURST_CYCLES;
        schedule[offset++] = LEADER_SPACE_CYCLES;
        int frame = ADDRESS
                | ((~ADDRESS & 0xff) << 8)
                | (COMMAND << 16)
                | ((~COMMAND & 0xff) << 24);
        for (int bit = 0; bit < 32; bit++) {
            schedule[offset++] = BIT_BURST_CYCLES;
            schedule[offset++] = (frame & (1 << bit)) == 0
                    ? ZERO_SPACE_CYCLES
                    : ONE_SPACE_CYCLES;
        }
        schedule[offset] = BIT_BURST_CYCLES;
        return schedule;
    }

    @Override
    public ComponentState<TvRemote> captureState() {
        return new TvRemoteState(armed, running, index, remaining);
    }

    @Override
    public ComponentState<TvRemote> captureState(MachineStateCapture capture) {
        return captureState();
    }

    @Override
    public void restoreState(ComponentState<TvRemote> state) {
        if (!(state instanceof TvRemoteState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        armed = mem.armed;
        running = mem.running;
        index = mem.index;
        remaining = mem.remaining;
    }

    private record TvRemoteState(boolean armed, boolean running, int index, int remaining)
            implements ComponentState<TvRemote> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record TvRemoteMemento(boolean armed, boolean running, int index, int remaining)
            implements Memento<TvRemote> {
    }
}
