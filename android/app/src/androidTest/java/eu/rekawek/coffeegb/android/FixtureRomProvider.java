package eu.rekawek.coffeegb.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Source-generated public-domain Game Boy fixture exposed only through a content URI.
 *
 * <p>The CI test deliberately does not package a ROM blob or provide a filesystem path. The
 * generated ROM-only program enables the LCD and loops after boot, which is enough to drive
 * display, input, state, and lifecycle coverage without relying on copyrighted game data.
 */
public final class FixtureRomProvider extends ContentProvider {

    static final Uri URI = Uri.parse("content://eu.rekawek.coffeegb.android.test.fixture/ci-smoke.gb");
    static final Uri SECOND_URI = Uri.parse(
            "content://eu.rekawek.coffeegb.android.test.fixture/ci-smoke-cgb.gbc");
    static final Uri SGB_URI = Uri.parse(
            "content://eu.rekawek.coffeegb.android.test.fixture/ci-smoke-sgb.gb");
    private static final String DISPLAY_NAME = "coffee-gb-ci-smoke.gb";
    private static final String SECOND_DISPLAY_NAME = "coffee-gb-ci-smoke-cgb.gbc";
    private static final String SGB_DISPLAY_NAME = "coffee-gb-ci-smoke-sgb.gb";
    private static final int ROM_SIZE = 0x8000;
    private static final byte[] NINTENDO_LOGO = {
            (byte) 0xce, (byte) 0xed, 0x66, 0x66, (byte) 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, (byte) 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, (byte) 0x88, (byte) 0x89, 0x00, 0x0e,
            (byte) 0xdc, (byte) 0xcc, 0x6e, (byte) 0xe6, (byte) 0xdd, (byte) 0xdd,
            (byte) 0xd9, (byte) 0x99, (byte) 0xbb, (byte) 0xbb, 0x67, 0x63,
            0x6e, 0x0e, (byte) 0xec, (byte) 0xcc, (byte) 0xdd, (byte) 0xdc,
            (byte) 0x99, (byte) 0x9f, (byte) 0xbb, (byte) 0xb9, 0x33, 0x3e,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        assertFixture(uri);
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{displayName(uri), ROM_SIZE});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        assertFixture(uri);
        return "application/x-gameboy-rom";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        assertFixture(uri);
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("The CI fixture is read-only");
        }
        try {
            Context context = getContext();
            if (context == null) {
                throw new FileNotFoundException("The CI fixture provider is not initialized");
            }
            File fixture = new File(context.getCacheDir(), displayName(uri));
            try (FileOutputStream output = new FileOutputStream(fixture)) {
                output.write(fixtureBytes(
                        uri.equals(SECOND_URI) ? "CI SMOKE CGB"
                                : uri.equals(SGB_URI) ? "CI SMOKE SGB" : "CI SMOKE",
                        uri.equals(SECOND_URI), uri.equals(SGB_URI)));
            }
            return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException failure) {
            throw new FileNotFoundException("Could not create CI fixture");
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("The CI fixture is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The CI fixture is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("The CI fixture is read-only");
    }

    private static void assertFixture(Uri uri) {
        if (!URI.equals(uri) && !SECOND_URI.equals(uri) && !SGB_URI.equals(uri)) {
            throw new IllegalArgumentException("Unknown CI fixture");
        }
    }

    private static String displayName(Uri uri) {
        assertFixture(uri);
        return uri.equals(SECOND_URI) ? SECOND_DISPLAY_NAME
                : uri.equals(SGB_URI) ? SGB_DISPLAY_NAME : DISPLAY_NAME;
    }

    private static byte[] fixtureBytes(String fixtureTitle, boolean cgb, boolean sgb) {
        byte[] rom = new byte[ROM_SIZE];
        rom[0x0100] = (byte) 0xc3; // JP 0x0150
        rom[0x0101] = 0x50;
        rom[0x0102] = 0x01;
        System.arraycopy(NINTENDO_LOGO, 0, rom, 0x0104, NINTENDO_LOGO.length);
        byte[] title = fixtureTitle.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0143] = cgb ? (byte) 0x80 : 0x00;
        rom[0x0146] = sgb ? (byte) 0x03 : 0x00;
        rom[0x0147] = 0x00; // ROM only
        rom[0x0148] = 0x00; // 32 KiB
        rom[0x0149] = 0x00; // no RAM
        int checksum = 0;
        for (int address = 0x0134; address <= 0x014c; address++) {
            checksum = (checksum - (rom[address] & 0xff) - 1) & 0xff;
        }
        rom[0x014d] = (byte) checksum;
        rom[0x0150] = 0x3e; // LD A, 0x91 (LCD and background enabled)
        rom[0x0151] = (byte) 0x91;
        rom[0x0152] = (byte) 0xea; // LD (0xff40), A
        rom[0x0153] = 0x40;
        rom[0x0154] = (byte) 0xff;
        rom[0x0155] = 0x18; // JR -2
        rom[0x0156] = (byte) 0xfe;
        return rom;
    }
}
