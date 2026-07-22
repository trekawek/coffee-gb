package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

public class SoundMode2 extends AbstractSoundMode {

    private int freqDivider;

    private int lastOutput;

    private int i;

    private boolean sampleSuppressed;

    private boolean activeBeforeTrigger;

    private boolean clock2Mhz;

    private boolean lowFrequencyPhase;

    private int justReloadedTicks;

    private final VolumeEnvelope volumeEnvelope;

    public SoundMode2(FrameSequencer frameSequencer, boolean gbc) {
        super(0xff15, 64, frameSequencer, gbc);
        this.volumeEnvelope = new VolumeEnvelope();
    }

    @Override
    public void start() {
        i = 0;
        sampleSuppressed = true;
        clock2Mhz = false;
        lowFrequencyPhase = true;
        justReloadedTicks = 0;
        if (gbc) {
            length.reset();
        }
        volumeEnvelope.start();
    }

    @Override
    public void stop() {
        super.stop();
        i = 0;
        lastOutput = 0;
        sampleSuppressed = true;
        clock2Mhz = false;
        lowFrequencyPhase = true;
        justReloadedTicks = 0;
        volumeEnvelope.setNr2(0);
    }

    @Override
    public void trigger() {
        // the duty position is not changed by the trigger, only the timer is reloaded
        int triggerDelay = activeBeforeTrigger ? 4 : 6;
        freqDivider = (getFrequency() - 1) * 2 + triggerDelay - (lowFrequencyPhase ? 1 : 0);
        if (!activeBeforeTrigger) {
            sampleSuppressed = true;
            lastOutput = 0;
        }
        volumeEnvelope.trigger();
    }

    @Override
    public void tickEnvelope() {
        volumeEnvelope.clockTick();
    }

    @Override
    public void tickEnvelopeClock(int frameSequencerStep) {
        volumeEnvelope.apuClockTick(frameSequencerStep);
    }

    @Override
    public int tick() {
        return tick(false);
    }

    @Override
    public int tick(boolean divReset) {
        clock2Mhz = !clock2Mhz;
        if (justReloadedTicks > 0) {
            justReloadedTicks--;
        }
        if (clock2Mhz) {
            lowFrequencyPhase = !lowFrequencyPhase;
        }

        boolean e;
        e = updateLength();
        e = dacEnabled && e;
        if (!e) {
            return 0;
        }

        if (clock2Mhz && freqDivider-- == 0) {
            resetFreqDivider();
            i = (i + 1) % 8;
            lastOutput = ((getDuty() & (1 << i)) >> i);
            sampleSuppressed = false;
            justReloadedTicks = 4;
        }
        return getCurrentOutput();
    }

    @Override
    public int getCurrentOutput() {
        return (sampleSuppressed ? 0 : lastOutput) * volumeEnvelope.getVolume();
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
        volumeEnvelope.setNr2(value, channelEnabled);
        dacEnabled = (value & 0b11111000) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        if (justReloadedTicks > 0) {
            resetFreqDivider();
        }
    }

    @Override
    protected void setNr4(int value) {
        activeBeforeTrigger = channelEnabled;
        super.setNr4(value);
        if ((value & (1 << 7)) == 0 && justReloadedTicks > 0) {
            resetFreqDivider();
        }
    }

    private int getDuty() {
        switch (getNr1() >> 6) {
            case 0:
                return 0b10000000;
            case 1:
                return 0b10000001;
            case 2:
                return 0b11100001;
            case 3:
                return 0b01111110;
            default:
                throw new IllegalStateException();
        }
    }

    private void resetFreqDivider() {
        freqDivider = (getFrequency() - 1) * 2 + 1;
    }

    @Override
    public Memento<AbstractSoundMode> saveToMemento() {
        return new SoundMode2Memento(super.saveToMemento(), freqDivider, lastOutput, i, sampleSuppressed,
                activeBeforeTrigger, clock2Mhz, lowFrequencyPhase, volumeEnvelope.saveToMemento(),
                justReloadedTicks);
    }

    @Override
    public void restoreFromMemento(Memento<AbstractSoundMode> memento) {
        if (!(memento instanceof SoundMode2Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        super.restoreFromMemento(mem.abstractSoundMemento);
        this.freqDivider = mem.freqDivider;
        this.lastOutput = mem.lastOutput;
        this.i = mem.i;
        this.sampleSuppressed = mem.sampleSuppressed;
        this.activeBeforeTrigger = mem.activeBeforeTrigger;
        this.clock2Mhz = mem.clock2Mhz;
        this.lowFrequencyPhase = mem.lowFrequencyPhase;
        this.justReloadedTicks = mem.justReloadedTicks;
        this.volumeEnvelope.restoreFromMemento(mem.volumeEnvelopeMemento);
    }

    private record SoundMode2Memento(Memento<AbstractSoundMode> abstractSoundMemento, int freqDivider, int lastOutput,
                                     int i, boolean sampleSuppressed, boolean activeBeforeTrigger,
                                     boolean clock2Mhz, boolean lowFrequencyPhase,
                                     Memento<VolumeEnvelope> volumeEnvelopeMemento,
                                     int justReloadedTicks) implements Memento<AbstractSoundMode> {
    }

}
