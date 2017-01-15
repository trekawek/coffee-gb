package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memory.Ram;

public class Sound implements AddressSpace {

    private final AbstractSoundMode[] allModes = new AbstractSoundMode[4];

    private final Ram waveRam = new Ram(0xff30, 0x10);

    private final Ram r = new Ram(0xff24, 0x03);

    private final SoundOutput output;

    public Sound(SoundOutput output) {
        allModes[0] = new SoundMode1_2(1);
        allModes[1] = new SoundMode1_2(2);
        allModes[2] = new SoundMode3(waveRam);
        allModes[3] = new SoundMode4();
        this.output = output;
    }

    public void tick() {
        if (!isEnabled()) {
            return;
        }
        int[] sound = new int[4];
        for (int i = 0; i < allModes.length; i++) {
            AbstractSoundMode m = allModes[i];
            if (m.isEnabled()) {
                sound[i] = m.tick();
            }
        }

        int selection = r.getByte(0xff25);
        int left = 0;
        int right = 0;
        int leftCount = 0;
        int rightCount = 0;
        for (int i = 0; i < 4; i++) {
            if ((selection & (1 << i)) != 0) {
                left += sound[i];
                leftCount++;
            }
            if ((selection & (1 << i + 4)) != 0) {
                right += sound[i];
                rightCount++;
            }
        }
        left /= leftCount;
        right /= rightCount;

        int volumes = r.getByte(0xff24);
        left *= (volumes & 0b111) + 1;
        right *= ((volumes >> 4) & 0b111) + 1;

        output.play((byte) left, (byte) right);
    }

    private boolean isEnabled() {
        return (r.getByte(0xff26) & (1 << 7)) != 0;
    }

    private AddressSpace getAddressSpace(int address) {
        for (AbstractSoundMode m : allModes) {
            if (m.accepts(address)) {
                return m;
            }
        }
        if (waveRam.accepts(address)) {
            return waveRam;
        }
        if (r.accepts(address)) {
            return r;
        }
        return null;
    }

    @Override
    public boolean accepts(int address) {
        return getAddressSpace(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff26) {
            if ((value & (1 << 7)) == 0) {
                if (isEnabled()) {
                    output.stop();
                }
            } else {
                if (!isEnabled()) {
                    output.start();
                }
            }
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            throw new IllegalArgumentException();
        }
        s.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff26) {
            int result = r.getByte(0xff26) & 0b11110000;
            for (int i = 0; i < allModes.length; i++) {
                result |= allModes[i].isEnabled() ? (1 << i) : 0;
            }
            return result;
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            throw new IllegalArgumentException();
        }
        return s.getByte(address);
    }
}
