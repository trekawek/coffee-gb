package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.*;

/** Exact high-frequency progression, including post-span register-visible phase and fetch latches. */
public class SoundPerformanceFrequencySpanTest {

    @Test
    public void waveformFrequencyExtremesMatchScalarAcrossTriggerAndReloadPhases() throws Exception {
        int[] frequencies = {0, 1023, 1920, 2040, 2044, 2045, 2046, 2047};
        int[] spans = {1, 2, 3, 4, 7, 15, 31, 54, 55, 56, 63, 64, 65, 129, 8193};
        for (boolean color : new boolean[]{false, true}) {
            for (int channel = 1; channel <= 3; channel++) {
                for (int frequency : frequencies) {
                    for (int phase = 0; phase < 8; phase++) {
                        for (int span : spans) {
                            AbstractSoundMode scalar = configured(channel, color, frequency);
                            AbstractSoundMode bulk = configured(channel, color, frequency);
                            for (int tick = 0; tick < phase; tick++) {
                                scalar.tick(false);
                                bulk.tick(false);
                            }
                            int expected = 0;
                            for (int tick = 0; tick < span; tick++) {
                                expected = scalar.tick(false);
                            }
                            assertEquals(expected, advance(bulk, span));
                            assertState("channel=" + channel + " color=" + color + " freq="
                                    + frequency + " phase=" + phase + " span=" + span,
                                    scalar.captureState(), bulk.captureState());
                            // A write immediately after the span observes the final reload latch
                            // (pulse channels) or final wave-RAM access window (CH3).
                            int address = channel == 1 ? 0xff13 : channel == 2 ? 0xff18 : 0xff30;
                            scalar.setByte(address, 0xa7);
                            bulk.setByte(address, 0xa7);
                            assertState("post-span write", scalar.captureState(), bulk.captureState());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void highFrequencyRegisterTrafficAndRestoresMatchScalar() throws Exception {
        Random random = new Random(0x415055);
        for (boolean color : new boolean[]{false, true}) {
            for (int channel = 1; channel <= 3; channel++) {
                AbstractSoundMode scalar = configured(channel, color, 2047);
                AbstractSoundMode bulk = configured(channel, color, 2047);
                int base = channel == 1 ? 0xff10 : channel == 2 ? 0xff15 : 0xff1a;
                for (int iteration = 0; iteration < 1500; iteration++) {
                    int span = 1 + random.nextInt(256);
                    for (int tick = 0; tick < span; tick++) {
                        scalar.tick(false);
                    }
                    advance(bulk, span);
                    assertState("span " + channel + '/' + iteration,
                            scalar.captureState(), bulk.captureState());
                    if (iteration % 11 == 0) {
                        scalar.tickLength();
                        bulk.tickLength();
                        scalar.tickEnvelope();
                        bulk.tickEnvelope();
                        scalar.tickSweep();
                        bulk.tickSweep();
                    }
                    if (iteration % 13 == 0) {
                        bulk.restoreState(bulk.captureState());
                    }
                    int address;
                    int value;
                    if (channel == 3 && iteration % 5 == 0) {
                        address = 0xff30 + random.nextInt(16);
                        value = random.nextInt(256);
                    } else if (iteration % 3 == 0) {
                        address = base + 3;
                        value = 0xf8 + random.nextInt(8);
                    } else if (iteration % 3 == 1) {
                        address = base + 4;
                        value = 0x87 | (random.nextBoolean() ? 0x40 : 0);
                    } else {
                        address = base + (channel == 3 ? 2 : 1);
                        value = channel == 3 ? random.nextInt(4) << 5 : random.nextInt(256);
                    }
                    scalar.setByte(address, value);
                    bulk.setByte(address, value);
                    if (channel == 3) {
                        for (int wave = 0xff30; wave <= 0xff3f; wave++) {
                            assertEquals("wave read window", scalar.getByte(wave), bulk.getByte(wave));
                        }
                    }
                    assertState("write " + channel + '/' + iteration,
                            scalar.captureState(), bulk.captureState());
                }
            }
        }
    }

    @Test
    public void sweepExpiryUsesOnlyItsOwnScalarTickAndPreservesSuffix() throws Exception {
        for (boolean color : new boolean[]{false, true}) {
            for (int sweep : new int[]{0x11, 0x19, 0x17, 0x10}) {
                for (int phase = 0; phase <= 16; phase++) {
                    CountingSweepChannel bulk = new CountingSweepChannel(color);
                    SoundMode1 scalar = new SoundMode1(new FrameSequencer(), color);
                    configurePulse(scalar, 0xff10, 2047, sweep);
                    configurePulse(bulk, 0xff10, 2047, sweep);
                    for (int tick = 0; tick < phase; tick++) {
                        scalar.tick(false);
                        bulk.tick(false);
                    }
                    bulk.scalarTicks = 0;
                    for (int tick = 0; tick < 129; tick++) {
                        scalar.tick(false);
                    }
                    bulk.tickPerformanceSpan(129);
                    assertState("sweep expiry", scalar.captureState(), bulk.captureState());
                    assertTrue("sweep expiry scalar-replayed its entire span", bulk.scalarTicks <= 1);
                }
            }
        }
    }

    @Test
    public void sweepFrequencyUpdatesRetainTheLastReloadLatchAcrossMultiplePeriods() throws Exception {
        for (boolean color : new boolean[]{false, true}) {
            SoundMode1 scalar = new SoundMode1(new FrameSequencer(), color);
            SoundMode1 bulk = new SoundMode1(new FrameSequencer(), color);
            configurePulse(scalar, 0xff10, 2047, 0x19);
            configurePulse(bulk, 0xff10, 2047, 0x19);
            for (int iteration = 0; iteration < 12; iteration++) {
                scalar.tickSweep();
                bulk.tickSweep();
                for (int tick = 0; tick < 8193; tick++) {
                    scalar.tick(false);
                }
                bulk.tickPerformanceSpan(8193);
                assertState("sweep frequency update " + iteration,
                        scalar.captureState(), bulk.captureState());
                scalar.setByte(0xff14, 0x87);
                bulk.setByte(0xff14, 0x87);
                assertState("retrigger after sweep", scalar.captureState(), bulk.captureState());
            }
        }
    }

    private static final class CountingSweepChannel extends SoundMode1 {
        int scalarTicks;
        CountingSweepChannel(boolean color) { super(new FrameSequencer(), color); }
        @Override public int tick(boolean divReset) {
            scalarTicks++;
            return super.tick(divReset);
        }
    }

    private static AbstractSoundMode configured(int channel, boolean color, int frequency) {
        FrameSequencer sequencer = new FrameSequencer();
        if (channel <= 2) {
            AbstractSoundMode result = channel == 1 ? new SoundMode1(sequencer, color)
                    : new SoundMode2(sequencer, color);
            configurePulse(result, channel == 1 ? 0xff10 : 0xff15, frequency, 0);
            return result;
        }
        SoundMode3 wave = new SoundMode3(sequencer,
                new Timer(new InterruptManager(color), new SpeedMode(color)), color);
        wave.start();
        for (int address = 0xff30; address <= 0xff3f; address++) {
            wave.setByte(address, (address * 31 ^ 0x73) & 0xff);
        }
        wave.setByte(0xff1a, 0x80);
        wave.setByte(0xff1b, 0);
        wave.setByte(0xff1c, 0x20);
        wave.setByte(0xff1d, frequency & 0xff);
        wave.setByte(0xff1e, 0x80 | (frequency >>> 8));
        return wave;
    }

    private static void configurePulse(AbstractSoundMode channel, int base, int frequency, int sweep) {
        channel.start();
        channel.setByte(base, sweep);
        channel.setByte(base + 1, 0xc0);
        channel.setByte(base + 2, 0xf3);
        channel.setByte(base + 3, frequency & 0xff);
        channel.setByte(base + 4, 0x80 | (frequency >>> 8));
    }

    private static int advance(AbstractSoundMode channel, int ticks) {
        if (channel instanceof SoundMode1 pulse) return pulse.tickPerformanceSpan(ticks);
        if (channel instanceof SoundMode2 pulse) return pulse.tickPerformanceSpan(ticks);
        return ((SoundMode3) channel).tickPerformanceSpan(ticks);
    }

    private static void assertState(String path, Object expected, Object actual) throws Exception {
        if (expected == actual) return;
        assertNotNull(path, expected);
        assertNotNull(path, actual);
        assertEquals(path, expected.getClass(), actual.getClass());
        if (expected.getClass().isArray()) {
            assertEquals(path, Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertState(path + '[' + i + ']', Array.get(expected, i), Array.get(actual, i));
            }
        } else if (expected.getClass().isRecord()) {
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertState(path + '.' + component.getName(), accessor.invoke(expected), accessor.invoke(actual));
            }
        } else {
            assertEquals(path, expected, actual);
        }
    }
}
