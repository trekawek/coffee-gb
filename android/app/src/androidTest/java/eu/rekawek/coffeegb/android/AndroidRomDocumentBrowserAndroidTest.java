package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidRomDocumentBrowserAndroidTest {

    @Test
    public void listsOnlyVisibleSupportedDocumentsWithDirectoriesFirst() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AndroidRomDocumentBrowser browser = new AndroidRomDocumentBrowser(
                context.getContentResolver());
        AndroidRomDocumentBrowser.Listing listing = browser.list(
                FixtureDocumentTreeProvider.TREE_URI,
                AndroidRomDocumentBrowser.rootDocument(FixtureDocumentTreeProvider.TREE_URI),
                new CancellationSignal());

        assertEquals("My ROMs", listing.label());
        assertNull(listing.errorMessage());
        assertFalse(listing.truncated());
        assertEquals(List.of("Folder", "Alpha.gb", "bundle.zip", "zeta.GBC"),
                listing.entries().stream().map(AndroidRomDocumentBrowser.Entry::label)
                        .collect(Collectors.toList()));
        assertEquals(AndroidRomDocumentBrowser.EntryKind.DIRECTORY,
                listing.entries().get(0).kind());
        for (AndroidRomDocumentBrowser.Entry entry : listing.entries()) {
            assertTrue(PersistedReadPermissions.covers(
                    FixtureDocumentTreeProvider.TREE_URI, entry.uri()));
        }
    }

    @Test
    public void boundsLargeProviderResultsBeforeRendering() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AndroidRomDocumentBrowser browser = new AndroidRomDocumentBrowser(
                context.getContentResolver(), 2);
        AndroidRomDocumentBrowser.Listing listing = browser.list(
                FixtureDocumentTreeProvider.TREE_URI,
                AndroidRomDocumentBrowser.rootDocument(FixtureDocumentTreeProvider.TREE_URI),
                new CancellationSignal());

        assertTrue(listing.truncated());
        assertEquals(List.of("Folder", "Alpha.gb"),
                listing.entries().stream().map(AndroidRomDocumentBrowser.Entry::label)
                        .collect(Collectors.toList()));
    }

    @Test
    public void boundsRowsScannedEvenWhenTheProviderReturnsMore() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AndroidRomDocumentBrowser browser = new AndroidRomDocumentBrowser(
                context.getContentResolver(), 2, 3);
        AndroidRomDocumentBrowser.Listing listing = browser.list(
                FixtureDocumentTreeProvider.TREE_URI,
                AndroidRomDocumentBrowser.rootDocument(FixtureDocumentTreeProvider.TREE_URI),
                new CancellationSignal());

        assertTrue(listing.truncated());
        assertEquals(List.of("Folder", "Alpha.gb"),
                listing.entries().stream().map(AndroidRomDocumentBrowser.Entry::label)
                        .collect(Collectors.toList()));
    }
}
