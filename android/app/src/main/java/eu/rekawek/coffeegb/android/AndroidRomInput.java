package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import eu.rekawek.coffeegb.core.memory.cart.RomInput;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Android Storage Access Framework adapter. It deliberately exposes no filesystem path. */
final class AndroidRomInput implements RomInput {

    private final ContentResolver resolver;
    private final Uri uri;
    private final String displayName;
    private final long declaredSize;

    AndroidRomInput(ContentResolver resolver, Uri uri) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.uri = Objects.requireNonNull(uri, "uri");
        Metadata metadata = metadata(resolver, uri);
        this.displayName = metadata.displayName;
        this.declaredSize = metadata.size;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public InputStream openStream() throws IOException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) {
            throw new FileNotFoundException("The selected document is no longer available");
        }
        return stream;
    }

    @Override
    public long declaredSize() {
        return declaredSize;
    }

    @Override
    public String toString() {
        return "AndroidRomInput(<redacted>)";
    }

    private static Metadata metadata(ContentResolver resolver, Uri uri) {
        String fallback = uri.getLastPathSegment();
        String name = fallback == null || fallback.isBlank() ? "selected-rom.gb" : fallback;
        long size = -1L;
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    String supplied = cursor.getString(nameColumn);
                    if (supplied != null && !supplied.isBlank()) {
                        name = supplied;
                    }
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        } catch (RuntimeException ignored) {
            // Metadata is advisory. The bounded stream reader remains authoritative.
        }
        return new Metadata(name, size);
    }

    private record Metadata(String displayName, long size) {}
}
