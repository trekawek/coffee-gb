package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Byte-level transcripts for the Mobile Adapter's documented full-duplex wire schedule. */
public class MobileAdapterSerialEndpointTest {

    private static final int DEVICE_ID = 0x08;

    private static final byte[] BEGIN = packet(
            0x10, "NINTENDO".getBytes(StandardCharsets.US_ASCII));

    private static final byte[] BEGIN_RESPONSE = packet(
            0x90, "NINTENDO".getBytes(StandardCharsets.US_ASCII));

    @Test
    public void beginSessionUsesIdleRequestAckPollingResponseAndSenderAckPhases() {
        MobileAdapterSerialEndpoint endpoint = endpoint();

        // Japanese Crystal probes a sleeping adapter with 4B before its BEGIN packet.
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        for (byte requestByte : BEGIN) {
            assertEquals(0xd2, exchange(endpoint, requestByte & 0xff));
        }

        // Game Boy is the request sender: adapter device ID, then request command acknowledgement.
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertEquals(0x90, exchange(endpoint, 0x00));

        // The adapter skips one byte, then consumes one validated 4B poll before exposing magic.
        assertEquals(0xd2, exchange(endpoint, 0x00));
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        for (byte responseByte : BEGIN_RESPONSE) {
            // The controller polls the backend at frame safe points even for synchronous replies;
            // doing so must not rewind an already-streaming packet back to 99.
            endpoint.pollBackendCompletion();
            assertEquals(responseByte & 0xff, exchange(endpoint, 0x4b));
        }

        // Adapter is now the response sender: it sends its device ID and zero while the Game Boy
        // returns its own device ID and acknowledges the response command.
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertEquals(0x00, exchange(endpoint, 0x10));

        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void changedGuestWriteIsVisibleAtPacketCommitAndIsNotCapturedOrUndoneByWireAbort() {
        MobileAdapterSerialEndpoint endpoint = endpoint();
        byte[] writeRequest = packet(0x1a, new byte[]{7, 0x55});

        assertNull(endpoint.latestGuestConfigurationMutation());
        for (int i = 0; i < writeRequest.length - 1; i++) {
            assertEquals(0xd2, exchange(endpoint, writeRequest[i] & 0xff));
            assertNull(endpoint.latestGuestConfigurationMutation());
        }
        assertEquals(0xd2, exchange(endpoint, writeRequest[writeRequest.length - 1] & 0xff));

        MobileAdapterEngine.GuestConfigurationMutation mutation =
                endpoint.latestGuestConfigurationMutation();
        assertNotNull(mutation);
        assertEquals(1, mutation.revision());
        assertArrayEquals(endpoint.configurationCopy(), mutation.configuration());
        byte[] detached = mutation.configuration();
        detached[7] = 0;
        assertEquals(0x55,
                endpoint.latestGuestConfigurationMutation().configuration()[7] & 0xff);

        ComponentState<eu.rekawek.coffeegb.core.serial.SerialEndpoint> captured =
                endpoint.captureState();
        MobileAdapterSerialEndpoint restored = endpoint();
        restored.restoreState(captured);
        assertEquals(0x55, restored.configurationCopy()[7] & 0xff);
        assertNull(restored.latestGuestConfigurationMutation());

        assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
        assertEquals(0x9a, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(1, endpoint.latestGuestConfigurationMutation().revision());
        assertArrayEquals(mutation.configuration(),
                endpoint.latestGuestConfigurationMutation().configuration());

        endpoint.disconnect();
        assertEquals(1, endpoint.latestGuestConfigurationMutation().revision());
        assertArrayEquals(mutation.configuration(),
                endpoint.latestGuestConfigurationMutation().configuration());
    }

    @Test
    public void wireStateRoundTripsAtEveryPhaseIncludingTheMiddleOfAResponseByte() {
        MobileAdapterSerialEndpoint endpoint = endpoint();
        for (byte requestByte : BEGIN) exchange(endpoint, requestByte & 0xff);

        endpoint = roundTripWireState(endpoint, 2);
        assertEquals(0x88, exchange(endpoint, 0x80));
        endpoint = roundTripWireState(endpoint, 3);
        assertEquals(0x90, exchange(endpoint, 0x00));
        endpoint = roundTripWireState(endpoint, 4);
        assertEquals(0xd2, exchange(endpoint, 0x00));
        endpoint = roundTripWireState(endpoint, 5);
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        endpoint = roundTripWireState(endpoint, 9);

        endpoint.setSb(0x4b);
        endpoint.startSending();
        assertEquals(1, endpoint.sendBit());
        assertEquals(0, endpoint.sendBit());
        assertEquals(0, endpoint.sendBit());
        endpoint = roundTripWireState(endpoint, 6);
        int firstResponseByte = 0b100;
        for (int bit = 3; bit < 8; bit++) {
            firstResponseByte = firstResponseByte << 1 | endpoint.sendBit();
        }
        assertEquals(0x99, firstResponseByte);

        for (int i = 1; i < BEGIN_RESPONSE.length; i++) {
            assertEquals(BEGIN_RESPONSE[i] & 0xff, exchange(endpoint, 0x4b));
        }
        endpoint = roundTripWireState(endpoint, 7);
        assertEquals(0x88, exchange(endpoint, 0x80));
        endpoint = roundTripWireState(endpoint, 8);
        assertEquals(0x00, exchange(endpoint, 0x10));

        assertTrue(endpoint.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
    }

    @Test
    public void releasedEndpointStateWithACompletedPacketRetainsItsIdleWireBehavior() {
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration());
        for (byte value : BEGIN) engine.acceptByte(value & 0xff);
        MobileAdapterEngine.MobileAdapterEngineState engineState =
                (MobileAdapterEngine.MobileAdapterEngineState) engine.captureState();
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState releasedState =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState(
                        engineState, 0x00, 0);

        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, 0, new byte[256]);
        endpoint.restoreState(releasedState);

        assertEquals(0xd2, exchange(endpoint, 0x4b));
        assertEquals(0, endpoint.snapshot().retainedBytes());
        assertArrayEquals(BEGIN_RESPONSE, endpoint.snapshot().responsePacket());
    }

    @Test
    public void releasedMidByteStateFinishesTheHistoricalIdleHighReply() {
        MobileAdapterEngine engine = new MobileAdapterEngine(
                ClockSpec.LEGACY, DEVICE_ID, configuration());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState releasedState =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState(
                        (MobileAdapterEngine.MobileAdapterEngineState) engine.captureState(),
                        0x99,
                        3);

        MobileAdapterSerialEndpoint endpoint = endpoint();
        endpoint.restoreState(releasedState);

        for (int bit = 3; bit < 8; bit++) assertEquals(1, endpoint.sendBit());
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void newMidRequestByteUsesTheAdditiveWireStateAndContinuesD2() {
        MobileAdapterSerialEndpoint endpoint = endpoint();
        endpoint.setSb(0x99);
        endpoint.startSending();
        int reply = 0;
        for (int bit = 0; bit < 3; bit++) reply = reply << 1 | endpoint.sendBit();

        ComponentState<eu.rekawek.coffeegb.core.serial.SerialEndpoint> captured =
                endpoint.captureState();
        assertTrue(captured instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState);
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState wireState =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState) captured;
        assertEquals(1, wireState.wirePhaseId());

        MobileAdapterSerialEndpoint restored = endpoint();
        restored.restoreState(wireState);
        for (int bit = 3; bit < 8; bit++) reply = reply << 1 | restored.sendBit();
        assertEquals(0xd2, reply);
        assertEquals(1, restored.snapshot().retainedBytes());
    }

    @Test
    public void midRequestByteStillContinuesD2WhenOpenBackendOwnershipIsNormalized() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        assertArrayEquals(packet(0xa3, new byte[]{0}), readResponse(endpoint));
        assertTrue(endpoint.hasExternalIo());

        endpoint.setSb(0x99);
        endpoint.startSending();
        int reply = 0;
        for (int bit = 0; bit < 3; bit++) reply = reply << 1 | endpoint.sendBit();
        ComponentState<eu.rekawek.coffeegb.core.serial.SerialEndpoint> captured =
                endpoint.captureState();
        assertTrue(captured instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState);
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState wireState =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState) captured;
        assertTrue(wireState.engineState() instanceof
                MobileAdapterEngine.MobileAdapterEngineNetworkState);

        MobileAdapterSerialEndpoint restored = endpoint();
        restored.restoreState(wireState);
        for (int bit = 3; bit < 8; bit++) reply = reply << 1 | restored.sendBit();
        assertEquals(0xd2, reply);
        assertEquals(1, restored.snapshot().retainedBytes());
        assertTrue(!restored.hasExternalIo());
    }

    @Test
    public void externalCaptureFinishesOnlyTheLatchedResponseByteThenDisconnects() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));

        endpoint.setSb(0x4b);
        endpoint.startSending();
        int reply = 0;
        for (int bit = 0; bit < 3; bit++) reply = reply << 1 | endpoint.sendBit();

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState normalized =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();
        assertEquals(11, normalized.wirePhaseId());
        assertTrue(normalized.engineState() instanceof
                MobileAdapterEngine.MobileAdapterEngineNetworkState);
        assertArrayEquals(new byte[0], normalized.requestAcknowledgement());
        assertArrayEquals(new byte[0], normalized.responsePacket());

        MobileAdapterSerialEndpoint restored = endpoint();
        restored.restoreState(normalized);
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState recaptured =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        restored.captureState();
        assertEquals(11, recaptured.wirePhaseId());
        assertTrue(recaptured.engineState() instanceof
                MobileAdapterEngine.MobileAdapterEngineState);

        MobileAdapterSerialEndpoint restoredAgain = endpoint();
        restoredAgain.restoreState(recaptured);
        for (int bit = 3; bit < 8; bit++) reply = reply << 1 | restoredAgain.sendBit();
        assertEquals(0x99, reply);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restoredAgain.snapshot().outcome());
        assertTrue(restoredAgain.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
        assertEquals(0xd2, exchange(restoredAgain, 0x99));
        assertEquals(1, restoredAgain.snapshot().retainedBytes());
    }

    @Test
    public void stalledTransferTimesOutBackendOwnershipWithoutReplacingItsLatchedReply() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertTrue(endpoint.hasExternalIo());

        endpoint.setSb(0);
        endpoint.startSending();
        for (int tick = 0; tick <= 3_000; tick++) endpoint.tick();

        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                endpoint.snapshot().outcome());
        assertTrue(!endpoint.hasExternalIo());
        assertEquals(0, backend.occupiedRequestSlots());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState timedOut =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();
        assertEquals(11, timedOut.wirePhaseId());
        assertEquals(0xd2, timedOut.currentReply());

        // A fresh SC start abandons the stalled byte and begins a clean request transfer.
        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void freshByteStartClearsCleanupAndIdleBoundaryOutcomesBeforeCapture() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        MobileAdapterSerialEndpoint cancelled = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        cancelled.disconnect();
        cancelled.setSb(0x99);
        cancelled.startSending();
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, cancelled.snapshot().outcome());
        MobileAdapterSerialEndpoint restoredCancelled = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        restoredCancelled.restoreState(cancelled.captureState());

        MobileAdapterSerialEndpoint boundary = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        assertEquals(0xd2, exchange(boundary, 0x99));
        for (int tick = 0; tick < 3_000; tick++) boundary.tick();
        assertEquals(MobileAdapterEngine.Outcome.IDLE_BOUNDARY_WAIT,
                boundary.snapshot().outcome());
        boundary.setSb(0x66);
        boundary.startSending();
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, boundary.snapshot().outcome());
        MobileAdapterSerialEndpoint restoredBoundary = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        restoredBoundary.restoreState(boundary.captureState());
    }

    @Test
    public void releasedIndexZeroNetworkStateKeepsItsOneShotFfReplyAcrossPolling() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        assertArrayEquals(packet(0xa3, new byte[]{0}), readResponse(endpoint));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointNetworkState released =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointNetworkState)
                        endpoint.captureState();
        assertEquals(0, released.sendBitIndex());

        MobileAdapterSerialEndpoint restored = endpoint();
        restored.restoreState(released);
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                restored.pollBackendCompletion().outcome());
        int reply = 0;
        for (int bit = 0; bit < 8; bit++) reply = reply << 1 | restored.sendBit();
        assertEquals(0xff, reply);

        MobileAdapterSerialEndpoint restarted = endpoint();
        restarted.restoreState(released);
        assertEquals(0xd2, exchange(restarted, 0x99));
    }

    @Test
    public void wireRestoreRejectsAckOrResponseDetachedFromItsEngineResult() {
        MobileAdapterSerialEndpoint endpoint = endpoint();
        for (byte requestByte : BEGIN) exchange(endpoint, requestByte & 0xff);
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState state =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState detachedAck =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), state.sendBitIndex(),
                        state.byteTransferActive(), state.wirePhaseId(),
                        state.currentReply(), new byte[]{(byte) 0x88, (byte) 0x91},
                        state.responsePacket(), state.responseByteIndex(), state.awaitingResponse(),
                        state.responseRetryCount());
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(detachedAck));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState detachedResponse =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), state.sendBitIndex(),
                        state.byteTransferActive(), state.wirePhaseId(),
                        state.currentReply(), state.requestAcknowledgement(),
                        packet(0x91, new byte[0]), state.responseByteIndex(), state.awaitingResponse(),
                        state.responseRetryCount());
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(detachedResponse));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState inconsistentOwnership =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), state.sendBitIndex(),
                        state.byteTransferActive(), state.wirePhaseId(),
                        state.currentReply(), state.requestAcknowledgement(), state.responsePacket(),
                        state.responseByteIndex(), false, state.responseRetryCount());
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(inconsistentOwnership));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState prematureCursor =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), state.sendBitIndex(),
                        state.byteTransferActive(), state.wirePhaseId(),
                        state.currentReply(), state.requestAcknowledgement(), state.responsePacket(),
                        1, state.awaitingResponse(), state.responseRetryCount());
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(prematureCursor));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState excessiveRetries =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), state.sendBitIndex(),
                        state.byteTransferActive(), state.wirePhaseId(),
                        state.currentReply(), state.requestAcknowledgement(), state.responsePacket(),
                        state.responseByteIndex(), state.awaitingResponse(), 5);
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(excessiveRetries));

        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState inactiveCursor =
                new MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState(
                        state.engineState(), state.sb(), 3, false, state.wirePhaseId(),
                        state.currentReply(), state.requestAcknowledgement(), state.responsePacket(),
                        state.responseByteIndex(), state.awaitingResponse(),
                        state.responseRetryCount());
        assertThrows(IllegalArgumentException.class,
                () -> endpoint().restoreState(inactiveCursor));
    }

    @Test
    public void responseErrorsRetryFourTimesThenAbortTheWireTransaction() {
        MobileAdapterSerialEndpoint endpoint = endpoint();
        sendRequestAndReadAcknowledgement(endpoint, BEGIN);

        for (int retry = 0; retry <= 4; retry++) {
            assertArrayEquals(BEGIN_RESPONSE, readResponsePacket(endpoint));
            assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
            assertEquals(0, exchange(endpoint, 0xf0 + retry % 3));
            if (retry < 4) {
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState state =
                        (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                                endpoint.captureState();
                assertEquals(9, state.wirePhaseId());
                assertEquals(retry + 1, state.responseRetryCount());
            }
        }

        assertTrue(endpoint.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void responsePollingKeepsAPendingTransactionAlive() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration(), backend);

        byte[] dial = packet(0x12, new byte[]{0, '#', '9', '6', '7', '7'});
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        assertArrayEquals(packet(0x92, new byte[0]), transaction(endpoint, dial));
        assertArrayEquals(packet(0xa1, new byte[]{
                        127, 0, 0, 1,
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                }),
                transaction(endpoint, packet(0x21, new byte[]{
                        0, 0,
                        1, 1, 1, 1,
                        8, 8, 8, 8,
                })));

        byte[] dnsRequest = packet(
                0x28, "service.test".getBytes(StandardCharsets.US_ASCII));
        sendRequestAndReadAcknowledgement(endpoint, dnsRequest);
        for (int poll = 0; poll < 4; poll++) {
            for (int tick = 0; tick < 2_999; tick++) endpoint.tick();
            assertEquals(0xd2, exchange(endpoint, 0x4b));
            assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                    endpoint.snapshot().outcome());
        }
        assertEquals(1, backend.pendingRequests());

        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{127, 0, 0, 1}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState completedState =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();
        assertEquals(9, completedState.wirePhaseId());
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0xa8},
                completedState.requestAcknowledgement());
        assertEquals(0,
                ((MobileAdapterEngine.MobileAdapterEngineState) completedState.engineState())
                        .acknowledgement().length);
        MobileAdapterSerialEndpoint restored = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, 0, new byte[MobileAdapterEngine.CONFIGURATION_BYTES]);
        restored.restoreState(completedState);
        assertResultsEqual(endpoint.snapshot(), restored.snapshot());
        endpoint = restored;
        assertArrayEquals(
                packet(0xa8, new byte[]{127, 0, 0, 1}),
                readResponse(endpoint));
    }

    @Test
    public void idleTimeoutClearsEveryActiveWirePhaseWithTheEngine() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        for (byte requestByte : BEGIN) exchange(endpoint, requestByte & 0xff);

        assertTimeoutClearsWireState(clock, endpoint, 2);
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertTimeoutClearsWireState(clock, endpoint, 3);
        assertEquals(0x90, exchange(endpoint, 0));
        assertTimeoutClearsWireState(clock, endpoint, 4);
        assertEquals(0xd2, exchange(endpoint, 0));
        assertTimeoutClearsWireState(clock, endpoint, 5);
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        assertTimeoutClearsWireState(clock, endpoint, 9);
        assertEquals(0x99, exchange(endpoint, 0x4b));
        assertTimeoutClearsWireState(clock, endpoint, 6);
        for (int index = 1; index < BEGIN_RESPONSE.length; index++) {
            assertEquals(BEGIN_RESPONSE[index] & 0xff, exchange(endpoint, 0x4b));
        }
        assertTimeoutClearsWireState(clock, endpoint, 7);
        assertEquals(0x88, exchange(endpoint, 0x80));
        assertTimeoutClearsWireState(clock, endpoint, 8);
    }

    @Test
    public void backendRevocationDropsAResponseAlreadyBeingStreamed() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        byte[] dial = packet(0x12, new byte[]{0, '#', '9', '6', '7', '7'});
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        assertArrayEquals(packet(0x92, new byte[0]), transaction(endpoint, dial));
        assertArrayEquals(packet(0xa1, new byte[]{
                        127, 0, 0, 1,
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                }),
                transaction(endpoint, packet(0x21, new byte[]{
                        0, 0,
                        1, 1, 1, 1,
                        8, 8, 8, 8,
                })));

        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0x1f, (byte) 0x90}));
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        assertEquals(0x99, exchange(endpoint, 0x4b));

        backend.cancelAll();
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                endpoint.pollBackendCompletion().outcome());
        assertTrue(endpoint.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
        assertArrayEquals(new byte[0], endpoint.snapshot().responsePacket());
        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void backendRevocationWaitsForTheLatchedResponseByteToFinish() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x23, new byte[]{127, 0, 0, 1, 0, 80}));
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{0}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.pollBackendCompletion().outcome());
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));

        endpoint.setSb(0x4b);
        endpoint.startSending();
        int firstResponseByte = 0;
        for (int bit = 0; bit < 3; bit++) {
            firstResponseByte = firstResponseByte << 1 | endpoint.sendBit();
        }
        backend.cancelAll();
        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                endpoint.pollBackendCompletion().outcome());
        for (int bit = 3; bit < 8; bit++) {
            firstResponseByte = firstResponseByte << 1 | endpoint.sendBit();
        }
        assertEquals(0x99, firstResponseByte);

        assertEquals(MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED,
                endpoint.pollBackendCompletion().outcome());
        assertTrue(endpoint.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(1, endpoint.snapshot().retainedBytes());
    }

    @Test
    public void timeoutCannotReplaceAReplyLatchedForAnActiveByte() {
        ClockSpec clock = new ClockSpec(1_000, 60, 1);
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                clock, DEVICE_ID, configuration());
        sendRequestAndReadAcknowledgement(endpoint, BEGIN);
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        for (int tick = 0; tick < 2_999; tick++) endpoint.tick();

        endpoint.setSb(0x4b);
        endpoint.startSending();
        int firstResponseByte = 0;
        for (int bit = 0; bit < 3; bit++) {
            firstResponseByte = firstResponseByte << 1 | endpoint.sendBit();
        }
        for (int tick = 0; tick <= 3_000; tick++) endpoint.tick();
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                endpoint.snapshot().outcome());
        for (int bit = 3; bit < 8; bit++) {
            firstResponseByte = firstResponseByte << 1 | endpoint.sendBit();
        }
        assertEquals(0x99, firstResponseByte);
        assertTrue(endpoint.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);

        for (int tick = 0; tick <= 3_000; tick++) endpoint.tick();
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                endpoint.snapshot().outcome());
        assertTrue(endpoint.snapshot().responsePacket().length == 0);
    }

    @Test
    public void pendingWirePollConsumesAnAsynchronousDnsReplyAtTheNextByteBoundary() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);

        byte[] dial = packet(0x12, new byte[]{0, '#', '9', '6', '7', '7'});
        assertArrayEquals(packet(0x6e, new byte[]{0x12, 0x01}), transaction(endpoint, dial));
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        assertArrayEquals(packet(0x92, new byte[0]), transaction(endpoint, dial));
        assertArrayEquals(packet(0xa1, new byte[]{
                        127, 0, 0, 1,
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                }),
                transaction(endpoint, packet(0x21, new byte[]{
                        3, 'u', 's', 'r',
                        3, 'p', 'w', 'd',
                        1, 1, 1, 1,
                        8, 8, 8, 8,
                })));

        byte[] dnsRequest = packet(
                0x28, "service.test".getBytes(StandardCharsets.US_ASCII));
        sendRequestAndReadAcknowledgement(endpoint, dnsRequest);
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        assertEquals(0xd2, exchange(endpoint, 0x4b));
        assertEquals(1, backend.pendingRequests());

        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{127, 0, 0, 1}));
        assertEquals(1, backend.completedResults());
        assertArrayEquals(
                packet(0xa8, new byte[]{127, 0, 0, 1}),
                readResponse(endpoint));
        assertEquals(0, backend.completedResults());
    }

    @Test
    public void backendCompletionInTheMiddleOfAPendingPollFinishesTheLatchedD2Byte() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));

        endpoint.setSb(0x4b);
        endpoint.startSending();
        int pendingReply = 0;
        for (int bit = 0; bit < 3; bit++) {
            pendingReply = pendingReply << 1 | endpoint.sendBit();
        }
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{127, 0, 0, 1}));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                endpoint.snapshot().outcome());
        for (int bit = 3; bit < 8; bit++) {
            pendingReply = pendingReply << 1 | endpoint.sendBit();
        }
        assertEquals(0xd2, pendingReply);
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_PENDING,
                endpoint.snapshot().outcome());

        // The next byte boundary, rather than a test-only controller poll, consumes the result.
        byte[] expected = packet(0xa8, new byte[]{127, 0, 0, 1});
        assertEquals(expected[0] & 0xff, exchange(endpoint, 0x4b));
        assertEquals(MobileAdapterEngine.Outcome.BACKEND_RESPONSE,
                endpoint.snapshot().outcome());
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState streaming =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        endpoint.captureState();
        assertEquals(6, streaming.wirePhaseId());
        assertEquals(1, streaming.responseByteIndex());

        for (int index = 1; index < expected.length; index++) {
            assertEquals(expected[index] & 0xff, exchange(endpoint, 0x4b));
        }
        assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
        assertEquals(0, exchange(endpoint, 0x28));
    }

    @Test
    public void completedBackendRequestLeavesOneRuntimeOnlyHistoryFence() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        assertTrue(!endpoint.consumeExternalIoActivity());

        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(endpoint.hasExternalIo());
        assertEquals(MobileAdapterBackendPort.CompletionResult.COMPLETED,
                backend.complete(
                        backend.generation(),
                        0,
                        MobileAdapterBackendPort.BackendStatus.SUCCESS,
                        new byte[]{127, 0, 0, 1}));
        assertArrayEquals(
                packet(0xa8, new byte[]{127, 0, 0, 1}),
                readResponse(endpoint));
        assertTrue(!endpoint.hasExternalIo());
        assertEquals(0, backend.completedResults());
        assertTrue(endpoint.consumeExternalIoActivity());
        assertTrue(!endpoint.consumeExternalIoActivity());

        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));
        endpoint.disconnect();
        assertTrue(!endpoint.hasExternalIo());
        assertTrue(endpoint.consumeExternalIoActivity());
        assertTrue(!endpoint.consumeExternalIoActivity());
    }

    @Test
    public void successfulRestoreStartsWithNoRuntimeOnlyHistoryFence() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));

        MobileAdapterSerialEndpoint restored = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        restored.restoreState(endpoint.captureState());

        assertTrue(!restored.consumeExternalIoActivity());
        assertTrue(endpoint.consumeExternalIoActivity());
    }

    @Test
    public void revokedPendingGenerationAutoPollsToIdleAndAcceptsFreshMagic() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(0xd2, exchange(endpoint, 0x4b));

        backend.cancelAll();

        assertEquals(0xd2, exchange(endpoint, 0x99));
        assertEquals(MobileAdapterEngine.Outcome.NEED_MORE, endpoint.snapshot().outcome());
        assertEquals(1, endpoint.snapshot().retainedBytes());
        assertEquals(0, backend.completedResults());
    }

    @Test
    public void invalidResponseGateRevokesBackendAndResynchronizesOnRequestMagic() {
        DeterministicMobileAdapterBackend backend = new DeterministicMobileAdapterBackend();
        MobileAdapterSerialEndpoint endpoint = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, DEVICE_ID, configuration(), backend);
        byte[] dial = packet(0x12, new byte[]{0, '#', '9', '6', '7', '7'});
        assertArrayEquals(BEGIN_RESPONSE, transaction(endpoint, BEGIN));
        assertArrayEquals(packet(0x92, new byte[0]), transaction(endpoint, dial));
        assertArrayEquals(packet(0xa1, new byte[]{
                        127, 0, 0, 1,
                        0, 0, 0, 0,
                        0, 0, 0, 0,
                }),
                transaction(endpoint, packet(0x21, new byte[]{
                        0, 0,
                        1, 1, 1, 1,
                        8, 8, 8, 8,
                })));

        sendRequestAndReadAcknowledgement(
                endpoint, packet(0x28, "service.test".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0xd2, exchange(endpoint, 0));
        assertEquals(1, backend.pendingRequests());

        byte[] status = packet(0x17, new byte[0]);
        assertEquals(0xd2, exchange(endpoint, status[0] & 0xff));
        assertEquals(0, backend.pendingRequests());
        assertTrue(!endpoint.hasExternalIo());
        assertEquals(1, endpoint.snapshot().retainedBytes());
        for (int index = 1; index < status.length; index++) {
            assertEquals(0xd2, exchange(endpoint, status[index] & 0xff));
        }
        assertArrayEquals(packet(0x97, new byte[]{4, 0x4d, 0}), readResponseAfterRequest(endpoint));
    }

    private static MobileAdapterSerialEndpoint roundTripWireState(
            MobileAdapterSerialEndpoint endpoint, int expectedPhase) {
        ComponentState<eu.rekawek.coffeegb.core.serial.SerialEndpoint> captured =
                endpoint.captureState();
        assertTrue(captured instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState);
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState wireState =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState) captured;
        assertEquals(expectedPhase, wireState.wirePhaseId());

        byte[] detachedAck = wireState.requestAcknowledgement();
        detachedAck[0] = 0;
        assertArrayEquals(new byte[]{(byte) 0x88, (byte) 0x90},
                wireState.requestAcknowledgement());
        byte[] detachedResponse = wireState.responsePacket();
        if (detachedResponse.length != 0) detachedResponse[0] = 0;
        assertArrayEquals(BEGIN_RESPONSE, wireState.responsePacket());

        MobileAdapterSerialEndpoint restored = new MobileAdapterSerialEndpoint(
                ClockSpec.LEGACY, 0, new byte[256]);
        restored.restoreState(wireState);
        assertResultsEqual(endpoint.snapshot(), restored.snapshot());
        return restored;
    }

    private static void assertTimeoutClearsWireState(
            ClockSpec clock,
            MobileAdapterSerialEndpoint source,
            int expectedPhase) {
        MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState state =
                (MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointWireState)
                        source.captureState();
        assertEquals(expectedPhase, state.wirePhaseId());
        MobileAdapterSerialEndpoint timedOut = new MobileAdapterSerialEndpoint(
                clock, 0, new byte[MobileAdapterEngine.CONFIGURATION_BYTES]);
        timedOut.restoreState(state);
        for (int tick = 0; tick <= 3_000; tick++) timedOut.tick();

        assertEquals(MobileAdapterEngine.Phase.SLEEP, timedOut.snapshot().phase());
        assertEquals(MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET,
                timedOut.snapshot().outcome());
        assertTrue(timedOut.captureState() instanceof
                MobileAdapterSerialEndpoint.MobileAdapterSerialEndpointState);
        assertEquals(0xd2, exchange(timedOut, 0x99));
        assertEquals(1, timedOut.snapshot().retainedBytes());
    }

    private static MobileAdapterSerialEndpoint endpoint() {
        return new MobileAdapterSerialEndpoint(ClockSpec.LEGACY, DEVICE_ID, configuration());
    }

    private static byte[] transaction(MobileAdapterSerialEndpoint endpoint, byte[] request) {
        sendRequestAndReadAcknowledgement(endpoint, request);
        return readResponse(endpoint);
    }

    private static byte[] readResponseAfterRequest(MobileAdapterSerialEndpoint endpoint) {
        assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
        assertEquals(0x97, exchange(endpoint, 0));
        return readResponse(endpoint);
    }

    private static void sendRequestAndReadAcknowledgement(
            MobileAdapterSerialEndpoint endpoint, byte[] request) {
        for (byte requestByte : request) {
            assertEquals(0xd2, exchange(endpoint, requestByte & 0xff));
        }
        assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
        assertEquals((request[2] & 0xff) ^ 0x80, exchange(endpoint, 0));
    }

    private static byte[] readResponse(MobileAdapterSerialEndpoint endpoint) {
        byte[] response = readResponsePacket(endpoint);
        assertEquals(DEVICE_ID | 0x80, exchange(endpoint, 0x80));
        assertEquals(0, exchange(endpoint, (response[2] & 0xff) ^ 0x80));
        return response;
    }

    private static byte[] readResponsePacket(MobileAdapterSerialEndpoint endpoint) {
        int first = 0xd2;
        for (int poll = 0; poll < 8 && first == 0xd2; poll++) {
            first = exchange(endpoint, 0x4b);
        }
        assertEquals(0x99, first);

        byte[] header = new byte[6];
        header[0] = (byte) first;
        for (int i = 1; i < header.length; i++) {
            header[i] = (byte) exchange(endpoint, 0x4b);
        }
        int dataLength = (header[4] & 0xff) << 8 | header[5] & 0xff;
        byte[] response = new byte[dataLength + 8];
        System.arraycopy(header, 0, response, 0, header.length);
        for (int i = header.length; i < response.length; i++) {
            response[i] = (byte) exchange(endpoint, 0x4b);
        }

        return response;
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
        byte[] result = new byte[data.length + 8];
        result[0] = (byte) 0x99;
        result[1] = 0x66;
        result[2] = (byte) command;
        result[4] = (byte) (data.length >>> 8);
        result[5] = (byte) data.length;
        System.arraycopy(data, 0, result, 6, data.length);
        int checksum = 0;
        for (int i = 2; i < 6 + data.length; i++) {
            checksum = checksum + (result[i] & 0xff) & 0xffff;
        }
        result[6 + data.length] = (byte) (checksum >>> 8);
        result[7 + data.length] = (byte) checksum;
        return result;
    }

    private static byte[] configuration() {
        byte[] result = new byte[MobileAdapterEngine.CONFIGURATION_BYTES];
        result[0] = 0x4d;
        result[1] = 0x41;
        result[2] = (byte) 0x81;
        return result;
    }

    private static void assertResultsEqual(MobileAdapterEngine.EngineResult expected,
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
