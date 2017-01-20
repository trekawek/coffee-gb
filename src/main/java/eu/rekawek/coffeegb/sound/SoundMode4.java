package eu.rekawek.coffeegb.sound;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class SoundMode4 extends AbstractSoundMode {

    private int lengthCounter;

    private VolumeEnvelope volumeEnvelope;

    private PolynomialCounter polynomialCounter;

    private int lastResult;

    private Lfsr lfsr = new Lfsr();

    public SoundMode4() {
        super(0xff1f);
        this.volumeEnvelope = new VolumeEnvelope(nr2);
        this.polynomialCounter = new PolynomialCounter(nr3);
    }

    @Override
    public boolean isEnabled() {
        return lengthCounter > 0 || ((nr4 & (1 << 6)) == 0);
    }

    @Override
    public void trigger() {
        this.lengthCounter = TICKS_PER_SEC / 256 * 64;
        lfsr.reset();
        volumeEnvelope.start();
    }

    @Override
    public int tick() {
        if (lengthCounter > 0) {
            lengthCounter--;
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
        lengthCounter = (value & 0b00111111) * (TICKS_PER_SEC / 256);
    }

    @Override
    protected void setNr2(int value) {
        super.setNr2(value);
        volumeEnvelope = new VolumeEnvelope(value);
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        polynomialCounter = new PolynomialCounter(value);
    }
}
