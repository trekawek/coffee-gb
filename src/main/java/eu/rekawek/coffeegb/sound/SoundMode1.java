package eu.rekawek.coffeegb.sound;

public class SoundMode1 extends AbstractSoundMode {

    private int freqDivider;

    private int lastOutput;

    private int i;

    private FrequencySweep frequencySweep;

    private VolumeEnvelope volumeEnvelope;

    public SoundMode1(boolean gbc) {
        super(0xff10, 64, gbc);
        this.frequencySweep = new FrequencySweep();
        this.volumeEnvelope = new VolumeEnvelope();
    }

    @Override
    public void start() {
        i = 0;
        if (gbc) {
            length.reset();
        }
        length.start();
        frequencySweep.start();
        volumeEnvelope.start();
    }

    @Override
    public void trigger() {
        i = 0;
        freqDivider = 1;
        volumeEnvelope.trigger();
    }

    @Override
    public int tick() {
        volumeEnvelope.tick();

        boolean e = true;
        e = updateLength() && e;
        e = updateSweep() && e;
        e = dacEnabled && e;
        if (!e) {
            return 0;
        }

        if (--freqDivider == 0) {
            resetFreqDivider();
            lastOutput = ((getDuty() & (1 << i)) >> i);
            i = (i + 1) % 8;
        }
        return lastOutput * volumeEnvelope.getVolume();
    }

    @Override
    protected void setNr0(int value) {
        super.setNr0(value);
        frequencySweep.setNr10(value);
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        length.setLength(64 - (value & 0b00111111));
    }

    @Override
    protected void setNr2(int value) {
        super.setNr2(value);
        volumeEnvelope.setNr2(value);
        dacEnabled = (value & 0b11111000) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        frequencySweep.setNr13(value);
    }

    @Override
    protected void setNr4(int value) {
        super.setNr4(value);
        frequencySweep.setNr14(value);
    }

    @Override
    protected int getNr3() {
        return frequencySweep.getNr13();
    }

    @Override
    protected int getNr4() {
        return (super.getNr4() & 0b11111000) | (frequencySweep.getNr14() & 0b00000111);
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
        freqDivider = getFrequency() * 4;
    }

    protected boolean updateSweep() {
        frequencySweep.tick();
        if (channelEnabled && !frequencySweep.isEnabled()) {
            channelEnabled = false;
        }
        return channelEnabled;
    }
}
