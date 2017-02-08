package eu.rekawek.coffeegb.sound;

public class SoundMode4 extends AbstractSoundMode {

    private VolumeEnvelope volumeEnvelope;

    private PolynomialCounter polynomialCounter;

    private int lastResult;

    private Lfsr lfsr = new Lfsr();

    public SoundMode4(boolean gbc) {
        super(0xff1f, 64, gbc);
        this.volumeEnvelope = new VolumeEnvelope();
        this.polynomialCounter = new PolynomialCounter();
    }

    @Override
    public void start() {
        if (gbc) {
            length.reset();
        }
        length.start();
        lfsr.start();
        volumeEnvelope.start();
    }

    @Override
    public void trigger() {
        lfsr.reset();
        volumeEnvelope.trigger();
    }

    @Override
    public int tick() {
        volumeEnvelope.tick();

        if (!updateLength()) {
            return 0;
        }
        if (!dacEnabled) {
            return 0;
        }

        if (polynomialCounter.tick()) {
            lastResult = lfsr.nextBit((nr3 & (1 << 3)) != 0);
        }
        return lastResult * volumeEnvelope.getVolume();
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
        polynomialCounter.setNr43(value);
    }
}
