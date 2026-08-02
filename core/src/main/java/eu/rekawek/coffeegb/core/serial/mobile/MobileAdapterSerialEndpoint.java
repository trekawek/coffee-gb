package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;

import java.util.Arrays;
import java.util.Objects;

/**
 * Link-port wrapper for the deterministic Mobile Adapter protocol engine.
 *
 * <p>The Mobile Adapter exchange is byte-pipelined. While the Game Boy clocks a request packet
 * out, the adapter returns {@code D2}. The following two bytes acknowledge that request. The Game
 * Boy then polls with {@code 4B} until the adapter starts a response packet, after which the two
 * devices exchange a second two-byte acknowledgement. This endpoint owns that wire schedule while
 * {@link MobileAdapterEngine} remains responsible for packet and command semantics.
 */
public final class MobileAdapterSerialEndpoint implements SerialEndpoint {

    private static final int IDLE_BYTE = 0xd2;

    private static final int POLL_BYTE = 0x4b;

    /** Initial response plus at most four bounded retransmissions. */
    private static final int MAX_RESPONSE_RETRIES = 4;

    private final MobileAdapterEngine engine;

    private int sb = 0xff;

    private int sendBitIndex;

    /** Distinguishes a byte boundary from a transfer whose first bit has not been clocked yet. */
    private boolean byteTransferActive;

    /** One-shot released-state reply whose index-zero activity was historically ambiguous. */
    private boolean legacyReplyLatched;

    /** Reply byte latched when the current Game Boy byte transfer starts. */
    private int currentReply = IDLE_BYTE;

    private WirePhase wirePhase = WirePhase.RECEIVE_REQUEST;

    private byte[] requestAcknowledgement = new byte[0];

    private byte[] responsePacket = new byte[0];

    /** Index of the response byte currently being streamed, or the next byte between transfers. */
    private int responseByteIndex;

    /** True for a synchronous response or while a backend response is still pending. */
    private boolean awaitingResponse;

    /** Number of response retransmissions already scheduled for this transaction. */
    private int responseRetryCount;

    /** Runtime-only history fence set when a backend request crosses the host-I/O boundary. */
    private transient boolean externalIoActivityObserved;

    public MobileAdapterSerialEndpoint(ClockSpec clockSpec, int deviceId, byte[] configuration) {
        this(clockSpec, deviceId, configuration, MobileAdapterBackendPort.DISCONNECTED);
    }

    public MobileAdapterSerialEndpoint(ClockSpec clockSpec, int deviceId, byte[] configuration,
                                       MobileAdapterBackendPort backendPort) {
        engine = new MobileAdapterEngine(clockSpec, deviceId, configuration, backendPort);
    }

    @Override
    public void tick() {
        if (engine.tickAndReportTimeoutReset()) normalizeCurrentByteOrReset();
    }

    @Override
    public void disconnect() {
        engine.cancelOrReplace();
        sb = 0xff;
        sendBitIndex = 0;
        byteTransferActive = false;
        resetWireState();
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb & 0xff;
    }

    @Override
    public int recvBit() {
        return -1;
    }

    @Override
    public void startSending() {
        // SC may be cleared or restarted before a byte receives eight clocks. A new start replaces
        // that abandoned byte; a normalized disconnect must not leak its one-shot reply.
        if (byteTransferActive || legacyReplyLatched) {
            byteTransferActive = false;
            legacyReplyLatched = false;
            if (wirePhase == WirePhase.NORMALIZED_DISCONNECT) resetWireState();
        }
        // CGB fast serial can clock hundreds of bytes before the controller's next frame safe
        // point. Once the validated response gate is pending, consume one already-published
        // completion at this byte boundary so the guest cannot outrun a DNS/socket reply. This
        // atomic port poll performs no host I/O and cannot replace a reply byte already in flight.
        if (wirePhase == WirePhase.RESPONSE_PENDING && responsePacket.length == 0) {
            pollBackendCompletion();
        }
        sendBitIndex = 0;
        byteTransferActive = true;
        engine.observeSerialActivity();
        if (wirePhase == WirePhase.RESPONSE_PENDING && responsePacket.length != 0) {
            wirePhase = WirePhase.RESPONSE_READY;
        }
        if (wirePhase == WirePhase.RESPONSE_READY) {
            wirePhase = WirePhase.RESPONSE_STREAM;
        }
        currentReply = replyForCurrentPhase();
    }

    @Override
    public int sendBit() {
        // Released endpoint records could not distinguish an idle bit index of zero from an armed
        // transfer before its first edge. Preserve their latched FF byte until either it finishes
        // or a new start replaces it.
        if (legacyReplyLatched) byteTransferActive = true;
        int bit = currentReply >>> (7 - sendBitIndex) & 1;
        sendBitIndex = (sendBitIndex + 1) & 7;
        if (sendBitIndex == 0) {
            byteTransferActive = false;
            legacyReplyLatched = false;
            completeByteTransfer();
        }
        return bit;
    }

    public MobileAdapterEngine.EngineResult snapshot() {
        return engine.snapshot();
    }

    /** Applies at most one already-published backend result without performing host I/O or waiting. */
    public MobileAdapterEngine.EngineResult pollBackendCompletion() {
        MobileAdapterEngine.EngineResult before = engine.snapshot();
        MobileAdapterEngine.EngineResult result = engine.pollBackendCompletion();
        boolean ownershipRevoked =
                result.outcome() != before.outcome() &&
                        (result.outcome() == MobileAdapterEngine.Outcome.CANCELLED ||
                                result.outcome() ==
                                        MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED);
        if (ownershipRevoked) {
            // A generation/authority boundary invalidates every cached wire byte, including a
            // response already being streamed or acknowledged. Finish only the byte whose reply
            // was already latched; backend ownership itself is revoked immediately.
            normalizeCurrentByteOrReset();
            return result;
        }
        byte[] response = result.responsePacket();
        if (awaitingResponse && responsePacket.length == 0 && response.length != 0) {
            responsePacket = response;
            responseByteIndex = 0;
            if (wirePhase == WirePhase.RESPONSE_PENDING && !byteTransferActive) {
                wirePhase = WirePhase.RESPONSE_READY;
            }
        }
        return result;
    }

    /** Runtime-only ownership hint for controller warnings; it is never captured as a host handle. */
    public boolean hasExternalIo() {
        return engine.hasExternalIo();
    }

    /**
     * Consumes the owner-thread history fence for backend work admitted since the previous
     * controller boundary. The fence is deliberately distinct from {@link #hasExternalIo()}:
     * a fast request can complete before the frame ends, but its host interaction cannot be
     * replayed from a portable or rewind snapshot.
     */
    public boolean consumeExternalIoActivity() {
        boolean observed = externalIoActivityObserved;
        externalIoActivityObserved = false;
        return observed;
    }

    public byte[] configurationCopy() {
        return engine.configurationCopy();
    }

    /** Returns the latest runtime-only changed guest write as a defensive snapshot, if any. */
    public MobileAdapterEngine.GuestConfigurationMutation latestGuestConfigurationMutation() {
        return engine.latestGuestConfigurationMutation();
    }

    public void replaceConfiguration(byte[] replacement) {
        engine.replaceConfiguration(replacement);
    }

    public boolean reservePendingPacketSlot() {
        return engine.reservePendingPacketSlot();
    }

    public void completePendingPacketSlot() {
        engine.completePendingPacketSlot();
    }

    private int replyForCurrentPhase() {
        return switch (wirePhase) {
            case RECEIVE_REQUEST, RESPONSE_TURNAROUND, RESPONSE_WAIT, RESPONSE_PENDING ->
                    IDLE_BYTE;
            case REQUEST_ACK_DEVICE, RESPONSE_ACK_DEVICE ->
                    requestAcknowledgement[0] & 0xff;
            case REQUEST_ACK_COMMAND -> requestAcknowledgement[1] & 0xff;
            case RESPONSE_STREAM -> responsePacket[responseByteIndex] & 0xff;
            case RESPONSE_ACK_COMMAND -> 0;
            case RESPONSE_READY, NORMALIZED_DISCONNECT -> throw new IllegalStateException(
                    "Response-ready phase must enter streaming before latching a byte");
        };
    }

    private void completeByteTransfer() {
        switch (wirePhase) {
            case RECEIVE_REQUEST -> acceptRequestByte();
            case REQUEST_ACK_DEVICE -> wirePhase = WirePhase.REQUEST_ACK_COMMAND;
            case REQUEST_ACK_COMMAND -> {
                if (awaitingResponse) {
                    // Real adapters return at least one D2 turnaround byte before response magic.
                    wirePhase = WirePhase.RESPONSE_TURNAROUND;
                } else {
                    resetWireState();
                }
            }
            case RESPONSE_TURNAROUND -> wirePhase = WirePhase.RESPONSE_WAIT;
            case RESPONSE_WAIT -> {
                // After the skipped turnaround byte, exactly one 4B admits command processing.
                // Invalid input aborts the transaction; 99 may immediately resynchronize as the
                // first byte of the next request packet.
                if (sb != POLL_BYTE) {
                    engine.abortWireTransaction();
                    resetWireState();
                    if (sb == 0x99) acceptRequestByte();
                } else {
                    wirePhase = responsePacket.length == 0 ?
                            WirePhase.RESPONSE_PENDING : WirePhase.RESPONSE_READY;
                }
            }
            case RESPONSE_PENDING -> {
                // The gate has already been validated. Further Game Boy bytes only keep the wire
                // alive while the controller-owned backend publishes its bounded completion.
                if (responsePacket.length != 0) wirePhase = WirePhase.RESPONSE_READY;
            }
            case RESPONSE_STREAM -> {
                responseByteIndex++;
                if (responseByteIndex == responsePacket.length) {
                    wirePhase = WirePhase.RESPONSE_ACK_DEVICE;
                }
            }
            case RESPONSE_ACK_DEVICE -> wirePhase = WirePhase.RESPONSE_ACK_COMMAND;
            case RESPONSE_ACK_COMMAND -> completeResponseAcknowledgement();
            case NORMALIZED_DISCONNECT -> resetWireState();
            case RESPONSE_READY -> throw new IllegalStateException(
                    "Response-ready phase cannot complete a byte transfer");
        }
    }

    private void completeResponseAcknowledgement() {
        int expected = (responsePacket[2] & 0xff) ^ 0x80;
        if (sb == expected) {
            resetWireState();
            return;
        }
        if ((sb == 0xf0 || sb == 0xf1 || sb == 0xf2) &&
                responseRetryCount < MAX_RESPONSE_RETRIES) {
            responseRetryCount++;
            responseByteIndex = 0;
            wirePhase = WirePhase.RESPONSE_READY;
            return;
        }
        resetWireState();
    }

    private void acceptRequestByte() {
        // Crystal wakes/probes the adapter with 4B before its first 99 66 request magic. Treat that
        // byte as link-layer idle only while the packet parser is empty; 4B remains ordinary data
        // once a request has started.
        if (sb == POLL_BYTE) {
            MobileAdapterEngine.EngineResult before = engine.snapshot();
            if (before.retainedBytes() == 0) {
                if (before.phase() != MobileAdapterEngine.Phase.SLEEP) {
                    engine.observeSerialActivity();
                }
                return;
            }
        }
        MobileAdapterEngine.EngineResult result = engine.acceptByte(sb);
        if (result.outcome() == MobileAdapterEngine.Outcome.BACKEND_PENDING) {
            externalIoActivityObserved = true;
        }
        byte[] acknowledgement = result.acknowledgement();
        if (acknowledgement.length != 2) {
            return;
        }

        requestAcknowledgement = acknowledgement;
        responsePacket = result.responsePacket();
        responseByteIndex = 0;
        awaitingResponse = responsePacket.length != 0 ||
                result.outcome() == MobileAdapterEngine.Outcome.BACKEND_PENDING;
        responseRetryCount = 0;
        wirePhase = WirePhase.REQUEST_ACK_DEVICE;
    }

    private void resetWireState() {
        currentReply = IDLE_BYTE;
        wirePhase = WirePhase.RECEIVE_REQUEST;
        requestAcknowledgement = new byte[0];
        responsePacket = new byte[0];
        responseByteIndex = 0;
        awaitingResponse = false;
        responseRetryCount = 0;
        legacyReplyLatched = false;
    }

    private void normalizeCurrentByteOrReset() {
        if (!byteTransferActive && !legacyReplyLatched) {
            resetWireState();
            return;
        }
        byteTransferActive = true;
        legacyReplyLatched = false;
        wirePhase = WirePhase.NORMALIZED_DISCONNECT;
        requestAcknowledgement = new byte[0];
        responsePacket = new byte[0];
        responseByteIndex = 0;
        awaitingResponse = false;
        responseRetryCount = 0;
        // currentReply and sendBitIndex remain latched until this byte finishes.
    }

    @Override
    public ComponentState<SerialEndpoint> captureState() {
        ComponentState<MobileAdapterEngine> engineState = engine.captureState();
        if (engineState instanceof MobileAdapterEngine.MobileAdapterEngineNetworkState networkState) {
            if (byteTransferActive && wirePhase != WirePhase.RECEIVE_REQUEST) {
                // Capture cannot retain a host handle, but it must finish the reply byte already
                // latched by SerialPort. The additive phase stores only that byte and aborts the
                // old wire transaction at its boundary after restore.
                return normalizedDisconnectState(networkState);
            }
            if (wirePhase == WirePhase.RECEIVE_REQUEST && byteTransferActive) {
                // External ownership is normalized by the nested engine record, but the current
                // D2 byte is still deterministic and must not regress to the released FF reply.
                return new MobileAdapterSerialEndpointWireState(
                        networkState,
                        sb,
                        sendBitIndex,
                        byteTransferActive,
                        wirePhase.id,
                        currentReply,
                        requestAcknowledgement,
                        responsePacket,
                        responseByteIndex,
                        awaitingResponse,
                        responseRetryCount);
            }
            // External I/O is already normalized to a disconnected engine state. Keep the released
            // endpoint record as the matching transaction-abort boundary.
            return new MobileAdapterSerialEndpointNetworkState(networkState, sb, sendBitIndex);
        }
        MobileAdapterEngine.MobileAdapterEngineState pureEngineState =
                (MobileAdapterEngine.MobileAdapterEngineState) engineState;
        if (legacyReplyLatched) {
            return new MobileAdapterSerialEndpointState(
                    pureEngineState, sb, sendBitIndex);
        }
        if (wirePhase == WirePhase.RECEIVE_REQUEST && !byteTransferActive) {
            return new MobileAdapterSerialEndpointState(pureEngineState, sb, sendBitIndex);
        }
        return new MobileAdapterSerialEndpointWireState(
                pureEngineState,
                sb,
                sendBitIndex,
                byteTransferActive,
                wirePhase.id,
                currentReply,
                requestAcknowledgement,
                responsePacket,
                responseByteIndex,
                awaitingResponse,
                responseRetryCount);
    }

    private MobileAdapterSerialEndpointWireState normalizedDisconnectState(
            ComponentState<MobileAdapterEngine> engineState) {
        return new MobileAdapterSerialEndpointWireState(
                engineState,
                sb,
                sendBitIndex,
                true,
                WirePhase.NORMALIZED_DISCONNECT.id,
                currentReply,
                new byte[0],
                new byte[0],
                0,
                false,
                0);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (state instanceof MobileAdapterSerialEndpointWireState wireState) {
            restoreWireEndpointState(wireState);
        } else if (state instanceof MobileAdapterSerialEndpointNetworkState networkState) {
            validateLegacyEndpointState(networkState.sb, networkState.sendBitIndex);
            engine.restoreState(networkState.engineState);
            sb = networkState.sb;
            sendBitIndex = networkState.sendBitIndex;
            byteTransferActive = sendBitIndex > 0;
            resetWireState();
            currentReply = 0xff;
            legacyReplyLatched = true;
            externalIoActivityObserved = false;
        } else if (state instanceof MobileAdapterSerialEndpointState legacyState) {
            validateLegacyEndpointState(legacyState.sb, legacyState.sendBitIndex);
            engine.restoreState(legacyState.engineState);
            sb = legacyState.sb;
            sendBitIndex = legacyState.sendBitIndex;
            byteTransferActive = sendBitIndex > 0;
            // Released states contain no wire cursor. Preserve their released idle-wire behavior;
            // inferring an ACK from retained engine output would be ambiguous with a completed
            // response that the current scheduler has already acknowledged.
            resetWireState();
            currentReply = 0xff;
            legacyReplyLatched = true;
            externalIoActivityObserved = false;
        } else {
            throw new IllegalArgumentException("Invalid Mobile Adapter serial endpoint state type");
        }
    }

    private void restoreWireEndpointState(MobileAdapterSerialEndpointWireState state) {
        validateLegacyEndpointState(state.sb, state.sendBitIndex);
        WirePhase restoredPhase = WirePhase.fromId(state.wirePhaseId);
        validateWireState(state, restoredPhase);
        engine.restoreState(state.engineState);

        sb = state.sb;
        sendBitIndex = state.sendBitIndex;
        byteTransferActive = state.byteTransferActive;
        legacyReplyLatched = false;
        wirePhase = restoredPhase;
        currentReply = state.currentReply;
        requestAcknowledgement = state.requestAcknowledgement();
        responsePacket = state.responsePacket();
        responseByteIndex = state.responseByteIndex;
        awaitingResponse = state.awaitingResponse;
        responseRetryCount = state.responseRetryCount;
        externalIoActivityObserved = false;
    }

    private static void validateLegacyEndpointState(int restoredSb, int restoredSendBitIndex) {
        if (restoredSb < 0 || restoredSb > 0xff) {
            throw new IllegalArgumentException("Mobile Adapter SB value must be in 0..255");
        }
        if (restoredSendBitIndex < 0 || restoredSendBitIndex > 7) {
            throw new IllegalArgumentException("Mobile Adapter send-bit index must be in 0..7");
        }
    }

    private static void validateWireState(MobileAdapterSerialEndpointWireState state,
                                          WirePhase phase) {
        if (state.currentReply < 0 || state.currentReply > 0xff) {
            throw new IllegalArgumentException("Mobile Adapter reply byte must be in 0..255");
        }
        if (!state.byteTransferActive && state.sendBitIndex != 0) {
            throw new IllegalArgumentException(
                    "Inactive Mobile Adapter byte transfer has a nonzero bit index");
        }
        byte[] acknowledgement = state.requestAcknowledgement();
        byte[] response = state.responsePacket();
        EngineOutput engineOutput = engineOutput(state.engineState);
        if (phase == WirePhase.NORMALIZED_DISCONNECT) {
            MobileAdapterEngine.Outcome outcome =
                    MobileAdapterEngine.Outcome.fromId(engineOutput.outcomeId);
            boolean normalizedOutcome =
                    outcome == MobileAdapterEngine.Outcome.CANCELLED ||
                            outcome == MobileAdapterEngine.Outcome.IDLE_TIMEOUT_RESET ||
                            outcome == MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED;
            if (!state.byteTransferActive || !normalizedOutcome ||
                    engineOutput.packetCount != 0 ||
                    engineOutput.acknowledgement.length != 0 ||
                    engineOutput.responsePacket.length != 0 ||
                    acknowledgement.length != 0 || response.length != 0 ||
                    state.responseByteIndex != 0 || state.awaitingResponse ||
                    state.responseRetryCount != 0) {
                throw new IllegalArgumentException(
                        "Normalized Mobile Adapter disconnect byte is inconsistent");
            }
            return;
        }
        if (phase == WirePhase.RECEIVE_REQUEST) {
            if (!state.byteTransferActive || state.currentReply != IDLE_BYTE ||
                    acknowledgement.length != 0 || response.length != 0 ||
                    state.responseByteIndex != 0 || state.awaitingResponse ||
                    state.responseRetryCount != 0) {
                throw new IllegalArgumentException(
                        "In-flight Mobile Adapter request state is inconsistent");
            }
            return;
        }
        if (phase == WirePhase.RESPONSE_PENDING) {
            throw new IllegalArgumentException(
                    "Runtime-only Mobile Adapter backend wait cannot be restored");
        }
        if (acknowledgement.length != 2) {
            throw new IllegalArgumentException(
                    "Active Mobile Adapter wire state requires a two-byte acknowledgement");
        }
        if ((acknowledgement[0] & 0xff) != (engineOutput.deviceId | 0x80)) {
            throw new IllegalArgumentException(
                    "Mobile Adapter wire acknowledgement device ID is invalid");
        }
        validateResponsePacket(response);
        if (!Arrays.equals(response, engineOutput.responsePacket)) {
            throw new IllegalArgumentException(
                    "Mobile Adapter wire response is detached from engine state");
        }
        if (!wireAcknowledgementMatches(acknowledgement, engineOutput)) {
            throw new IllegalArgumentException(
                    "Mobile Adapter wire acknowledgement is detached from engine state");
        }
        if (state.responseByteIndex < 0 || state.responseByteIndex > response.length) {
            throw new IllegalArgumentException("Mobile Adapter response cursor is invalid");
        }
        if (state.awaitingResponse != (response.length != 0)) {
            throw new IllegalArgumentException(
                    "Mobile Adapter response-wait ownership is inconsistent");
        }
        if (state.responseRetryCount < 0 ||
                state.responseRetryCount > MAX_RESPONSE_RETRIES) {
            throw new IllegalArgumentException("Mobile Adapter response retry count is invalid");
        }
        if ((phase == WirePhase.RESPONSE_STREAM ||
                phase == WirePhase.RESPONSE_ACK_DEVICE ||
                phase == WirePhase.RESPONSE_ACK_COMMAND ||
                phase == WirePhase.RESPONSE_READY) && response.length == 0) {
            throw new IllegalArgumentException("Mobile Adapter response phase has no packet");
        }
        if (phase == WirePhase.RESPONSE_STREAM &&
                state.responseByteIndex >= response.length) {
            throw new IllegalArgumentException("Mobile Adapter streaming cursor is exhausted");
        }
        if ((phase == WirePhase.RESPONSE_ACK_DEVICE ||
                phase == WirePhase.RESPONSE_ACK_COMMAND) &&
                state.responseByteIndex != response.length) {
            throw new IllegalArgumentException("Mobile Adapter response ACK starts before EOF");
        }
        if ((phase == WirePhase.REQUEST_ACK_DEVICE ||
                phase == WirePhase.REQUEST_ACK_COMMAND ||
                phase == WirePhase.RESPONSE_TURNAROUND ||
                phase == WirePhase.RESPONSE_WAIT ||
                phase == WirePhase.RESPONSE_READY) && state.responseByteIndex != 0) {
            throw new IllegalArgumentException(
                    "Mobile Adapter response cursor starts before streaming");
        }
        if ((phase == WirePhase.REQUEST_ACK_DEVICE ||
                phase == WirePhase.REQUEST_ACK_COMMAND ||
                phase == WirePhase.RESPONSE_TURNAROUND ||
                phase == WirePhase.RESPONSE_WAIT) && state.responseRetryCount != 0) {
            throw new IllegalArgumentException(
                    "Mobile Adapter request acknowledgement has a response retry count");
        }
        if (phase == WirePhase.RESPONSE_READY && state.byteTransferActive) {
            throw new IllegalArgumentException(
                    "Mobile Adapter response-ready phase cannot be mid-byte");
        }
        if (!state.awaitingResponse &&
                phase != WirePhase.REQUEST_ACK_DEVICE &&
                phase != WirePhase.REQUEST_ACK_COMMAND) {
            throw new IllegalArgumentException("Mobile Adapter response phase is not expected");
        }
        if (state.byteTransferActive) {
            int expectedReply = switch (phase) {
                case REQUEST_ACK_DEVICE, RESPONSE_ACK_DEVICE -> acknowledgement[0] & 0xff;
                case REQUEST_ACK_COMMAND -> acknowledgement[1] & 0xff;
                case RECEIVE_REQUEST, RESPONSE_TURNAROUND, RESPONSE_WAIT, RESPONSE_PENDING ->
                        IDLE_BYTE;
                case RESPONSE_STREAM -> response[state.responseByteIndex] & 0xff;
                case RESPONSE_ACK_COMMAND -> 0;
                case RESPONSE_READY, NORMALIZED_DISCONNECT -> throw new IllegalStateException();
            };
            if (state.currentReply != expectedReply) {
                throw new IllegalArgumentException(
                        "Mobile Adapter in-flight reply does not match its wire phase");
            }
        }
    }

    private static void validateResponsePacket(byte[] packet) {
        if (packet.length == 0) {
            return;
        }
        if (packet.length < 8 || packet.length > MobileAdapterEngine.MAX_PACKET_BYTES ||
                (packet[0] & 0xff) != 0x99 || (packet[1] & 0xff) != 0x66 || packet[3] != 0) {
            throw new IllegalArgumentException("Mobile Adapter wire response packet is invalid");
        }
        int length = (packet[4] & 0xff) << 8 | packet[5] & 0xff;
        if (packet.length != length + 8) {
            throw new IllegalArgumentException("Mobile Adapter wire response length is invalid");
        }
        int checksum = 0;
        for (int i = 2; i < 6 + length; i++) {
            checksum = checksum + (packet[i] & 0xff) & 0xffff;
        }
        int expected = (packet[6 + length] & 0xff) << 8 | packet[7 + length] & 0xff;
        if (checksum != expected) {
            throw new IllegalArgumentException("Mobile Adapter wire response checksum is invalid");
        }
    }

    private static EngineOutput engineOutput(ComponentState<MobileAdapterEngine> state) {
        if (state instanceof MobileAdapterEngine.MobileAdapterEngineState pureState) {
            return new EngineOutput(
                    pureState.deviceId(),
                    pureState.outcomeId(),
                    pureState.packetCount(),
                    pureState.acknowledgement(),
                    pureState.responsePacket());
        }
        if (state instanceof MobileAdapterEngine.MobileAdapterEngineNetworkState networkState) {
            return new EngineOutput(
                    networkState.deviceId(),
                    networkState.outcomeId(),
                    networkState.packetCount(),
                    networkState.acknowledgement(),
                    networkState.responsePacket());
        }
        throw new IllegalArgumentException("Unknown Mobile Adapter engine state type");
    }

    private static boolean wireAcknowledgementMatches(
            byte[] acknowledgement,
            EngineOutput engineOutput) {
        if (Arrays.equals(acknowledgement, engineOutput.acknowledgement)) {
            return true;
        }
        if (engineOutput.acknowledgement.length != 0 || engineOutput.responsePacket.length == 0) {
            return false;
        }
        int expectedCommand = switch (MobileAdapterEngine.Outcome.fromId(engineOutput.outcomeId)) {
            // A successful asynchronous completion clears the engine's operation-local ACK, while
            // the wire endpoint must retain the ACK already sent for that same request.
            case BACKEND_RESPONSE -> engineOutput.responsePacket[2] & 0xff;
            case BACKEND_REMOTE_CLOSED -> 0x95;
            case BACKEND_ERROR -> engineOutput.responsePacket.length >= 8 &&
                    (engineOutput.responsePacket[2] & 0xff) == 0x6e ?
                    (engineOutput.responsePacket[6] & 0xff) ^ 0x80 : -1;
            default -> -1;
        };
        return (acknowledgement[1] & 0xff) == expectedCommand;
    }

    private record EngineOutput(
            int deviceId,
            int outcomeId,
            int packetCount,
            byte[] acknowledgement,
            byte[] responsePacket) {
    }

    private enum WirePhase {
        RECEIVE_REQUEST(1),
        REQUEST_ACK_DEVICE(2),
        REQUEST_ACK_COMMAND(3),
        RESPONSE_TURNAROUND(4),
        RESPONSE_WAIT(5),
        RESPONSE_STREAM(6),
        RESPONSE_ACK_DEVICE(7),
        RESPONSE_ACK_COMMAND(8),
        RESPONSE_READY(9),
        /** Runtime-only wait after the poll gate; external ownership is never serialized. */
        RESPONSE_PENDING(10),
        /** Serialized one-byte latch that resets after normalized host-I/O loss. */
        NORMALIZED_DISCONNECT(11);

        private final int id;

        WirePhase(int id) {
            this.id = id;
        }

        private static WirePhase fromId(int id) {
            for (WirePhase value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown Mobile Adapter wire phase " + id);
        }
    }

    /** Released Phase-1 endpoint state, retained for default-wire captures and compatibility. */
    public record MobileAdapterSerialEndpointState(
            MobileAdapterEngine.MobileAdapterEngineState engineState,
            int sb,
            int sendBitIndex) implements ComponentState<SerialEndpoint> {

        public MobileAdapterSerialEndpointState {
            Objects.requireNonNull(engineState, "engineState");
        }
    }

    /** Additive endpoint state for a deterministic acknowledgement/response wire transaction. */
    public record MobileAdapterSerialEndpointWireState(
            ComponentState<MobileAdapterEngine> engineState,
            int sb,
            int sendBitIndex,
            boolean byteTransferActive,
            int wirePhaseId,
            int currentReply,
            byte[] requestAcknowledgement,
            byte[] responsePacket,
            int responseByteIndex,
            boolean awaitingResponse,
            int responseRetryCount) implements ComponentState<SerialEndpoint> {

        public MobileAdapterSerialEndpointWireState {
            Objects.requireNonNull(engineState, "engineState");
            requestAcknowledgement = requestAcknowledgement.clone();
            responsePacket = responsePacket.clone();
        }

        @Override
        public byte[] requestAcknowledgement() {
            return requestAcknowledgement.clone();
        }

        @Override
        public byte[] responsePacket() {
            return responsePacket.clone();
        }
    }

    /** Additive Phase-2 endpoint state used only when its engine observed external I/O. */
    public record MobileAdapterSerialEndpointNetworkState(
            MobileAdapterEngine.MobileAdapterEngineNetworkState engineState,
            int sb,
            int sendBitIndex) implements ComponentState<SerialEndpoint> {

        public MobileAdapterSerialEndpointNetworkState {
            Objects.requireNonNull(engineState, "engineState");
        }
    }
}
