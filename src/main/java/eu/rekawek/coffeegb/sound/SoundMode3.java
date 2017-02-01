package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.memory.Ram;

public class SoundMode3 extends AbstractSoundMode {

    private final Ram waveRam = new Ram(0xff30, 0x10);

    private int freqDivider;

    private int lastOutput;

    private int i;

    private int ticksSinceRead = 65536;

    private int lastReadAddr;

    private int buffer;

    private boolean triggered;

    public SoundMode3() {
        super(0xff1a, 256);
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
        } else if (ticksSinceRead < 1) {
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
        } else if (ticksSinceRead < 1) {
            waveRam.setByte(lastReadAddr, value);
        }
    }

    @Override
    protected void setNr3(int value) {
        super.setNr3(value);
    }

    @Override
    public void setNr4(int value) {
        if ((value & (1 << 7)) != 0) {
            if (isEnabled() && ticksSinceRead < 1) {
                int pos = ((i + 1) & 0x1f) / 2;
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
        length.start();
    }

    @Override
    public void trigger() {
        i = 0;
        resetFreqDivider();
        triggered = true;
    }

    @Override
    public int tick() {
        ticksSinceRead++;
        if (!updateLength()) {
            return 0;
        }
        if (!dacEnabled) {
            return 0;
        }

        if ((getNr0() & (1 << 7)) == 0) {
            return 0;
        }

        if (--freqDivider == 0) {
            resetFreqDivider();
            if (triggered) {
                lastOutput = buffer & 0xf0;
                triggered = false;
            } else {
                lastOutput = getWaveEntry();
            }
            i = (i + 1) % 32;
        }
        return lastOutput;
    }

    @Override
    protected void setNr0(int value) {
        super.setNr0(value);
        dacEnabled = (value & (1 << 7)) != 0;
        channelEnabled &= dacEnabled;
    }

    @Override
    protected void setNr1(int value) {
        super.setNr1(value);
        length.setLength(256 - value);
    }

    private int getVolume() {
        return (getNr2() >> 5) & 0b11;
    }

    private int getWaveEntry() {
        ticksSinceRead = 0;
        lastReadAddr = 0xff30 + i / 2;
        buffer = waveRam.getByte(lastReadAddr);
        int b = buffer;
        if (i % 2 == 0) {
            b = (b >> 4) & 0x0f;
        } else {
            b = b & 0x0f;
        }
        switch (getVolume()) {
            case 0:
                return 0;
            case 1:
                return b;
            case 2:
                return b >> 1;
            case 3:
                return b >> 2;
            default:
                throw new IllegalStateException();
        }
    }

    private void resetFreqDivider() {
        freqDivider = getFrequency() * 2;
    }
}
