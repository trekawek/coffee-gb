package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;
import java.util.EnumSet;
import java.util.Random;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

public class PerformanceWorkloadTest {
    private static final Scenario[] MMIO_POLLS = {
            Scenario.IF_POLL, Scenario.IE_POLL, Scenario.DIV_POLL,
            Scenario.TIMA_POLL, Scenario.JOYP_POLL, Scenario.NR52_POLL,
            Scenario.LYC_POLL
    };

    @Test
    public void persistentMmioLoadFormsKeepStateAndBatchBetweenFencedReads() throws Exception {
        Profile[] profiles = {Profile.DMG, Profile.CGB_X2, Profile.CGB0_COMPAT, Profile.SGB2};
        for (int s = 0; s < MMIO_POLLS.length; s++) {
            Scenario scenario = MMIO_POLLS[s];
            for (int row = 0; row < profiles.length; row++) {
                Profile profile = profiles[row];
                // Every register sees all four forms, without multiplying every hardware axis.
                PollingForm form = PollingForm.values()[(s + row) % 4];
                for (int spacing : new int[]{0, 8}) {
                    byte[] image = pollingImage(scenario, profile, form, spacing);
                    try (Gameboy reference = pollingSession(image, profile, new PlayerInputHub());
                         Gameboy candidate = pollingSession(image, profile, new PlayerInputHub())) {
                        preparePollingPair(reference, candidate);
                        try (var oracle = timerOracle(scenario, reference, candidate)) {
                            var diagnostics = new PerformanceDiagnostics(20_000);
                            candidate.setPerformanceDiagnostics(diagnostics);
                            String label = scenario + "/" + profile + "/" + form + "/gap=" + spacing;
                            for (int window = 0; window < 2; window++) {
                                int completedBefore = candidate.getCpu().getRegisters().getDE();
                                long epochsBefore = candidate.getPerformanceEpochTicks();
                                runPollingTicks(reference, candidate, oracle, label, 20_000);
                                assertNotEquals(label + " did not finish a polling loop", completedBefore,
                                        candidate.getCpu().getRegisters().getDE());
                                assertTrue(label + " lost steady batching in window " + window,
                                        candidate.getPerformanceEpochTicks() - epochsBefore >= 1_000);
                                assertStateEquals(label + " window " + window,
                                        reference.captureStateWithoutTimeSource(),
                                        candidate.captureStateWithoutTimeSource());
                            }
                            var report = diagnostics.snapshot();
                            assertEquals(40_000, report.ticks());
                            assertEquals(report.ticks(), report.executionTicks().values().stream()
                                    .mapToLong(Long::longValue).sum());
                            assertTrue(label + " did not exercise the retained MMIO fence",
                                    report.fences().getOrDefault(pollFence(scenario), 0L) > 0);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void mixedMmioPollsKeepDeviceEventsAndRestoredSplitContinuations() throws Exception {
        for (Scenario scenario : MMIO_POLLS) {
            for (Profile profile : new Profile[]{Profile.DMG, Profile.CGB_X2, Profile.CGB0_COMPAT}) {
                PlayerInputHub referenceHub = new PlayerInputHub();
                PlayerInputHub candidateHub = new PlayerInputHub();
                var referenceInput = referenceHub.openSource(0);
                var candidateInput = candidateHub.openSource(0);
                // The ordinary runner uses this exact mixed-form, dense scenario image too.
                byte[] image = image(scenario, profile);
                try (Gameboy reference = pollingSession(image, profile, referenceHub);
                     Gameboy candidate = pollingSession(image, profile, candidateHub)) {
                    preparePollingPair(reference, candidate);
                    if (scenario == Scenario.NR52_POLL) {
                        // Common bus setup after startup: force a real length expiry during reads.
                        for (Gameboy machine : new Gameboy[]{reference, candidate}) {
                            machine.getAddressSpace().setByte(0xff26, 0x80);
                            machine.getAddressSpace().setByte(0xff11, 0);
                            machine.getAddressSpace().setByte(0xff12, 0xf0);
                            machine.getAddressSpace().setByte(0xff14, 0xc0);
                            machine.getAddressSpace().setByte(0xff11, 0x3f);
                            assertEquals(1, machine.getAddressSpace().getByte(0xff26) & 1);
                        }
                    }
                    if (scenario == Scenario.JOYP_POLL) {
                        referenceInput.update(EnumSet.of(Button.RIGHT));
                        candidateInput.update(EnumSet.of(Button.RIGHT));
                    }
                    try (var oracle = timerOracle(scenario, reference, candidate)) {
                        candidate.resetPerformanceBulkCounters();
                        runPollingTicks(reference, candidate, oracle, scenario + "/" + profile, 20_000);
                        assertTrue(scenario + "/" + profile + " mixed-form batching",
                                candidate.getPerformanceEpochTicks() >= 1_000);
                        assertStateEquals(scenario + "/" + profile + " mixed reads",
                                reference.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                        if (scenario == Scenario.JOYP_POLL) {
                            assertEquals("the CPU must observe pressed RIGHT", 0,
                                    candidate.getAddressSpace().getByte(0xc000) & 1);
                            referenceInput.update(EnumSet.noneOf(Button.class));
                            candidateInput.update(EnumSet.noneOf(Button.class));
                        } else if (scenario == Scenario.NR52_POLL) {
                            assertEquals("the CPU must observe channel length expiry", 0,
                                    candidate.getAddressSpace().getByte(0xc000) & 1);
                        } else if (scenario == Scenario.IF_POLL) {
                            assertEquals("the CPU must observe timer IF", 4,
                                    candidate.getAddressSpace().getByte(0xc000) & 4);
                        }
                        var referenceState = reference.captureStateWithoutTimeSource();
                        var candidateState = candidate.captureStateWithoutTimeSource();
                        runPollingTicks(reference, candidate, oracle, "pre-restore prefix", 37);
                        // Each saved state is consumed once: restored component arrays can become live.
                        reference.restoreStateSilently(referenceState);
                        candidate.restoreStateSilently(candidateState);
                        int expectedFrames = oracle == null ? reference.runTicks(12_345) : 0;
                        Random random = new Random(41021L + scenario.ordinal());
                        int remaining = 12_345;
                        int frames = 0;
                        while (remaining > 0) {
                            int count = Math.min(remaining, 1 + random.nextInt(97));
                            int committedFrames = oracle == null ? candidate.runTicks(count)
                                    : oracle.runTicks("restored partition", count);
                            frames += committedFrames;
                            remaining -= count;
                        }
                        if (oracle == null) assertEquals(expectedFrames, frames); // Oracle compares frames per partition.
                        assertStateEquals(scenario + "/" + profile + " restored partitions",
                                reference.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
                        if (scenario == Scenario.JOYP_POLL) {
                            assertEquals("the CPU must observe released RIGHT", 1,
                                    candidate.getAddressSpace().getByte(0xc000) & 1);
                        }
                    }
                }
            }
        }
    }

    private static TimerPollingReadOracle timerOracle(Scenario scenario, Gameboy reference, Gameboy candidate) {
        // Performance has always permitted FF04/05 reads to see the immediately preceding
        // master dot. Validate that exact transaction contract, then retain full state equality.
        return scenario == Scenario.DIV_POLL || scenario == Scenario.TIMA_POLL
                ? new TimerPollingReadOracle(reference, candidate) : null;
    }

    private static int runPollingTicks(Gameboy reference, Gameboy candidate,
                                      TimerPollingReadOracle oracle, String label, int ticks) {
        if (oracle != null) return oracle.runTicks(label, ticks);
        int expectedFrames = reference.runTicks(ticks);
        int frames = candidate.runTicks(ticks);
        assertEquals(label + " frames", expectedFrames, frames);
        return frames;
    }

    private static Gameboy pollingSession(byte[] image, Profile profile, PlayerInputHub input)
            throws java.io.IOException {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile.hardware).setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE).setRtcTimeSource(() -> 0L)
                .setPlayerInputSource(input).setSupportBatterySave(false).build();
    }

    private static void preparePollingPair(Gameboy reference, Gameboy candidate) {
        reference.setPerformanceBatchingEnabled(false);
        candidate.setPerformanceBatchingEnabled(false);
        // Both execute CPU setup identically, including the existing Performance MMIO journals.
        // The measured loop contains reads and WRAM writes; it adds no new journal timing policy.
        assertEquals(reference.runTicks(210_000), candidate.runTicks(210_000));
        candidate.setPerformanceBatchingEnabled(true);
        candidate.resetPerformanceBulkCounters();
    }

    private static PerformanceDiagnostics.Fence pollFence(Scenario scenario) {
        return switch (scenario) {
            case IF_POLL, IE_POLL -> PerformanceDiagnostics.Fence.INTERRUPT;
            case DIV_POLL, TIMA_POLL -> PerformanceDiagnostics.Fence.TIMER;
            case JOYP_POLL -> PerformanceDiagnostics.Fence.JOYPAD;
            case NR52_POLL -> PerformanceDiagnostics.Fence.SOUND;
            case LYC_POLL -> PerformanceDiagnostics.Fence.PPU;
            default -> throw new IllegalArgumentException("Expected MMIO polling scenario");
        };
    }

    @Test
    public void lycPollingChangingWritesAndRestoreBoundariesMatchScalarAcrossEveryProfileAndForm()
            throws Exception {
        for (Profile profile : Profile.values()) {
            for (PollingForm form : PollingForm.values()) {
                byte[] image = pollingImage(Scenario.LYC_POLL, profile, form, 0);
                try (Gameboy reference = pollingSession(image, profile, new PlayerInputHub());
                     Gameboy candidate = pollingSession(image, profile, new PlayerInputHub())) {
                    preparePollingPair(reference, candidate);
                    String label = Scenario.LYC_POLL + "/" + profile + "/" + form;

                    // Use the MMU CPU-write hook so the changing FF45 boundary follows the
                    // canonical peripheral path rather than a debugger-only write.
                    writeLyc(reference, 0x35);
                    writeLyc(candidate, 0x35);
                    assertEquals(label + " initial CPU write", 0x35,
                            reference.getAddressSpace().getByte(0xff45));
                    assertEquals(label + " candidate initial CPU write", 0x35,
                            candidate.getAddressSpace().getByte(0xff45));
                    runPollingTicks(reference, candidate, null, label + " initial", 20_000);
                    assertLycReadResults(reference, 0x35, label + " reference initial reads");
                    assertLycReadResults(candidate, 0x35, label + " candidate initial reads");
                    assertStateEquals(label + " initial state",
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());

                    var savedReference = reference.captureStateWithoutTimeSource();
                    var savedCandidate = candidate.captureStateWithoutTimeSource();
                    writeLyc(reference, 0xa7);
                    writeLyc(candidate, 0xa7);
                    runPollingTicks(reference, candidate, null, label + " changed", 37);
                    assertStateEquals(label + " changed-write state",
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                    runPollingTicks(reference, candidate, null, label + " changed drain", 512);
                    assertLycReadResults(reference, 0xa7, label + " reference changed reads");
                    assertLycReadResults(candidate, 0xa7, label + " candidate changed reads");
                    assertStateEquals(label + " changed-write drained state",
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());

                    reference.restoreStateSilently(savedReference);
                    candidate.restoreStateSilently(savedCandidate);
                    candidate.resetPerformanceBulkCounters();
                    Random random = new Random(0x6c594350L + 97L * profile.ordinal() + form.ordinal());
                    int remaining = 12_345;
                    int referenceFrames = 0;
                    int candidateFrames = 0;
                    while (remaining > 0) {
                        int ticks = Math.min(remaining, 1 + random.nextInt(97));
                        int expected = reference.runTicks(ticks);
                        int actual = candidate.runTicks(ticks);
                        referenceFrames += expected;
                        candidateFrames += actual;
                        assertEquals(label + " restored partition callbacks", expected, actual);
                        remaining -= ticks;
                    }
                    assertEquals(label + " restored frame callbacks",
                            referenceFrames, candidateFrames);
                    assertStateEquals(label + " restored continuation",
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                    assertLycReadResults(reference, 0x35, label + " reference restored reads");
                    assertLycReadResults(candidate, 0x35, label + " candidate restored reads");
                    assertTrue(label + " did not retain ordinary polling work after restore",
                            candidate.getPerformanceEpochTicks() > 0);
                }
            }
        }
    }

    private static void assertLycReadResults(Gameboy gameboy, int expected, String label) {
        for (int offset = 0; offset < 4; offset++) {
            assertEquals(label + " result slot " + offset, expected,
                    gameboy.getAddressSpace().getByte(0xc000 + offset));
        }
    }

    private static void writeLyc(Gameboy gameboy, int value) {
        gameboy.getAddressSpace().setByteFromCpu(0xff45, value);
    }

    @Test
    public void persistentMaskedInterruptsAndPollingBatchAcrossEveryProfile() throws Exception {
        for (Profile profile : Profile.values()) {
            for (Scenario scenario : new Scenario[]{Scenario.CPU, Scenario.MASKED_IRQ,
                    Scenario.STAT_POLL, Scenario.LY_POLL}) {
                try (Gameboy reference = session(scenario, profile);
                     Gameboy candidate = session(scenario, profile)) {
                    reference.setPerformanceBatchingEnabled(false);
                    assertEquals(reference.runTicks(210_000), candidate.runTicks(210_000));
                    PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(25_000);
                    candidate.setPerformanceDiagnostics(diagnostics);
                    for (int window = 0; window < 4; window++) {
                        assertEquals(reference.runTicks(25_000), candidate.runTicks(25_000));
                    }
                    var report = diagnostics.snapshot();
                    assertEquals(100_000, report.ticks());
                    assertEquals(report.ticks(), report.executionTicks().values().stream()
                            .mapToLong(Long::longValue).sum());
                    for (var window : report.windows()) {
                        assertTrue(profile + "/" + scenario + " lost sustained epochs " + window,
                                window.epochTicks() >= 1_000);
                    }
                    assertStateEquals(profile + "/" + scenario,
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                }
            }
        }
    }

    @Test
    public void lcdOffAndArmedHblankKeepTheirBatchCoverageAndState() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB, Profile.CGB_X2, Profile.CGB0,
                Profile.CGB0_X2}) {
            for (Scenario scenario : new Scenario[]{Scenario.LCD_OFF, Scenario.HBLANK_DMA}) {
                try (Gameboy reference = session(scenario, profile);
                     Gameboy candidate = session(scenario, profile)) {
                    reference.setPerformanceBatchingEnabled(false);
                    assertEquals(reference.runTicks(210_000), candidate.runTicks(210_000));
                    candidate.resetPerformanceBulkCounters();
                    assertEquals(reference.runTicks(100_000), candidate.runTicks(100_000));
                    assertTrue(profile + "/" + scenario + " did not retain epochs",
                            candidate.getPerformanceEpochTicks() > 10_000);
                    assertStateEquals(profile + "/" + scenario,
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                    var savedReference = reference.captureStateWithoutTimeSource();
                    var savedCandidate = candidate.captureStateWithoutTimeSource();
                    reference.runTicks(417);
                    candidate.runTicks(417);
                    reference.restoreStateSilently(savedReference);
                    candidate.restoreStateSilently(savedCandidate);
                    assertEquals(reference.runTicks(70_224), candidate.runTicks(70_224));
                    assertStateEquals(profile + "/" + scenario + " restored",
                            reference.captureStateWithoutTimeSource(),
                            candidate.captureStateWithoutTimeSource());
                }
            }
        }
    }

    @Test
    public void lcdOffCompatibilityAndDoubleSpeedHaltRetainTheirClocksAndBlankCallbacks() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB_COMPAT, Profile.CGB0_COMPAT,
                Profile.CGB_X2, Profile.CGB0_X2}) {
            Scenario scenario = profile.color ? Scenario.LCD_OFF_HALT : Scenario.LCD_OFF;
            try (Gameboy reference = session(scenario, profile);
                 Gameboy candidate = session(scenario, profile)) {
                reference.setPerformanceBatchingEnabled(false);
                // Establish the same LCD-off state before comparing the new inert plane.
                // Compatibility mode's existing LCD-on write journal has bounded write skew.
                candidate.setPerformanceBatchingEnabled(false);
                reference.runTicks(210_000);
                candidate.runTicks(210_000);
                candidate.setPerformanceBatchingEnabled(true);
                candidate.resetPerformanceBulkCounters();
                assertEquals(reference.runTicks(210_672), candidate.runTicks(210_672));
                assertTrue(profile + " LCD-off batching",
                        candidate.getPerformanceEpochTicks() + candidate.getPerformanceBulkTicks() > 100_000);
                assertStateEquals(profile + " LCD off", reference.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void repeatedSpeedChangesRedispatchWithinOneCallerBudget() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB, Profile.CGB0}) {
            try (Gameboy reference = session(Scenario.SPEED_SWITCH, profile);
                 Gameboy candidate = session(Scenario.SPEED_SWITCH, profile)) {
                reference.setPerformanceBatchingEnabled(false);
                reference.runTicks(210_000);
                candidate.runTicks(210_000);
                candidate.resetPerformanceBulkCounters();
                assertEquals(reference.runTicks(210_672), candidate.runTicks(210_672));
                assertTrue(profile + " switched epochs", candidate.getPerformanceEpochTicks() > 1_000);
                assertStateEquals(profile + " switched", reference.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void packetPartitionsAndDiagnosticsPreserveCanonicalState() throws Exception {
        for (Profile profile : Profile.values()) {
            try (Gameboy whole = session(Scenario.STAT_POLL, profile);
                 Gameboy split = session(Scenario.STAT_POLL, profile)) {
                whole.runTicks(210_000);
                split.runTicks(210_000);
                PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(10_000);
                split.setPerformanceDiagnostics(diagnostics);
                int expectedFrames = whole.runTicks(70_224);
                Random random = new Random(9127);
                int frames = 0;
                int remaining = 70_224;
                while (remaining > 0) {
                    int count = Math.min(remaining, 1 + random.nextInt(4096));
                    frames += split.runTicks(count);
                    remaining -= count;
                }
                assertEquals(expectedFrames, frames);
                assertEquals(70_224, diagnostics.snapshot().ticks());
                assertStateEquals(profile + " partitions",
                        whole.captureStateWithoutTimeSource(), split.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void diagnosticsAreDetachedAndCountOverlappingReasonsWithoutDoubleCountingTicks() {
        PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(10);
        diagnostics.recordTicks(PerformanceDiagnostics.Execution.SCALAR, 13);
        diagnostics.recordRejected(PerformanceDiagnostics.Blocker.DMA.mask()
                | PerformanceDiagnostics.Blocker.PPU_LATCH.mask());
        diagnostics.recordTicks(PerformanceDiagnostics.Execution.EPOCH, 7);
        diagnostics.recordReplay(PerformanceDiagnostics.Subsystem.STAT_REPLAY, 5);
        diagnostics.recordFence(0xff41);
        diagnostics.recordScanline(false, PerformanceDiagnostics.Blocker.PPU_DMA.mask());
        var snapshot = diagnostics.snapshot();
        diagnostics.recordTicks(PerformanceDiagnostics.Execution.SCALAR, 1000);
        assertEquals(20, snapshot.ticks());
        assertEquals(13, snapshot.longestScalarRun());
        assertEquals(2, snapshot.windows().size());
        assertEquals(Long.valueOf(1), snapshot.blockers().get(PerformanceDiagnostics.Blocker.DMA));
        assertEquals(Long.valueOf(1), snapshot.fences().get(PerformanceDiagnostics.Fence.STAT));
        assertEquals(Long.valueOf(5), snapshot.subsystemTicks()
                .get(PerformanceDiagnostics.Subsystem.STAT_REPLAY));
        assertEquals(20, snapshot.executionTicks().values().stream().mapToLong(Long::longValue).sum());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.executionTicks().clear());
    }
}
