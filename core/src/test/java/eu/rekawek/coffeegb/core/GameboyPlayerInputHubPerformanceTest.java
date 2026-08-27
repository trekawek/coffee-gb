package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** End-to-end coverage for the live PlayerInputHub path used by Android adapters. */
public final class GameboyPlayerInputHubPerformanceTest {

    @Test
    public void performanceHubMatchesScalarAtRandomChunkBoundariesAndRestores() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            for (boolean held : new boolean[]{false, true}) {
                PlayerInputHub performanceHub = new PlayerInputHub();
                PlayerInputHub.SourceHandle performanceSource = performanceHub.openSource(0);
                AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                        PlayerInputSnapshot.released());
                if (held) {
                    Set<Button> initial = Set.of(Button.A, Button.RIGHT);
                    performanceSource.update(initial);
                    scalarInput.set(snapshot(initial));
                }
                try (Gameboy performance = session(cgb, ExecutionMode.PERFORMANCE, performanceHub);
                        Gameboy scalar = session(cgb, ExecutionMode.PERFORMANCE, scalarInput::get)) {
                    Random random = new Random(0x4f3d_21a7L + (cgb ? 1 : 0)
                            + (held ? 7 : 0));
                    for (int chunkIndex = 0; chunkIndex < 90; chunkIndex++) {
                        int chunk = 1 + random.nextInt(97);
                        long scalarFrames = 0;
                        for (int tick = 0; tick < chunk; tick++) {
                            if (scalar.runTicks(1) != 0) {
                                scalarFrames++;
                            }
                        }
                        assertEquals("frame events cgb=" + cgb + ", held=" + held
                                        + ", chunk=" + chunkIndex,
                                scalarFrames, performance.runTicks(chunk));
                        ComponentState<Gameboy> scalarState = scalar.captureState();
                        ComponentState<Gameboy> performanceState = performance.captureState();
                        assertEquals("state cgb=" + cgb + ", held=" + held
                                        + ", chunk=" + chunkIndex + " "
                                        + componentHashes(scalarState, performanceState),
                                stateHash(scalarState), stateHash(performanceState));
                        assertRasterEquivalent(scalar, performance,
                                "cgb=" + cgb + ", held=" + held + ", chunk=" + chunkIndex);
                    }
                    assertTrue("hub performance path had no fast coverage cgb=" + cgb
                                    + ", held=" + held,
                            performance.getPerformanceBulkTicks()
                                    + performance.getPerformanceEpochTicks() > 0);

                    ComponentState<Gameboy> saved = performance.captureState();
                    int savedHash = stateHash(saved);
                    performance.runTicks(73);
                    performance.restoreState(saved);
                    assertEquals("capture/restore cgb=" + cgb + ", held=" + held,
                            savedHash, stateHash(performance.captureState()));

                    for (int tick = 0; tick < 32; tick++) {
                        long scalarFrame = scalar.runTicks(1);
                        assertEquals("restore frame cgb=" + cgb + ", held=" + held,
                                scalarFrame != 0, performance.runTicks(1) != 0);
                        assertEquals("restore state cgb=" + cgb + ", held=" + held,
                                stateHash(scalar.captureState()), stateHash(performance.captureState()));
                        assertRasterEquivalent(scalar, performance,
                                "restore cgb=" + cgb + ", held=" + held);
                    }
                }
            }
        }
    }

    @Test
    public void cgbBulkTailPublishesGpuTimingBeforeImmediateFf55Start() throws Exception {
        for (int tail = 1; tail <= 3; tail++) {
            PlayerInputHub hub = new PlayerInputHub();
            hub.openSource(0);
            AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                    PlayerInputSnapshot.released());
            try (Gameboy performance = session(true, ExecutionMode.PERFORMANCE, hub);
                    Gameboy scalar = session(true, ExecutionMode.PERFORMANCE, scalarInput::get)) {
                // Find a synchronized endpoint where the immediately preceding call is exactly
                // one all-subsystem packet of the requested 1–3 dots. The scalar oracle follows
                // the same PERFORMANCE renderer lifecycle but has a custom source, so its
                // all-subsystem packet horizon is zero.
                boolean allBulk = false;
                for (int attempt = 0; attempt < 4_000 && !allBulk; attempt++) {
                    long before = performance.getPerformanceBulkTicks();
                    performance.runTicks(tail);
                    for (int i = 0; i < tail; i++) {
                        scalar.runTicks(1);
                    }
                    allBulk = performance.getPerformanceBulkTicks() - before == tail;
                }
                assertTrue("CGB setup did not exercise an all-bulk " + tail + "-dot tail",
                        allBulk);

                // Start GDMA immediately after the proven packet tail. The final GPU timing
                // publication must make FF55 observe the same request state as scalar execution.
                startOneBlockGdma(performance);
                startOneBlockGdma(scalar);
                performance.runTicks(1);
                scalar.runTicks(1);

                assertEquals("FF55 start after " + tail + "-dot packet tail",
                        scalar.getAddressSpace().getByte(0xff55),
                        performance.getAddressSpace().getByte(0xff55));
                assertEquals("HDMA timing latch after " + tail + "-dot packet tail",
                        stateHash(scalar.getHdma().captureState()),
                        stateHash(performance.getHdma().captureState()));
            }
        }
    }

    @Test
    public void settledHaltMatchesScalarDmgAndCgbWithBulkCoverage() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            PlayerInputHub hub = new PlayerInputHub();
            hub.openSource(0);
            try (Gameboy performance = haltSession(cgb, hub);
                    // A non-hub source deliberately makes Joypad's horizon zero, retaining the
                    // PERFORMANCE components while forcing the scalar scheduler as the oracle.
                    Gameboy scalar = haltSession(cgb,
                            () -> PlayerInputSnapshot.released())) {
                for (int chunk = 0; chunk < 120; chunk++) {
                    performance.runTicks(100);
                    scalar.runTicks(100);
                }

                assertEquals("HALT state cgb=" + cgb, Cpu.State.HALTED,
                        performance.getCpu().getState());
                assertEquals("scalar HALT state cgb=" + cgb, Cpu.State.HALTED,
                        scalar.getCpu().getState());
                assertEquals("HALT differential cgb=" + cgb + " "
                                + componentHashes(scalar.captureState(), performance.captureState()),
                        stateHash(scalar.captureState()), stateHash(performance.captureState()));
                assertRasterEquivalent(scalar, performance, "HALT cgb=" + cgb);
                assertTrue("stable HALT had no substantial bulk coverage cgb=" + cgb
                                + " ticks=" + performance.getPerformanceBulkTicks(),
                        performance.getPerformanceBulkTicks() > 1_000);
                if (cgb) {
                    assertEquals("CGB settled HALT must retain the ordinary three-dot cap",
                            0, performance.getPerformanceBulkMaxTicks());
                } else {
                    assertTrue("DMG settled HALT never crossed a machine-cycle boundary"
                                    + " maxSpan=" + performance.getPerformanceBulkMaxTicks(),
                            performance.getPerformanceBulkMaxTicks() > 3);
                }
            }
        }
    }

    @Test
    public void cgbCompatibilitySettledHaltMatchesScalarWithCgbIdlePlane() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            PlayerInputHub performanceHub = new PlayerInputHub();
            performanceHub.openSource(0);
            try (Gameboy performance = haltCgbCompatSession(profile, performanceHub);
                    Gameboy scalar = haltCgbCompatSession(profile,
                            () -> PlayerInputSnapshot.released())) {
                performance.runTicks(128);
                scalar.runTicks(128);
                assertTrue(profile.id() + " compatibility setup did not enter DMG-compat mode",
                        performance.getSpeedMode().isDmgCompat());
                assertEquals(Cpu.State.HALTED, performance.getCpu().getState());
                assertEquals(Cpu.State.HALTED, scalar.getCpu().getState());

                performance.resetPerformanceBulkCounters();
                scalar.resetPerformanceBulkCounters();
                for (int chunk = 0; chunk < 120; chunk++) {
                    assertEquals(profile.id() + " compatibility HALT callbacks chunk=" + chunk,
                            runScalarTicks(scalar, 100), performance.runTicks(100));
                    assertStateEquivalent(scalar.captureState(), performance.captureState(),
                            profile.id() + " compatibility HALT chunk=" + chunk);
                    assertRasterEquivalent(scalar, performance,
                            profile.id() + " compatibility HALT chunk=" + chunk);
                }
                assertTrue(profile.id() + " compatibility settled HALT did not cross a machine cycle",
                        performance.getPerformanceBulkMaxTicks() > 3);
                assertTrue(profile.id() + " compatibility settled HALT had no substantial bulk coverage",
                        performance.getPerformanceBulkTicks() > 1_000);
            }
        }
    }

    @Test
    public void sgbSettledHaltMatchesScalarForBothClockProfiles() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            PlayerInputHub performanceHub = new PlayerInputHub();
            performanceHub.openSource(0);
            try (Gameboy performance = haltProfileSession(profile, performanceHub);
                    Gameboy scalar = haltProfileSession(profile,
                            () -> PlayerInputSnapshot.released())) {
                performance.runTicks(128);
                scalar.runTicks(128);
                assertEquals(profile.id() + " CPU state", Cpu.State.HALTED,
                        performance.getCpu().getState());
                assertEquals(profile.id() + " scalar CPU state", Cpu.State.HALTED,
                        scalar.getCpu().getState());

                performance.resetPerformanceBulkCounters();
                scalar.resetPerformanceBulkCounters();
                // Run beyond one 70,224-dot frame so the SGB VRAM transfer reaches its
                // VBlank/materialization boundary while the scalar oracle remains authoritative.
                for (int chunk = 0; chunk < 800; chunk++) {
                    assertEquals(profile.id() + " frame callbacks chunk=" + chunk,
                            runScalarTickCalls(scalar, 100), performance.runTicks(100));
                    ComponentState<Gameboy> scalarState = scalar.captureState();
                    ComponentState<Gameboy> performanceState = performance.captureState();
                    // The direct renderer publishes a whole line at mode-3 entry, while the
                    // scalar oracle fills it dot by dot. Compare the recursive state only at a
                    // completed-line/VBlank materialization boundary; exact raw SGB transfer payloads
                    // are checked synchronously by PerformanceScanlineIntegrationTest.
                    if ((performance.getGpu().getMode() == Mode.OamSearch
                            || performance.getGpu().getMode() == Mode.VBlank)
                            && ((chunk & 63) == 0 || chunk >= 700)) {
                        assertStateEquivalent(scalarState, performanceState,
                                profile.id() + " HALT chunk=" + chunk);
                    }
                    assertRasterEquivalent(scalar, performance,
                            profile.id() + " HALT chunk=" + chunk);
                }
                assertTrue(profile.id() + " settled HALT did not cross a machine cycle",
                        performance.getPerformanceBulkMaxTicks() > 3);
                assertTrue(profile.id() + " settled HALT had no substantial bulk coverage: "
                                + performance.getPerformanceBulkTicks(),
                        performance.getPerformanceBulkTicks() > 100);
            }
        }
    }

    @Test
    public void cgbCompatibilitySettledHaltRejectsActiveOamDma() throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        performanceHub.openSource(0);
        try (Gameboy performance = haltCgbCompatSession(
                HardwareProfileRegistry.CGB, performanceHub);
                Gameboy scalar = haltCgbCompatSession(
                        HardwareProfileRegistry.CGB, () -> PlayerInputSnapshot.released())) {
            performance.runTicks(128);
            scalar.runTicks(128);
            performance.resetPerformanceBulkCounters();
            scalar.resetPerformanceBulkCounters();

            performance.getAddressSpace().setByte(0xff46, 0xc0);
            scalar.getAddressSpace().setByte(0xff46, 0xc0);
            assertEquals("active OAM DMA setup",
                    recordComponentHash(scalar.captureState(), "dmaMemento"),
                    recordComponentHash(performance.captureState(), "dmaMemento"));
            assertEquals("active OAM DMA frame callbacks", runScalarTicks(scalar, 54),
                    performance.runTicks(54));
            assertEquals("CGB compatibility HALT must not bulk through OAM DMA", 0L,
                    performance.getPerformanceBulkTicks());
            assertStateEquivalent(scalar.captureState(), performance.captureState(),
                    "CGB compatibility active OAM DMA");
        }
    }

    @Test
    public void cgbCompatibilitySettledHaltRejectsActiveHdma() throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        performanceHub.openSource(0);
        try (Gameboy performance = haltCgbCompatSession(
                HardwareProfileRegistry.CGB, performanceHub);
                Gameboy scalar = haltCgbCompatSession(
                        HardwareProfileRegistry.CGB, () -> PlayerInputSnapshot.released())) {
            performance.runTicks(128);
            scalar.runTicks(128);
            performance.resetPerformanceBulkCounters();
            scalar.resetPerformanceBulkCounters();

            startOneBlockGdma(performance);
            startOneBlockGdma(scalar);
            assertEquals("active HDMA setup",
                    stateHash(scalar.getHdma().captureState()),
                    stateHash(performance.getHdma().captureState()));
            assertEquals("active HDMA frame callbacks", runScalarTicks(scalar, 54),
                    performance.runTicks(54));
            assertEquals("CGB compatibility HALT must not bulk through HDMA", 0L,
                    performance.getPerformanceBulkTicks());
            assertStateEquivalent(scalar.captureState(), performance.captureState(),
                    "CGB compatibility active HDMA");
        }
    }

    @Test
    public void cgbCompatibilitySettledHaltWakeEdgesMatchScalarForBothProfiles()
            throws Exception {
        int[] tails = {1, 3, 7, 17, 53};
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (WakeSource wakeSource : WakeSource.values()) {
                for (int tail : tails) {
                    PlayerInputHub hub = new PlayerInputHub();
                    hub.openSource(0);
                    try (Gameboy performance = haltCgbCompatSession(profile, hub);
                            Gameboy scalar = haltCgbCompatSession(profile,
                                    () -> PlayerInputSnapshot.released())) {
                        settleHalt(performance, scalar, true, wakeSource, tail);
                        armWakeSource(performance, wakeSource);
                        armWakeSource(scalar, wakeSource);
                        performance.resetPerformanceBulkCounters();
                        scalar.resetPerformanceBulkCounters();

                        int elapsed = 0;
                        while (performance.getCpu().getState() == Cpu.State.HALTED
                                && elapsed < 2_000) {
                            assertEquals(profile.id() + " compat " + wakeSource + " tail=" + tail,
                                    runScalarTicks(scalar, tail), performance.runTicks(tail));
                            assertEquals(profile.id() + " compat CPU wake tail=" + tail,
                                    scalar.getCpu().getState(), performance.getCpu().getState());
                            elapsed += tail;
                        }
                        assertTrue(profile.id() + " compat " + wakeSource + " did not wake",
                                performance.getCpu().getState() != Cpu.State.HALTED);
                        assertStateEquivalent(scalar.captureState(), performance.captureState(),
                                profile.id() + " compat " + wakeSource + " tail=" + tail);
                        assertRasterEquivalent(scalar, performance,
                                profile.id() + " compat " + wakeSource + " tail=" + tail);
                    }
                }
            }
        }
    }

    @Test
    public void settledHaltWakeEdgesMatchScalarForShortAndLongTails() throws Exception {
        int[] tails = {1, 3, 7, 17, 53};
        for (boolean cgb : new boolean[]{false, true}) {
            for (WakeSource wakeSource : WakeSource.values()) {
                for (int tail : tails) {
                    PlayerInputHub hub = new PlayerInputHub();
                    hub.openSource(0);
                    try (Gameboy performance = haltSession(cgb, hub);
                            Gameboy scalar = haltSession(cgb,
                                    () -> PlayerInputSnapshot.released())) {
                        settleHalt(performance, scalar, cgb, wakeSource, tail);
                        armWakeSource(performance, wakeSource);
                        armWakeSource(scalar, wakeSource);
                        performance.resetPerformanceBulkCounters();
                        scalar.resetPerformanceBulkCounters();

                        boolean sawWholeTail = false;
                        int elapsed = 0;
                        while (performance.getCpu().getState() == Cpu.State.HALTED
                                && elapsed < 2_000) {
                            long bulkBefore = performance.getPerformanceBulkTicks();
                            long performanceFrames = performance.runTicks(tail);
                            long scalarFrames = runScalarTicks(scalar, tail);
                            sawWholeTail |= performance.getPerformanceBulkTicks() - bulkBefore
                                    == tail;
                            assertEquals(context(cgb, wakeSource, tail) + " frame events",
                                    scalarFrames, performanceFrames);
                            assertEquals(context(cgb, wakeSource, tail) + " CPU wake tick",
                                    scalar.getCpu().getState(), performance.getCpu().getState());
                            elapsed += tail;
                        }

                        assertTrue(context(cgb, wakeSource, tail) + " did not wake",
                                performance.getCpu().getState() != Cpu.State.HALTED);
                        if (tail <= 3) {
                            assertTrue(context(cgb, wakeSource, tail)
                                            + " never committed a whole caller tail",
                                    sawWholeTail);
                        }

                        // Continue past the wake boundary. Inactive OAM DMA must leave its
                        // HALT-pause latch on the same running-CPU value as the scalar machine.
                        assertEquals(context(cgb, wakeSource, tail) + " post-wake frame events",
                                runScalarTicks(scalar, 8), performance.runTicks(8));
                        ComponentState<Gameboy> scalarState = scalar.captureState();
                        ComponentState<Gameboy> performanceState = performance.captureState();
                        assertEquals(context(cgb, wakeSource, tail) + " DMA pause latch",
                                recordComponentHash(scalarState, "dmaMemento"),
                                recordComponentHash(performanceState, "dmaMemento"));
                        assertStateEquivalent(scalarState, performanceState,
                                context(cgb, wakeSource, tail));
                        assertRasterEquivalent(scalar, performance,
                                context(cgb, wakeSource, tail));
                    }
                }
            }
        }
    }

    @Test
    public void settledHaltRandomLongChunkTailsMatchFullState() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            PlayerInputHub performanceHub = new PlayerInputHub();
            performanceHub.openSource(0);
            try (Gameboy performance = haltSession(cgb, performanceHub);
                    Gameboy scalar = haltSession(cgb,
                            () -> PlayerInputSnapshot.released())) {
                performance.runTicks(128);
                runScalarTicks(scalar, 128);
                Random random = new Random(0x6a17_2b9dL + (cgb ? 1 : 0));
                for (int chunkIndex = 0; chunkIndex < 80; chunkIndex++) {
                    int chunk = 4 + random.nextInt(50);
                    long scalarFrames = runScalarTicks(scalar, chunk);
                    long performanceFrames = performance.runTicks(chunk);
                    assertEquals("random HALT tail frame cgb=" + cgb
                                    + ", chunk=" + chunkIndex,
                            scalarFrames, performanceFrames);
                    assertStateEquivalent(scalar.captureState(), performance.captureState(),
                            "random HALT tail cgb=" + cgb + ", chunk=" + chunkIndex);
                    assertRasterEquivalent(scalar, performance,
                            "random HALT tail cgb=" + cgb + ", chunk=" + chunkIndex);
                }
                if (cgb) {
                    assertEquals("CGB random HALT tails must retain the ordinary three-dot cap",
                            0, performance.getPerformanceBulkMaxTicks());
                } else {
                    assertTrue("DMG random HALT tails never crossed a machine cycle",
                            performance.getPerformanceBulkMaxTicks() > 3);
                }
            }
        }
    }

    @Test
    public void settledHaltPlayerInputHubMutationWaitsForTheSixtyFourTickPoll() throws Exception {
        for (boolean cgb : new boolean[]{false, true}) {
            PlayerInputHub performanceHub = new PlayerInputHub();
            PlayerInputHub.SourceHandle performanceSource = performanceHub.openSource(0);
            AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                    PlayerInputSnapshot.released());
            try (Gameboy performance = haltSession(cgb, performanceHub);
                    Gameboy scalar = haltSession(cgb, scalarInput::get)) {
                performance.runTicks(128);
                scalar.runTicks(128);
                // Move away from the initial poll residue, then mutate both hosts before the
                // next 64-T master-tick poll. The candidate must retain the hub's settled horizon
                // until that poll, and both full canonical states must remain identical.
                performance.runTicks(11);
                scalar.runTicks(11);
                Set<Button> held = Set.of(Button.A, Button.LEFT);
                performanceSource.update(held);
                // At tick 139 the next hub poll is tick 193. Keep the scalar oracle released
                // through the 53 pre-poll dots, then publish the same snapshot immediately
                // before the poll tick so the canonical states meet exactly at that boundary.
                assertEquals("hub mutation pre-poll frame cgb=" + cgb,
                        runScalarTicks(scalar, 53), performance.runTicks(53));
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "hub mutation before poll cgb=" + cgb);
                scalarInput.set(snapshot(held));
                assertEquals("hub mutation poll frame cgb=" + cgb,
                        runScalarTicks(scalar, 1), performance.runTicks(1));
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "hub mutation at poll cgb=" + cgb);
                int[] chunks = {7, 17, 31, 53, 7};
                for (int chunkIndex = 0; chunkIndex < chunks.length; chunkIndex++) {
                    int chunk = chunks[chunkIndex];
                    long scalarFrames = runScalarTicks(scalar, chunk);
                    long performanceFrames = performance.runTicks(chunk);
                    assertEquals("hub mutation frame cgb=" + cgb
                                    + ", chunk=" + chunkIndex,
                            scalarFrames, performanceFrames);
                    assertStateEquivalent(scalar.captureState(), performance.captureState(),
                            "hub mutation cgb=" + cgb + ", chunk=" + chunkIndex);
                }
            }
        }
    }

    @Test
    public void nativeDoubleSpeedSettledHaltMatchesScalarAcrossPacketTails() throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        performanceHub.openSource(0);
        try (Gameboy performance = nativeDoubleSpeedHaltSession(performanceHub);
                Gameboy scalar = nativeDoubleSpeedHaltSession(
                        () -> PlayerInputSnapshot.released())) {
            settleNativeDoubleSpeedHalt(performance, scalar);
            ComponentState<Gameboy> settledPerformance = performance.captureState();
            ComponentState<Gameboy> settledScalar = scalar.captureState();
            int[] tails = {1, 2, 3, 17, 53, 54, 55};
            for (int tail : tails) {
                performance.restoreState(settledPerformance);
                scalar.restoreState(settledScalar);
                assertEquals("native HALT tail=" + tail + " frame events",
                        runScalarTicks(scalar, tail), performance.runTicks(tail));
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "native HALT tail=" + tail);
                assertRasterEquivalent(scalar, performance, "native HALT tail=" + tail);
            }

            performance.restoreState(settledPerformance);
            scalar.restoreState(settledScalar);
            performance.resetPerformanceBulkCounters();
            assertEquals("native HALT 1000-dot frame events", runScalarTicks(scalar, 1_000),
                    performance.runTicks(1_000));
            assertTrue("native double-speed HALT did not cross a CPU phase boundary",
                    performance.getPerformanceBulkMaxTicks() > 3);
            assertTrue("native double-speed HALT had no substantial packet coverage",
                    performance.getPerformanceBulkTicks() > 3);
            assertStateEquivalent(scalar.captureState(), performance.captureState(),
                    "native HALT 54-dot packet");
        }
    }

    @Test
    public void nativeDoubleSpeedSettledHaltWakeEdgesMatchScalarForAllTails() throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        performanceHub.openSource(0);
        try (Gameboy performance = nativeDoubleSpeedHaltSession(performanceHub);
                Gameboy scalar = nativeDoubleSpeedHaltSession(
                        () -> PlayerInputSnapshot.released())) {
            settleNativeDoubleSpeedHalt(performance, scalar);
            ComponentState<Gameboy> settledPerformance = performance.captureState();
            ComponentState<Gameboy> settledScalar = scalar.captureState();
            int[] tails = {1, 2, 3, 17, 53, 54, 55};
            for (WakeSource wakeSource : WakeSource.values()) {
                for (int tail : tails) {
                    performance.restoreState(settledPerformance);
                    scalar.restoreState(settledScalar);
                    armWakeSource(performance, wakeSource);
                    armWakeSource(scalar, wakeSource);
                    int elapsed = 0;
                    while (performance.getCpu().getState() == Cpu.State.HALTED
                            && elapsed < 4_000) {
                        assertEquals(context(true, wakeSource, tail) + " frame events",
                                runScalarTicks(scalar, tail), performance.runTicks(tail));
                        assertEquals(context(true, wakeSource, tail) + " CPU state",
                                scalar.getCpu().getState(), performance.getCpu().getState());
                        elapsed += tail;
                    }
                    assertTrue(context(true, wakeSource, tail) + " did not wake",
                            performance.getCpu().getState() != Cpu.State.HALTED);
                    assertStateEquivalent(scalar.captureState(), performance.captureState(),
                            context(true, wakeSource, tail) + " wake");
                    assertRasterEquivalent(scalar, performance,
                            context(true, wakeSource, tail) + " wake");
                }
            }
        }
    }

    @Test
    public void nativeDoubleSpeedSettledHaltPlayerInputPollAndRestoreAtBothCpuPhases()
            throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        PlayerInputHub.SourceHandle performanceSource = performanceHub.openSource(0);
        AtomicReference<PlayerInputSnapshot> scalarInput = new AtomicReference<>(
                PlayerInputSnapshot.released());
        try (Gameboy performance = nativeDoubleSpeedHaltSession(performanceHub);
                Gameboy scalar = nativeDoubleSpeedHaltSession(scalarInput::get)) {
            settleNativeDoubleSpeedHalt(performance, scalar);
            ComponentState<Gameboy> settledPerformance = performance.captureState();
            ComponentState<Gameboy> settledScalar = scalar.captureState();
            for (int phase = 0; phase < 2; phase++) {
                performance.restoreState(settledPerformance);
                scalar.restoreState(settledScalar);
                int guard = 0;
                while (performance.getCpu().getDebugMachineCycle() != phase
                        && guard++ < 4) {
                    assertEquals("phase setup frame", scalar.tick(), performance.tick());
                }
                assertEquals("native HALT phase setup", phase,
                        performance.getCpu().getDebugMachineCycle());
                ComponentState<Gameboy> phasePerformance = performance.captureState();
                ComponentState<Gameboy> phaseScalar = scalar.captureState();
                assertStateEquivalent(phaseScalar, phasePerformance,
                        "native HALT saved phase=" + phase);

                assertEquals("native HALT restore frame phase=" + phase,
                        runScalarTicks(scalar, 17), performance.runTicks(17));
                performance.restoreState(phasePerformance);
                scalar.restoreState(phaseScalar);
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "native HALT restored phase=" + phase);
                assertEquals("native HALT restored tail phase=" + phase,
                        runScalarTicks(scalar, 55), performance.runTicks(55));
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "native HALT restored run phase=" + phase);

                performance.restoreState(phasePerformance);
                scalar.restoreState(phaseScalar);
                performance.runTicks(11);
                scalar.runTicks(11);
                Set<Button> held = Set.of(Button.A, Button.LEFT);
                performanceSource.update(held);
                while ((joypadTick(performance) & 63L) != 0) {
                    assertEquals("native HALT hub pre-poll phase=" + phase,
                            runScalarTicks(scalar, 1), performance.runTicks(1));
                    assertStateEquivalent(scalar.captureState(), performance.captureState(),
                            "native HALT hub pre-poll phase=" + phase);
                }
                scalarInput.set(snapshot(held));
                assertEquals("native HALT hub poll phase=" + phase,
                        runScalarTicks(scalar, 1), performance.runTicks(1));
                assertStateEquivalent(scalar.captureState(), performance.captureState(),
                        "native HALT hub poll phase=" + phase);
                scalarInput.set(PlayerInputSnapshot.released());
                performanceSource.update(Set.of());
                // sampledInput is intentionally host-only and is not serialized. Let both
                // hosts observe the release through their ordinary poll before the next phase
                // restore, so the memento restore starts from the same physical source state.
                for (int tick = 0; tick < 80; tick++) {
                    scalar.tick();
                    performance.tick();
                }
            }
        }
    }

    @Test
    public void nativeDoubleSpeedSettledHaltRejectsActiveOamDma() throws Exception {
        PlayerInputHub performanceHub = new PlayerInputHub();
        performanceHub.openSource(0);
        try (Gameboy performance = nativeDoubleSpeedHaltSession(performanceHub);
                Gameboy scalar = nativeDoubleSpeedHaltSession(
                        () -> PlayerInputSnapshot.released())) {
            settleNativeDoubleSpeedHalt(performance, scalar);
            performance.resetPerformanceBulkCounters();
            scalar.resetPerformanceBulkCounters();
            performance.getAddressSpace().setByte(0xff46, 0xc0);
            scalar.getAddressSpace().setByte(0xff46, 0xc0);
            assertEquals("active OAM DMA state",
                    recordComponentHash(scalar.captureState(), "dmaMemento"),
                    recordComponentHash(performance.captureState(), "dmaMemento"));
            assertEquals("active OAM DMA frame events", runScalarTicks(scalar, 54),
                    performance.runTicks(54));
            assertEquals("active OAM DMA must reject HALT packets", 0L,
                    performance.getPerformanceBulkTicks());
            assertStateEquivalent(scalar.captureState(), performance.captureState(),
                    "active OAM DMA HALT");
        }
    }

    private static Gameboy session(boolean cgb, ExecutionMode mode, PlayerInputHub hub)
            throws Exception {
        return session(cgb, mode, (PlayerInputSource) hub);
    }

    private static Gameboy session(boolean cgb, ExecutionMode mode, PlayerInputSource inputSource)
            throws Exception {
        byte[] image = new byte[0x8000];
        // Select both JOYP rows once, then hold a stable CPU loop. Android's physical hub is
        // therefore observable through the same filtered P10-P13 path as a real game.
        image[0x100] = 0x3e; // LD A,0
        image[0x101] = 0;
        image[0x102] = (byte) 0xe0; // LDH (FF00),A
        image[0x103] = 0;
        image[0x104] = 0x18; // JR $0104
        image[0x105] = (byte) 0xfe;
        image[0x143] = (byte) (cgb ? 0x80 : 0);
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(mode)
                .setSupportBatterySave(false)
                .setPlayerInputSource(inputSource)
                .build();
        // Use the real disconnected cable endpoint used by the controller link path.  Its
        // PERFORMANCE capability is intentionally distinct from the NULL endpoint and must
        // remain quiet only while it has no peer.
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static Gameboy haltSession(boolean cgb, PlayerInputSource inputSource) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x76; // HALT with no interrupt sources enabled
        image[0x101] = 0x00;
        image[0x143] = (byte) (cgb ? 0x80 : 0);
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static Gameboy haltCgbCompatSession(
            HardwareProfile profile, PlayerInputSource inputSource) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x76; // HALT with no interrupt sources enabled
        image[0x101] = 0x00;
        image[0x143] = 0;
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static Gameboy haltProfileSession(
            HardwareProfile profile, PlayerInputSource inputSource) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x76; // HALT with no interrupt sources enabled
        image[0x101] = 0x00;
        image[0x143] = 0;
        image[0x147] = 0;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static Gameboy nativeDoubleSpeedHaltSession(PlayerInputSource inputSource)
            throws Exception {
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(nativeDoubleSpeedHaltRom()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), new Peer2PeerSerialEndpoint(), null);
        return gameboy;
    }

    private static void settleNativeDoubleSpeedHalt(Gameboy performance, Gameboy scalar) {
        int guard = 0;
        while (!(performance.getSpeedMode().getSpeedMode() == 2
                        && performance.getCpu().getState() == Cpu.State.HALTED
                        && performance.getCpu().performanceNativeCgbSettledHaltSpanEligible())
                && guard++ < 300_000) {
            assertEquals("native HALT setup frame events", scalar.tick(), performance.tick());
        }
        assertTrue("native double-speed HALT setup did not settle", guard < 300_000);
        assertEquals(Cpu.State.HALTED, scalar.getCpu().getState());
        assertEquals(2, scalar.getSpeedMode().getSpeedMode());
        assertStateEquivalent(scalar.captureState(), performance.captureState(),
                "native double-speed HALT settled");
    }

    private static void settleHalt(Gameboy performance, Gameboy scalar, boolean cgb,
                                   WakeSource wakeSource, int tail) {
        performance.runTicks(128);
        runScalarTicks(scalar, 128);
        assertEquals(context(cgb, wakeSource, tail) + " candidate did not settle in HALT",
                Cpu.State.HALTED, performance.getCpu().getState());
        assertEquals(context(cgb, wakeSource, tail) + " oracle did not settle in HALT",
                Cpu.State.HALTED, scalar.getCpu().getState());

        // Arm mode-2 STAT only from a deasserted source so the tested interrupt is the next
        // PPU edge, rather than the register write itself.
        if (wakeSource == WakeSource.STAT) {
            int guard = 0;
            while (performance.getGpu().getVisibleStatMode() == 2 && guard++ < 100) {
                performance.runTicks(1);
                scalar.runTicks(1);
            }
            assertTrue(context(cgb, wakeSource, tail) + " STAT source did not deassert",
                    performance.getGpu().getVisibleStatMode() != 2);
        }
        assertStateEquivalent(scalar.captureState(), performance.captureState(),
                context(cgb, wakeSource, tail) + " pre-arm");
    }

    private static void armWakeSource(Gameboy gameboy, WakeSource wakeSource) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xffff, 0);
        bus.setByte(0xff0f, 0);
        if (wakeSource == WakeSource.TIMER) {
            bus.setByte(0xff07, 0);
            bus.setByte(0xff04, 0);
            bus.setByte(0xff06, 0);
            bus.setByte(0xff05, 0xff);
            bus.setByte(0xff07, 0x05);
            bus.setByte(0xffff, 0x04);
        } else {
            bus.setByte(0xff41, 0x20);
            bus.setByte(0xff0f, 0);
            bus.setByte(0xffff, 0x02);
        }
    }

    private static byte[] nativeDoubleSpeedHaltRom() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF4D),A
        image[0x103] = 0x4d;
        image[0x104] = 0x10; // STOP + padding: enters CGB double speed
        image[0x105] = 0x00;
        image[0x106] = 0x76; // HALT after the speed-switch countdown
        image[0x107] = 0x00;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static long runScalarTicks(Gameboy scalar, int ticks) {
        long frameEvents = 0;
        for (int tick = 0; tick < ticks; tick++) {
            frameEvents += scalar.runTicks(1);
        }
        return frameEvents;
    }

    /** Exact scalar oracle used by the long SGB boundary test without scheduler setup overhead. */
    private static long runScalarTickCalls(Gameboy scalar, int ticks) {
        long frameEvents = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (scalar.tick()) {
                frameEvents++;
            }
        }
        return frameEvents;
    }

    private static String context(boolean cgb, WakeSource wakeSource, int tail) {
        return "cgb=" + cgb + ", wake=" + wakeSource + ", tail=" + tail;
    }

    private static void assertStateEquivalent(ComponentState<Gameboy> scalar,
                                              ComponentState<Gameboy> performance,
                                              String context) {
        int scalarHash = stateHash(scalar);
        int performanceHash = stateHash(performance);
        if (scalarHash != performanceHash) {
            assertEquals(context + " " + componentHashes(scalar, performance),
                    scalarHash, performanceHash);
        }
    }

    private static int recordComponentHash(Object value, String componentName) {
        return stateHash(recordComponentValue(value, componentName));
    }

    private static Object recordComponentValue(Object value, String componentName) {
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (!component.getName().equals(componentName)) {
                continue;
            }
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot hash state component " + component, e);
            }
        }
        throw new AssertionError("Missing record component " + componentName);
    }

    private static long joypadTick(Gameboy gameboy) {
        Object memento = recordComponentValue(gameboy.captureState(), "joypadMemento");
        return ((Number) recordComponentValue(memento, "tick")).longValue();
    }

    private enum WakeSource {
        TIMER,
        STAT
    }

    private static PlayerInputSnapshot snapshot(Set<Button> buttons) {
        ArrayList<Set<Button>> players = new ArrayList<>();
        players.add(buttons);
        players.add(Set.of());
        players.add(Set.of());
        players.add(Set.of());
        return PlayerInputSnapshot.of(players);
    }

    private static void startOneBlockGdma(Gameboy gameboy) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xff51, 0);
        bus.setByte(0xff52, 0);
        bus.setByte(0xff53, 0);
        bus.setByte(0xff54, 0);
        bus.setByte(0xff55, 0);
    }

    /** Canonical recursive hash for record mementos, including primitive/object arrays. */
    private static int stateHash(Object value) {
        if (value == null) {
            return 0;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int hash = 1;
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + stateHash(Array.get(value, i));
            }
            return hash;
        }
        if (type.isRecord()) {
            int hash = type.getName().hashCode();
            for (RecordComponent component : type.getRecordComponents()) {
                try {
                    if (isHostOnlyState(type, component.getName())) {
                        continue;
                    }
                    hash = 31 * hash + component.getName().hashCode();
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    hash = 31 * hash + stateHash(accessor.invoke(value));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Cannot hash state component " + component, e);
                }
            }
            return hash;
        }
        return value.hashCode();
    }

    private static boolean isHostOnlyState(Class<?> type, String componentName) {
        String name = type.getName();
        if (name.equals("eu.rekawek.coffeegb.core.sound.Sound$SoundState")
                || name.equals("eu.rekawek.coffeegb.core.sound.Sound$SoundMemento")) {
            return switch (componentName) {
                case "buffer", "i", "performanceSamplePhase", "audioDecimation" -> true;
                default -> false;
            };
        }
        if (name.equals("eu.rekawek.coffeegb.core.gpu.Gpu$GpuState")
                || name.equals("eu.rekawek.coffeegb.core.gpu.Gpu$GpuMemento")) {
            return switch (componentName) {
                case "performanceWindowLineCounter", "performanceScanlineCursor",
                        "performanceScanlineLine", "performanceScanlineEndTick",
                        "pixelTransferPhaseMemento", "pixelMachineMemento" -> true;
                default -> false;
            };
        }
        return name.equals("eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferState")
                || name.equals("eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferMemento");
    }

    private static void assertRasterEquivalent(Gameboy scalar, Gameboy performance,
                                               String context) {
        assertEquals(context + " LY", scalar.getGpu().getLine(), performance.getGpu().getLine());
        assertEquals(context + " line ticks", scalar.getGpu().getTicksInLine(),
                performance.getGpu().getTicksInLine());
        assertEquals(context + " mode", scalar.getGpu().getMode(), performance.getGpu().getMode());
        assertEquals(context + " LCDC", scalar.getGpu().getByte(0xff40),
                performance.getGpu().getByte(0xff40));
        assertEquals(context + " STAT", scalar.getGpu().getByte(0xff41),
                performance.getGpu().getByte(0xff41));
    }

    private static String componentHashes(Object left, Object right) {
        if (!left.getClass().isRecord() || !right.getClass().isRecord()) {
            return "";
        }
        StringBuilder result = new StringBuilder("components[");
        for (RecordComponent component : left.getClass().getRecordComponents()) {
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                int leftHash = stateHash(accessor.invoke(left));
                int rightHash = stateHash(accessor.invoke(right));
                if (leftHash != rightHash) {
                    result.append(component.getName()).append('=').append(leftHash)
                            .append('/').append(rightHash).append(',');
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        return result.append(']').toString();
    }
}
