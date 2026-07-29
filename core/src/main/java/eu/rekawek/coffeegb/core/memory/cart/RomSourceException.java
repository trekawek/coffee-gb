package eu.rekawek.coffeegb.core.memory.cart;

import java.io.IOException;

/** Typed failure raised while snapshotting or inspecting an untrusted desktop ROM source. */
public final class RomSourceException extends IOException {

    public enum Reason {
        MISSING,
        NOT_A_FILE,
        UNSUPPORTED_TYPE,
        UNREADABLE,
        ROM_TOO_LARGE,
        CONTAINER_TOO_LARGE,
        INVALID_ARCHIVE,
        UNSAFE_ARCHIVE_ENTRY,
        NO_ROM_CANDIDATES,
        INVALID_HEADER,
        INVALID_SELECTION
    }

    private final Reason reason;

    public RomSourceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RomSourceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
