package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;

public class SoundMode1 extends AbstractSoundMode {

    private int freqDivider;

    private int lastOutput;

    private int i;

    private boolean sampleSuppressed;

    private boolean activeBeforeTrigger;

    // bit 0 = clock2Mhz, bit 1 = lowFrequencyPhase; tick decrements modulo 4
    private int phase;

    private int justReloadedTicks;

    private boolean justReloadedFromSweep;

    private final FrequencySweep frequencySweep;

    private final VolumeEnvelope volumeEnvelope;

    public SoundMode1(FrameSequencer frameSequencer, boolean gbc) {
        super(0xff10, 64, frameSequencer, gbc);
        this.frequencySweep = new FrequencySweep();
        this.volumeEnvelope = new VolumeEnvelope();
    }

    @Override
    public void start() {
        i = 0;
        sampleSuppressed = true;
        phase = 0b10;
        justReloadedTicks = 0;
        justReloadedFromSweep = false;
        if (gbc) {
            length.reset();
        }
        frequencySweep.start();
        volumeEnvelope.start();
    }

    @Override
    public void stop() {
        super.stop();
        i = 0;
        lastOutput = 0;
        sampleSuppressed = true;
        phase = 0b10;
        justReloadedTicks = 0;
        justReloadedFromSweep = false;
        frequencySweep.setNr10(0);
        frequencySweep.setNr13(0);
        frequencySweep.setNr14(0);
        volumeEnvelope.setNr2(0);
    }

    @Override
    public void trigger() {
        // the duty position is not changed by the trigger, only the timer is reloaded
        boolean lowFrequencyPhase = (phase & 2) != 0;
        int triggerDelay = activeBeforeTrigger ? 4 : 6;
        if (activeBeforeTrigger && (justReloadedTicks == 1
                || (gbc && justReloadedTicks == 3 && justReloadedFromSweep))) {
            // A trigger on the trailing T-cycle of a pulse reload must not charge
            // that same 2 MHz edge to the newly loaded divider. On CGB, the first
            // reload after a sweep update reaches this latch two phases earlier.
            triggerDelay++;
        }
        freqDivider = (getFrequency() - 1) * 2 + triggerDelay - (lowFrequencyPhase ? 1 : 0);
        if (!activeBeforeTrigger) {
            sampleSuppressed = true;
            lastOutput = 0;
        }
        frequencySweep.trigger(activeBeforeTrigger, lowFrequencyPhase, gbc);
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
    public void tickSweep() {
        frequencySweep.clockTick();
    }

    @Override
    public int tick() {
        return tick(false);
    }

    @Override
    public int tick(boolean divReset) {
        phase = (phase - 1) & 3;
        frequencySweep.tick();
        if (justReloadedTicks > 0) {
            justReloadedTicks--;
        }
        boolean e;
        e = channelEnabled;
        e = updateSweep() && e;
        e = dacEnabled && e;
        if (!e) {
            return 0;
        }

        if ((phase & 1) != 0 && freqDivider-- == 0) {
            justReloadedFromSweep = frequencySweep.consumeFrequencyUpdate();
            resetFreqDivider();
            i = (i + 1) % 8;
            lastOutput = ((getDuty() & (1 << i)) >> i);
            sampleSuppressed = false;
            justReloadedTicks = 4;
        }
        return getCurrentOutput();
    }

    /** Advances a short PERFORMANCE quiet span while retaining pulse-edge state exactly. */
    int tickPerformanceSpan(int ticks) {
        if (ticks <= 0) {
            return getCurrentOutput();
        }
        // A calculation can disable CH1 before the coincident pulse clock. Advance only
        // the prefix arithmetically, apply that one scalar event, then batch the suffix.
        int calculationAt = frequencySweep.calculationExpiryOffset(ticks);
        if (calculationAt > 0) {
            int prefix = calculationAt - 1;
            if (prefix > 0) {
                frequencySweep.tickPerformanceSpan(prefix);
                advancePulseSpan(prefix);
            }
            int output = tick(false);
            int suffix = ticks - calculationAt;
            return suffix == 0 ? output : tickPerformanceSpan(suffix);
        }

        frequencySweep.tickPerformanceSpan(ticks);
        return advancePulseSpan(ticks);
    }

    private int advancePulseSpan(int ticks) {
        int firstPhase = (phase - 1) & 3;
        int edgeCount = (firstPhase & 1) != 0 ? (ticks + 1) / 2 : ticks / 2;
        int firstEdgePosition = (firstPhase & 1) != 0 ? 1 : 2;
        phase = (phase - ticks) & 3;
        int reloadedAt = 0;
        justReloadedTicks = Math.max(0, justReloadedTicks - ticks);
        boolean e = channelEnabled;
        e = updateSweep() && e;
        e = dacEnabled && e;
        if (!e) {
            return 0;
        }
        // A constant frequency lets every waveform expiry be counted directly. Preserve the
        // final reload's timestamp because an immediately following trigger/frequency write
        // observes its four-dot latch, even when several duty positions were crossed.
        if (freqDivider >= edgeCount) {
            freqDivider -= edgeCount;
        } else {
            int period = getFrequency() * 2;
            int afterFirst = edgeCount - freqDivider - 1;
            int reloads = 1 + afterFirst / period;
            int tail = afterFirst % period;
            boolean frequencyUpdated = frequencySweep.consumeFrequencyUpdate();
            justReloadedFromSweep = reloads == 1 && frequencyUpdated;
            freqDivider = period - 1 - tail;
            i = (i + reloads) & 7;
            lastOutput = (getDuty() >>> i) & 1;
            sampleSuppressed = false;
            reloadedAt = firstEdgePosition + (edgeCount - tail - 1) * 2;
        }
        if (reloadedAt != 0) {
            justReloadedTicks = Math.max(0, 4 - (ticks - reloadedAt));
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
        volumeEnvelope.setNr2(value, channelEnabled);
        dacEnabled = (value & 0b11111000) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        frequencySweep.setNr13(value);
        if (justReloadedTicks > 0) {
            resetFreqDivider();
        }
    }

    @Override
    protected void setNr4(int value) {
        activeBeforeTrigger = channelEnabled;
        frequencySweep.setNr14(value);
        super.setNr4(value);
        if ((value & (1 << 7)) == 0 && justReloadedTicks > 0) {
            resetFreqDivider();
        }
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

    protected boolean updateSweep() {
        if (channelEnabled && !frequencySweep.isEnabled()) {
            channelEnabled = false;
        }
        return channelEnabled;
    }

    @Override
    public ComponentState<AbstractSoundMode> captureState() {
        return new SoundMode1State(super.captureState(), freqDivider, lastOutput, i, sampleSuppressed,
                activeBeforeTrigger, (phase & 1) != 0, (phase & 2) != 0,
                frequencySweep.captureState(),
                justReloadedTicks, justReloadedFromSweep, volumeEnvelope.captureState());
    }

    @Override
    public void restoreState(ComponentState<AbstractSoundMode> state) {
        if (!(state instanceof SoundMode1State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        super.restoreState(mem.abstractSoundMemento);
        this.freqDivider = mem.freqDivider;
        this.lastOutput = mem.lastOutput;
        this.i = mem.i;
        this.sampleSuppressed = mem.sampleSuppressed;
        this.activeBeforeTrigger = mem.activeBeforeTrigger;
        this.phase = Boolean.compare(mem.clock2Mhz, false)
                | (Boolean.compare(mem.lowFrequencyPhase, false) << 1);
        this.justReloadedTicks = mem.justReloadedTicks;
        this.justReloadedFromSweep = mem.justReloadedFromSweep;
        this.frequencySweep.restoreState(mem.frequencySweepMemento);
        this.volumeEnvelope.restoreState(mem.volumeEnvelopeMemento);
    }

    private record SoundMode1State(ComponentState<AbstractSoundMode> abstractSoundMemento, int freqDivider, int lastOutput,
                                     int i, boolean sampleSuppressed, boolean activeBeforeTrigger,
                                     boolean clock2Mhz, boolean lowFrequencyPhase,
                                     ComponentState<FrequencySweep> frequencySweepMemento,
                                     int justReloadedTicks, boolean justReloadedFromSweep,
                                     ComponentState<VolumeEnvelope> volumeEnvelopeMemento) implements ComponentState<AbstractSoundMode> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SoundMode1Memento(Memento<AbstractSoundMode> abstractSoundMemento, int freqDivider, int lastOutput,
                                     int i, boolean sampleSuppressed, boolean activeBeforeTrigger,
                                     boolean clock2Mhz, boolean lowFrequencyPhase,
                                     Memento<FrequencySweep> frequencySweepMemento,
                                     int justReloadedTicks, boolean justReloadedFromSweep,
                                     Memento<VolumeEnvelope> volumeEnvelopeMemento) implements Memento<AbstractSoundMode> {
    }
}
