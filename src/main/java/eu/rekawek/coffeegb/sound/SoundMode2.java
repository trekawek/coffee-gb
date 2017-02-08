package eu.rekawek.coffeegb.sound;

public class SoundMode2 extends AbstractSoundMode {

    private int freqDivider;

    private int lastOutput;

    private int i;

    private VolumeEnvelope volumeEnvelope;

    public SoundMode2(boolean gbc) {
        super(0xff15, 64, gbc);
        this.volumeEnvelope = new VolumeEnvelope();
    }

    @Override
    public void start() {
        i = 0;
        if (gbc) {
            length.reset();
        }
        length.start();
        volumeEnvelope.start();
    }

    @Override
    public void trigger() {
        this.i = 0;
        freqDivider = 1;
        volumeEnvelope.trigger();
    }

    @Override
    public int tick() {
        volumeEnvelope.tick();

        boolean e = true;
        e = updateLength() && e;
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
}
