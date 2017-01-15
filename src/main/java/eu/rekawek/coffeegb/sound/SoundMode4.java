package eu.rekawek.coffeegb.sound;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;

public class SoundMode4 extends AbstractSoundMode {

    private int lengthCounter;

    public SoundMode4() {
        super(0xff1f);
    }

    @Override
    public boolean isEnabled() {
        return lengthCounter > 0 || ((nr4 & (1 << 6)) == 0);
    }

    @Override
    public void trigger() {
        this.lengthCounter = 64;
    }

    @Override
    public int tick() {
        if (lengthCounter > 0) {
            lengthCounter--;
        }

        return 0;
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        lengthCounter = (value & 0b00111111) * (TICKS_PER_SEC / 256);
    }
}
