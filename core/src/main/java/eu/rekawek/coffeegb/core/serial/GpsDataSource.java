package eu.rekawek.coffeegb.core.serial;

/** Host data seam for a live GPS Boy receiver; implementations must be thread-safe. */
public interface GpsDataSource {

    GpsFix currentFix();

    /** Current UTC-compatible Unix time used for the receiver's live time/date response. */
    default long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
