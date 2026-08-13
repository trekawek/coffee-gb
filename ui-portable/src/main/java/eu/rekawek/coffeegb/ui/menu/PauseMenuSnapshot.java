package eu.rekawek.coffeegb.ui.menu;

import java.util.Objects;

/**
 * Immutable, detached data captured once while opening the root pause menu.
 *
 * <p>Hosts deliberately keep this object while the user visits child pages.  It prevents a menu
 * render from sampling a live emulator, and makes the displayed title, play time, battery policy,
 * and frame belong to the same opening instant.
 */
public final class PauseMenuSnapshot {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_SECONDS = 999L * 60L * 60L + 59L * 60L + 59L;

    private final String romTitle;
    private final long playTimeNanos;
    private final boolean batterySaveActive;
    private final MenuPreview preview;

    public PauseMenuSnapshot(String romTitle, long playTimeNanos, boolean batterySaveActive,
            MenuPreview preview) {
        this.romTitle = Objects.requireNonNull(romTitle, "romTitle");
        this.playTimeNanos = Math.max(0L, playTimeNanos);
        this.batterySaveActive = batterySaveActive;
        this.preview = Objects.requireNonNull(preview, "preview");
    }

    public String romTitle() {
        return romTitle;
    }

    public long playTimeNanos() {
        return playTimeNanos;
    }

    public boolean batterySaveActive() {
        return batterySaveActive;
    }

    public MenuPreview preview() {
        return preview;
    }

    /** Formats the bounded elapsed value shown in the pause menu. */
    public String formattedPlayTime() {
        return formatPlayTime(playTimeNanos);
    }

    public static String formatPlayTime(long elapsedNanos) {
        long seconds = Math.max(0L, elapsedNanos) / NANOS_PER_SECOND;
        seconds = Math.min(MAX_SECONDS, seconds);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        if (hours == 0L) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, remainder);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
    }
}
