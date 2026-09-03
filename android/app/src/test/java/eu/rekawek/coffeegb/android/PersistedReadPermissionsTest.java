package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PersistedReadPermissionsTest {

    @Test
    public void identicalAuthorityAndOpaqueTreeIdIdentifyTheSameGrant() {
        assertTrue(PersistedReadPermissions.sameTreeIdentity(
                "com.example.documents", "opaque:roms/root",
                "com.example.documents", "opaque:roms/root"));
    }

    @Test
    public void differentProviderOrTreeCannotBorrowAnotherGrant() {
        assertFalse(PersistedReadPermissions.sameTreeIdentity(
                "com.example.documents", "opaque:roms/root",
                "com.other.documents", "opaque:roms/root"));
        assertFalse(PersistedReadPermissions.sameTreeIdentity(
                "com.example.documents", "opaque:roms/root",
                "com.example.documents", "opaque:other/root"));
    }

    @Test
    public void missingIdentityCannotEstablishDurableAccess() {
        assertFalse(PersistedReadPermissions.sameTreeIdentity(
                null, "opaque:roms/root", "com.example.documents", "opaque:roms/root"));
        assertFalse(PersistedReadPermissions.sameTreeIdentity(
                "com.example.documents", null, "com.example.documents", null));
    }
}
