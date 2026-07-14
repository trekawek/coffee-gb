package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class VolumeEnvelope implements Serializable, Originator<VolumeEnvelope> {

    private int initialVolume;

    private int envelopeDirection;

    private int sweep;

    private int volume;

    private int timer;

    private boolean finished;

    private boolean pendingEnvelopeClock;

    public void setNr2(int register) {
        setNr2(register, false);
    }

    public void setNr2(int register, boolean channelActive) {
        int oldSweep = sweep;
        int oldDirection = envelopeDirection;
        if (channelActive) {
            applyWriteGlitch(register, oldSweep, oldDirection);
        }
        this.initialVolume = register >> 4;
        this.envelopeDirection = (register & (1 << 3)) == 0 ? -1 : 1;
        this.sweep = register & 0b111;
        if (channelActive && oldSweep == 0 && sweep != 0 && !finished) {
            pendingEnvelopeClock = true;
        } else if (sweep == 0) {
            pendingEnvelopeClock = false;
        }
    }

    public boolean isEnabled() {
        return sweep > 0;
    }

    public void start() {
        finished = true;
        pendingEnvelopeClock = false;
    }

    public void trigger() {
        volume = initialVolume;
        timer = sweep == 0 ? 8 : sweep;
        finished = false;
        pendingEnvelopeClock = false;
    }

    public void clockTick() {
        if (finished) {
            return;
        }
        if ((volume == 0 && envelopeDirection == -1) || (volume == 15 && envelopeDirection == 1)) {
            finished = true;
            return;
        }
        if (sweep == 0) {
            return;
        }
        if (--timer <= 0) {
            timer = sweep;
            clockVolume();
        }
    }

    public void apuClockTick(int frameSequencerStep) {
        // The frame sequencer numbers the power-on edge as step 0, while the
        // envelope's secondary latch observes the opposite half of that phase.
        if (pendingEnvelopeClock && (frameSequencerStep & 1) == 1) {
            pendingEnvelopeClock = false;
            timer = sweep;
            clockVolume();
        }
    }

    private void applyWriteGlitch(int register, int oldSweep, int oldDirection) {
        int oldLow = oldSweep | (oldDirection == 1 ? 0b1000 : 0);
        int newLow = register & 0b1111;
        boolean locked = finished;
        boolean shouldTick = (newLow & 0b111) != 0 && oldSweep == 0 && !locked;
        boolean shouldInvert = ((newLow ^ oldLow) & 0b1000) != 0;

        if (newLow == 0b1000 && oldLow == 0b1000 && !locked) {
            shouldTick = true;
        }
        if (shouldInvert) {
            if ((newLow & 0b1000) != 0) {
                if (oldSweep == 0 && !locked) {
                    volume ^= 0b1111;
                } else {
                    volume = (14 - volume) & 0b1111;
                }
                shouldTick = false;
            } else {
                volume = (16 - volume) & 0b1111;
            }
        }
        if (shouldTick) {
            volume = (volume + ((newLow & 0b1000) != 0 ? 1 : -1)) & 0b1111;
        }
    }

    private void clockVolume() {
        if ((volume == 0 && envelopeDirection == -1) || (volume == 15 && envelopeDirection == 1)) {
            finished = true;
            return;
        }
        volume += envelopeDirection;
    }

    public int getVolume() {
        return volume;
    }

    @Override
    public Memento<VolumeEnvelope> saveToMemento() {
        return new VolumeEnvelopeMemento(initialVolume, envelopeDirection, sweep, volume, timer, finished,
                pendingEnvelopeClock);
    }

    @Override
    public void restoreFromMemento(Memento<VolumeEnvelope> memento) {
        if (!(memento instanceof VolumeEnvelopeMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.initialVolume = mem.initialVolume;
        this.envelopeDirection = mem.envelopeDirection;
        this.sweep = mem.sweep;
        this.volume = mem.volume;
        this.timer = mem.timer;
        this.finished = mem.finished;
        this.pendingEnvelopeClock = mem.pendingEnvelopeClock;
    }

    private record VolumeEnvelopeMemento(int initialVolume, int envelopeDirection, int sweep, int volume, int timer,
                                         boolean finished, boolean pendingEnvelopeClock) implements Memento<VolumeEnvelope> {
    }

}
