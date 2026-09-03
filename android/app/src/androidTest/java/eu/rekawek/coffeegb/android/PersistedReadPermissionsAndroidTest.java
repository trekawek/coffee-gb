package eu.rekawek.coffeegb.android;

import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PersistedReadPermissionsAndroidTest {

    private static final String AUTHORITY = "com.example.documents";

    @Test
    public void exactDocumentGrantCoversOnlyTheExactUri() {
        Uri document = DocumentsContract.buildDocumentUri(AUTHORITY, "opaque:game.gb");
        Uri other = DocumentsContract.buildDocumentUri(AUTHORITY, "opaque:other.gb");

        assertTrue(PersistedReadPermissions.covers(document, document));
        assertFalse(PersistedReadPermissions.covers(document, other));
    }

    @Test
    public void persistedTreeCoversDocumentUrisBuiltThroughThatTree() {
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "opaque:roms/root");
        Uri child = DocumentsContract.buildDocumentUriUsingTree(
                tree, "provider-specific/child:game.gb");
        Uri nested = DocumentsContract.buildDocumentUriUsingTree(
                tree, "unrelated-looking-id-without-a-path-contract");

        assertTrue(PersistedReadPermissions.covers(tree, child));
        assertTrue(PersistedReadPermissions.covers(tree, nested));
    }

    @Test
    public void anotherTreeProviderOrPlainDocumentUriIsNotCovered() {
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "opaque:roms/root");
        Uri otherTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "opaque:other/root");
        Uri otherTreeChild = DocumentsContract.buildDocumentUriUsingTree(
                otherTree, "opaque:other/root/game.gb");
        Uri otherProviderTree = DocumentsContract.buildTreeDocumentUri(
                "com.other.documents", "opaque:roms/root");
        Uri otherProviderChild = DocumentsContract.buildDocumentUriUsingTree(
                otherProviderTree, "opaque:roms/root/game.gb");
        Uri plainDocument = DocumentsContract.buildDocumentUri(
                AUTHORITY, "opaque:roms/root/game.gb");

        assertFalse(PersistedReadPermissions.covers(tree, otherTreeChild));
        assertFalse(PersistedReadPermissions.covers(tree, otherProviderChild));
        assertFalse(PersistedReadPermissions.covers(tree, plainDocument));
        assertFalse(PersistedReadPermissions.covers(tree, null));
    }
}
