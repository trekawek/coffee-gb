package eu.rekawek.coffeegb.android;

import android.content.pm.ActivityInfo;

import java.util.Objects;

/** Keeps the current display orientation stable for the lifetime of an active tilt session. */
final class TiltOrientationLock {

    interface Host {
        int requestedOrientation();

        void requestOrientation(int orientation);
    }

    private final Host host;
    private boolean active;
    private int restoreOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    TiltOrientationLock(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        if (active) {
            int previous = host.requestedOrientation();
            host.requestOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            restoreOrientation = previous;
        } else {
            host.requestOrientation(restoreOrientation);
        }
        this.active = active;
    }
}
