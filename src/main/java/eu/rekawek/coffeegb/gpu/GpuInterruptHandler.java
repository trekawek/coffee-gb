package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;

import java.util.LinkedList;
import java.util.List;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;
import static eu.rekawek.coffeegb.gpu.Mode.*;

public class GpuInterruptHandler {

    private static final long TICKS_PER_FRAME = 70224;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final LinkedList<State> states = new LinkedList<>();

    private long tick;

    private boolean isLCDCTriggered;

    public GpuInterruptHandler(InterruptManager interruptManager, Gpu gpu) {
        this.interruptManager = interruptManager;
        this.gpu = gpu;
    }

    public void tick() {
        addState();
        if (states.size() == 1) {
            return;
        }

        State n1State = states.getLast();
        State n2State = states.get(states.size() - 2);

        boolean requestVBlank = false;
        boolean requestLCDC = false;

        if (n2State.mode == HBlank && n1State.mode == VBlank && n1State.tick == tick) {
            requestVBlank = true;
        }
        if (n1State.isTriggersStat()) {
            requestLCDC = true;
        }
        // vblank_stat_intr-GS.s
        if (n2State.mode == HBlank && n1State.mode == VBlank && n1State.isStatEnabledForMode(OamSearch)) {
            requestLCDC = true;
        }
        if (n1State.mode == null) {
            requestLCDC = false;
        }

        if (requestVBlank) {
            interruptManager.requestInterrupt(InterruptType.VBlank);
        }
        if (requestLCDC && !isLCDCTriggered) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
        isLCDCTriggered = requestLCDC;

        tick++;
        if (tick % TICKS_PER_FRAME == 0) {
            rewindStates();
        }
    }

    private void rewindStates() {
        long offset = TICKS_PER_FRAME * (states.getFirst().tick / TICKS_PER_FRAME);
        if (offset == 0) {
            return;
        }

        List<State> rewound = new LinkedList<>();
        for (State state : states) {
            if (state.tick < offset) {
                throw new IllegalStateException("Rewinding states too far");
            }
            rewound.add(new State(state.tick - offset, state.mode, state.stat, state.ly, state.lyc));
        }
        states.clear();
        states.addAll(rewound);

        tick -= offset;
    }

    private void addState() {
        State newState = new State(tick, gpu.isLcdEnabled() ? gpu.getMode() : null, gpu.getByte(STAT.getAddress()), gpu.getByte(LY.getAddress()), gpu.getByte(LYC.getAddress()));
        State lastState = states.peekLast();
        if (newState.isSame(lastState)) {
            return;
        }
        while (states.size() >= 10) {
            states.removeFirst();
        }
        states.add(newState);
    }

    private record State(long tick, Mode mode, int stat, int ly, int lyc) {
        boolean isSame(State state) {
            if (state == null) {
                return false;
            }
            return mode == state.mode && stat == state.stat && ly == state.ly && lyc == state.lyc;
        }

        boolean isTriggersStat() {
            return isTriggersStateMode() || isTriggersLyLycEquals();
        }

        boolean isTriggersStateMode() {
            return isStatEnabledForMode(mode);
        }

        boolean isTriggersLyLycEquals() {
            return isLyLycEquals() && isStatEnabledForLyLycEquals();
        }

        boolean isStatEnabledForMode(Mode mode) {
            return mode != null && mode.statBit != -1 && (stat & (1 << mode.statBit)) != 0;
        }

        boolean isStatEnabledForLyLycEquals() {
            return (stat & 0b01000000) != 0;
        }

        boolean isLyLycEquals() {
            return ly == lyc;
        }

        boolean isLcdDisabled() {
            return mode == null;
        }
    }
}
