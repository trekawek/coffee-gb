package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.Gameboy;

public abstract class AbstractLengthCounterTest {

    protected final int maxlen;

    protected final FrameSequencer frameSequencer;

    protected final LengthCounter lengthCounter;

    private int divCounter;

    public AbstractLengthCounterTest() {
        this(256);
    }

    public AbstractLengthCounterTest(int maxlen) {
        this.maxlen = maxlen;
        this.frameSequencer = new FrameSequencer();
        this.lengthCounter = new LengthCounter(maxlen, frameSequencer);
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
            int firedStep = frameSequencer.tick(divCounter = (divCounter + 1) & 0xffff, true, false);
            if (firedStep >= 0 && (firedStep & 1) == 0) {
                lengthCounter.clockTick();
            }
        }
    }

    protected void delayApu(int apuUnit) {
        delayClocks(apuUnit * (Gameboy.TICKS_PER_SEC / 256));
    }

    protected void syncApu() {
        frameSequencer.reset();
        lengthCounter.reset();
        // blargg's sync_apu returns just after a length clock, i.e. the next frame
        // sequencer step is one that does not clock length
        while (frameSequencer.tick(divCounter = (divCounter + 1) & 0xffff, true, false) != 0) {
            // advance until step 0 fires
        }
    }
}
