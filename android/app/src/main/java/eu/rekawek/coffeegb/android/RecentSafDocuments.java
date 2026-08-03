package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Small, permission-aware recent-document list. Stale SAF grants are removed eagerly. */
final class RecentSafDocuments {

    private static final String PREFERENCES = "coffee-gb-saf";
    private static final String KEY_URIS = "recent-rom-uris";
    private static final int CAPACITY = 10;

    private final ContentResolver resolver;
    private final SharedPreferences preferences;

    RecentSafDocuments(Context context) {
        resolver = context.getContentResolver();
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    void recordIfPersisted(Uri uri) {
        if (!hasPersistedReadPermission(uri)) {
            remove(uri);
            return;
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(uri.toString());
        ordered.addAll(preferences.getStringSet(KEY_URIS, Collections.emptySet()));
        while (ordered.size() > CAPACITY) {
            String oldest = ordered.stream().skip(CAPACITY).findFirst().orElseThrow();
            ordered.remove(oldest);
        }
        preferences.edit().putStringSet(KEY_URIS, ordered).apply();
    }

    List<Uri> readable() {
        List<Uri> result = new ArrayList<>();
        for (String encoded : preferences.getStringSet(KEY_URIS, Collections.emptySet())) {
            Uri uri = Uri.parse(encoded);
            if (hasPersistedReadPermission(uri)) {
                result.add(uri);
            }
        }
        save(result);
        return result;
    }

    void remove(Uri uri) {
        List<Uri> remaining = new ArrayList<>();
        for (String encoded : preferences.getStringSet(KEY_URIS, Collections.emptySet())) {
            Uri candidate = Uri.parse(encoded);
            if (!candidate.equals(uri) && hasPersistedReadPermission(candidate)) {
                remaining.add(candidate);
            }
        }
        save(remaining);
    }

    private boolean hasPersistedReadPermission(Uri uri) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()) {
                return true;
            }
        }
        return false;
    }

    private void save(List<Uri> uris) {
        LinkedHashSet<String> encoded = new LinkedHashSet<>();
        for (Uri uri : uris) {
            if (encoded.size() == CAPACITY) {
                break;
            }
            encoded.add(uri.toString());
        }
        preferences.edit().putStringSet(KEY_URIS, encoded).apply();
    }
}
