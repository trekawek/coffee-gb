package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Differential coverage for the bounded PERFORMANCE APU quiet-span seam. */
public final class SoundPerformanceSpanTest {

    @Test
    public void oneTo63TickSpansMatchScalarForPulseWaveAndNoisePhases() throws Exception {
        for (boolean gbc : new boolean[]{false, true}) {
            for (int warmup = 0; warmup <= 14; warmup++) {
                for (int span = 1; span <= 63; span++) {
                    Sound scalar = configured(gbc);
                    Sound bulk = configured(gbc);
                    for (int i = 0; i < warmup; i++) {
                        scalar.tick(false);
                        bulk.tick(false);
                    }
                    for (int i = 0; i < span; i++) {
                        scalar.tick(false);
                    }
                    bulk.tickPerformanceQuietSpan(span);
                    assertEquals("gbc=" + gbc + " warmup=" + warmup + " span=" + span,
                            digest(scalar.captureState()), digest(bulk.captureState()));
                }
            }
        }
    }

    @Test
    public void unsupportedBoundaryFallsBackToScalarOutputCadence() throws Exception {
        Sound scalar = configured(true);
        Sound bulk = configured(true);
        for (int i = 0; i < 10; i++) {
            scalar.tick(false);
            bulk.tick(false);
        }
        // The compact slot boundary is inside this request; the seam must not skip or duplicate
        // it even though its normal caller splits before reaching this case.
        scalar.tick(false);
        scalar.tick(false);
        bulk.tickPerformanceQuietSpan(2);
        assertEquals(digest(scalar.captureState()), digest(bulk.captureState()));
    }

    @Test
    public void spanAfterFrameSequencerCommitMatchesScalar() throws Exception {
        Sound scalar = configured(true);
        Sound bulk = configured(true);
        Timer scalarTimer = timerOf(scalar);
        Timer bulkTimer = timerOf(bulk);

        // Cross the selected DIV tap explicitly, commit the pending length/sweep/envelope
        // clock, then advance a quiet span. The bulk API is allowed after the commit but never
        // across it.
        scalarTimer.presetDiv(0x1000);
        bulkTimer.presetDiv(0x1000);
        scalar.tickFrameSequencer(false);
        bulk.tickFrameSequencer(false);
        scalarTimer.presetDiv(0);
        bulkTimer.presetDiv(0);
        scalar.tickFrameSequencer(false);
        bulk.tickFrameSequencer(false);
        scalar.commitFrameSequencerClock();
        bulk.commitFrameSequencerClock();

        scalar.tick(false);
        bulk.tickPerformanceQuietSpan(1);
        assertEquals(digest(scalar.captureState()), digest(bulk.captureState()));
    }

    @Test
    public void pendingSpanMaterializesBeforeRegisterAccessAndCapture() throws Exception {
        Sound scalar = configured(false);
        Sound bulk = configured(false);
        for (int i = 0; i < 23; i++) {
            scalar.tick(false);
            bulk.tickPerformanceQuietSpan(1);
        }

        assertEquals(23, intField(bulk, "pendingPerformanceTicks"));
        assertEquals(scalar.getByte(0xff26), bulk.getByte(0xff26));
        assertEquals(0, intField(bulk, "pendingPerformanceTicks"));
        assertEquals(digest(scalar.captureState()), digest(bulk.captureState()));

        for (int i = 0; i < 19; i++) {
            scalar.tick(false);
            bulk.tickPerformanceQuietSpan(1);
        }
        Object scalarState = scalar.captureState();
        Object bulkState = bulk.captureState();
        assertEquals(0, intField(bulk, "pendingPerformanceTicks"));
        assertEquals(digest(scalarState), digest(bulkState));
    }

    @Test
    public void pendingSpanMaterializesBeforeNrWriteAndSaveRestoreContinuesExactly() throws Exception {
        Sound scalar = configured(false);
        Sound bulk = configured(false);
        for (int i = 0; i < 27; i++) {
            scalar.tick(false);
            bulk.tickPerformanceQuietSpan(1);
        }

        // NR writes are CPU-visible APU boundaries just like reads: the channel must observe
        // every deferred master tick before the register latch changes.
        scalar.setByte(0xff12, 0x82);
        bulk.setByte(0xff12, 0x82);
        assertEquals(0, intField(bulk, "pendingPerformanceTicks"));
        assertEquals(digest(scalar.captureState()), digest(bulk.captureState()));

        // Capture is canonical, but this also exercises restore of the resulting same-mode
        // compact stream before more lazy ticks are accumulated.
        Sound restored = configured(false);
        restored.restoreState(bulk.captureState());
        for (int i = 0; i < 19; i++) {
            scalar.tick(false);
            restored.tickPerformanceQuietSpan(1);
        }
        assertEquals(digest(scalar.captureState()), digest(restored.captureState()));
    }

    @Test
    public void pendingSpanMaterializesBeforeWaveRamAndFrameSequencerBoundaries() throws Exception {
        Sound scalar = configured(true);
        Sound bulk = configured(true);
        Timer scalarTimer = timerOf(scalar);
        Timer bulkTimer = timerOf(bulk);
        for (int i = 0; i < 17; i++) {
            scalar.tick(false);
            bulk.tickPerformanceQuietSpan(1);
        }

        assertEquals(scalar.getByte(0xff30), bulk.getByte(0xff30));
        scalar.setByte(0xff30, 0x42);
        bulk.setByte(0xff30, 0x42);

        scalarTimer.presetDiv(0x1000);
        bulkTimer.presetDiv(0x1000);
        scalar.tickFrameSequencer(false);
        bulk.tickFrameSequencer(false);
        scalarTimer.presetDiv(0);
        bulkTimer.presetDiv(0);
        scalar.tickFrameSequencer(false);
        bulk.tickFrameSequencer(false);
        scalar.commitFrameSequencerClock();
        bulk.commitFrameSequencerClock();
        assertEquals(0, intField(bulk, "pendingPerformanceTicks"));
        assertEquals(digest(scalar.captureState()), digest(bulk.captureState()));
    }

    @Test
    public void disabledApuAndObservationHooksFailClosedWithoutStalePendingTicks() throws Exception {
        Sound disabledScalar = configured(false);
        Sound disabledBulk = configured(false);
        disabledScalar.setByte(0xff26, 0x00);
        disabledBulk.setByte(0xff26, 0x00);
        for (int i = 0; i < 19; i++) {
            disabledScalar.tick(false);
            disabledBulk.tickPerformanceQuietSpan(1);
        }
        assertEquals(digest(disabledScalar.captureState()), digest(disabledBulk.captureState()));
        assertEquals(0, intField(disabledBulk, "pendingPerformanceTicks"));

        Sound debug = configured(false);
        debug.setDebugHooks(new TestDebugHooks());
        debug.tickPerformanceQuietSpan(7);
        assertEquals(0, intField(debug, "pendingPerformanceTicks"));

        Sound observed = configured(false);
        SoundOutputObserver observer = (left, right) -> { };
        assertEquals(true, observed.attachOutputObserver(observer));
        observed.tickPerformanceQuietSpan(7);
        assertEquals(0, intField(observed, "pendingPerformanceTicks"));
        assertEquals(true, observed.detachOutputObserver(observer));
    }

    @Test
    public void channelTwoDividerExpiryMatchesScalar() throws Exception {
        for (boolean gbc : new boolean[]{false, true}) {
            Sound scalar = configured(gbc);
            Sound bulk = configured(gbc);
            int ticks = assertNextTickMatchesScalar("CH2 divider expiry gbc=" + gbc, scalar, bulk,
                    sound -> {
                        Object mode = field(sound, "mode2");
                        int phase = intField(mode, "phase");
                        return intField(mode, "freqDivider") == 0
                                && (((phase - 1) & 1) != 0);
                    }, 5_000);
            assertEquals("CH2 first divider expiry gbc=" + gbc, 3_084, ticks);
        }
    }

    @Test
    public void channelThreeDividerExpiryMatchesScalar() throws Exception {
        for (boolean gbc : new boolean[]{false, true}) {
            Sound scalar = configured(gbc);
            Sound bulk = configured(gbc);
            int ticks = assertNextTickMatchesScalar("CH3 divider expiry gbc=" + gbc, scalar, bulk,
                    sound -> {
                        Object mode = field(sound, "mode3");
                        return !booleanField(mode, "clock2Mhz")
                                && intField(mode, "freqDivider") == 1;
                    }, 5_000);
            assertEquals("CH3 first divider expiry gbc=" + gbc, 2_054, ticks);
        }
    }

    @Test
    public void channelOneSweepCalculationExpiryFallsBackToScalar() throws Exception {
        for (boolean gbc : new boolean[]{false, true}) {
            Sound scalar = configured(gbc);
            Sound bulk = configured(gbc);
            int ticks = assertNextTickMatchesScalar("CH1 sweep calculation expiry gbc=" + gbc,
                    scalar, bulk,
                    sound -> intField(field(field(sound, "mode1"), "frequencySweep"),
                            "calculationDelay") == 1,
                    100);
            assertEquals("CH1 first sweep calculation expiry gbc=" + gbc, 11, ticks);
        }
    }

    /**
     * Finds the exact tick immediately before a channel edge, while avoiding the compact output
     * boundary so that the one-tick quiet-span call exercises the channel's own edge handling.
     * A sweep calculation expiry is intentionally included: SoundMode1 must fail closed to the
     * scalar path because the calculation can disable CH1 before a pulse edge in the same span.
     */
    private static int assertNextTickMatchesScalar(String label, Sound scalar, Sound bulk,
                                                   StatePredicate edge, int maxTicks)
            throws Exception {
        for (int ticks = 0; ticks < maxTicks; ticks++) {
            if (edge.test(scalar) && scalar.performanceQuietSpanLimit(1) == 1) {
                scalar.tick(false);
                bulk.tickPerformanceQuietSpan(1);
                assertEquals(label, digest(scalar.captureState()), digest(bulk.captureState()));
                return ticks;
            }
            scalar.tick(false);
            bulk.tick(false);
        }
        fail(label + " was not reached within " + maxTicks + " ticks");
        return -1;
    }

    @FunctionalInterface
    private interface StatePredicate {
        boolean test(Sound sound) throws Exception;
    }

    private static Sound configured(boolean gbc) {
        SpeedMode speedMode = new SpeedMode(gbc);
        Timer timer = new Timer(new InterruptManager(gbc), speedMode);
        Sound sound = new Sound(timer, speedMode, gbc,
                new ClockSpec(1_100_000, 1, 100, 1), ExecutionMode.PERFORMANCE);
        sound.setByte(0xff26, 0x80);
        sound.setByte(0xff24, 0x77);
        sound.setByte(0xff25, 0xff);
        sound.setByte(0xff11, 0xc0);
        sound.setByte(0xff12, 0xf3);
        sound.setByte(0xff10, 0x11);
        sound.setByte(0xff13, 0xff);
        sound.setByte(0xff14, 0x87);
        sound.setByte(0xff16, 0x80);
        sound.setByte(0xff17, 0xa3);
        sound.setByte(0xff18, 0xff);
        sound.setByte(0xff19, 0x84);
        sound.setByte(0xff1a, 0x80);
        sound.setByte(0xff1b, 0x20);
        sound.setByte(0xff1c, 0x60);
        sound.setByte(0xff1d, 0xff);
        sound.setByte(0xff1e, 0x83);
        sound.setByte(0xff20, 0x3f);
        sound.setByte(0xff21, 0xf3);
        sound.setByte(0xff22, 0x30);
        sound.setByte(0xff23, 0x80);
        return sound;
    }

    private static Timer timerOf(Sound sound) throws Exception {
        return (Timer) field(sound, "timer");
    }

    private static Object field(Object receiver, String name) throws Exception {
        java.lang.reflect.Field field = receiver instanceof Sound
                ? Sound.class.getDeclaredField(name)
                : receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static int intField(Object receiver, String name) throws Exception {
        Object value = field(receiver, name);
        return value instanceof Integer integer ? integer : ((Number) value).intValue();
    }

    private static boolean booleanField(Object receiver, String name) throws Exception {
        return (Boolean) field(receiver, name);
    }

    private static long digest(Object value) throws Exception {
        Digest digest = new Digest();
        visit(value, digest);
        return digest.value;
    }

    private static void visit(Object value, Digest digest)
            throws IllegalAccessException, InvocationTargetException {
        if (value == null) {
            digest.mix(0);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            digest.mix(type.getName().hashCode());
            int length = Array.getLength(value);
            digest.mix(length);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                if (type.getComponentType().isPrimitive()) {
                    digest.mix(element.hashCode());
                } else {
                    visit(element, digest);
                }
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?>) {
            digest.mix(type.getName().hashCode());
            digest.mix(value.hashCode());
            return;
        }
        if (!type.isRecord()) {
            throw new AssertionError("Unexpected state type: " + type.getName());
        }
        digest.mix(type.getName().hashCode());
        for (RecordComponent component : type.getRecordComponents()) {
            digest.mix(component.getName().hashCode());
            component.getAccessor().trySetAccessible();
            visit(component.getAccessor().invoke(value), digest);
        }
    }

    private static final class Digest {
        private long value = 0xcbf29ce484222325L;

        private void mix(long next) {
            value ^= next + 0x9e3779b97f4a7c15L + (value << 6) + (value >>> 2);
        }
    }
}
