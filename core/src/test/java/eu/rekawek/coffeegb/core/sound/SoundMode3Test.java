package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SoundMode3Test {

    @Test
    public void mutedRetriggerDoesNotLeakStaleWaveSample() {
        SpeedMode speedMode = new SpeedMode(false);
        Timer timer = new Timer(new InterruptManager(false), speedMode);
        SoundMode3 mode = new SoundMode3(new FrameSequencer(), timer, false);
        mode.start();

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
}
