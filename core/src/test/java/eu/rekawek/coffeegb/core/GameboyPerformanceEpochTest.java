package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.sgb.Commands;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Focused fixed-x1 CGB, physical-DMG, and SGB coverage for the coarse PERFORMANCE epoch. */
public final class GameboyPerformanceEpochTest {

    @Test
    public void nativeDoubleSpeedLoopUsesEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            // The first batch performs the speed-switch countdown on the legacy scheduler;
            // the next owner batch starts in native double speed and enters the epoch lane.
            gameboy.runTicks(160_000);
            gameboy.runTicks(100_000);
            assertTrue("test ROM did not enter CGB double speed",
                    gameboy.getSpeedMode().getSpeedMode() == 2);
            assertTrue("native CGB epoch lane did not run speed="
                            + gameboy.getSpeedMode().getSpeedMode()
                            + " cpu=" + gameboy.getCpu().getState()
                            + " mode=" + gameboy.getGpu().getMode()
                            + " line=" + gameboy.getGpu().getLine()
                            + " lcd=" + gameboy.getGpu().isLcdEnabled()
                            + " cpuEpoch=" + gameboy.getCpu().getPerformanceEpochCount()
                            + " cpuTicks=" + gameboy.getCpu().getPerformanceEpochTicks()
                            + " accesses=" + gameboy.getCpu().getPerformanceEpochAccesses(),
                    gameboy.getPerformanceEpochTicks() > 0);
        }
    }

    @Test
    public void nativeDoubleSpeedNr50WritesAcrossSamplesMatchScalarHostAudio()
            throws Exception {
        byte[] image = nativeDoubleSpeedNr50Loop();
        try (Gameboy scalar = nativeDoubleSpeedSession(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeDoubleSpeedSession(
                     image, PlayerInputSource.RELEASED)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            // The speed-switch preamble is about 130k master ticks. The alternating NR50
            // loop then writes every 20 double-speed master ticks, walking every phase of the
            // 55-dot compact-output cadence and repeatedly terminating unfenced epochs.
            for (int chunk = 0; chunk < 60; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("native CGB x2 NR50 frame callbacks", scalarFrames, candidateFrames);
            assertEquals(2, scalar.getSpeedMode().getSpeedMode());
            assertEquals(2, candidate.getSpeedMode().getSpeedMode());
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("native CGB x2 NR50 loop had no coarse epoch coverage",
                    candidate.getPerformanceEpochTicks() > 0);
            assertTrue("NR50 writes did not terminate an unfenced native CGB x2 epoch",
                    candidate.getCpu().getPerformanceEpochTerminalAccesses() > 0);
            // This includes SoundState.buffer/i/performanceSamplePhase, so a sample captured
            // before replaying its preceding NR50 write is observable even when CPU/PPU agree.
            assertDeepStateEquals("native CGB x2 NR50/sample ordering",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void stopAwarePreconditioningNeverUsesEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.runTicks(160_000);
            assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
            gameboy.resetPerformanceBulkCounters();

            assertEquals(4_096, gameboy.runTicksUntilStop(4_096, () -> false));
            assertEquals(0L, gameboy.getPerformanceEpochCount());
            assertEquals(0L, gameboy.getPerformanceEpochTicks());
        }
    }

    @Test
    public void stopAwareMeasuredWindowRetainsPerformanceEpochLane() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            gameboy.runTicks(160_000);
            assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
            gameboy.resetPerformanceBulkCounters();

            assertEquals(4_096, gameboy.runMeasuredTicksUntilStop(4_096, () -> false));
            assertTrue("measured stop-aware PERFORMANCE path lost native epoch coverage",
                    gameboy.getPerformanceEpochTicks() > 0L);
        }
    }

    @Test
    public void cgbDmgCompatibilityBootstrapStaysScalarUntilAuthenticHandoff() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(
                new Rom(validNonColorRom()))
                .setGameboyType(GameboyType.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            assertFalse(gameboy.isBootstrapReady());
            assertEquals(0L, gameboy.getPerformanceBulkSpanCount());
            assertEquals(0L, gameboy.getPerformanceEpochCount());

            int executed = gameboy.runTicksUntilStop(40_000_000,
                    gameboy::isBootstrapReady);

            assertTrue("authentic bootstrap did not reach the cartridge handoff", executed > 0);
            assertTrue(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
            assertTrue(gameboy.getSpeedMode().isDmgCompat());
            assertEquals(0L, gameboy.getPerformanceBulkSpanCount());
            assertEquals(0L, gameboy.getPerformanceEpochCount());

            gameboy.resetPerformanceBulkCounters();
            gameboy.runTicks(4_096);
            assertTrue("ordinary CGB compatibility did not enter the epoch lane after NORMAL handoff",
                    gameboy.getPerformanceEpochTicks() > 0);
        }
    }

    @Test
    public void fastForwardStopsAtTheEntryPointThenSettlesTheHandoffScalar() throws Exception {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(
                new Rom(validNonColorRom()))
                .setGameboyType(GameboyType.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build()) {
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertFalse(gameboy.isBootstrapReady());

            int executed = gameboy.runTicksUntilStop(32, gameboy::isBootstrapReady);

            assertTrue("fast-forward handoff tail did not execute", executed > 0);
            assertTrue(gameboy.isBootstrapReady());
            assertEquals(0x0100, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0xff, gameboy.getAddressSpace().getByte(0xff50));
            assertTrue(gameboy.getSpeedMode().isDmgCompat());
            assertEquals(0L, gameboy.getPerformanceBulkSpanCount());
            assertEquals(0L, gameboy.getPerformanceEpochCount());

            gameboy.resetPerformanceBulkCounters();
            gameboy.runTicks(4_096);
            assertTrue("ordinary CGB compatibility did not enter the epoch lane after FAST_FORWARD handoff",
                    gameboy.getPerformanceEpochTicks() > 0);
        }
    }

    @Test
    public void fastForwardCgbCompatibilityUsesMode2PhasePacketsAfterBootGdma()
            throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = fastForwardCgbCompatibilitySession(
                        PlayerInputSnapshot::released);
                Gameboy candidate = fastForwardCgbCompatibilitySession(candidateHub)) {
            assertEquals("FAST_FORWARD handoff tail",
                    scalar.runTicksUntilStop(32, scalar::isBootstrapReady),
                    candidate.runTicksUntilStop(32, candidate::isBootstrapReady));
            assertTrue(scalar.isBootstrapReady());
            assertTrue(candidate.isBootstrapReady());
            assertTrue(candidate.getSpeedMode().isDmgCompat());
            Hdma.HdmaState bootHdma = (Hdma.HdmaState) candidate.getHdma().captureState();
            assertEquals("authentic boot did not retain the completed-GDMA request clock",
                    0, bootHdma.hblankRequestTicks());

            advanceScalarPairToVisibleMode2Dot(scalar, candidate, 20);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            assertTrue("FAST_FORWARD compatibility mode 2 rejected its phase transaction",
                    candidate.getGpu().performanceCgbNormalSpeedMode2PhaseSpanLimit(3) > 0);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            int ticks = 79 - candidate.getGpu().getTicksInLine();
            for (int i = 0; i < ticks; i++) {
                scalar.tick();
            }
            assertEquals("FAST_FORWARD mode-2 frame callbacks", 0, candidate.runTicks(ticks));
            assertTrue("FAST_FORWARD compatibility mode 2 had no bulk coverage",
                    candidate.getPerformanceBulkTicks() > 0);
            assertDeepStateEquals("FAST_FORWARD compatibility mode-2 dot 79",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgAndCgbCompatibilityEnterTheirEpochLanes() throws Exception {
        byte[] loop = new byte[0x8000];
        loop[0x100] = (byte) 0xc3;
        loop[0x101] = 0x00;
        loop[0x102] = 0x01;
        try (Gameboy dmg = new Gameboy.GameboyConfiguration(new Rom(loop))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.DMG)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
             Gameboy compat = new Gameboy.GameboyConfiguration(new Rom(loop))
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setGameboyType(GameboyType.CGB)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setSupportBatterySave(false)
                     .build()) {
            dmg.runTicks(100_000);
            compat.runTicks(100_000);
            assertTrue("physical DMG did not enter the coarse epoch lane",
                    dmg.getPerformanceEpochTicks() > 0);
            assertTrue("ordinary CGB compatibility did not enter the coarse epoch lane",
                    compat.getPerformanceEpochTicks() > 0);
            assertTrue(compat.getSpeedMode().isDmgCompat());
        }
    }

    @Test
    public void cgbCompatibilityRomWramLoopMatchesFallbackWithEpochCoverage()
            throws Exception {
        byte[] image = dmgRomWramLoop();
        try (Gameboy scalar = cgbCompatibilitySession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = cgbCompatibilitySession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("CGB compatibility frame callbacks", scalarFrames, candidateFrames);
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("CGB compatibility ROM/WRAM loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 10_000);
            assertEquals("CGB compatibility used a non-raster epoch plan",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks());
            assertEquals(0L, candidate.getPerformanceEpochMode2ReplayTicks());
            assertEquals(scalar.getAddressSpace().getByte(0xc000),
                    candidate.getAddressSpace().getByte(0xc000));
            assertDeepStateEquals("CGB compatibility ROM/WRAM loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityUnsafeIoWriteMatchesFallbackAndTerminatesEpoch()
            throws Exception {
        byte[] image = cgbCompatibilityIoWriteLoop();
        try (Gameboy scalar = cgbCompatibilitySession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = cgbCompatibilitySession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            for (int chunk = 0; chunk < 12; chunk++) {
                assertEquals("CGB compatibility IO frame callback", scalar.runTicks(5_000),
                        candidate.runTicks(5_000));
            }
            assertTrue("CGB compatibility IO loop had no coarse coverage epochs="
                            + candidate.getPerformanceEpochCount()
                            + " ticks=" + candidate.getPerformanceEpochTicks()
                            + " bulk=" + candidate.getPerformanceBulkTicks()
                            + " pc=" + Integer.toHexString(candidate.getCpu().getRegisters().getPC())
                            + " mode=" + candidate.getGpu().getMode()
                            + " line=" + candidate.getGpu().getLine()
                            + " dot=" + candidate.getGpu().getTicksInLine(),
                    candidate.getPerformanceEpochTicks() > 0);
            assertTrue("unsafe IO did not terminate a CGB compatibility epoch",
                    candidate.getCpu().getPerformanceEpochTerminalAccesses() > 0);
            assertDeepStateEquals("CGB compatibility IO loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityOamDmaWriteMatchesFallback() throws Exception {
        byte[] image = cgbCompatibilityOamDmaWriteLoop();
        try (Gameboy scalar = cgbCompatibilitySession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = cgbCompatibilitySession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            for (int chunk = 0; chunk < 8; chunk++) {
                assertEquals("CGB compatibility OAM-DMA frame callback", scalar.runTicks(5_000),
                        candidate.runTicks(5_000));
                assertDeepStateEquals("CGB compatibility OAM-DMA chunk " + chunk,
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
            assertTrue("CGB compatibility OAM-DMA loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 0);
            assertTrue("CGB compatibility OAM-DMA write did not terminate an epoch",
                    candidate.getCpu().getPerformanceEpochTerminalAccesses() > 0);
        }
    }

    @Test
    public void nativeCgbNormalSpeedRomWramLoopMatchesFallbackWithEpochCoverage()
            throws Exception {
        byte[] image = nativeColor(dmgRomWramLoop());
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("native CGB x1 frame callbacks", scalarFrames, candidateFrames);
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("native CGB x1 ROM/WRAM loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 10_000);
            assertTrue("native CGB x1 ROM/WRAM loop had no mode-2 epoch coverage",
                    candidate.getPerformanceEpochMode2BulkTicks() > 0);
            assertEquals("native CGB x1 epoch plan accounting",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks()
                            + candidate.getPerformanceEpochMode2BulkTicks());
            assertFalse(candidate.getSpeedMode().isDmgCompat());
            assertEquals(1, candidate.getSpeedMode().getSpeedMode());
            assertEquals(scalar.getAddressSpace().getByte(0xc000),
                    candidate.getAddressSpace().getByte(0xc000));
            assertDeepStateEquals("native CGB x1 ROM/WRAM loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedMbc3RtcMatchesScalarWithEpochCoverage()
            throws Exception {
        byte[] image = nativeMbc3(dmgRomWramLoop());
        try (Gameboy scalar = nativeCgbNormalSpeedMbc3Session(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeCgbNormalSpeedMbc3Session(
                     image, PlayerInputSource.RELEASED)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("native CGB x1 MBC3 frame callbacks", scalarFrames, candidateFrames);
            assertEquals("custom-source MBC3 oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("native CGB x1 MBC3 loop had no coarse epoch coverage",
                    candidate.getPerformanceEpochTicks() > 10_000L);
            assertEquals("native CGB x1 MBC3 epoch plan accounting",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks()
                            + candidate.getPerformanceEpochMode2BulkTicks());
            assertDeepStateEquals("native CGB x1 MBC3 RTC oscillator",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedMbc3RtcMatchesScalarUnderImeDisabledRawPendingInterrupt()
            throws Exception {
        byte[] image = nativeMbc3(dmgRomWramLoop());
        try (Gameboy scalar = nativeCgbNormalSpeedMbc3Session(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeCgbNormalSpeedMbc3Session(
                     image, PlayerInputSource.RELEASED)) {
            for (Gameboy gameboy : new Gameboy[] {scalar, candidate}) {
                gameboy.getAddressSpace().setByte(0xffff, 0x01);
                gameboy.getAddressSpace().setByte(0xff0f,
                        gameboy.getAddressSpace().getByte(0xff0f) | 0x01);
            }
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(4_000);
                candidateFrames += candidate.runTicks(4_000);
                assertDeepStateEquals("native CGB x1 masked MBC3 chunk " + chunk,
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }

            assertEquals("native CGB x1 masked MBC3 frame callbacks",
                    scalarFrames, candidateFrames);
            assertEquals("custom-source masked oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("masked MBC3 loop had no native CGB x1 epoch coverage",
                    candidate.getPerformanceEpochTicks() > 10_000L);
            assertEquals("raw pending IF/IE was not retained", 0x01,
                    candidate.getAddressSpace().getByte(0xffff) & 0x01);
            assertEquals("raw pending IF was not retained", 0x01,
                    candidate.getAddressSpace().getByte(0xff0f) & 0x01);
        }
    }

    @Test
    public void nativeCgbNormalSpeedMbc3RtcWritesStayScalarAfterExactClockPrefixes()
            throws Exception {
        byte[] image = nativeMbc3(nativeCgbMbc3RtcWriteLoop());
        try (Gameboy scalar = nativeCgbNormalSpeedMbc3Session(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeCgbNormalSpeedMbc3Session(
                     image, PlayerInputSource.RELEASED)) {
            for (int chunk = 0; chunk < 20; chunk++) {
                assertEquals("native CGB x1 MBC3 RTC-write frame callback " + chunk,
                        scalar.runTicks(4_000), candidate.runTicks(4_000));
                assertDeepStateEquals("native CGB x1 MBC3 RTC-write chunk " + chunk,
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }

            assertTrue("safe MBC3 instruction prefixes had no native CGB x1 epoch coverage",
                    candidate.getPerformanceEpochTicks() > 0L);
            assertEquals("decoded MBC3/RTC access crossed a native CGB x1 epoch",
                    0L, candidate.getCpu().getPerformanceEpochTerminalAccesses());
            assertEquals("latched RTC seconds", 42,
                    candidate.getAddressSpace().getByte(0xc000));
        }
    }

    @Test
    public void nativeCgbNormalSpeedMbc3LcdOffVramAndLcdcHandoffMatchScalar()
            throws Exception {
        byte[] image = nativeMbc3(nativeCgbLcdOffVramThenEnable());
        try (Gameboy scalar = nativeCgbNormalSpeedMbc3Session(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeCgbNormalSpeedMbc3Session(
                     image, PlayerInputSource.RELEASED)) {
            advancePairUntilLcdDisabled(scalar, candidate);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("MBC3 LCD-off blank/VRAM frame callback",
                    scalar.runTicks(2_000), candidate.runTicks(2_000));
            int guard = 0;
            while (!(candidate.getCpu().getState() == Cpu.State.RUNNING
                    && candidate.getCpu().getDebugOpcode() == 0xe0
                    && candidate.getCpu().getRegisters().getPC() == 0x0112
                    && candidate.getCpu().getDebugMachineCycle() == 3)
                    && guard++ < 12_000) {
                assertEquals("MBC3 LCDC-enable setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("MBC3 test did not stop before the LCDC-enable write", guard < 12_000);
            assertFalse(candidate.getGpu().isLcdEnabled());
            assertTrue("MBC3 VRAM clear had no LCD-off epoch coverage",
                    candidate.getPerformanceEpochLcdOffTicks() > 0L);
            assertEquals("MBC3 LCD-off VRAM access reached the terminal bus", 0L,
                    candidate.getCpu().getPerformanceEpochTerminalAccesses());
            for (int offset = 0; offset < 0x100; offset++) {
                assertEquals("MBC3 LCD-off VRAM read/write " + offset, 1,
                        candidate.getAddressSpace().getByte(0x8000 + offset));
            }
            assertDeepStateEquals("MBC3 before LCDC enable",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("MBC3 LCDC-enable boundary frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
            assertEquals("MBC3 decoded LCDC enable crossed the LCD-off epoch", 0L,
                    candidate.getPerformanceEpochTicks());
            assertTrue(candidate.getGpu().isLcdEnabled());
            assertDeepStateEquals("MBC3 after scalar LCDC enable",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void clockedMbc3CgbCompatibilityRemainsOutsideRunningEpoch()
            throws Exception {
        byte[] compatibility = mbc3(dmgRomWramLoop());
        try (Gameboy cgbCompatibility = new Gameboy.GameboyConfiguration(new Rom(compatibility))
                     .setHardwareProfile(HardwareProfileRegistry.CGB)
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setPlayerInputSource(PlayerInputSource.RELEASED)
                     .setRtcTimeSource(() -> 0L)
                     .setSupportBatterySave(false)
                     .build()) {
            cgbCompatibility.runTicks(100_000);

            assertTrue(cgbCompatibility.getSpeedMode().isDmgCompat());
            assertEquals("clocked MBC3 entered CGB-compatibility running epoch",
                    0L, cgbCompatibility.getPerformanceEpochTicks());
        }
    }

    @Test
    public void nativeCgbExternalClockWaitMatchesFallbackWithEpochCoverage()
            throws Exception {
        byte[] image = nativeCgbExternalClockWramLoop();
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("native CGB external-wait frame callbacks",
                    scalarFrames, candidateFrames);
            assertEquals("test ROM did not retain its active external-clock transfer",
                    0x80, candidate.getAddressSpace().getByte(0xff02) & 0x81);
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("native CGB external-clock wait had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 10_000);
            assertTrue("native CGB external-clock wait had no mode-2 epoch coverage",
                    candidate.getPerformanceEpochMode2BulkTicks() > 0);
            assertEquals("native CGB external-clock wait epoch plan accounting",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks()
                            + candidate.getPerformanceEpochMode2BulkTicks());
            assertEquals(scalar.getAddressSpace().getByte(0xc000),
                    candidate.getAddressSpace().getByte(0xc000));
            assertDeepStateEquals("native CGB external-clock wait",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedUnsafeIoWriteMatchesFallbackAndStaysScalar()
            throws Exception {
        byte[] image = nativeColor(cgbCompatibilityIoWriteLoop());
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            for (int chunk = 0; chunk < 12; chunk++) {
                assertEquals("native CGB x1 IO frame callback", scalar.runTicks(5_000),
                        candidate.runTicks(5_000));
            }
            assertTrue("native CGB x1 IO loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 0);
            assertEquals("decoded IO write crossed a native CGB x1 epoch",
                    0L, candidate.getCpu().getPerformanceEpochTerminalAccesses());
            assertDeepStateEquals("native CGB x1 IO loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedLcdOffBlankBoundariesMatchScalarDeepState()
            throws Exception {
        byte[] image = nativeCgbLcdOffVramLoop();
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            advancePairUntilLcdDisabled(scalar, candidate);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            int beforeInitialBlank = Gameboy.LCD_OFF_BLANK_DELAY - 2;
            assertEquals("LCD-off pre-blank frame callback", 0,
                    scalar.runTicks(beforeInitialBlank));
            assertEquals("LCD-off epoch crossed initial blank boundary", 0,
                    candidate.runTicks(beforeInitialBlank));
            assertDeepStateEquals("before initial LCD-off blank",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            assertEquals("scalar initial LCD-off blank", 1, scalar.runTicks(1));
            assertEquals("candidate initial LCD-off blank", 1, candidate.runTicks(1));
            assertDeepStateEquals("after initial LCD-off blank",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            int beforeRefreshBlank = candidate.getClockSpec().controllerTicksPerFrame() - 1;
            assertEquals("LCD-off pre-refresh frame callback", 0,
                    scalar.runTicks(beforeRefreshBlank));
            assertEquals("LCD-off epoch crossed refresh blank boundary", 0,
                    candidate.runTicks(beforeRefreshBlank));
            assertDeepStateEquals("before recurring LCD-off blank",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            assertEquals("scalar recurring LCD-off blank", 1, scalar.runTicks(1));
            assertEquals("candidate recurring LCD-off blank", 1, candidate.runTicks(1));
            assertTrue("stable LCD-off loop had no epoch coverage",
                    candidate.getPerformanceEpochLcdOffTicks() > 50_000L);
            assertEquals("LCD-off VRAM accesses reached the terminal bus", 0L,
                    candidate.getCpu().getPerformanceEpochTerminalAccesses());
            assertDeepStateEquals("after recurring LCD-off blank",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedLcdOffVramReadWriteAndLcdcEnableMatchScalar()
            throws Exception {
        byte[] image = nativeCgbLcdOffVramThenEnable();
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            advancePairUntilLcdDisabled(scalar, candidate);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("LCD-off VRAM bulk setup frame callback",
                    scalar.runTicks(2_000), candidate.runTicks(2_000));
            int guard = 0;
            while (!(candidate.getCpu().getState() == Cpu.State.RUNNING
                    && candidate.getCpu().getDebugOpcode() == 0xe0
                    && candidate.getCpu().getRegisters().getPC() == 0x0112
                    && candidate.getCpu().getDebugMachineCycle() == 3)
                    && guard++ < 12_000) {
                assertEquals("LCD-off LCDC-enable setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not stop before the LCDC-enable write", guard < 12_000);
            assertFalse(candidate.getGpu().isLcdEnabled());
            assertTrue("VRAM clear loop had no LCD-off epoch coverage",
                    candidate.getPerformanceEpochLcdOffTicks() > 0L);
            assertEquals("LCD-off VRAM accesses reached the terminal bus", 0L,
                    candidate.getCpu().getPerformanceEpochTerminalAccesses());
            for (int offset = 0; offset < 0x100; offset++) {
                assertEquals("LCD-off VRAM read/write " + offset, 1,
                        candidate.getAddressSpace().getByte(0x8000 + offset));
            }
            assertDeepStateEquals("before LCDC enable",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("LCDC-enable boundary frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
            assertEquals("decoded LCDC enable crossed the LCD-off epoch", 0L,
                    candidate.getPerformanceEpochTicks());
            assertTrue(candidate.getGpu().isLcdEnabled());
            assertDeepStateEquals("after scalar LCDC enable",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgAndSgbNormalSpeedLcdOffVramEpochsMatchScalar()
            throws Exception {
        byte[] image = physicalDmgLcdOffVramThenEnable();
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            assertLcdOffVramAndLcdcEnableMatchesScalar(
                    scalar, candidate, "physical DMG", false);
        }
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(
                    image, profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(
                         image, profile, PlayerInputSource.RELEASED)) {
                assertLcdOffVramAndLcdcEnableMatchesScalar(
                        scalar, candidate, profile.id(), true);
            }
        }
    }

    @Test
    public void physicalDmgAndSgbNormalSpeedLcdOffBlankBoundariesMatchScalarDeepState()
            throws Exception {
        byte[] image = physicalDmgLcdOffVramLoop();
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            assertLcdOffBlankBoundariesMatchScalar(scalar, candidate, "physical DMG");
        }
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(image, profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(image, profile, PlayerInputSource.RELEASED)) {
                assertLcdOffBlankBoundariesMatchScalar(scalar, candidate, profile.id());
            }
        }
    }

    @Test
    public void nativeCgbNormalSpeedNr44TriggerStaysScalarAfterSafeEpochCoverage()
            throws Exception {
        byte[] image = nativeColor(dmgRomWramLoop());
        image[0x200] = (byte) 0xe0; // LDH (FF23),A: trigger CH4
        image[0x201] = 0x23;
        image[0x202] = 0x18; // JR 0202
        image[0x203] = (byte) 0xfe;
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0
                    && candidate.getCpu().performanceNativeCgbNormalSpeedEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("native CGB x1 NR44 setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted native CGB x1 NR44 entry",
                    guard < 200_000);
            assertDeepStateEquals("before native CGB x1 NR44 decode",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            scalar.getAddressSpace().setByte(0xff26, 0x80); // APU on
            candidate.getAddressSpace().setByte(0xff26, 0x80);
            scalar.getAddressSpace().setByte(0xff21, 0xf3); // CH4 DAC/envelope
            candidate.getAddressSpace().setByte(0xff21, 0xf3);
            scalar.getAddressSpace().setByte(0xff22, 0x30); // stable nonzero divisor
            candidate.getAddressSpace().setByte(0xff22, 0x30);
            scalar.getCpu().getRegisters().setA(0xc0);
            candidate.getCpu().getRegisters().setA(0xc0);
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            int boundaryGuard = 0;
            while (!(candidate.getCpu().getState() == Cpu.State.RUNNING
                    && candidate.getCpu().getDebugOpcode() == 0xe0
                    && candidate.getCpu().getRegisters().getPC() == 0x0202
                    && candidate.getCpu().getDebugMachineCycle() == 3)
                    && boundaryGuard++ < 64) {
                assertEquals("native CGB x1 NR44 decode frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not stop immediately before the NR44 write", boundaryGuard < 64);
            assertEquals("native CGB x1 scalar oracle entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("safe NR44 decode prefix had no native CGB x1 epoch coverage",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("before native CGB x1 NR44 trigger boundary",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("native CGB x1 NR44 trigger frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
            assertEquals("decoded NR44 write crossed a native CGB x1 epoch",
                    0L, candidate.getPerformanceEpochTicks());
            assertEquals("decoded NR44 write reached the deferred epoch bus",
                    0L, candidate.getCpu().getPerformanceEpochTerminalAccesses());
            assertTrue("NR44 trigger did not enable CH4",
                    (candidate.getAddressSpace().getByte(0xff26) & 0x08) != 0);
            assertDeepStateEquals("native CGB x1 NR44 trigger Sound state",
                    scalar.getSound().captureState(), candidate.getSound().captureState());
            assertDeepStateEquals("after native CGB x1 NR44 trigger boundary",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void fastForwardNativeCgbNormalSpeedMatchesScalarAfterCompletedBootGdma()
            throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
             Gameboy scalar = fastForwardNativeCgbNormalSpeedSession(
                     PlayerInputSnapshot::released);
             Gameboy candidate = fastForwardNativeCgbNormalSpeedSession(candidateHub)) {
            assertEquals("native CGB FAST_FORWARD handoff tail",
                    scalar.runTicksUntilStop(32, scalar::isBootstrapReady),
                    candidate.runTicksUntilStop(32, candidate::isBootstrapReady));
            assertTrue(scalar.isBootstrapReady());
            assertTrue(candidate.isBootstrapReady());
            assertFalse(candidate.getSpeedMode().isDmgCompat());
            assertEquals(1, candidate.getSpeedMode().getSpeedMode());
            Hdma.HdmaState bootHdma = (Hdma.HdmaState) candidate.getHdma().captureState();
            assertEquals("authentic boot did not retain the completed-GDMA request clock",
                    0, bootHdma.hblankRequestTicks());
            assertDeepStateEquals("native CGB FAST_FORWARD handoff",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            for (int chunk = 0; chunk < 12; chunk++) {
                assertEquals("native CGB FAST_FORWARD frame callback " + chunk,
                        scalar.runTicks(5_000), candidate.runTicks(5_000));
                assertDeepStateEquals("native CGB FAST_FORWARD chunk " + chunk,
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
            assertEquals("FAST_FORWARD scalar oracle entered the epoch lane", 0L,
                    scalar.getPerformanceEpochTicks());
            assertTrue("FAST_FORWARD native CGB x1 had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 0L);
        }
    }

    @Test
    public void nativeCgbNormalSpeedEpochIsLimitedToOrdinaryPerformanceCgb() throws Exception {
        byte[] nativeColor = nativeColor(dmgRomWramLoop());
        try (Gameboy cgb0 = new Gameboy.GameboyConfiguration(new Rom(nativeColor))
                     .setHardwareProfile(HardwareProfileRegistry.CGB0)
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setSupportBatterySave(false)
                     .build();
             Gameboy nativeSpeed1 = new Gameboy.GameboyConfiguration(new Rom(nativeColor))
                     .setHardwareProfile(HardwareProfileRegistry.CGB)
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setExecutionMode(ExecutionMode.PERFORMANCE)
                     .setSupportBatterySave(false)
                     .build();
             Gameboy accuracy = new Gameboy.GameboyConfiguration(new Rom(nativeColor))
                     .setHardwareProfile(HardwareProfileRegistry.CGB)
                     .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                     .setExecutionMode(ExecutionMode.ACCURACY)
                     .setSupportBatterySave(false)
                     .build()) {
            cgb0.runTicks(100_000);
            nativeSpeed1.runTicks(100_000);
            accuracy.runTicks(100_000);
            assertEquals(0L, cgb0.getPerformanceEpochTicks());
            assertTrue(nativeSpeed1.getPerformanceEpochTicks() > 0L);
            assertEquals(0L, accuracy.getPerformanceEpochTicks());
            assertFalse(nativeSpeed1.getSpeedMode().isDmgCompat());
            assertEquals(1, nativeSpeed1.getSpeedMode().getSpeedMode());
        }
    }

    @Test
    public void sgbProfilesFencedEpochsMatchScalarAcrossAFrame() throws Exception {
        byte[] image = dmgRomWramLoop();
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(image, profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(image, profile, PlayerInputSource.RELEASED)) {
                long scalarFrames = 0;
                long candidateFrames = 0;
                for (int chunk = 0; chunk < 20; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }

                assertEquals(profile.id() + " frame callbacks", scalarFrames, candidateFrames);
                assertEquals(profile.id() + " custom-source oracle entered the epoch lane",
                        0L, scalar.getPerformanceEpochTicks());
                assertTrue(profile.id() + " did not enter its fenced epoch lane",
                        candidate.getPerformanceEpochTicks() > 5_000);
                assertEquals(profile.id() + " crossed an unsafe memory boundary inside an epoch",
                        0L, candidate.getCpu().getPerformanceEpochTerminalAccesses());
                assertEquals(profile.id() + " WRAM loop", scalar.getAddressSpace().getByte(0xc000),
                        candidate.getAddressSpace().getByte(0xc000));
                assertDeepStateEquals(profile.id() + " fenced epoch",
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void sgbProfilesSafeDecodedStackAndIndirectOpsMatchScalar() throws Exception {
        byte[] image = dmgRomSafeDecodedStackLoop();
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(image, profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(image, profile, PlayerInputSource.RELEASED)) {
                long scalarFrames = 0;
                long candidateFrames = 0;
                for (int chunk = 0; chunk < 20; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }

                assertEquals(profile.id() + " safe stack frame callbacks",
                        scalarFrames, candidateFrames);
                assertEquals(profile.id() + " safe stack custom-source oracle entered the epoch lane",
                        0L, scalar.getPerformanceEpochTicks());
                assertTrue(profile.id() + " safe stack loop had no coarse epoch coverage",
                        candidate.getPerformanceEpochTicks() > 0L);
                assertEquals(profile.id() + " safe stack crossed an unsafe boundary",
                        0L, candidate.getCpu().getPerformanceEpochTerminalAccesses());
                assertEquals(profile.id() + " safe WRAM result", 1,
                        candidate.getAddressSpace().getByte(0xc000));
                assertDeepStateEquals(profile.id() + " safe stack epoch",
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void sgbRasterHorizonReuseMatchesScalarAtRasterPhasesAndBudgets()
            throws Exception {
        int[] budgets = {1, 3, 7, 17, 54};
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            for (SgbRasterPhase phase : SgbRasterPhase.values()) {
                for (int budget : budgets) {
                    try (Gameboy scalar = sgbSession(
                            dmgRomWramLoop(), profile, PlayerInputSnapshot::released);
                         Gameboy candidate = sgbSession(
                                 dmgRomWramLoop(), profile, PlayerInputSource.RELEASED)) {
                        scalar.getGpu().setPerformanceScanlineEnabled(true);
                        candidate.getGpu().setPerformanceScanlineEnabled(true);
                        advanceSgbPairToRasterPhase(scalar, candidate, phase);
                        int rasterHorizon = candidate.getGpu()
                                .performancePhysicalDmgEpochSpanLimit(54);
                        assertTrue(profile.id() + " " + phase + " has no raster horizon",
                                rasterHorizon > 0);
                        assertEquals(profile.id() + " " + phase + " horizon budget " + budget,
                                Math.min(budget, rasterHorizon),
                                candidate.getGpu().performancePhysicalDmgEpochSpanLimit(budget));

                        assertEquals(profile.id() + " " + phase + " frame callbacks " + budget,
                                runScalarTicks(scalar, budget), candidate.runTicks(budget));
                        assertDeepStateEquals(profile.id() + " " + phase + " budget " + budget,
                                scalar.captureStateWithoutTimeSource(),
                                candidate.captureStateWithoutTimeSource());
                        if (budget == 54) {
                            assertTrue(profile.id() + " " + phase + " did not use an SGB epoch",
                                    candidate.getPerformanceEpochTicks() > 0L);
                            if (phase == SgbRasterPhase.DIRECT) {
                                assertTrue(profile.id() + " direct ticks were not raster-accounted",
                                        candidate.getPerformanceEpochRasterFastTicks() > 0L);
                                assertEquals(profile.id() + " direct ticks entered SGB idle", 0L,
                                        candidate.getPerformanceEpochSgbIdleTicks());
                            } else {
                                assertTrue(profile.id() + " " + phase
                                                + " ticks were not SGB-idle-accounted",
                                        candidate.getPerformanceEpochSgbIdleTicks() > 0L);
                                assertEquals(profile.id() + " " + phase
                                                + " ticks leaked into raster accounting", 0L,
                                        candidate.getPerformanceEpochRasterFastTicks());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void sgbStrictMaskedInterruptFallbackMatchesScalar()
            throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(
                    dmgRomWramLoop(), profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(
                         dmgRomWramLoop(), profile, PlayerInputSource.RELEASED)) {
                scalar.getGpu().setPerformanceScanlineEnabled(true);
                candidate.getGpu().setPerformanceScanlineEnabled(true);
                advanceSgbPairToRasterPhase(scalar, candidate, SgbRasterPhase.HBLANK);
                candidate.resetPerformanceBulkCounters();

                for (Gameboy gameboy : new Gameboy[]{scalar, candidate}) {
                    gameboy.getAddressSpace().setByte(0xffff, 1);
                    gameboy.getAddressSpace().setByte(0xff0f,
                            gameboy.getAddressSpace().getByte(0xff0f) | 1);
                }
                assertFalse(profile.id() + " admitted a masked SGB interrupt",
                        candidate.getCpu().performanceNormalSpeedEpochEntryEligible(false));

                int scalarFrames = runScalarTicks(scalar, 54);
                int candidateFrames = candidate.runTicks(54);

                assertEquals(profile.id() + " frame callbacks", scalarFrames, candidateFrames);
                assertEquals(profile.id() + " strict masked SGB idle commit", 0L,
                        candidate.getPerformanceEpochSgbIdleTicks());
                assertDeepStateEquals(profile.id() + " strict masked fallback",
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void sgbProfilesMultiplayerQuietIntervalMatchesScalarAroundMltReq()
            throws Exception {
        byte[] image = dmgRomWramLoop();
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (Gameboy scalar = sgbSession(image, profile, PlayerInputSnapshot::released);
                 Gameboy candidate = sgbSession(image, profile, PlayerInputSource.RELEASED)) {
                postMltReq(scalar, 1);
                postMltReq(candidate, 1);
                assertEquals(profile.id() + " MLT_REQ(1) player count", 2,
                        candidate.getSgbMultiplayerStatus().playerCount());

                long scalarFrames = 0;
                long candidateFrames = 0;
                for (int chunk = 0; chunk < 4; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }
                assertTrue(profile.id() + " MLT_REQ(1) had no epoch coverage",
                        candidate.getPerformanceEpochTicks() > 0L);
                assertTrue(profile.id() + " MLT_REQ(1) had no bulk coverage",
                        candidate.getPerformanceBulkTicks()
                                + candidate.getPerformanceEpochMode2BulkTicks() > 0L);
                assertEquals(profile.id() + " MLT_REQ(1) crossed a terminal CPU access", 0L,
                        candidate.getCpu().getPerformanceEpochTerminalAccesses());

                postMltReq(scalar, 0);
                postMltReq(candidate, 0);
                for (int chunk = 0; chunk < 4; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }

                assertEquals(profile.id() + " multiplayer frame callbacks",
                        scalarFrames, candidateFrames);
                assertEquals(profile.id() + " MLT_REQ(0) player count", 1,
                        candidate.getSgbMultiplayerStatus().playerCount());
                assertEquals(profile.id() + " multiplayer terminal CPU access", 0L,
                        candidate.getCpu().getPerformanceEpochTerminalAccesses());
                assertDeepStateEquals(profile.id() + " multiplayer quiet interval",
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void sgbProfilesMultiplayerReleasedHubMatchesForcedScalarAroundMltReq()
            throws Exception {
        byte[] image = dmgRomWramLoop();
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            PlayerInputHub hub = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle ignored = hub.openSource(0);
                 Gameboy scalar = sgbSession(image, profile, hub::sample);
                 Gameboy candidate = sgbSession(image, profile, hub)) {
                // PlayerInputHub rebuilds an equal but non-identical empty snapshot, matching the
                // Android controller path rather than the RELEASED singleton source.
                ignored.update(Set.of());
                postMltReq(scalar, 1);
                postMltReq(candidate, 1);

                long scalarFrames = 0;
                long candidateFrames = 0;
                for (int chunk = 0; chunk < 4; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }
                assertEquals(profile.id() + " Hub MLT_REQ(1) frame callbacks",
                        scalarFrames, candidateFrames);
                assertEquals(profile.id() + " Hub forced-scalar oracle entered an epoch",
                        0L, scalar.getPerformanceEpochTicks());
                assertTrue(profile.id() + " released Hub MLT_REQ(1) had no epoch coverage",
                        candidate.getPerformanceEpochTicks() > 0L);
                assertTrue(profile.id() + " released Hub MLT_REQ(1) had no bulk coverage",
                        candidate.getPerformanceBulkTicks()
                                + candidate.getPerformanceEpochMode2BulkTicks() > 0L);
                assertEquals(profile.id() + " released Hub crossed a terminal CPU access", 0L,
                        candidate.getCpu().getPerformanceEpochTerminalAccesses());

                postMltReq(scalar, 0);
                postMltReq(candidate, 0);
                for (int chunk = 0; chunk < 4; chunk++) {
                    scalarFrames += scalar.runTicks(5_000);
                    candidateFrames += candidate.runTicks(5_000);
                }

                assertEquals(profile.id() + " released Hub multiplayer frame callbacks",
                        scalarFrames, candidateFrames);
                assertEquals(profile.id() + " released Hub final player count", 1,
                        candidate.getSgbMultiplayerStatus().playerCount());
                assertEquals(profile.id() + " released Hub terminal CPU access", 0L,
                        candidate.getCpu().getPerformanceEpochTerminalAccesses());
                assertDeepStateEquals(profile.id() + " released Hub multiplayer interval",
                        scalar.captureStateWithoutTimeSource(),
                        candidate.captureStateWithoutTimeSource());
            }
        }
    }

    @Test
    public void physicalDmgRomWramLoopMatchesLegacySchedulerWithEpochCoverage()
            throws Exception {
        byte[] image = dmgRomWramLoop();
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < 20; chunk++) {
                scalarFrames += scalar.runTicks(5_000);
                candidateFrames += candidate.runTicks(5_000);
            }

            assertEquals("physical-DMG frame callbacks", scalarFrames, candidateFrames);
            assertEquals("custom-source oracle unexpectedly entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("physical-DMG ROM/WRAM loop had no coarse coverage",
                    candidate.getPerformanceEpochTicks() > 10_000);
            assertEquals("physical DMG epoch plan accounting",
                    candidate.getPerformanceEpochTicks(),
                    candidate.getPerformanceEpochRasterFastTicks()
                            + candidate.getPerformanceEpochMode2ReplayTicks());
            assertEquals("physical DMG mode-2 bulk accounting",
                    candidate.getPerformanceEpochMode2ReplayTicks(),
                    candidate.getPerformanceEpochMode2BulkTicks());
            assertEquals(scalar.getAddressSpace().getByte(0xc000),
                    candidate.getAddressSpace().getByte(0xc000));
            assertDeepStateEquals("physical-DMG ROM/WRAM loop",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    private enum SgbRasterPhase {
        DIRECT,
        HBLANK,
        VBLANK
    }

    private static void advanceSgbPairToRasterPhase(
            Gameboy scalar, Gameboy candidate, SgbRasterPhase phase) {
        int guard = 0;
        while (!isSgbRasterPhase(candidate, phase) && guard++ < 456 * 160) {
            assertEquals("SGB " + phase + " setup frame callback",
                    scalar.tick(), candidate.tick());
        }
        assertTrue("SGB setup did not reach " + phase, guard < 456 * 160);
        assertDeepStateEquals("SGB " + phase + " setup",
                scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static boolean isSgbRasterPhase(Gameboy gameboy, SgbRasterPhase phase) {
        if (gameboy.getGpu().performancePhysicalDmgEpochSpanLimit(1) <= 0) {
            return false;
        }
        return switch (phase) {
            case DIRECT -> gameboy.getGpu().getMode() == Mode.PixelTransfer
                    && gameboy.getGpu().isPerformanceScanlineCursorActive();
            case HBLANK -> gameboy.getGpu().getMode() == Mode.HBlank;
            case VBLANK -> gameboy.getGpu().getMode() == Mode.VBlank;
        };
    }

    private static int runScalarTicks(Gameboy gameboy, int ticks) {
        int frames = 0;
        for (int i = 0; i < ticks; i++) {
            if (gameboy.tick()) {
                frames++;
            }
        }
        return frames;
    }

    @Test
    public void physicalDmgPlainOamWritesDelegateImmediatelyAndMatchScalar() throws Exception {
        assertPhysicalDmgOamLoopMatchesScalar(dmgPlainOamWriteLoop(), 120_000,
                "plain LD-to-OAM");
    }

    @Test
    public void physicalDmgPushOamCorruptionAndSuppressionMatchScalar() throws Exception {
        assertPhysicalDmgOamLoopMatchesScalar(dmgPushOamLoop(), 120_000,
                "PUSH OAM-bug suppression");
    }

    @Test
    public void physicalDmgEpochRetiringHaltPublishesDmaPauseLatch() throws Exception {
        byte[] image = dmgRomWramLoop();
        image[0x200] = 0x76; // HALT, selected after reaching a trusted raster span
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0
                    && candidate.getCpu().performancePhysicalDmgEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("HALT setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted physical-DMG HALT entry", guard < 200_000);
            assertDeepStateEquals("before physical-DMG HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("HALT frame callback", scalar.runTicks(8), candidate.runTicks(8));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertTrue("HALT fetch did not retire inside a physical-DMG epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("after physical-DMG HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityEpochRetiringHaltMatchesScalarDeepState() throws Exception {
        byte[] image = dmgRomWramLoop();
        image[0x200] = 0x76; // HALT, selected after reaching a trusted CGB raster span
        try (Gameboy scalar = cgbCompatibilitySession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = cgbCompatibilitySession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            completeOneBlockGdmaPair(scalar, candidate,
                    "CGB compatibility completed-GDMA HALT setup");
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0
                    && candidate.getCpu().performanceCgbCompatibilityEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("CGB compatibility HALT setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted CGB compatibility HALT entry",
                    guard < 200_000);
            assertDeepStateEquals("before CGB compatibility HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("CGB compatibility HALT frame callback", scalar.runTicks(8),
                    candidate.runTicks(8));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertTrue("HALT fetch did not retire inside a CGB compatibility epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("after CGB compatibility HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedEpochAndSettledHaltMatchScalarDeepState()
            throws Exception {
        byte[] image = nativeColor(dmgRomWramLoop());
        image[0x200] = 0x76; // HALT, selected after reaching a trusted CGB raster span
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            completeOneBlockGdmaPair(scalar, candidate,
                    "native CGB x1 completed-GDMA HALT setup");
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0
                    && candidate.getCpu().performanceNativeCgbNormalSpeedEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("native CGB x1 HALT setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted native CGB x1 HALT entry",
                    guard < 200_000);
            assertDeepStateEquals("before native CGB x1 HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("native CGB x1 HALT frame callback",
                    scalar.runTicks(8), candidate.runTicks(8));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertEquals("native CGB x1 scalar oracle entered the epoch lane",
                    0L, scalar.getPerformanceEpochTicks());
            assertTrue("HALT fetch did not retire inside a native CGB x1 epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("after native CGB x1 HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();
            assertEquals("native CGB x1 settled-HALT frame callback",
                    scalar.runTicks(64), candidate.runTicks(64));
            assertTrue("native CGB x1 settled HALT had no long packet",
                    candidate.getPerformanceBulkMaxTicks() > 3);
            assertDeepStateEquals("native CGB x1 settled HALT",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            // A quiet-raster settled-HALT packet does not prove the native-x1 mode-2 lane.
            // Place both machines at a visible OAM-search fixed point, then run exactly to the
            // scalar dot-79 handoff.  Before this optimization, the scheduler could cover this
            // interval only with one-to-three-dot phase packets, so bulkMaxTicks stayed <= 3.
            advanceScalarPairToVisibleMode2Dot(scalar, candidate, 20);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();
            assertEquals("native CGB x1 mode-2 settled-HALT frame callback",
                    scalar.runTicks(59), candidate.runTicks(59));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertEquals(Mode.OamSearch, candidate.getGpu().getMode());
            assertEquals(79, candidate.getGpu().getTicksInLine());
            assertEquals("mode-2 settled HALT unexpectedly used a running-CPU epoch",
                    0L, candidate.getPerformanceEpochTicks());
            assertTrue("native CGB x1 mode-2 settled HALT had no long packet",
                    candidate.getPerformanceBulkMaxTicks() > 3);
            assertDeepStateEquals("native CGB x1 mode-2 settled HALT at dot 79",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            assertEquals("native CGB x1 scalar dot-80 handoff frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
            assertEquals(Mode.PixelTransfer, candidate.getGpu().getMode());
            assertDeepStateEquals("native CGB x1 scalar dot-80 handoff",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbNormalSpeedOrdinaryHaltWakeUsesMaskedEpochAndPhasePackets()
            throws Exception {
        byte[] image = nativeColor(dmgRomWramLoop());
        image[0x200] = 0x76; // HALT
        image[0x201] = 0x00; // NOP stream after the ordinary IME=0 wake
        try (Gameboy scalar = nativeCgbNormalSpeedSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = nativeCgbNormalSpeedSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 0)
                    && guard++ < 200_000) {
                assertEquals("ordinary-wake setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("ordinary-wake setup did not reach a stable epoch point",
                    guard < 200_000);
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);

            assertEquals("HALT entry frame callback",
                    scalar.runTicks(8), candidate.runTicks(8));
            assertEquals(Cpu.State.HALTED, scalar.getCpu().getState());
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertDeepStateEquals("settled HALT before ordinary wake",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            for (Gameboy gameboy : new Gameboy[] {scalar, candidate}) {
                gameboy.getAddressSpace().setByte(0xffff, 0x01);
                gameboy.getAddressSpace().setByte(0xff0f,
                        gameboy.getAddressSpace().getByte(0xff0f) | 0x01);
                gameboy.resetPerformanceBulkCounters();
            }

            assertEquals("ordinary-wake frame callback",
                    scalar.runTicks(2_000), candidate.runTicks(2_000));
            assertTrue("fixture did not retain the ordinary HALT-wake phase",
                    candidate.getCpu().isOrdinaryHaltWakeStatPhase());
            assertTrue("IME=0 raw pending code had no epoch coverage",
                    candidate.getPerformanceEpochTicks() > 0L);
            assertTrue("ordinary HALT-wake phase had no short packet coverage",
                    candidate.getPerformanceBulkTicks() > 0L);
            assertDeepStateEquals("ordinary wake before restore",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            var scalarCheckpoint = scalar.captureState();
            var candidateCheckpoint = candidate.captureState();
            scalar.restoreState(scalarCheckpoint);
            candidate.restoreState(candidateCheckpoint);
            assertEquals("ordinary-wake restored frame callback",
                    scalar.runTicks(2_000), candidate.runTicks(2_000));
            assertDeepStateEquals("ordinary wake after restore",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void nativeCgbDoubleSpeedEpochRetiringHaltAfterCompletedGdmaMatchesScalar()
            throws Exception {
        byte[] image = doubleSpeedLoop();
        image[0x200] = 0x76;
        try (Gameboy scalar = nativeDoubleSpeedSession(
                image, PlayerInputSnapshot::released);
             Gameboy candidate = nativeDoubleSpeedSession(
                     image, PlayerInputSource.RELEASED)) {
            int speedGuard = 0;
            while (!(candidate.getSpeedMode().getSpeedMode() == 2
                    && candidate.getCpu().getState() != Cpu.State.SPEED_SWITCH)
                    && speedGuard++ < 300_000) {
                assertEquals("native CGB x2 speed setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("native CGB x2 setup did not finish its speed switch",
                    speedGuard < 300_000);
            completeOneBlockGdmaPair(scalar, candidate,
                    "native CGB x2 completed-GDMA HALT setup");

            int guard = 0;
            while (!(candidate.getGpu().isPerformanceScanlineCursorActive()
                    && candidate.getGpu().getTicksInLine() >= 100
                    && candidate.getGpu().getTicksInLine() <= 180
                    && candidate.getCpu().getState() == Cpu.State.OPCODE
                    && candidate.getCpu().getDebugMachineCycle() == 1
                    && candidate.getCpu().performanceEpochEntryEligible())
                    && guard++ < 200_000) {
                assertEquals("native CGB x2 HALT setup frame callback",
                        scalar.runTicks(1), candidate.runTicks(1));
            }
            assertTrue("test did not reach a trusted native CGB x2 HALT entry",
                    guard < 200_000);
            assertDeepStateEquals("before native CGB x2 HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
            scalar.getCpu().getRegisters().setPC(0x0200);
            candidate.getCpu().getRegisters().setPC(0x0200);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            assertEquals("native CGB x2 HALT frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
            assertEquals(Cpu.State.HALTED, candidate.getCpu().getState());
            assertTrue("HALT fetch did not retire inside a native CGB x2 epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertDeepStateEquals("after native CGB x2 completed-GDMA HALT epoch",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityMeasuredWindowRetainsEpochLane() throws Exception {
        try (Gameboy gameboy = cgbCompatibilitySession(
                dmgRomWramLoop(), PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            gameboy.resetPerformanceBulkCounters();
            assertEquals(4_096, gameboy.runMeasuredTicksUntilStop(4_096, () -> false));
            assertTrue("measured CGB compatibility window lost epoch coverage",
                    gameboy.getPerformanceEpochTicks() > 0);
        }
    }

    @Test
    public void accuracyStaysOutsidePhysicalDmgEpoch() throws Exception {
        byte[] image = dmgRomWramLoop();
        try (Gameboy accuracy = physicalDmgSession(
                image, PlayerInputSource.RELEASED, ExecutionMode.ACCURACY)) {
            accuracy.runTicks(100_000);
            assertEquals(0L, accuracy.getPerformanceEpochTicks());
        }
    }

    @Test
    public void mode2ReplayMatchesScalarAndLeavesTheHandoffTickScalar() throws Exception {
        try (Gameboy scalar = nativeDoubleSpeedSession();
             Gameboy replay = nativeDoubleSpeedSession()) {
            advanceScalarToMode2ReplayStart(scalar);
            advanceScalarToMode2ReplayStart(replay);
            assertDeepStateEquals("mode-2 start", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());
            replay.getGpu().setPerformanceScanlineEnabled(true);
            int mode2Limit = replay.getGpu().performanceEpochMode2ReplaySpanLimit(54);
            replay.getGpu().setPerformanceScanlineEnabled(false);
            assertTrue("mode-2 replay preflight rejected start: limit="
                            + mode2Limit
                            + " cpu=" + replay.getCpu().performanceEpochEntryEligible()
                            + " mode=" + replay.getGpu().getMode()
                            + " dot=" + replay.getGpu().getTicksInLine()
                            + " cursor=" + replay.getGpu().isPerformanceScanlineCursorActive(),
                    mode2Limit > 0);
            assertTrue("CPU did not settle after the speed-switch countdown",
                    replay.getCpu().performanceEpochEntryEligible());

            scalar.resetPerformanceBulkCounters();
            replay.resetPerformanceBulkCounters();
            int replayDots = 79 - replay.getGpu().getTicksInLine();
            for (int i = 0; i < replayDots; i++) {
                scalar.tick();
            }
            assertEquals(0, replay.runTicks(replayDots));

            assertEquals(Mode.OamSearch, replay.getGpu().getMode());
            assertEquals(79, replay.getGpu().getTicksInLine());
            assertTrue("mode-2 epoch coverage was too small: "
                            + replay.getPerformanceEpochMode2ReplayTicks(),
                    replay.getPerformanceEpochMode2ReplayTicks() >= 40);
            assertEquals("mode-2 epochs used the arithmetic raster plan",
                    replay.getPerformanceEpochTicks(),
                    replay.getPerformanceEpochMode2ReplayTicks());
            assertEquals("mode-2 epoch fell back to the per-dot PPU replay",
                    replay.getPerformanceEpochMode2ReplayTicks(),
                    replay.getPerformanceEpochMode2BulkTicks());
            assertEquals(0L, replay.getPerformanceEpochRasterFastTicks());
            assertDeepStateEquals("mode-2 dot 79", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());

            long replayedBeforeHandoff = replay.getPerformanceEpochMode2ReplayTicks();
            assertEquals(scalar.runTicks(1), replay.runTicks(1));
            assertEquals("mode-3 handoff was replayed inside the mode-2 transaction",
                    replayedBeforeHandoff, replay.getPerformanceEpochMode2ReplayTicks());
            assertEquals("mode-3 handoff was included in the mode-2 bulk counter",
                    replayedBeforeHandoff, replay.getPerformanceEpochMode2BulkTicks());
            assertEquals(Mode.PixelTransfer, replay.getGpu().getMode());
            assertTrue("scalar handoff did not arm the direct renderer",
                    replay.getGpu().isPerformanceScanlineCursorActive());
            assertDeepStateEquals("mode-3 handoff", scalar.captureStateWithoutTimeSource(),
                    replay.captureStateWithoutTimeSource());
        }
    }

    private static Gameboy nativeDoubleSpeedSession() throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(doubleSpeedLoop()))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy nativeDoubleSpeedSession(
            byte[] image, PlayerInputSource inputSource) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy fastForwardCgbCompatibilitySession(PlayerInputSource inputSource)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(validNonColorRom()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy fastForwardNativeCgbNormalSpeedSession(PlayerInputSource inputSource)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(validNativeColorRom()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy physicalDmgSession(
            byte[] image, PlayerInputSource inputSource, ExecutionMode executionMode)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(HardwareProfileRegistry.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(executionMode)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy cgbCompatibilitySession(
            byte[] image, PlayerInputSource inputSource, ExecutionMode executionMode)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(executionMode)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy nativeCgbNormalSpeedSession(
            byte[] image, PlayerInputSource inputSource, ExecutionMode executionMode)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(nativeColor(image)))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(executionMode)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy nativeCgbNormalSpeedMbc3Session(
            byte[] image, PlayerInputSource inputSource) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(nativeMbc3(image)))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .build();
    }

    private static Gameboy sgbSession(
            byte[] image, HardwareProfile profile, PlayerInputSource inputSource)
            throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static void postMltReq(Gameboy gameboy, int players) throws Exception {
        int[] packet = new int[Commands.PACKET_SIZE];
        packet[0] = 0x11 * 8 + 1;
        packet[1] = players;
        var field = Gameboy.class.getDeclaredField("sgbBus");
        field.setAccessible(true);
        EventBus sgbBus = (EventBus) field.get(gameboy);
        sgbBus.post(Commands.toCommand(packet));
    }

    private static void assertPhysicalDmgOamLoopMatchesScalar(
            byte[] image, int ticks, String label) throws Exception {
        try (Gameboy scalar = physicalDmgSession(
                image, PlayerInputSnapshot::released, ExecutionMode.PERFORMANCE);
             Gameboy candidate = physicalDmgSession(
                     image, PlayerInputSource.RELEASED, ExecutionMode.PERFORMANCE)) {
            // SKIP boot can already be inside mode 2, where ordinary OAM writes are locked.
            // Expose OAM explicitly and prove the non-uniform seed took effect so PUSH-class
            // corruption cannot collapse into an all-zero fixed point.
            int scalarLcdc = scalar.getAddressSpace().getByte(0xff40);
            int candidateLcdc = candidate.getAddressSpace().getByte(0xff40);
            assertEquals(label + " initial LCDC", scalarLcdc, candidateLcdc);
            scalar.getAddressSpace().setByte(0xff40, 0x00);
            candidate.getAddressSpace().setByte(0xff40, 0x00);
            for (int offset = 0; offset < 0xa0; offset++) {
                int value = (offset * 37 + 11) & 0xff;
                scalar.getAddressSpace().setByte(0xfe00 + offset, value);
                candidate.getAddressSpace().setByte(0xfe00 + offset, value);
                assertEquals(label + " scalar OAM seed " + offset,
                        value, scalar.getAddressSpace().getByte(0xfe00 + offset));
                assertEquals(label + " candidate OAM seed " + offset,
                        value, candidate.getAddressSpace().getByte(0xfe00 + offset));
            }
            scalar.getAddressSpace().setByte(0xff40, scalarLcdc);
            candidate.getAddressSpace().setByte(0xff40, candidateLcdc);

            long scalarFrames = 0;
            long candidateFrames = 0;
            for (int chunk = 0; chunk < ticks / 4_000; chunk++) {
                scalarFrames += scalar.runTicks(4_000);
                candidateFrames += candidate.runTicks(4_000);
            }

            assertEquals(label + " frame callbacks", scalarFrames, candidateFrames);
            assertTrue(label + " did not exercise a coarse epoch",
                    candidate.getPerformanceEpochTicks() > 0);
            assertTrue(label + " did not terminate an epoch on an OAM access",
                    candidate.getCpu().getPerformanceEpochTerminalAccesses() > 0);
            assertDeepStateEquals(label,
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    private static void advanceScalarToMode2ReplayStart(Gameboy gameboy) {
        int guard = 0;
        int stableDoubleSpeedTicks = 0;
        while (!(stableDoubleSpeedTicks >= 1_000
                && !gameboy.getGpu().isFirstLine()
                && gameboy.getGpu().getLine() < 144
                && gameboy.getGpu().getMode() == Mode.OamSearch
                && gameboy.getGpu().getTicksInLine() == 13)
                && guard++ < 400_000) {
            gameboy.tick();
            if (gameboy.getSpeedMode().getSpeedMode() == 2
                    && gameboy.getCpu().getState() != Cpu.State.SPEED_SWITCH) {
                stableDoubleSpeedTicks++;
            } else {
                stableDoubleSpeedTicks = 0;
            }
        }
        assertTrue("test did not reach a settled native-CGB mode-2 dot", guard < 400_000);
    }

    private static void advanceScalarPairToVisibleMode2Dot(
            Gameboy scalar, Gameboy candidate, int targetDot) {
        int guard = 0;
        while (!(scalar.getGpu().isLcdEnabled()
                && !scalar.getGpu().isFirstLine()
                && scalar.getGpu().getLine() < 144
                && scalar.getGpu().getMode() == Mode.OamSearch
                && scalar.getGpu().getTicksInLine() == targetDot)
                && guard++ < 456 * 160) {
            assertEquals("FAST_FORWARD mode-2 setup frame callback",
                    scalar.tick(), candidate.tick());
        }
        assertTrue("FAST_FORWARD setup did not reach visible mode-2 dot " + targetDot,
                guard < 456 * 160);
        assertDeepStateEquals("FAST_FORWARD visible mode-2 setup",
                scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static void completeOneBlockGdmaPair(
            Gameboy scalar, Gameboy candidate, String label) {
        startOneBlockGdma(scalar);
        startOneBlockGdma(candidate);
        int guard = 0;
        while (candidate.getHdma().hasActiveOrPendingTransfer() && guard++ < 256) {
            assertEquals(label + " frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
        }
        assertTrue(label + " did not complete", guard < 256);
        Hdma.HdmaState scalarHdma = (Hdma.HdmaState) scalar.getHdma().captureState();
        Hdma.HdmaState candidateHdma = (Hdma.HdmaState) candidate.getHdma().captureState();
        assertFalse(label + " scalar transfer remained active", scalarHdma.transferInProgress());
        assertFalse(label + " candidate transfer remained active",
                candidateHdma.transferInProgress());
        assertFalse(label + " scalar HBlank transfer remained armed",
                scalarHdma.hblankTransfer());
        assertFalse(label + " candidate HBlank transfer remained armed",
                candidateHdma.hblankTransfer());
        assertEquals(label + " scalar request clock", 0, scalarHdma.hblankRequestTicks());
        assertEquals(label + " candidate request clock", 0,
                candidateHdma.hblankRequestTicks());
        assertDeepStateEquals(label,
                scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static void advancePairUntilLcdDisabled(Gameboy scalar, Gameboy candidate) {
        int guard = 0;
        while ((scalar.getGpu().isLcdEnabled() || candidate.getGpu().isLcdEnabled())
                && guard++ < 256) {
            assertEquals("LCD-disable setup frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
        }
        assertTrue("test ROM did not disable the LCD", guard < 256);
        assertFalse(scalar.getGpu().isLcdEnabled());
        assertFalse(candidate.getGpu().isLcdEnabled());
        assertDeepStateEquals("after scalar LCD-disable boundary",
                scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static void assertLcdOffVramAndLcdcEnableMatchesScalar(
            Gameboy scalar, Gameboy candidate, String label, boolean sgb) {
        advancePairUntilLcdDisabled(scalar, candidate);
        for (Gameboy gameboy : new Gameboy[]{scalar, candidate}) {
            gameboy.getAddressSpace().setByte(0xff41, 0x40);
            gameboy.getAddressSpace().setByte(0xff45, 1);
        }
        scalar.resetPerformanceBulkCounters();
        candidate.resetPerformanceBulkCounters();

        assertEquals(label + " LCD-off frame callback",
                scalar.runTicks(2_000), candidate.runTicks(2_000));
        var scalarCheckpoint = scalar.captureStateWithoutTimeSource();
        var candidateCheckpoint = candidate.captureStateWithoutTimeSource();
        assertEquals(label + " uninterrupted memento continuation",
                scalar.runTicks(257), candidate.runTicks(257));
        assertDeepStateEquals(label + " uninterrupted memento state",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
        scalar.restoreStateSilently(scalarCheckpoint);
        candidate.restoreStateSilently(candidateCheckpoint);
        assertEquals(label + " restored memento continuation",
                scalar.runTicks(257), candidate.runTicks(257));
        assertDeepStateEquals(label + " restored memento state",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
        int guard = 0;
        while (!(candidate.getCpu().getState() == Cpu.State.RUNNING
                && candidate.getCpu().getDebugOpcode() == 0xe0
                && candidate.getCpu().getRegisters().getPC() == 0x0112
                && candidate.getCpu().getDebugMachineCycle() == 3)
                && guard++ < 12_000) {
            assertEquals(label + " LCDC-enable setup frame callback",
                    scalar.runTicks(1), candidate.runTicks(1));
        }
        assertTrue(label + " did not stop before the LCDC-enable write", guard < 12_000);
        assertFalse(candidate.getGpu().isLcdEnabled());
        assertTrue(label + " had no LCD-off epoch coverage",
                candidate.getPerformanceEpochLcdOffTicks() > 0L);
        if (sgb) {
            assertTrue(label + " did not continue LCD-off epochs past the inert LYC deadline",
                    candidate.getPerformanceEpochLcdOffTicks() > 1_000L);
        }
        assertEquals(label + " LCD-off epoch reached a terminal CPU access", 0L,
                candidate.getCpu().getPerformanceEpochTerminalAccesses());
        assertDeepStateEquals(label + " before LCDC enable",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

        scalar.resetPerformanceBulkCounters();
        candidate.resetPerformanceBulkCounters();
        assertEquals(label + " LCDC-enable boundary frame callback",
                scalar.runTicks(1), candidate.runTicks(1));
        assertEquals(label + " decoded LCDC enable crossed the LCD-off epoch", 0L,
                candidate.getPerformanceEpochTicks());
        assertEquals(label + " LCDC-enable tick was counted as LCD-off epoch", 0L,
                candidate.getPerformanceEpochLcdOffTicks());
        assertTrue(label + " LCDC enable did not take effect", candidate.getGpu().isLcdEnabled());
        assertDeepStateEquals(label + " after scalar LCDC enable",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

        for (Gameboy gameboy : new Gameboy[]{scalar, candidate}) {
            gameboy.getAddressSpace().setByte(0xff0f, 0);
        }
        assertEquals(label + " post-enable STAT/LYC frame callback",
                scalar.runTicks(470), candidate.runTicks(470));
        assertNotEquals(label + " next STAT/LYC event did not publish", 0,
                candidate.getAddressSpace().getByte(0xff0f) & 0x02);
        assertDeepStateEquals(label + " after post-enable STAT/LYC event",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
    }

    private static void assertLcdOffBlankBoundariesMatchScalar(
            Gameboy scalar, Gameboy candidate, String label) {
        advancePairUntilLcdDisabled(scalar, candidate);
        scalar.resetPerformanceBulkCounters();
        candidate.resetPerformanceBulkCounters();

        int beforeInitialBlank = Gameboy.LCD_OFF_BLANK_DELAY - 2;
        assertEquals(label + " LCD-off pre-blank frame callback", 0,
                scalar.runTicks(beforeInitialBlank));
        assertEquals(label + " LCD-off epoch crossed initial blank boundary", 0,
                candidate.runTicks(beforeInitialBlank));
        assertDeepStateEquals(label + " before initial LCD-off blank",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

        assertEquals(label + " scalar initial LCD-off blank", 1, scalar.runTicks(1));
        assertEquals(label + " candidate initial LCD-off blank", 1, candidate.runTicks(1));
        assertDeepStateEquals(label + " after initial LCD-off blank",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

        int beforeRecurringBlank = candidate.getClockSpec().controllerTicksPerFrame() - 1;
        assertEquals(label + " LCD-off pre-refresh frame callback", 0,
                scalar.runTicks(beforeRecurringBlank));
        assertEquals(label + " LCD-off epoch crossed refresh blank boundary", 0,
                candidate.runTicks(beforeRecurringBlank));
        assertDeepStateEquals(label + " before recurring LCD-off blank",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());

        assertEquals(label + " scalar recurring LCD-off blank", 1, scalar.runTicks(1));
        assertEquals(label + " candidate recurring LCD-off blank", 1, candidate.runTicks(1));
        assertTrue(label + " stable LCD-off loop had no epoch coverage",
                candidate.getPerformanceEpochLcdOffTicks() > 50_000L);
        assertEquals(label + " LCD-off VRAM accesses reached the terminal bus", 0L,
                candidate.getCpu().getPerformanceEpochTerminalAccesses());
        assertDeepStateEquals(label + " after recurring LCD-off blank",
                scalar.captureStateWithoutTimeSource(), candidate.captureStateWithoutTimeSource());
    }

    private static void startOneBlockGdma(Gameboy gameboy) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xff51, 0);
        bus.setByte(0xff52, 0);
        bus.setByte(0xff53, 0);
        bus.setByte(0xff54, 0);
        bus.setByte(0xff55, 0);
    }

    /** Record/array-aware equality for the private immutable component-state graph. */
    private static void assertDeepStateEquals(String path, Object expected, Object actual) {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        assertEquals(path + " type", expected.getClass(), actual.getClass());
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(path + " length", length, Array.getLength(actual));
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        Array.get(expected, i), Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof List<?> expectedList) {
            List<?> actualList = (List<?>) actual;
            assertEquals(path + " size", expectedList.size(), actualList.size());
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']',
                        expectedList.get(i), actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            assertEquals(path, expected, actual);
            return;
        }
        try {
            for (RecordComponent component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepStateEquals(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot compare " + path, e);
        }
    }

    private static byte[] validNonColorRom() {
        byte[] image = new byte[0x8000];
        int[] logo = {
                0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
                0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
                0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
                0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
                0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
                0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e,
        };
        for (int index = 0; index < logo.length; index++) {
            image[0x104 + index] = (byte) logo[index];
        }
        image[0x100] = (byte) 0xc3; // JP 0100, keep the post-boot fixture alive
        image[0x101] = 0x00;
        image[0x102] = 0x01;
        image[0x143] = 0x00; // non-color cartridge on a CGB profile
        image[0x147] = 0x00;
        image[0x148] = 0x00;
        image[0x149] = 0x00;
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (image[address] & 0xff) - 1) & 0xff;
        }
        image[0x14d] = (byte) checksum;
        return image;
    }

    private static byte[] validNativeColorRom() {
        byte[] image = validNonColorRom();
        image[0x143] = (byte) 0x80;
        updateHeaderChecksum(image);
        return image;
    }

    private static byte[] nativeColor(byte[] image) {
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] mbc3(byte[] image) {
        image[0x147] = 0x10; // MBC3 + timer + RAM + battery
        image[0x149] = 0x03; // 32 KiB external RAM
        return image;
    }

    private static byte[] nativeMbc3(byte[] image) {
        return nativeColor(mbc3(image));
    }

    private static void updateHeaderChecksum(byte[] image) {
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (image[address] & 0xff) - 1) & 0xff;
        }
        image[0x14d] = (byte) checksum;
    }

    private static byte[] doubleSpeedLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF4D),A
        image[0x103] = 0x4d;
        image[0x104] = 0x10; // STOP + padding
        image[0x105] = 0x00;
        image[0x106] = (byte) 0xc3; // JP 0106
        image[0x107] = 0x06;
        image[0x108] = 0x01;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] nativeDoubleSpeedNr50Loop() {
        byte[] image = new byte[0x8000];
        int pc = 0x100;
        image[pc++] = 0x3e; // LD A,1
        image[pc++] = 0x01;
        image[pc++] = (byte) 0xe0; // LDH (FF4D),A
        image[pc++] = 0x4d;
        image[pc++] = 0x10; // STOP + padding: enter native CGB double speed
        image[pc++] = 0x00;

        image[pc++] = 0x3e; // LD A,80
        image[pc++] = (byte) 0x80;
        image[pc++] = (byte) 0xe0; // LDH (FF26),A: APU on
        image[pc++] = 0x26;
        image[pc++] = 0x3e; // LD A,11
        image[pc++] = 0x11;
        image[pc++] = (byte) 0xe0; // LDH (FF25),A: route CH1 to both outputs
        image[pc++] = 0x25;
        image[pc++] = 0x3e; // LD A,80
        image[pc++] = (byte) 0x80;
        image[pc++] = (byte) 0xe0; // LDH (FF11),A: 50% duty
        image[pc++] = 0x11;
        image[pc++] = 0x3e; // LD A,F0
        image[pc++] = (byte) 0xf0;
        image[pc++] = (byte) 0xe0; // LDH (FF12),A: CH1 DAC/envelope
        image[pc++] = 0x12;
        image[pc++] = 0x3e; // LD A,FF
        image[pc++] = (byte) 0xff;
        image[pc++] = (byte) 0xe0; // LDH (FF13),A
        image[pc++] = 0x13;
        image[pc++] = 0x3e; // LD A,87
        image[pc++] = (byte) 0x87;
        image[pc++] = (byte) 0xe0; // LDH (FF14),A: trigger CH1
        image[pc++] = 0x14;

        int loop = pc;
        image[pc++] = 0x3e; // LD A,77
        image[pc++] = 0x77;
        image[pc++] = (byte) 0xe0; // LDH (FF24),A: maximum output volume
        image[pc++] = 0x24;
        image[pc++] = (byte) 0xee; // XOR 77 => 00
        image[pc++] = 0x77;
        image[pc++] = (byte) 0xe0; // LDH (FF24),A: minimum output volume
        image[pc++] = 0x24;
        image[pc++] = 0x18; // JR loop
        image[pc++] = (byte) (loop - pc);
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] dmgRomWramLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x21; // LD HL,C000
        image[0x101] = 0x00;
        image[0x102] = (byte) 0xc0;
        image[0x103] = 0x7e; // LD A,(HL)
        image[0x104] = 0x3c; // INC A
        image[0x105] = 0x77; // LD (HL),A
        image[0x106] = 0x18; // JR 0103
        image[0x107] = (byte) 0xfb;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] dmgRomSafeDecodedStackLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x31; // LD SP,C100
        image[0x101] = 0x00;
        image[0x102] = (byte) 0xc1;
        image[0x103] = 0x21; // LD HL,C000
        image[0x104] = 0x00;
        image[0x105] = (byte) 0xc0;
        image[0x106] = 0x36; // LD (HL),01
        image[0x107] = 0x01;
        image[0x108] = (byte) 0xcd; // CALL 0110
        image[0x109] = 0x10;
        image[0x10a] = 0x01;
        image[0x10b] = 0x18; // JR 0108
        image[0x10c] = (byte) 0xfb;
        image[0x110] = (byte) 0xc5; // PUSH BC
        image[0x111] = (byte) 0xc1; // POP BC
        image[0x112] = (byte) 0xcb; // BIT 0,(HL)
        image[0x113] = 0x46;
        image[0x114] = (byte) 0xc9; // RET
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] nativeCgbExternalClockWramLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,80
        image[0x101] = (byte) 0x80;
        image[0x102] = (byte) 0xe0; // LDH (FF02),A: active external-clock wait
        image[0x103] = 0x02;
        image[0x104] = 0x21; // LD HL,C000
        image[0x105] = 0x00;
        image[0x106] = (byte) 0xc0;
        image[0x107] = 0x7e; // LD A,(HL)
        image[0x108] = 0x3c; // INC A
        image[0x109] = 0x77; // LD (HL),A
        image[0x10a] = 0x18; // JR 0107
        image[0x10b] = (byte) 0xfb;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] nativeCgbMbc3RtcWriteLoop() {
        byte[] image = new byte[0x8000];
        int pc = 0x100;
        image[pc++] = 0x3e; // LD A,0A
        image[pc++] = 0x0a;
        image[pc++] = (byte) 0xea; // LD (0000),A: enable MBC3 RAM/RTC
        image[pc++] = 0x00;
        image[pc++] = 0x00;
        image[pc++] = 0x3e; // LD A,08
        image[pc++] = 0x08;
        image[pc++] = (byte) 0xea; // LD (4000),A: select RTC seconds
        image[pc++] = 0x00;
        image[pc++] = 0x40;
        int loop = pc;
        image[pc++] = 0x3e; // LD A,2A
        image[pc++] = 0x2a;
        image[pc++] = (byte) 0xea; // LD (A000),A: reset seconds and sub-second phase
        image[pc++] = 0x00;
        image[pc++] = (byte) 0xa0;
        image[pc++] = (byte) 0xaf; // XOR A
        image[pc++] = (byte) 0xea; // LD (6000),A: latch current RTC value
        image[pc++] = 0x00;
        image[pc++] = 0x60;
        image[pc++] = (byte) 0xfa; // LD A,(A000): read latched seconds
        image[pc++] = 0x00;
        image[pc++] = (byte) 0xa0;
        image[pc++] = (byte) 0xea; // LD (C000),A: expose the observed value
        image[pc++] = 0x00;
        image[pc++] = (byte) 0xc0;
        image[pc++] = (byte) 0xc3; // JP loop
        image[pc++] = (byte) (loop & 0xff);
        image[pc] = (byte) (loop >>> 8);
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] nativeCgbLcdOffVramLoop() {
        byte[] image = new byte[0x8000];
        int pc = 0x100;
        image[pc++] = (byte) 0xaf; // XOR A
        image[pc++] = (byte) 0xe0; // LDH (FF40),A: LCD off
        image[pc++] = 0x40;
        image[pc++] = 0x21; // LD HL,8000
        image[pc++] = 0x00;
        image[pc++] = (byte) 0x80;
        int loop = pc;
        image[pc++] = 0x7e; // LD A,(HL)
        image[pc++] = 0x3c; // INC A
        image[pc++] = 0x77; // LD (HL),A
        image[pc++] = 0x18; // JR loop
        image[pc++] = (byte) (loop - pc);
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] physicalDmgLcdOffVramLoop() {
        byte[] image = nativeCgbLcdOffVramLoop();
        image[0x143] = 0;
        return image;
    }

    private static byte[] nativeCgbLcdOffVramThenEnable() {
        byte[] image = new byte[0x8000];
        int pc = 0x100;
        image[pc++] = (byte) 0xaf; // XOR A
        image[pc++] = (byte) 0xe0; // LDH (FF40),A: LCD off
        image[pc++] = 0x40;
        image[pc++] = 0x21; // LD HL,8000
        image[pc++] = 0x00;
        image[pc++] = (byte) 0x80;
        image[pc++] = 0x06; // LD B,00: 256 iterations
        image[pc++] = 0x00;
        int loop = pc;
        image[pc++] = 0x7e; // LD A,(HL)
        image[pc++] = 0x3c; // INC A
        image[pc++] = 0x22; // LD (HL+),A
        image[pc++] = 0x05; // DEC B
        image[pc++] = 0x20; // JR NZ,loop
        image[pc++] = (byte) (loop - pc);
        image[pc++] = 0x3e; // LD A,91
        image[pc++] = (byte) 0x91;
        image[pc++] = (byte) 0xe0; // LDH (FF40),A: LCD on, must stay scalar
        image[pc++] = 0x40;
        image[pc++] = (byte) 0xc3; // JP 0112
        image[pc++] = 0x12;
        image[pc] = 0x01;
        image[0x143] = (byte) 0x80;
        return image;
    }

    private static byte[] physicalDmgLcdOffVramThenEnable() {
        byte[] image = nativeCgbLcdOffVramThenEnable();
        image[0x143] = 0;
        return image;
    }

    private static byte[] cgbCompatibilityIoWriteLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF47),A
        image[0x103] = 0x47;
        image[0x104] = (byte) 0xc3; // JP 0100
        image[0x105] = 0x00;
        image[0x106] = 0x01;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] cgbCompatibilityOamDmaWriteLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF46),A
        image[0x103] = 0x46;
        image[0x104] = (byte) 0xc3; // JP 0100
        image[0x105] = 0x00;
        image[0x106] = 0x01;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] dmgPlainOamWriteLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x21; // LD HL,FE00
        image[0x101] = 0x00;
        image[0x102] = (byte) 0xfe;
        image[0x103] = 0x3e; // LD A,55
        image[0x104] = 0x55;
        image[0x105] = 0x77; // LD (HL),A
        image[0x106] = 0x3c; // INC A
        image[0x107] = 0x18; // JR 0105
        image[0x108] = (byte) 0xfc;
        image[0x143] = 0x00;
        return image;
    }

    private static byte[] dmgPushOamLoop() {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x31; // LD SP,FE08
        image[0x101] = 0x08;
        image[0x102] = (byte) 0xfe;
        image[0x103] = 0x01; // LD BC,1234
        image[0x104] = 0x34;
        image[0x105] = 0x12;
        image[0x106] = (byte) 0xc5; // PUSH BC (three DMG write corruptions)
        image[0x107] = (byte) 0xc3; // JP 0100
        image[0x108] = 0x00;
        image[0x109] = 0x01;
        image[0x143] = 0x00;
        return image;
    }

}
