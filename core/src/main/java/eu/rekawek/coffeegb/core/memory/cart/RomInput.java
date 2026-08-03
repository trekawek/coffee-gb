package eu.rekawek.coffeegb.core.memory.cart;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * One user-selected ROM input that can be opened without resolving it to a filesystem path.
 *
 * <p>The caller owns the host permission (for example, a desktop file handle or Android SAF
 * grant). Coffee GB opens the stream once while creating a bounded {@link RomSourceSnapshot};
 * implementations therefore need not support reopening the source after that call.
 */
public interface RomInput {

    /** Human-readable source name used only for type selection and presentation. */
    String displayName();

    /** Opens the exact bytes selected by the user. */
    InputStream openStream() throws IOException;

    /**
     * Declared byte length when the host can provide it, or {@code -1} when unknown.
     *
     * <p>The loader still enforces its own observed-byte bounds, so this value is advisory.
     */
    default long declaredSize() {
        return -1L;
    }

    /** Simple one-shot adapter for hosts that already own a stream-opening function. */
    static RomInput of(String displayName, StreamOpener opener) {
        String name = Objects.requireNonNull(displayName, "displayName");
        StreamOpener checkedOpener = Objects.requireNonNull(opener, "opener");
        if (name.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return new RomInput() {
            @Override
            public String displayName() {
                return name;
            }

            @Override
            public InputStream openStream() throws IOException {
                return checkedOpener.open();
            }
        };
    }

    @FunctionalInterface
    interface StreamOpener {
        InputStream open() throws IOException;
    }
}
