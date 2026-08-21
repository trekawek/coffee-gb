package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.timer.Timer;

public class SoundMode3 extends AbstractSoundMode {

    private static final int[] VOLUME_SHIFTS = {4, 0, 1, 2};

    private static final int[] DMG_WAVE =
            new int[]{
                    0x84, 0x40, 0x43, 0xaa, 0x2d, 0x78, 0x92, 0x3c,
                    0x60, 0x59, 0x59, 0xb0, 0x34, 0xb8, 0x2e, 0xda
            };

    private static final int[] CGB_WAVE =
            new int[]{
                    0x00, 0xff, 0x00, 0xff, 0x00, 0xff, 0x00, 0xff,
                    0x00, 0xff, 0x00, 0xff, 0x00, 0xff, 0x00, 0xff
            };

    private final Ram waveRam = new Ram(0xff30, 0x10);

    private final int[] waveRamData = waveRam.getSpace();

    byte[] copyDebugWaveRam() {
        int[] source = waveRam.getSpace();
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (byte) source[i];
        }
        return result;
    }

    private final Timer timer;

    // counts 2 MHz APU cycles: the CH3 frequency counter is clocked by the 2 MHz APU
    // clock, so the sample fetches are quantized to that lattice
    private int freqDivider;

    private int frequencyPeriod = 2048;

    private int volumeShift = VOLUME_SHIFTS[0];

    private int lastOutput;

    private int i;

    private int ticksSinceRead = 65536;

    private int lastReadAddr;

    private int buffer;

    private boolean triggered;

    // CH3 is clocked by the APU's fixed 2 MHz clock, independently of CPU speed.
    private boolean clock2Mhz;

    public SoundMode3(FrameSequencer frameSequencer, Timer timer, boolean gbc) {
        super(0xff1a, 256, frameSequencer, gbc);
        this.timer = timer;
        for (int i = 0; i < 16; i++) {
            waveRam.setByte(0xff30 + i, gbc ? CGB_WAVE[i] : DMG_WAVE[i]);
        }
    }

    @Override
    public boolean accepts(int address) {
        return waveRam.accepts(address) || super.accepts(address);
    }

    @Override
    public int getByte(int address) {
        if (!waveRam.accepts(address)) {
            return super.getByte(address);
        }
        if (!isEnabled()) {
            return waveRam.getByte(address);
        } else if (waveRam.accepts(lastReadAddr) && (gbc || ticksSinceRead < 2)) {
            return waveRam.getByte(lastReadAddr);
        } else {
            return 0xff;
        }
    }

    @Override
    public void setByte(int address, int value) {
        if (!waveRam.accepts(address)) {
            super.setByte(address, value);
            return;
        }
        if (!isEnabled()) {
            waveRam.setByte(address, value);
        } else if (waveRam.accepts(lastReadAddr) && (gbc || ticksSinceRead < 2)) {
            waveRam.setByte(lastReadAddr, value);
        }
    }

    /** Whether a write reaches storage rather than being blocked by the active wave-RAM gate. */
    boolean isWriteAccepted(int address) {
        return !waveRam.accepts(address)
                || !isEnabled()
                || (waveRam.accepts(lastReadAddr) && (gbc || ticksSinceRead < 2));
    }

    @Override
    protected void setNr0(int value) {
        super.setNr0(value);
        if (!dacEnabled && (value & (1 << 7)) != 0) {
            // re-enabling the DAC rewinds the wave position but must leave the length
            // counter alone (cgb_sound 03-trigger #11)
            i = 0;
        }
        dacEnabled = (value & (1 << 7)) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        length.setLength(256 - (value & 0xff));
    }

    @Override
    protected void setNr2(int value) {
        super.setNr2(value);
        volumeShift = VOLUME_SHIFTS[(value >> 5) & 0b11];
        if (channelEnabled) {
            lastOutput = getBufferedOutput();
        }
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
        updateFrequencyPeriod();
    }

    @Override
    public void setNr4(int value) {
        // trigger() runs from super.setNr4(), so cache the period with the newly written
        // high frequency bits before delegating.
        frequencyPeriod = 2048 - (nr3 | ((value & 0b111) << 8));
        if (!gbc && (value & (1 << 7)) != 0) {
            // retriggering the channel while it is about to fetch a sample corrupts the
            // first bytes of the wave RAM
            if (isEnabled() && freqDivider <= 1) {
                int pos = ((i + 1) & 31) >> 1;
                if (pos < 4) {
                    waveRamData[0] = waveRamData[pos];
                } else {
                    pos = pos & ~3;
                    for (int j = 0; j < 4; j++) {
                        waveRamData[j] = waveRamData[(pos + j) & 15];
                    }
                }
            }
        }
        super.setNr4(value);
    }

    @Override
    public void start() {
        i = 0;
        clock2Mhz = false;
        if (gbc) {
            length.reset();
        }
    }

    @Override
    public void stop() {
        super.stop();
        updateDerivedRegisters();
        i = 0;
        lastOutput = 0;
        buffer = 0;
        triggered = false;
    }

    @Override
    protected int getFullLength() {
        return 256;
    }

    @Override
    public void trigger() {
        i = 0;
        // CH3_RESTART asynchronously resets the wave nibble selector.  The sample
        // buffer itself remains stale, but its high nibble is immediately routed
        // through the current NR32 volume shifter.
        lastOutput = applyVolume((buffer >> 4) & 0x0f);
        // the first wave position advance is delayed by 3 extra APU cycles and does not
        // fetch a sample; the stale buffer keeps playing until the second advance
        freqDivider = frequencyPeriod + 3;
        triggered = !gbc;
        if (gbc) {
            // CGB wave-RAM access is redirected to the current byte immediately,
            // but the sample buffer itself is not refreshed by a trigger.
            lastReadAddr = 0xff30;
            ticksSinceRead = 0;
        }
    }

    @Override
    public int tick() {
        ticksSinceRead++;
        clock2Mhz = !clock2Mhz;
        if (!channelEnabled) {
            return 0;
        }
        if (clock2Mhz && --freqDivider == 0) {
            resetFreqDivider();
            i = (i + 1) & 31;
            int stale = applyVolume((buffer >> 4) & 0x0f);
            int out = getWaveEntry();
            // the first advance after the trigger fetches the sample (opening the CPU
            // access window), but the stale buffer value is what gets played
            lastOutput = triggered ? stale : out;
            triggered = false;
        }
        return lastOutput;
    }

    /** Advances a short PERFORMANCE quiet span on the fixed 2-MHz wave lattice. */
    int tickPerformanceSpan(int ticks) {
        if (ticks <= 0) {
            return getCurrentOutput();
        }
        int firstEdgePosition = clock2Mhz ? 2 : 1;
        int edgeCount = clock2Mhz ? ticks / 2 : (ticks + 1) / 2;
        int lastReadPosition = 0;
        ticksSinceRead += ticks;
        if (channelEnabled && freqDivider > edgeCount) {
            freqDivider -= edgeCount;
        } else if (channelEnabled) {
            for (int edge = 0; edge < edgeCount; edge++) {
                if (--freqDivider == 0) {
                    resetFreqDivider();
                    i = (i + 1) & 31;
                    int stale = applyVolume((buffer >> 4) & 0x0f);
                    int out = getWaveEntry();
                    // getWaveEntry() resets ticksSinceRead at the edge; remember its timestamp so
                    // the ticks after the final edge are restored below.
                    lastReadPosition = firstEdgePosition + edge * 2;
                    lastOutput = triggered ? stale : out;
                    triggered = false;
                }
            }
        }
        clock2Mhz = (ticks & 1) != 0 ? !clock2Mhz : clock2Mhz;
        if (lastReadPosition != 0) {
            ticksSinceRead = ticks - lastReadPosition;
        }
        return getCurrentOutput();
    }

    @Override
    public int getCurrentOutput() {
        return channelEnabled ? lastOutput : 0;
    }

    private int getWaveEntry() {
        ticksSinceRead = 0;
        int waveRamIndex = i >> 1;
        lastReadAddr = 0xff30 + waveRamIndex;
        buffer = waveRamData[waveRamIndex];
        return getBufferedOutput();
    }

    private int getBufferedOutput() {
        int b = buffer;
        if ((i & 1) == 0) {
            b = (b >> 4) & 0x0f;
        } else {
            b = b & 0x0f;
        }
        return applyVolume(b);
    }

    private int applyVolume(int sample) {
        return sample >> volumeShift;
    }

    private void resetFreqDivider() {
        freqDivider = frequencyPeriod;
    }

    private void updateFrequencyPeriod() {
        frequencyPeriod = 2048 - (nr3 | ((nr4 & 0b111) << 8));
    }

    private void updateDerivedRegisters() {
        updateFrequencyPeriod();
        volumeShift = VOLUME_SHIFTS[(nr2 >> 5) & 0b11];
    }

    @Override
    public ComponentState<AbstractSoundMode> captureState() {
        return new SoundMode3State(super.captureState(), waveRam.captureState(), freqDivider, lastOutput, i, ticksSinceRead, lastReadAddr, buffer, triggered, clock2Mhz);
    }

    @Override
    public ComponentState<AbstractSoundMode> captureState(MachineStateCapture capture) {
        return new SoundMode3State(
                super.captureState(),
                waveRam.captureState(capture),
                freqDivider,
                lastOutput,
                i,
                ticksSinceRead,
                lastReadAddr,
                buffer,
                triggered,
                clock2Mhz);
    }

    @Override
    public void restoreState(ComponentState<AbstractSoundMode> state) {
        if (!(state instanceof SoundMode3State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        super.restoreState(mem.abstractSoundMemento);
        updateDerivedRegisters();
        this.waveRam.restoreState(mem.waveRamMemento);
        this.freqDivider = mem.freqDivider;
        this.lastOutput = mem.lastOutput;
        this.i = mem.i;
        this.ticksSinceRead = mem.ticksSinceRead;
        this.lastReadAddr = mem.lastReadAddr;
        this.buffer = mem.buffer;
        this.triggered = mem.triggered;
        this.clock2Mhz = mem.clock2Mhz;
    }

    private record SoundMode3State(ComponentState<AbstractSoundMode> abstractSoundMemento, ComponentState<Ram> waveRamMemento,
                                     int freqDivider, int lastOutput, int i, int ticksSinceRead, int lastReadAddr,
                                     int buffer, boolean triggered,
                                     boolean clock2Mhz) implements ComponentState<AbstractSoundMode> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SoundMode3Memento(Memento<AbstractSoundMode> abstractSoundMemento, Memento<Ram> waveRamMemento,
                                     int freqDivider, int lastOutput, int i, int ticksSinceRead, int lastReadAddr,
                                     int buffer, boolean triggered,
                                     boolean clock2Mhz) implements Memento<AbstractSoundMode> {
    }
}
