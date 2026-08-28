package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import eu.rekawek.coffeegb.core.memory.cart.RomImage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Durable app-private copies of documents returned by non-persistable Android pickers. */
final class AndroidRomImportStore {

    private static final String DIRECTORY = "rom-imports";
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final long MAX_ZIP_BYTES = 128L * 1024 * 1024;
    private static final int MAX_IMPORTS = 16;

    private final ContentResolver resolver;
    private final File directory;

    AndroidRomImportStore(Context context) throws IOException {
        Context application = context.getApplicationContext();
        resolver = application.getContentResolver();
        directory = new File(application.getFilesDir(), DIRECTORY);
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Could not create the private ROM import directory");
        }
    }

    Uri importDocument(Uri source) throws IOException {
        AndroidRomInput input = new AndroidRomInput(resolver, source);
        String extension = RomDocumentFilter.extension(input.displayName());
        if (!RomDocumentFilter.accepts(input.displayName())) {
            throw new IOException("Unsupported ROM document type");
        }
        long limit = extension.equals("zip") ? MAX_ZIP_BYTES : RomImage.MAX_ROM_BYTES;
        if (input.declaredSize() > limit) {
            throw new IOException("ROM document exceeds its size limit");
        }

        File temporary = File.createTempFile("pending-", ".tmp", directory);
        MessageDigest digest = sha256();
        long total = 0L;
        try (InputStream stream = input.openStream();
                FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (read > limit - total) {
                    throw new IOException("ROM document exceeds its size limit");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
            }
            output.getFD().sync();
        } catch (IOException | RuntimeException failure) {
            temporary.delete();
            throw failure;
        }

        File imported = new File(directory, hex(digest.digest()) + "." + extension);
        if (imported.isFile()) {
            temporary.delete();
            imported.setLastModified(System.currentTimeMillis());
        } else if (!temporary.renameTo(imported)) {
            temporary.delete();
            throw new IOException("Could not retain the selected ROM document");
        }
        pruneOldImports(imported);
        return Uri.fromFile(imported);
    }

    boolean ownsReadable(Uri uri) {
        File file = ownedFile(uri);
        return file != null && file.isFile() && file.canRead();
    }

    void deleteIfOwned(Uri uri) {
        File file = ownedFile(uri);
        if (file != null) {
            file.delete();
        }
    }

    private File ownedFile(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                || uri.getPath() == null) {
            return null;
        }
        try {
            File file = new File(uri.getPath()).getCanonicalFile();
            File parent = file.getParentFile();
            if (parent == null || !parent.equals(directory.getCanonicalFile())
                    || !file.getName().matches("[0-9a-f]{64}\\.(gb|gbc|rom|zip)")) {
                return null;
            }
            return file;
        } catch (IOException failure) {
            return null;
        }
    }

    private void pruneOldImports(File keep) {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().matches("[0-9a-f]{64}\\.(gb|gbc|rom|zip)"));
        if (files == null || files.length <= MAX_IMPORTS) {
            return;
        }
        ArrayList<File> ordered = new ArrayList<>(List.of(files));
        ordered.sort(Comparator.comparingLong(File::lastModified));
        int remaining = ordered.size();
        for (File candidate : ordered) {
            if (remaining <= MAX_IMPORTS) {
                break;
            }
            if (!candidate.equals(keep) && candidate.delete()) {
                remaining--;
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }
}
