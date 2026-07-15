package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.timer.Timer;

public class SoundMode3 extends AbstractSoundMode {

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

    private final Timer timer;

    // counts 2 MHz APU cycles: the CH3 frequency counter is clocked by the 2 MHz APU
    // clock, so the sample fetches are quantized to that lattice
    private int freqDivider;

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
        if (channelEnabled) {
            lastOutput = getBufferedOutput();
        }
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
    }

    @Override
    public void setNr4(int value) {
        if (!gbc && (value & (1 << 7)) != 0) {
            // retriggering the channel while it is about to fetch a sample corrupts the
            // first bytes of the wave RAM
            if (isEnabled() && freqDivider <= 1) {
                int pos = ((i + 1) % 32) / 2;
                if (pos < 4) {
                    waveRam.setByte(0xff30, waveRam.getByte(0xff30 + pos));
                } else {
                    pos = pos & ~3;
                    for (int j = 0; j < 4; j++) {
                        waveRam.setByte(0xff30 + j, waveRam.getByte(0xff30 + ((pos + j) % 0x10)));
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
        // the first wave position advance is delayed by 3 extra APU cycles and does not
        // fetch a sample; the stale buffer keeps playing until the second advance
        freqDivider = getFrequency() + 3;
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
            return lastOutput;
        }
        if (!updateLength()) {
            return 0;
        }

        if (clock2Mhz && --freqDivider == 0) {
            resetFreqDivider();
            i = (i + 1) % 32;
            int stale = applyVolume((buffer >> 4) & 0x0f);
            int out = getWaveEntry();
            // the first advance after the trigger fetches the sample (opening the CPU
            // access window), but the stale buffer value is what gets played
            lastOutput = triggered ? stale : out;
            triggered = false;
        }
        return lastOutput;
    }

    @Override
    public int getCurrentOutput() {
        return lastOutput;
    }

    private int getVolume() {
        return (getNr2() >> 5) & 0b11;
    }

    private int getWaveEntry() {
        ticksSinceRead = 0;
        lastReadAddr = 0xff30 + i / 2;
        buffer = waveRam.getByte(lastReadAddr);
        return getBufferedOutput();
    }

    private int getBufferedOutput() {
        int b = buffer;
        if (i % 2 == 0) {
            b = (b >> 4) & 0x0f;
        } else {
            b = b & 0x0f;
        }
        return applyVolume(b);
    }

    private int applyVolume(int sample) {
        switch (getVolume()) {
            case 0:
                return 0;
            case 1:
                return sample;
            case 2:
                return sample >> 1;
            case 3:
                return sample >> 2;
            default:
                throw new IllegalStateException();
        }
    }

    private void resetFreqDivider() {
        freqDivider = getFrequency();
    }

    @Override
    public Memento<AbstractSoundMode> saveToMemento() {
        return new SoundMode3Memento(super.saveToMemento(), waveRam.saveToMemento(), freqDivider, lastOutput, i, ticksSinceRead, lastReadAddr, buffer, triggered, clock2Mhz);
    }

    @Override
    public void restoreFromMemento(Memento<AbstractSoundMode> memento) {
        if (!(memento instanceof SoundMode3Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        super.restoreFromMemento(mem.abstractSoundMemento);
        this.waveRam.restoreFromMemento(mem.waveRamMemento);
        this.freqDivider = mem.freqDivider;
        this.lastOutput = mem.lastOutput;
        this.i = mem.i;
        this.ticksSinceRead = mem.ticksSinceRead;
        this.lastReadAddr = mem.lastReadAddr;
        this.buffer = mem.buffer;
        this.triggered = mem.triggered;
        this.clock2Mhz = mem.clock2Mhz;
    }

    private record SoundMode3Memento(Memento<AbstractSoundMode> abstractSoundMemento, Memento<Ram> waveRamMemento,
                                     int freqDivider, int lastOutput, int i, int ticksSinceRead, int lastReadAddr,
                                     int buffer, boolean triggered,
                                     boolean clock2Mhz) implements Memento<AbstractSoundMode> {
    }
}
