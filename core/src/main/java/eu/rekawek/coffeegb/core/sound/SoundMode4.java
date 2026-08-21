package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;

public class SoundMode4 extends AbstractSoundMode {

    private final VolumeEnvelope volumeEnvelope;

    private final PolynomialCounter polynomialCounter;

    private int lastResult;

    private final Lfsr lfsr = new Lfsr();

    public SoundMode4(FrameSequencer frameSequencer, boolean gbc) {
        super(0xff1f, 64, frameSequencer, gbc);
        this.volumeEnvelope = new VolumeEnvelope();
        this.polynomialCounter = new PolynomialCounter();
    }

    @Override
    public void start() {
        if (gbc) {
            length.reset();
        }
        lfsr.start();
        polynomialCounter.start();
        volumeEnvelope.start();
    }

    @Override
    public void stop() {
        super.stop();
        lastResult = 0;
        volumeEnvelope.setNr2(0);
        polynomialCounter.stop();
    }

    @Override
    public void trigger() {
        lfsr.reset();
        lastResult = 0;
        polynomialCounter.trigger();
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
        boolean stepLfsr = polynomialCounter.tick();
        if (!channelEnabled) {
            return 0;
        }
        if (!dacEnabled) {
            return 0;
        }

        if (stepLfsr) {
            lastResult = lfsr.nextBit((nr3 & (1 << 3)) != 0);
        }
        return getCurrentOutput();
    }

    /** Advances a short PERFORMANCE quiet span and applies any resulting LFSR edges. */
    int tickPerformanceSpan(int ticks) {
        if (ticks <= 0) {
            return getCurrentOutput();
        }
        int steps = polynomialCounter.advancePerformanceSpan(ticks);
        if (channelEnabled && dacEnabled) {
            boolean widthMode7 = (nr3 & (1 << 3)) != 0;
            for (int i = 0; i < steps; i++) {
                lastResult = lfsr.nextBit(widthMode7);
            }
        }
        return getCurrentOutput();
    }

    @Override
    public int getCurrentOutput() {
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
        volumeEnvelope.setNr2(value, channelEnabled);
        dacEnabled = (value & 0b11111000) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        polynomialCounter.setNr43(value);
    }

    @Override
    public ComponentState<AbstractSoundMode> captureState() {
        return new SoundMode4State(super.captureState(), volumeEnvelope.captureState(), polynomialCounter.captureState(), lastResult, lfsr.captureState());
    }

    @Override
    public void restoreState(ComponentState<AbstractSoundMode> state) {
        if (!(state instanceof SoundMode4State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        super.restoreState(mem.abstractSoundMemento);
        this.volumeEnvelope.restoreState(mem.volumeEnvelopeMemento);
        this.polynomialCounter.restoreState(mem.polynomialCounterMemento);
        this.lastResult = mem.lastResult;
        this.lfsr.restoreState(mem.lfsrMemento);
    }

    private record SoundMode4State(ComponentState<AbstractSoundMode> abstractSoundMemento,
                                     ComponentState<VolumeEnvelope> volumeEnvelopeMemento,
                                     ComponentState<PolynomialCounter> polynomialCounterMemento, int lastResult,
                                     ComponentState<Lfsr> lfsrMemento) implements ComponentState<AbstractSoundMode> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SoundMode4Memento(Memento<AbstractSoundMode> abstractSoundMemento,
                                     Memento<VolumeEnvelope> volumeEnvelopeMemento,
                                     Memento<PolynomialCounter> polynomialCounterMemento, int lastResult,
                                     Memento<Lfsr> lfsrMemento) implements Memento<AbstractSoundMode> {
    }
}
