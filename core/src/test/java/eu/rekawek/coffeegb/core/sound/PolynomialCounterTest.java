package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/** Differential coverage for the closed-form polynomial-counter quiet-span advance. */
public final class PolynomialCounterTest {

    private static final int[] EDGE_COUNTERS = {0, 1, 0x1fff, 0x2000, 0x3ffe, 0x3fff};

    private static final Field NR43 = field("nr43");
    private static final Field COUNTER = field("counter");
    private static final Field COUNTDOWN = field("counterCountdown");
    private static final Field CLOCK_2_MHZ = field("clock2Mhz");
    private static final Field ALIGNMENT = field("alignment");
    private static final Field BACKGROUND_ACTIVE = field("backgroundActive");
    private static final Field COUNTDOWN_RELOADED = field("countdownReloaded");

    @Test
    public void allNr43FieldsAndCountdownBoundariesMatchScalarForLongSpan() throws Exception {
        for (boolean clock2Mhz : new boolean[]{false, true}) {
            for (int alignment = 0; alignment < 4; alignment++) {
                for (int nr43 = 0; nr43 <= 0xff; nr43++) {
                    int reload = reload(nr43);
                    int[] countdowns = {1, reload - 1, reload, reload + 1,
                            reload * 2, reload * 2 + 1};
                    for (int counter : EDGE_COUNTERS) {
                        for (int countdown : countdowns) {
                            for (boolean countdownReloaded : new boolean[]{false, true}) {
                                assertMatchesScalar("phase=" + clock2Mhz
                                                + ", alignment=" + alignment
                                                + ", nr43=" + Integer.toHexString(nr43)
                                                + ", counter=" + counter
                                                + ", countdown=" + countdown
                                                + ", reloaded=" + countdownReloaded,
                                        state(nr43, counter, countdown, clock2Mhz, alignment,
                                                true, countdownReloaded),
                                        54);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void everyTickLengthZeroThrough54MatchesScalarAcrossBothClockPhases() throws Exception {
        for (boolean clock2Mhz : new boolean[]{false, true}) {
            for (int alignment = 0; alignment < 4; alignment++) {
                for (int nr43 = 0; nr43 <= 0xff; nr43++) {
                    int reload = reload(nr43);
                    int[] countdowns = {1, reload, reload + 1};
                    for (int counter : new int[]{0, 0x3fff}) {
                        for (int countdown : countdowns) {
                            PolynomialCounter scalar = state(nr43, counter, countdown,
                                    clock2Mhz, alignment, true, false);
                            ComponentState<PolynomialCounter> initial = scalar.captureState();
                            PolynomialCounter bulk = new PolynomialCounter();
                            for (int ticks = 0; ticks <= 54; ticks++) {
                                scalar.restoreState(initial);
                                bulk.restoreState(initial);
                                int expected = advanceScalar(scalar, ticks);
                                int actual = bulk.advancePerformanceSpan(ticks);
                                assertEquals("phase=" + clock2Mhz + ", alignment=" + alignment
                                                + ", nr43=" + Integer.toHexString(nr43)
                                                + ", counter=" + counter + ", countdown="
                                                + countdown + ", ticks=" + ticks,
                                        expected, actual);
                                assertEquals("state phase=" + clock2Mhz + ", alignment="
                                                + alignment + ", nr43=" + Integer.toHexString(nr43)
                                                + ", counter=" + counter + ", countdown="
                                                + countdown + ", ticks=" + ticks,
                                        scalar.captureState(), bulk.captureState());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void inactiveAndZeroEdgeSpansRetainScalarState() throws Exception {
        for (boolean clock2Mhz : new boolean[]{false, true}) {
            for (int alignment = 0; alignment < 4; alignment++) {
                PolynomialCounter scalar = state(0x8f, 0x3fff, 0, clock2Mhz, alignment,
                        false, true);
                ComponentState<PolynomialCounter> initial = scalar.captureState();
                PolynomialCounter bulk = new PolynomialCounter();
                for (int ticks = 0; ticks <= 54; ticks++) {
                    scalar.restoreState(initial);
                    bulk.restoreState(initial);
                    int expected = advanceScalar(scalar, ticks);
                    int actual = bulk.advancePerformanceSpan(ticks);
                    assertEquals("inactive phase=" + clock2Mhz + ", alignment=" + alignment
                                    + ", ticks=" + ticks, expected, actual);
                    assertEquals("inactive state phase=" + clock2Mhz + ", alignment="
                                    + alignment + ", ticks=" + ticks,
                            scalar.captureState(), bulk.captureState());
                }
            }
        }
    }

    @Test
    public void nr43WriteAtReloadAndRestoreContinueExactlyAsScalar() throws Exception {
        for (int alignment = 0; alignment < 4; alignment++) {
            for (int oldNr43 = 0; oldNr43 <= 0xff; oldNr43 += 17) {
                PolynomialCounter scalar = state(oldNr43, 0x3ffe, reload(oldNr43), false,
                        alignment, true, true);
                PolynomialCounter bulk = state(oldNr43, 0x3ffe, reload(oldNr43), false,
                        alignment, true, true);

                int newNr43 = (oldNr43 * 29 + 0x53) & 0xff;
                scalar.setNr43(newNr43);
                bulk.setNr43(newNr43);
                ComponentState<PolynomialCounter> checkpoint = scalar.captureState();
                assertEquals("NR43 write alignment=" + alignment + ", old="
                                + Integer.toHexString(oldNr43),
                        scalar.captureState(), bulk.captureState());

                for (int ticks = 0; ticks <= 54; ticks++) {
                    scalar.restoreState(checkpoint);
                    bulk.restoreState(checkpoint);
                    int expected = advanceScalar(scalar, ticks);
                    int actual = bulk.advancePerformanceSpan(ticks);
                    assertEquals("NR43 write alignment=" + alignment + ", old="
                                    + Integer.toHexString(oldNr43) + ", new="
                                    + Integer.toHexString(newNr43) + ", ticks=" + ticks,
                            expected, actual);
                    assertEquals("NR43 state alignment=" + alignment + ", old="
                                    + Integer.toHexString(oldNr43) + ", new="
                                    + Integer.toHexString(newNr43) + ", ticks=" + ticks,
                            scalar.captureState(), bulk.captureState());
                }
            }
        }
    }

    private static PolynomialCounter state(int nr43, int counter, int countdown,
                                           boolean clock2Mhz, int alignment,
                                           boolean backgroundActive,
                                           boolean countdownReloaded) throws Exception {
        PolynomialCounter result = new PolynomialCounter();
        result.start();
        NR43.setInt(result, nr43);
        COUNTER.setInt(result, counter);
        COUNTDOWN.setInt(result, countdown);
        CLOCK_2_MHZ.setBoolean(result, clock2Mhz);
        ALIGNMENT.setInt(result, alignment);
        BACKGROUND_ACTIVE.setBoolean(result, backgroundActive);
        COUNTDOWN_RELOADED.setBoolean(result, countdownReloaded);
        return result;
    }

    private static int advanceScalar(PolynomialCounter counter, int ticks) {
        int steps = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (counter.tick()) {
                steps++;
            }
        }
        return steps;
    }

    private static void assertMatchesScalar(String label, PolynomialCounter scalar,
                                             int ticks) {
        ComponentState<PolynomialCounter> initial = scalar.captureState();
        PolynomialCounter bulk = new PolynomialCounter();
        bulk.restoreState(initial);
        int expected = advanceScalar(scalar, ticks);
        int actual = bulk.advancePerformanceSpan(ticks);
        assertEquals(label + " steps", expected, actual);
        assertEquals(label + " state", scalar.captureState(), bulk.captureState());
    }

    private static int reload(int nr43) {
        int reload = (nr43 & 0b111) << 2;
        return reload == 0 ? 2 : reload;
    }

    private static Field field(String name) {
        try {
            Field field = PolynomialCounter.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
