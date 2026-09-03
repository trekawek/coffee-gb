package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Bounded, presentation-neutral ROM directory listing backed by Android's Storage Access
 * Framework.
 *
 * <p>The caller owns the ancestor stack. Document IDs are provider-defined opaque values, so this
 * class never attempts to derive a parent URI or turn a document into a filesystem path.</p>
 */
final class AndroidRomDocumentBrowser {

    static final int DEFAULT_MAX_ENTRIES = 4_096;
    private static final int SCAN_BUDGET_MULTIPLIER = 8;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;
    private static final String DIRECTORY_MIME_TYPE =
            DocumentsContract.Document.MIME_TYPE_DIR;
    private static final String[] CHILD_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    private static final String[] NAME_PROJECTION = {
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
    };
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparingInt((Entry entry) -> entry.kind() == EntryKind.DIRECTORY ? 0 : 1)
            .thenComparing(entry -> entry.label().toLowerCase(Locale.ROOT))
            .thenComparing(Entry::label)
            .thenComparing(entry -> entry.uri().toString());

    private final ContentResolver resolver;
    private final int maxEntries;
    private final int maxScannedRows;

    AndroidRomDocumentBrowser(ContentResolver resolver) {
        this(resolver, DEFAULT_MAX_ENTRIES);
    }

    AndroidRomDocumentBrowser(ContentResolver resolver, int maxEntries) {
        this(resolver, maxEntries, scanBudget(maxEntries));
    }

    AndroidRomDocumentBrowser(ContentResolver resolver, int maxEntries, int maxScannedRows) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxScannedRows < maxEntries) {
            throw new IllegalArgumentException("maxScannedRows must cover maxEntries");
        }
        this.maxEntries = maxEntries;
        this.maxScannedRows = maxScannedRows;
    }

    /** Builds the root document URI represented by a granted tree URI. */
    static Uri rootDocument(Uri treeUri) {
        Objects.requireNonNull(treeUri, "treeUri");
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw new IllegalArgumentException("A document tree URI is required");
        }
        return DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    /**
     * Queries one directory. This may cross a provider process and must be called off the UI
     * thread. Failures are deliberately redacted so provider details and document IDs never enter
     * the on-screen menu.
     */
    Listing list(Uri treeUri, Uri directoryUri, CancellationSignal cancellationSignal) {
        Objects.requireNonNull(treeUri, "treeUri");
        Objects.requireNonNull(directoryUri, "directoryUri");
        try {
            if (!DocumentsContract.isTreeUri(treeUri)
                    || !DocumentsContract.isTreeUri(directoryUri)
                    || !Objects.equals(treeUri.getAuthority(), directoryUri.getAuthority())
                    || !DocumentsContract.getTreeDocumentId(treeUri).equals(
                            DocumentsContract.getTreeDocumentId(directoryUri))) {
                return Listing.failed("ROM FOLDER");
            }
            String label = displayName(directoryUri, cancellationSignal);
            String directoryId = DocumentsContract.getDocumentId(directoryUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, directoryId);
            BoundedEntries bounded = new BoundedEntries(maxEntries);
            try (Cursor cursor = resolver.query(children, CHILD_PROJECTION,
                    null, null, null, cancellationSignal)) {
                if (cursor == null) {
                    return Listing.failed(label);
                }
                int idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                    return Listing.failed(label);
                }
                int scannedRows = 0;
                boolean scanLimitReached = false;
                while (cursor.moveToNext()) {
                    cancellationSignal.throwIfCanceled();
                    if (scannedRows >= maxScannedRows) {
                        scanLimitReached = true;
                        break;
                    }
                    scannedRows++;
                    String documentId = cursor.getString(idColumn);
                    String name = normalizedName(cursor.getString(nameColumn));
                    String mimeType = cursor.getString(mimeColumn);
                    if (documentId == null || documentId.isBlank() || name.isBlank()
                            || name.startsWith(".")) {
                        continue;
                    }
                    EntryKind kind;
                    if (DIRECTORY_MIME_TYPE.equals(mimeType)) {
                        kind = EntryKind.DIRECTORY;
                    } else if (RomDocumentFilter.accepts(name)) {
                        kind = EntryKind.ROM;
                    } else {
                        continue;
                    }
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    bounded.add(new Entry(kind, name, child));
                }
                if (scanLimitReached) {
                    bounded.markTruncated();
                }
            }
            return new Listing(label, bounded.sorted(), null, bounded.truncated());
        } catch (RuntimeException failure) {
            return Listing.failed("ROM FOLDER");
        }
    }

    private String displayName(Uri uri, CancellationSignal cancellationSignal) {
        try (Cursor cursor = resolver.query(uri, NAME_PROJECTION,
                null, null, null, cancellationSignal)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (column >= 0) {
                    String name = normalizedName(cursor.getString(column));
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
        }
        return "ROM FOLDER";
    }

    private static String normalizedName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        return codePoints <= MAX_DISPLAY_NAME_LENGTH ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_DISPLAY_NAME_LENGTH));
    }

    private static int scanBudget(int maxEntries) {
        return maxEntries > Integer.MAX_VALUE / SCAN_BUDGET_MULTIPLIER
                ? Integer.MAX_VALUE : maxEntries * SCAN_BUDGET_MULTIPLIER;
    }

    enum EntryKind {
        DIRECTORY,
        ROM
    }

    record Entry(EntryKind kind, String label, Uri uri) {
        Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(uri, "uri");
        }
    }

    record Listing(String label, List<Entry> entries, String errorMessage, boolean truncated) {
        Listing {
            Objects.requireNonNull(label, "label");
            entries = List.copyOf(entries);
        }

        private static Listing failed(String label) {
            return new Listing(label, List.of(), "UNABLE TO READ FOLDER", false);
        }
    }

    private static final class BoundedEntries {
        private final int maximum;
        private final PriorityQueue<Entry> entries;
        private int accepted;
        private boolean forcedTruncated;

        private BoundedEntries(int maximum) {
            this.maximum = maximum;
            entries = new PriorityQueue<>(maximum, ENTRY_ORDER.reversed());
        }

        private void add(Entry entry) {
            accepted++;
            if (entries.size() < maximum) {
                entries.add(entry);
            } else if (ENTRY_ORDER.compare(entry, entries.peek()) < 0) {
                entries.remove();
                entries.add(entry);
            }
        }

        private List<Entry> sorted() {
            java.util.ArrayList<Entry> sorted = new java.util.ArrayList<>(entries);
            sorted.sort(ENTRY_ORDER);
            return List.copyOf(sorted);
        }

        private boolean truncated() {
            return forcedTruncated || accepted > maximum;
        }

        private void markTruncated() {
            forcedTruncated = true;
        }
    }
}
