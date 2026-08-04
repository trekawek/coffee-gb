package eu.rekawek.coffeegb.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Source-generated public-domain Game Boy fixture exposed only through a content URI.
 *
 * <p>The CI test deliberately does not package a ROM blob or provide a filesystem path. The
 * generated ROM-only program loops after boot, which is enough to drive display, input, state,
 * and lifecycle coverage without relying on copyrighted game data.
 */
public final class FixtureRomProvider extends ContentProvider {

    static final Uri URI = Uri.parse("content://eu.rekawek.coffeegb.android.test.fixture/ci-smoke.gb");
    private static final String DISPLAY_NAME = "coffee-gb-ci-smoke.gb";
    private static final int ROM_SIZE = 0x8000;

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
        cursor.addRow(new Object[]{DISPLAY_NAME, ROM_SIZE});
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
            File fixture = new File(requireContext().getCacheDir(), DISPLAY_NAME);
            if (!fixture.isFile() || fixture.length() != ROM_SIZE) {
                Files.write(fixture.toPath(), fixtureBytes());
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
        if (!URI.equals(uri)) {
            throw new IllegalArgumentException("Unknown CI fixture");
        }
    }

    private static byte[] fixtureBytes() {
        byte[] rom = new byte[ROM_SIZE];
        rom[0x0100] = (byte) 0xc3; // JP 0x0150
        rom[0x0101] = 0x50;
        rom[0x0102] = 0x01;
        byte[] title = "CI SMOKE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0147] = 0x00; // ROM only
        rom[0x0148] = 0x00; // 32 KiB
        rom[0x0149] = 0x00; // no RAM
        int checksum = 0;
        for (int address = 0x0134; address <= 0x014c; address++) {
            checksum = (checksum - (rom[address] & 0xff) - 1) & 0xff;
        }
        rom[0x014d] = (byte) checksum;
        rom[0x0150] = 0x18; // JR -2
        rom[0x0151] = (byte) 0xfe;
        return rom;
    }
}
