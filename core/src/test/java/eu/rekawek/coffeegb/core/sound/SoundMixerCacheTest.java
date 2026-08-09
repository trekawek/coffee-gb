package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SoundMixerCacheTest {

    @Test
    public void stableMixerOutputIsPublishedOnEveryTickAndChannelOutputChangesIt() {
        Fixture fixture = newFixture();
        enableChannel1DacAndRouteBoth(fixture.sound);

        assertSample(fixture, 120, 120);
        assertSample(fixture, 120, 120);
        assertSample(fixture, 120, 120);

        fixture.sound.setByte(0xff11, 0xc0);
        fixture.sound.setByte(0xff13, 0xff);
        fixture.sound.setByte(0xff14, 0x87);

        boolean observedChannelOutputChange = false;
        for (int i = 0; i < 20; i++) {
            int[] sample = tick(fixture);
            if (sample[0] == -120 && sample[1] == -120) {
                observedChannelOutputChange = true;
                break;
            }
        }
        assertTrue("channel output change did not reach the cached mixer", observedChannelOutputChange);
    }

    @Test
    public void mixerAndDacRegisterWritesInvalidateCachedOutput() {
        Fixture fixture = newFixture();
        enableChannel1DacAndRouteBoth(fixture.sound);
        assertSample(fixture, 120, 120);

        fixture.sound.setByte(0xff24, 0x00);
        assertSample(fixture, 15, 15);

        fixture.sound.setByte(0xff25, 0x01);
        assertSample(fixture, 0, 15);

        fixture.sound.setByte(0xff12, 0x00);
        assertSample(fixture, 0, 0);

        fixture.sound.setByte(0xff12, 0xf0);
        assertSample(fixture, 0, 15);
    }

    @Test
    public void channelOverrideAndApuPowerTransitionsInvalidateCachedOutput() {
        Fixture fixture = newFixture();
        enableChannel1DacAndRouteBoth(fixture.sound);
        assertSample(fixture, 120, 120);

        fixture.sound.enableChannel(0, false);
        assertSample(fixture, 0, 0);

        fixture.sound.enableChannel(0, true);
        assertSample(fixture, 120, 120);

        fixture.sound.setByte(0xff26, 0x00);
        assertSample(fixture, 0, 0);

        fixture.sound.setByte(0xff26, 0x80);
        assertSample(fixture, 0, 0);

        enableChannel1DacAndRouteBoth(fixture.sound);
        assertSample(fixture, 120, 120);
    }

    @Test
    public void restoringStateInvalidatesCachedOutput() {
        Fixture fixture = newFixture();
        enableChannel1DacAndRouteBoth(fixture.sound);
        assertSample(fixture, 120, 120);
        var state = fixture.sound.captureState();

        fixture.sound.setByte(0xff12, 0x00);
        assertSample(fixture, 0, 0);

        fixture.sound.restoreState(state);
        assertSample(fixture, 120, 120);
    }

    private static Fixture newFixture() {
        SpeedMode speedMode = new SpeedMode(true);
        Sound sound = new Sound(new Timer(new InterruptManager(true), speedMode), speedMode, true);
        List<int[]> samples = new ArrayList<>();
        assertTrue(sound.attachOutputObserver((left, right) -> samples.add(new int[]{left, right})));
        return new Fixture(sound, samples);
    }

    private static void enableChannel1DacAndRouteBoth(Sound sound) {
        sound.setByte(0xff24, 0x77);
        sound.setByte(0xff12, 0xf0);
        sound.setByte(0xff25, 0x11);
    }

    private static void assertSample(Fixture fixture, int left, int right) {
        assertArrayEquals(new int[]{left, right}, tick(fixture));
    }

    private static int[] tick(Fixture fixture) {
        int before = fixture.samples.size();
        fixture.sound.tick();
        assertEquals(before + 1, fixture.samples.size());
        return fixture.samples.get(before);
    }

    private record Fixture(Sound sound, List<int[]> samples) {
    }
}
