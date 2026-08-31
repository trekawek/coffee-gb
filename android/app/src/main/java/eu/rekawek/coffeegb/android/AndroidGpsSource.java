package eu.rekawek.coffeegb.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import eu.rekawek.coffeegb.core.serial.GpsDataSource;
import eu.rekawek.coffeegb.core.serial.GpsFix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe bridge from Android location providers to the emulated GPS receiver. */
final class AndroidGpsSource implements GpsDataSource, AutoCloseable {

    private static final long UPDATE_INTERVAL_MILLIS = 1_000L;
    private static final long OPTIONAL_FIELD_REUSE_MILLIS = 10_000L;
    private static final double MIN_VERTICAL_SPEED_INTERVAL_SECONDS = 0.25;
    private static final double MAX_VERTICAL_SPEED_INTERVAL_SECONDS = 30.0;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<GpsFix> latest =
            new AtomicReference<>(GpsFix.unavailable(System.currentTimeMillis()));
    private final AtomicBoolean enabled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            accept(location);
        }

        @Override
        public void onProviderEnabled(String provider) {
            // Updates resume automatically for an already registered listener.
        }

        @Override
        public void onProviderDisabled(String provider) {
            // Preserve the last real fix until the core marks it stale.
        }

        @Deprecated
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Required by the API 26 listener contract; provider callbacks carry the data.
        }
    };

    /** Accessed only on the main looper. */
    private boolean registered;

    AndroidGpsSource(Context context) {
        this.context = context.getApplicationContext();
        locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    void setEnabled(boolean value) {
        enabled.set(value);
        runOnMain(this::synchronizeRegistration);
    }

    @Override
    public GpsFix currentFix() {
        if (!enabled.get() || closed.get() || !hasLocationPermission()) {
            return GpsFix.unavailable(System.currentTimeMillis());
        }
        return latest.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            enabled.set(false);
            runOnMain(this::synchronizeRegistration);
        }
    }

    private void synchronizeRegistration() {
        boolean shouldRegister = enabled.get() && !closed.get() && hasLocationPermission()
                && locationManager != null;
        if (!shouldRegister) {
            if (registered) {
                try {
                    locationManager.removeUpdates(listener);
                } catch (RuntimeException ignored) {
                    // The source is already logically disabled; platform cleanup is best effort.
                }
                registered = false;
            }
            latest.set(GpsFix.unavailable(System.currentTimeMillis()));
            return;
        }
        if (registered) {
            return;
        }

        boolean anyProvider = false;
        for (String provider : requestedProviders()) {
            try {
                locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MILLIS, 0.0f,
                        listener, Looper.getMainLooper());
                anyProvider = true;
                Location lastKnown = locationManager.getLastKnownLocation(provider);
                if (lastKnown != null) {
                    accept(lastKnown);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // A permission or provider can disappear between discovery and registration.
            }
        }
        registered = anyProvider;
    }

    private List<String> requestedProviders() {
        List<String> available;
        try {
            available = locationManager.getAllProviders();
        } catch (RuntimeException ignored) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>(3);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && available.contains(LocationManager.FUSED_PROVIDER)) {
            result.add(LocationManager.FUSED_PROVIDER);
        }
        if (available.contains(LocationManager.GPS_PROVIDER)) {
            result.add(LocationManager.GPS_PROVIDER);
        }
        if (available.contains(LocationManager.NETWORK_PROVIDER)) {
            result.add(LocationManager.NETWORK_PROVIDER);
        }
        return result;
    }

    private void accept(Location location) {
        if (location == null || !enabled.get() || closed.get()) {
            return;
        }
        long timestamp = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        GpsFix previous = latest.get();
        if (previous.hasPosition() && timestamp < previous.timestampMillis()) {
            return;
        }
        boolean reuseOptionalFields = previous.hasPosition()
                && timestamp - previous.timestampMillis() <= OPTIONAL_FIELD_REUSE_MILLIS;
        boolean locationHasAltitude = hasAltitude(location);
        double locationAltitude = locationHasAltitude ? altitudeMeters(location) : Double.NaN;
        double altitude = locationHasAltitude ? locationAltitude
                : reuseOptionalFields ? previous.altitudeMeters() : Double.NaN;
        double speed = location.hasSpeed() ? location.getSpeed()
                : reuseOptionalFields ? previous.speedMetersPerSecond() : Double.NaN;
        double bearing = location.hasBearing() ? location.getBearing()
                : reuseOptionalFields ? previous.bearingDegrees() : Double.NaN;
        double verticalSpeed = verticalSpeed(locationHasAltitude, locationAltitude, timestamp,
                previous);
        if (!Double.isFinite(verticalSpeed) && reuseOptionalFields) {
            verticalSpeed = previous.verticalSpeedMetersPerSecond();
        }
        latest.set(new GpsFix(timestamp, location.getLatitude(), location.getLongitude(), altitude,
                speed, bearing, verticalSpeed));
    }

    private static double verticalSpeed(boolean hasAltitude, double altitudeMeters, long timestamp,
            GpsFix previous) {
        if (!hasAltitude || !previous.hasAltitude()) {
            return Double.NaN;
        }
        double elapsedSeconds = (timestamp - previous.timestampMillis()) / 1_000.0;
        if (elapsedSeconds < MIN_VERTICAL_SPEED_INTERVAL_SECONDS
                || elapsedSeconds > MAX_VERTICAL_SPEED_INTERVAL_SECONDS) {
            return Double.NaN;
        }
        return (altitudeMeters - previous.altitudeMeters()) / elapsedSeconds;
    }

    private static boolean hasAltitude(Location location) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? location.hasMslAltitude() || location.hasAltitude()
                : location.hasAltitude();
    }

    private static double altitudeMeters(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && location.hasMslAltitude()) {
            // TAIP AL altitude is height above mean sea level; Android's legacy altitude is
            // height above the WGS-84 ellipsoid. Prefer the platform's corrected MSL value.
            return location.getMslAltitudeMeters();
        }
        return location.getAltitude();
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}
