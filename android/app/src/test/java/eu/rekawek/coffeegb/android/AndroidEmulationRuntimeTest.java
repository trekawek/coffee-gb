package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.controller.properties.RuntimeWarmupFlavor;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public void benchmarkEndpointAndAckMustBelongToOneCurrentPresentedSession() {
        assertTrue(AndroidEmulationRuntime.benchmarkSessionMatches(12L, 12L, 12L, 12L));
        assertFalse(AndroidEmulationRuntime.benchmarkSessionMatches(11L, 12L, 12L, 12L));
        assertFalse(AndroidEmulationRuntime.benchmarkSessionMatches(12L, 11L, 12L, 12L));
        assertFalse(AndroidEmulationRuntime.benchmarkSessionMatches(12L, 12L, 11L, 12L));
        assertFalse(AndroidEmulationRuntime.benchmarkSessionMatches(12L, 12L, 12L, 13L));
        assertFalse(AndroidEmulationRuntime.benchmarkSessionMatches(0L, 0L, 0L, 0L));
    }

    @Test
    public void replacementRotatesTheLockedScenarioInputEpoch() {
        eu.rekawek.coffeegb.core.joypad.PlayerInputHub hub =
                new eu.rekawek.coffeegb.core.joypad.PlayerInputHub();
        AndroidInputRouter input = new AndroidInputRouter(hub);
        try {
            assertTrue(AndroidEmulationRuntime.resetBenchmarkScenarioInput(input, true));
            input.setBenchmarkScenarioMask(BenchmarkGameplayScenario.RIGHT_MASK);
            input.endBenchmarkScenario();
            assertTrue(input.benchmarkScenarioSourceClosed());

            assertTrue(AndroidEmulationRuntime.resetBenchmarkScenarioInput(input, true));
            assertFalse(input.benchmarkScenarioSourceClosed());
            input.setBenchmarkScenarioMask(BenchmarkGameplayScenario.B_MASK);
            assertEquals(java.util.Set.of(eu.rekawek.coffeegb.core.joypad.Button.B),
                    hub.sample().buttons(0));
        } finally {
            input.close();
        }
    }

    @Test
    public void replacementRejectsAClosedRouterAndCannotClaimSourceClosure() {
        AndroidInputRouter input = new AndroidInputRouter(
                new eu.rekawek.coffeegb.core.joypad.PlayerInputHub());
        input.close();

        assertFalse(AndroidEmulationRuntime.resetBenchmarkScenarioInput(input, true));
        assertFalse(input.benchmarkScenarioSourceClosed());
    }

    @Test
    public void replacementInvalidatesEveryScheduledScenarioCompletionPoll() {
        assertTrue(AndroidEmulationRuntime.benchmarkCompletionPollMatches(
                4L, 4L, 21L, 21L, 21L, 21L));
        assertFalse(AndroidEmulationRuntime.benchmarkCompletionPollMatches(
                4L, 5L, 21L, 21L, 21L, 21L));
        assertFalse(AndroidEmulationRuntime.benchmarkCompletionPollMatches(
                5L, 5L, 21L, 22L, 22L, 22L));
    }

    @Test
    public void visibilityLossStopsAScheduledCompletionPollBeforeItCanResumeAudio() {
        assertTrue(AndroidEmulationRuntime.benchmarkCompletionPollMayTouchAudio(
                true, 4L, 4L, 21L, 21L, 21L, 21L));
        // All epoch and generation identities still match, but visibility loss has made the
        // diagnostic session terminal. The real poll returns at this guard before reading or
        // resuming AndroidAudioSink.
        assertFalse(AndroidEmulationRuntime.benchmarkCompletionPollMayTouchAudio(
                false, 4L, 4L, 21L, 21L, 21L, 21L));
    }

    @Test
    public void visibilityPauseWinsWhenItRacesAnInFlightAudioResumePoll() throws Exception {
        AndroidEmulationRuntime.BenchmarkAudioLifecycleGate gate =
                new AndroidEmulationRuntime.BenchmarkAudioLifecycleGate();
        AtomicBoolean outputPlaying = new AtomicBoolean(false);
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        CountDownLatch lossAttempted = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> poll = threads.submit(() -> gate.run(() -> {
                pollEntered.countDown();
                await(releasePoll);
                outputPlaying.set(true);
            }));
            assertTrue(pollEntered.await(1, TimeUnit.SECONDS));
            Future<?> visibilityLoss = threads.submit(() -> {
                lossAttempted.countDown();
                gate.run(() -> outputPlaying.set(false));
            });
            assertTrue(lossAttempted.await(1, TimeUnit.SECONDS));
            releasePoll.countDown();
            poll.get(1, TimeUnit.SECONDS);
            visibilityLoss.get(1, TimeUnit.SECONDS);
            assertFalse(outputPlaying.get());
        } finally {
            releasePoll.countDown();
            threads.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for benchmark audio gate test");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for benchmark audio gate test",
                    failure);
        }
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
            assertEquals(ExecutionMode.ACCURACY, properties.getOverrides().getExecutionMode());
        }
    }

    @Test
    public void benchmarkExecutionModeReachesControllerSessionOverrides() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance");
        try (EmulatorProperties properties = new EmulatorProperties(
                AndroidEmulationRuntime.androidSettingsOverrides(options))) {
            assertEquals(ExecutionMode.PERFORMANCE,
                    properties.getOverrides().getExecutionMode());
        }
    }

    @Test
    public void shadowMeasuredWarmupSelectorIsOnlyNativeCgbPerformanceSilentScenario() {
        DiagnosticsOptions eligible = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "performance", "cgb-action-v1", "silent-pcm-v1");
        try (EmulatorProperties properties = new EmulatorProperties(
                AndroidEmulationRuntime.androidSettingsOverrides(eligible))) {
            assertEquals(RuntimeWarmupFlavor.SHADOW_MEASURED_EXACT_V1,
                    properties.getOverrides().getRuntimeWarmupFlavor());
        }

        String[] rejectedHardware = {"dmg", "cgb0"};
        for (String hardware : rejectedHardware) {
            DiagnosticsOptions rejected = DiagnosticsOptions.parseValues(
                    true, hardware, true, "presentation", true, true, false,
                    null, null, null, -1, null, null, null, null, false, null, -1, -1,
                    "performance", "cgb-action-v1", "silent-pcm-v1");
            try (EmulatorProperties properties = new EmulatorProperties(
                    AndroidEmulationRuntime.androidSettingsOverrides(rejected))) {
                assertEquals(RuntimeWarmupFlavor.SCALAR,
                        properties.getOverrides().getRuntimeWarmupFlavor());
            }
        }

        DiagnosticsOptions accuracy = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                null, null, null, -1, null, null, null, null, false, null, -1, -1,
                "accuracy", "cgb-action-v1", "silent-pcm-v1");
        try (EmulatorProperties properties = new EmulatorProperties(
                AndroidEmulationRuntime.androidSettingsOverrides(accuracy))) {
            assertEquals(RuntimeWarmupFlavor.SCALAR,
                    properties.getOverrides().getRuntimeWarmupFlavor());
        }
    }

    @Test
    public void ordinaryExecutionModeUsesLiveControllerPropertyForReloads() {
        try (EmulatorProperties properties = new EmulatorProperties(
                AndroidEmulationRuntime.androidSettingsOverrides())) {
            properties.setProperty(EmulatorProperties.Key.ExecutionMode, "PERFORMANCE");
            assertEquals(ExecutionMode.PERFORMANCE, properties.getSystem().getExecutionMode());
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
                assertEquals(BootstrapMode.FAST_FORWARD,
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
