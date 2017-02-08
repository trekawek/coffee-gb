package eu.rekawek.coffeegb.memory.cart.rtc;

import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RealTimeClockTest {

    private RealTimeClock rtc;

    private VirtualClock clock;

    @Before
    public void setup() {
        clock = new VirtualClock();
        rtc = new RealTimeClock(clock);
    }

    @Test
    public void testBasicGet() {
        forward(5, 8, 12, 2);
        assertClockEquals(5, 8, 12, 2);
    }

    @Test
    public void testLatch() {
        forward(5, 8, 12, 2);

        rtc.latch();
        forward(10, 5, 19, 4);
        assertClockEquals(5, 8, 12, 2);
        rtc.unlatch();

        assertClockEquals(5 + 10, 8 + 5, 12 + 19, 2 + 4);
    }

    @Test
    public void testCounterOverflow() {
        forward(511, 23, 59, 59);
        assertFalse(rtc.isCounterOverflow());

        clock.forward(1, TimeUnit.SECONDS);
        assertClockEquals(0, 0, 0 ,0);
        assertTrue(rtc.isCounterOverflow());

        forward(10, 5, 19, 4);
        assertClockEquals(10, 5, 19, 4);
        assertTrue(rtc.isCounterOverflow());

        rtc.clearCounterOverflow();
        assertClockEquals(10, 5, 19, 4);
        assertFalse(rtc.isCounterOverflow());
    }

    @Test
    public void setClock() {
        forward(10, 5, 19, 4);
        assertClockEquals(10, 5, 19, 4);

        rtc.setHalt(true);
        assertTrue(rtc.isHalt());

        rtc.setDayCounter(10);
        rtc.setHours(16);
        rtc.setMinutes(21);
        rtc.setSeconds(32);
        forward(1, 1, 1, 1); // should be ignored after unhalt
        rtc.setHalt(false);

        assertFalse(rtc.isHalt());
        assertClockEquals(10, 16, 21, 32);
        forward(2, 2, 2, 2);
        assertClockEquals(12, 18, 23, 34);
    }

    private void forward(int days, int hours, int minutes, int seconds) {
        clock.forward(days, TimeUnit.DAYS);
        clock.forward(hours, TimeUnit.HOURS);
        clock.forward(minutes, TimeUnit.MINUTES);
        clock.forward(seconds, TimeUnit.SECONDS);
    }

    private void assertClockEquals(int days, int hours, int minutes, int seconds) {
        assertEquals("days", days, rtc.getDayCounter());
        assertEquals("hours", hours, rtc.getHours());
        assertEquals("minutes", minutes, rtc.getMinutes());
        assertEquals("seconds", seconds, rtc.getSeconds());
    }
}
