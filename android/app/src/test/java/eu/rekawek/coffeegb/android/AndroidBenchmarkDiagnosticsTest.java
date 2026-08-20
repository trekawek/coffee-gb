package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    public void benchmarkAnchorMustCompleteBeforeArmAcknowledgement() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.emulationStarted();

        assertEquals("WARMING", diagnostics.phaseForTesting());
        assertFalse(diagnostics.benchmarkAnchorReady());
        diagnostics.benchmarkAnchorPosted(false);
        assertFalse(diagnostics.benchmarkAnchorReady());
        assertFalse(diagnostics.armBenchmark(17L, TOKEN,
                AndroidAudioSink.AudioBaseline.unavailable()));

        diagnostics.benchmarkAnchorPosted(true);
        assertTrue(diagnostics.benchmarkAnchorReady());
        assertEquals("ANCHOR_READY", diagnostics.phaseForTesting());
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(17L, TOKEN,
                AndroidAudioSink.AudioBaseline.unavailable()));
        Controller.BenchmarkArmAcknowledgedEvent ack =
                new Controller.BenchmarkArmAcknowledgedEvent(17L, TOKEN);
        assertEquals(17L, ack.getGeneration());
        assertEquals(TOKEN, ack.getToken());
        assertEquals("ARMED", diagnostics.phaseForTesting());
    }

    @Test
    public void duplicateArmAndStaleEpochAreRejectedAfterControllerAck() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        AndroidBenchmarkDiagnostics diagnostics = armedDiagnostics(23L);

        assertFalse(diagnostics.armBenchmark(24L, TOKEN,
                AndroidAudioSink.AudioBaseline.unavailable()));
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
            new Controller.BenchmarkArmEvent(0L, TOKEN);
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
            new Controller.BenchmarkPhysicalFrameBoundaryEvent(600L, 0L);
            throw new AssertionError("zero epoch accepted");
        } catch (IllegalArgumentException expected) {
            // expected
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
        diagnostics.emulationStarted();
        diagnostics.benchmarkAnchorPosted(true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(41L, TOKEN,
                AndroidAudioSink.AudioBaseline.unavailable()));
        for (int frame = 1; frame <= 600; frame++) {
            diagnostics.frameReady();
        }
        diagnostics.benchmarkFrameBoundary(
                new Controller.BenchmarkFrameBoundaryEvent(600L, true, true, 1));
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
    }

    private static AndroidBenchmarkDiagnostics armedDiagnostics(long generation) {
        AndroidBenchmarkDiagnostics diagnostics = newDiagnostics();
        diagnostics.sessionLaunch();
        diagnostics.emulationStarted();
        diagnostics.benchmarkAnchorPosted(true);
        diagnostics.audioFocusResult(true);
        assertTrue(diagnostics.armBenchmark(generation, TOKEN,
                AndroidAudioSink.AudioBaseline.unavailable()));
        assertEquals(generation, diagnostics.benchmarkGeneration());
        return diagnostics;
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
}
