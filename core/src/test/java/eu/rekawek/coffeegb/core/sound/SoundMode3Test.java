package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SoundMode3Test {

    @Test
    public void frequencyLowWriteAffectsTheNextReloadWithoutRestartingTheTimer() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0x30);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xfc);
        mode.setByte(0xff1e, 0x87);

        // The running trigger delay keeps its period of four APU cycles, while the
        // following reload observes the newly written period of one APU cycle.
        mode.setByte(0xff1d, 0xff);
        assertTicks(mode, 14, 0);
        assertEquals(3, mode.tick());
    }

    @Test
    public void frequencyHighWriteAffectsTheNextReloadWithoutRestartingTheTimer() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0xb0);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);
        mode.setByte(0xff1e, 0x87);

        // A bare NR34 write changes the next reload to 257 APU cycles without
        // restarting the already running trigger delay.
        mode.setByte(0xff1e, 0x06);
        assertTicks(mode, 520, 0);
        assertEquals(11, mode.tick());
    }

    @Test
    public void triggerUsesTheNewFrequencyHighBits() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0xa0);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);

        // NR34 changes the high bits from zero to seven and triggers in the same write.
        // Period one plus the three-cycle trigger delay produces the first fetch at T7
        // and the first non-stale output at T9.
        mode.setByte(0xff1e, 0x87);
        assertTicks(mode, 8, 0);
        assertEquals(10, mode.tick());
    }

    @Test
    public void allVolumeCodesUpdateTheCurrentOutputImmediately() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0xf0);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);
        mode.setByte(0xff1e, 0x87);
        assertTicks(mode, 8, 0);
        assertEquals(15, mode.tick());

        mode.setByte(0xff1c, 0x00);
        assertEquals(0, mode.getCurrentOutput());
        mode.setByte(0xff1c, 0x20);
        assertEquals(15, mode.getCurrentOutput());
        mode.setByte(0xff1c, 0x40);
        assertEquals(7, mode.getCurrentOutput());
        mode.setByte(0xff1c, 0x60);
        assertEquals(3, mode.getCurrentOutput());
    }

    @Test
    public void restoreRebuildsFrequencyAndVolumeDerivedState() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0xe0);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);
        mode.setByte(0xff1e, 0x87);
        var state = mode.captureState();

        mode.setByte(0xff1c, 0x00);
        mode.setByte(0xff1d, 0x00);
        mode.setByte(0xff1e, 0x06);
        mode.restoreState(state);

        assertTicks(mode, 8, 0);
        assertEquals(14, mode.tick());
    }

    @Test
    public void mutedRetriggerDoesNotLeakStaleWaveSample() {
        SoundMode3 mode = newDmgMode();

        mode.setByte(0xff30, 0xff);
        mode.setByte(0xff31, 0xff);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);
        mode.setByte(0xff1e, 0x87);

        for (int i = 0; i < 8; i++) {
            mode.tick();
        }
        assertEquals(15, mode.tick());

        mode.setByte(0xff1c, 0x00);
        mode.setByte(0xff1e, 0x87);
        for (int i = 0; i < 12; i++) {
            assertEquals(0, mode.tick());
        }
    }

    @Test
    public void lengthExpiryGatesTheRetainedSampleWhileTheDacStaysOn() {
        SoundMode3 mode = newDmgMode();
        mode.setByte(0xff31, 0xf0);
        mode.setByte(0xff1a, 0x80);
        mode.setByte(0xff1c, 0x20);
        mode.setByte(0xff1d, 0xff);
        mode.setByte(0xff1e, 0x87);
        assertTicks(mode, 8, 0);
        assertEquals(15, mode.tick());

        mode.setByte(0xff1b, 0xff); // length = 1
        mode.setByte(0xff1e, 0x47); // enable length without retriggering
        mode.tickLength();

        assertTrue(mode.isDacEnabled());
        assertFalse(mode.isEnabled());
        assertEquals(0, mode.getCurrentOutput());
        assertEquals(0, mode.tick());
    }

    @Test
    public void retriggerReprojectsTheStaleHighNibbleAtBoth2MhzPhases() {
        for (int phase = 0; phase < 2; phase++) {
            SoundMode3 mode = newDmgMode();
            mode.setByte(0xff30, 0xe3);
            mode.setByte(0xff1a, 0x80);
            mode.setByte(0xff1c, 0x20);
            mode.setByte(0xff1d, 0xfe); // period = 2 APU cycles
            mode.setByte(0xff1e, 0x87);

            // The first post-trigger advance opens the port and latches E3, while
            // continuing to play the pre-trigger buffer.
            assertTicks(mode, 9 + phase, 0);
            mode.setByte(0xff1c, 0x40);
            assertEquals(1, mode.getCurrentOutput()); // low nibble at half volume

            mode.setByte(0xff1e, 0x87);
            assertEquals(7, mode.getCurrentOutput()); // reset selector: high nibble
            assertEquals(7, mode.tick());
        }
    }

    private static SoundMode3 newDmgMode() {
        SpeedMode speedMode = new SpeedMode(false);
        Timer timer = new Timer(new InterruptManager(false), speedMode);
        SoundMode3 mode = new SoundMode3(new FrameSequencer(), timer, false);
        mode.start();
        return mode;
    }

    private static void assertTicks(SoundMode3 mode, int ticks, int output) {
        for (int i = 0; i < ticks; i++) {
            assertEquals(output, mode.tick());
        }
    }
}
