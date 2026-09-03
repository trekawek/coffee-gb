package eu.rekawek.coffeegb.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;

/** Test-only DocumentsContract-shaped provider for deterministic in-screen browser listings. */
public final class FixtureDocumentTreeProvider extends ContentProvider {

    static final String AUTHORITY = "eu.rekawek.coffeegb.android.test.browser";
    static final Uri TREE_URI = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "rom-root");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        String[] columns = projection == null ? new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (uri.getPathSegments().contains("children")) {
            add(cursor, columns, "folder", "Folder", DocumentsContract.Document.MIME_TYPE_DIR);
            add(cursor, columns, "zeta", "zeta.GBC", "application/octet-stream");
            add(cursor, columns, "alpha", "Alpha.gb", "application/octet-stream");
            add(cursor, columns, "archive", "bundle.zip", "application/zip");
            add(cursor, columns, "hidden", ".hidden.gb", "application/octet-stream");
            add(cursor, columns, "hidden-folder", ".hidden-folder",
                    DocumentsContract.Document.MIME_TYPE_DIR);
            add(cursor, columns, "save", "battery.sav", "application/octet-stream");
        } else {
            String id = DocumentsContract.getDocumentId(uri);
            add(cursor, columns, id, "folder".equals(id) ? "Folder" : "My ROMs",
                    DocumentsContract.Document.MIME_TYPE_DIR);
        }
        return cursor;
    }

    private static void add(MatrixCursor cursor, String[] columns, String id, String name,
            String mimeType) {
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            row[index] = switch (columns[index]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name;
                case DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType;
                default -> null;
            };
        }
        cursor.addRow(row);
    }

    @Override
    public String getType(Uri uri) {
        return DocumentsContract.Document.MIME_TYPE_DIR;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        throw new FileNotFoundException("The browser fixture has no readable payload");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("The browser fixture is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The browser fixture is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The browser fixture is read-only");
    }
}
