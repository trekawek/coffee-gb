package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.PHASE_ADDRESS;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.WAIT_PHASE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Differential coverage for the attached-endpoint serial denial scalar lease.
 *
 * <p>Each case compares the PERFORMANCE batching path with the scalar reference and uses
 * endpoint transitions to prove that the denial loop re-reads the one-dot horizon rather than
 * retaining a stale denial.</p>
 */
public final class SerialDeniedScalarLeaseFocusedTest {
    private static final Field LCDC_RETRY = field("performanceLcdcWriteReplayRetry");
    private static final Method SERIAL_DENIED_LEASE = method(
            "tryPerformanceSerialDeniedScalarLease", long.class,
            java.util.function.BooleanSupplier.class, boolean.class);

    @Test
    public void barcodePersistentWaitAndRestoreRemainCanonicalOnBothProfiles() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (BarcodeBoyReadyWaitFixture.Session scalar =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, false);
                 BarcodeBoyReadyWaitFixture.Session candidate =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, true)) {
                BarcodeBoyReadyWaitFixture.requireExternalWait(scalar, 100_000);
                BarcodeBoyReadyWaitFixture.requireExternalWait(candidate, 100_000);
                assertEquivalent(profile + " entry", scalar, candidate);

                ComponentState<Gameboy> scalarCheckpoint =
                        scalar.gameboy.captureStateWithoutTimeSource();
                ComponentState<Gameboy> candidateCheckpoint =
                        candidate.gameboy.captureStateWithoutTimeSource();
                for (int partition : new int[]{1, 7, 54, 55, 113}) {
                    assertEquals(profile + " frame events partition=" + partition,
                            scalar.gameboy.runTicks(partition), candidate.gameboy.runTicks(partition));
                    assertEquivalent(profile + " partition=" + partition, scalar, candidate);
                }

                scalar.gameboy.restoreStateSilently(scalarCheckpoint);
                candidate.gameboy.restoreStateSilently(candidateCheckpoint);
                for (int partition : new int[]{3, 54, 9, 121}) {
                    assertEquals(profile + " restored frame events partition=" + partition,
                            scalar.gameboy.runTicks(partition), candidate.gameboy.runTicks(partition));
                    assertEquivalent(profile + " restored partition=" + partition, scalar, candidate);
                }
            }
        }
    }

    @Test
    public void nativeDeniedEntryExpiresLcdcRetryHintBeforeScalarLease() throws Exception {
        try (BarcodeBoyReadyWaitFixture.Session candidate =
                     BarcodeBoyReadyWaitFixture.Session.open(
                             BarcodeBoyReadyWaitFixture.Profile.CGB_X2, true)) {
            BarcodeBoyReadyWaitFixture.requireExternalWait(candidate, 100_000);
            assertEquals(2, candidate.gameboy.getSpeedMode().getSpeedMode());
            LCDC_RETRY.setBoolean(candidate.gameboy, true);
            candidate.gameboy.runTicks(1);
            assertFalse("serial denial must expire the old LCDC retry hint",
                    LCDC_RETRY.getBoolean(candidate.gameboy));
            assertEquals(WAIT_PHASE,
                    candidate.gameboy.getAddressSpace().getByte(PHASE_ADDRESS));
            assertTrue(candidate.gameboy.isExternalClockTransferActive());
        }
    }

    @Test
    public void persistentWaitPreservesFrameCallbacksAcrossBothClockProfiles() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (BarcodeBoyReadyWaitFixture.Session scalar =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, false);
                 BarcodeBoyReadyWaitFixture.Session candidate =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, true)) {
                BarcodeBoyReadyWaitFixture.requireExternalWait(scalar, 100_000);
                BarcodeBoyReadyWaitFixture.requireExternalWait(candidate, 100_000);
                long scalarFrames = scalar.gameboy.runTicks(140_448);
                long candidateFrames = candidate.gameboy.runTicks(140_448);
                assertTrue(profile + " did not cross a frame boundary", scalarFrames > 0);
                assertEquals(profile + " frame callbacks", scalarFrames, candidateFrames);
                assertEquivalent(profile + " frame continuation", scalar, candidate);
            }
        }
    }

    @Test
    public void stopAwareDeniedLeaseStopsAtSameBoundaryAsScalarReference() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (SwitchingSession scalar = SwitchingSession.open(profile, false, Integer.MAX_VALUE);
                 SwitchingSession candidate = SwitchingSession.open(profile, true, Integer.MAX_VALUE)) {
                requireWait(scalar.gameboy);
                requireWait(candidate.gameboy);
                scalar.endpoint.resetCounters();
                candidate.endpoint.resetCounters();
                assertEquals(profile + " stop-aware ticks", 17,
                        scalar.gameboy.runMeasuredTicksUntilStop(
                                200, () -> scalar.endpoint.scalarTicks >= 17));
                assertEquals(profile + " candidate stop-aware ticks", 17,
                        candidate.gameboy.runMeasuredTicksUntilStop(
                                200, () -> candidate.endpoint.scalarTicks >= 17));
                assertEquivalent(profile + " stop-aware state", scalar.gameboy,
                        candidate.gameboy, scalar.endpoint, candidate.endpoint);
            }
        }
    }

    @Test
    public void attachedDenialLeavesHaltToTheExistingSettledScheduler() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            byte[] image = BarcodeBoyReadyWaitFixture.image(profile);
            image[0x0200] = 0x76; // HALT, reached only after the common external-wait setup.
            try (SwitchingSession scalar = SwitchingSession.open(
                         profile, false, Integer.MAX_VALUE, image.clone());
                 SwitchingSession candidate = SwitchingSession.open(
                         profile, true, Integer.MAX_VALUE, image.clone())) {
                requireWait(scalar.gameboy);
                requireWait(candidate.gameboy);
                scalar.endpoint.resetCounters();
                candidate.endpoint.resetCounters();
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(scalar.gameboy);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(candidate.gameboy);

                int guard = 0;
                while (!(candidate.gameboy.getCpu().getState() == Cpu.State.OPCODE
                        && candidate.gameboy.getCpu().getDebugMachineCycle() == 0)
                        && guard++ < 200_000) {
                    assertEquals(profile + " HALT setup frame callbacks",
                            scalar.gameboy.runTicks(1), candidate.gameboy.runTicks(1));
                }
                assertTrue(profile + " did not reach a HALT fetch boundary", guard < 200_000);
                scalar.gameboy.getCpu().getRegisters().setPC(0x0200);
                candidate.gameboy.getCpu().getRegisters().setPC(0x0200);
                assertEquals(profile + " HALT frame callbacks",
                        scalar.gameboy.runTicks(8), candidate.gameboy.runTicks(8));
                assertEquals(profile + " scalar HALT state pc="
                                + Integer.toHexString(scalar.gameboy.getCpu().getRegisters().getPC())
                                + " opcode=" + Integer.toHexString(scalar.gameboy.getCpu().getDebugOpcode())
                                + " mcycle=" + scalar.gameboy.getCpu().getDebugMachineCycle(),
                        Cpu.State.HALTED, scalar.gameboy.getCpu().getState());
                assertEquals(profile + " candidate HALT state pc="
                                + Integer.toHexString(candidate.gameboy.getCpu().getRegisters().getPC())
                                + " opcode=" + Integer.toHexString(candidate.gameboy.getCpu().getDebugOpcode())
                                + " mcycle=" + candidate.gameboy.getCpu().getDebugMachineCycle(),
                        Cpu.State.HALTED, candidate.gameboy.getCpu().getState());
                assertTrue(profile + " HALT did not receive scalar endpoint callbacks",
                        scalar.endpoint.scalarTicks > 0);
                assertEquivalent(profile + " attached-denial HALT state",
                        scalar.gameboy, candidate.gameboy, scalar.endpoint, candidate.endpoint);
            }
        }
    }

    @Test
    public void reentrantDetachDuringDeniedLeaseReturnsToNullEndpointOwner() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (RebindingSession scalar = RebindingSession.open(profile, false);
                 RebindingSession candidate = RebindingSession.open(profile, true)) {
                requireWait(scalar.gameboy);
                requireWait(candidate.gameboy);
                scalar.endpoint.armDetachAfter(5);
                candidate.endpoint.armDetachAfter(5);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(scalar.gameboy);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(candidate.gameboy);
                assertEquals(profile + " reentrant detach frame callbacks",
                        scalar.gameboy.runTicks(80), candidate.gameboy.runTicks(80));
                assertEquals(profile + " scalar detach callback count", 5,
                        scalar.endpoint.callbackCount);
                assertEquals(profile + " candidate detach callback count", 5,
                        candidate.endpoint.callbackCount);
                assertTrue(profile + " scalar endpoint detached", scalar.endpoint.detached);
                assertTrue(profile + " candidate endpoint detached", candidate.endpoint.detached);
                PerformanceStateAssertions.assertStateEquals(profile + " reentrant detach continuation",
                        scalar.gameboy.captureStateWithoutTimeSource(),
                        candidate.gameboy.captureStateWithoutTimeSource());
                assertEquals(profile + " endpoint probe state",
                        scalar.endpoint.captureProbeState(), candidate.endpoint.captureProbeState());
            }
        }
    }

    @Test
    public void endpointAttachDetachBetweenRunTicksRemainsCanonical() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (SwitchingSession scalar = SwitchingSession.open(profile, false);
                 SwitchingSession candidate = SwitchingSession.open(profile, true)) {
                requireWait(scalar.gameboy);
                requireWait(candidate.gameboy);
                scalar.gameboy.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT);
                candidate.gameboy.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT);
                assertEquals(profile + " detached ordinary frames",
                        scalar.gameboy.runTicks(256), candidate.gameboy.runTicks(256));
                assertEquivalent(profile + " detached ordinary state", scalar, candidate);

                scalar.endpoint.resetCounters();
                candidate.endpoint.resetCounters();
                scalar.gameboy.setSerialEndpoint(scalar.endpoint);
                candidate.gameboy.setSerialEndpoint(candidate.endpoint);
                assertEquals(profile + " attached denial frames",
                        scalar.gameboy.runTicks(80), candidate.gameboy.runTicks(80));
                assertEquivalent(profile + " attached denial state", scalar, candidate);

                scalar.gameboy.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT);
                candidate.gameboy.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT);
                assertEquals(profile + " re-detached ordinary frames",
                        scalar.gameboy.runTicks(256), candidate.gameboy.runTicks(256));
                assertEquivalent(profile + " re-detached ordinary state", scalar, candidate);
            }
        }
    }

    @Test
    public void denialLoopLeavesOnFirstNewPositiveOneDotHorizon() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (SwitchingSession scalar = SwitchingSession.open(profile, false);
                 SwitchingSession candidate = SwitchingSession.open(profile, true)) {
                requireWait(scalar.gameboy);
                requireWait(candidate.gameboy);
                scalar.endpoint.resetCounters();
                candidate.endpoint.resetCounters();
                LCDC_RETRY.setBoolean(scalar.gameboy, false);
                LCDC_RETRY.setBoolean(candidate.gameboy, false);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(scalar.gameboy);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(candidate.gameboy);
                assertEquals(profile + " initial denied callbacks", 0, scalar.endpoint.scalarTicks);
                assertEquals(profile + " initial denied callbacks candidate", 0,
                        candidate.endpoint.scalarTicks);

                ComponentState<Gameboy> scalarCheckpoint =
                        scalar.gameboy.captureStateWithoutTimeSource();
                ComponentState<Gameboy> candidateCheckpoint =
                        candidate.gameboy.captureStateWithoutTimeSource();
                SwitchingEndpoint.State scalarEndpointCheckpoint = scalar.endpoint.captureProbeState();
                SwitchingEndpoint.State candidateEndpointCheckpoint = candidate.endpoint.captureProbeState();

                long packedLease = invokeDeniedLease(candidate.gameboy, 54,
                        profile.doubleSpeed);
                assertEquals(profile + " exact denied lease consumed five ticks", 5L,
                        packedLease & 0xffffffffL);
                long scalarLeaseFrames = scalar.gameboy.runTicks(5);
                assertEquals(profile + " exact denied lease frame callbacks", scalarLeaseFrames,
                        packedLease >>> 32);
                assertEquivalent(profile + " exact first-release scalar parity", scalar.gameboy,
                        candidate.gameboy, scalar.endpoint, candidate.endpoint);
                assertEquals(profile + " exact first-release endpoint state",
                        scalar.endpoint.captureProbeState(), candidate.endpoint.captureProbeState());

                scalar.gameboy.restoreStateSilently(scalarCheckpoint);
                candidate.gameboy.restoreStateSilently(candidateCheckpoint);
                scalar.endpoint.restoreProbeState(scalarEndpointCheckpoint);
                candidate.endpoint.restoreProbeState(candidateEndpointCheckpoint);
                long scalarEpochBefore = scalar.gameboy.getPerformanceEpochTicks();
                long candidateEpochBefore = candidate.gameboy.getPerformanceEpochTicks();
                assertEquals(profile + " transition frame events",
                        scalar.gameboy.runTicks(80), candidate.gameboy.runTicks(80));
                assertEquals(profile + " denial callback count scalar", 5,
                        scalar.endpoint.scalarTicks);
                assertEquals(profile + " candidate left denial at first positive horizon", 5,
                        candidate.endpoint.scalarTicks);
                assertTrue(profile + " scalar endpoint became quiet", scalar.endpoint.quiet);
                assertTrue(profile + " candidate endpoint became quiet", candidate.endpoint.quiet);
                assertEquals(profile + " scalar reference stayed scalar", scalarEpochBefore,
                        scalar.gameboy.getPerformanceEpochTicks());
                assertTrue(profile + " candidate resumed positive epoch proof after release",
                        candidate.gameboy.getPerformanceEpochTicks() > candidateEpochBefore);
                assertEquivalent(profile + " post-transition state", scalar.gameboy,
                        candidate.gameboy, scalar.endpoint, candidate.endpoint);
                assertEquals(profile + " post-transition endpoint state",
                        scalar.endpoint.captureProbeState(), candidate.endpoint.captureProbeState());

                scalar.gameboy.restoreStateSilently(scalarCheckpoint);
                candidate.gameboy.restoreStateSilently(candidateCheckpoint);
                scalar.endpoint.restoreProbeState(scalarEndpointCheckpoint);
                candidate.endpoint.restoreProbeState(candidateEndpointCheckpoint);
                assertEquals(profile + " restored transition frame events",
                        scalar.gameboy.runTicks(80), candidate.gameboy.runTicks(80));
                assertEquivalent(profile + " restored transition state", scalar.gameboy,
                        candidate.gameboy, scalar.endpoint, candidate.endpoint);
                assertEquals(profile + " restored endpoint state",
                        scalar.endpoint.captureProbeState(), candidate.endpoint.captureProbeState());
            }
        }
    }

    private static void requireWait(Gameboy gameboy) {
        long elapsed = 0;
        while (elapsed < 100_000
                && (gameboy.getAddressSpace().getByte(PHASE_ADDRESS) != WAIT_PHASE
                || !gameboy.isExternalClockTransferActive())) {
            long step = Math.min(2_048, 100_000 - elapsed);
            gameboy.runTicks(step);
            elapsed += step;
        }
        assertEquals(WAIT_PHASE, gameboy.getAddressSpace().getByte(PHASE_ADDRESS));
        assertTrue(gameboy.isExternalClockTransferActive());
    }

    private static void assertEquivalent(String label,
                                         BarcodeBoyReadyWaitFixture.Session scalar,
                                         BarcodeBoyReadyWaitFixture.Session candidate) {
        assertEquivalent(label, scalar.gameboy, candidate.gameboy,
                scalar.endpoint, candidate.endpoint);
    }

    private static void assertEquivalent(String label,
                                         SwitchingSession scalar,
                                         SwitchingSession candidate) {
        assertEquivalent(label, scalar.gameboy, candidate.gameboy,
                scalar.endpoint, candidate.endpoint);
    }

    private static void assertEquivalent(String label,
                                         Gameboy scalar, Gameboy candidate,
                                         BarcodeBoySerialEndpoint scalarEndpoint,
                                         BarcodeBoySerialEndpoint candidateEndpoint) {
        PerformanceStateAssertions.assertStateEquals(label,
                scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
        PerformanceStateAssertions.assertStateEquals(label + " endpoint",
                scalarEndpoint.captureState(), candidateEndpoint.captureState());
    }

    private static Field field(String name) {
        try {
            Field field = Gameboy.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Method method = Gameboy.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long invokeDeniedLease(Gameboy gameboy, long remaining,
                                          boolean nativeCgbOwner) throws Exception {
        return (Long) SERIAL_DENIED_LEASE.invoke(gameboy, remaining, null, nativeCgbOwner);
    }

    private static final class RebindingSession implements AutoCloseable {
        private final EventBusImpl eventBus;
        private final Gameboy gameboy;
        private final RebindingEndpoint endpoint;

        private RebindingSession(EventBusImpl eventBus, Gameboy gameboy,
                                 RebindingEndpoint endpoint) {
            this.eventBus = eventBus;
            this.gameboy = gameboy;
            this.endpoint = endpoint;
        }

        private static RebindingSession open(BarcodeBoyReadyWaitFixture.Profile profile,
                                             boolean batching) throws IOException {
            RebindingEndpoint endpoint = new RebindingEndpoint();
            Gameboy gameboy = new Gameboy.GameboyConfiguration(
                    new Rom(BarcodeBoyReadyWaitFixture.image(profile)))
                    .setHardwareProfile(profile.hardware)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setPlayerInputSource(new PlayerInputHub())
                    .setSupportBatterySave(false)
                    .setRtcTimeSource(() -> 0L)
                    .build();
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            gameboy.init(eventBus, endpoint, null);
            endpoint.owner = gameboy;
            if (!batching) {
                gameboy.setPerformanceBatchingEnabled(false);
            }
            return new RebindingSession(eventBus, gameboy, endpoint);
        }

        @Override
        public void close() {
            try {
                endpoint.disconnect();
            } finally {
                try {
                    gameboy.closeSilently();
                } finally {
                    eventBus.close();
                }
            }
        }
    }

    private static final class RebindingEndpoint extends BarcodeBoySerialEndpoint {
        private Gameboy owner;
        private int detachAfter = Integer.MAX_VALUE;
        private int callbackCount;
        private boolean detached;

        private void armDetachAfter(int callbacks) {
            callbackCount = 0;
            detached = false;
            detachAfter = callbacks;
        }

        @Override
        public void tick() {
            callbackCount++;
            if (!detached && callbackCount >= detachAfter) {
                detached = true;
                owner.setSerialEndpoint(SerialEndpoint.NULL_ENDPOINT);
            }
            super.tick();
        }

        private record ProbeState(int callbackCount, boolean detached) {
        }

        private ProbeState captureProbeState() {
            return new ProbeState(callbackCount, detached);
        }
    }

    private static final class SwitchingSession implements AutoCloseable {
        private final EventBusImpl eventBus;
        private final Gameboy gameboy;
        private final SwitchingEndpoint endpoint;

        private SwitchingSession(EventBusImpl eventBus, Gameboy gameboy,
                                 SwitchingEndpoint endpoint) {
            this.eventBus = eventBus;
            this.gameboy = gameboy;
            this.endpoint = endpoint;
        }

        private static SwitchingSession open(BarcodeBoyReadyWaitFixture.Profile profile,
                                             boolean batching) throws IOException {
            return open(profile, batching, 5);
        }

        private static SwitchingSession open(BarcodeBoyReadyWaitFixture.Profile profile,
                                             boolean batching, int releaseAfter) throws IOException {
            return open(profile, batching, releaseAfter, BarcodeBoyReadyWaitFixture.image(profile));
        }

        private static SwitchingSession open(BarcodeBoyReadyWaitFixture.Profile profile,
                                             boolean batching, int releaseAfter,
                                             byte[] image) throws IOException {
            SwitchingEndpoint endpoint = new SwitchingEndpoint(releaseAfter);
            Gameboy gameboy = new Gameboy.GameboyConfiguration(
                    new Rom(image))
                    .setHardwareProfile(profile.hardware)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setPlayerInputSource(new PlayerInputHub())
                    .setSupportBatterySave(false)
                    .setRtcTimeSource(() -> 0L)
                    .build();
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            gameboy.init(eventBus, endpoint, null);
            if (!batching) {
                gameboy.setPerformanceBatchingEnabled(false);
            }
            return new SwitchingSession(eventBus, gameboy, endpoint);
        }

        @Override
        public void close() {
            try {
                endpoint.disconnect();
            } finally {
                try {
                    gameboy.closeSilently();
                } finally {
                    eventBus.close();
                }
            }
        }
    }

    private static final class SwitchingEndpoint extends BarcodeBoySerialEndpoint {
        private final int releaseAfter;
        private int scalarTicks;
        private boolean quiet;

        private SwitchingEndpoint(int releaseAfter) {
            this.releaseAfter = releaseAfter;
        }

        @Override
        public int performanceQuietSpanLimit(int requested) {
            return quiet ? Math.max(0, requested) : 0;
        }

        @Override
        public int performanceExternalClockWaitSpanLimit(int requested) {
            return quiet ? Math.max(0, requested) : 0;
        }

        private record State(int scalarTicks, boolean quiet) {
        }

        private State captureProbeState() {
            return new State(scalarTicks, quiet);
        }

        private void restoreProbeState(State state) {
            scalarTicks = state.scalarTicks();
            quiet = state.quiet();
        }

        private void resetCounters() {
            scalarTicks = 0;
            quiet = false;
        }

        @Override
        public void tick() {
            scalarTicks++;
            if (scalarTicks >= releaseAfter) {
                quiet = true;
            }
            super.tick();
        }
    }
}
