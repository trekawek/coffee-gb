package eu.rekawek.coffeegb.android;

import android.content.pm.ActivityInfo;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TiltOrientationLockTest {

    @Test
    public void locksOnceAndRestoresThePreviousActivityPolicy() {
        FakeHost host = new FakeHost(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        TiltOrientationLock lock = new TiltOrientationLock(host);

        lock.setActive(true);
        lock.setActive(true);
        lock.setActive(false);
        lock.setActive(false);

        assertEquals(List.of(
                ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE), host.requests);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                host.requestedOrientation());
    }

    @Test
    public void aLaterSessionCapturesItsOwnRestoreOrientation() {
        FakeHost host = new FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        TiltOrientationLock lock = new TiltOrientationLock(host);

        lock.setActive(true);
        lock.setActive(false);
        host.orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        lock.setActive(true);
        lock.setActive(false);

        assertEquals(List.of(
                ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT), host.requests);
    }

    private static final class FakeHost implements TiltOrientationLock.Host {
        private final List<Integer> requests = new ArrayList<>();
        private int orientation;

        private FakeHost(int orientation) {
            this.orientation = orientation;
        }

        @Override
        public int requestedOrientation() {
            return orientation;
        }

        @Override
        public void requestOrientation(int orientation) {
            requests.add(orientation);
            this.orientation = orientation;
        }
    }
}
