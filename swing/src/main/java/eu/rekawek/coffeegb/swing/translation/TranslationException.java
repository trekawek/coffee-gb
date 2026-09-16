package eu.rekawek.coffeegb.swing.translation;

/** A user-facing failure that never includes an API response, screenshot, or credential. */
public final class TranslationException extends RuntimeException {

    public enum Kind {
        MISSING_API_KEY,
        AUTHENTICATION,
        RATE_LIMIT,
        TIMEOUT,
        REFUSED,
        INCOMPLETE,
        MALFORMED_RESPONSE,
        NETWORK,
        SERVICE,
        CONFIGURATION
    }

    private final Kind kind;

    public TranslationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
