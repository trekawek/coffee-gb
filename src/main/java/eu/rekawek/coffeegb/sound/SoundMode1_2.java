package eu.rekawek.coffeegb.sound;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class SoundMode1_2 extends AbstractSoundMode {

    private final int mode;

    private int freqDivider;

    private int lengthCounter;

    private int lastOutput;

    private int i;

    private FrequencySweep frequencySweep;

    private VolumeEnvelope volumeEnvelope;

    public SoundMode1_2(int mode) {
        super(mode == 1 ? 0xff10 : 0xff15);
        if (mode != 1 && mode != 2) {
            throw new IllegalArgumentException();
        }
        this.mode = mode;
        this.frequencySweep = new FrequencySweep(nr0, getFrequency());
        this.volumeEnvelope = new VolumeEnvelope(nr2);
    }

    @Override
    public boolean isEnabled() {
        return lengthCounter > 0 || ((nr4 & (1 << 6)) == 0);
    }

    @Override
    public void trigger() {
        this.lengthCounter = 64;
        this.i = 0;
        resetFreqDivider();
    }

    @Override
    public int tick() {
        if (lengthCounter > 0) {
            lengthCounter--;
        }

        volumeEnvelope.tick();
        frequencySweep.tick();

        if (freqDivider-- == 0) {
            resetFreqDivider();
            lastOutput = ((getDuty() & (1 << i)) >> i);
            lastOutput *= volumeEnvelope.getVolume();
            i = (i + 1) % 8;
        }
        return lastOutput;
    }

    @Override
    protected void setNr0(int value) {
        super.setNr0(value);
        if (mode == 1) {
            frequencySweep = new FrequencySweep(value, getFrequency());
        }
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        lengthCounter = (value & 0b00111111) * (TICKS_PER_SEC / 256);
    }

    protected void setNr2(int value) {
        super.setNr2(value);
        volumeEnvelope = new VolumeEnvelope(value);
    }

    private int getDuty() {
        switch (getNr1() >> 6) {
            case 0:
                return 0b00000001;
            case 1:
                return 0b10000001;
            case 2:
                return 0b10000111;
            case 3:
                return 0b01111110;
            default:
                throw new IllegalStateException();
        }
    }

    private void resetFreqDivider() {
        if (frequencySweep.isEnabled()) {
            freqDivider = frequencySweep.getFreq() * 4;
        } else {
            freqDivider = getFrequency() * 4;
        }
    }
}
