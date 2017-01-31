package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.Gameboy;

public abstract class AbstractLengthCounterTest {

    protected final int maxlen;

    protected final LengthCounter lengthCounter;

    public AbstractLengthCounterTest() {
        this(256);
    }

    public AbstractLengthCounterTest(int maxlen) {
        this.maxlen = maxlen;
        this.lengthCounter = new LengthCounter(maxlen);
    }

    protected void wchn(int register, int value) {
        if (register == 1) {
            lengthCounter.setLength(0 - value);
        } else if (register == 4) {
            lengthCounter.setNr4(value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    protected void delayClocks(int clocks) {
        for (int i = 0; i < clocks; i++) {
            lengthCounter.tick();
        }
    }

    protected void delayApu(int apuUnit) {
        delayClocks(apuUnit * (Gameboy.TICKS_PER_SEC / 256));
    }

    protected void syncApu() {
        lengthCounter.reset();
    }
}
