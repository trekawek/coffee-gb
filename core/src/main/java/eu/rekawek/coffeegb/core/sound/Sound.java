package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection;
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection;
import eu.rekawek.coffeegb.core.debug.DebugByteData;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.util.Arrays;

public class Sound implements AddressSpace, StatefulComponent<Sound> {

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

    private final int[] buffer;

    private int i = 0;

    private final Timer timer;

    private final boolean gbc;

    private final ClockSpec clockSpec;

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

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc) {
        this(timer, speedMode, gbc, ClockSpec.LEGACY);
    }

    public Sound(Timer timer, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode, boolean gbc,
                 ClockSpec clockSpec) {
        this.timer = timer;
        this.speedMode = speedMode;
        this.gbc = gbc;
        this.clockSpec = clockSpec;
        this.buffer = new int[Math.multiplyExact(clockSpec.controllerTicksPerFrame(), 2)];
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

        int enabledBefore = getDebugEnabledChannelMask();

        channels[0] = allModes[0].tick(divReset);
        channels[1] = allModes[1].tick(divReset);
        channels[2] = allModes[2].tick(divReset);
        channels[3] = allModes[3].tick(divReset);
        notifyDebugChannelDisables(enabledBefore);

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
        int enabledBefore = getDebugEnabledChannelMask();
        notifyDebugEvent(ApuTrace.Kind.FRAME_SEQUENCER_STEP, -1, -1, firedStep);
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
        notifyDebugChannelDisables(enabledBefore);
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
            eventBus.post(new SoundSampleEvent(buffer, clockSpec));
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
            int enabledBefore = getDebugEnabledChannelMask();
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
            notifyDebugRegisterWrite(-1, address, value);
            notifyDebugChannelDisables(enabledBefore);
            return;
        }

        if (!enabled && address < 0xff30) {
            // while the APU is off, the only writable register bits are the DMG length
            // counters (and NR52 handled above); everything else is ignored
            int channel = -1;
            if (!gbc) {
                switch (address) {
                    case 0xff11:
                        allModes[0].writeLengthWhileOff(value);
                        channel = 1;
                        break;
                    case 0xff16:
                        allModes[1].writeLengthWhileOff(value);
                        channel = 2;
                        break;
                    case 0xff1b:
                        allModes[2].writeLengthWhileOff(value);
                        channel = 3;
                        break;
                    case 0xff20:
                        allModes[3].writeLengthWhileOff(value);
                        channel = 4;
                        break;
                }
            }
            if (channel != -1) {
                notifyDebugRegisterWrite(channel, address, value);
            }
            return;
        }

        AddressSpace s = getAddressSpace(address);
        if (s == null) {
            return;
        }
        boolean accepted = !(s instanceof SoundMode3 mode3)
                || mode3.isWriteAccepted(address);
        int enabledBefore = getDebugEnabledChannelMask();
        s.setByte(address, value);
        if (!accepted) {
            return;
        }
        int channel = getDebugChannel(address);
        notifyDebugRegisterWrite(channel, address, value);
        if (isTriggerRegister(address) && (value & 0x80) != 0) {
            notifyDebugEvent(ApuTrace.Kind.CHANNEL_TRIGGERED, channel, address, value & 0xff);
        }
        notifyDebugChannelDisables(enabledBefore);
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        this.debugHooks = debugHooks;
    }

    private void notifyDebugRegisterWrite(int channel, int address, int value) {
        notifyDebugEvent(ApuTrace.Kind.REGISTER_WRITTEN, channel, address, value & 0xff);
    }

    private void notifyDebugChannelDisables(int enabledBefore) {
        DebugHooks hooks = debugHooks;
        if (hooks == null || enabledBefore == 0) {
            return;
        }
        int disabled = enabledBefore & ~getEnabledChannelMask();
        for (int i = 0; i < allModes.length; i++) {
            if ((disabled & (1 << i)) != 0) {
                hooks.onApuEvent(ApuTrace.Kind.CHANNEL_DISABLED, i + 1, -1, -1);
            }
        }
    }

    private void notifyDebugEvent(ApuTrace.Kind kind, int channel, int register, int value) {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onApuEvent(kind, channel, register, value);
        }
    }

    private int getDebugEnabledChannelMask() {
        return debugHooks == null ? 0 : getEnabledChannelMask();
    }

    private int getEnabledChannelMask() {
        int result = 0;
        for (int i = 0; i < allModes.length; i++) {
            if (allModes[i].isEnabled()) {
                result |= 1 << i;
            }
        }
        return result;
    }

    /** Captures internal APU state without applying CPU register masks or wave-RAM locks. */
    public DebugAudioInspection captureDebugAudioInspection() {
        var debugChannels = new java.util.ArrayList<DebugAudioChannelInspection>(4);
        for (int i = 0; i < allModes.length; i++) {
            AbstractSoundMode mode = allModes[i];
            debugChannels.add(new DebugAudioChannelInspection(
                    i + 1,
                    mode.isEnabled(),
                    mode.isDacEnabled(),
                    mode.isEnabled() ? mode.getCurrentOutput() : 0,
                    mode.length.getValue(),
                    mode.length.isEnabled(),
                    i == 1 || i == 3 ? 0 : mode.getNr0(),
                    mode.getNr1(),
                    mode.getNr2(),
                    mode.getNr3(),
                    mode.getNr4()));
        }
        return new DebugAudioInspection(
                enabled,
                getDebugFrameSequencerStep(),
                r.getByte(0xff24),
                r.getByte(0xff25),
                getByte(0xff26),
                debugChannels,
                new DebugByteData(((SoundMode3) allModes[2]).copyDebugWaveRam()));
    }

    public int getDebugFrameSequencerStep() {
        return frameSequencer.getDebugStep();
    }

    private static boolean isTriggerRegister(int address) {
        return address == 0xff14 || address == 0xff19
                || address == 0xff1e || address == 0xff23;
    }

    private static int getDebugChannel(int address) {
        if (address >= 0xff10 && address <= 0xff14) {
            return 1;
        }
        if (address >= 0xff15 && address <= 0xff19) {
            return 2;
        }
        if ((address >= 0xff1a && address <= 0xff1e)
                || (address >= 0xff30 && address <= 0xff3f)) {
            return 3;
        }
        if (address >= 0xff1f && address <= 0xff23) {
            return 4;
        }
        return -1;
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
    public ComponentState<Sound> captureState() {
        return captureState(null);
    }

    @Override
    public ComponentState<Sound> captureState(MachineStateCapture capture) {
        var allModeMementos = new ComponentState[allModes.length];
        for (int i = 0; i < allModes.length; i++) {
            allModeMementos[i] = capture == null
                    ? allModes[i].captureState()
                    : allModes[i].captureState(capture);
        }
        // Only the prefix before i has been written. The rest is overwritten before the
        // next SoundSampleEvent can expose it, so retaining the full ~546 KiB frame buffer
        // in every rewind state wastes memory and creates a G1 humongous allocation.
        int[] pendingSamples = capture == null ? Arrays.copyOf(buffer, i) : capture.ints(buffer, i);
        return new SoundState(
                allModeMementos,
                capture == null ? r.captureState() : r.captureState(capture),
                frameSequencer.captureState(),
                capture == null ? channels.clone() : capture.ints(channels),
                enabled,
                capture == null ? overriddenEnabled.clone() : capture.booleans(overriddenEnabled),
                pendingSamples, i, pendingFrameSequencerStep,
                frameSequencerClockPhase, frameSequencerDivOffset);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        r.declareMachineStatePayloads(capture);
        capture.declareInts(buffer, i);
    }

    @Override
    public void restoreState(ComponentState<Sound> state) {
        if (!(state instanceof SoundState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.allModes.length != mem.allModeMementos.length) {
            throw new IllegalArgumentException("ComponentState allModes length doesn't match");
        }
        if (this.channels.length != mem.channels.length) {
            throw new IllegalArgumentException("ComponentState channels length doesn't match");
        }
        if (this.overriddenEnabled.length != mem.overriddenEnabled.length) {
            throw new IllegalArgumentException("ComponentState overriddenEnabled length doesn't match");
        }
        if (mem.i < 0 || mem.i >= this.buffer.length || (mem.i & 1) != 0) {
            throw new IllegalArgumentException("ComponentState buffer position is invalid");
        }
        // New mementos retain only buffer[0..i). Accept the former full-buffer shape as
        // well so save states written by older Coffee GB versions remain loadable.
        if (mem.buffer.length != mem.i && mem.buffer.length != this.buffer.length) {
            throw new IllegalArgumentException("ComponentState buffer length doesn't match");
        }
        for (int i = 0; i < allModes.length; i++) {
            this.allModes[i].restoreState(mem.allModeMementos[i]);
        }
        this.r.restoreState(mem.ramMemento());
        this.frameSequencer.restoreState(mem.frameSequencerMemento());
        System.arraycopy(mem.channels, 0, this.channels, 0, this.channels.length);
        this.enabled = mem.enabled();
        System.arraycopy(mem.overriddenEnabled, 0, this.overriddenEnabled, 0, this.overriddenEnabled.length);
        System.arraycopy(mem.buffer, 0, this.buffer, 0, mem.i);
        this.i = mem.i;
        this.pendingFrameSequencerStep = mem.pendingFrameSequencerStep;
        this.frameSequencerClockPhase = mem.frameSequencerClockPhase;
        this.frameSequencerDivOffset = mem.frameSequencerDivOffset;

    }

    public record SoundSampleEvent(int[] buffer, ClockSpec clockSpec) implements Event {
        public SoundSampleEvent(int[] buffer) {
            this(buffer, ClockSpec.LEGACY);
        }
    }

    public record SoundEnabledEvent(boolean enabled) implements Event {
    }

    private record SoundState(ComponentState<AbstractSoundMode>[] allModeMementos, ComponentState<Ram> ramMemento,
                                ComponentState<FrameSequencer> frameSequencerMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i, int pendingFrameSequencerStep,
                                int frameSequencerClockPhase,
                                int frameSequencerDivOffset) implements ComponentState<Sound> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SoundMemento(Memento<AbstractSoundMode>[] allModeMementos, Memento<Ram> ramMemento,
                                Memento<FrameSequencer> frameSequencerMemento, int[] channels,
                                boolean enabled, boolean[] overriddenEnabled, int[] buffer,
                                int i, int pendingFrameSequencerStep,
                                int frameSequencerClockPhase,
                                int frameSequencerDivOffset) implements Memento<Sound> {
    }
}
