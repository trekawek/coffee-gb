package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.BackendRequest;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.CompletionResult;
import static eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterBackendPort.OfferResult;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MobileAdapterEngineTest {

    private static final int DEVICE_ID = 0x08;

    private static final byte[] BEGIN = packet(
            0x10, "NINTENDO".getBytes(StandardCharsets.US_ASCII));

    @Test
    public void exactTimeoutBoundaryUsesClockPhaseUnitsForIntegerAndRationalClocks() {
        MobileAdapterEngine legacy = engine(ClockSpec.LEGACY);
        legacy.acceptByte(0x99);
        long legacyBoundary = ClockSpec.LEGACY.ticksForMilliseconds(
                MobileAdapterEngine.IDLE_TIMEOUT_MILLIS, ClockSpec.Rounding.FLOOR);
        MobileAdapterEngine.EngineResult exact = legacy.advanceTicks(legacyBoundary);
        assertEquals(MobileAdapterEngine.Outcome.IDLE_BOUNDARY_WAIT, exact.outcome());
        assertEquals(1, exact.retainedBytes());
        MobileAdapterEngine.EngineResult expired = legacy.advanceTicks(1);
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET, expired.outcome());
        assertEquals(MobileAdapterEngine.Phase.SLEEP, expired.phase());
        assertEquals(0, expired.retainedBytes());

        MobileAdapterEngine rational = engine(ClockSpec.SGB);
        rational.acceptByte(0x99);
        long rationalFloor = ClockSpec.SGB.ticksForMilliseconds(
                MobileAdapterEngine.IDLE_TIMEOUT_MILLIS, ClockSpec.Rounding.FLOOR);
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE,
                rational.advanceTicks(rationalFloor).outcome());
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                rational.advanceTicks(1).outcome());

        MobileAdapterEngine hugeAdvance = engine(ClockSpec.SGB2);
        hugeAdvance.acceptByte(0x99);
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                hugeAdvance.advanceTicks(Long.MAX_VALUE).outcome());
    }

    @Test
    public void negativeTimeIsTransientAndCannotCorruptCapturedState() {
        MobileAdapterEngine engine = engine(ClockSpec.LEGACY);
        feed(engine, Arrays.copyOf(BEGIN, 5));
        MobileAdapterEngine.MobileAdapterEngineState before = state(engine);

        MobileAdapterEngine.EngineResult regression = engine.advanceTicks(-1);
        assertEquals(MobileAdapterEngine.Outcome.TIME_REGRESSION, regression.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.TIME_REGRESSION, regression.error());
        assertEngineStatesEqual(before, state(engine));
    }

    @Test
    public void masterClockTickPathIsVoidAndMatchesBulkAdvanceWithoutMaterializingResults()
            throws Exception {
        assertEquals(Void.TYPE, MobileAdapterEngine.class.getMethod("tick").getReturnType());
        MobileAdapterEngine perTick = engine(ClockSpec.LEGACY);
        MobileAdapterEngine bulk = engine(ClockSpec.LEGACY);
        feed(perTick, Arrays.copyOf(BEGIN, 6));
        feed(bulk, Arrays.copyOf(BEGIN, 6));

        for (int tick = 0; tick < 10_000; tick++) {
            perTick.tick();
        }
        bulk.advanceTicks(10_000);

        assertEngineStatesEqual(state(bulk), state(perTick));
    }

    @Test
    public void stateRoundTripContinuesPartialPacketsAndDefensivelyOwnsEveryArray() {
        byte[] sourceConfiguration = configuration();
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine original = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, sourceConfiguration, backend);
        sourceConfiguration[0] = (byte) 0xee;
        feed(original, Arrays.copyOf(BEGIN, 6));
        assertTrue(original.reservePendingPacketSlot());
        assertTrue(original.reservePendingPacketSlot());
        original.advanceTicks(42);

        MobileAdapterEngine.MobileAdapterEngineState captured = state(original);
        assertEquals(16, captured.expectedPacketBytes());
        assertEquals(6, captured.packetCount());
        assertEquals(2, captured.pendingPacketSlots());
        byte[] exposedPacket = captured.packetBuffer();
        byte[] exposedConfiguration = captured.configuration();
        exposedPacket[0] = 0;
        exposedConfiguration[0] = 0;
        assertEquals(0x99, captured.packetBuffer()[0] & 0xff);
        assertEquals(0x4d, captured.configuration()[0] & 0xff);
        assertEquals(0x4d, original.configurationCopy()[0] & 0xff);

        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(1, 0x12, new byte[]{1})));
        MobileAdapterEngine restored = new MobileAdapterEngine(
                ClockSpec.LEGACY, 0x01, new byte[256], backend);
        restored.restoreState(captured);
        assertEquals(0, backend.occupiedRequestSlots());
        assertEquals(0, backend.bufferedBytes());

        MobileAdapterEngine.EngineResult originalResult = feed(
                original, Arrays.copyOfRange(BEGIN, 6, BEGIN.length));
        MobileAdapterEngine.EngineResult restoredResult = feed(
                restored, Arrays.copyOfRange(BEGIN, 6, BEGIN.length));
        assertResultsEqual(originalResult, restoredResult);
        assertEquals(MobileAdapterEngine.Outcome.SESSION_STARTED, restoredResult.outcome());
        assertEquals(2, restoredResult.pendingPacketSlots());

        byte[] visibleResponse = restoredResult.responsePacket();
        byte[] visibleAck = restoredResult.acknowledgement();
        visibleResponse[0] = 0;
        visibleAck[0] = 0;
        assertEquals(0x99, restored.snapshot().responsePacket()[0] & 0xff);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x90},
                restored.snapshot().acknowledgement());

        MobileAdapterEngine responseRestored = engine(ClockSpec.LEGACY);
        responseRestored.restoreState(restored.captureState());
        assertResultsEqual(restored.snapshot(), responseRestored.snapshot());
    }

    @Test
    public void pendingLimitRoundTripsForDirectReservationAndBackendSubmission() {
        MobileAdapterEngine direct = engine(ClockSpec.LEGACY);
        assertTrue(direct.reservePendingPacketSlot());
        assertTrue(direct.reservePendingPacketSlot());
        assertFalse(direct.reservePendingPacketSlot());
        assertEquals(0, direct.snapshot().acknowledgement().length);
        MobileAdapterEngine.MobileAdapterEngineState directState = state(direct);
        MobileAdapterEngine directRestored = engine(ClockSpec.LEGACY);
        directRestored.restoreState(directState);
        assertEngineStatesEqual(directState, state(directRestored));

        MobileAdapterEngine submitted = engine(ClockSpec.LEGACY);
        feed(submitted, BEGIN);
        assertTrue(submitted.reservePendingPacketSlot());
        assertTrue(submitted.reservePendingPacketSlot());
        MobileAdapterEngine.EngineResult rejected = feed(
                submitted,
                packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(MobileAdapterEngine.Outcome.PENDING_LIMIT, rejected.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.PENDING_LIMIT, rejected.error());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xf2},
                rejected.acknowledgement());
        assertEquals(0, rejected.responsePacket().length);
        assertEquals(2, rejected.pendingPacketSlots());

        MobileAdapterEngine.MobileAdapterEngineState submittedState = state(submitted);
        MobileAdapterEngine submittedRestored = engine(ClockSpec.LEGACY);
        submittedRestored.restoreState(submittedState);
        assertEngineStatesEqual(submittedState, state(submittedRestored));
    }

    @Test
    public void additiveNetworkStateRejectsAFalseExternalIoMarker() {
        MobileAdapterEngine source = engine(ClockSpec.LEGACY);
        feed(source, BEGIN);
        MobileAdapterEngine.MobileAdapterEngineState legacy = state(source);
        MobileAdapterEngine.MobileAdapterEngineNetworkState nonCanonical =
                new MobileAdapterEngine.MobileAdapterEngineNetworkState(
                        legacy.phaseId(),
                        legacy.outcomeId(),
                        legacy.errorId(),
                        legacy.deviceId(),
                        legacy.packetBuffer(),
                        legacy.packetCount(),
                        legacy.expectedPacketBytes(),
                        legacy.configuration(),
                        legacy.responsePacket(),
                        legacy.acknowledgement(),
                        legacy.idlePhaseUnits(),
                        legacy.serialByteObserved(),
                        legacy.pendingPacketSlots(),
                        false);

        assertThrows(IllegalArgumentException.class,
                () -> engine(ClockSpec.LEGACY).restoreState(nonCanonical));
    }

    @Test
    public void everyPersistableOutcomeRoundTripsAndTransientRegressionDoesNot() {
        Set<MobileAdapterEngine.Outcome> restoredOutcomes =
                EnumSet.noneOf(MobileAdapterEngine.Outcome.class);

        MobileAdapterEngine needMore = engine(ClockSpec.LEGACY);
        needMore.acceptByte(0x99);
        roundTripOutcome(needMore, MobileAdapterEngine.Outcome.NEED_MORE, restoredOutcomes);

        MobileAdapterEngine started = engine(ClockSpec.LEGACY);
        feed(started, BEGIN);
        roundTripOutcome(started, MobileAdapterEngine.Outcome.SESSION_STARTED, restoredOutcomes);

        MobileAdapterEngine ended = engine(ClockSpec.LEGACY);
        feed(ended, packet(0x11, new byte[0]));
        roundTripOutcome(ended, MobileAdapterEngine.Outcome.SESSION_ENDED, restoredOutcomes);

        MobileAdapterEngine reset = engine(ClockSpec.LEGACY);
        feed(reset, packet(0x16, new byte[0]));
        roundTripOutcome(reset, MobileAdapterEngine.Outcome.SESSION_RESET, restoredOutcomes);

        MobileAdapterEngine checksum = engine(ClockSpec.LEGACY);
        byte[] badChecksum = BEGIN.clone();
        badChecksum[badChecksum.length - 1] ^= 1;
        feed(checksum, badChecksum);
        roundTripOutcome(checksum, MobileAdapterEngine.Outcome.CHECKSUM_ERROR, restoredOutcomes);

        long idleBoundary = ClockSpec.LEGACY.ticksForMilliseconds(
                MobileAdapterEngine.IDLE_TIMEOUT_MILLIS, ClockSpec.Rounding.FLOOR);
        MobileAdapterEngine timedOut = engine(ClockSpec.LEGACY);
        timedOut.acceptByte(0x99);
        timedOut.advanceTicks(idleBoundary);
        timedOut.advanceTicks(1);
        roundTripOutcome(timedOut, MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                restoredOutcomes);

        MobileAdapterEngine boundaryWait = engine(ClockSpec.LEGACY);
        boundaryWait.acceptByte(0x99);
        boundaryWait.advanceTicks(idleBoundary);
        roundTripOutcome(boundaryWait, MobileAdapterEngine.Outcome.IDLE_BOUNDARY_WAIT,
                restoredOutcomes);

        MobileAdapterEngine configRead = engine(ClockSpec.LEGACY);
        feed(configRead, packet(0x19, new byte[]{0, 1}));
        roundTripOutcome(configRead, MobileAdapterEngine.Outcome.CONFIG_READ, restoredOutcomes);

        MobileAdapterEngine configBoundary = engine(ClockSpec.LEGACY);
        feed(configBoundary, packet(0x19, new byte[]{(byte) 128, (byte) 128}));
        roundTripOutcome(configBoundary, MobileAdapterEngine.Outcome.CONFIG_READ_BOUNDARY,
                restoredOutcomes);

        MobileAdapterEngine configWrite = engine(ClockSpec.LEGACY);
        feed(configWrite, packet(0x1a, new byte[]{0, 0x55}));
        roundTripOutcome(configWrite, MobileAdapterEngine.Outcome.CONFIG_WRITE,
                restoredOutcomes);

        MobileAdapterEngine dialled = engine(ClockSpec.LEGACY);
        feed(dialled, BEGIN);
        feed(dialled, packet(0x12, ispDialData()));
        roundTripOutcome(dialled, MobileAdapterEngine.Outcome.TELEPHONE_DIALLED,
                restoredOutcomes);

        MobileAdapterEngine hungUp = engine(ClockSpec.LEGACY);
        feed(hungUp, BEGIN);
        feed(hungUp, packet(0x12, ispDialData()));
        feed(hungUp, packet(0x13, new byte[0]));
        roundTripOutcome(hungUp, MobileAdapterEngine.Outcome.TELEPHONE_HUNG_UP,
                restoredOutcomes);

        MobileAdapterEngine telephoneStatus = engine(ClockSpec.LEGACY);
        feed(telephoneStatus, BEGIN);
        feed(telephoneStatus, packet(0x17, new byte[0]));
        roundTripOutcome(telephoneStatus, MobileAdapterEngine.Outcome.TELEPHONE_STATUS,
                restoredOutcomes);

        MobileAdapterEngine loggedIn = engine(ClockSpec.LEGACY);
        feed(loggedIn, BEGIN);
        feed(loggedIn, packet(0x12, ispDialData()));
        feed(loggedIn, packet(0x21, ispLoginData()));
        roundTripOutcome(loggedIn, MobileAdapterEngine.Outcome.ISP_LOGGED_IN,
                restoredOutcomes);

        MobileAdapterEngine loggedOut = engine(ClockSpec.LEGACY);
        feed(loggedOut, BEGIN);
        feed(loggedOut, packet(0x12, ispDialData()));
        feed(loggedOut, packet(0x21, ispLoginData()));
        feed(loggedOut, packet(0x22, new byte[0]));
        roundTripOutcome(loggedOut, MobileAdapterEngine.Outcome.ISP_LOGGED_OUT,
                restoredOutcomes);

        MobileAdapterEngine serviceError = engine(ClockSpec.LEGACY);
        feed(serviceError, BEGIN);
        feed(serviceError, packet(0x12, new byte[]{0, '1'}));
        roundTripOutcome(serviceError, MobileAdapterEngine.Outcome.SERVICE_ERROR,
                restoredOutcomes);

        MobileAdapterEngine unsupported = engine(ClockSpec.LEGACY);
        feed(unsupported, packet(0x7e, new byte[0]));
        roundTripOutcome(unsupported, MobileAdapterEngine.Outcome.UNSUPPORTED_COMMAND,
                restoredOutcomes);

        MobileAdapterEngine magic = engine(ClockSpec.LEGACY);
        feed(magic, new byte[]{(byte) 0x99, 0x65});
        roundTripOutcome(magic, MobileAdapterEngine.Outcome.MAGIC_ERROR, restoredOutcomes);

        MobileAdapterEngine reserved = engine(ClockSpec.LEGACY);
        feed(reserved, new byte[]{(byte) 0x99, 0x66, 0x10, 1});
        roundTripOutcome(reserved, MobileAdapterEngine.Outcome.RESERVED_ERROR, restoredOutcomes);

        MobileAdapterEngine length = engine(ClockSpec.LEGACY);
        feed(length, new byte[]{(byte) 0x99, 0x66, 0x7e, 0, 0, (byte) 0xff});
        roundTripOutcome(length, MobileAdapterEngine.Outcome.LENGTH_LIMIT, restoredOutcomes);

        // Exact framing prevents production input from overflowing the 262-byte parser, but this
        // append-only persisted terminal code must remain decodable.
        MobileAdapterEngine.MobileAdapterEngineState rejected = state(magic);
        MobileAdapterEngine.MobileAdapterEngineState bufferLimit =
                new MobileAdapterEngine.MobileAdapterEngineState(
                        rejected.phaseId(),
                        MobileAdapterEngine.Outcome.BUFFER_LIMIT.id(),
                        MobileAdapterEngine.ErrorCode.BUFFER_LIMIT.id(),
                        rejected.deviceId(),
                        rejected.packetBuffer(),
                        rejected.packetCount(),
                        rejected.expectedPacketBytes(),
                        rejected.configuration(),
                        rejected.responsePacket(),
                        rejected.acknowledgement(),
                        rejected.idlePhaseUnits(),
                        rejected.serialByteObserved(),
                        rejected.pendingPacketSlots());
        roundTripOutcome(bufferLimit, MobileAdapterEngine.Outcome.BUFFER_LIMIT, restoredOutcomes);

        MobileAdapterEngine cancelled = engine(ClockSpec.LEGACY);
        cancelled.cancelOrReplace();
        roundTripOutcome(cancelled, MobileAdapterEngine.Outcome.CANCELLED, restoredOutcomes);

        MobileAdapterEngine pendingLimit = engine(ClockSpec.LEGACY);
        assertTrue(pendingLimit.reservePendingPacketSlot());
        assertTrue(pendingLimit.reservePendingPacketSlot());
        assertFalse(pendingLimit.reservePendingPacketSlot());
        roundTripOutcome(pendingLimit, MobileAdapterEngine.Outcome.PENDING_LIMIT,
                restoredOutcomes);

        MobileAdapterEngine unavailable = engine(ClockSpec.LEGACY);
        feed(unavailable, BEGIN);
        feed(unavailable, packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII)));
        roundTripOutcome(unavailable, MobileAdapterEngine.Outcome.BACKEND_ERROR,
                restoredOutcomes);

        DeterministicMobileAdapterBackend networkBackend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine dns = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), networkBackend);
        feed(dns, BEGIN);
        feed(dns, packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(CompletionResult.COMPLETED,
                networkBackend.complete(networkBackend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{(byte) 192, 0, 2, 1}));
        dns.pollBackendCompletion();
        roundTripOutcome(dns, MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                restoredOutcomes);

        DeterministicMobileAdapterBackend closedBackend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine remoteClosed = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), closedBackend);
        feed(remoteClosed, BEGIN);
        feed(remoteClosed, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(CompletionResult.COMPLETED,
                closedBackend.complete(closedBackend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{0}));
        remoteClosed.pollBackendCompletion();
        feed(remoteClosed, packet(0x15, new byte[]{0}));
        assertEquals(CompletionResult.COMPLETED,
                closedBackend.complete(closedBackend.generation(), 1,
                        MobileAdapterBackendPort.BackendStatus.REMOTE_CLOSED, new byte[0]));
        remoteClosed.pollBackendCompletion();
        roundTripOutcome(remoteClosed, MobileAdapterEngine.Outcome.BACKEND_REMOTE_CLOSED,
                restoredOutcomes);

        DeterministicMobileAdapterBackend liveBackend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine live = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), liveBackend);
        feed(live, BEGIN);
        feed(live, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        MobileAdapterEngine.MobileAdapterEngineNetworkState external = networkState(live);
        assertTrue(external.externalIoAtCapture());
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED.id(),
                external.outcomeId());
        MobileAdapterEngine restoredExternal = engine(ClockSpec.LEGACY);
        restoredExternal.restoreState(external);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restoredExternal.snapshot().outcome());
        assertFalse(restoredExternal.hasExternalIo());
        restoredOutcomes.add(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED);

        assertEquals(
                EnumSet.complementOf(EnumSet.of(
                        MobileAdapterEngine.Outcome.TIME_REGRESSION,
                        MobileAdapterEngine.Outcome.BACKEND_PENDING)),
                restoredOutcomes);
        MobileAdapterEngine.MobileAdapterEngineState partial = state(needMore);
        MobileAdapterEngine.MobileAdapterEngineState transientRegression =
                new MobileAdapterEngine.MobileAdapterEngineState(
                        partial.phaseId(),
                        MobileAdapterEngine.Outcome.TIME_REGRESSION.id(),
                        MobileAdapterEngine.ErrorCode.NONE.id(),
                        partial.deviceId(),
                        partial.packetBuffer(),
                        partial.packetCount(),
                        partial.expectedPacketBytes(),
                        partial.configuration(),
                        partial.responsePacket(),
                        partial.acknowledgement(),
                        partial.idlePhaseUnits(),
                        partial.serialByteObserved(),
                        partial.pendingPacketSlots());
        assertThrows(IllegalArgumentException.class,
                () -> engine(ClockSpec.LEGACY).restoreState(transientRegression));
    }

    @Test
    public void malformedRestoreIsRejectedBeforeMutatingLiveStateOrBackendOwnership() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine live = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(live, Arrays.copyOf(BEGIN, 6));
        MobileAdapterEngine.MobileAdapterEngineState baseline = state(live);
        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(7, 0x12, new byte[]{1, 2, 3})));

        MobileAdapterEngine.MobileAdapterEngineState wrongError = copyState(
                baseline,
                baseline.packetBuffer(),
                baseline.responsePacket(),
                MobileAdapterEngine.ErrorCode.CHECKSUM.id());
        assertThrows(IllegalArgumentException.class, () -> live.restoreState(wrongError));
        assertEngineStatesEqual(baseline, state(live));
        assertEquals(1, backend.occupiedRequestSlots());

        byte[] staleParser = baseline.packetBuffer();
        staleParser[100] = 1;
        MobileAdapterEngine.MobileAdapterEngineState stale = copyState(
                baseline, staleParser, baseline.responsePacket(), baseline.errorId());
        assertThrows(IllegalArgumentException.class, () -> live.restoreState(stale));
        assertEngineStatesEqual(baseline, state(live));

        MobileAdapterEngine.MobileAdapterEngineState ownerlessPartial = copyTimingState(
                baseline, baseline.idlePhaseUnits(), false);
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(ownerlessPartial));
        assertEngineStatesEqual(baseline, state(live));
        assertEquals(1, backend.occupiedRequestSlots());

        MobileAdapterEngine successful = engine(ClockSpec.LEGACY);
        feed(successful, BEGIN);
        MobileAdapterEngine.MobileAdapterEngineState responseState = state(successful);
        byte[] brokenResponse = responseState.responsePacket();
        brokenResponse[brokenResponse.length - 1] ^= 1;
        MobileAdapterEngine.MobileAdapterEngineState badResponse = copyState(
                responseState,
                responseState.packetBuffer(),
                brokenResponse,
                responseState.errorId());
        assertThrows(IllegalArgumentException.class, () -> live.restoreState(badResponse));
        assertEngineStatesEqual(baseline, state(live));

        MobileAdapterEngine backendError = engine(ClockSpec.LEGACY);
        feed(backendError, BEGIN);
        assertBackendError(feed(backendError,
                packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII))), 0x28, 0x01);
        MobileAdapterEngine.MobileAdapterEngineState backendErrorState = state(backendError);
        MobileAdapterEngine.MobileAdapterEngineState wrongBackendErrorCommand = copyState(
                backendErrorState,
                backendErrorState.packetBuffer(),
                packet(0x7f, new byte[]{0x28, 0x01}),
                backendErrorState.errorId());
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(wrongBackendErrorCommand));
        MobileAdapterEngine.MobileAdapterEngineState responseBearingBusy = copyState(
                backendErrorState,
                backendErrorState.packetBuffer(),
                backendErrorState.responsePacket(),
                MobileAdapterEngine.ErrorCode.BACKEND_BUSY.id());
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(responseBearingBusy));
        MobileAdapterEngine.MobileAdapterEngineState invalidResponseWithUseError = copyState(
                backendErrorState,
                backendErrorState.packetBuffer(),
                backendErrorState.responsePacket(),
                MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID.id());
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(invalidResponseWithUseError));
        assertEngineStatesEqual(baseline, state(live));
        assertEquals(1, backend.occupiedRequestSlots());

        MobileAdapterEngine loggedIn = engine(ClockSpec.LEGACY);
        feed(loggedIn, BEGIN);
        feed(loggedIn, packet(0x12, ispDialData()));
        feed(loggedIn, packet(0x21, ispLoginData()));
        MobileAdapterEngine.MobileAdapterEngineState loggedInState = state(loggedIn);
        MobileAdapterEngine.MobileAdapterEngineState nonCanonicalLoginResponse = copyState(
                loggedInState,
                loggedInState.packetBuffer(),
                packet(0xa1, new byte[]{127, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0}),
                loggedInState.errorId());
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(nonCanonicalLoginResponse));
        assertEngineStatesEqual(baseline, state(live));

        MobileAdapterEngine wrongNumber = engine(ClockSpec.LEGACY);
        feed(wrongNumber, BEGIN);
        feed(wrongNumber, packet(0x12, new byte[]{0, '1'}));
        MobileAdapterEngine.MobileAdapterEngineState dialValueError = state(wrongNumber);
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(copyPhaseState(dialValueError, 3)));
        MobileAdapterEngine asleepDial = engine(ClockSpec.LEGACY);
        feed(asleepDial, packet(0x12, ispDialData()));
        MobileAdapterEngine.MobileAdapterEngineState dialPhaseError = state(asleepDial);
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(copyPhaseState(dialPhaseError, 2)));
        assertEngineStatesEqual(baseline, state(live));

        MobileAdapterEngine.MobileAdapterEngineState ownerlessResult = copyTimingState(
                responseState, 0, false);
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(ownerlessResult));

        assertTrue(successful.reservePendingPacketSlot());
        assertTrue(successful.reservePendingPacketSlot());
        assertFalse(successful.reservePendingPacketSlot());
        successful.completePendingPacketSlot();
        MobileAdapterEngine.MobileAdapterEngineState ownerlessSession = copyTimingState(
                state(successful), 0, false);
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE.id(),
                ownerlessSession.outcomeId());
        assertEquals(0, ownerlessSession.packetCount());
        assertThrows(IllegalArgumentException.class,
                () -> live.restoreState(ownerlessSession));

        MobileAdapterEngine rational = engine(ClockSpec.SGB);
        feed(rational, Arrays.copyOf(BEGIN, 6));
        MobileAdapterEngine.MobileAdapterEngineState unaligned = copyTimingState(
                state(rational), 1, true);
        assertThrows(IllegalArgumentException.class,
                () -> rational.restoreState(unaligned));
    }

    @Test
    public void supportedCommandsAndConservativeFailuresRespectEveryFrozenBoundary() {
        byte[] configuration = configuration();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration);

        MobileAdapterEngine.EngineResult boundary = feed(
                engine, packet(0x19, new byte[]{(byte) 128, (byte) 128}));
        assertEquals(MobileAdapterEngine.Outcome.CONFIG_READ_BOUNDARY, boundary.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.NONE, boundary.error());
        assertEquals(137, boundary.responsePacket().length);
        assertEquals(128, boundary.responsePacket()[6] & 0xff);
        for (int i = 0; i < 128; i++) {
            assertEquals(i, boundary.responsePacket()[7 + i] & 0xff);
        }
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x99},
                boundary.acknowledgement());
        assertPacketChecksum(boundary.responsePacket());

        MobileAdapterEngine.EngineResult write = feed(
                engine, packet(0x1a, new byte[]{0, 1, 0x55}));
        assertEquals(MobileAdapterEngine.Outcome.CONFIG_WRITE, write.outcome());
        assertEquals(1, engine.configurationCopy()[0]);
        assertEquals(0x55, engine.configurationCopy()[1] & 0xff);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x9a}, write.acknowledgement());
        assertEquals(0x9a, write.responsePacket()[2] & 0xff);

        byte[] maximumWrite = new byte[MobileAdapterEngine.MAX_CONFIGURATION_OPERATION_BYTES + 1];
        maximumWrite[0] = (byte) 128;
        Arrays.fill(maximumWrite, 1, maximumWrite.length, (byte) 0x6a);
        assertEquals(MobileAdapterEngine.Outcome.CONFIG_WRITE,
                feed(engine, packet(0x1a, maximumWrite)).outcome());
        for (int i = 128; i < 256; i++) {
            assertEquals(0x6a, engine.configurationCopy()[i] & 0xff);
        }
        assertEquals(MobileAdapterEngine.Outcome.CONFIG_WRITE,
                feed(engine, packet(0x1a, new byte[]{(byte) 255})).outcome());

        byte[] afterWrite = engine.configurationCopy();
        assertUnsupported(feed(engine,
                packet(0x1a, new byte[]{(byte) 255, 1, 2})));
        byte[] oversizedWrite = new byte[MobileAdapterEngine.MAX_CONFIGURATION_OPERATION_BYTES + 2];
        assertUnsupported(feed(engine, packet(0x1a, oversizedWrite)));
        assertArrayEquals(afterWrite, engine.configurationCopy());

        assertUnsupported(feed(engine, packet(0x19, new byte[]{(byte) 200, 57})));
        assertUnsupported(feed(engine, packet(0x19, new byte[]{0, (byte) 129})));
        assertUnsupported(feed(engine, packet(0x10, "NINTEND0".getBytes(
                StandardCharsets.US_ASCII))));
        assertUnsupported(feed(engine, packet(0x11, new byte[]{1})));
        assertUnsupported(feed(engine, packet(0x16, new byte[]{1})));
        assertUnsupported(feed(engine, packet(0x7e, new byte[254])));

        MobileAdapterEngine.EngineResult length = feed(
                engine, new byte[]{(byte) 0x99, 0x66, 0x7e, 0, 0, (byte) 0xff});
        assertEquals(MobileAdapterEngine.Outcome.LENGTH_LIMIT, length.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.LENGTH_LIMIT, length.error());
        assertEquals(0, length.retainedBytes());
        assertEquals(0, length.acknowledgement().length);

        MobileAdapterEngine.EngineResult magic = feed(
                engine, new byte[]{(byte) 0x99, 0x65});
        assertEquals(MobileAdapterEngine.Outcome.MAGIC_ERROR, magic.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.INVALID_MAGIC, magic.error());
        MobileAdapterEngine.EngineResult reserved = feed(
                engine, new byte[]{(byte) 0x99, 0x66, 0x10, 1});
        assertEquals(MobileAdapterEngine.Outcome.RESERVED_ERROR, reserved.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.RESERVED_VALUE, reserved.error());

        byte[] invalidChecksum = BEGIN.clone();
        invalidChecksum[invalidChecksum.length - 1] ^= 1;
        MobileAdapterEngine.EngineResult checksum = feed(engine, invalidChecksum);
        assertEquals(MobileAdapterEngine.Outcome.CHECKSUM_ERROR, checksum.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.CHECKSUM, checksum.error());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xf1},
                checksum.acknowledgement());
        assertEquals(0, checksum.responsePacket().length);
    }

    @Test
    public void configurationReplacementIsAtomicAndDeviceAcknowledgementIsNotFixtureSpecific() {
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, 0x21, configuration());
        byte[] replacement = new byte[256];
        Arrays.fill(replacement, (byte) 0x5a);
        engine.replaceConfiguration(replacement);
        replacement[0] = 0;
        assertEquals(0x5a, engine.configurationCopy()[0] & 0xff);
        assertThrows(IllegalArgumentException.class,
                () -> engine.replaceConfiguration(new byte[255]));
        assertEquals(0x5a, engine.configurationCopy()[0] & 0xff);

        MobileAdapterEngine.EngineResult started = feed(engine, BEGIN);
        assertArrayEquals(new byte[]{(byte) 0xa1, (byte) 0x90},
                started.acknowledgement());
        assertFalse(Arrays.equals(started.responsePacket(), started.acknowledgement()));

        MobileAdapterEngine.EngineResult historical = feed(
                engine, packet(0x19, new byte[]{0, 1}));
        byte[] newer = new byte[256];
        engine.replaceConfiguration(newer);
        MobileAdapterEngine restored = new MobileAdapterEngine(
                ClockSpec.LEGACY, 0, new byte[256]);
        restored.restoreState(engine.captureState());
        assertResultsEqual(historical, restored.snapshot());
        assertArrayEquals(newer, restored.configurationCopy());
    }

    @Test
    public void fakeBackendEnforcesEightOccupiedSlotsAndAggregateByteOwnershipExactly() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        for (int i = 0; i < MobileAdapterBackendPort.MAX_REQUEST_SLOTS; i++) {
            assertEquals(OfferResult.ACCEPTED,
                    offer(backend, new BackendRequest(i, 0x12, new byte[0])));
        }
        assertEquals(MobileAdapterBackendPort.MAX_REQUEST_SLOTS,
                backend.occupiedRequestSlots());
        assertEquals(OfferResult.DUPLICATE_ID,
                offer(backend, new BackendRequest(0, 0x12, new byte[0])));
        assertEquals(OfferResult.REQUEST_LIMIT,
                offer(backend, new BackendRequest(9, 0x12, new byte[0])));
        assertEquals(CompletionResult.COMPLETED, complete(backend, 0, new byte[]{1}));
        assertEquals(OfferResult.REQUEST_LIMIT,
                offer(backend, new BackendRequest(9, 0x12, new byte[0])));
        assertEquals(1, backend.completedResults());
        assertEquals(7, backend.pendingRequests());
        backend.cancelAll();

        MobileAdapterBackendPort.BackendGeneration cancelledGeneration = backend.generation();
        assertEquals(OfferResult.ACCEPTED,
                backend.offer(cancelledGeneration,
                        new BackendRequest(55, 0x12, new byte[]{1})));
        backend.cancelAll();
        MobileAdapterBackendPort.BackendGeneration currentGeneration = backend.generation();
        assertFalse(cancelledGeneration == currentGeneration);
        assertEquals(OfferResult.STALE_GENERATION,
                backend.offer(cancelledGeneration,
                        new BackendRequest(56, 0x12, new byte[]{1})));
        assertEquals(OfferResult.ACCEPTED,
                backend.offer(currentGeneration,
                        new BackendRequest(55, 0x12, new byte[]{2})));
        assertEquals(CompletionResult.STALE_GENERATION,
                backend.complete(cancelledGeneration, 55, new byte[]{3}));
        assertEquals(1, backend.pendingRequests());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(currentGeneration, 55,
                        MobileAdapterBackendPort.BackendStatus.LOOKUP_FAILED, new byte[]{4}));
        MobileAdapterBackendPort.BackendCompletion typed = backend.poll(currentGeneration);
        assertNotNull(typed);
        assertSame(currentGeneration, typed.generation());
        assertEquals(MobileAdapterBackendPort.BackendStatus.LOOKUP_FAILED, typed.status());
        backend.cancelAll();
        assertNull(backend.poll(currentGeneration));

        byte[] source = new byte[MobileAdapterBackendPort.MAX_BUFFERED_BYTES];
        source[0] = 0x33;
        BackendRequest maximum = new BackendRequest(100, 0x12, source);
        source[0] = 0;
        assertEquals(0x33, maximum.payload()[0] & 0xff);
        assertEquals(OfferResult.ACCEPTED, offer(backend, maximum));
        assertEquals(MobileAdapterBackendPort.MAX_BUFFERED_BYTES, backend.bufferedBytes());
        assertEquals(OfferResult.BYTE_LIMIT,
                offer(backend, new BackendRequest(101, 0x12, new byte[]{1})));
        assertEquals(CompletionResult.BYTE_LIMIT,
                complete(backend, 100,
                        new byte[MobileAdapterBackendPort.MAX_BUFFERED_BYTES + 1]));
        assertEquals(CompletionResult.COMPLETED,
                complete(backend, 100,
                        new byte[MobileAdapterBackendPort.MAX_BUFFERED_BYTES]));
        assertEquals(CompletionResult.UNKNOWN_ID, complete(backend, 100, new byte[0]));
        MobileAdapterBackendPort.BackendCompletion completion = backend.poll();
        assertNotNull(completion);
        assertEquals(100, completion.requestId());
        assertSame(backend.generation(), completion.generation());
        assertEquals(MobileAdapterBackendPort.BackendStatus.SUCCESS, completion.status());
        byte[] visible = completion.payload();
        visible[0] = 1;
        assertEquals(0, completion.payload()[0]);
        assertNull(backend.poll());
        assertEquals(0, backend.occupiedRequestSlots());
        assertEquals(0, backend.bufferedBytes());
        backend.cancelAll();
        assertEquals(0, backend.bufferedBytes());
        assertThrows(IllegalArgumentException.class,
                () -> new BackendRequest(102, 0x12,
                        new byte[MobileAdapterBackendPort.MAX_BUFFERED_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> new MobileAdapterBackendPort.BackendCompletion(102,
                        new byte[MobileAdapterBackendPort.MAX_BUFFERED_BYTES + 1]));
    }

    @Test
    public void documentedCustomBackendCommandsUseOneBoundedNonblockingResponseChannel() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);

        MobileAdapterEngine.EngineResult dnsPending = feed(
                engine, packet(0x28, "fixture.test\0ignored".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING, dnsPending.outcome());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xa8},
                dnsPending.acknowledgement());
        assertEquals(1, dnsPending.pendingPacketSlots());
        assertEquals(1, backend.pendingRequests());
        assertThrows(IllegalStateException.class, engine::completePendingPacketSlot);
        assertEquals(1, engine.snapshot().pendingPacketSlots());

        MobileAdapterEngine.EngineResult busy = feed(
                engine, packet(0x28, "second.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_ERROR, busy.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.BACKEND_BUSY, busy.error());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xf2}, busy.acknowledgement());
        assertEquals(1, backend.pendingRequests());

        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{(byte) 192, 0, 2, 44}));
        MobileAdapterEngine.EngineResult dns = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE, dns.outcome());
        assertEquals(0xa8, dns.responsePacket()[2] & 0xff);
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 44},
                Arrays.copyOfRange(dns.responsePacket(), 6, 10));
        assertEquals(0, dns.pendingPacketSlots());

        MobileAdapterEngine.EngineResult openPending = feed(
                engine, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING, openPending.outcome());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 1,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{0}));
        MobileAdapterEngine.EngineResult opened = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE, opened.outcome());
        assertEquals(0xa3, opened.responsePacket()[2] & 0xff);
        assertTrue(engine.hasExternalIo());

        byte[] maximumTransfer = new byte[MobileAdapterEngine.MAX_PACKET_DATA_BYTES];
        maximumTransfer[0] = 0;
        Arrays.fill(maximumTransfer, 1, maximumTransfer.length, (byte) 0x5a);
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x15, maximumTransfer)).outcome());
        byte[] maximumReply = maximumTransfer.clone();
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 2,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, maximumReply));
        MobileAdapterEngine.EngineResult transfer = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE, transfer.outcome());
        assertEquals(MobileAdapterEngine.MAX_PACKET_BYTES, transfer.responsePacket().length);
        assertEquals(0x95, transfer.responsePacket()[2] & 0xff);

        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x24, new byte[]{0})).outcome());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 3,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                engine.pollBackendCompletion().outcome());
        assertFalse(engine.hasExternalIo());

        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x25, new byte[]{127, 0, 0, 1, 0, 53})).outcome());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 4,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{1}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                engine.pollBackendCompletion().outcome());
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x26, new byte[]{1})).outcome());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 5,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{1}));
        engine.pollBackendCompletion();
        assertFalse(engine.hasExternalIo());
    }

    @Test
    public void customBackendRejectsMalformedRequestsAndMapsTypedFailuresToCoarseErrors() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);

        assertBackendError(feed(engine, packet(0x15, new byte[0])), 0x15, 0x00);
        assertBackendError(feed(engine, packet(0x23, new byte[]{127, 0, 0, 1, 0, 0})),
                0x23, 0x03);
        assertBackendError(feed(engine, packet(0x23, new byte[5])), 0x23, 0x03);
        assertBackendError(feed(engine, packet(0x23, new byte[7])), 0x23, 0x03);
        assertBackendError(feed(engine, packet(0x24, new byte[0])), 0x24, 0x00);
        assertBackendError(feed(engine, packet(0x24, new byte[2])), 0x24, 0x00);
        assertBackendError(feed(engine, packet(0x24, new byte[]{2})), 0x24, 0x00);
        assertBackendError(feed(engine, packet(0x25, new byte[5])), 0x25, 0x03);
        assertBackendError(feed(engine, packet(0x25, new byte[7])), 0x25, 0x03);
        assertBackendError(feed(engine, packet(0x26, new byte[0])), 0x26, 0x00);
        assertBackendError(feed(engine, packet(0x26, new byte[2])), 0x26, 0x00);
        assertBackendError(feed(engine, packet(0x26, new byte[]{0})), 0x26, 0x00);
        assertBackendError(feed(engine, packet(0x28, new byte[0])), 0x28, 0x02);
        byte[] oversizedDnsName = new byte[MobileAdapterEngine.MAX_DNS_NAME_BYTES + 1];
        Arrays.fill(oversizedDnsName, (byte) 'a');
        assertBackendError(feed(engine, packet(0x28, oversizedDnsName)), 0x28, 0x02);
        assertEquals(0, backend.occupiedRequestSlots());

        byte[] maximumDnsName = new byte[MobileAdapterEngine.MAX_DNS_NAME_BYTES];
        Arrays.fill(maximumDnsName, (byte) 'a');
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x28, maximumDnsName)).outcome());
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{(byte) 192, 0, 2, 1}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                engine.pollBackendCompletion().outcome());

        feed(engine, packet(0x28, "missing.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 1,
                        MobileAdapterBackendPort.BackendStatus.LOOKUP_FAILED, new byte[0]));
        assertBackendError(engine.pollBackendCompletion(), 0x28, 0x02);

        feed(engine, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 2,
                        MobileAdapterBackendPort.BackendStatus.CONNECTION_LIMIT, new byte[0]));
        assertBackendError(engine.pollBackendCompletion(), 0x23, 0x00);

        feed(engine, packet(0x28, "malformed.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 3,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[3]));
        MobileAdapterEngine.EngineResult malformed = engine.pollBackendCompletion();
        assertBackendError(malformed, 0x28, 0x02);
        assertEquals(MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID, malformed.error());

        feed(engine, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        MobileAdapterBackendPort.BackendGeneration malformedOpenGeneration = backend.generation();
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(malformedOpenGeneration, 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{2}));
        MobileAdapterEngine.EngineResult malformedOpen = engine.pollBackendCompletion();
        assertBackendError(malformedOpen, 0x23, 0x03);
        assertEquals(MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID,
                malformedOpen.error());
        assertFalse(malformedOpenGeneration == backend.generation());
        assertEquals(0, malformedOpen.pendingPacketSlots());
        assertEquals(0, backend.occupiedRequestSlots());
        assertFalse(engine.hasExternalIo());

        assertServiceError(feed(engine, packet(0x12, new byte[]{0, '1'})), 0x12, 0x03);
        assertServiceError(feed(engine, packet(0x21, new byte[10])), 0x21, 0x01);
    }

    @Test
    public void crystalServiceFlowDialsBlueAdapterLogsIntoIspAndReportsTelephoneState() {
        MobileAdapterEngine engine = engine(ClockSpec.LEGACY);

        assertServiceError(feed(engine, packet(0x17, new byte[0])), 0x17, 0x01);
        assertEquals(MobileAdapterEngine.Phase.SLEEP, engine.snapshot().phase());
        feed(engine, BEGIN);

        MobileAdapterEngine.EngineResult disconnected = feed(
                engine, packet(0x17, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.TELEPHONE_STATUS, disconnected.outcome());
        assertEquals(MobileAdapterEngine.Phase.SESSION, disconnected.phase());
        assertArrayEquals(new byte[]{0, 0x4d, 0}, responseData(disconnected));
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x97},
                disconnected.acknowledgement());

        MobileAdapterEngine.EngineResult dialled = feed(engine, packet(0x12, ispDialData()));
        assertEquals(MobileAdapterEngine.Outcome.TELEPHONE_DIALLED, dialled.outcome());
        assertEquals(MobileAdapterEngine.Phase.TELEPHONE, dialled.phase());
        assertEquals(0, responseData(dialled).length);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x92}, dialled.acknowledgement());

        MobileAdapterEngine.EngineResult connected = feed(engine, packet(0x17, new byte[0]));
        assertArrayEquals(new byte[]{4, 0x4d, 0}, responseData(connected));

        MobileAdapterEngine.EngineResult loggedIn = feed(engine, packet(0x21, ispLoginData()));
        assertEquals(MobileAdapterEngine.Outcome.ISP_LOGGED_IN, loggedIn.outcome());
        assertEquals(MobileAdapterEngine.Phase.INTERNET, loggedIn.phase());
        assertArrayEquals(new byte[]{127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                responseData(loggedIn));
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xa1},
                loggedIn.acknowledgement());

        MobileAdapterEngine.EngineResult internetStatus = feed(
                engine, packet(0x17, new byte[0]));
        assertArrayEquals(new byte[]{4, 0x4d, 0}, responseData(internetStatus));

        MobileAdapterEngine.EngineResult loggedOut = feed(engine, packet(0x22, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.ISP_LOGGED_OUT, loggedOut.outcome());
        assertEquals(MobileAdapterEngine.Phase.TELEPHONE, loggedOut.phase());
        assertEquals(0, responseData(loggedOut).length);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xa2},
                loggedOut.acknowledgement());

        MobileAdapterEngine.EngineResult hungUp = feed(engine, packet(0x13, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.TELEPHONE_HUNG_UP, hungUp.outcome());
        assertEquals(MobileAdapterEngine.Phase.SESSION, hungUp.phase());
        assertEquals(0, responseData(hungUp).length);
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x93}, hungUp.acknowledgement());
    }

    @Test
    public void serviceCommandsRejectInvalidStateAndBodiesWithTypedAdapterErrors() {
        MobileAdapterEngine engine = engine(ClockSpec.LEGACY);
        assertServiceError(feed(engine, packet(0x12, ispDialData())), 0x12, 0x01);

        feed(engine, BEGIN);
        assertServiceError(feed(engine, packet(0x12, new byte[0])), 0x12, 0x02);
        byte[] oversizedDial = new byte[MobileAdapterEngine.MAX_DIAL_DATA_BYTES + 1];
        Arrays.fill(oversizedDial, (byte) '1');
        oversizedDial[0] = 0;
        assertServiceError(feed(engine, packet(0x12, oversizedDial)), 0x12, 0x02);
        assertServiceError(feed(engine, packet(0x12, new byte[]{1, '#', '9', '6', '7', '7'})),
                0x12, 0x02);
        assertServiceError(feed(engine, packet(0x12, new byte[]{0, '1'})), 0x12, 0x03);
        assertServiceError(feed(engine, packet(0x21, ispLoginData())), 0x21, 0x01);

        feed(engine, packet(0x12, ispDialData()));
        assertServiceError(feed(engine, packet(0x17, new byte[]{1})), 0x17, 0x02);
        assertServiceError(feed(engine, packet(0x21, Arrays.copyOf(ispLoginData(), 9))),
                0x21, 0x02);
        byte[] oversizedUser = ispLoginData();
        oversizedUser[0] = MobileAdapterEngine.MAX_ISP_CREDENTIAL_BYTES + 1;
        assertServiceError(feed(engine, packet(0x21, oversizedUser)), 0x21, 0x02);
        assertServiceError(feed(engine, packet(0x22, new byte[0])), 0x22, 0x01);

        feed(engine, packet(0x21, ispLoginData()));
        assertServiceError(feed(engine, packet(0x22, new byte[]{1})), 0x22, 0x02);
        feed(engine, packet(0x22, new byte[0]));
        feed(engine, packet(0x13, new byte[0]));
        assertServiceError(feed(engine, packet(0x13, new byte[0])), 0x13, 0x01);
    }

    @Test
    public void ispLogoutCancelsPendingBackendOwnershipAndInternetAcceptsNetworkCommands() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);
        feed(engine, packet(0x12, ispDialData()));
        assertBackendError(feed(engine, packet(0x28,
                "service.test".getBytes(StandardCharsets.US_ASCII))), 0x28, 0x01);
        assertEquals(0, backend.occupiedRequestSlots());
        feed(engine, packet(0x21, ispLoginData()));

        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                feed(engine, packet(0x28,
                        "service.test".getBytes(StandardCharsets.US_ASCII))).outcome());
        assertTrue(engine.hasExternalIo());
        assertEquals(1, backend.occupiedRequestSlots());
        MobileAdapterBackendPort.BackendGeneration stale = backend.generation();

        MobileAdapterEngine.EngineResult loggedOut = feed(engine, packet(0x22, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.ISP_LOGGED_OUT, loggedOut.outcome());
        assertEquals(0, loggedOut.pendingPacketSlots());
        assertFalse(engine.hasExternalIo());
        assertEquals(0, backend.occupiedRequestSlots());
        assertFalse(stale == backend.generation());
    }

    @Test
    public void cancellationGenerationAndCaptureDiscardExternalOwnershipDeterministically() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);
        feed(engine, packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII)));
        MobileAdapterBackendPort.BackendGeneration stale = backend.generation();

        MobileAdapterEngine.MobileAdapterEngineNetworkState captured = networkState(engine);
        assertTrue(captured.externalIoAtCapture());
        assertEquals(0, captured.pendingPacketSlots());
        assertEquals(0, captured.responsePacket().length);
        assertEquals(0, captured.acknowledgement().length);

        backend.cancelAll();
        assertNull(backend.poll(stale));
        MobileAdapterEngine.EngineResult disconnected = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                disconnected.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.EXTERNAL_IO_DISCONNECTED,
                disconnected.error());
        assertFalse(engine.hasExternalIo());

        MobileAdapterEngine restored = engine(ClockSpec.LEGACY);
        restored.restoreState(captured);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restored.snapshot().outcome());
        assertFalse(restored.hasExternalIo());
        assertTrue(restored.captureState() instanceof
                MobileAdapterEngine.MobileAdapterEngineState);
    }

    @Test
    public void backendCompletionWaitsForAConcurrentPartialGuestPacketAndRemainsCapturable() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);
        feed(engine, packet(0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{(byte) 192, 0, 2, 9}));

        byte[] nextPacket = packet(0x19, new byte[]{0, 0});
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE,
                engine.acceptByte(nextPacket[0] & 0xff).outcome());

        MobileAdapterEngine.EngineResult waiting = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, waiting.outcome());
        assertEquals(1, waiting.retainedBytes());
        assertEquals(1, backend.completedResults());
        MobileAdapterEngine.MobileAdapterEngineNetworkState captured = networkState(engine);
        MobileAdapterEngine restoredPartial = engine(ClockSpec.LEGACY);
        restoredPartial.restoreState(captured);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restoredPartial.snapshot().outcome());
        assertEquals(1, restoredPartial.snapshot().retainedBytes());

        MobileAdapterEngine.EngineResult pureResult = feed(
                engine, Arrays.copyOfRange(nextPacket, 1, nextPacket.length));
        assertEquals(MobileAdapterEngine.Outcome.CONFIG_READ, pureResult.outcome());
        MobileAdapterEngine.EngineResult completion = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE, completion.outcome());
        assertEquals(0, completion.retainedBytes());
        assertEquals(0, backend.completedResults());

        MobileAdapterEngine restoredCompletion = engine(ClockSpec.LEGACY);
        restoredCompletion.restoreState(engine.captureState());
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                restoredCompletion.snapshot().outcome());
    }

    @Test
    public void idleLogicalConnectionObservesExternalBackendGenerationCancellation() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(engine, BEGIN);
        feed(engine, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                engine.pollBackendCompletion().outcome());
        assertTrue(engine.hasExternalIo());

        backend.cancelAll();

        MobileAdapterEngine.EngineResult disconnected = engine.pollBackendCompletion();
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                disconnected.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.EXTERNAL_IO_DISCONNECTED,
                disconnected.error());
        assertEquals(0, disconnected.pendingPacketSlots());
        assertFalse(engine.hasExternalIo());
    }

    @Test
    public void externalIoCapturePreservesAConcurrentPartialGuestPacket() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine live = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        feed(live, BEGIN);
        feed(live, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(CompletionResult.COMPLETED,
                backend.complete(backend.generation(), 0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS, new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                live.pollBackendCompletion().outcome());

        byte[] dnsPacket = packet(
                0x28, "fixture.test".getBytes(StandardCharsets.US_ASCII));
        feed(live, Arrays.copyOf(dnsPacket, 6));
        assertEquals(6, live.snapshot().retainedBytes());
        assertTrue(live.hasExternalIo());

        MobileAdapterEngine.MobileAdapterEngineNetworkState captured = networkState(live);
        assertTrue(captured.externalIoAtCapture());
        assertEquals(6, captured.packetCount());
        assertEquals(dnsPacket.length, captured.expectedPacketBytes());
        assertTrue(live.hasExternalIo());

        MobileAdapterEngine restored = engine(ClockSpec.LEGACY);
        restored.restoreState(captured);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restored.snapshot().outcome());
        assertEquals(6, restored.snapshot().retainedBytes());
        assertFalse(restored.hasExternalIo());

        MobileAdapterEngine.EngineResult completed = feed(
                restored, Arrays.copyOfRange(dnsPacket, 6, dnsPacket.length));
        assertBackendError(completed, 0x28, 0x01);
        assertEquals(0, completed.retainedBytes());
        live.cancelOrReplace();
    }

    @Test
    public void cancellationResetEndTimeoutAndRestoreReleaseAllBoundedOwnership() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        for (int cycle = 0; cycle < 10; cycle++) {
            assertEquals(OfferResult.ACCEPTED,
                    offer(backend, new BackendRequest(cycle, 0x12, new byte[]{1, 2})));
            assertTrue(engine.reservePendingPacketSlot());
            assertTrue(engine.reservePendingPacketSlot());
            feed(engine, Arrays.copyOf(BEGIN, 6));
            MobileAdapterEngine.EngineResult cancelled = engine.cancelOrReplace();
            assertEquals(MobileAdapterEngine.Outcome.CANCELLED, cancelled.outcome());
            assertEquals(0, cancelled.retainedBytes());
            assertEquals(0, cancelled.pendingPacketSlots());
            assertEquals(0, backend.occupiedRequestSlots());
            assertEquals(0, backend.bufferedBytes());
        }
        engine.cancelOrReplace();

        feed(engine, BEGIN);
        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(20, 0x12, new byte[]{1})));
        assertTrue(engine.reservePendingPacketSlot());
        MobileAdapterEngine.EngineResult reset = feed(engine, packet(0x16, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.SESSION_RESET, reset.outcome());
        assertEquals(0, reset.pendingPacketSlots());
        assertEquals(0, backend.occupiedRequestSlots());

        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(21, 0x12, new byte[]{1})));
        assertTrue(engine.reservePendingPacketSlot());
        MobileAdapterEngine.EngineResult ended = feed(engine, packet(0x11, new byte[0]));
        assertEquals(MobileAdapterEngine.Outcome.SESSION_ENDED, ended.outcome());
        assertEquals(0, ended.pendingPacketSlots());
        assertEquals(0, backend.occupiedRequestSlots());

        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(22, 0x12, new byte[]{1})));
        assertTrue(engine.reservePendingPacketSlot());
        engine.acceptByte(0x99);
        long boundary = ClockSpec.LEGACY.ticksForMilliseconds(
                MobileAdapterEngine.IDLE_TIMEOUT_MILLIS, ClockSpec.Rounding.FLOOR);
        engine.advanceTicks(boundary);
        MobileAdapterEngine.EngineResult timeout = engine.advanceTicks(1);
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET, timeout.outcome());
        assertEquals(0, timeout.pendingPacketSlots());
        assertEquals(0, backend.occupiedRequestSlots());

        feed(engine, Arrays.copyOf(BEGIN, 6));
        MobileAdapterEngine.MobileAdapterEngineState restorable = state(engine);
        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(23, 0x12, new byte[]{1, 2, 3})));
        assertTrue(engine.reservePendingPacketSlot());
        feed(engine, Arrays.copyOfRange(BEGIN, 6, BEGIN.length));
        engine.restoreState(restorable);
        assertEngineStatesEqual(restorable, state(engine));
        assertEquals(0, backend.occupiedRequestSlots());
        assertEquals(0, backend.bufferedBytes());
    }

    @Test
    public void pendingPacketLimitRecoversToAValidCapturableStateAfterCompletion() {
        MobileAdapterEngine engine = engine(ClockSpec.LEGACY);
        assertTrue(engine.reservePendingPacketSlot());
        assertTrue(engine.reservePendingPacketSlot());
        assertFalse(engine.reservePendingPacketSlot());
        assertEquals(MobileAdapterEngine.Outcome.PENDING_LIMIT, engine.snapshot().outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.PENDING_LIMIT, engine.snapshot().error());
        assertFalse(engine.reservePendingPacketSlot());
        engine.completePendingPacketSlot();
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, engine.snapshot().outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.NONE, engine.snapshot().error());
        assertNotNull(engine.captureState());
        assertTrue(engine.reservePendingPacketSlot());
        assertThrows(IllegalStateException.class, () -> {
            engine.completePendingPacketSlot();
            engine.completePendingPacketSlot();
            engine.completePendingPacketSlot();
        });

        engine.cancelOrReplace();
        assertTrue(engine.reservePendingPacketSlot());
        MobileAdapterEngine restored = engine(ClockSpec.LEGACY);
        restored.restoreState(engine.captureState());
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, restored.snapshot().outcome());
        assertEquals(1, restored.snapshot().pendingPacketSlots());
    }

    @Test
    public void serialEndpointSchedulesRequestAndResponseBytesAndPersistsPartialRequests() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        for (byte value : BEGIN) {
            assertTrue(endpoint.isSerialInputHigh());
            assertEquals(-1, endpoint.recvBit());
            assertEquals(0xd2, exchange(endpoint, value & 0xff));
        }
        assertEquals(MobileAdapterEngine.Outcome.SESSION_STARTED,
                endpoint.snapshot().outcome());
        assertArrayEquals(packet(0x90, "NINTENDO".getBytes(StandardCharsets.US_ASCII)),
                endpoint.snapshot().responsePacket());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x90},
                endpoint.snapshot().acknowledgement());

        assertEquals(0x88, exchange(endpoint, 0x80));
        assertEquals(0x90, exchange(endpoint, 0x00));
        assertEquals(0xd2, exchange(endpoint, 0x00));
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        byte[] response = endpoint.snapshot().responsePacket();
        for (byte value : response) {
            assertEquals(value & 0xff, exchange(endpoint, 0x4b));
        }
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertEquals(0x00, exchange(endpoint, 0x10));

        endpoint.setSb(0x99);
        endpoint.startSending();
        assertEquals(1, endpoint.sendBit());
        assertEquals(1, endpoint.sendBit());
        assertEquals(0, endpoint.sendBit());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState captured =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();
        assertEquals(0x99, captured.sb());
        assertEquals(3, captured.sendBitIndex());
        assertTrue(captured.byteTransferActive());
        assertEquals(1, captured.wirePhaseId());
        MobileAdapterEngine.MobileAdapterEngineState capturedEngine =
                (MobileAdapterEngine.MobileAdapterEngineState) captured.engineState();
        assertEquals(0, capturedEngine.packetCount());

        MobileAdapterSerialEndpoint restored = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, 0, new byte[256]);
        restored.restoreState(captured);
        assertResultsEqual(endpoint.snapshot(), restored.snapshot());
        assertEquals(1, restored.sendBit());
        assertEquals(0, restored.sendBit());
        assertEquals(0, restored.sendBit());
        assertEquals(1, restored.sendBit());
        assertEquals(0, restored.sendBit());
        assertEquals(1, restored.snapshot().retainedBytes());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState invalid =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState(
                        capturedEngine, captured.sb(), 8);
        MobileAdapterEngine.EngineResult beforeInvalidRestore = restored.snapshot();
        assertThrows(IllegalArgumentException.class, () -> restored.restoreState(invalid));
        assertResultsEqual(beforeInvalidRestore, restored.snapshot());

        assertEquals(OfferResult.ACCEPTED,
                offer(backend, new BackendRequest(1, 0x12, new byte[]{1})));
        assertTrue(endpoint.reservePendingPacketSlot());
        endpoint.disconnect();
        endpoint.disconnect();
        assertEquals(MobileAdapterEngine.Outcome.CANCELLED, endpoint.snapshot().outcome());
        assertEquals(0, endpoint.snapshot().retainedBytes());
        assertEquals(0, endpoint.snapshot().pendingPacketSlots());
        assertEquals(0, backend.occupiedRequestSlots());
        assertEquals(0xd2, exchange(endpoint, 0x99));
        SerialEndpoint.NULL_ENDPOINT.disconnect();
    }

    @Test
    public void serialEndpointCommitsBytesOnlyAfterEightClockEdgesAndRestartsPartialTransfers() {
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration());

        endpoint.setSb(0x99);
        endpoint.startSending();
        assertEquals(0, endpoint.snapshot().retainedBytes());
        for (int bit = 0; bit < 3; bit++) {
            assertEquals((0xd2 >>> (7 - bit)) & 1, endpoint.sendBit());
        }
        assertEquals(0, endpoint.snapshot().retainedBytes());

        // Rewriting SC with the transfer bit set restarts the byte; the abandoned three clocks
        // must not consume an input byte or contribute to packet magic.
        endpoint.startSending();
        for (int bit = 0; bit < 8; bit++) {
            assertEquals((0xd2 >>> (7 - bit)) & 1, endpoint.sendBit());
        }
        assertEquals(1, endpoint.snapshot().retainedBytes());

        endpoint.setSb(0x66);
        endpoint.startSending();
        for (int bit = 0; bit < 7; bit++) {
            assertEquals((0xd2 >>> (7 - bit)) & 1, endpoint.sendBit());
        }
        assertEquals(1, endpoint.snapshot().retainedBytes());
        assertEquals(0, endpoint.sendBit());
        assertEquals(2, endpoint.snapshot().retainedBytes());
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, endpoint.snapshot().outcome());
    }

    @Test
    public void persistedEnumCodesAreExplicitUniqueAndRoundTrip() {
        assertEquals(1, MobileAdapterEngine.Phase.SLEEP.id());
        assertEquals(2, MobileAdapterEngine.Phase.SESSION.id());
        assertEquals(3, MobileAdapterEngine.Phase.TELEPHONE.id());
        assertEquals(4, MobileAdapterEngine.Phase.INTERNET.id());

        assertEquals(1, MobileAdapterEngine.Outcome.NEED_MORE.id());
        assertEquals(2, MobileAdapterEngine.Outcome.SESSION_STARTED.id());
        assertEquals(3, MobileAdapterEngine.Outcome.SESSION_ENDED.id());
        assertEquals(4, MobileAdapterEngine.Outcome.SESSION_RESET.id());
        assertEquals(5, MobileAdapterEngine.Outcome.CHECKSUM_ERROR.id());
        assertEquals(6, MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET.id());
        assertEquals(7, MobileAdapterEngine.Outcome.IDLE_BOUNDARY_WAIT.id());
        assertEquals(8, MobileAdapterEngine.Outcome.CONFIG_READ.id());
        assertEquals(9, MobileAdapterEngine.Outcome.CONFIG_READ_BOUNDARY.id());
        assertEquals(10, MobileAdapterEngine.Outcome.UNSUPPORTED_COMMAND.id());
        assertEquals(11, MobileAdapterEngine.Outcome.MAGIC_ERROR.id());
        assertEquals(12, MobileAdapterEngine.Outcome.RESERVED_ERROR.id());
        assertEquals(13, MobileAdapterEngine.Outcome.LENGTH_LIMIT.id());
        assertEquals(14, MobileAdapterEngine.Outcome.BUFFER_LIMIT.id());
        assertEquals(15, MobileAdapterEngine.Outcome.TIME_REGRESSION.id());
        assertEquals(16, MobileAdapterEngine.Outcome.CANCELLED.id());
        assertEquals(17, MobileAdapterEngine.Outcome.PENDING_LIMIT.id());
        assertEquals(18, MobileAdapterEngine.Outcome.CONFIG_WRITE.id());
        assertEquals(19, MobileAdapterEngine.Outcome.BACKEND_PENDING.id());
        assertEquals(20, MobileAdapterEngine.Outcome.BACKEND_RESPONSE.id());
        assertEquals(21, MobileAdapterEngine.Outcome.BACKEND_ERROR.id());
        assertEquals(22, MobileAdapterEngine.Outcome.BACKEND_REMOTE_CLOSED.id());
        assertEquals(23, MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED.id());
        assertEquals(24, MobileAdapterEngine.Outcome.TELEPHONE_DIALLED.id());
        assertEquals(25, MobileAdapterEngine.Outcome.TELEPHONE_HUNG_UP.id());
        assertEquals(26, MobileAdapterEngine.Outcome.TELEPHONE_STATUS.id());
        assertEquals(27, MobileAdapterEngine.Outcome.ISP_LOGGED_IN.id());
        assertEquals(28, MobileAdapterEngine.Outcome.ISP_LOGGED_OUT.id());
        assertEquals(29, MobileAdapterEngine.Outcome.SERVICE_ERROR.id());

        assertEquals(0, MobileAdapterEngine.ErrorCode.NONE.id());
        assertEquals(1, MobileAdapterEngine.ErrorCode.INVALID_MAGIC.id());
        assertEquals(2, MobileAdapterEngine.ErrorCode.RESERVED_VALUE.id());
        assertEquals(3, MobileAdapterEngine.ErrorCode.LENGTH_LIMIT.id());
        assertEquals(4, MobileAdapterEngine.ErrorCode.CHECKSUM.id());
        assertEquals(5, MobileAdapterEngine.ErrorCode.UNSUPPORTED_COMMAND.id());
        assertEquals(6, MobileAdapterEngine.ErrorCode.BUFFER_LIMIT.id());
        assertEquals(7, MobileAdapterEngine.ErrorCode.TIME_REGRESSION.id());
        assertEquals(8, MobileAdapterEngine.ErrorCode.PENDING_LIMIT.id());
        assertEquals(9, MobileAdapterEngine.ErrorCode.BACKEND_BUSY.id());
        assertEquals(10, MobileAdapterEngine.ErrorCode.BACKEND_UNAVAILABLE.id());
        assertEquals(11, MobileAdapterEngine.ErrorCode.BACKEND_RESPONSE_INVALID.id());
        assertEquals(12, MobileAdapterEngine.ErrorCode.EXTERNAL_IO_DISCONNECTED.id());

        Set<Integer> phases = new HashSet<>();
        for (MobileAdapterEngine.Phase value : MobileAdapterEngine.Phase.values()) {
            assertTrue(phases.add(value.id()));
            assertSame(value, MobileAdapterEngine.Phase.fromId(value.id()));
        }
        Set<Integer> outcomes = new HashSet<>();
        for (MobileAdapterEngine.Outcome value : MobileAdapterEngine.Outcome.values()) {
            assertTrue(outcomes.add(value.id()));
            assertSame(value, MobileAdapterEngine.Outcome.fromId(value.id()));
        }
        Set<Integer> errors = new HashSet<>();
        for (MobileAdapterEngine.ErrorCode value : MobileAdapterEngine.ErrorCode.values()) {
            assertTrue(errors.add(value.id()));
            assertSame(value, MobileAdapterEngine.ErrorCode.fromId(value.id()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MobileAdapterEngine.Phase.fromId(0));
        assertThrows(IllegalArgumentException.class,
                () -> MobileAdapterEngine.Outcome.fromId(0));
        assertThrows(IllegalArgumentException.class,
                () -> MobileAdapterEngine.ErrorCode.fromId(-1));
    }

    private static MobileAdapterEngine engine(ClockSpec clockSpec) {
        return new MobileAdapterEngine(clockSpec, DEVICE_ID, configuration());
    }

    private static OfferResult offer(DeterministicMobileAdapterBackend backend,
                                     BackendRequest request) {
        return backend.offer(backend.generation(), request);
    }

    private static CompletionResult complete(DeterministicMobileAdapterBackend backend,
                                             long requestId, byte[] response) {
        return backend.complete(backend.generation(), requestId, response);
    }

    private static byte[] configuration() {
        byte[] result = new byte[MobileAdapterEngine.CONFIGURATION_BYTES];
        result[0] = 0x4d;
        result[1] = 0x41;
        result[2] = (byte) 0x81;
        for (int i = 0; i < 128; i++) result[128 + i] = (byte) i;
        return result;
    }

    private static byte[] ispDialData() {
        return new byte[]{0, '#', '9', '6', '7', '7'};
    }

    private static byte[] ispLoginData() {
        return new byte[]{
                3, 'u', 's', 'r',
                3, 'p', 'w', 'd',
                1, 1, 1, 1,
                8, 8, 8, 8
        };
    }

    private static MobileAdapterEngine.EngineResult feed(
            MobileAdapterEngine engine, byte[] bytes) {
        MobileAdapterEngine.EngineResult result = engine.snapshot();
        for (byte value : bytes) result = engine.acceptByte(value & 0xff);
        return result;
    }

    private static int exchange(MobileAdapterSerialEndpoint endpoint, int outgoing) {
        endpoint.setSb(outgoing);
        endpoint.startSending();
        int incoming = 0;
        for (int bit = 0; bit < 8; bit++) {
            incoming = incoming << 1 | endpoint.sendBit();
        }
        return incoming;
    }

    private static byte[] packet(int command, byte[] data) {
        if (data.length > MobileAdapterEngine.MAX_PACKET_DATA_BYTES) {
            throw new IllegalArgumentException("test packet exceeds production limit");
        }
        byte[] bytes = new byte[8 + data.length];
        bytes[0] = (byte) 0x99;
        bytes[1] = 0x66;
        bytes[2] = (byte) command;
        bytes[4] = (byte) (data.length >>> 8);
        bytes[5] = (byte) data.length;
        System.arraycopy(data, 0, bytes, 6, data.length);
        int checksum = 0;
        for (int i = 2; i < 6 + data.length; i++) {
            checksum = (checksum + (bytes[i] & 0xff)) & 0xffff;
        }
        bytes[6 + data.length] = (byte) (checksum >>> 8);
        bytes[7 + data.length] = (byte) checksum;
        return bytes;
    }

    private static void assertPacketChecksum(byte[] packet) {
        assertTrue(packet.length >= 8);
        int length = ((packet[4] & 0xff) << 8) | (packet[5] & 0xff);
        assertEquals(8 + length, packet.length);
        int checksum = 0;
        for (int i = 2; i < 6 + length; i++) {
            checksum = (checksum + (packet[i] & 0xff)) & 0xffff;
        }
        int actual = ((packet[6 + length] & 0xff) << 8) |
                (packet[7 + length] & 0xff);
        assertEquals(checksum, actual);
    }

    private static void assertUnsupported(MobileAdapterEngine.EngineResult result) {
        assertEquals(MobileAdapterEngine.Outcome.UNSUPPORTED_COMMAND, result.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.UNSUPPORTED_COMMAND, result.error());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xf0},
                result.acknowledgement());
        assertEquals(0, result.responsePacket().length);
    }

    private static void assertBackendError(MobileAdapterEngine.EngineResult result,
                                           int command, int error) {
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_ERROR, result.outcome());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) (command ^ 0x80)},
                result.acknowledgement());
        assertEquals(0x6e, result.responsePacket()[2] & 0xff);
        assertArrayEquals(new byte[]{(byte) command, (byte) error},
                Arrays.copyOfRange(result.responsePacket(), 6, 8));
        assertPacketChecksum(result.responsePacket());
    }

    private static void assertServiceError(MobileAdapterEngine.EngineResult result,
                                           int command, int error) {
        assertEquals(MobileAdapterEngine.Outcome.SERVICE_ERROR, result.outcome());
        assertEquals(MobileAdapterEngine.ErrorCode.NONE, result.error());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) (command ^ 0x80)},
                result.acknowledgement());
        assertEquals(0x6e, result.responsePacket()[2] & 0xff);
        assertArrayEquals(new byte[]{(byte) command, (byte) error}, responseData(result));
        assertPacketChecksum(result.responsePacket());
    }

    private static byte[] responseData(MobileAdapterEngine.EngineResult result) {
        byte[] response = result.responsePacket();
        return Arrays.copyOfRange(response, 6, response.length - 2);
    }

    private static MobileAdapterEngine.MobileAdapterEngineState state(
            MobileAdapterEngine engine) {
        return (MobileAdapterEngine.MobileAdapterEngineState) engine.captureState();
    }

    private static MobileAdapterEngine.MobileAdapterEngineNetworkState networkState(
            MobileAdapterEngine engine) {
        return (MobileAdapterEngine.MobileAdapterEngineNetworkState) engine.captureState();
    }

    private static void roundTripOutcome(
            MobileAdapterEngine engine,
            MobileAdapterEngine.Outcome expectedOutcome,
            Set<MobileAdapterEngine.Outcome> restoredOutcomes) {
        roundTripOutcome(state(engine), expectedOutcome, restoredOutcomes);
    }

    private static void roundTripOutcome(
            MobileAdapterEngine.MobileAdapterEngineState expected,
            MobileAdapterEngine.Outcome expectedOutcome,
            Set<MobileAdapterEngine.Outcome> restoredOutcomes) {
        assertEquals(expectedOutcome.id(), expected.outcomeId());
        MobileAdapterEngine restored = engine(ClockSpec.LEGACY);
        restored.restoreState(expected);
        assertEngineStatesEqual(expected, state(restored));
        assertEquals(expectedOutcome, restored.snapshot().outcome());
        assertTrue(restoredOutcomes.add(expectedOutcome));
    }

    private static MobileAdapterEngine.MobileAdapterEngineState copyState(
            MobileAdapterEngine.MobileAdapterEngineState source,
            byte[] parser,
            byte[] response,
            int errorId) {
        return new MobileAdapterEngine.MobileAdapterEngineState(
                source.phaseId(),
                source.outcomeId(),
                errorId,
                source.deviceId(),
                parser,
                source.packetCount(),
                source.expectedPacketBytes(),
                source.configuration(),
                response,
                source.acknowledgement(),
                source.idlePhaseUnits(),
                source.serialByteObserved(),
                source.pendingPacketSlots());
    }

    private static MobileAdapterEngine.MobileAdapterEngineState copyTimingState(
            MobileAdapterEngine.MobileAdapterEngineState source,
            long idlePhaseUnits,
            boolean serialByteObserved) {
        return new MobileAdapterEngine.MobileAdapterEngineState(
                source.phaseId(),
                source.outcomeId(),
                source.errorId(),
                source.deviceId(),
                source.packetBuffer(),
                source.packetCount(),
                source.expectedPacketBytes(),
                source.configuration(),
                source.responsePacket(),
                source.acknowledgement(),
                idlePhaseUnits,
                serialByteObserved,
                source.pendingPacketSlots());
    }

    private static MobileAdapterEngine.MobileAdapterEngineState copyPhaseState(
            MobileAdapterEngine.MobileAdapterEngineState source,
            int phaseId) {
        return new MobileAdapterEngine.MobileAdapterEngineState(
                phaseId,
                source.outcomeId(),
                source.errorId(),
                source.deviceId(),
                source.packetBuffer(),
                source.packetCount(),
                source.expectedPacketBytes(),
                source.configuration(),
                source.responsePacket(),
                source.acknowledgement(),
                source.idlePhaseUnits(),
                source.serialByteObserved(),
                source.pendingPacketSlots());
    }

    private static void assertEngineStatesEqual(
            MobileAdapterEngine.MobileAdapterEngineState expected,
            MobileAdapterEngine.MobileAdapterEngineState actual) {
        assertEquals(expected.phaseId(), actual.phaseId());
        assertEquals(expected.outcomeId(), actual.outcomeId());
        assertEquals(expected.errorId(), actual.errorId());
        assertEquals(expected.deviceId(), actual.deviceId());
        assertArrayEquals(expected.packetBuffer(), actual.packetBuffer());
        assertEquals(expected.packetCount(), actual.packetCount());
        assertEquals(expected.expectedPacketBytes(), actual.expectedPacketBytes());
        assertArrayEquals(expected.configuration(), actual.configuration());
        assertArrayEquals(expected.responsePacket(), actual.responsePacket());
        assertArrayEquals(expected.acknowledgement(), actual.acknowledgement());
        assertEquals(expected.idlePhaseUnits(), actual.idlePhaseUnits());
        assertEquals(expected.serialByteObserved(), actual.serialByteObserved());
        assertEquals(expected.pendingPacketSlots(), actual.pendingPacketSlots());
    }

    private static void assertResultsEqual(
            MobileAdapterEngine.EngineResult expected,
            MobileAdapterEngine.EngineResult actual) {
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.outcome(), actual.outcome());
        assertEquals(expected.error(), actual.error());
        assertArrayEquals(expected.responsePacket(), actual.responsePacket());
        assertArrayEquals(expected.acknowledgement(), actual.acknowledgement());
        assertEquals(expected.retainedBytes(), actual.retainedBytes());
        assertEquals(expected.pendingPacketSlots(), actual.pendingPacketSlots());
    }
}
