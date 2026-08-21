package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class Timer implements AddressSpace, StatefulComponent<Timer> {

    /**
     * PERFORMANCE's phase scheduler currently asks peripherals to cover at most the three
     * non-bus clocks between two normal-speed CPU machine cycles.  Keeping the bound here makes
     * the component API safe for callers which do not have the CPU phase information available.
     */
    private static final int PERFORMANCE_MAX_QUIET_SPAN = 3;

    private static final int FRAME_SEQUENCER_DIV_BIT = 12;

    private final SpeedMode speedMode;

    private final InterruptManager interruptManager;

    private static final int[] FREQ_TO_BIT = {9, 3, 5, 7};

    // the divider has already counted a few cycles when the CPU fetches its first opcode
    // (reset release), which makes the internal counter reach exactly 0xABCC when the DMG
    // boot ROM hands over at 0x0100 (boot_div-dmgABCmgb); model-specific authentic-boot
    // phases and skipped-boot post-ROM values are preset by Gameboy
    private int div = 4, tac, tma, tima;

    private boolean previousBit;

    private boolean overflow;

    private int ticksSinceOverflow;

    private boolean divReset;

    private int haltWakeDelay;

    // On the DMG the divider is an asynchronous ripple counter. If the HALT bug is
    // entered in the M-cycle immediately following a DIV reset, the duplicated fetch
    // can line the subsequent DIV read up with the first carry ripple. The high byte
    // briefly exposes the alternating ripple nodes before settling to 0x01.
    private int ticksSinceDivReset = Integer.MAX_VALUE;

    private boolean haltBugDivRipplePending;

    private boolean haltBugDivRippleVisible;

    // The CPU acknowledge gate can see a timer event a few clocks ahead. When
    // that event falls inside the gate, its future IF assertion has already been
    // consumed by the acknowledge and must not reassert when time catches up.
    private boolean suppressNextInterruptRequest;

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    public Timer(InterruptManager interruptManager, SpeedMode speedMode) {
        this.speedMode = speedMode;
        this.interruptManager = interruptManager;
    }

    public void presetDiv(int value) {
        this.div = value & 0xffff;
        ticksSinceDivReset = Integer.MAX_VALUE;
        haltBugDivRipplePending = false;
        haltBugDivRippleVisible = false;
    }

    public int getDivCounter() {
        return div;
    }

    public int getDebugTima() {
        return tima;
    }

    public int getDebugTma() {
        return tma;
    }

    public int getDebugTac() {
        return tac | 0xf8;
    }

    public boolean isDebugOverflowPending() {
        return overflow;
    }

    /** Number of CPU clocks remaining before an overflowing TIMA reloads from TMA. */
    public int getDebugOverflowDelayTicks() {
        return overflow ? Math.max(0, 4 - ticksSinceOverflow) : 0;
    }

    public void tick() {
        acknowledgeInterruptIfNeeded();
        divReset = false;
        int speed = speedMode.getSpeedMode();
        for (int i = 0; i < speed; i++) {
            tickCpuClock();
        }
    }

    /**
     * Returns the largest normal-speed PERFORMANCE span which can be advanced without visiting
     * the per-clock timer state machine.
     *
     * <p>The limit stops before every observable timer edge: a selected timer falling edge,
     * overflow/reload/interrupt or HALT-wake transition, the DMG divider-ripple diagnostic, and
     * the raw divider edge which can clock the APU frame sequencer.  The CGB boot offset is
     * included conservatively as a second possible frame-sequencer tap.  A zero result means the
     * caller must use the scalar path.  The returned value is never greater than
     * {@link #PERFORMANCE_MAX_QUIET_SPAN}.</p>
     *
     * <p>This method is deliberately state-only and does not consume interrupt acknowledge
     * signals or clear transient flags.  That makes it safe to use as a preflight before the
     * caller decides whether to take the bulk path.</p>
     */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0 || speedMode.getSpeedMode() != 1 || debugHooks != null
                || divReset || overflow || haltWakeDelay != 0
                || haltBugDivRippleVisible || suppressNextInterruptRequest) {
            return 0;
        }

        int span = Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN);
        span = capBefore(span, clocksToTimerFallingEdge());
        span = capBefore(span, clocksToPendingDividerRipple());
        span = capBefore(span, clocksToFrameSequencerEdge(0));
        if (speedMode.isGbc()) {
            // Sound's later-revision CGB boot state uses a +2 DIV tap offset until the first
            // FF04 reset. Timer does not own that offset, so include both phases and take the
            // conservative limit. After a reset the extra candidate is harmless.
            span = capBefore(span, clocksToFrameSequencerEdge(2));
        }
        return Math.max(0, span);
    }

    /** Returns the largest safe span using the scheduler's normal three-clock bound. */
    public int performanceQuietSpanLimit() {
        return performanceQuietSpanLimit(PERFORMANCE_MAX_QUIET_SPAN);
    }

    /** True when the requested span can be applied by {@link #tickPerformanceQuietSpan(int)}. */
    public boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    /**
     * Advances an already-preflighted quiet span arithmetically.
     *
     * <p>There are no timer edges inside an eligible span, so DIV and the divider input latch are
     * the only changing state.  Interrupt acknowledgement is still handled at the span's first
     * clock exactly as in {@link #tick()}; this preserves the CPU acknowledge window without
     * requiring a per-clock callback.  A false return guarantees that this method made no state
     * change.</p>
     */
    public boolean tickPerformanceQuietSpan(int ticks) {
        if (!canTickPerformanceQuietSpan(ticks)) {
            return false;
        }

        // Keep the acknowledge gate at the same beginning-of-tick position as tick().  The
        // preflight above excludes every timer edge in the span, so an acknowledgement can only
        // update the manager's acknowledge/suppression latches and cannot make an interior timer
        // transition arrive late.
        acknowledgeInterruptIfNeeded();
        divReset = false;
        div = (div + ticks) & 0xffff;
        previousBit = timerInput(div, tac);
        if (ticksSinceDivReset != Integer.MAX_VALUE) {
            ticksSinceDivReset += ticks;
        }
        // Scalar tick() clears this one-tick diagnostic at the beginning of every clock.  An
        // eligible span cannot contain the carry which sets it, so it is settled by the end.
        haltBugDivRippleVisible = false;
        return true;
    }

    /** Applies a span after the caller has already passed {@link #canTickPerformanceQuietSpan(int)}. */
    public void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        if (!tickPerformanceQuietSpan(ticks)) {
            throw new IllegalStateException("Timer quiet span is not eligible: " + ticks);
        }
    }

    /** Naming alias for schedulers which use the GPU's advance-oriented bulk vocabulary. */
    public boolean advancePerformanceQuietSpan(int ticks) {
        return tickPerformanceQuietSpan(ticks);
    }

    /** Trusted naming alias for schedulers which use the GPU's advance-oriented vocabulary. */
    public void advancePerformanceQuietSpanTrusted(int ticks) {
        tickPerformanceQuietSpanTrusted(ticks);
    }

    private static int capBefore(int currentLimit, long eventDistance) {
        if (eventDistance == Long.MAX_VALUE) {
            return currentLimit;
        }
        // The event at distance d belongs to the scalar tick which advances to that state.  A
        // quiet span may therefore consume at most d-1 clocks.
        return Math.min(currentLimit, (int) Math.max(0, eventDistance - 1));
    }

    /** Distance to the next falling edge of the selected TIMA input. */
    private long clocksToTimerFallingEdge() {
        boolean enabled = (tac & 0x04) != 0;
        if (!enabled) {
            // DMG TAC writes retain previousBit.  Disabling a timer while that stale latch is
            // high produces the falling edge on the following divider clock.
            return previousBit ? 1 : Long.MAX_VALUE;
        }

        int bitPos = FREQ_TO_BIT[tac & 0x03];
        int period = 1 << (bitPos + 1);
        int halfPeriod = period >>> 1;
        int phase = div & (period - 1);
        if (previousBit) {
            // A stale high latch with the input already low settles immediately; otherwise the
            // next low half-period boundary supplies the falling edge.
            return phase < halfPeriod ? 1L : period - phase;
        }
        // With a low previous latch, the next falling edge is the next low-boundary after a full
        // period.  This remains correct whether the current sampled input is low or high.
        return period - phase;
    }

    /** Distance to a potential low transition of the selected DIV/APU tap. */
    private long clocksToFrameSequencerEdge(int divOffset) {
        int period = 1 << (FRAME_SEQUENCER_DIV_BIT + 1);
        int phase = (div + divOffset) & (period - 1);
        return period - phase;
    }

    /** Distance to the carry which exposes the one-tick DMG HALT-bug ripple. */
    private long clocksToPendingDividerRipple() {
        if (!haltBugDivRipplePending) {
            return Long.MAX_VALUE;
        }
        return 0x100 - (div & 0xff);
    }

    private void acknowledgeInterruptIfNeeded() {
        if (!interruptManager.consumeTimerInterruptAcknowledge()) {
            return;
        }

        int acknowledgeWindow = speedMode.isGbc() ? 8 : 3;
        if (clocksToNextInterrupt() <= acknowledgeWindow) {
            suppressNextInterruptRequest = true;
        }
        interruptManager.finishTimerInterruptAcknowledge();
    }

    private long clocksToNextInterrupt() {
        if ((tac & 0x04) == 0) {
            return Long.MAX_VALUE;
        }
        if (overflow) {
            return ticksSinceOverflow < 4
                    ? 4L - ticksSinceOverflow
                    : Long.MAX_VALUE;
        }

        int bitPos = FREQ_TO_BIT[tac & 0x03];
        int period = 1 << (bitPos + 1);
        int clocksToFirstFallingEdge = period - (div & (period - 1));
        int fallingEdgesToOverflow = 0x100 - tima;
        return clocksToFirstFallingEdge
                + (long) (fallingEdgesToOverflow - 1) * period
                + 3;
    }

    private void tickCpuClock() {
        haltBugDivRippleVisible = false;
        if (haltWakeDelay > 0 && --haltWakeDelay == 0) {
            interruptManager.releaseHaltWake(InterruptManager.InterruptType.Timer);
        }
        int oldDiv = div;
        div = (div + 1) & 0xffff;
        boolean bit = (tac & 0x04) != 0
                && (div & (1 << FREQ_TO_BIT[tac & 0x03])) != 0;
        if (!bit && previousBit) {
            incTima();
        }
        previousBit = bit;
        if (ticksSinceDivReset != Integer.MAX_VALUE) {
            ticksSinceDivReset++;
        }
        if (haltBugDivRipplePending && (oldDiv & 0xff) == 0xff && (div & 0xff) == 0) {
            haltBugDivRipplePending = false;
            haltBugDivRippleVisible = true;
        }
        if (overflow) {
            ticksSinceOverflow++;
            // The reload/IRQ gate opens three clocks after the falling edge
            // (the edge tick itself is count 1). TMA then owns TIMA for four
            // clocks; writes before that window cancel the overflow, while
            // writes inside it are overwritten by the reload bus.
            if (ticksSinceOverflow >= 4) {
                int oldTima = tima;
                tima = tma;
                if (ticksSinceOverflow == 4 || oldTima != tima) {
                    notifyDebugEvent(TimerTrace.Kind.COUNTER_RELOADED);
                }
            }
            if (ticksSinceOverflow == 4) {
                if (suppressNextInterruptRequest) {
                    suppressNextInterruptRequest = false;
                } else {
                    interruptManager.requestInterruptBeforeHaltWake(InterruptManager.InterruptType.Timer);
                    haltWakeDelay = 4;
                }
            }
            if (ticksSinceOverflow == 8) {
                overflow = false;
                ticksSinceOverflow = 0;
            }
        }
    }

    private void incTima() {
        tima++;
        tima %= 0x100;
        if (tima == 0) {
            overflow = true;
            ticksSinceOverflow = 0;
            notifyDebugEvent(TimerTrace.Kind.COUNTER_OVERFLOWED);
        } else {
            notifyDebugEvent(TimerTrace.Kind.COUNTER_INCREMENTED);
        }
    }

    private void updateDiv(int newDiv) {
        this.div = newDiv;
        boolean bit = timerInput(div, tac);
        if (!bit && previousBit) {
            incTima();
        }
        previousBit = bit;
    }

    private static boolean timerInput(int div, int tac) {
        int bitPos = FREQ_TO_BIT[tac & 0b11];
        return (tac & (1 << 2)) != 0 && (div & (1 << bitPos)) != 0;
    }

    @Override
    public boolean accepts(int address) {
        return address >= 0xff04 && address <= 0xff07;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff04:
                updateDiv(0);
                divReset = true;
                ticksSinceDivReset = 0;
                haltBugDivRipplePending = false;
                haltBugDivRippleVisible = false;
                notifyDebugEvent(TimerTrace.Kind.DIVIDER_RESET);
                break;

            case 0xff05:
                if (ticksSinceOverflow < 4) {
                    tima = value;
                    overflow = false;
                    ticksSinceOverflow = 0;
                }
                break;

            case 0xff06:
                tma = value;
                break;

            case 0xff07:
                int oldControl = tac & 0x07;
                if (speedMode.isGbc()) {
                    // TAC changes the input of the timer's falling-edge detector at
                    // the write edge itself. Waiting for the following divider tick
                    // is observably one T-cycle late on CGB (tac_set_enabled).
                    boolean oldInput = timerInput(div, tac);
                    boolean newInput = timerInput(div, value);
                    tac = value;
                    if (oldInput && !newInput) {
                        incTima();
                    }
                    previousBit = newInput;
                } else {
                    tac = value;
                }
                if ((tac & 0x07) != oldControl) {
                    notifyDebugEvent(TimerTrace.Kind.CONTROL_CHANGED);
                }
                break;
        }
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        this.debugHooks = debugHooks;
    }

    private void notifyDebugEvent(TimerTrace.Kind kind) {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onTimerEvent(kind, div, tima, tma, tac & 0x07);
        }
    }

    public boolean consumeDivReset() {
        boolean result = divReset;
        divReset = false;
        return result;
    }

    public boolean isDivResetPending() {
        return divReset;
    }

    /**
     * Records the DMG HALT-bug clock phase. This is intentionally tied to the
     * divider reset phase rather than to a particular instruction stream.
     */
    public void onHaltBug() {
        if (!speedMode.isGbc() && speedMode.getSpeedMode() == 1 && ticksSinceDivReset == 4) {
            haltBugDivRipplePending = true;
        }
    }

    /**
     * Applies the CGB timer-divider phase adjustment performed by the speed
     * switch clock mux before STOP resets DIV.
     */
    public void onSpeedSwitch() {
        if ((tac & 0x07) >= 0x05) {
            updateDiv((div + 4) & 0xffff);
        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff04:
                return haltBugDivRippleVisible ? 0x55 : div >> 8;

            case 0xff05:
                return tima;

            case 0xff06:
                return tma;

            case 0xff07:
                return tac | 0b11111000;
        }
        throw new IllegalArgumentException();
    }

    @Override
    public ComponentState<Timer> captureState() {
        return new TimerState(div, tac, tma, tima, previousBit, overflow, ticksSinceOverflow, divReset,
                haltWakeDelay, ticksSinceDivReset, haltBugDivRipplePending, haltBugDivRippleVisible,
                suppressNextInterruptRequest);
    }

    @Override
    public void restoreState(ComponentState<Timer> state) {
        if (!(state instanceof TimerState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.div = mem.div;
        this.tac = mem.tac;
        this.tma = mem.tma;
        this.tima = mem.tima;
        this.previousBit = mem.previousBit;
        this.overflow = mem.overflow;
        this.ticksSinceOverflow = mem.ticksSinceOverflow;
        this.divReset = mem.divReset;
        this.haltWakeDelay = mem.haltWakeDelay;
        this.ticksSinceDivReset = mem.ticksSinceDivReset;
        this.haltBugDivRipplePending = mem.haltBugDivRipplePending;
        this.haltBugDivRippleVisible = mem.haltBugDivRippleVisible;
        this.suppressNextInterruptRequest = mem.suppressNextInterruptRequest;
    }

    public record TimerState(int div, int tac, int tma, int tima, boolean previousBit, boolean overflow,
                               int ticksSinceOverflow, boolean divReset,
                               int haltWakeDelay, int ticksSinceDivReset,
                               boolean haltBugDivRipplePending,
                               boolean haltBugDivRippleVisible,
                               boolean suppressNextInterruptRequest) implements ComponentState<Timer> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    public record TimerMemento(int div, int tac, int tma, int tima, boolean previousBit, boolean overflow,
                               int ticksSinceOverflow, boolean divReset,
                               int haltWakeDelay, int ticksSinceDivReset,
                               boolean haltBugDivRipplePending,
                               boolean haltBugDivRippleVisible,
                               boolean suppressNextInterruptRequest) implements Memento<Timer> {
    }

}
