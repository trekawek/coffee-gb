package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.List;
import java.util.Objects;

/** Resolves durable read access for exact SAF documents and descendants of persisted trees. */
final class PersistedReadPermissions {

    private PersistedReadPermissions() {
    }

    static boolean covers(ContentResolver resolver, Uri target) {
        if (resolver == null || target == null) {
            return false;
        }
        final List<UriPermission> permissions;
        try {
            permissions = resolver.getPersistedUriPermissions();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (permissions == null) {
            return false;
        }
        for (UriPermission permission : permissions) {
            if (permission != null && permission.isReadPermission()
                    && covers(permission.getUri(), target)) {
                return true;
            }
        }
        return false;
    }

    static boolean covers(Uri persisted, Uri target) {
        if (persisted == null || target == null) {
            return false;
        }
        if (persisted.equals(target)) {
            return true;
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(persisted.getScheme())
                || !ContentResolver.SCHEME_CONTENT.equals(target.getScheme())) {
            return false;
        }
        try {
            if (!DocumentsContract.isTreeUri(persisted)
                    || !DocumentsContract.isTreeUri(target)) {
                return false;
            }
            return sameTreeIdentity(
                    persisted.getAuthority(), DocumentsContract.getTreeDocumentId(persisted),
                    target.getAuthority(), DocumentsContract.getTreeDocumentId(target));
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // A malformed/provider-specific URI is not proof of durable access.
            return false;
        }
    }

    static boolean sameTreeIdentity(String persistedAuthority, String persistedTreeId,
            String targetAuthority, String targetTreeId) {
        return persistedAuthority != null && persistedTreeId != null
                && Objects.equals(persistedAuthority, targetAuthority)
                && Objects.equals(persistedTreeId, targetTreeId);
    }
}
