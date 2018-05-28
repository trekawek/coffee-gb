package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.debug.command.apu.Channel;
import eu.rekawek.coffeegb.memory.Ram;

public class Sound implements AddressSpace {

    private static final int[] MASKS = new int[] {
            0x80, 0x3f, 0x00, 0xff, 0xbf,
            0xff, 0x3f, 0x00, 0xff, 0xbf,
            0x7f, 0xff, 0x9f, 0xff, 0xbf,
            0xff, 0xff, 0x00, 0x00, 0xbf,
            0x00, 0x00, 0x70,
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    private final AbstractSoundMode[] allModes = new AbstractSoundMode[4];

    private final Ram r = new Ram(0xff24, 0x03);

    private final SoundOutput output;

    private int[] channels = new int[4];

    private boolean enabled;

    private boolean[] overridenEnabled = {true, true, true, true};

    public Sound(SoundOutput output, boolean gbc) {
        allModes[0] = new SoundMode1(gbc);
        allModes[1] = new SoundMode2(gbc);
        allModes[2] = new SoundMode3(gbc);
        allModes[3] = new SoundMode4(gbc);
        this.output = output;
    }

    public void tick() {
        if (!enabled) {
            return;
        }
        for (int i = 0; i < allModes.length; i++) {
            AbstractSoundMode m = allModes[i];
            channels[i] = m.tick();
        }

        int selection = r.getByte(0xff25);
        int left = 0;
        int right = 0;
        for (int i = 0; i < 4; i++) {
            if (!overridenEnabled[i]) {
                continue;
            }
            if ((selection & (1 << i + 4)) != 0) {
                left += channels[i];
            }
            if ((selection & (1 << i)) != 0) {
                right += channels[i];
            }
        }
        left /= 4;
        right /= 4;

        int volumes = r.getByte(0xff24);
        left *= ((volumes >> 4) & 0b111);
        right *= (volumes & 0b111);

        output.play((byte) left, (byte) right);
    }

    private AddressSpace getAddressSpace(int address) {
        for (AbstractSoundMode m : allModes) {
            if (m.accepts(address)) {
                return m;
            }
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
                if (enabled) {
                    enabled = false;
                    stop();
                }
            } else {
                if (!enabled) {
                    enabled = true;
                    start();
                }
            }
            return;
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            throw new IllegalArgumentException();
        }
        s.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        int result;
        if (address == 0xff26) {
            result = 0;
            for (int i = 0; i < allModes.length; i++) {
                result |= allModes[i].isEnabled() ? (1 << i) : 0;
            }
            result |= enabled ? (1 << 7) : 0;
        } else {
            result = getUnmaskedByte(address);
        }
        return result | MASKS[address - 0xff10];
    }

    private int getUnmaskedByte(int address) {
        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            throw new IllegalArgumentException();
        }
        return s.getByte(address);
    }

    private void start() {
        for (int i = 0xff10; i <= 0xff25; i++) {
            int v = 0;
            // lengths should be preserved
            if (i == 0xff11 || i == 0xff16 || i == 0xff20) { // channel 1, 2, 4 lengths
                v = getUnmaskedByte(i) & 0b00111111;
            } else if (i == 0xff1b) { // channel 3 length
                v = getUnmaskedByte(i);
            }
            setByte(i, v);
        }
        for (AbstractSoundMode m : allModes) {
            m.start();
        }
        output.start();
    }

    private void stop() {
        output.stop();
        for (AbstractSoundMode s : allModes) {
            s.stop();
        }
    }

    public void enableChannel(int i, boolean enabled) {
        overridenEnabled[i] = enabled;
    }
}
