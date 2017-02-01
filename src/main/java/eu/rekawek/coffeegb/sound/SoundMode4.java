package eu.rekawek.coffeegb.sound;

public class SoundMode4 extends AbstractSoundMode {

    private VolumeEnvelope volumeEnvelope;

    private PolynomialCounter polynomialCounter;

    private int lastResult;

    private Lfsr lfsr = new Lfsr();

    public SoundMode4() {
        super(0xff1f, 64);
        this.volumeEnvelope = new VolumeEnvelope(nr2);
        this.polynomialCounter = new PolynomialCounter(nr3);
    }

    @Override
    public void start() {
        lfsr.start();
        volumeEnvelope.start();
        length.start();
    }

    @Override
    public void trigger() {
        lfsr.reset();
        volumeEnvelope.trigger();
    }

    @Override
    public int tick() {
        if (!updateLength()) {
            return 0;
        }
        if (!dacEnabled) {
            return 0;
        }

        volumeEnvelope.tick();

        if (polynomialCounter.tick()) {
            lastResult = lfsr.nextBit((nr3 & (1 << 3)) != 0);
            lastResult *= volumeEnvelope.getVolume();
        }
        return lastResult;
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        length.setLength(64 - (value & 0b00111111));
    }

    @Override
    protected void setNr2(int value) {
        super.setNr2(value);
        volumeEnvelope = new VolumeEnvelope(value);
        dacEnabled = (value & 0b11111000) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        polynomialCounter = new PolynomialCounter(value);
    }
}
