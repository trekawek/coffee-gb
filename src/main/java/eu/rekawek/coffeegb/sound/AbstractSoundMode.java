package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;

public abstract class AbstractSoundMode implements AddressSpace {

    protected final int offset;

    protected final boolean gbc;

    protected boolean channelEnabled;

    protected boolean dacEnabled;

    protected int nr0, nr1, nr2, nr3, nr4;

    protected LengthCounter length;

    public AbstractSoundMode(int offset, int length, boolean gbc) {
        this.offset = offset;
        this.length = new LengthCounter(length);
        this.gbc = gbc;
    }

    public abstract int tick();

    protected abstract void trigger();

    public boolean isEnabled() {
        return channelEnabled && dacEnabled;
    }

    @Override
    public boolean accepts(int address) {
        return address >= offset && address < offset + 5;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address - offset) {
            case 0:
                setNr0(value);
                break;

            case 1:
                setNr1(value);
                break;

            case 2:
                setNr2(value);
                break;

            case 3:
                setNr3(value);
                break;

            case 4:
                setNr4(value);
                break;
        }
    }

    @Override
    public int getByte(int address) {
        switch (address - offset) {
            case 0:
                return getNr0();

            case 1:
                return getNr1();

            case 2:
                return getNr2();

            case 3:
                return getNr3();

            case 4:
                return getNr4();

            default:
                throw new IllegalArgumentException("Illegal address for sound mode: " + Integer.toHexString(address));
        }
    }

    protected void setNr0(int value) {
        nr0 = value;
    }

    protected void setNr1(int value) {
        nr1 = value;
    }

    protected void setNr2(int value) {
        nr2 = value;
    }

    protected void setNr3(int value) {
        nr3 = value;
    }

    protected void setNr4(int value) {
        nr4 = value;
        length.setNr4(value);
        if ((value & (1 << 7)) != 0) {
            channelEnabled = dacEnabled;
            trigger();
        }
    }

    protected int getNr0() {
        return nr0;
    }

    protected int getNr1() {
        return nr1;
    }

    protected int getNr2() {
        return nr2;
    }

    protected int getNr3() {
        return nr3;
    }

    protected int getNr4() {
        return nr4;
    }

    protected int getFrequency() {
        return 2048 - (getNr3() | ((getNr4() & 0b111) << 8));
    }

    public abstract void start();

    public void stop() {
        channelEnabled = false;

    }

    protected boolean updateLength() {
        length.tick();
        if (!length.isEnabled()) {
            return channelEnabled;
        }
        if (channelEnabled && length.getValue() == 0) {
            channelEnabled = false;
        }
        return channelEnabled;
    }
}
