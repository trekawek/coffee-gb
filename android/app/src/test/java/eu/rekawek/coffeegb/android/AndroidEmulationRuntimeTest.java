package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AndroidEmulationRuntimeTest {

    @Test
    public void resetReloadIsAcceptedAfterItsPreviousSessionHasAlreadyStopped() {
        // The controller posts STOPPED before the no-request STARTED event for a reset.  The
        // active layout and its monotonic generation, rather than a RUNNING/PAUSED state, retain
        // the identity needed to accept that replacement session.
        assertTrue(AndroidEmulationRuntime.isResetReload(null, true, 12L, 11L));
    }

    @Test
    public void androidControllerPropertiesDisableRewindThroughTransientOverride() {
        try (EmulatorProperties properties =
                     new EmulatorProperties(AndroidEmulationRuntime.androidSettingsOverrides())) {
            assertFalse(properties.getSaves().getRewindEnabled());
            assertEquals(Boolean.FALSE, properties.getOverrides().getRewindEnabled());
            assertEquals(Boolean.FALSE, properties.getOverrides().getRuntimeWarmupEnabled());
        }
    }

    @Test
    public void resetReloadRejectsMissingOrStaleGenerationsAndNormalOpenRequests() {
        assertFalse(AndroidEmulationRuntime.isResetReload(null, true, null, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(null, true, 11L, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(7L, true, 12L, 11L));
        assertFalse(AndroidEmulationRuntime.isResetReload(null, false, 12L, 11L));
    }

    @Test
    public void recentArchiveCandidateFollowsExactEntryWhenItsOrdinalChanges() {
        List<RomSourceSnapshot.ArchiveCandidate> changed = List.of(
                new RomSourceSnapshot.ArchiveCandidate(2L, "bonus.gb", 0, 32_768L, "BONUS"),
                new RomSourceSnapshot.ArchiveCandidate(5L, "games/tetris.gb", 0,
                        32_768L, "TETRIS"));

        assertEquals(Long.valueOf(5L), AndroidEmulationRuntime.resolveRecentCandidateToken(
                changed, 1L, "games/tetris.gb", 0));
    }

    @Test
    public void recentArchiveCandidateNeverFallsThroughToAReusedToken() {
        List<RomSourceSnapshot.ArchiveCandidate> changed = List.of(
                new RomSourceSnapshot.ArchiveCandidate(1L, "different.gb", 0,
                        32_768L, "DIFFERENT"));

        assertNull(AndroidEmulationRuntime.resolveRecentCandidateToken(
                changed, 1L, "games/tetris.gb", 0));
        assertNull(AndroidEmulationRuntime.resolveRecentCandidateToken(
                changed, 1L, "", -1));
    }

    @Test
    public void recentArchiveResolutionDoesNotDependOnInventoryHeaderTitle() {
        List<RomSourceSnapshot.ArchiveCandidate> candidates = List.of(
                new RomSourceSnapshot.ArchiveCandidate(7L, "games/tetris.gbc", 0,
                        32_768L, "SCRAMBLED INVENTORY TITLE"));

        assertEquals(Long.valueOf(7L), AndroidEmulationRuntime.resolveRecentCandidateToken(
                candidates, 7L, "games/tetris.gbc", 0));
    }

    @Test
    public void recentHashRejectsChangedRomButAllowsSafeLegacyEntry() {
        String original = "a".repeat(64);
        String changed = "b".repeat(64);

        assertTrue(AndroidEmulationRuntime.recentHashMatches(original, original));
        assertTrue(AndroidEmulationRuntime.recentHashMatches(original.toUpperCase(), original));
        assertFalse(AndroidEmulationRuntime.recentHashMatches(original, changed));
        assertTrue(AndroidEmulationRuntime.recentHashMatches("", changed));
    }

    @Test
    public void benchmarkRecentIdentityRequiresAFullHexSha256Hash() {
        assertTrue(RecentSafDocuments.hasValidRomHash("a".repeat(64)));
        assertTrue(RecentSafDocuments.hasValidRomHash("ABCDEF0123456789".repeat(4)));
        assertFalse(RecentSafDocuments.hasValidRomHash(""));
        assertFalse(RecentSafDocuments.hasValidRomHash("a".repeat(63)));
        assertFalse(RecentSafDocuments.hasValidRomHash("a".repeat(64) + "0"));
        assertFalse(RecentSafDocuments.hasValidRomHash("g".repeat(64)));
    }

    @Test
    public void benchmarkOverridesAreTransientAndCanForceDmgWithWarmup() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "forced-dmg", false, "sink", true, true, false);
        try (EmulatorProperties properties =
                     new EmulatorProperties(AndroidEmulationRuntime.androidSettingsOverrides(options))) {
            assertEquals(HardwareProfileRegistry.DMG,
                    properties.getOverrides().getHardwareProfile());
            assertEquals(Boolean.TRUE, properties.getOverrides().getRuntimeWarmupEnabled());
            assertEquals(Boolean.FALSE, properties.getOverrides().getRewindEnabled());
        }
    }

    @Test
    public void everyExplicitHardwareSelectionBecomesTheMatchingTransientOverride() {
        for (DiagnosticsOptions.Hardware hardware : DiagnosticsOptions.Hardware.values()) {
            DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                    true, hardware.externalValue(), true, "sink", false, true, false);
            try (EmulatorProperties properties =
                         new EmulatorProperties(AndroidEmulationRuntime.androidSettingsOverrides(options))) {
                assertEquals(hardware.profileOverride(),
                        properties.getOverrides().getHardwareProfile());
                assertEquals(BootstrapMode.SKIP,
                        properties.getOverrides().getBootstrapMode());
                assertEquals(Boolean.FALSE, properties.getOverrides().getRewindEnabled());
            }
        }
    }

    @Test
    public void disabledDiagnosticsDoNotOverrideBootstrapOrHardwareProfile() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                false, "cgb", true, "sink", true, true, false);
        try (EmulatorProperties properties =
                     new EmulatorProperties(AndroidEmulationRuntime.androidSettingsOverrides(options))) {
            assertNull(properties.getOverrides().getHardwareProfile());
            assertNull(properties.getOverrides().getBootstrapMode());
        }
    }

    @Test
    public void archiveCandidatesFromOneDocumentHaveSeparatePreviewKeys() {
        assertNotEquals(
                RecentSafDocuments.previewKey("content://roms/collection.zip", 1L,
                        "tetris.gb", 0),
                RecentSafDocuments.previewKey("content://roms/collection.zip", 2L,
                        "zelda.gb", 0));
    }
}
