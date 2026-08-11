package eu.rekawek.coffeegb.android;

import java.util.Objects;

/** Pure persisted state machine for a deferred full-resolution printer share. */
final class PrinterExportContinuation {

    enum Phase {
        NONE,
        PENDING,
        READY,
        FAILED
    }

    private static final PrinterExportContinuation NONE =
            new PrinterExportContinuation(0L, "", Phase.NONE);

    private final long token;
    private final String uri;
    private final Phase phase;

    private PrinterExportContinuation(long token, String uri, Phase phase) {
        this.token = Math.max(0L, token);
        this.uri = Objects.requireNonNull(uri, "uri");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (phase != Phase.NONE && (this.token == 0L || uri.isEmpty())) {
            throw new IllegalArgumentException("Active continuation needs a token and URI");
        }
    }

    static PrinterExportContinuation none() {
        return NONE;
    }

    static PrinterExportContinuation pending(long token, String uri) {
        return new PrinterExportContinuation(token, uri, Phase.PENDING);
    }

    static PrinterExportContinuation restored(long token, String uri, String phaseName) {
        try {
            Phase phase = Phase.valueOf(phaseName);
            return phase == Phase.NONE ? none()
                    : new PrinterExportContinuation(token, uri, phase);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return none();
        }
    }

    PrinterExportContinuation complete(long completedToken, boolean successful) {
        if (phase != Phase.PENDING || completedToken != token) {
            return this;
        }
        return new PrinterExportContinuation(token, uri,
                successful ? Phase.READY : Phase.FAILED);
    }

    long token() {
        return token;
    }

    String uri() {
        return uri;
    }

    Phase phase() {
        return phase;
    }

    boolean actionable() {
        return phase == Phase.READY || phase == Phase.FAILED;
    }
}
