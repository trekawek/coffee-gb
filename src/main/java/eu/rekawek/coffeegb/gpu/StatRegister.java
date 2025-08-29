package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static eu.rekawek.coffeegb.gpu.GpuRegister.LY;
import static eu.rekawek.coffeegb.gpu.GpuRegister.LYC;
import static eu.rekawek.coffeegb.gpu.Mode.*;

public class StatRegister implements AddressSpace, Originator<StatRegister> {

    public static final int ADDRESS = 0xff41;

    private static final long TICKS_PER_FRAME = 70224;

    private static final Map<Mode, Integer> MODE_STAT_DELAY = Map.of(HBlank, 12, VBlank, 0, OamSearch, 4, PixelTransfer, 8);

    private static final int LY_DELAY = 0;

    private static final int DISABLE_LCD_DELAY = 0;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final LinkedList<State> states = new LinkedList<>();

    private int stat = 0x80;

    private long tick;

    private boolean isLCDCTriggered;

    public StatRegister(InterruptManager interruptManager, Gpu gpu) {
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
        Mode mode = n1State.mode;
        long delay = tick - n1State.tick();

        if (n1State.mode != null && MODE_STAT_DELAY.get(n1State.mode) > delay) {
            requestLCDC = isLCDCTriggered;
        }
        if (n1State.isTriggersLyLycEquals() && LY_DELAY > delay) {
            requestLCDC = isLCDCTriggered;
        }
        if (n2State.mode == HBlank && n1State.mode == VBlank && delay == 0) {
            requestVBlank = true;
        }
        if (n1State.isTriggersStateMode() && MODE_STAT_DELAY.get(n1State.mode) <= delay) {
            requestLCDC = true;
        }
        if (n1State.isTriggersLyLycEquals() && LY_DELAY <= delay) {
            requestLCDC = true;
        }
        // vblank_stat_intr-GS.s
        if (n2State.mode == HBlank && n1State.mode == VBlank && n1State.isStatEnabledForMode(OamSearch)) {
            requestLCDC = true;
        }
        if (n1State.mode == null) {
            requestLCDC = false;
        }
        if (n2State.mode != n1State.mode) {
            int newPpuMode = -1;
            if (mode == null && DISABLE_LCD_DELAY <= delay) {
                newPpuMode = 0;
            } else if (mode != null && MODE_STAT_DELAY.get(mode) <= delay) {
                newPpuMode = mode.ordinal();
            }
            if (newPpuMode != -1) {
                stat = (stat & 0b11111100) | newPpuMode;
            }
        }
        if (n2State.isLyLycEquals() != n1State.isLyLycEquals()) {
            if (LY_DELAY <= delay) {
                stat = (stat & 0b11111011) | (n1State.isLyLycEquals() ? 0b100 : 0b000);
            }
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
        State newState = new State(tick, gpu.isLcdEnabled() ? gpu.getMode() : null, stat & 0b01111000, gpu.getByte(LY.getAddress()), gpu.getByte(LYC.getAddress()));
        State lastState = states.peekLast();
        if (newState.isSame(lastState)) {
            return;
        }
        states.add(newState);
        while (states.size() > 2) {
            states.removeFirst();
        }
    }

    @Override
    public boolean accepts(int address) {
        return address == ADDRESS;
    }

    @Override
    public void setByte(int address, int value) {
        stat = (stat & 0b00000111) | (value & 0b11111000);
    }

    @Override
    public int getByte(int address) {
        return stat;
    }

    @Override
    public Memento<StatRegister> saveToMemento() {
        return new StatRegisterMemento(new ArrayList<>(states), stat, tick, isLCDCTriggered);
    }

    @Override
    public void restoreFromMemento(Memento<StatRegister> memento) {
        if (!(memento instanceof StatRegisterMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.states.clear();
        this.states.addAll(mem.states);
        this.stat = mem.stat;
        this.tick = mem.tick;
        this.isLCDCTriggered = mem.isLCDCTriggered;
        if (states.size() > 2) {
            throw new IllegalStateException("Invalid memento length");
        }
    }

    private record StatRegisterMemento(List<State> states, int stat, long tick,
                                       boolean isLCDCTriggered) implements Memento<StatRegister> {
    }

    private record State(long tick, Mode mode, int stat, int ly, int lyc) implements Serializable {
        boolean isSame(State state) {
            if (state == null) {
                return false;
            }
            return mode == state.mode && stat == state.stat && ly == state.ly && lyc == state.lyc;
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
    }
}
