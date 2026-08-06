package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SoundFrameSequencerTimingTest {

    private static final int NR52 = 0xff26;

    private static final int CHANNEL_2_ENABLED = 1 << 1;

    @Test
    public void cgbBootPsgPhaseAccountsForTenClockCpuDividerPreset() {
        Rig rig = newCgbRig();
        triggerChannel2WithLengthOne(rig.sound);

        rig.timer.presetDiv(0x7ffc);
        tickFrameSequencer(rig.sound);
        assertChannel2Enabled(rig.sound);

        rig.timer.tick();
        tickFrameSequencer(rig.sound);
        assertChannel2Enabled(rig.sound);

        rig.timer.tick();
        tickFrameSequencer(rig.sound);
        assertChannel2Disabled(rig.sound);
    }

    @Test
    public void divResetAlignsCgbFrameSequencerWithVisibleDivider() {
        Rig rig = newCgbRig();
        rig.timer.setByte(0xff04, 0);
        tickFrameSequencer(rig.sound);
        rig.sound.setByte(NR52, 0);
        rig.sound.setByte(NR52, 0x80);
        triggerChannel2WithLengthOne(rig.sound);

        rig.timer.presetDiv(0x7ff8);
        tickFrameSequencer(rig.sound);
        rig.timer.presetDiv(0x7ff0);
        tickFrameSequencer(rig.sound);
        assertChannel2Enabled(rig.sound);

        rig.timer.presetDiv(0x8000);
        tickFrameSequencer(rig.sound);
        assertChannel2Disabled(rig.sound);
    }

    @Test
    public void cgbBootDividerPhaseSurvivesMementoRestore() {
        Rig rig = newCgbRig();
        triggerChannel2WithLengthOne(rig.sound);
        rig.timer.presetDiv(0x7fee);
        tickFrameSequencer(rig.sound);
        var soundMemento = rig.sound.saveToMemento();
        var timerMemento = rig.timer.saveToMemento();

        rig.timer.setByte(0xff04, 0);
        tickFrameSequencer(rig.sound);
        rig.sound.restoreFromMemento(soundMemento);
        rig.timer.restoreFromMemento(timerMemento);

        rig.timer.presetDiv(0x7ffd);
        tickFrameSequencer(rig.sound);
        assertChannel2Enabled(rig.sound);

        rig.timer.presetDiv(0x7ffe);
        tickFrameSequencer(rig.sound);
        assertChannel2Disabled(rig.sound);
    }

    private static Rig newCgbRig() {
        SpeedMode speedMode = new SpeedMode(true);
        Timer timer = new Timer(new InterruptManager(true), speedMode);
        return new Rig(timer, new Sound(timer, speedMode, true));
    }

    private static void triggerChannel2WithLengthOne(Sound sound) {
        sound.setByte(0xff16, 0x3f);
        sound.setByte(0xff17, 0xf0);
        sound.setByte(0xff18, 0);
        sound.setByte(0xff19, 0xc0);
        assertChannel2Enabled(sound);
    }

    private static void tickFrameSequencer(Sound sound) {
        sound.tickFrameSequencer();
        sound.commitFrameSequencerClock();
    }

    private static void assertChannel2Enabled(Sound sound) {
        assertTrue((sound.getByte(NR52) & CHANNEL_2_ENABLED) != 0);
    }

    private static void assertChannel2Disabled(Sound sound) {
        assertFalse((sound.getByte(NR52) & CHANNEL_2_ENABLED) != 0);
    }

    private record Rig(Timer timer, Sound sound) {
    }
}
