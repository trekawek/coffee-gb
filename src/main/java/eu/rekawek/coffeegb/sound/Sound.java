package eu.rekawek.coffeegb.sound;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
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

    private boolean enabled = true;

    private final boolean[] overriddenEnabled = {true, true, true, true};

    private final int[] buffer = new int[Gameboy.TICKS_PER_FRAME * 2];

    private int i = 0;

    private transient EventBus eventBus;

    public Sound(boolean gbc) {
        allModes[0] = new SoundMode1(gbc);
        allModes[1] = new SoundMode2(gbc);
        allModes[2] = new SoundMode3(gbc);
        allModes[3] = new SoundMode4(gbc);
        // Initial volume
        r.setByte(0xFF24, 0x77);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void tick() {
        if (!enabled) {
            play(0, 0);
        }

        channels[0] = allModes[0].tick();
        channels[1] = allModes[1].tick();
        channels[2] = allModes[2].tick();
        channels[3] = allModes[3].tick();

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

        play(left, right);
    }

    private void play(int left, int right) {
        buffer[i] = left;
        buffer[i + 1] = right;
        i += 2;
        if (i == buffer.length) {
            eventBus.post(new SoundSampleEvent(buffer));
            i = 0;
        }
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
        //LOG.atInfo().log("setByte({}, {})", Integer.toHexString(address), Integer.toHexString(value));
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
        return new SoundMemento(allModeMementos, r.saveToMemento(), channels.clone(), enabled, overriddenEnabled.clone(), buffer.clone(), i);
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
        if (this.buffer.length != mem.buffer.length) {
            throw new IllegalArgumentException("Memento buffer length doesn't match");
        }
        for (int i = 0; i < allModes.length; i++) {
            this.allModes[i].restoreFromMemento(mem.allModeMementos[i]);
        }
        this.r.restoreFromMemento(mem.ramMemento());
        System.arraycopy(mem.channels, 0, this.channels, 0, this.channels.length);
        this.enabled = mem.enabled();
        System.arraycopy(mem.overriddenEnabled, 0, this.overriddenEnabled, 0, this.overriddenEnabled.length);
        System.arraycopy(mem.buffer, 0, this.buffer, 0, this.buffer.length);
        this.i = mem.i;

    }

    public record SoundSampleEvent(int[] buffer) implements Event {
    }

    public record SoundEnabledEvent(boolean enabled) implements Event {
    }

    private record SoundMemento(Memento<AbstractSoundMode>[] allModeMementos, Memento<Ram> ramMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i) implements Memento<Sound> {
    }
}
