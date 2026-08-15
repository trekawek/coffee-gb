package eu.rekawek.coffeegb.core.signal;

/**
 * Test-only primitive that detects edges between a committed input level and the level resolved
 * for the current clock.
 */
public final class EdgeDetector {

    private boolean previous;

    private boolean resolved;

    public EdgeDetector(boolean initialLevel) {
        previous = initialLevel;
        resolved = initialLevel;
    }

    /** Samples a resolved wire without advancing the remembered level. */
    public void resolve(boolean level) {
        resolved = level;
    }

    public boolean rising() {
        return !previous && resolved;
    }

    public boolean falling() {
        return previous && !resolved;
    }

    public boolean previousLevel() {
        return previous;
    }

    public boolean resolvedLevel() {
        return resolved;
    }

    public void commit() {
        previous = resolved;
    }

    /** Restores portable state at a clock boundary. */
    public void restore(boolean level) {
        previous = level;
        resolved = level;
    }
}
