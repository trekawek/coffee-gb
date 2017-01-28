package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class SoundMode3 extends AbstractSoundMode {

    private final AddressSpace waveram;

    private int freqDivider;

    private int lastOutput;

    private int i;

    public SoundMode3(AddressSpace waveram) {
        super(0xff1a);
        this.waveram = waveram;
    }

    @Override
    public void trigger() {
        if (lengthCounter == 0) {
            this.lengthCounter = 256 * (TICKS_PER_SEC / 256);
        }
        i = 0;
        resetFreqDivider();
    }

    @Override
    public int tick() {
        if (!dacEnabled) {
            return 0;
        }
        if (!updateLength()) {
            return 0;
        }

        if ((getNr0() & (1 << 7)) == 0) {
            return 0;
        }

        if (freqDivider-- == 0) {
            resetFreqDivider();
            lastOutput = getWaveEntry(i);
            i = (i + 1) % 32;
        }
        return lastOutput;
    }

    @Override
    protected void setNr0(int value) {
        super.setNr0(value);
        dacEnabled = (value & (1 << 7)) != 0;
        enabled &= dacEnabled;
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        lengthCounter = (256 - value) * (TICKS_PER_SEC / 256);
    }

    private int getVolume() {
        return (getNr2() >> 5) & 0b11;
    }

    private int getWaveEntry(int i) {
        int b = waveram.getByte(0xff30 + i / 2);
        if (i % 2 == 0) {
            b = b >> 4;
        } else {
            b = b & 0x0f;
        }
        switch (getVolume()) {
            case 0:
                return 0;
            case 1:
                return b;
            case 2:
                return b >> 1;
            case 3:
                return b >> 2;
            default:
                throw new IllegalStateException();
        }
    }

    private void resetFreqDivider() {
        freqDivider = getFrequency() * 2;
    }
}
