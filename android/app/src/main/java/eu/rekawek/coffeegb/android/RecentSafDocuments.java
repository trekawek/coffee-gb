package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.StatePngCodec;
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Permission-aware, game-level recent catalog. One persisted document may contribute several ZIP
 * entries; URI and archive identity remain private to the runtime while the menu receives only
 * redacted names, timestamps, and detached screenshots.
 */
final class RecentSafDocuments {

    private static final String PREFERENCES = "coffee-gb-saf";
    private static final String KEY_URIS = "recent-rom-uris";
    private static final String KEY_DOCUMENT_ENTRIES = "recent-rom-entries-v2";
    private static final String KEY_GAME_ENTRIES = "recent-game-entries-v3";
    private static final int CAPACITY = 10;
    private static final SecureRandom BENCHMARK_NONCE_RANDOM = new SecureRandom();

    private final ContentResolver resolver;
    private final SharedPreferences preferences;
    private final File previewDirectory;

    RecentSafDocuments(Context context) {
        Context application = context.getApplicationContext();
        resolver = application.getContentResolver();
        preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        previewDirectory = new File(application.getFilesDir(), "recent-game-previews");
    }

    /** Legacy direct-document path retained for the Library chooser. */
    void recordIfPersisted(Uri uri) {
        recordIfPersisted(uri, displayName(uri),
                RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN, "", -1, "", null);
    }

    /** Records one successfully started game and its exact direct/archive identity. */
    void recordIfPersisted(Uri uri, String romName, long candidateToken,
            String archiveEntryName, int archiveEntryOccurrence, String romHash,
            NativeFrameStore.Snapshot snapshot) {
        if (uri == null || !hasPersistedReadPermission(uri)) {
            if (uri != null) {
                remove(uri);
            }
            return;
        }
        StoredEntry newest = stored(uri, romName, candidateToken, archiveEntryName,
                archiveEntryOccurrence, romHash, System.currentTimeMillis());
        // Re-opening/reordering a selected game must retain its app-owned benchmark nonce.  The
        // nonce is catalog identity, not launch input, so replacing the entry without carrying it
        // would make the same recent slot look like a new workload.
        for (StoredEntry prior : storedEntries()) {
            // A nonce names the exact app-owned catalog identity, not merely a URI/archive
            // coordinate.  Preserve it only when the bytes still hash identically; an in-place
            // SAF replacement must clear the old nonce so the next benchmark launch rotates it.
            if (sameGame(prior, newest) && hasValidRomHash(prior.romHash())
                    && hasValidRomHash(newest.romHash())
                    && prior.romHash().equalsIgnoreCase(newest.romHash())
                    && hasValidBenchmarkNonce(prior.benchmarkNonce())) {
                newest = withBenchmarkNonce(newest, prior.benchmarkNonce());
                break;
            }
        }
        ArrayList<StoredEntry> ordered = new ArrayList<>();
        ordered.add(newest);
        for (StoredEntry entry : storedEntries()) {
            boolean documentShapeChanged = entry.uri().equals(newest.uri())
                    && (entry.candidateToken()
                            == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN)
                    != (newest.candidateToken()
                            == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN);
            if (!sameGame(entry, newest) && !documentShapeChanged) {
                ordered.add(entry);
            }
        }
        while (ordered.size() > CAPACITY) {
            ordered.remove(ordered.size() - 1);
        }
        if (snapshot != null) {
            writePreview(newest, snapshot);
        }
        saveEntries(ordered);
    }

    /** Returns readable games in most-recent-first order, pruning revoked grants. */
    List<Entry> readableEntries() {
        List<StoredEntry> stored = storedEntries();
        ArrayList<StoredEntry> readable = new ArrayList<>();
        for (StoredEntry entry : stored) {
            if (hasPersistedReadPermission(entry.uri())) {
                readable.add(entry);
            }
        }
        if (!sameEntries(stored, readable)) {
            saveEntries(readable);
        } else {
            cleanupPreviewDirectory(readable);
        }
        ArrayList<Entry> result = new ArrayList<>(readable.size());
        for (StoredEntry entry : readable) {
            result.add(new Entry(entry.uri(), entry.romName(), entry.candidateToken(),
                    entry.archiveEntryName(), entry.archiveEntryOccurrence(),
                    entry.romHash(), entry.lastPlayedMillis(), readPreview(entry)));
        }
        return List.copyOf(result);
    }

    /**
     * Returns only the newest readable identity for the benchmark launcher.  Unlike the menu
     * catalog path this never decodes a persisted thumbnail or constructs a display row.  It
     * deliberately reads only the current v3 catalog: migration, repair, pruning, and preview
     * cleanup are all writes and therefore do not belong on the benchmark launch path.
     */
    Entry mostRecentReadableEntry() {
        return benchmarkEntryAtSlot(0);
    }

    /** Read-only opaque slot lookup used by the benchmark scheduler for row-specific selections. */
    Entry benchmarkEntryAtSlot(int slot) {
        if (slot < 0 || slot >= CAPACITY) {
            return null;
        }
        String encoded = preferences.getString(KEY_GAME_ENTRIES, null);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            JSONArray array = new JSONArray(encoded);
            int readableIndex = 0;
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String encodedUri = object.optString("uri", "");
                if (encodedUri.isBlank()) {
                    continue;
                }
                StoredEntry entry = stored(Uri.parse(encodedUri),
                        object.optString("romName", ""),
                        object.optLong("candidateToken",
                                RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN),
                        object.optString("entryName", ""),
                        object.optInt("entryOccurrence", -1),
                        object.optString("romHash", ""),
                        object.optLong("lastPlayed", 0L),
                        object.optString("benchmarkNonce", ""));
                if (hasValidRomHash(entry.romHash()) && hasPersistedReadPermission(entry.uri())) {
                    if (readableIndex++ != slot) {
                        continue;
                    }
                    return new Entry(entry.uri(), entry.romName(), entry.candidateToken(),
                            entry.archiveEntryName(), entry.archiveEntryOccurrence(),
                            entry.romHash(), entry.lastPlayedMillis(), MenuPreview.empty());
                }
            }
        } catch (Exception ignored) {
            // A malformed private catalog is not repaired by a benchmark launch.
        }
        return null;
    }

    /**
     * Returns the app-owned opaque workload nonce for a selected recent game, creating it once.
     * The nonce is generated independently of URI, title, archive name, ROM bytes, and save data;
     * it is persisted only beside the private recent-game metadata.
     */
    String ensureBenchmarkNonce(Entry target) {
        if (target == null || !hasPersistedReadPermission(target.uri())) {
            return "unknown";
        }
        String encoded = preferences.getString(KEY_GAME_ENTRIES, null);
        if (encoded == null || encoded.isBlank()) {
            return "unknown";
        }
        try {
            JSONArray array = new JSONArray(encoded);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                StoredEntry candidate = stored(Uri.parse(object.optString("uri", "")),
                        object.optString("romName", ""),
                        object.optLong("candidateToken",
                                RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN),
                        object.optString("entryName", ""), object.optInt("entryOccurrence", -1),
                        object.optString("romHash", ""), object.optLong("lastPlayed", 0L),
                        object.optString("benchmarkNonce", ""));
                if (!sameGame(candidate, stored(target.uri(), target.romName(),
                        target.candidateToken(), target.archiveEntryName(),
                        target.archiveEntryOccurrence(), target.romHash(),
                        target.lastPlayedMillis()))) {
                    continue;
                }
                if (!hasValidRomHash(candidate.romHash())
                        || !hasValidRomHash(target.romHash())
                        || !candidate.romHash().equalsIgnoreCase(target.romHash())) {
                    continue;
                }
                String existing = normalizeBenchmarkNonce(object.optString("benchmarkNonce", ""));
                if (hasValidBenchmarkNonce(existing)) {
                    return existing;
                }
                Set<String> used = new HashSet<>();
                for (int otherIndex = 0; otherIndex < array.length(); otherIndex++) {
                    JSONObject other = array.optJSONObject(otherIndex);
                    if (other != null) {
                        String otherNonce = normalizeBenchmarkNonce(
                                other.optString("benchmarkNonce", ""));
                        if (hasValidBenchmarkNonce(otherNonce)) {
                            used.add(otherNonce);
                        }
                    }
                }
                String nonce;
                do {
                    nonce = randomBenchmarkNonce();
                } while (used.contains(nonce));
                object.put("benchmarkNonce", nonce);
                if (preferences.edit().putString(KEY_GAME_ENTRIES, array.toString()).commit()) {
                    return nonce;
                }
                return "unknown";
            }
        } catch (Exception ignored) {
            // A malformed private catalog cannot be made benchmark-eligible.
        }
        return "unknown";
    }

    private static String randomBenchmarkNonce() {
        byte[] bytes = new byte[24];
        BENCHMARK_NONCE_RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    static boolean hasValidRomHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    /** Compatibility path used by the existing document-level Library chooser. */
    List<Uri> readable() {
        LinkedHashSet<Uri> uris = new LinkedHashSet<>();
        for (Entry entry : readableEntries()) {
            uris.add(entry.uri());
        }
        return List.copyOf(uris);
    }

    /** Removes every recent game backed by a revoked or unreadable document. */
    void remove(Uri uri) {
        if (uri == null) {
            return;
        }
        ArrayList<StoredEntry> remaining = new ArrayList<>();
        for (StoredEntry entry : storedEntries()) {
            if (!entry.uri().equals(uri) && hasPersistedReadPermission(entry.uri())) {
                remaining.add(entry);
            }
        }
        saveEntries(remaining);
    }

    /** Removes only one game from a multi-ROM document. */
    void removeGame(Entry target) {
        if (target == null) {
            return;
        }
        removeGame(target.uri(), target.candidateToken(), target.archiveEntryName(),
                target.archiveEntryOccurrence());
    }

    void removeGame(Uri uri, long candidateToken, String archiveEntryName,
            int archiveEntryOccurrence) {
        if (uri == null) {
            return;
        }
        StoredEntry target = stored(uri, "RECENT GAME", candidateToken, archiveEntryName,
                archiveEntryOccurrence, "", 0L);
        ArrayList<StoredEntry> remaining = new ArrayList<>();
        for (StoredEntry entry : storedEntries()) {
            if (!sameGame(entry, target) && hasPersistedReadPermission(entry.uri())) {
                remaining.add(entry);
            }
        }
        saveEntries(remaining);
    }

    private List<StoredEntry> storedEntries() {
        String encoded = preferences.getString(KEY_GAME_ENTRIES, null);
        if (encoded == null || encoded.isBlank()) {
            return migrateDocumentCatalog();
        }
        ArrayList<StoredEntry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(encoded);
            for (int index = 0; index < array.length() && result.size() < CAPACITY; index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String encodedUri = object.optString("uri", "");
                if (encodedUri.isBlank()) {
                    continue;
                }
                Uri uri = Uri.parse(encodedUri);
                long candidateToken = object.optLong("candidateToken",
                        RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN);
                String entryName = object.optString("entryName", "");
                int occurrence = object.optInt("entryOccurrence", -1);
                String romName = object.optString("romName", "");
                String romHash = object.optString("romHash", "");
                result.add(stored(uri, romName, candidateToken, entryName, occurrence, romHash,
                        object.optLong("lastPlayed", 0L),
                        object.optString("benchmarkNonce", "")));
            }
            if (result.size() != array.length() || array.length() > CAPACITY) {
                saveEntries(result);
            }
        } catch (Exception ignored) {
            // Corrupt private metadata must not strand old images or surface bogus rows.
            saveEntries(List.of());
            return List.of();
        }
        return result;
    }

    private List<StoredEntry> migrateDocumentCatalog() {
        ArrayList<StoredEntry> migrated = new ArrayList<>();
        String v2 = preferences.getString(KEY_DOCUMENT_ENTRIES, null);
        if (v2 != null && !v2.isBlank()) {
            try {
                JSONArray array = new JSONArray(v2);
                for (int index = 0; index < array.length() && migrated.size() < CAPACITY; index++) {
                    JSONObject object = array.optJSONObject(index);
                    if (object == null) {
                        continue;
                    }
                    String encodedUri = object.optString("uri", "");
                    if (!encodedUri.isBlank()) {
                        Uri uri = Uri.parse(encodedUri);
                        migrated.add(stored(uri, displayName(uri),
                                RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN, "", -1, "",
                                object.optLong("lastPlayed", 0L)));
                    }
                }
            } catch (Exception ignored) {
                migrated.clear();
            }
        }
        if (migrated.isEmpty()) {
            for (String value : preferences.getStringSet(KEY_URIS, Collections.emptySet())) {
                try {
                    Uri uri = Uri.parse(value);
                    migrated.add(stored(uri, displayName(uri),
                            RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN, "", -1, "", 0L));
                } catch (RuntimeException ignored) {
                    // Ignore malformed legacy metadata.
                }
                if (migrated.size() == CAPACITY) {
                    break;
                }
            }
        }
        saveEntries(migrated);
        return List.copyOf(migrated);
    }

    private void saveEntries(List<StoredEntry> entries) {
        JSONArray array = new JSONArray();
        LinkedHashSet<String> legacyUris = new LinkedHashSet<>();
        ArrayList<StoredEntry> bounded = new ArrayList<>();
        for (StoredEntry entry : entries) {
            if (bounded.size() == CAPACITY) {
                break;
            }
            JSONObject object = new JSONObject();
            try {
                object.put("uri", entry.uri().toString());
                object.put("romName", entry.romName());
                object.put("candidateToken", entry.candidateToken());
                object.put("entryName", entry.archiveEntryName());
                object.put("entryOccurrence", entry.archiveEntryOccurrence());
                object.put("romHash", entry.romHash());
                object.put("lastPlayed", entry.lastPlayedMillis());
                if (hasValidBenchmarkNonce(entry.benchmarkNonce())) {
                    object.put("benchmarkNonce", entry.benchmarkNonce());
                }
            } catch (Exception ignored) {
                continue;
            }
            array.put(object);
            bounded.add(entry);
            legacyUris.add(entry.uri().toString());
        }
        preferences.edit().putString(KEY_GAME_ENTRIES, array.toString())
                .putStringSet(KEY_URIS, legacyUris).apply();
        cleanupPreviewDirectory(bounded);
    }

    private String displayName(Uri uri) {
        if (uri == null) {
            return "RECENT GAME";
        }
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.isBlank()) {
                        return normalizeName(value, "RECENT GAME");
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A readable URI may have a provider that declines metadata queries.
        }
        String fallback = uri.getLastPathSegment();
        return normalizeName(fallback, "RECENT GAME");
    }

    private MenuPreview readPreview(StoredEntry entry) {
        File file = previewFile(entry);
        if (!file.isFile()) {
            return MenuPreview.empty();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            StateImage image = StatePngCodec.INSTANCE.decode(bytes);
            int[] rgb = image.copyRgb();
            int[] argb = new int[rgb.length];
            for (int index = 0; index < rgb.length; index++) {
                argb[index] = 0xff000000 | rgb[index];
            }
            return MenuPreview.ready(image.getWidth(), image.getHeight(), argb);
        } catch (Exception ignored) {
            file.delete();
            return MenuPreview.empty();
        }
    }

    private void writePreview(StoredEntry entry, NativeFrameStore.Snapshot snapshot) {
        try {
            StateImage image = new StateImage(snapshot.width(), snapshot.height(),
                    rgbPixels(snapshot.pixels())).thumbnail(StateImage.THUMBNAIL_WIDTH,
                    StateImage.THUMBNAIL_HEIGHT);
            byte[] encoded = StatePngCodec.INSTANCE.encode(image);
            if (!previewDirectory.exists() && !previewDirectory.mkdirs()) {
                return;
            }
            File target = previewFile(entry);
            File temporary = new File(target.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(encoded);
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                if (target.delete()) {
                    temporary.renameTo(target);
                }
            }
        } catch (Exception ignored) {
            // Screenshot metadata is supplemental and must never prevent opening a game.
        }
    }

    private void cleanupPreviewDirectory(List<StoredEntry> retained) {
        if (!previewDirectory.isDirectory()) {
            return;
        }
        Set<String> expected = new HashSet<>();
        for (StoredEntry entry : retained) {
            expected.add(previewFile(entry).getName());
        }
        File[] files = previewDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(".tmp")
                    || (file.getName().endsWith(".png") && !expected.contains(file.getName()))) {
                file.delete();
            }
        }
    }

    private File previewFile(StoredEntry entry) {
        return new File(previewDirectory, previewKey(entry.uri().toString(),
                entry.candidateToken(), entry.archiveEntryName(),
                entry.archiveEntryOccurrence()) + ".png");
    }

    static String previewKey(String uri, long candidateToken, String entryName, int occurrence) {
        return digest(uri + '\u0000' + candidateToken + '\u0000'
                + normalizeEntryName(entryName) + '\u0000' + occurrence);
    }

    private static int[] rgbPixels(int[] argb) {
        int[] rgb = new int[argb.length];
        for (int index = 0; index < argb.length; index++) {
            rgb[index] = argb[index] & 0x00ffffff;
        }
        return rgb;
    }

    private static StoredEntry stored(Uri uri, String romName, long candidateToken,
            String entryName, int occurrence, String romHash, long lastPlayedMillis) {
        return stored(uri, romName, candidateToken, entryName, occurrence, romHash,
                lastPlayedMillis, "");
    }

    private static StoredEntry stored(Uri uri, String romName, long candidateToken,
            String entryName, int occurrence, String romHash, long lastPlayedMillis,
            String benchmarkNonce) {
        long token = candidateToken < RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                ? RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN : candidateToken;
        String archiveName = token == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                ? "" : normalizeEntryName(entryName);
        int archiveOccurrence = token == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                ? -1 : Math.max(0, occurrence);
        return new StoredEntry(uri, normalizeName(romName, "RECENT GAME"), token, archiveName,
                archiveOccurrence, normalizeHash(romHash), Math.max(0L, lastPlayedMillis),
                normalizeBenchmarkNonce(benchmarkNonce));
    }

    private static StoredEntry withBenchmarkNonce(StoredEntry entry, String nonce) {
        return new StoredEntry(entry.uri(), entry.romName(), entry.candidateToken(),
                entry.archiveEntryName(), entry.archiveEntryOccurrence(), entry.romHash(),
                entry.lastPlayedMillis(), normalizeBenchmarkNonce(nonce));
    }

    private static String normalizeBenchmarkNonce(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hasValidBenchmarkNonce(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]{15,63}")
                && !"unknown".equals(value) && !"invalid".equals(value);
    }

    private static boolean sameGame(StoredEntry left, StoredEntry right) {
        if (!left.uri().equals(right.uri())) {
            return false;
        }
        if (left.candidateToken() == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN
                || right.candidateToken() == RomSourceSnapshot.ArchiveCandidate.DIRECT_TOKEN) {
            return left.candidateToken() == right.candidateToken();
        }
        return left.archiveEntryOccurrence() == right.archiveEntryOccurrence()
                && left.archiveEntryName().equals(right.archiveEntryName());
    }

    private static boolean sameEntries(List<StoredEntry> left, List<StoredEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).equals(right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeName(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static String normalizeEntryName(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private static String normalizeHash(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private boolean hasPersistedReadPermission(Uri uri) {
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    record Entry(Uri uri, String romName, long candidateToken, String archiveEntryName,
                 int archiveEntryOccurrence, String romHash, long lastPlayedMillis,
                 MenuPreview preview) {
        Entry {
            romHash = normalizeHash(romHash);
            preview = preview == null ? MenuPreview.empty() : preview;
        }
    }

    private record StoredEntry(Uri uri, String romName, long candidateToken,
                               String archiveEntryName, int archiveEntryOccurrence, String romHash,
                               long lastPlayedMillis, String benchmarkNonce) {
    }
}
