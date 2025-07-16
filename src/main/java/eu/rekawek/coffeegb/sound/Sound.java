package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.Ram;

import java.io.Serializable;

public class Sound implements AddressSpace, Serializable, Originator<Sound> {

    private static final int[] MASKS =
            new int[]{
                    0x80, 0x3f, 0x00, 0xff, 0xbf, 0xff, 0x3f, 0x00, 0xff, 0xbf, 0x7f, 0xff, 0x9f, 0xff, 0xbf,
                    0xff, 0xff, 0x00, 0x00, 0xbf, 0x00, 0x00, 0x70, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                    0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00
            };

    private final AbstractSoundMode[] allModes = new AbstractSoundMode[4];

    private final Ram r = new Ram(0xff24, 0x03);

    private final int[] channels = new int[4];

    private boolean enabled;

    private final boolean[] overriddenEnabled = {true, true, true, true};

    private transient EventBus eventBus;

    public Sound(boolean gbc) {
        allModes[0] = new SoundMode1(gbc);
        allModes[1] = new SoundMode2(gbc);
        allModes[2] = new SoundMode3(gbc);
        allModes[3] = new SoundMode4(gbc);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void tick() {
        if (!enabled) {
            eventBus.post(SoundSampleEvent.createEvent((byte) 0, (byte) 0));
            return;
        }
        for (int i = 0; i < allModes.length; i++) {
            channels[i] = allModes[i].tick();
        }

        int selection = r.getByte(0xff25);
        int left = 0;
        int right = 0;
        for (int i = 0; i < 4; i++) {
            if (!overriddenEnabled[i]) {
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

        eventBus.post(SoundSampleEvent.createEvent((byte) left, (byte) right));
    }

    private AddressSpace getAddressSpace(int address) {
        if (r.accepts(address)) {
            return r;
        }
        for (AbstractSoundMode m : allModes) {
            if (m.accepts(address)) {
                return m;
            }
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
    }

    private void stop() {
        for (AbstractSoundMode s : allModes) {
            s.stop();
        }
    }

    public void enableChannel(int i, boolean enabled) {
        overriddenEnabled[i] = enabled;
    }

    @Override
    public Memento<Sound> saveToMemento() {
        var allModeMementos = new Memento[allModes.length];
        for (int i = 0; i < allModes.length; i++) {
            allModeMementos[i] = allModes[i].saveToMemento();
        }
        return new SoundMemento(allModeMementos, r.saveToMemento(), channels.clone(), enabled, overriddenEnabled.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Sound> memento) {
        if (!(memento instanceof SoundMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.allModes.length != mem.allModeMementos.length) {
            throw new IllegalArgumentException("Memento allModes length doesn't match");
        }
        if (this.channels.length != mem.channels.length) {
            throw new IllegalArgumentException("Memento channels length doesn't match");
        }
        if (this.overriddenEnabled.length != mem.overriddenEnabled.length) {
            throw new IllegalArgumentException("Memento overriddenEnabled length doesn't match");
        }
        for (int i = 0; i < allModes.length; i++) {
            this.allModes[i].restoreFromMemento(mem.allModeMementos[i]);
        }
        this.r.restoreFromMemento(mem.ramMemento());
        System.arraycopy(mem.channels, 0, this.channels, 0, this.channels.length);
        this.enabled = mem.enabled();
        System.arraycopy(mem.overriddenEnabled, 0, this.overriddenEnabled, 0, this.overriddenEnabled.length);
    }

    public record SoundSampleEvent(byte left, byte right) implements Event {
        private static final SoundSampleEvent[][] SAMPLES = new SoundSampleEvent[0x80][];

        static {
            for (int i = 0; i < 0x80; i++) {
                SAMPLES[i] = new SoundSampleEvent[0x80];
                for (int j = 0; j < 0x80; j++) {
                    SAMPLES[i][j] = new SoundSampleEvent((byte) i, (byte) j);
                }
            }
        }

        private static SoundSampleEvent createEvent(byte left, byte right) {
            return SAMPLES[left][right];
        }
    }

    public record SoundEnabledEvent(boolean enabled) implements Event {
    }

    private record SoundMemento(Memento<AbstractSoundMode>[] allModeMementos, Memento<Ram> ramMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled) implements Memento<Sound> {
    }
}
