package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.Gameboy;

public class VolumeEnvelope {

    private int initialVolume;

    private int envelopeDirection;

    private int sweep;

    private int volume;

    private int i;

    private boolean finished;

    public void setNr2(int register) {
        this.initialVolume = register >> 4;
        this.envelopeDirection = (register & (1 << 3)) == 0 ? -1 : 1;
        this.sweep = register & 0b111;
    }

    public boolean isEnabled() {
        return sweep > 0;
    }

    public void start() {
        finished = true;
        i = 8192;
    }

    public void trigger() {
        volume = initialVolume;
        i = 0;
        finished = false;
    }

    public void tick() {
        if (finished) {
            return;
        }
        if ((volume == 0 && envelopeDirection == -1) || (volume == 15 && envelopeDirection == 1)) {
            finished = true;
            return;
        }
        if (++i == sweep * Gameboy.TICKS_PER_SEC / 64) {
            i = 0;
            volume += envelopeDirection;
        }
    }

    public int getVolume() {
        if (isEnabled()) {
            return volume;
        } else {
            return initialVolume;
        }
    }
}
