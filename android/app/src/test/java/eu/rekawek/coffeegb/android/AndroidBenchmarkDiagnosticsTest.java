package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode;
import eu.rekawek.coffeegb.core.Gameboy.BootstrapOutcome;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Run-control tests for the benchmark APK. The default debug unit-test variant intentionally
 * skips these because its compile-time diagnostics flag is false; the non-debuggable benchmark
 * variant has an explicitly enabled unit-test component and executes them with the real
 * diagnostics specialization.
 */
public class AndroidBenchmarkDiagnosticsTest {

    private static final String TOKEN = "benchmark-token-0001";
    private static final long SESSION = 7L;

    @Test
    public void benchmarkAnchorMustCompleteBeforeArmAcknowledgement() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION);
        diagnostics.emulationStarted(SESSION);

        assertEquals("WARMING", diagnostics.phaseForTesting());
        assertFalse(diagnostics.benchmarkAnchorReady(SESSION));
        diagnostics.benchmarkAnchorPosted(SESSION, false);
        assertFalse(diagnostics.benchmarkAnchorReady(SESSION));
        assertFalse(diagnostics.armBenchmark(SESSION, 17L, TOKEN, readyAudioBaseline()));

        diagnostics.benchmarkAnchorPosted(SESSION, true);
        assertTrue(diagnostics.benchmarkAnchorReady(SESSION));
        assertEquals("ANCHOR_READY", diagnostics.phaseForTesting());
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(SESSION, 17L, TOKEN, readyAudioBaseline()));
        Controller.BenchmarkArmAcknowledgedEvent ack =
                new Controller.BenchmarkArmAcknowledgedEvent(17L, TOKEN, SESSION);
        assertEquals(17L, ack.getGeneration());
        assertEquals(TOKEN, ack.getToken());
        assertEquals(SESSION, ack.getSessionGeneration());
        assertEquals("ARMED", diagnostics.phaseForTesting());
    }

    @Test
    public void duplicateArmAndStaleEpochAreRejectedAfterControllerAck() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = armedDiagnostics(23L);

        assertFalse(diagnostics.armBenchmark(SESSION, 24L, TOKEN, readyAudioBaseline()));
        assertTrue(diagnostics.acceptsFrameEpoch(23L));
        assertFalse(diagnostics.acceptsFrameEpoch(24L));

        for (int frame = 1; frame < 600; frame++) {
            assertFalse(diagnostics.frameReady());
        }
        assertTrue(diagnostics.frameReady());
        assertEquals(600L, diagnostics.readyFramesForTesting());
        assertEquals("CORE_FROZEN", diagnostics.phaseForTesting());

        // The frame-600 sample is captured from the actual physical Display boundary, not from
        // the controller/audio cadence.  It is the only event that supplies final speed evidence.
        diagnostics.benchmarkFrameBoundary(
                new Controller.BenchmarkFrameBoundaryEvent(600L, false, false, 1));
        assertFalse(diagnostics.finalResultEmittedForTesting());
        assertEquals(600L, diagnostics.readyFramesForTesting());
        assertFalse(diagnostics.frameReady());
        assertEquals(600L, diagnostics.readyFramesForTesting());
    }

    @Test
    public void physicalReady600ThenSubmitted600ClosesEpochAndRejects601() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = armedDiagnostics(31L);
        NativeFrameStore frames = new NativeFrameStore(diagnostics);
        try {
            frames.beginBenchmarkEpoch(31L);
            int[] pixels = new int[160 * 144];
            frames.publish(new eu.rekawek.coffeegb.core.gpu.Display.DmgFrameReadyEvent(pixels));
            NativeFrameStore.Frame anchorFrame = frames.takeLatest();
            assertTrue(anchorFrame != null);
            assertEquals(31L, anchorFrame.epoch());
            frames.frameSubmitted(anchorFrame);
            frames.finishDrawing(anchorFrame);

            // The remaining boundaries are delivered by the synchronous Display seam.  The
            // store's epoch above proves the renderer callback is linked to this arm generation.
            for (int frame = 2; frame <= 600; frame++) {
                assertEquals(frame == 600, diagnostics.frameReady());
            }
        } finally {
            frames.close();
        }
        assertEquals("CORE_FROZEN", diagnostics.phaseForTesting());
        diagnostics.benchmarkFrameBoundary(
                new Controller.BenchmarkFrameBoundaryEvent(600L, false, false, 1));

        for (int submission = 2; submission <= 600; submission++) {
            diagnostics.frameSubmitted(submission);
        }
        assertEquals(600L, diagnostics.submittedFramesForTesting());
        assertEquals("SUBMISSIONS_COMPLETE", diagnostics.phaseForTesting());
        diagnostics.benchmarkDrainPosted(true);
        assertEquals("DONE", diagnostics.phaseForTesting());
        assertTrue(diagnostics.finalResultEmittedForTesting());
        assertFalse(diagnostics.acceptsFrameEpoch(31L));

        // A stale renderer callback and a physical 601st publication cannot extend the measured
        // window or manufacture a second accepted submission.
        diagnostics.frameSubmitted(601L);
        assertFalse(diagnostics.frameReady());
        assertEquals(600L, diagnostics.readyFramesForTesting());
        assertEquals(600L, diagnostics.submittedFramesForTesting());
    }

    @Test
    public void invalidArmAndBoundaryEventsAreRejectedByTypedContracts() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        try {
            new Controller.BenchmarkArmEvent(0L, TOKEN, SESSION);
            throw new AssertionError("zero generation accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new Controller.BenchmarkFrameBoundaryEvent(600L, false, false, 0);
            throw new AssertionError("invalid speed accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new Controller.BenchmarkFrameBoundaryEvent(
                    600L, false, false, 1, 0L, 0L,
                    0L, 0L, -1, 0L, 0L);
            throw new AssertionError("negative epoch maximum accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new Controller.BenchmarkPhysicalFrameBoundaryEvent(600L, 0L);
            throw new AssertionError("zero epoch accepted");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void bootstrapReadyIsGenerationBoundAndAcceptsOnlyAuthenticRequestedOutcome() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", false, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60);
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> 1_000_000L);
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION);

        Controller.BootstrapReadyEvent stale = new Controller.BootstrapReadyEvent(
                SESSION - 1L, BootstrapMode.FAST_FORWARD, BootstrapOutcome.AUTHENTIC_HANDOFF,
                HardwareProfileRegistry.DMG, HardwareProfileRegistry.DMG.identity(), false, false, 1);
        assertFalse(diagnostics.acceptBootstrapReady(stale));
        assertFalse(diagnostics.bootstrapReadyForTesting());

        Controller.BootstrapReadyEvent fallback = new Controller.BootstrapReadyEvent(
                SESSION, BootstrapMode.FAST_FORWARD, BootstrapOutcome.TIMED_OUT_FALLBACK,
                HardwareProfileRegistry.DMG, HardwareProfileRegistry.DMG.identity(), false, false, 1);
        assertFalse(diagnostics.acceptBootstrapReady(fallback));
        assertFalse(diagnostics.bootstrapReadyForTesting());

        Controller.BootstrapReadyEvent ready = new Controller.BootstrapReadyEvent(
                SESSION, BootstrapMode.FAST_FORWARD, BootstrapOutcome.AUTHENTIC_HANDOFF,
                HardwareProfileRegistry.DMG, HardwareProfileRegistry.DMG.identity(), false, false, 1);
        assertTrue(diagnostics.acceptBootstrapReady(ready));
        assertTrue(diagnostics.bootstrapReadyForTesting());
        assertEquals(BootstrapOutcome.AUTHENTIC_HANDOFF,
                diagnostics.bootstrapOutcomeForTesting());
        assertFalse(diagnostics.acceptBootstrapReady(ready));
        assertTrue(records.stream().anyMatch(line -> line.startsWith("event=boot_result ")
                && line.contains("requested_bootstrap=fast-forward")
                && line.contains("bootstrap_outcome=authentic_handoff")
                && line.contains("session_generation=7")));
    }

    @Test
    public void bootstrapSkipRequiresSkippedOutcomeAndFullRequiresAuthenticHandoff() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        for (String mode : new String[]{"skip", "full"}) {
            BootstrapMode requested = "skip".equals(mode)
                    ? BootstrapMode.SKIP : BootstrapMode.NORMAL;
            DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                    true, "dmg", true, "presentation", false, true, false,
                    null, null, null, -1, null, null, null, null, false, null, -1, -1,
                    "accuracy", null, null, null, null, null, mode);
            AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                    null, options, message -> { }, () -> 1_000_000L);
            diagnostics.sessionLaunch();
            diagnostics.beginSession(SESSION);
            BootstrapOutcome wrong = requested == BootstrapMode.SKIP
                    ? BootstrapOutcome.AUTHENTIC_HANDOFF : BootstrapOutcome.SKIPPED;
            assertFalse(diagnostics.acceptBootstrapReady(new Controller.BootstrapReadyEvent(
                    SESSION, requested, wrong, HardwareProfileRegistry.DMG,
                    HardwareProfileRegistry.DMG.identity(), false, false, 1)));
            BootstrapOutcome right = requested == BootstrapMode.SKIP
                    ? BootstrapOutcome.SKIPPED : BootstrapOutcome.AUTHENTIC_HANDOFF;
            assertTrue(diagnostics.acceptBootstrapReady(new Controller.BootstrapReadyEvent(
                    SESSION, requested, right, HardwareProfileRegistry.DMG,
                    HardwareProfileRegistry.DMG.identity(), false, false, 1)));
        }
    }

    @Test
    public void finalResultUsesThePhysicalFrameSixHundredHardwareEvidence() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60);
        AtomicLong now = new AtomicLong();
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> now.addAndGet(1_000_000L));
        diagnostics.sessionLaunch();
        diagnostics.hardwareProfile(new Controller.HardwareProfileEvent(
                HardwareProfileRegistry.CGB,
                HardwareProfileRegistry.CGB.identity(), true, false, 1));
        diagnostics.beginSession(SESSION);
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(SESSION, 41L, TOKEN, readyAudioBaseline()));
        for (int frame = 1; frame <= 600; frame++) {
            diagnostics.frameReady();
        }
        diagnostics.benchmarkFrameBoundary(
                new Controller.BenchmarkFrameBoundaryEvent(
                        600L, true, true, 1, 12L, 345L,
                        67L, 8_901L, 64, 7_654L, 4_321L, 4_000L));
        for (int submission = 1; submission <= 600; submission++) {
            diagnostics.frameSubmitted(submission);
        }
        diagnostics.benchmarkDrainPosted(true);
        String finalRecord = records.stream()
                .filter(line -> line.startsWith("event=final_result"))
                .findFirst()
                .orElseThrow();
        assertTrue(finalRecord.contains("effective_gbc=true"));
        assertTrue(finalRecord.contains("effective_dmg_compat=true"));
        assertTrue(finalRecord.contains("effective_mode=cgb-dmg-compat"));
        String speedRecord = records.stream()
                .filter(line -> line.startsWith("event=speed_sample"))
                .findFirst()
                .orElseThrow();
        assertTrue(speedRecord.contains("performance_bulk_spans=12"));
        assertTrue(speedRecord.contains("performance_bulk_ticks=345"));
        assertTrue(speedRecord.contains("performance_epoch_count=67"));
        assertTrue(speedRecord.contains("performance_epoch_ticks=8901"));
        assertTrue(speedRecord.contains("performance_epoch_max_ticks=64"));
        assertTrue(speedRecord.contains("performance_epoch_raster_fast_ticks=7654"));
        assertTrue(speedRecord.contains("performance_epoch_mode2_replay_ticks=4321"));
        assertTrue(speedRecord.contains("performance_epoch_mode2_bulk_ticks=4000"));
        assertTrue(speedRecord.contains("benchmark_audio_dropped_channel_ticks=0"));
    }

    private static AndroidBenchmarkDiagnostics armedDiagnostics(long generation) {
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION);
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(
                SESSION, generation, TOKEN, readyAudioBaseline()));
        assertEquals(generation, diagnostics.benchmarkGeneration());
        return diagnostics;
    }

    @Test
    public void staleSessionCannotPublishAnchorOrArmReplacement() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(11L);
        diagnostics.emulationStarted(11L);
        diagnostics.beginSession(12L);

        diagnostics.benchmarkAnchorPosted(11L, true);
        assertFalse(diagnostics.benchmarkAnchorReady(12L));
        diagnostics.emulationStarted(12L);
        diagnostics.benchmarkAnchorPosted(12L, true);
        diagnostics.audioFocusResult(true);
        assertFalse(diagnostics.armBenchmark(11L, 51L, TOKEN, readyAudioBaseline()));
        assertTrue(diagnostics.armBenchmark(12L, 52L, TOKEN, readyAudioBaseline()));
    }

    @Test
    public void armRequiresEmptyPlayingAudioBaseline() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION);
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.armBenchmark(
                SESSION, 61L, TOKEN, audioBaseline(4L, 0L, true)));
        assertFalse(diagnostics.armBenchmark(
                SESSION, 62L, TOKEN, audioBaseline(0L, 0L, false)));
        assertTrue(diagnostics.armBenchmark(
                SESSION, 63L, TOKEN, readyAudioBaseline()));
    }

    @Test
    public void silentPcmArmRequiresFreshStrictMutedHostProof() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newSilentDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION, silentSessionStartAudioBaseline());
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.armBenchmark(SESSION, 64L, TOKEN, readyAudioBaseline()));
        assertTrue(diagnostics.systemAudioBadLatched());

        // A system-audio ARM failure is terminal for its diagnostics/session generation. A
        // later valid admission must start from a fresh diagnostics/session instance.
        AndroidBenchmarkDiagnostics valid = newSilentDiagnostics();
        valid.sessionLaunch();
        valid.beginSession(SESSION + 1L, silentSessionStartAudioBaseline());
        valid.emulationStarted(SESSION + 1L);
        valid.benchmarkAnchorPosted(SESSION + 1L, true);
        valid.audioFocusResult(true);
        assertTrue(valid.armBenchmark(SESSION + 1L, 65L, TOKEN, silentAudioBaseline()));
    }

    @Test
    public void silentPcmArmRejectsAnyPriorFocusLossEvenAfterRegain() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newSilentDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION, silentSessionStartAudioBaseline());
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);
        diagnostics.audioFocusLost();
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.armBenchmark(SESSION, 66L, TOKEN, silentAudioBaseline()));
    }

    @Test
    public void silentPcmArmRejectsPcmEvidenceInheritedFromAnOlderSession() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newSilentDiagnostics();
        diagnostics.sessionLaunch();
        AndroidAudioSink.AudioBaseline inherited = silentAudioBaseline();
        diagnostics.beginSession(SESSION, inherited);
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.armBenchmark(SESSION, 67L, TOKEN, inherited));
    }

    @Test
    public void silentPcmSystemAudioAllGoodHasArmIntervalsAndTerminalProof() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = armedSilentDiagnostics(null);
        for (int frame = 60; frame <= 600; frame += 60) {
            diagnostics.sampleSystemAudioForTesting(frame, silentAudioBaseline());
        }
        diagnostics.sampleSystemAudioForTesting(600, silentAudioBaseline());
        assertEquals(12, diagnostics.systemAudioSampleCountForTesting());
        assertEquals(0, diagnostics.systemAudioBadCountForTesting());
        assertEquals(600, diagnostics.systemAudioLastFrameForTesting());
    }

    @Test
    public void silentPcmSystemAudioFailureAtFrame60RevokesOnceAndStaysFrozen() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AtomicInteger revocations = new AtomicInteger();
        AndroidBenchmarkDiagnostics diagnostics = armedSilentDiagnostics(revocations);
        diagnostics.sampleSystemAudioForTesting(60, systemAudioBaseline(1, 15, false));
        diagnostics.sampleSystemAudioForTesting(540, systemAudioBaseline(1, 15, false));
        assertEquals(1, revocations.get());
        assertEquals(1, diagnostics.systemAudioBadCountForTesting());
    }

    @Test
    public void silentPcmSystemAudioFailureAtFrame540RevokesAndUnavailableFailsClosed() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AtomicInteger revocations = new AtomicInteger();
        AndroidBenchmarkDiagnostics diagnostics = armedSilentDiagnostics(revocations);
        diagnostics.sampleSystemAudioForTesting(540, systemAudioBaseline(1, 15, false));
        diagnostics.sampleSystemAudioForTesting(600, null);
        assertEquals(1, revocations.get());
        assertEquals(1, diagnostics.systemAudioBadCountForTesting());
    }

    @Test
    public void scriptedSessionNeedsCurrentExactCompletionEvidence() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60, -1, "performance", "dmg-action-v1");
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> 1_000_000L);
        diagnostics.beginSession(71L);

        diagnostics.benchmarkScenarioCompleted(70L, 313, 313, true, true, true);
        diagnostics.emulationStarted(71L);
        assertEquals("SCENARIO_RUNNING", diagnostics.phaseForTesting());
        diagnostics.benchmarkScenarioCompleted(71L, 312, 313, true, true, true);
        diagnostics.emulationStarted(71L);
        assertEquals("SCENARIO_RUNNING", diagnostics.phaseForTesting());
        diagnostics.benchmarkScenarioCompleted(71L, 313, 313, true, true, true);
        diagnostics.emulationStarted(71L);
        assertEquals("WARMING", diagnostics.phaseForTesting());
        String completion = records.stream()
                .filter(line -> line.startsWith("event=scenario_complete")
                        && line.contains("completed=true"))
                .findFirst()
                .orElseThrow();
        assertTrue(completion.contains("session_generation=71"));
        assertTrue(completion.contains("completed_frames=313"));
        assertTrue(completion.contains("source_closed=true"));
        assertTrue(completion.contains("audio_drained=true"));
        assertTrue(completion.contains("artifact_id="));
        assertTrue(completion.contains("pair_id=pair-0001"));
        assertTrue(completion.contains("matrix_block=block-0001"));
        assertTrue(completion.contains("row_order=0"));
        assertTrue(completion.contains("run_side=parent"));
    }

    @Test
    public void visibilityLossIsTerminalBeforeScenarioOrAnchorArm() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        AndroidBenchmarkDiagnostics diagnostics = scriptedDiagnostics(records);
        diagnostics.sessionLaunch();
        diagnostics.beginSession(81L);

        diagnostics.benchmarkVisibilityLost();
        diagnostics.benchmarkScenarioCompleted(81L, 313, 313, true, true, true);
        diagnostics.emulationStarted(81L);
        diagnostics.benchmarkAnchorPosted(81L, true);
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.benchmarkPreArmValid(81L));
        assertFalse(diagnostics.benchmarkAnchorReady(81L));
        assertFalse(diagnostics.armBenchmark(81L, 71L, TOKEN, readyAudioBaseline()));
        assertTrue(records.stream().anyMatch(line ->
                line.startsWith("event=benchmark_invalidated artifact_id=")
                        && line.contains(" pair_id=pair-0001 ")
                        && line.contains(" matrix_block=block-0001 ")
                        && line.contains(" row_order=0 run_side=parent ")
                        && line.endsWith("session_generation=81"
                                + " phase=scenario_running reason=visibility_lost")));

        // A replacement in the same runtime remains poisoned even after the old generation is
        // invalidated. Only a fresh runtime/sessionLaunch may establish continuous visibility.
        diagnostics.invalidateSession();
        diagnostics.beginSession(82L);
        diagnostics.benchmarkScenarioCompleted(82L, 313, 313, true, true, true);
        diagnostics.emulationStarted(82L);
        assertEquals("SCENARIO_RUNNING", diagnostics.phaseForTesting());
        diagnostics.benchmarkAnchorPosted(82L, true);
        assertFalse(diagnostics.benchmarkAnchorReady(82L));
        assertFalse(diagnostics.armBenchmark(82L, 72L, TOKEN, readyAudioBaseline()));

        AndroidBenchmarkDiagnostics fresh = scriptedDiagnostics(new ArrayList<>());
        fresh.sessionLaunch();
        fresh.beginSession(83L);
        fresh.benchmarkScenarioCompleted(83L, 313, 313, true, true, true);
        fresh.emulationStarted(83L);
        fresh.benchmarkAnchorPosted(83L, true);
        fresh.audioFocusResult(true);
        assertTrue(fresh.benchmarkAnchorReady(83L));
        assertTrue(fresh.armBenchmark(83L, 73L, TOKEN, readyAudioBaseline()));
    }

    @Test
    public void loadingVisibilityLossPoisonsTheNextPublishedSessionGeneration() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60);
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> 1_000_000L);
        diagnostics.sessionLaunch();
        diagnostics.invalidateSession();

        diagnostics.benchmarkVisibilityLost();
        diagnostics.beginSession(91L);
        diagnostics.emulationStarted(91L);
        diagnostics.benchmarkAnchorPosted(91L, true);
        diagnostics.audioFocusResult(true);

        assertFalse(diagnostics.benchmarkPreArmValid(91L));
        assertFalse(diagnostics.benchmarkAnchorReady(91L));
        assertFalse(diagnostics.armBenchmark(91L, 81L, TOKEN, readyAudioBaseline()));
        assertTrue(records.stream().anyMatch(line ->
                line.startsWith("event=benchmark_invalidated artifact_id=")
                        && line.contains(" pair_id=pair-0001 ")
                        && line.contains(" matrix_block=block-0001 ")
                        && line.contains(" row_order=0 run_side=parent ")
                        && line.endsWith("session_generation=91"
                                + " phase=idle reason=visibility_lost")));
    }

    @Test
    public void productionLengthFinalRecordStaysBelowBoundedUtf8Payload() throws Exception {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        String longToken = "a".repeat(64);
        String workloadNonce = "b".repeat(64);
        long sessionGeneration = 9_223_372_036_854_775_000L;
        long benchmarkGeneration = 9_223_372_036_854_774_000L;
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                longToken, "c".repeat(64), "d".repeat(64), 6,
                "variant", "variant", "e".repeat(64), "f".repeat(64), true,
                workloadNonce, 120, 9, "performance", "cgb-action-v1",
                "silent-pcm-relaxed-apu-v1");
        AtomicLong now = new AtomicLong(9_000_000_000_000_000L);
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> now.addAndGet(1_000_000L),
                "1".repeat(64), "2".repeat(64));
        diagnostics.sessionLaunch();
        diagnostics.setWorkloadNonce(workloadNonce);
        diagnostics.hardwareProfile(new Controller.HardwareProfileEvent(
                HardwareProfileRegistry.CGB,
                HardwareProfileRegistry.CGB.identity(), true, false, 2));
        diagnostics.beginSession(sessionGeneration, silentSessionStartAudioBaseline());
        diagnostics.benchmarkScenarioCompleted(
                sessionGeneration, 923, 923, true, true, true);
        diagnostics.emulationStarted(sessionGeneration);
        diagnostics.benchmarkAnchorPosted(sessionGeneration, true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(
                sessionGeneration, benchmarkGeneration, "z".repeat(64),
                productionLikeAudioBaseline()));
        diagnostics.setSystemAudioBaselineForTesting(productionLikeAudioBaseline());
        for (int frame = 1; frame <= 600; frame++) {
            diagnostics.frameReady();
        }
        diagnostics.benchmarkFrameBoundary(new Controller.BenchmarkFrameBoundaryEvent(
                600L, true, false, 2, 9_999_999L, 999_999_999L,
                999_999_999L, Long.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                "silent-pcm-relaxed-apu-v1", true, true, true,
                999_999_999L, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, 999_999_999L, 999_999_999L, 999_999_999L, null));
        for (int submission = 1; submission <= 600; submission++) {
            diagnostics.frameSubmitted(submission);
        }
        java.lang.reflect.Field terminalStats = AndroidBenchmarkDiagnostics.class
                .getDeclaredField("audioTerminalStats");
        terminalStats.setAccessible(true);
        terminalStats.set(diagnostics, productionLikeAudioStats());
        diagnostics.benchmarkDrainPosted(true);

        String finalRecord = records.stream()
                .filter(line -> line.startsWith("event=final_result"))
                .findFirst()
                .orElseThrow();
        String matrixRecord = records.stream()
                .filter(line -> line.startsWith("event=matrix_run"))
                .findFirst()
                .orElseThrow();
        assertTrue(matrixRecord.contains("audio_start_input_events=999999999"));
        assertFalse(finalRecord.contains("audio_start_input_events="));
        assertTrue(finalRecord.contains("audio_pcm_input_events=999999999"));
        assertTrue(finalRecord.contains("benchmark_audio_policy=silent-pcm-relaxed-apu-v1"));
        assertTrue(finalRecord.contains("benchmark_audio_flags=111"));
        assertTrue(finalRecord.contains("benchmark_audio_calendar=999999999,999999999,999999999,999999999,999999999,999999999,999999999,999999999"));
        assertTrue(finalRecord.contains("system_audio_sample_count=12"));
        assertTrue(finalRecord.contains("system_audio_bad_count=0"));
        assertTrue(finalRecord.getBytes(StandardCharsets.UTF_8).length
                <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES);
        for (String record : records) {
            assertTrue(record.getBytes(StandardCharsets.UTF_8).length
                    <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES);
        }
    }

    @Test
    public void productionLengthGoalFinalRecordStaysBelowBoundedUtf8Payload() throws Exception {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        List<String> records = new ArrayList<>();
        String longToken = "a".repeat(64);
        String workloadNonce = "b".repeat(64);
        long sessionGeneration = 9_223_372_036_854_775_000L;
        long benchmarkGeneration = 9_223_372_036_854_774_000L;
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                longToken, "c".repeat(64), "d".repeat(64), 6,
                "parent", "parent", "ignored-device", "thermal", true,
                null, 120, 2, "performance", null, "silent-pcm-v1",
                BenchmarkWorkload.MATRIX_VERSION, "c1-cgb-native", "c1", "fast-forward");
        AtomicLong now = new AtomicLong(9_000_000_000_000_000L);
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> now.addAndGet(1_000_000L),
                "1".repeat(64), "2".repeat(64));
        diagnostics.sessionLaunch();
        assertTrue(diagnostics.setWorkloadNonce(workloadNonce));
        diagnostics.hardwareProfile(new Controller.HardwareProfileEvent(
                HardwareProfileRegistry.CGB,
                HardwareProfileRegistry.CGB.identity(), true, false, 1));
        diagnostics.beginSession(sessionGeneration, silentSessionStartAudioBaseline());
        diagnostics.benchmarkScenarioCompleted(
                sessionGeneration, 1_582, 1_582, true, true, true);
        diagnostics.emulationStarted(sessionGeneration);
        diagnostics.benchmarkAnchorPosted(sessionGeneration, true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(
                sessionGeneration, benchmarkGeneration, "z".repeat(64),
                productionLikeAudioBaseline()));
        diagnostics.setSystemAudioBaselineForTesting(productionLikeAudioBaseline());
        for (int frame = 1; frame <= 600; frame++) {
            diagnostics.frameReady();
        }
        diagnostics.benchmarkFrameBoundary(new Controller.BenchmarkFrameBoundaryEvent(
                600L, true, false, 2, 9_999_999L, 999_999_999L,
                999_999_999L, Long.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                "silent-pcm-v1", true, true, true,
                42_134_400L, 766_080L, 603L, 999_999_999L,
                999_999_999L, 999_999_999L, 0L, 0L, goalTelemetrySnapshot()));
        for (int submission = 1; submission <= 600; submission++) {
            diagnostics.frameSubmitted(submission);
        }
        java.lang.reflect.Field terminalStats = AndroidBenchmarkDiagnostics.class
                .getDeclaredField("audioTerminalStats");
        terminalStats.setAccessible(true);
        terminalStats.set(diagnostics, productionLikeAudioStats());
        diagnostics.benchmarkDrainPosted(true);

        String finalRecord = records.stream()
                .filter(line -> line.startsWith("event=final_result"))
                .findFirst()
                .orElseThrow();
        String coreRecord = records.stream()
                .filter(line -> line.startsWith("event=core_result"))
                .findFirst()
                .orElseThrow();
        int finalBytes = finalRecord.getBytes(StandardCharsets.UTF_8).length;
        int coreBytes = coreRecord.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(2_970, finalBytes);
        assertEquals(1_561, coreBytes);
        assertTrue("goal final payload bytes=" + finalBytes,
                finalBytes <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES);
        assertTrue("goal core payload bytes=" + coreBytes,
                coreBytes <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES);
        // Keep an explicit safety margin so a future identity field cannot silently reach the
        // Android log cliff.  This assertion is intentionally against the actual emitted record,
        // not a hand-built approximation of the production concatenation.
        assertTrue("goal final payload has insufficient headroom: " + finalBytes,
                finalBytes <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES - 64);
        assertTrue(finalRecord.contains("matrix_version=" + BenchmarkWorkload.MATRIX_VERSION));
        assertTrue(finalRecord.contains("benchmark_generation=" + benchmarkGeneration));
        assertTrue(finalRecord.contains("requested_hardware=cgb"));
        assertTrue(finalRecord.contains("clock_ticks_num=4194304"));
        assertTrue(finalRecord.contains("clock_ticks_den=1"));
        assertTrue(finalRecord.contains("clock_frames_num=60"));
        assertTrue(finalRecord.contains("clock_frames_den=1"));
        assertTrue(finalRecord.contains("clock_ticks_frame=69905"));
        assertTrue(finalRecord.contains("core_result_id="));
        assertTrue(finalRecord.contains("audio_start_ledger="));
        for (String record : records) {
            assertTrue(record.getBytes(StandardCharsets.UTF_8).length
                    <= AndroidBenchmarkDiagnostics.MAX_LOG_RECORD_BYTES);
        }
    }

    @Test
    public void goalSgbCoreResultBindsFrameAllocationsToArtifactSide() throws Exception {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        Method validCoreResult = AndroidBenchmarkDiagnostics.class.getDeclaredMethod(
                "validCoreResult",
                eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot.class);
        validCoreResult.setAccessible(true);

        AndroidBenchmarkDiagnostics parent = goalSgbDiagnostics("parent");
        assertTrue((Boolean) validCoreResult.invoke(parent,
                goalTelemetrySnapshot(3_830_400L, 600L, 13_824_000L)));
        assertFalse((Boolean) validCoreResult.invoke(parent,
                goalTelemetrySnapshot(3_830_400L, 599L, 13_824_000L)));
        assertFalse((Boolean) validCoreResult.invoke(parent,
                goalTelemetrySnapshot(3_830_400L, 601L, 13_824_000L)));

        AndroidBenchmarkDiagnostics candidate = goalSgbDiagnostics("candidate");
        assertTrue((Boolean) validCoreResult.invoke(candidate,
                goalTelemetrySnapshot(3_830_400L, 0L, 13_824_000L)));
        assertFalse((Boolean) validCoreResult.invoke(candidate,
                goalTelemetrySnapshot(3_830_400L, 1L, 13_824_000L)));
    }

    private static AndroidBenchmarkDiagnostics goalSgbDiagnostics(String side) {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "sgb", true, "presentation", true, true, false,
                "build-token-0001", "pair-token-0001", "block-token-0001", 2,
                side, "parent", "device-build-0001", "thermal-window-0001", true,
                null, 120, 0, "performance", null, "silent-pcm-v1",
                BenchmarkWorkload.MATRIX_VERSION, "d-sgb", "d", "fast-forward");
        return new AndroidBenchmarkDiagnostics(null, options, ignored -> { },
                System::nanoTime, "1".repeat(64), "2".repeat(64));
    }

    private static eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot
            goalTelemetrySnapshot() throws Exception {
        return goalTelemetrySnapshot(766_080L, 0L, 0L);
    }

    private static eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot
            goalTelemetrySnapshot(long sampleSlots, long frameArrayAllocations,
                    long centerPixels) throws Exception {
        Class<?> snapshot = eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot.class;
        Constructor<?> constructor = snapshot.getDeclaredConstructor(
                long.class, long.class, long.class, long.class, int.class,
                long.class, long.class, int.class, long.class, long.class, int.class,
                long.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
        return (eu.rekawek.coffeegb.core.Gameboy.PerformanceTelemetrySnapshot) constructor.newInstance(
                42_134_400L, 42_134_400L, 0L, 0L, 0,
                0L, 0L, 0, 0L, 0L, 0,
                0L, 0L, 0L, 0L, 0L,
                42_134_400L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 42_134_400L, sampleSlots,
                1L, frameArrayAllocations, 0L, centerPixels);
    }

    private static AndroidAudioSink.AudioBaseline readyAudioBaseline() {
        return audioBaseline(0L, 0L, true);
    }

    private static AndroidAudioSink.AudioBaseline productionLikeAudioBaseline() {
        return new AndroidAudioSink.AudioBaseline(
                999_999_999L, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, 999_999_999L, 0L, 999_999_999L,
                0L, 0L, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, 999_999_999L, 0L,
                true, true, 48_000, 6, 140_448, true, false, false, 100,
                0, 15, true, 0, false, 999_999_999L, 999_999_999L);
    }

    private static AndroidAudioSink.Stats productionLikeAudioStats() {
        return new AndroidAudioSink.Stats(
                48_000, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, false, true,
                999_999_999, 999_999_999, 999_999_999,
                999_999_999L, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, 999_999_999L, 999_999_999L, 999_999_999L,
                999_999_999L, 999_999_999L, 6,
                true, true, false, 100,
                999_999_999L, 999_999_999L, 0, 15, true, 6, 999_999_999);
    }

    private static AndroidAudioSink.AudioBaseline audioBaseline(
            long pendingBytes, long queuedBytes, boolean outputPlaying) {
        return new AndroidAudioSink.AudioBaseline(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                pendingBytes, queuedBytes, 0L, 0L, 0L, 0L, 0L, 0L,
                true, outputPlaying, 48_000, 4_800, 140_448);
    }

    private static AndroidBenchmarkDiagnostics newDiagnostics() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60);
        AtomicLong now = new AtomicLong();
        return new AndroidBenchmarkDiagnostics(null, options, message -> {
            // Android's JVM stub Log throws without a device/Robolectric.  The benchmark variant
            // still exercises the real compile-time diagnostics branch with this bounded sink.
        }, () -> now.addAndGet(1_000_000L));
    }

    private static AndroidBenchmarkDiagnostics scriptedDiagnostics(List<String> records) {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60, -1, "performance", "dmg-action-v1");
        AtomicLong now = new AtomicLong();
        return new AndroidBenchmarkDiagnostics(
                null, options, records::add, () -> now.addAndGet(1_000_000L));
    }

    private static AndroidBenchmarkDiagnostics newSilentDiagnostics() {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60, -1, "performance", null, "silent-pcm-v1");
        AtomicLong now = new AtomicLong();
        return new AndroidBenchmarkDiagnostics(null, options, message -> {
        }, () -> now.addAndGet(1_000_000L));
    }

    private static AndroidBenchmarkDiagnostics armedSilentDiagnostics(AtomicInteger revocations) {
        AndroidBenchmarkDiagnostics diagnostics = newSilentDiagnosticsWithRevocations(revocations);
        diagnostics.sessionLaunch();
        diagnostics.beginSession(SESSION, silentSessionStartAudioBaseline());
        diagnostics.emulationStarted(SESSION);
        diagnostics.benchmarkAnchorPosted(SESSION, true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(SESSION, 65L, TOKEN, silentAudioBaseline()));
        return diagnostics;
    }

    private static AndroidBenchmarkDiagnostics newSilentDiagnosticsWithRevocations(
            AtomicInteger revocations) {
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "cgb", true, "presentation", true, true, false,
                "ignored-build", "pair-0001", "block-0001", 0,
                "parent", "parent", "ignored-device", "thermal", true,
                "workload-0001", 60, -1, "performance", null, "silent-pcm-v1");
        AtomicLong now = new AtomicLong();
        return new AndroidBenchmarkDiagnostics(null, options, message -> {
        }, () -> now.addAndGet(1_000_000L), null, null,
                revocations == null ? (generation, session) -> {
                } : (generation, session) -> revocations.incrementAndGet());
    }

    private static AndroidAudioSink.AudioBaseline systemAudioBaseline(
            int systemVolume, int systemVolumeMax, boolean systemMuted) {
        return new AndroidAudioSink.AudioBaseline(
                1L, 1L, 4L, 1L, 4L, 1L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, true, true, 48_000, 6, 140_448,
                true, false, false, 100, systemVolume, systemVolumeMax, systemMuted,
                0, false, 1L, 2L);
    }

    private static AndroidAudioSink.AudioBaseline silentAudioBaseline() {
        return new AndroidAudioSink.AudioBaseline(
                1L, 1L, 4L, 1L, 4L, 1L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, true, true, 48_000, 6, 140_448,
                true, false, false, 100, 0, 15, true, 0, false, 1L, 2L);
    }

    private static AndroidAudioSink.AudioBaseline silentSessionStartAudioBaseline() {
        return new AndroidAudioSink.AudioBaseline(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, true, true, 48_000, 6, 140_448,
                true, false, false, 100, 0, 15, true, 0, false, 1L, 2L);
    }
}
