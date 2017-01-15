package eu.rekawek.coffeegb.sound;

import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class SoundTest {

    public void testDuty() {
        SoundMode1_2 mode = new SoundMode1_2(1);
        mode.setByte(0xff11, 0b00000000); // duty = 00
        mode.setByte(0xff13, 0b00000001); // freq = 0
        mode.setByte(0xff14, 0b10000000); // trigger

        assertSound(mode, 0, 32 * (2048 - 0) * 7);
        assertSound(mode, 15, 32 * (2048 - 0));
        assertSound(mode, 0, 32 * (2048 - 0) * 7);
    }

    private void assertSound(AbstractSoundMode sound, int value, long ticks) {
        for (long i = 0; i < ticks; i++) {
            assertEquals("Invalid value at tick = " + i, value, sound.tick());
        }
    }

    @Test
    public void testSound() throws LineUnavailableException {
        int[] wave = new int[] {0x11, 0x23, 0x56, 0x78, 0x99, 0x98, 0x76, 0x67, 0x9A, 0xDF, 0xFE, 0xC9, 0x85, 0x42, 0x11, 0x31};
        byte[] b = new byte[16384 * 10];

        for (int i = 0, j = 0; i < b.length; i++) {
            int w = wave[(j / 2) % wave.length];
            if ((j % 2) == 0) {
                b[i] = (byte) (w >> 4);
            } else {
                b[i] = (byte) (w & 0xf);
            }
            if ((i % 7) == 0) {
                j++;
            }
        }

        for (int i = 0; i < b.length; i++) {
            b[i] *= 16;
        }

        System.out.println(Arrays.toString(b));

        AudioFormat f = new AudioFormat(16384, 8, 1, false, true);
        SourceDataLine line = AudioSystem.getSourceDataLine(f);
        line.open(f);
        line.start();
        line.write(b, 0, b.length);
        line.drain();
        line.close();
    }
}
