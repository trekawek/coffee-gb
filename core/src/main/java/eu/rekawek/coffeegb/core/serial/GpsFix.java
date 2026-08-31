package eu.rekawek.coffeegb.core.serial;

/** Immutable host-provided GPS observation used by the optional GPS Boy receiver. */
public record GpsFix(long timestampMillis, double latitudeDegrees, double longitudeDegrees,
                     double altitudeMeters, double speedMetersPerSecond,
                     double bearingDegrees, double verticalSpeedMetersPerSecond) {

    public static GpsFix unavailable(long timestampMillis) {
        return new GpsFix(timestampMillis, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN);
    }

    public boolean hasPosition() {
        return timestampMillis > 0
                && Double.isFinite(latitudeDegrees)
                && latitudeDegrees >= -90.0
                && latitudeDegrees <= 90.0
                && Double.isFinite(longitudeDegrees)
                && longitudeDegrees >= -180.0
                && longitudeDegrees <= 180.0;
    }

    public boolean hasAltitude() {
        return Double.isFinite(altitudeMeters);
    }

    public boolean hasSpeed() {
        return Double.isFinite(speedMetersPerSecond) && speedMetersPerSecond >= 0.0;
    }

    public boolean hasBearing() {
        return Double.isFinite(bearingDegrees);
    }

    public boolean hasVerticalSpeed() {
        return Double.isFinite(verticalSpeedMetersPerSecond);
    }
}
