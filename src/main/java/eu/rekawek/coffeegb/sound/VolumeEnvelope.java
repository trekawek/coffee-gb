package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.Gameboy;

public class VolumeEnvelope {

    private final int initialVolume;

    private final int envelopeDirection;

    private final int sweep;

    private int volume;

    private int i;

    private boolean finished;

    public VolumeEnvelope(int register) {
        this.initialVolume = register >> 4;
        this.envelopeDirection = (register & (1 << 3)) == 0 ? -1 : 1;
        this.sweep = register & 0b111;
        start();
    }

    public boolean isEnabled() {
        return sweep > 0;
    }

    public void start() {
        volume = initialVolume;
        i = 0;
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
            return 1;
        }
    }
}
