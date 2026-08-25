package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class PolynomialCounter implements StatefulComponent<PolynomialCounter> {

    private static final int[] RELOAD_ALIGNMENT_OFFSETS = {2, 1, 0, 3};

    private int nr43;

    // The noise generator uses a prescaler feeding a free-running 14-bit counter;
    // the NR43 shift selects which rising counter edge clocks the LFSR.
    private int counter;

    // Expressed in fixed 2 MHz APU cycles.
    private int counterCountdown;

    private boolean clock2Mhz;

    private int alignment;

    private boolean backgroundActive;

    private boolean countdownReloaded;

    public void start() {
        nr43 = 0;
        counter = 0;
        counterCountdown = 0;
        clock2Mhz = false;
        alignment = 0;
        backgroundActive = false;
        countdownReloaded = false;
    }

    public void stop() {
        start();
    }

    public void setNr43(int value) {
        if (countdownReloaded) {
            int divisor = (value & 0b111) << 2;
            if (divisor == 0) {
                divisor = 2;
            }
            counterCountdown = divisor + (divisor == 2 ? 0 : RELOAD_ALIGNMENT_OFFSETS[alignment]);
        }
        nr43 = value;
    }

    public void trigger() {
        int divisor = nr43 & 0b111;
        counterCountdown = divisor == 0 ? 6 : divisor * 4 + 6;
        if (divisor == 0) {
            if ((alignment & 1) != 0) {
                counterCountdown += backgroundActive ? -1 : 1;
            }
        } else {
            if (alignment == 2) {
                counterCountdown -= 2;
            } else if (alignment == 0 && divisor > 1) {
                counterCountdown -= 4;
            }
        }
        backgroundActive = true;
        countdownReloaded = false;
    }

    public boolean tick() {
        clock2Mhz = !clock2Mhz;
        if (!clock2Mhz) {
            return false;
        }

        alignment = (alignment + 1) & 3;
        if (!backgroundActive) {
            return false;
        }

        if (--counterCountdown > 0) {
            countdownReloaded = false;
            return false;
        }

        int divisor = (nr43 & 0b111) << 2;
        counterCountdown = divisor == 0 ? 2 : divisor;

        int mask = 1 << (nr43 >> 4);
        boolean oldBit = (counter & mask) != 0;
        counter = (counter + 1) & 0x3fff;
        boolean newBit = (counter & mask) != 0;
        countdownReloaded = true;
        return !oldBit && newBit;
    }

    /** Advances the free-running noise counter over a short quiet span. */
    int advancePerformanceSpan(int ticks) {
        if (ticks <= 0) {
            return 0;
        }
        int edgeCount = clock2Mhz ? ticks / 2 : (ticks + 1) / 2;
        int steps = 0;
        alignment = (alignment + edgeCount) & 3;
        if (!backgroundActive) {
            clock2Mhz ^= (ticks & 1) != 0;
            return 0;
        }
        // Until the next prescaler reload, the active noise state is only a countdown. Avoid
        // visiting each 2-MHz edge when this whole compact window stays in that quiet interval.
        if (edgeCount > 0 && counterCountdown > edgeCount) {
            counterCountdown -= edgeCount;
            countdownReloaded = false;
            clock2Mhz ^= (ticks & 1) != 0;
            return 0;
        }
        if (edgeCount == 0) {
            clock2Mhz ^= (ticks & 1) != 0;
            return 0;
        }

        int reload = (nr43 & 0b111) << 2;
        if (reload == 0) {
            reload = 2;
        }
        int after = edgeCount - counterCountdown;
        int increments = 1 + after / reload;
        int tail = after - (increments - 1) * reload;
        counterCountdown = tail == 0 ? reload : reload - tail;
        countdownReloaded = tail == 0;

        int shift = nr43 >>> 4;
        if (shift < 14) {
            int mask = 1 << shift;
            steps = ((counter + increments + mask) >>> (shift + 1))
                    - ((counter + mask) >>> (shift + 1));
        }
        counter = (counter + increments) & 0x3fff;
        clock2Mhz ^= (ticks & 1) != 0;
        return steps;
    }

    @Override
    public ComponentState<PolynomialCounter> captureState() {
        return new PolynomialCounterState(nr43, counter, counterCountdown, clock2Mhz, alignment, backgroundActive,
                countdownReloaded);
    }

    @Override
    public void restoreState(ComponentState<PolynomialCounter> state) {
        if (!(state instanceof PolynomialCounterState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.nr43 = mem.nr43;
        this.counter = mem.counter;
        this.counterCountdown = mem.counterCountdown;
        this.clock2Mhz = mem.clock2Mhz;
        this.alignment = mem.alignment;
        this.backgroundActive = mem.backgroundActive;
        this.countdownReloaded = mem.countdownReloaded;
    }

    private record PolynomialCounterState(int nr43, int counter, int counterCountdown, boolean clock2Mhz,
                                             int alignment, boolean backgroundActive,
                                             boolean countdownReloaded) implements ComponentState<PolynomialCounter> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record PolynomialCounterMemento(int nr43, int counter, int counterCountdown, boolean clock2Mhz,
                                             int alignment, boolean backgroundActive,
                                             boolean countdownReloaded) implements Memento<PolynomialCounter> {
    }
}
