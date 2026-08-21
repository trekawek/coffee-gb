package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sgb.Commands;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic differential coverage for the guarded DMG/MGB/CGB performance timing cursor.
 *
 * <p>The comparison is made against an ordinary ACCURACY session, including the complete
 * record-shaped machine state. No external ROM, save, title, or host path is involved.</p>
 */
public final class PerformanceSteadyBackgroundDifferentialTest {

    private static final int STEADY_LINE = 1;

    private static final List<HardwareProfile> SUPPORTED_PROFILES =
            List.of(HardwareProfileRegistry.DMG, HardwareProfileRegistry.MGB);

    private static final List<HardwareProfile> NATIVE_CGB_PROFILES =
            List.of(HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0);

    private static final List<HardwareProfile> SGB_PROFILES =
            List.of(HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2);

    @Test
    public void uninterruptedSpanMatchesAccuracyForEveryFineScx() throws Exception {
        for (HardwareProfile profile : SUPPORTED_PROFILES) {
            for (int scx = 0; scx < 8; scx++) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    tickPair(accuracy, performance);
                    assertTrue(lazyCursor(performance.gpu));
                    while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                        tickPair(accuracy, performance);
                    }

                    assertEquals(Mode.HBlank, performance.gpu.getMode());
                    assertEquals(249 + scx, performance.gpu.getTicksInLine());
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance,
                            profile.id() + " uninterrupted scx=" + scx);
                }
            }
        }
    }

    @Test
    public void nativeCgbAndCgb0SteadySpanMatchesAccuracyWithBankedAttributes() throws Exception {
        for (HardwareProfile profile : NATIVE_CGB_PROFILES) {
            for (int scx : new int[]{0, 3, 7}) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    tickPair(accuracy, performance);
                    assertTrue(profile.id() + " must arm its native CGB cursor",
                            lazyCursor(performance.gpu));

                    while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                        tickPair(accuracy, performance);
                    }
                    assertEquals(Mode.HBlank, performance.gpu.getMode());
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance,
                            profile.id() + " color span scx=" + scx);
                }
            }
        }
    }

    @Test
    public void sgbAndSgb2SteadySpanPreservesFramesTransfersAudioAndCadence()
            throws Exception {
        for (HardwareProfile profile : SGB_PROFILES) {
            for (int scx = 0; scx < 8; scx++) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    tickPair(accuracy, performance);
                    assertTrue(profile.id() + " must arm its SGB timing cursor",
                            lazyCursor(performance.gpu));
                    assertEquals(profile, performance.gameboy.getHardwareProfile());
                    assertSgbCadence(profile, performance.gameboy.getClockSpec());

                    int startLine = performance.gpu.getLine();
                    int startTicksInLine = performance.gpu.getTicksInLine();
                    int startDmgFrames = performance.events.frameCount;
                    int startSgbFrames = performance.events.sgbFrameCount;
                    int startTransfers = performance.events.vRamTransferCount;
                    long startMasterTicks = performance.masterTicks;

                    int elapsed = 0;
                    do {
                        tickPair(accuracy, performance);
                        elapsed++;
                        assertTrue(profile.id() + " did not return to the same PPU phase",
                                elapsed <= 70_224);
                    } while (performance.gpu.getLine() != startLine
                            || performance.gpu.getTicksInLine() != startTicksInLine);

                    assertEquals(profile.id() + " exact LCD frame length", 70_224, elapsed);
                    assertEquals(profile.id() + " master tick accounting", 70_224,
                            performance.masterTicks - startMasterTicks);
                    assertEquals(startDmgFrames + 1, performance.events.frameCount);
                    assertEquals(startDmgFrames + 1, accuracy.events.frameCount);
                    assertEquals(startSgbFrames + 1, performance.events.sgbFrameCount);
                    assertEquals(startSgbFrames + 1, accuracy.events.sgbFrameCount);
                    assertEquals(startTransfers + 1, performance.events.vRamTransferCount);
                    assertEquals(startTransfers + 1, accuracy.events.vRamTransferCount);
                    assertEquals(accuracy.events.sgbFrameHash, performance.events.sgbFrameHash);
                    assertEquals(accuracy.events.vRamTransferHash,
                            performance.events.vRamTransferHash);
                    assertEquals(accuracy.events.audioCount, performance.events.audioCount);
                    assertEquals(accuracy.events.audioHash, performance.events.audioHash);
                    assertSameState(accuracy, performance,
                            profile.id() + " SGB frame boundary scx=" + scx);
                }
            }
        }
    }

    @Test
    public void sgbJoypadCommandLeavesArmedCursorAndRenderedStateIdentical()
            throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.SGB, 3);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.SGB, 3)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue("SGB cursor should be armed before JOYP transfer",
                    lazyCursor(performance.gpu));

            sendSgbPal01Command(accuracy.gameboy);
            sendSgbPal01Command(performance.gameboy);
            assertEquals("Accuracy must deliver the PAL01 packet", 1,
                    accuracy.events.pal01Count);
            assertEquals("Performance must deliver the PAL01 packet", 1,
                    performance.events.pal01Count);
            assertEquals(accuracy.events.pal01Hash, performance.events.pal01Hash);
            assertTrue("JOYP/SGB command must not disturb the independent PPU cursor",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "SGB JOYP command while armed");

            int targetLine = performance.gpu.getLine();
            int targetTicks = performance.gpu.getTicksInLine();
            for (int i = 0; i < 70_224; i++) {
                tickPair(accuracy, performance);
            }
            assertEquals(targetLine, performance.gpu.getLine());
            assertEquals(targetTicks, performance.gpu.getTicksInLine());
            assertEquals(accuracy.events.sgbFrameCount, performance.events.sgbFrameCount);
            assertEquals(accuracy.events.sgbFrameHash, performance.events.sgbFrameHash);
            assertEquals(accuracy.events.vRamTransferCount,
                    performance.events.vRamTransferCount);
            assertEquals(accuracy.events.vRamTransferHash,
                    performance.events.vRamTransferHash);
            assertEquals(accuracy.events.audioCount, performance.events.audioCount);
            assertEquals(accuracy.events.audioHash, performance.events.audioHash);
            assertSameState(accuracy, performance, "SGB JOYP command frame continuation");
        }
    }

    @Test
    public void unresolvedBootCompatibilityFailsClosedUntilResolved() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            performance.gpu.setBootCompatibilityResolved(false);
            tickPair(accuracy, performance);
            assertFalse("unresolved boot compatibility must keep PERFORMANCE scalar",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "unresolved boot compatibility");

            performance.gpu.setBootCompatibilityResolved(true);
            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue("resolved boot compatibility should permit native CGB cursor",
                    lazyCursor(performance.gpu));
        }
    }

    @Test
    public void nativeCgbVramAttributeWriteMaterializesAndPreventsRearm() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            accuracy.gpu.setByte(0xff4f, 1);
            performance.gpu.setByte(0xff4f, 1);
            accuracy.gpu.setByte(0x9800, 0x03);
            performance.gpu.setByte(0x9800, 0x03);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "CGB VRAM1 attribute write materialization");

            while (accuracy.gpu.getLine() == STEADY_LINE
                    && accuracy.gpu.getMode() == Mode.PixelTransfer) {
                tickPair(accuracy, performance);
            }
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "CGB VRAM write remains exact");
        }
    }

    @Test
    public void cgbDmgCompatibilitySteadySpanMatchesAccuracyForEveryFineScx()
            throws Exception {
        for (int scx = 0; scx < 8; scx++) {
            try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                    HardwareProfileRegistry.CGB, scx, false);
                    Session performance = new Session(ExecutionMode.PERFORMANCE,
                            HardwareProfileRegistry.CGB, scx, false)) {
                assertTrue("synthetic non-CGB cartridge must resolve CGB compatibility",
                        performance.gpu.isDmgCompatMode());
                enterSteadyLine(accuracy);
                enterSteadyLine(performance);
                tickPair(accuracy, performance);
                assertTrue("CGB DMG-compatibility should arm the color timing cursor",
                        lazyCursor(performance.gpu));

                while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                    tickPair(accuracy, performance);
                }
                assertEquals(Mode.HBlank, performance.gpu.getMode());
                assertEquals(accuracy.gpu.getTicksInLine(), performance.gpu.getTicksInLine());
                assertFalse(lazyCursor(performance.gpu));
                assertSameState(accuracy, performance,
                        "CGB DMG-compatibility color span scx=" + scx);
            }
        }
    }

    @Test
    public void cgbDmgCompatibilityMasksIoAndMaterializesOnScxWrite()
            throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2, false);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2, false)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            // DMG compatibility keeps the physical CGB's VBK read mask but ignores its
            // bank-select write; the separate CGB work-RAM bank register is open bus.
            assertEquals(0xfe, performance.gameboy.getAddressSpace().getByte(0xff4f));
            accuracy.gameboy.getAddressSpace().setByte(0xff4f, 1);
            performance.gameboy.getAddressSpace().setByte(0xff4f, 1);
            assertEquals(0xfe, performance.gameboy.getAddressSpace().getByte(0xff4f));
            accuracy.gameboy.getAddressSpace().setByte(0xff70, 3);
            performance.gameboy.getAddressSpace().setByte(0xff70, 3);
            assertEquals(0xff, performance.gameboy.getAddressSpace().getByte(0xff70));
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "CGB DMG-compatibility IO mask");

            // Re-arm on a later eligible line. This makes the following write test independent
            // of the intentional materialization caused by the direct IO reads above.
            while (accuracy.gpu.getLine() == STEADY_LINE
                    || accuracy.gpu.getMode() != Mode.PixelTransfer
                    || accuracy.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));
            accuracy.gpu.setByte(0xff43, 6);
            performance.gpu.setByte(0xff43, 6);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance,
                    "CGB DMG-compatibility SCX write invalidation");
        }
    }

    @Test
    public void cgbDmgCompatibilityBootResolutionControlsCursor() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2, false);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2, false)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            assertTrue(performance.gpu.isDmgCompatMode());
            performance.gpu.setBootCompatibilityResolved(false);
            tickPair(accuracy, performance);
            assertFalse("unresolved compatibility handoff must remain scalar",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "compatibility handoff unresolved");

            performance.gpu.setBootCompatibilityResolved(true);
            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue("resolved compatibility handoff should arm the cursor",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "compatibility handoff resolved");
        }
    }

    @Test
    public void cgb0DmgCompatibilityRemainsScalarUntilMeasured() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB0, 2, false);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB0, 2, false)) {
            assertTrue(performance.gpu.isDmgCompatMode());
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertFalse("CGB0 DMG-compatibility must keep the unmeasured scalar path",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "CGB0 compatibility scalar fallback");
        }
    }

    @Test
    public void nativeCgbGdmaStartMaterializesTheCursor() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            startOneBlockGdma(accuracy.gameboy);
            startOneBlockGdma(performance.gameboy);
            tickPair(accuracy, performance);
            assertFalse("GDMA must materialize the deferred timing span",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "GDMA cursor materialization");
        }
    }

    @Test
    public void nativeCgbHblankDmaArmMaterializesAndKeepsCursorScalar() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.CGB, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue("native CGB cursor should be armed before HBlank DMA",
                    lazyCursor(performance.gpu));

            startOneBlockHblankDma(accuracy.gameboy);
            startOneBlockHblankDma(performance.gameboy);
            tickPair(accuracy, performance);
            assertFalse("an armed HBlank DMA must materialize the deferred span",
                    lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "HBlank DMA armed cursor materialization");
        }
    }

    @Test
    public void steadySpanMatchesAccuracyAtArmMidLineAndNextLineBoundary() throws Exception {
        for (HardwareProfile profile : SUPPORTED_PROFILES) {
            for (int scx = 0; scx < 8; scx++) {
                try (Session accuracy = new Session(ExecutionMode.ACCURACY, profile, scx);
                        Session performance = new Session(ExecutionMode.PERFORMANCE, profile, scx)) {
                    enterSteadyLine(accuracy);
                    enterSteadyLine(performance);

                    assertEquals(80, accuracy.gpu.getTicksInLine());
                    assertEquals(80, performance.gpu.getTicksInLine());
                    tickPair(accuracy, performance);
                    assertFalse(lazyCursor(accuracy.gpu));
                    assertTrue(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " arm scx=" + scx);

                    for (int i = 0; i < 24; i++) {
                        tickPair(accuracy, performance);
                    }
                    // The arm-point state comparison above intentionally materialized the cursor;
                    // subsequent dots therefore exercise the ordinary continuation after a safe
                    // observation boundary.
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " mid-line scx=" + scx);

                    while (accuracy.gpu.getMode() == Mode.PixelTransfer) {
                        tickPair(accuracy, performance);
                    }
                    assertEquals(Mode.HBlank, accuracy.gpu.getMode());
                    assertEquals(Mode.HBlank, performance.gpu.getMode());
                    assertEquals(249 + scx, accuracy.gpu.getTicksInLine());
                    assertEquals(accuracy.gpu.getTicksInLine(), performance.gpu.getTicksInLine());
                    assertFalse(lazyCursor(performance.gpu));
                    assertSameState(accuracy, performance, profile.id() + " mode0 scx=" + scx);

                    while (accuracy.gpu.getLine() == STEADY_LINE) {
                        tickPair(accuracy, performance);
                    }
                    assertEquals(0, accuracy.gpu.getTicksInLine());
                    assertEquals(0, performance.gpu.getTicksInLine());
                    assertSameState(accuracy, performance,
                            profile.id() + " next-line-start scx=" + scx);
                }
            }
        }
    }

    @Test
    public void gpuWriteMaterializesTheSpanBeforeChangingCanonicalState() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 3);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 3)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            for (int i = 0; i < 25; i++) {
                tickPair(accuracy, performance);
            }
            assertTrue(lazyCursor(performance.gpu));

            accuracy.gpu.setByte(0xff43, 6);
            performance.gpu.setByte(0xff43, 6);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "SCX write materialization");
            for (int i = 0; i < 420; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "SCX write invalidation");
        }
    }

    @Test
    public void retainedMutableAliasMaterializesAndPermanentlyFallsBack() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            GpuRegisterValues accuracyRegisters = accuracy.gpu.getRegisters();
            GpuRegisterValues performanceRegisters = performance.gpu.getRegisters();
            assertFalse(lazyCursor(performance.gpu));
            accuracyRegisters.put(GpuRegister.SCX, 5);
            performanceRegisters.put(GpuRegister.SCX, 5);

            while (accuracy.gpu.getLine() == STEADY_LINE
                    || accuracy.gpu.getMode() != Mode.PixelTransfer
                    || accuracy.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retained mutable register alias");
        }
    }

    @Test
    public void debugRetirementMaterializesAndBlocksUntilDetached() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            performance.gameboy.enableDebugRetirementTracking();
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement attach materialization");

            // Keep retirement observation attached through the next eligible line. The
            // canonical state must continue to match while the cursor stays scalar-deopted.
            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement blocks re-arm");

            performance.gameboy.disableDebugRetirementTracking();
            while (performance.gpu.getLine() == STEADY_LINE + 1
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement detach re-enables");
        }
    }

    @Test
    public void nonPpuInstrumentationSharesObservationBlockerWithRetirement() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 2);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 2)) {
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));

            DebugInstrumentation instrumentation = cpuOnlyInstrumentation();
            assertFalse(instrumentation.requiresPpuHooks());
            performance.gameboy.updateDebugInstrumentation(instrumentation, 0);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "non-PPU instrumentation attach");

            while (performance.gpu.getLine() == STEADY_LINE
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "non-PPU instrumentation blocks re-arm");

            // Both observation sources are aggregated: removing instrumentation alone must not
            // re-enable the cursor while retirement tracking remains attached.
            performance.gameboy.enableDebugRetirementTracking();
            performance.gameboy.updateDebugInstrumentation(null, 0);
            while (performance.gpu.getLine() == STEADY_LINE + 1
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "retirement keeps blocker after detach");

            performance.gameboy.disableDebugRetirementTracking();
            while (performance.gpu.getLine() == STEADY_LINE + 2
                    || performance.gpu.getMode() != Mode.PixelTransfer
                    || performance.gpu.getTicksInLine() != 80) {
                tickPair(accuracy, performance);
            }
            tickPair(accuracy, performance);
            assertTrue(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "both observations detached");
        }
    }

    @Test
    public void disabledWindowInsertionLatchFailsClosed() throws Exception {
        try (Session accuracy = new Session(ExecutionMode.ACCURACY,
                HardwareProfileRegistry.DMG, 0);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 0)) {
            armWindowMasterThenDisable(accuracy);
            armWindowMasterThenDisable(performance);
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);

            tickPair(accuracy, performance);
            assertFalse(lazyCursor(performance.gpu));
            for (int i = 0; i < 420; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "disabled-window insertion latch");
        }
    }

    @Test
    public void performanceCheckpointRestoresIntoBothModesThroughFrameAndAudioEdges()
            throws Exception {
        try (Session source = new Session(ExecutionMode.PERFORMANCE,
                HardwareProfileRegistry.DMG, 5);
                Session accuracy = new Session(ExecutionMode.ACCURACY,
                        HardwareProfileRegistry.DMG, 5);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.DMG, 5)) {
            enterSteadyLine(source);
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(source, accuracy);
            tickPair(source, performance);
            for (int i = 0; i < 22; i++) {
                source.gameboy.tick();
                accuracy.gameboy.tick();
                performance.gameboy.tick();
            }
            assertTrue(lazyCursor(source.gpu));

            ComponentState<Gameboy> saved = source.gameboy.captureStateWithoutTimeSource();
            accuracy.gameboy.restoreStateSilently(saved);
            performance.gameboy.restoreStateSilently(saved);
            assertFalse(lazyCursor(accuracy.gpu));
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "restored checkpoint");

            // restoreStateSilently deliberately suppresses the abandoned partial frame. Two
            // physical frames reach the first unsuppressed visible frame and at least one audio
            // buffer, exercising display and APU event payloads as well as the PPU line edge.
            for (int i = 0; i < 145_000; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "cross-mode continuation");
            assertEquals(accuracy.gameboy.getAddressSpace().getByte(0xff41),
                    performance.gameboy.getAddressSpace().getByte(0xff41));
            assertEquals(accuracy.gameboy.getAddressSpace().getByte(0xff0f),
                    performance.gameboy.getAddressSpace().getByte(0xff0f));
            assertEquals(accuracy.gpu.getLine(), performance.gpu.getLine());
            assertEquals(accuracy.gpu.getTicksInLine(), performance.gpu.getTicksInLine());
            assertEquals(accuracy.gpu.getMode(), performance.gpu.getMode());
            assertEquals(accuracy.events.frameCount, performance.events.frameCount);
            assertEquals(accuracy.events.frameHash, performance.events.frameHash);
            assertEquals(accuracy.events.audioCount, performance.events.audioCount);
            assertEquals(accuracy.events.audioHash, performance.events.audioHash);
            assertTrue("synthetic run must publish a visible frame",
                    accuracy.events.frameCount > 0);
            assertTrue("synthetic run must publish an audio buffer",
                    accuracy.events.audioCount > 0);
        }
    }

    @Test
    public void nativeCgbCheckpointRestoresIntoBothModesThroughFrameAndAudioEdges()
            throws Exception {
        for (HardwareProfile profile : NATIVE_CGB_PROFILES) {
            try (Session source = new Session(ExecutionMode.PERFORMANCE, profile, 5);
                    Session accuracy = new Session(ExecutionMode.ACCURACY, profile, 5);
                    Session performance = new Session(ExecutionMode.PERFORMANCE, profile, 5)) {
                enterSteadyLine(source);
                enterSteadyLine(accuracy);
                enterSteadyLine(performance);
                tickPair(source, accuracy);
                tickPair(source, performance);
                for (int i = 0; i < 22; i++) {
                    source.gameboy.tick();
                    accuracy.gameboy.tick();
                    performance.gameboy.tick();
                }
                assertTrue(profile.id() + " checkpoint should capture an armed cursor",
                        lazyCursor(source.gpu));

                ComponentState<Gameboy> saved = source.gameboy.captureStateWithoutTimeSource();
                accuracy.gameboy.restoreStateSilently(saved);
                performance.gameboy.restoreStateSilently(saved);
                assertFalse(lazyCursor(accuracy.gpu));
                assertFalse(lazyCursor(performance.gpu));
                assertSameState(accuracy, performance, profile.id() + " restored checkpoint");

                for (int i = 0; i < 145_000; i++) {
                    tickPair(accuracy, performance);
                }
                assertSameState(accuracy, performance, profile.id() + " cross-mode continuation");
                assertEquals(accuracy.events.frameCount, performance.events.frameCount);
                assertEquals(accuracy.events.frameHash, performance.events.frameHash);
                assertEquals(accuracy.events.audioCount, performance.events.audioCount);
                assertEquals(accuracy.events.audioHash, performance.events.audioHash);
                assertTrue(profile.id() + " synthetic run must publish a visible frame",
                        accuracy.events.frameCount > 0);
                assertTrue(profile.id() + " synthetic run must publish an audio buffer",
                        accuracy.events.audioCount > 0);
            }
        }
    }

    @Test
    public void cgbDmgCompatibilityCheckpointRestoresIntoBothModesThroughFrameAndAudioEdges()
            throws Exception {
        try (Session source = new Session(ExecutionMode.PERFORMANCE,
                HardwareProfileRegistry.CGB, 5, false);
                Session accuracy = new Session(ExecutionMode.ACCURACY,
                        HardwareProfileRegistry.CGB, 5, false);
                Session performance = new Session(ExecutionMode.PERFORMANCE,
                        HardwareProfileRegistry.CGB, 5, false)) {
            enterSteadyLine(source);
            enterSteadyLine(accuracy);
            enterSteadyLine(performance);
            tickPair(source, accuracy);
            tickPair(source, performance);
            for (int i = 0; i < 22; i++) {
                source.gameboy.tick();
                accuracy.gameboy.tick();
                performance.gameboy.tick();
            }
            assertTrue("compatibility checkpoint should capture an armed cursor",
                    lazyCursor(source.gpu));

            ComponentState<Gameboy> saved = source.gameboy.captureStateWithoutTimeSource();
            accuracy.gameboy.restoreStateSilently(saved);
            performance.gameboy.restoreStateSilently(saved);
            assertFalse(lazyCursor(accuracy.gpu));
            assertFalse(lazyCursor(performance.gpu));
            assertSameState(accuracy, performance, "compatibility restored checkpoint");

            for (int i = 0; i < 145_000; i++) {
                tickPair(accuracy, performance);
            }
            assertSameState(accuracy, performance, "compatibility cross-mode continuation");
            assertEquals(accuracy.events.frameCount, performance.events.frameCount);
            assertEquals(accuracy.events.frameHash, performance.events.frameHash);
            assertEquals(accuracy.events.audioCount, performance.events.audioCount);
            assertEquals(accuracy.events.audioHash, performance.events.audioHash);
            assertTrue("compatibility run must publish a visible frame",
                    accuracy.events.frameCount > 0);
            assertTrue("compatibility run must publish an audio buffer",
                    accuracy.events.audioCount > 0);
        }
    }

    private static void enterSteadyLine(Session session) {
        while (session.gpu.getLine() != STEADY_LINE
                || session.gpu.getMode() != Mode.PixelTransfer) {
            session.tick();
        }
    }

    private static void armWindowMasterThenDisable(Session session) {
        session.gpu.setByte(0xff40, 0x00);
        session.gpu.setByte(0xff4a, 0x00);
        session.gpu.setByte(0xff4b, 0x07);
        session.gpu.setByte(0xff40, 0xb1);
        while (session.gpu.getLine() != 0
                || session.gpu.getMode() != Mode.HBlank
                || session.gpu.getTicksInLine() < 300) {
            session.tick();
        }
        session.gpu.setByte(0xff40, 0x91);
    }

    private static void tickPair(Session left, Session right) {
        assertEquals(left.tick(), right.tick());
    }

    private static void startOneBlockGdma(Gameboy gameboy) {
        var addressSpace = gameboy.getAddressSpace();
        addressSpace.setByte(0xff51, 0x00);
        addressSpace.setByte(0xff52, 0x00);
        addressSpace.setByte(0xff53, 0x00);
        addressSpace.setByte(0xff54, 0x00);
        addressSpace.setByte(0xff55, 0x00);
    }

    private static void startOneBlockHblankDma(Gameboy gameboy) {
        var addressSpace = gameboy.getAddressSpace();
        addressSpace.setByte(0xff51, 0x00);
        addressSpace.setByte(0xff52, 0x00);
        addressSpace.setByte(0xff53, 0x00);
        addressSpace.setByte(0xff54, 0x00);
        addressSpace.setByte(0xff55, 0x80);
    }

    private static void sendSgbPal01Command(Gameboy gameboy) {
        int[] packet = new int[16];
        packet[0] = 1; // PAL01, one packet
        for (int i = 1; i < packet.length - 1; i++) {
            packet[i] = (i * 0x13 + 7) & 0xff;
        }
        var addressSpace = gameboy.getAddressSpace();
        writeJoypadSelector(addressSpace, 0x30);
        writeJoypadSelector(addressSpace, 0x00);
        writeJoypadSelector(addressSpace, 0x30);
        for (int bitIndex = 0; bitIndex < packet.length * 8; bitIndex++) {
            int bit = packet[bitIndex / 8] >> (bitIndex & 7) & 1;
            writeJoypadSelector(addressSpace, bit == 0 ? 0x20 : 0x10);
            writeJoypadSelector(addressSpace, 0x30);
        }
        writeJoypadSelector(addressSpace, 0x20);
        writeJoypadSelector(addressSpace, 0x30);
    }

    private static void assertSgbCadence(HardwareProfile profile, ClockSpec clockSpec) {
        if (profile == HardwareProfileRegistry.SGB) {
            assertEquals(140_625L, clockSpec.controllerFramesPerSecondNumerator());
            assertEquals(2_299L, clockSpec.controllerFramesPerSecondDenominator());
        } else {
            assertEquals(HardwareProfileRegistry.SGB2, profile);
            assertEquals(262_144L, clockSpec.controllerFramesPerSecondNumerator());
            assertEquals(4_389L, clockSpec.controllerFramesPerSecondDenominator());
        }
    }

    private static void writeJoypadSelector(eu.rekawek.coffeegb.core.AddressSpace addressSpace,
                                            int selector) {
        addressSpace.setByte(0xff00, selector);
    }

    private static EventBusImpl sgbBus(Gameboy gameboy) throws Exception {
        var field = Gameboy.class.getDeclaredField("sgbBus");
        field.setAccessible(true);
        return (EventBusImpl) field.get(gameboy);
    }

    private static void assertSameState(Session left, Session right, String point)
            throws Exception {
        assertEquals(point, stateDigest(left.gameboy), stateDigest(right.gameboy));
        assertEquals(point + " STAT", left.gameboy.getAddressSpace().getByte(0xff41),
                right.gameboy.getAddressSpace().getByte(0xff41));
        assertEquals(point + " IF", left.gameboy.getAddressSpace().getByte(0xff0f),
                right.gameboy.getAddressSpace().getByte(0xff0f));
    }

    private static boolean lazyCursor(Gpu gpu) throws Exception {
        var cursor = Gpu.class.getDeclaredField("steadyTimingCursor");
        cursor.setAccessible(true);
        return cursor.getBoolean(gpu);
    }

    private static byte[] syntheticRom(HardwareProfile profile, boolean nativeColor) {
        byte[] rom = new byte[0x8000];
        rom[0x100] = (byte) 0xc3; // JP $0100: a stable CPU-side workload with no MMIO writes
        rom[0x101] = 0;
        rom[0x102] = 1;
        rom[0x147] = 0;
        if (nativeColor && profile.family() == HardwareProfile.Family.CGB) {
            rom[0x143] = (byte) 0x80;
        }
        return rom;
    }

    private static void configureNativeCgb(Gpu gpu) {
        if (!gpu.isGbc()) {
            return;
        }
        // Tile 2 in bank 0 and bank 1 deliberately differ. Attribute 0x6d selects bank 1,
        // palette 5, X flip, and Y flip, exercising every CGB property consumed by the exact
        // fetcher while the scalar timing FIFO retains only occupancy.
        gpu.setByte(0xff4f, 0);
        for (int row = 0; row < 8; row++) {
            gpu.setByte(0x8020 + row * 2, 0x18 ^ row * 3);
            gpu.setByte(0x8021 + row * 2, 0x81 ^ row * 5);
        }
        for (int i = 0; i < 0x20; i++) {
            gpu.setByte(0x9800 + i, 2);
        }
        gpu.setByte(0xff4f, 1);
        for (int row = 0; row < 8; row++) {
            gpu.setByte(0x8020 + row * 2, 0xa5 ^ row * 7);
            gpu.setByte(0x8021 + row * 2, 0x42 ^ row * 11);
        }
        for (int i = 0; i < 0x20; i++) {
            gpu.setByte(0x9800 + i, 0x6d);
        }
        gpu.setByte(0xff4f, 0);

        gpu.setByte(0xff68, 0x80 | (5 << 3));
        for (int value : new int[]{0x1f, 0x00, 0x00, 0x3e, 0x00, 0x7c, 0x10, 0x42}) {
            gpu.setByte(0xff69, value);
        }
    }

    private static DebugInstrumentation cpuOnlyInstrumentation() {
        DebugInstrumentation instrumentation = new DebugInstrumentation(
                2,
                32,
                8,
                EnumSet.of(DebugBreakpointKind.PROGRAM_COUNTER),
                EnumSet.of(TraceCategory.CPU));
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.CPU)));
        return instrumentation;
    }

    private static long stateDigest(Gameboy gameboy) throws Exception {
        return digest(gameboy.captureStateWithoutTimeSource());
    }

    private static long digest(Object value) throws Exception {
        Hasher hasher = new Hasher();
        visit(value, hasher, new IdentityHashMap<>());
        return hasher.value;
    }

    private static void visit(Object value, Hasher hasher, IdentityHashMap<Object, Boolean> seen)
            throws Exception {
        if (value == null) {
            hasher.mix(0);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            hasher.mix(type.getName().hashCode());
            int length = Array.getLength(value);
            hasher.mix(length);
            for (int i = 0; i < length; i++) {
                Object child = Array.get(value, i);
                if (type.getComponentType().isPrimitive()) {
                    hasher.mix(child.hashCode());
                } else {
                    visit(child, hasher, seen);
                }
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value instanceof Enum<?>) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(value.hashCode());
            return;
        }
        if (value instanceof List<?> list) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(list.size());
            for (Object child : list) {
                visit(child, hasher, seen);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            hasher.mix(type.getName().hashCode());
            hasher.mix(map.size());
            for (var entry : map.entrySet()) {
                visit(entry.getKey(), hasher, seen);
                visit(entry.getValue(), hasher, seen);
            }
            return;
        }
        if (!type.isRecord()) {
            throw new AssertionError("Unexpected machine-state shape: " + type.getName());
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            hasher.mix(0x51ed270b);
            return;
        }
        hasher.mix(type.getName().hashCode());
        for (RecordComponent component : type.getRecordComponents()) {
            hasher.mix(component.getName().hashCode());
            var accessor = component.getAccessor();
            if (!accessor.trySetAccessible()) {
                throw new AssertionError("State accessor is not accessible: " + component);
            }
            try {
                visit(accessor.invoke(value), hasher, seen);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError("Unable to read state component " + component, e);
            }
        }
    }

    private static final class Hasher {
        private long value = 0xcbf29ce484222325L;

        private void mix(long next) {
            value ^= next + 0x9e3779b97f4a7c15L + (value << 6) + (value >>> 2);
        }
    }

    private static final class EventDigest {
        private int frameCount;
        private long frameHash = 0xcbf29ce484222325L;
        private int sgbFrameCount;
        private long sgbFrameHash = 0xcbf29ce484222325L;
        private int vRamTransferCount;
        private long vRamTransferHash = 0xcbf29ce484222325L;
        private int pal01Count;
        private long pal01Hash = 0xcbf29ce484222325L;
        private int audioCount;
        private long audioHash = 0xcbf29ce484222325L;

        private void onFrame(Display.DmgFrameReadyEvent event) {
            frameCount++;
            mix(event.lcdBlank() ? 1 : 0);
            for (int pixel : event.pixels()) {
                mix(pixel);
            }
        }

        private void onCgbFrame(Display.GbcFrameReadyEvent event) {
            frameCount++;
            for (int pixel : event.pixels()) {
                mix(pixel);
            }
        }

        private void onSgbFrame(SgbDisplay.SgbFrameReadyEvent event) {
            sgbFrameCount++;
            mixSgb(event.includeBorder() ? 1 : 0);
            for (int pixel : event.buffer()) {
                mixSgb(pixel);
            }
        }

        private void onVramTransfer(VRamTransfer.VRamTransferComplete event) {
            vRamTransferCount++;
            for (int pixel : event.buffer()) {
                mixTransfer(pixel);
            }
        }

        private void onPal01(Commands.Pal01Cmd event) {
            pal01Count++;
            for (int color : event.getPalette0()) {
                mixPal01(color);
            }
            for (int color : event.getPalette1()) {
                mixPal01(color);
            }
        }

        private void onAudio(Sound.SoundSampleEvent event) {
            audioCount++;
            for (int sample : event.buffer()) {
                mixAudio(sample);
            }
        }

        private void mix(long next) {
            frameHash ^= next & 0xffffffffL;
            frameHash *= 0x100000001b3L;
        }

        private void mixAudio(long next) {
            audioHash ^= next & 0xffffffffL;
            audioHash *= 0x100000001b3L;
        }

        private void mixSgb(long next) {
            sgbFrameHash ^= next & 0xffffffffL;
            sgbFrameHash *= 0x100000001b3L;
        }

        private void mixTransfer(long next) {
            vRamTransferHash ^= next & 0xffffffffL;
            vRamTransferHash *= 0x100000001b3L;
        }

        private void mixPal01(long next) {
            pal01Hash ^= next & 0xffffffffL;
            pal01Hash *= 0x100000001b3L;
        }
    }

    private static final class Session implements AutoCloseable {
        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);
        private final EventDigest events = new EventDigest();
        private final Gameboy gameboy;
        private final Gpu gpu;
        private long masterTicks;

        private Session(ExecutionMode mode, HardwareProfile profile, int scx) throws Exception {
            this(mode, profile, scx, true);
        }

        private Session(ExecutionMode mode, HardwareProfile profile, int scx,
                        boolean nativeColor) throws Exception {
            eventBus.register(events::onFrame, Display.DmgFrameReadyEvent.class);
            eventBus.register(events::onCgbFrame, Display.GbcFrameReadyEvent.class);
            eventBus.register(events::onSgbFrame, SgbDisplay.SgbFrameReadyEvent.class);
            eventBus.register(events::onAudio, Sound.SoundSampleEvent.class);
            gameboy = new Gameboy.GameboyConfiguration(new Rom(syntheticRom(profile, nativeColor)))
                    .setHardwareProfile(profile)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(mode)
                    .setSupportBatterySave(false)
                    .setDisplaySgbBorder(profile.capabilities().superGameboyBorder())
                    .build();
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            if (profile.capabilities().superGameboyCommands()) {
                sgbBus(gameboy).register(events::onVramTransfer,
                        VRamTransfer.VRamTransferComplete.class);
                sgbBus(gameboy).register(events::onPal01, Commands.Pal01Cmd.class);
            }
            gpu = gameboy.getGpu();
            for (int address = 0x8000; address < 0xa000; address++) {
                gpu.writeVideoRam0ForCore(address,
                        (address * 37 ^ address >>> 3 ^ 0x5a) & 0xff);
            }
            if (nativeColor) {
                configureNativeCgb(gpu);
            }
            gpu.setByte(0xff42, (scx * 19 + 3) & 0xff);
            gpu.setByte(0xff43, scx);
        }

        private boolean tick() {
            boolean result = gameboy.tick();
            masterTicks++;
            return result;
        }

        @Override
        public void close() {
            gameboy.closeSilently();
            eventBus.close();
        }
    }
}
