package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Focused checks for the transient system-muted PERFORMANCE audio calendar. */
public final class PerformanceSystemMutedAudioCalendarTest {

    @Test
    public void silentCalendarPreservesZeroCadenceAtEveryInitialPhase() {
        for (int phase = 0; phase < 55; phase++) {
            Sound sound = newSound();
            List<Sound.SoundSampleEvent> events = new ArrayList<>();
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            eventBus.register(events::add, Sound.SoundSampleEvent.class);
            sound.init(eventBus);
            sound.setPerformanceSystemMutedAudioCalendar(true);
            sound.resetPerformanceSystemMutedAudioCalendarCounters();

            sound.tickPerformanceQuietSpan(phase);
            sound.tickPerformanceQuietSpan(55);
            assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarZeroSampleSlots());
            assertEquals(1, events.size());
            assertAllZero(events.get(0).buffer());

            sound.tickPerformanceQuietSpan(55 - phase);
            assertEquals(2L, sound.getPerformanceSystemMutedAudioCalendarZeroSampleSlots());
            assertEquals(2, events.size());
            assertAllZero(events.get(1).buffer());
            assertEquals(110L, sound.getPerformanceSystemMutedAudioCalendarSkippedTicks());
            assertEquals(110L, sound.getPerformanceSystemMutedAudioCalendarMaxPendingTicks());

            sound.materializePendingPerformanceTicks();
        }
    }

    @Test
    public void silentCalendarBoundaryAndFrameSequencerKeepCadenceAndBoundaries() {
        Sound sound = newSound();
        sound.setPerformanceSystemMutedAudioCalendar(true);
        sound.resetPerformanceSystemMutedAudioCalendarCounters();

        assertEquals(100, sound.performanceQuietSpanLimit(100));
        assertEquals(100, sound.performanceEpochSpanLimit(100));
        sound.tickPerformanceBoundary(false);
        sound.tickPerformanceQuietSpan(54);
        assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarZeroSampleSlots());

        Timer timer = timerOf(sound);
        timer.presetDiv(0x1000);
        sound.tickFrameSequencer(false);
        timer.presetDiv(0);
        sound.tickFrameSequencer(false);
        sound.commitFrameSequencerClock();
        assertTrue(sound.getPerformanceSystemMutedAudioCalendarFrameSequencerCommits() >= 1L);
    }

    @Test
    public void defaultCalendarRemainsOffAndRetainsExistingAudioCeilings() {
        Sound sound = newSound();
        assertFalse(sound.isPerformanceSystemMutedAudioCalendarEnabled());
        assertEquals(Sound.PerformanceSystemMutedAudioMode.OFF,
                sound.getPerformanceSystemMutedAudioMode());
        assertEquals(54, sound.performanceQuietSpanLimit(100));
        assertEquals(54, sound.performanceEpochSpanLimit(100));
        sound.tickPerformanceQuietSpan(54);
        assertEquals(0L, sound.getPerformanceSystemMutedAudioCalendarSkippedTicks());
        sound.materializePendingPerformanceTicks();

        Sound accuracy = newSound(ExecutionMode.ACCURACY);
        try {
            accuracy.setPerformanceSystemMutedAudioCalendar(true);
            fail("accuracy mode must reject the silent PCM calendar");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void booleanSetterRetainsExactPolicyAndExplicitModesRoundTrip() {
        Sound sound = newSound();
        sound.setPerformanceSystemMutedAudioCalendar(true);
        assertEquals(Sound.PerformanceSystemMutedAudioMode.EXACT,
                sound.getPerformanceSystemMutedAudioMode());
        sound.setPerformanceSystemMutedAudioMode(
                Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
        assertEquals(Sound.PerformanceSystemMutedAudioMode.RELAXED_APU,
                sound.getPerformanceSystemMutedAudioMode());
        sound.setPerformanceSystemMutedAudioCalendar(false);
        assertEquals(Sound.PerformanceSystemMutedAudioMode.OFF,
                sound.getPerformanceSystemMutedAudioMode());
    }

    @Test
    public void relaxedCalendarPreservesZeroCadenceAndDropsOnlyDeferredChannelTicks() {
        for (int phase = 0; phase < 55; phase++) {
            Sound sound = newSound();
            List<Sound.SoundSampleEvent> events = new ArrayList<>();
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            eventBus.register(events::add, Sound.SoundSampleEvent.class);
            sound.init(eventBus);
            sound.setPerformanceSystemMutedAudioMode(
                    Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
            sound.resetPerformanceSystemMutedAudioCalendarCounters();

            sound.tickPerformanceQuietSpan(phase);
            sound.tickPerformanceQuietSpan(55);
            assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarZeroSampleSlots());
            assertEquals(1, events.size());
            assertAllZero(events.get(0).buffer());
            assertEquals(0L,
                    sound.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());

            sound.setByte(0xff24, 0x77);
            assertEquals(phase + 55L,
                    sound.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());
            assertEquals(sound.getPerformanceSystemMutedAudioCalendarSkippedTicks(),
                    sound.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());
            assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarApuWrites());
            assertEquals(1, events.size());
            eventBus.close();
        }
    }

    @Test
    public void relaxedCalendarKeepsFrameSequencerAndApuAccessBoundaries() throws Exception {
        Sound sound = newSound();
        sound.setPerformanceSystemMutedAudioMode(
                Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
        sound.resetPerformanceSystemMutedAudioCalendarCounters();
        sound.tickPerformanceQuietSpan(12);

        Timer timer = timerOf(sound);
        timer.presetDiv(0x1000);
        sound.tickFrameSequencer(false);
        timer.presetDiv(0);
        sound.tickFrameSequencer(false);
        sound.commitFrameSequencerClock();

        assertTrue(sound.getPerformanceSystemMutedAudioCalendarFrameSequencerCommits() >= 1L);
        assertTrue(sound.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks() >= 12L);
        assertEquals(0x80, sound.getByte(0xff26) & 0x80);
        assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarApuReads());
    }

    @Test
    public void relaxedDisableDropsLiveDebtAndRestoreTurnsModeOff() throws Exception {
        try (SoundPair pair = pair(true)) {
            pair.silent.setPerformanceSystemMutedAudioMode(
                    Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
            pair.silent.resetPerformanceSystemMutedAudioCalendarCounters();
            pair.silent.tickPerformanceQuietSpan(8192);
            pair.silent.setPerformanceSystemMutedAudioMode(
                    Sound.PerformanceSystemMutedAudioMode.OFF);
            assertEquals(Sound.PerformanceSystemMutedAudioMode.OFF,
                    pair.silent.getPerformanceSystemMutedAudioMode());
            assertEquals(8192L,
                    pair.silent.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());

            var state = pair.silent.captureState();
            pair.silent.setPerformanceSystemMutedAudioMode(
                    Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
            pair.silent.restoreState(state);
            assertEquals(Sound.PerformanceSystemMutedAudioMode.OFF,
                    pair.silent.getPerformanceSystemMutedAudioMode());
            assertFalse(pair.silent.isPerformanceSystemMutedAudioCalendarEnabled());
        }
    }

    @Test
    public void relaxedCalendarDeliberatelyDivergesFromExactChannelState() throws Exception {
        try (SoundPair pair = pair(true)) {
            pair.silent.setPerformanceSystemMutedAudioMode(
                    Sound.PerformanceSystemMutedAudioMode.RELAXED_APU);
            pair.silent.resetPerformanceSystemMutedAudioCalendarCounters();
            int debt = 8192;
            advanceScalar(pair.scalar, debt);
            pair.silent.tickPerformanceQuietSpan(debt);
            pair.scalar.materializePendingPerformanceTicks();
            pair.silent.setByte(0xff24, 0x77);

            assertEquals((long) debt,
                    pair.silent.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());
            assertEquals(pair.silent.getPerformanceSystemMutedAudioCalendarSkippedTicks(),
                    pair.silent.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks());
            assertNotEquals("relaxed mode must not advance deferred channel state",
                    canonicalDigest(pair.scalar.captureState()),
                    canonicalDigest(pair.silent.captureState()));
        }
    }

    @Test
    public void calendarCountsApuBoundariesAndMaterializesDebtBeforeCpuVisibleAccess() {
        Sound sound = newSound();
        sound.setPerformanceSystemMutedAudioCalendar(true);
        sound.resetPerformanceSystemMutedAudioCalendarCounters();
        sound.tickPerformanceQuietSpan(12);
        assertEquals(0x80, sound.getByte(0xff26) & 0x80);
        sound.setByte(0xff24, 0x77);
        assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarApuReads());
        assertEquals(1L, sound.getPerformanceSystemMutedAudioCalendarApuWrites());
    }

    @Test
    public void randomizedDebtsThrough8192MaterializeTheCanonicalApuCalendar() throws Exception {
        int[] fixedDebts = {1, 2, 3, 54, 55, 56, 127, 511, 1023, 2047, 4095, 8191, 8192};
        Random random = new Random(0x5a17c0deL);
        for (boolean gbc : new boolean[]{false, true}) {
            for (int sample = 0; sample < 96; sample++) {
                int debt = sample < fixedDebts.length
                        ? fixedDebts[sample] : 1 + random.nextInt(8192);
                try (SoundPair pair = pair(gbc)) {
                    int warmup = random.nextInt(41);
                    for (int i = 0; i < warmup; i++) {
                        pair.scalar.tick(false);
                        pair.silent.tickPerformanceQuietSpan(1);
                    }
                    advanceScalar(pair.scalar, debt);
                    pair.silent.tickPerformanceQuietSpan(debt);
                    switch (sample & 3) {
                        case 0 -> assertEquals(pair.scalar.getByte(0xff26), pair.silent.getByte(0xff26));
                        case 1 -> {
                            pair.scalar.setByte(0xff24, 0x31 + sample);
                            pair.silent.setByte(0xff24, 0x31 + sample);
                        }
                        case 2 -> commitFrameSequencer(pair);
                        case 3 -> {
                            pair.scalar.materializePendingPerformanceTicks();
                            pair.silent.materializePendingPerformanceTicks();
                        }
                        default -> throw new AssertionError();
                    }
                    assertExact(pair, "gbc=" + gbc + " debt=" + debt + " sample=" + sample);
                }
            }
        }
    }

    @Test
    public void sweepExpiryOffsetsOneThroughFortyAndPulseReloadsStayExact() throws Exception {
        for (int offset = 1; offset <= 40; offset++) {
            try (SoundPair pair = pair(true)) {
                Object scalarSweep = field(field(pair.scalar, "mode1"), "frequencySweep");
                Object silentSweep = field(field(pair.silent, "mode1"), "frequencySweep");
                setIntField(scalarSweep, "calculationDelay", offset);
                setIntField(silentSweep, "calculationDelay", offset);
                setBooleanField(scalarSweep, "unshiftedCalculation", true);
                setBooleanField(silentSweep, "unshiftedCalculation", true);
                int debt = offset + 17;
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "sweep offset=" + offset);
            }
        }

        for (int reload = 1; reload <= 40; reload++) {
            try (SoundPair pair = pair(true)) {
                setIntField(field(pair.scalar, "mode1"), "freqDivider", reload);
                setIntField(field(pair.silent, "mode1"), "freqDivider", reload);
                setIntField(field(pair.scalar, "mode1"), "phase", 1);
                setIntField(field(pair.silent, "mode1"), "phase", 1);
                int debt = reload + 19;
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "pulse reload=" + reload);
            }
        }
    }

    @Test
    public void waveTriggerFetchWindowAndNoisePolynomialConfigurationsStayExact() throws Exception {
        int[] waveDebts = {1, 2, 3, 4, 7, 15, 31, 63, 127};
        for (int debt : waveDebts) {
            try (SoundPair pair = pair(true)) {
                for (int address = 0xff30; address <= 0xff3f; address++) {
                    pair.scalar.setByte(address, address ^ 0x5a);
                    pair.silent.setByte(address, address ^ 0x5a);
                }
                pair.scalar.setByte(0xff1a, 0x80);
                pair.silent.setByte(0xff1a, 0x80);
                pair.scalar.setByte(0xff1b, 0x20);
                pair.silent.setByte(0xff1b, 0x20);
                pair.scalar.setByte(0xff1c, 0x60);
                pair.silent.setByte(0xff1c, 0x60);
                pair.scalar.setByte(0xff1d, 0x42);
                pair.silent.setByte(0xff1d, 0x42);
                pair.scalar.setByte(0xff1e, 0x87);
                pair.silent.setByte(0xff1e, 0x87);
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "wave debt=" + debt);
            }
        }

        int[] nr43Values = {0x00, 0x01, 0x07, 0x10, 0x17, 0x20, 0x2f, 0x37, 0x3f, 0xff};
        for (int nr43 : nr43Values) {
            try (SoundPair pair = pair(true)) {
                pair.scalar.setByte(0xff21, 0xf3);
                pair.silent.setByte(0xff21, 0xf3);
                pair.scalar.setByte(0xff22, nr43);
                pair.silent.setByte(0xff22, nr43);
                pair.scalar.setByte(0xff23, 0x80);
                pair.silent.setByte(0xff23, 0x80);
                int debt = 11 + (nr43 & 7);
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "noise NR43=" + nr43);
            }
        }
    }

    @Test
    public void apuAccessFrameSequencerDivDebugObserverAndRestoreBoundariesStayExact()
            throws Exception {
        for (int interruption = 0; interruption < 7; interruption++) {
            try (SoundPair pair = pair(true)) {
                advanceScalar(pair.scalar, 8192);
                pair.silent.tickPerformanceQuietSpan(8192);
                switch (interruption) {
                    case 0 -> assertEquals(pair.scalar.getByte(0xff26), pair.silent.getByte(0xff26));
                    case 1 -> {
                        pair.scalar.setByte(0xff24, 0x42);
                        pair.silent.setByte(0xff24, 0x42);
                    }
                    case 2 -> commitFrameSequencer(pair);
                    case 3 -> {
                        pair.scalarTimer.presetDiv(0);
                        pair.silentTimer.presetDiv(0);
                        pair.scalar.tickFrameSequencer(true);
                        pair.silent.tickFrameSequencer(true);
                        pair.scalar.commitFrameSequencerClock();
                        pair.silent.commitFrameSequencerClock();
                    }
                    case 4 -> {
                        pair.scalar.setDebugHooks(new TestDebugHooks());
                        pair.silent.setDebugHooks(new TestDebugHooks());
                    }
                    case 5 -> {
                        SoundOutputObserver scalarObserver = (left, right) -> { };
                        SoundOutputObserver silentObserver = (left, right) -> { };
                        assertTrue(pair.scalar.attachOutputObserver(scalarObserver));
                        assertTrue(pair.silent.attachOutputObserver(silentObserver));
                    }
                    case 6 -> {
                        var scalarState = pair.scalar.captureState();
                        var silentState = pair.silent.captureState();
                        pair.scalar.restoreState(scalarState);
                        pair.silent.restoreState(silentState);
                        assertFalse(pair.silent.isPerformanceSystemMutedAudioCalendarEnabled());
                    }
                    default -> throw new AssertionError();
                }
                assertExact(pair, "interruption=" + interruption);
            }
        }
    }

    @Test
    public void explicitDisableWithLiveDebtMaterializesExactlyAndTurnsCalendarOff() throws Exception {
        try (SoundPair pair = pair(true)) {
            advanceScalar(pair.scalar, 8192);
            pair.silent.tickPerformanceQuietSpan(8192);
            pair.silent.setPerformanceSystemMutedAudioCalendar(false);
            assertFalse(pair.silent.isPerformanceSystemMutedAudioCalendarEnabled());
            assertExact(pair, "disable with live debt");
        }
    }

    @Test
    public void foreignStateRestoreClearsLiveCalendarDebtAndTransientFlag() throws Exception {
        try (SoundPair pair = pair(true)) {
            advanceScalar(pair.scalar, 137);
            var foreignState = pair.scalar.captureState();
            pair.silent.tickPerformanceQuietSpan(8192);
            pair.silent.restoreState(foreignState);
            assertFalse(pair.silent.isPerformanceSystemMutedAudioCalendarEnabled());
            assertEquals(canonicalDigest(foreignState),
                    canonicalDigest(pair.silent.captureState()));
            assertEquals(component(foreignState, "i"),
                    component(pair.silent.captureState(), "i"));
        }
    }

    @Test
    public void nr52OffOnTransitionMaterializesDebtAcrossDisabledApu() throws Exception {
        try (SoundPair pair = pair(true)) {
            advanceScalar(pair.scalar, 211);
            pair.silent.tickPerformanceQuietSpan(211);
            pair.scalar.setByte(0xff26, 0x00);
            pair.silent.setByte(0xff26, 0x00);
            advanceScalar(pair.scalar, 73);
            pair.silent.tickPerformanceQuietSpan(73);
            pair.scalar.setByte(0xff26, 0x80);
            pair.silent.setByte(0xff26, 0x80);
            advanceScalar(pair.scalar, 149);
            pair.silent.tickPerformanceQuietSpan(149);
            pair.silent.materializePendingPerformanceTicks();
            assertExact(pair, "NR52 off/on");
        }
    }

    @Test
    public void dmgSweepExpiryAndNonOverflowDelayedCalculationOffsetsStayExact() throws Exception {
        for (int offset = 1; offset <= 40; offset++) {
            try (SoundPair pair = pair(false)) {
                Object scalarSweep = field(field(pair.scalar, "mode1"), "frequencySweep");
                Object silentSweep = field(field(pair.silent, "mode1"), "frequencySweep");
                setIntField(scalarSweep, "calculationDelay", offset);
                setIntField(silentSweep, "calculationDelay", offset);
                setBooleanField(scalarSweep, "unshiftedCalculation", true);
                setBooleanField(silentSweep, "unshiftedCalculation", true);
                int debt = offset + 13;
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "DMG sweep expiry offset=" + offset);
            }
        }

        for (int offset = 1; offset <= 40; offset++) {
            try (SoundPair pair = pair(false)) {
                Object scalarSweep = field(field(pair.scalar, "mode1"), "frequencySweep");
                Object silentSweep = field(field(pair.silent, "mode1"), "frequencySweep");
                setIntField(scalarSweep, "calculationDelay", offset);
                setIntField(silentSweep, "calculationDelay", offset);
                setBooleanField(scalarSweep, "unshiftedCalculation", true);
                setBooleanField(silentSweep, "unshiftedCalculation", true);
                int debt = Math.max(1, offset - 1);
                advanceScalar(pair.scalar, debt);
                pair.silent.tickPerformanceQuietSpan(debt);
                pair.silent.materializePendingPerformanceTicks();
                assertExact(pair, "DMG non-overflow offset=" + offset);
            }
        }
    }

    private static void advanceScalar(Sound sound, int ticks) {
        for (int i = 0; i < ticks; i++) {
            sound.tick(false);
        }
    }

    private static void commitFrameSequencer(SoundPair pair) throws Exception {
        pair.scalarTimer.presetDiv(0x1000);
        pair.silentTimer.presetDiv(0x1000);
        pair.scalar.tickFrameSequencer(false);
        pair.silent.tickFrameSequencer(false);
        pair.scalarTimer.presetDiv(0);
        pair.silentTimer.presetDiv(0);
        pair.scalar.tickFrameSequencer(false);
        pair.silent.tickFrameSequencer(false);
        pair.scalar.commitFrameSequencerClock();
        pair.silent.commitFrameSequencerClock();
    }

    private static SoundPair pair(boolean gbc) throws Exception {
        Sound scalar = configured(gbc);
        Sound silent = configured(gbc);
        EventBusImpl scalarBus = new EventBusImpl(null, null, false);
        EventBusImpl silentBus = new EventBusImpl(null, null, false);
        List<int[]> scalarEvents = new ArrayList<>();
        List<int[]> silentEvents = new ArrayList<>();
        scalarBus.register(event -> scalarEvents.add(event.buffer().clone()), Sound.SoundSampleEvent.class);
        silentBus.register(event -> silentEvents.add(event.buffer().clone()), Sound.SoundSampleEvent.class);
        scalar.init(scalarBus);
        silent.init(silentBus);
        silent.setPerformanceSystemMutedAudioCalendar(true);
        silent.resetPerformanceSystemMutedAudioCalendarCounters();
        return new SoundPair(scalar, silent, timerOf(scalar), timerOf(silent), scalarBus, silentBus,
                scalarEvents, silentEvents);
    }

    private static void assertExact(SoundPair pair, String label) throws Exception {
        Object scalarState = pair.scalar.captureState();
        Object silentState = pair.silent.captureState();
        assertEquals(label, canonicalDigest(scalarState), canonicalDigest(silentState));
        assertEquals(label + " sample phase", component(scalarState, "performanceSamplePhase"),
                component(silentState, "performanceSamplePhase"));
        assertEquals(label + " buffer index", component(scalarState, "i"), component(silentState, "i"));
        assertEquals(label + " event count", pair.scalarEvents.size(), pair.silentEvents.size());
        for (int[] event : pair.silentEvents) {
            assertAllZero(event);
        }
        assertAllZero((int[]) component(silentState, "buffer"));
    }

    private record SoundPair(Sound scalar, Sound silent, Timer scalarTimer, Timer silentTimer,
                             EventBusImpl scalarBus, EventBusImpl silentBus,
                             List<int[]> scalarEvents, List<int[]> silentEvents)
            implements AutoCloseable {
        @Override
        public void close() {
            scalarBus.close();
            silentBus.close();
        }
    }

    private static Sound newSound() {
        return newSound(ExecutionMode.PERFORMANCE);
    }

    private static Sound newSound(ExecutionMode mode) {
        SpeedMode speedMode = new SpeedMode(true);
        Timer timer = new Timer(new InterruptManager(true), speedMode);
        return new Sound(timer, speedMode, true, new ClockSpec(55, 1, 1), mode);
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

    private static Timer timerOf(Sound sound) {
        try {
            var field = Sound.class.getDeclaredField("timer");
            field.setAccessible(true);
            return (Timer) field.get(sound);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object field(Object receiver, String name) throws Exception {
        var field = receiver instanceof Sound
                ? Sound.class.getDeclaredField(name)
                : receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static void setIntField(Object receiver, String name, int value) throws Exception {
        var field = receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(receiver, value);
    }

    private static void setBooleanField(Object receiver, String name, boolean value)
            throws Exception {
        var field = receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(receiver, value);
    }

    private static Object component(Object record, String name) throws Exception {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                component.getAccessor().trySetAccessible();
                return component.getAccessor().invoke(record);
            }
        }
        throw new AssertionError("missing component " + name + " in " + record.getClass());
    }

    private static long canonicalDigest(Object state) throws Exception {
        Digest digest = new Digest();
        visit(state, digest, true);
        return digest.value;
    }

    private static void visit(Object value, Digest digest, boolean root)
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
                    visit(element, digest, false);
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
            throw new AssertionError("unexpected state type " + type.getName());
        }
        digest.mix(type.getName().hashCode());
        for (RecordComponent component : type.getRecordComponents()) {
            if (root && type.getSimpleName().equals("SoundState")
                    && component.getName().equals("buffer")) {
                continue;
            }
            digest.mix(component.getName().hashCode());
            component.getAccessor().trySetAccessible();
            visit(component.getAccessor().invoke(value), digest, false);
        }
    }

    private static final class Digest {
        private long value = 0xcbf29ce484222325L;

        private void mix(long next) {
            value ^= next + 0x9e3779b97f4a7c15L + (value << 6) + (value >>> 2);
        }
    }

    private static void assertAllZero(int[] buffer) {
        assertTrue("silent PCM event must contain only zeros", Arrays.stream(buffer)
                .allMatch(value -> value == 0));
    }
}
