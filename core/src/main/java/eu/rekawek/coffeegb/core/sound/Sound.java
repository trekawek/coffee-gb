package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.io.Serializable;
import java.util.Arrays;

public class Sound implements AddressSpace, Serializable, Originator<Sound> {

    private static final int CGB_BOOT_DIV_APU_OFFSET = 2;

    private static final boolean[] ENABLED = {true, true, true, true};

    private static final int[] MASKS =
            new int[]{
                    0x80, 0x3f, 0x00, 0xff, 0xbf, 0xff, 0x3f, 0x00, 0xff, 0xbf, 0x7f, 0xff, 0x9f, 0xff, 0xbf,
                    0xff, 0xff, 0x00, 0x00, 0xbf, 0x00, 0x00, 0x70, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
                    0xff, 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00
            };

    private final FrameSequencer frameSequencer = new FrameSequencer();

    private final AbstractSoundMode[] allModes = new AbstractSoundMode[4];

    private final Ram r = new Ram(0xff24, 0x03);

    private final int[] channels = new int[4];

    private boolean enabled = true;

    private final boolean[] overriddenEnabled = {true, true, true, true};

    private final int[] buffer = new int[Gameboy.TICKS_PER_FRAME * 2];

    private int i = 0;

    private final Timer timer;

    private final boolean gbc;

    private transient EventBus eventBus = EventBus.NULL_EVENT_BUS;

    private final eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode;

    private int pendingFrameSequencerStep = -1;

    /**
     * Position of the APU clock relative to the CPU clock mux. Gambatte's PSG keeps
     * this as the low two bits of its last-update timestamp: each speed switch moves
     * it by one sub-clock, and phase 1 makes a natural DIV-APU clock visible after the
     * CPU bus access in the same master tick.
     */
    private int frameSequencerClockPhase;

    /**
     * The later-revision CGB boot starts the CPU divider ten clocks into its period,
     * while the PSG tap starts at absolute phase twelve. Their relative offset is
     * therefore two clocks. A write to DIV resets both domains and removes it.
     */
    private int frameSequencerDivOffset;

    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc) {
        this.timer = timer;
        this.speedMode = speedMode;
        this.gbc = gbc;
        frameSequencerDivOffset = gbc ? CGB_BOOT_DIV_APU_OFFSET : 0;
        allModes[0] = new SoundMode1(frameSequencer, gbc);
        allModes[1] = new SoundMode2(frameSequencer, gbc);
        allModes[2] = new SoundMode3(frameSequencer, timer, gbc);
        allModes[3] = new SoundMode4(frameSequencer, gbc);
        // Initial volume
        r.setByte(0xFF24, 0x77);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void tick() {
        boolean divReset = timer.consumeDivReset();
        if (!enabled) {
            play(0, 0);
            return;
        }

        channels[0] = allModes[0].tick(divReset);
        channels[1] = allModes[1].tick(divReset);
        channels[2] = allModes[2].tick(divReset);
        channels[3] = allModes[3].tick(divReset);

        int selection = r.getByte(0xff25);
        int left = 0;
        int right = 0;
        for (int i = 0; i < 4; i++) {
            if (!overriddenEnabled[i] || !ENABLED[i]) {
                continue;
            }
            // the DAC maps the digital 0-15 to analog +15..-15 (0 = the highest level);
            // a DAC that is enabled while its channel is inactive therefore outputs a
            // constant positive offset. Games play PCM speech by parking such a DC and
            // modulating the master volume (Perfect Dark's intro voice, issue #56); a
            // disabled DAC outputs true analog zero.
            int analog = allModes[i].isDacEnabled() ? 15 - 2 * channels[i] : 0;
            if ((selection & (1 << i + 4)) != 0) {
                left += analog;
            }
            if ((selection & (1 << i)) != 0) {
                right += analog;
            }
        }

        // NR50 volume 0 means "very quiet", not silence: the scale factor is volume+1
        int volumes = r.getByte(0xff24);
        left *= ((volumes >> 4) & 0b111) + 1;
        right *= (volumes & 0b111) + 1;

        play(left, right);
    }

    /**
     * Updates DIV-APU independently of channel sampling. It is called both before the
     * CPU (for natural DIV edges) and after it (for an edge caused by an FF04 write).
     */
    public void tickFrameSequencer() {
        int divCounter = (timer.getDivCounter() + frameSequencerDivOffset) & 0xffff;
        int firedStep = frameSequencer.tick(divCounter, enabled, speedMode.getSpeedMode() == 2);
        if (firedStep >= 0) {
            pendingFrameSequencerStep = firedStep;
        }
        if (timer.isDivResetPending()) {
            frameSequencerDivOffset = 0;
        }
    }

    /**
     * Commits clocks selected by the DIV-APU edge sampled earlier in this master tick.
     * The sequencer phase changes before the CPU access, while length/status side effects
     * become visible immediately after it.
     */
    public void commitFrameSequencerClock() {
        int firedStep = pendingFrameSequencerStep;
        pendingFrameSequencerStep = -1;
        if (firedStep < 0) {
            return;
        }
        for (AbstractSoundMode m : allModes) m.tickEnvelopeClock(firedStep);
        if ((firedStep & 1) == 0) {
            for (AbstractSoundMode m : allModes) m.tickLength();
        }
        if (firedStep == 2 || firedStep == 6) {
            for (AbstractSoundMode m : allModes) m.tickSweep();
        }
        if (firedStep == 7) {
            for (AbstractSoundMode m : allModes) m.tickEnvelope();
        }
    }

    public boolean isFrameSequencerClockAfterCpu() {
        return frameSequencerClockPhase == 1;
    }

    public void onSpeedSwitch() {
        frameSequencerClockPhase = (frameSequencerClockPhase + 1) & 3;
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
        if (address == 0xff76 || address == 0xff77) {
            // the PCM12/PCM34 registers only exist on the CGB
            return gbc;
        }
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

        if (!enabled && address < 0xff30) {
            // while the APU is off, the only writable register bits are the DMG length
            // counters (and NR52 handled above); everything else is ignored
            if (!gbc) {
                switch (address) {
                    case 0xff11:
                        allModes[0].writeLengthWhileOff(value);
                        break;
                    case 0xff16:
                        allModes[1].writeLengthWhileOff(value);
                        break;
                    case 0xff1b:
                        allModes[2].writeLengthWhileOff(value);
                        break;
                    case 0xff20:
                        allModes[3].writeLengthWhileOff(value);
                        break;
                }
            }
            return;
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            return;
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
        } else if (address == 0xff76) {
            return (allModes[0].isEnabled() ? allModes[0].getCurrentOutput() : 0)
                    | (allModes[1].isEnabled() ? allModes[1].getCurrentOutput() << 4 : 0);
        } else if (address == 0xff77) {
            return (allModes[2].isEnabled() ? allModes[2].getCurrentOutput() : 0)
                    | (allModes[3].isEnabled() ? allModes[3].getCurrentOutput() << 4 : 0);
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
        // the registers were zeroed at power-off and the length counters keep their values
        for (AbstractSoundMode m : allModes) {
            m.start();
        }
        frameSequencer.reset(timer.getDivCounter(), speedMode.getSpeedMode() == 2);
        // Power-on re-synchronizes the PSG to the selected CPU clock. In double
        // speed, its last-update timestamp starts one sub-clock before phase zero.
        frameSequencerClockPhase = speedMode.getSpeedMode() == 2 ? 3 : 0;
    }

    private void stop() {
        for (AbstractSoundMode s : allModes) {
            s.stop();
        }
        r.setByte(0xff24, 0);
        r.setByte(0xff25, 0);
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
        // Only the prefix before i has been written. The rest is overwritten before the
        // next SoundSampleEvent can expose it, so retaining the full ~546 KiB frame buffer
        // in every rewind state wastes memory and creates a G1 humongous allocation.
        int[] pendingSamples = Arrays.copyOf(buffer, i);
        return new SoundMemento(allModeMementos, r.saveToMemento(),
                frameSequencer.saveToMemento(), channels.clone(), enabled,
                overriddenEnabled.clone(), pendingSamples, i, pendingFrameSequencerStep,
                frameSequencerClockPhase, frameSequencerDivOffset);
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
        if (mem.i < 0 || mem.i >= this.buffer.length || (mem.i & 1) != 0) {
            throw new IllegalArgumentException("Memento buffer position is invalid");
        }
        // New mementos retain only buffer[0..i). Accept the former full-buffer shape as
        // well so save states written by older Coffee GB versions remain loadable.
        if (mem.buffer.length != mem.i && mem.buffer.length != this.buffer.length) {
            throw new IllegalArgumentException("Memento buffer length doesn't match");
        }
        for (int i = 0; i < allModes.length; i++) {
            this.allModes[i].restoreFromMemento(mem.allModeMementos[i]);
        }
        this.r.restoreFromMemento(mem.ramMemento());
        this.frameSequencer.restoreFromMemento(mem.frameSequencerMemento());
        System.arraycopy(mem.channels, 0, this.channels, 0, this.channels.length);
        this.enabled = mem.enabled();
        System.arraycopy(mem.overriddenEnabled, 0, this.overriddenEnabled, 0, this.overriddenEnabled.length);
        System.arraycopy(mem.buffer, 0, this.buffer, 0, mem.i);
        this.i = mem.i;
        this.pendingFrameSequencerStep = mem.pendingFrameSequencerStep;
        this.frameSequencerClockPhase = mem.frameSequencerClockPhase;
        this.frameSequencerDivOffset = mem.frameSequencerDivOffset;

    }

    public record SoundSampleEvent(int[] buffer) implements Event {
    }

    public record SoundEnabledEvent(boolean enabled) implements Event {
    }

    private record SoundMemento(Memento<AbstractSoundMode>[] allModeMementos, Memento<Ram> ramMemento,
                                Memento<FrameSequencer> frameSequencerMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i, int pendingFrameSequencerStep,
                                int frameSequencerClockPhase,
                                int frameSequencerDivOffset) implements Memento<Sound> {
    }
}
