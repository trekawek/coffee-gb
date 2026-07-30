package eu.rekawek.coffeegb.core.serial.mobile;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic, platform-neutral Mobile Adapter GB packet engine.
 *
 * <p>The engine implements the clean-room packet subset frozen for issues #351 and #352. It
 * accepts one complete serial byte at a time, retains at most one 262-byte packet, and exposes
 * response packet and acknowledgement bytes as separate immutable channels. It deliberately does
 * not prescribe an on-wire ordering for those channels because the clean-room evidence does not
 * define one.
 */
public final class MobileAdapterEngine implements StatefulComponent<MobileAdapterEngine> {

    public static final int MAX_PACKET_DATA_BYTES = 254;

    public static final int MAX_PACKET_BYTES = 262;

    public static final int CONFIGURATION_BYTES = 256;

    public static final int MAX_CONFIGURATION_OPERATION_BYTES = 128;

    public static final int MAX_PENDING_PACKET_SLOTS = 2;

    /** One byte of every transfer packet is the logical connection identifier. */
    public static final int MAX_TRANSFER_DATA_BYTES = MAX_PACKET_DATA_BYTES - 1;

    /** RFC-compatible textual ceiling used before a name reaches the controller backend. */
    public static final int MAX_DNS_NAME_BYTES = 253;

    public static final int MAX_LOGICAL_CONNECTIONS = 2;

    public static final int IDLE_TIMEOUT_MILLIS = 3_000;

    private static final int MAGIC_1 = 0x99;

    private static final int MAGIC_2 = 0x66;

    private static final int COMMAND_BEGIN_SESSION = 0x10;

    private static final int COMMAND_END_SESSION = 0x11;

    private static final int COMMAND_TRANSFER = 0x15;

    private static final int COMMAND_RESET = 0x16;

    private static final int COMMAND_CONFIG_READ = 0x19;

    private static final int COMMAND_CONFIG_WRITE = 0x1a;

    private static final int COMMAND_TCP_OPEN = 0x23;

    private static final int COMMAND_TCP_CLOSE = 0x24;

    private static final int COMMAND_UDP_OPEN = 0x25;

    private static final int COMMAND_UDP_CLOSE = 0x26;

    private static final int COMMAND_DNS_QUERY = 0x28;

    private static final int COMMAND_REMOTE_CLOSED = 0x1f;

    private static final int COMMAND_ERROR_STATUS = 0x6e;

    private static final int ACK_UNSUPPORTED = 0xf0;

    private static final int ACK_CHECKSUM_ERROR = 0xf1;

    private static final int ACK_INTERNAL_ERROR = 0xf2;

    private static final int CONNECTION_EMPTY = 0;

    private static final int CONNECTION_TCP = 1;

    private static final int CONNECTION_UDP = 2;

    private static final byte[] BEGIN_SESSION_DATA =
            "NINTENDO".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] EMPTY_BYTES = new byte[0];

    private final byte[] packetBuffer = new byte[MAX_PACKET_BYTES];

    private final long phaseUnitsPerTick;

    private final long idleBoundaryPhaseUnits;

    private final transient MobileAdapterBackendPort backendPort;

    private byte[] configuration;

    private int deviceId;

    private Phase phase = Phase.SLEEP;

    private Outcome outcome = Outcome.NEED_MORE;

    private ErrorCode error = ErrorCode.NONE;

    private int packetCount;

    private int expectedPacketBytes = -1;

    private byte[] responsePacket = EMPTY_BYTES;

    private byte[] acknowledgement = EMPTY_BYTES;

    private long idlePhaseUnits;

    private boolean serialByteObserved;

    private int pendingPacketSlots;

    /** Runtime-only logical view of controller-owned sockets; deliberately absent from mementos. */
    private final int[] connectionKinds = new int[MAX_LOGICAL_CONNECTIONS];

    /** Identity-only generation that owns every nonempty entry in {@link #connectionKinds}. */
    private transient MobileAdapterBackendPort.BackendGeneration connectionBackendGeneration;

    private long nextBackendRequestId;

    private long pendingBackendRequestId = -1;

    private int pendingBackendCommand = -1;

    private byte[] pendingBackendPayload = EMPTY_BYTES;

    private transient MobileAdapterBackendPort.BackendGeneration pendingBackendGeneration;

    private boolean backendPacketSlotReserved;

    public MobileAdapterEngine(ClockSpec clockSpec, int deviceId, byte[] configuration) {
        this(clockSpec, deviceId, configuration, MobileAdapterBackendPort.DISCONNECTED);
    }

    public MobileAdapterEngine(ClockSpec clockSpec, int deviceId, byte[] configuration,
                               MobileAdapterBackendPort backendPort) {
        Objects.requireNonNull(clockSpec, "clockSpec");
        phaseUnitsPerTick = clockSpec.secondPhaseUnitsPerTick();
        idleBoundaryPhaseUnits = Math.multiplyExact(
                IDLE_TIMEOUT_MILLIS / 1_000L, clockSpec.secondPhaseLimit());
        this.backendPort = Objects.requireNonNull(backendPort, "backendPort");
        this.deviceId = requireDeviceId(deviceId);
        this.configuration = requireConfiguration(configuration);
    }

    /** Accepts one unsigned serial byte and returns the complete visible engine result. */
    public EngineResult acceptByte(int value) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException("Serial byte must be in 0..255");
        }
        beginInputOperation();
        serialByteObserved = true;
        idlePhaseUnits = 0;

        if (packetCount >= packetBuffer.length) {
            reject(Outcome.BUFFER_LIMIT, ErrorCode.BUFFER_LIMIT);
            return snapshot();
        }
        packetBuffer[packetCount++] = (byte) value;

        if (packetCount == 2 &&
                ((packetBuffer[0] & 0xff) != MAGIC_1 ||
                        (packetBuffer[1] & 0xff) != MAGIC_2)) {
            reject(Outcome.MAGIC_ERROR, ErrorCode.INVALID_MAGIC);
            return snapshot();
        }
        if (packetCount == 4 && packetBuffer[3] != 0) {
            reject(Outcome.RESERVED_ERROR, ErrorCode.RESERVED_VALUE);
            return snapshot();
        }
        if (packetCount == 6) {
            int declaredLength = unsigned16(packetBuffer, 4);
            if (declaredLength > MAX_PACKET_DATA_BYTES) {
                reject(Outcome.LENGTH_LIMIT, ErrorCode.LENGTH_LIMIT);
                return snapshot();
            }
            expectedPacketBytes = Math.addExact(8, declaredLength);
        }
        if (expectedPacketBytes > 0 && packetCount == expectedPacketBytes) {
            commitPacket();
        }
        return snapshot();
    }

    /**
     * Advances the engine by a nonnegative number of emulated master ticks.
     *
     * <p>A negative value is reported as a time regression and changes no state. At the exact
     * 3,000 ms boundary a partial packet remains retained; the first tick beyond it resets.
     */
    public EngineResult advanceTicks(long ticks) {
        if (ticks < 0) {
            return snapshot(Outcome.TIME_REGRESSION, ErrorCode.TIME_REGRESSION);
        }
        advancePositiveTicks(ticks);
        return snapshot();
    }

    /**
     * Advances one master tick without materializing an {@link EngineResult}.
     *
     * <p>The serial port invokes this method at the emulated master-clock rate. Keeping the hot
     * path void avoids allocating and defensively cloning a result millions of times per second;
     * callers request an immutable result explicitly through {@link #snapshot()}.
     */
    public void tick() {
        advancePositiveTicks(1);
    }

    private void advancePositiveTicks(long ticks) {
        if (ticks == 0 || !serialByteObserved) {
            return;
        }

        long remainingToBoundary = idleBoundaryPhaseUnits - idlePhaseUnits;
        long ticksThroughBoundary = remainingToBoundary / phaseUnitsPerTick;
        if (ticks > ticksThroughBoundary) {
            timeoutReset();
            return;
        }

        idlePhaseUnits = Math.addExact(
                idlePhaseUnits, Math.multiplyExact(ticks, phaseUnitsPerTick));
        if (idlePhaseUnits == idleBoundaryPhaseUnits && packetCount > 0) {
            outcome = Outcome.IDLE_BOUNDARY_WAIT;
            error = ErrorCode.NONE;
        }
    }

    /** Reserves one of the two deterministic packet slots without allocating. */
    public boolean reservePendingPacketSlot() {
        if (pendingPacketSlots >= MAX_PENDING_PACKET_SLOTS) {
            clearOutput();
            outcome = Outcome.PENDING_LIMIT;
            error = ErrorCode.PENDING_LIMIT;
            return false;
        }
        pendingPacketSlots++;
        return true;
    }

    /** Releases one deterministic packet slot after its pure result has been consumed. */
    public void completePendingPacketSlot() {
        int callerOwnedSlots = pendingPacketSlots - (backendPacketSlotReserved ? 1 : 0);
        if (callerOwnedSlots <= 0) {
            throw new IllegalStateException("No pending Mobile Adapter packet slot");
        }
        pendingPacketSlots--;
        if (outcome == Outcome.PENDING_LIMIT) {
            outcome = Outcome.NEED_MORE;
            error = ErrorCode.NONE;
        }
    }

    /**
     * Atomically polls and applies at most one controller completion without waiting.
     *
     * <p>The completion's generation and request identity are checked again immediately before any
     * deterministic state changes. A controller calls this at an emulator safe point; it is not a
     * host-I/O callback and never blocks.
     */
    public EngineResult pollBackendCompletion() {
        // A new guest packet owns the visible operation until it is complete. Applying an older
        // asynchronous result (or its revoked generation) here would replace NEED_MORE while
        // retaining parser bytes, producing an ordering ambiguity and a state that the persisted
        // invariants correctly reject. Leave the bounded completion queued until this packet
        // commits or a cancellation boundary clears it.
        if (packetCount > 0) {
            return snapshot();
        }
        if (hasOpenConnection() &&
                (connectionBackendGeneration == null ||
                        backendPort.generation() != connectionBackendGeneration)) {
            externalIoDisconnected();
            return snapshot();
        }
        if (pendingBackendRequestId < 0 || pendingBackendGeneration == null) {
            return snapshot();
        }
        if (backendPort.generation() != pendingBackendGeneration) {
            externalIoDisconnected();
            return snapshot();
        }
        MobileAdapterBackendPort.BackendCompletion completion =
                backendPort.poll(pendingBackendGeneration);
        if (completion == null) {
            if (backendPort.generation() != pendingBackendGeneration) {
                externalIoDisconnected();
            }
            return snapshot();
        }
        if (completion.generation() != pendingBackendGeneration ||
                backendPort.generation() != pendingBackendGeneration ||
                completion.requestId() != pendingBackendRequestId) {
            externalIoDisconnected();
            return snapshot();
        }
        applyBackendCompletion(completion);
        return snapshot();
    }

    /** True only for runtime ownership that capture deliberately converts to disconnected state. */
    public boolean hasExternalIo() {
        return pendingBackendRequestId >= 0 || hasOpenConnection();
    }

    /**
     * Replaces the complete configuration atomically in memory after validating a detached copy.
     * Persistence remains a controller responsibility and never occurs in this core method.
     */
    public void replaceConfiguration(byte[] replacement) {
        byte[] validated = requireConfiguration(replacement);
        configuration = validated;
    }

    public byte[] configurationCopy() {
        return configuration.clone();
    }

    /** Clears all deterministic request/response ownership for detach or session replacement. */
    public EngineResult cancelOrReplace() {
        phase = Phase.SLEEP;
        outcome = Outcome.CANCELLED;
        error = ErrorCode.NONE;
        clearParser();
        clearOutput();
        pendingPacketSlots = 0;
        cancelBackendOwnership();
        serialByteObserved = false;
        idlePhaseUnits = 0;
        return snapshot();
    }

    public EngineResult snapshot() {
        return snapshot(outcome, error);
    }

    @Override
    public ComponentState<MobileAdapterEngine> captureState() {
        boolean externalIoAtCapture = hasExternalIo();
        int capturedPendingSlots = pendingPacketSlots - (backendPacketSlotReserved ? 1 : 0);
        if (externalIoAtCapture) {
            return new MobileAdapterEngineNetworkState(
                    phase.id,
                    Outcome.EXTERNAL_IO_DISCONNECTED.id,
                    ErrorCode.EXTERNAL_IO_DISCONNECTED.id,
                    deviceId,
                    packetBuffer,
                    packetCount,
                    expectedPacketBytes,
                    configuration,
                    EMPTY_BYTES,
                    EMPTY_BYTES,
                    idlePhaseUnits,
                    serialByteObserved,
                    capturedPendingSlots,
                    true);
        }
        return new MobileAdapterEngineState(
                phase.id, outcome.id, error.id, deviceId, packetBuffer, packetCount,
                expectedPacketBytes, configuration, responsePacket, acknowledgement,
                idlePhaseUnits, serialByteObserved, capturedPendingSlots);
    }

    @Override
    public void restoreState(ComponentState<MobileAdapterEngine> state) {
        MobileAdapterEngineNetworkState restored;
        if (state instanceof MobileAdapterEngineNetworkState networkState) {
            if (!networkState.externalIoAtCapture) {
                throw new IllegalArgumentException(
                        "Mobile Adapter network state requires captured external I/O");
            }
            restored = networkState;
        } else if (state instanceof MobileAdapterEngineState legacyState) {
            restored = networkState(legacyState);
        } else {
            throw new IllegalArgumentException("Invalid Mobile Adapter engine state type");
        }
        validateState(restored);
        cancelBackendOwnership();

        phase = Phase.fromId(restored.phaseId);
        outcome = Outcome.fromId(restored.outcomeId);
        error = ErrorCode.fromId(restored.errorId);
        deviceId = restored.deviceId;
        byte[] restoredPacket = restored.packetBuffer();
        System.arraycopy(restoredPacket, 0, packetBuffer, 0, packetBuffer.length);
        packetCount = restored.packetCount;
        expectedPacketBytes = restored.expectedPacketBytes;
        configuration = restored.configuration();
        responsePacket = restored.responsePacket();
        acknowledgement = restored.acknowledgement();
        idlePhaseUnits = restored.idlePhaseUnits;
        serialByteObserved = restored.serialByteObserved;
        pendingPacketSlots = restored.pendingPacketSlots;
        if (restored.externalIoAtCapture) {
            outcome = Outcome.EXTERNAL_IO_DISCONNECTED;
            error = ErrorCode.EXTERNAL_IO_DISCONNECTED;
            clearOutput();
        }
    }

    private static MobileAdapterEngineNetworkState networkState(
            MobileAdapterEngineState legacyState) {
        return new MobileAdapterEngineNetworkState(
                legacyState.phaseId,
                legacyState.outcomeId,
                legacyState.errorId,
                legacyState.deviceId,
                legacyState.packetBuffer,
                legacyState.packetCount,
                legacyState.expectedPacketBytes,
                legacyState.configuration,
                legacyState.responsePacket,
                legacyState.acknowledgement,
                legacyState.idlePhaseUnits,
                legacyState.serialByteObserved,
                legacyState.pendingPacketSlots,
                false);
    }

    private void commitPacket() {
        int command = packetBuffer[2] & 0xff;
        int length = unsigned16(packetBuffer, 4);
        int expectedChecksum = 0;
        for (int i = 2; i < 6 + length; i++) {
            expectedChecksum = (expectedChecksum + (packetBuffer[i] & 0xff)) & 0xffff;
        }
        int actualChecksum = unsigned16(packetBuffer, 6 + length);
        byte[] data = Arrays.copyOfRange(packetBuffer, 6, 6 + length);
        clearParser();

        if (actualChecksum != expectedChecksum) {
            outcome = Outcome.CHECKSUM_ERROR;
            error = ErrorCode.CHECKSUM;
            acknowledgement = acknowledgement(ACK_CHECKSUM_ERROR);
            return;
        }

        switch (command) {
            case COMMAND_BEGIN_SESSION -> beginSession(data);
            case COMMAND_END_SESSION -> endSession(data);
            case COMMAND_TRANSFER -> submitBackendCommand(command, validateTransfer(data));
            case COMMAND_RESET -> resetSession(data);
            case COMMAND_CONFIG_READ -> readConfiguration(data);
            case COMMAND_CONFIG_WRITE -> writeConfiguration(data);
            case COMMAND_TCP_OPEN, COMMAND_UDP_OPEN ->
                    submitBackendCommand(command, validateOpen(data));
            case COMMAND_TCP_CLOSE ->
                    submitBackendCommand(command, validateClose(data, CONNECTION_TCP));
            case COMMAND_UDP_CLOSE ->
                    submitBackendCommand(command, validateClose(data, CONNECTION_UDP));
            case COMMAND_DNS_QUERY -> submitBackendCommand(command, validateDnsQuery(data));
            default -> unsupported();
        }
    }

    private void beginSession(byte[] data) {
        if (!Arrays.equals(data, BEGIN_SESSION_DATA)) {
            unsupported();
            return;
        }
        phase = Phase.SESSION;
        outcome = Outcome.SESSION_STARTED;
        responsePacket = packet(COMMAND_BEGIN_SESSION | 0x80, data);
        acknowledgement = acknowledgement(COMMAND_BEGIN_SESSION ^ 0x80);
    }

    private void endSession(byte[] data) {
        if (data.length != 0) {
            unsupported();
            return;
        }
        phase = Phase.SLEEP;
        pendingPacketSlots = 0;
        cancelBackendOwnership();
        outcome = Outcome.SESSION_ENDED;
        responsePacket = packet(COMMAND_END_SESSION | 0x80, EMPTY_BYTES);
        acknowledgement = acknowledgement(COMMAND_END_SESSION ^ 0x80);
    }

    private void resetSession(byte[] data) {
        if (data.length != 0) {
            unsupported();
            return;
        }
        phase = Phase.SESSION;
        pendingPacketSlots = 0;
        cancelBackendOwnership();
        outcome = Outcome.SESSION_RESET;
        responsePacket = packet(COMMAND_RESET | 0x80, EMPTY_BYTES);
        acknowledgement = acknowledgement(COMMAND_RESET ^ 0x80);
    }

    private void readConfiguration(byte[] data) {
        if (data.length != 2) {
            unsupported();
            return;
        }
        int offset = data[0] & 0xff;
        int requested = data[1] & 0xff;
        int end = Math.addExact(offset, requested);
        if (requested > MAX_CONFIGURATION_OPERATION_BYTES || end > configuration.length) {
            unsupported();
            return;
        }

        byte[] result = new byte[Math.addExact(requested, 1)];
        result[0] = (byte) offset;
        System.arraycopy(configuration, offset, result, 1, requested);
        outcome = requested == MAX_CONFIGURATION_OPERATION_BYTES ?
                Outcome.CONFIG_READ_BOUNDARY : Outcome.CONFIG_READ;
        responsePacket = packet(COMMAND_CONFIG_READ | 0x80, result);
        acknowledgement = acknowledgement(COMMAND_CONFIG_READ ^ 0x80);
    }

    private void writeConfiguration(byte[] data) {
        if (data.length < 1 || data.length > MAX_CONFIGURATION_OPERATION_BYTES + 1) {
            unsupported();
            return;
        }
        int offset = data[0] & 0xff;
        int writeLength = data.length - 1;
        int end = Math.addExact(offset, writeLength);
        if (end > configuration.length) {
            unsupported();
            return;
        }

        byte[] replacement = configuration.clone();
        System.arraycopy(data, 1, replacement, offset, writeLength);
        configuration = replacement;
        outcome = Outcome.CONFIG_WRITE;
        responsePacket = packet(COMMAND_CONFIG_WRITE | 0x80, new byte[]{(byte) offset});
        acknowledgement = acknowledgement(COMMAND_CONFIG_WRITE ^ 0x80);
    }

    private byte[] validateTransfer(byte[] data) {
        if (data.length < 1 || data.length > MAX_TRANSFER_DATA_BYTES + 1) {
            return null;
        }
        int connectionId = data[0] & 0xff;
        if (!isOpenConnection(connectionId)) return null;
        return data;
    }

    private byte[] validateOpen(byte[] data) {
        if (data.length != 6) return null;
        int port = unsigned16(data, 4);
        return port == 0 ? null : data;
    }

    private byte[] validateClose(byte[] data, int expectedKind) {
        if (data.length != 1) return null;
        int connectionId = data[0] & 0xff;
        return connectionId < connectionKinds.length &&
                connectionKinds[connectionId] == expectedKind ? data : null;
    }

    private byte[] validateDnsQuery(byte[] data) {
        if (data.length == 0) return null;
        int effectiveLength = 0;
        while (effectiveLength < data.length && data[effectiveLength] != 0) {
            int value = data[effectiveLength] & 0xff;
            if (value > 0x7f) return null;
            effectiveLength++;
        }
        if (effectiveLength == 0 || effectiveLength > MAX_DNS_NAME_BYTES) return null;
        return Arrays.copyOf(data, effectiveLength);
    }

    private void submitBackendCommand(int command, byte[] validatedPayload) {
        if (validatedPayload == null) {
            backendCommandError(command, protocolErrorCode(command,
                    MobileAdapterBackendPort.BackendStatus.INVALID_CONNECTION),
                    ErrorCode.BACKEND_RESPONSE_INVALID);
            return;
        }
        if (phase != Phase.SESSION) {
            backendCommandError(command, invalidUseErrorCode(command),
                    ErrorCode.BACKEND_UNAVAILABLE);
            return;
        }
        if (pendingBackendRequestId >= 0) {
            clearOutput();
            outcome = Outcome.BACKEND_ERROR;
            error = ErrorCode.BACKEND_BUSY;
            acknowledgement = acknowledgement(ACK_INTERNAL_ERROR);
            return;
        }
        if (!reservePendingPacketSlot()) {
            acknowledgement = acknowledgement(ACK_INTERNAL_ERROR);
            return;
        }
        backendPacketSlotReserved = true;

        long requestId = nextBackendRequestId;
        nextBackendRequestId = requestId == Long.MAX_VALUE ? 0 : requestId + 1;
        MobileAdapterBackendPort.BackendGeneration generation = backendPort.generation();
        MobileAdapterBackendPort.OfferResult admission = backendPort.offer(
                generation,
                new MobileAdapterBackendPort.BackendRequest(requestId, command, validatedPayload));
        if (admission != MobileAdapterBackendPort.OfferResult.ACCEPTED) {
            releaseBackendPacketSlot();
            if (admission == MobileAdapterBackendPort.OfferResult.UNAVAILABLE) {
                backendCommandError(command, invalidUseErrorCode(command),
                        ErrorCode.BACKEND_UNAVAILABLE);
            } else if (admission == MobileAdapterBackendPort.OfferResult.STALE_GENERATION) {
                externalIoDisconnected();
            } else {
                clearOutput();
                outcome = Outcome.BACKEND_ERROR;
                error = ErrorCode.BACKEND_UNAVAILABLE;
                acknowledgement = acknowledgement(ACK_INTERNAL_ERROR);
            }
            return;
        }

        pendingBackendRequestId = requestId;
        pendingBackendCommand = command;
        pendingBackendPayload = validatedPayload.clone();
        pendingBackendGeneration = generation;
        clearOutput();
        outcome = Outcome.BACKEND_PENDING;
        error = ErrorCode.NONE;
        acknowledgement = acknowledgement(command ^ 0x80);
    }

    private void applyBackendCompletion(MobileAdapterBackendPort.BackendCompletion completion) {
        int command = pendingBackendCommand;
        byte[] request = pendingBackendPayload;
        clearPendingBackendRequest();
        clearOutput();

        MobileAdapterBackendPort.BackendStatus status = completion.status();
        byte[] payload = completion.payload();
        if (status == MobileAdapterBackendPort.BackendStatus.CANCELLED) {
            externalIoDisconnected();
            return;
        }
        if (status == MobileAdapterBackendPort.BackendStatus.REMOTE_CLOSED) {
            if (command != COMMAND_TRANSFER || payload.length != 0 || request.length < 1) {
                malformedBackendCompletion(command);
                return;
            }
            int connectionId = request[0] & 0xff;
            if (!isOpenConnection(connectionId)) {
                malformedBackendCompletion(command);
                return;
            }
            clearLogicalConnection(connectionId);
            outcome = Outcome.BACKEND_REMOTE_CLOSED;
            error = ErrorCode.NONE;
            responsePacket = packet(COMMAND_REMOTE_CLOSED | 0x80, EMPTY_BYTES);
            return;
        }
        if (status != MobileAdapterBackendPort.BackendStatus.SUCCESS) {
            backendCommandError(command, protocolErrorCode(command, status),
                    ErrorCode.BACKEND_UNAVAILABLE);
            return;
        }

        if (!validateSuccessfulBackendPayload(command, request, payload)) {
            malformedBackendCompletion(command);
            return;
        }
        switch (command) {
            case COMMAND_TCP_OPEN -> {
                connectionKinds[payload[0] & 0xff] = CONNECTION_TCP;
                connectionBackendGeneration = completion.generation();
            }
            case COMMAND_UDP_OPEN -> {
                connectionKinds[payload[0] & 0xff] = CONNECTION_UDP;
                connectionBackendGeneration = completion.generation();
            }
            case COMMAND_TCP_CLOSE, COMMAND_UDP_CLOSE ->
                    clearLogicalConnection(payload[0] & 0xff);
            default -> {
            }
        }
        outcome = Outcome.BACKEND_RESPONSE;
        error = ErrorCode.NONE;
        responsePacket = packet(command | 0x80, payload);
    }

    private boolean validateSuccessfulBackendPayload(int command, byte[] request, byte[] payload) {
        return switch (command) {
            case COMMAND_TCP_OPEN, COMMAND_UDP_OPEN ->
                    payload.length == 1 && (payload[0] & 0xff) < connectionKinds.length &&
                            connectionKinds[payload[0] & 0xff] == CONNECTION_EMPTY;
            case COMMAND_TCP_CLOSE, COMMAND_UDP_CLOSE ->
                    payload.length == 1 && request.length == 1 && payload[0] == request[0] &&
                            (payload[0] & 0xff) < connectionKinds.length;
            case COMMAND_TRANSFER ->
                    payload.length >= 1 && payload.length <= MAX_PACKET_DATA_BYTES &&
                            request.length >= 1 && payload[0] == request[0] &&
                            isOpenConnection(payload[0] & 0xff);
            case COMMAND_DNS_QUERY -> payload.length == 4;
            default -> false;
        };
    }

    private void malformedBackendCompletion(int command) {
        // A successful backend operation may already have opened, consumed, or closed a host
        // resource before its result crosses this boundary. Once that result has an impossible
        // shape, the core and backend can no longer prove matching ownership; fail closed by
        // rotating the complete generation before exposing the sanitized protocol error.
        cancelBackendOwnership();
        backendCommandError(command,
                protocolErrorCode(command,
                        MobileAdapterBackendPort.BackendStatus.COMMUNICATION_FAILED),
                ErrorCode.BACKEND_RESPONSE_INVALID);
    }

    private void backendCommandError(int command, int commandError, ErrorCode engineError) {
        clearOutput();
        outcome = Outcome.BACKEND_ERROR;
        error = engineError;
        responsePacket = packet(COMMAND_ERROR_STATUS,
                new byte[]{(byte) command, (byte) commandError});
    }

    private static int protocolErrorCode(int command,
                                         MobileAdapterBackendPort.BackendStatus status) {
        if ((command == COMMAND_TCP_OPEN || command == COMMAND_UDP_OPEN) &&
                status == MobileAdapterBackendPort.BackendStatus.CONNECTION_LIMIT) {
            return 0x00;
        }
        return switch (command) {
            case COMMAND_TRANSFER, COMMAND_TCP_CLOSE, COMMAND_UDP_CLOSE -> 0x00;
            case COMMAND_TCP_OPEN, COMMAND_UDP_OPEN -> 0x03;
            case COMMAND_DNS_QUERY -> 0x02;
            default -> 0x00;
        };
    }

    private static int invalidUseErrorCode(int command) {
        return switch (command) {
            case COMMAND_TRANSFER -> 0x01;
            case COMMAND_TCP_OPEN, COMMAND_TCP_CLOSE, COMMAND_UDP_OPEN, COMMAND_UDP_CLOSE,
                    COMMAND_DNS_QUERY -> 0x01;
            default -> 0x00;
        };
    }

    private void externalIoDisconnected() {
        cancelBackendOwnership();
        clearOutput();
        outcome = Outcome.EXTERNAL_IO_DISCONNECTED;
        error = ErrorCode.EXTERNAL_IO_DISCONNECTED;
    }

    private void cancelBackendOwnership() {
        backendPort.cancelAll();
        pendingBackendRequestId = -1;
        pendingBackendCommand = -1;
        pendingBackendPayload = EMPTY_BYTES;
        pendingBackendGeneration = null;
        nextBackendRequestId = 0;
        if (backendPacketSlotReserved && pendingPacketSlots > 0) pendingPacketSlots--;
        backendPacketSlotReserved = false;
        Arrays.fill(connectionKinds, CONNECTION_EMPTY);
        connectionBackendGeneration = null;
    }

    private void clearPendingBackendRequest() {
        pendingBackendRequestId = -1;
        pendingBackendCommand = -1;
        pendingBackendPayload = EMPTY_BYTES;
        pendingBackendGeneration = null;
        releaseBackendPacketSlot();
    }

    private void releaseBackendPacketSlot() {
        if (!backendPacketSlotReserved) return;
        backendPacketSlotReserved = false;
        if (pendingPacketSlots <= 0) {
            throw new IllegalStateException("Mobile Adapter backend packet slot ownership is invalid");
        }
        pendingPacketSlots--;
    }

    private boolean isOpenConnection(int connectionId) {
        return connectionId >= 0 && connectionId < connectionKinds.length &&
                connectionKinds[connectionId] != CONNECTION_EMPTY;
    }

    private void clearLogicalConnection(int connectionId) {
        connectionKinds[connectionId] = CONNECTION_EMPTY;
        if (!hasOpenConnection()) connectionBackendGeneration = null;
    }

    private boolean hasOpenConnection() {
        for (int kind : connectionKinds) {
            if (kind != CONNECTION_EMPTY) return true;
        }
        return false;
    }

    private void unsupported() {
        outcome = Outcome.UNSUPPORTED_COMMAND;
        error = ErrorCode.UNSUPPORTED_COMMAND;
        acknowledgement = acknowledgement(ACK_UNSUPPORTED);
    }

    private void reject(Outcome rejectedOutcome, ErrorCode rejectedError) {
        outcome = rejectedOutcome;
        error = rejectedError;
        clearOutput();
        clearParser();
    }

    private void timeoutReset() {
        phase = Phase.SLEEP;
        outcome = Outcome.IDLE_TIMEOUT_RESET;
        error = ErrorCode.NONE;
        clearParser();
        clearOutput();
        pendingPacketSlots = 0;
        cancelBackendOwnership();
        serialByteObserved = false;
        idlePhaseUnits = 0;
    }

    private void beginInputOperation() {
        outcome = Outcome.NEED_MORE;
        error = ErrorCode.NONE;
        clearOutput();
    }

    private void clearParser() {
        Arrays.fill(packetBuffer, (byte) 0);
        packetCount = 0;
        expectedPacketBytes = -1;
    }

    private void clearOutput() {
        responsePacket = EMPTY_BYTES;
        acknowledgement = EMPTY_BYTES;
    }

    private byte[] acknowledgement(int secondByte) {
        return new byte[]{(byte) (deviceId | 0x80), (byte) secondByte};
    }

    private static byte[] packet(int command, byte[] data) {
        if (data.length > MAX_PACKET_DATA_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter response data exceeds packet limit");
        }
        byte[] result = new byte[Math.addExact(8, data.length)];
        result[0] = (byte) MAGIC_1;
        result[1] = (byte) MAGIC_2;
        result[2] = (byte) command;
        result[3] = 0;
        result[4] = (byte) (data.length >>> 8);
        result[5] = (byte) data.length;
        System.arraycopy(data, 0, result, 6, data.length);
        int checksum = 0;
        for (int i = 2; i < 6 + data.length; i++) {
            checksum = (checksum + (result[i] & 0xff)) & 0xffff;
        }
        result[6 + data.length] = (byte) (checksum >>> 8);
        result[7 + data.length] = (byte) checksum;
        return result;
    }

    private EngineResult snapshot(Outcome visibleOutcome, ErrorCode visibleError) {
        return new EngineResult(
                phase,
                visibleOutcome,
                visibleError,
                responsePacket,
                acknowledgement,
                packetCount,
                pendingPacketSlots);
    }

    private void validateState(MobileAdapterEngineNetworkState state) {
        Phase restoredPhase = Phase.fromId(state.phaseId);
        Outcome restoredOutcome = Outcome.fromId(state.outcomeId);
        ErrorCode restoredError = ErrorCode.fromId(state.errorId);
        requireDeviceId(state.deviceId);

        byte[] restoredPacket = state.packetBuffer();
        if (restoredPacket.length != MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter parser buffer must contain 262 bytes");
        }
        if (state.packetCount < 0 || state.packetCount > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter parser count is outside its buffer");
        }
        if (state.packetCount < 6) {
            if (state.expectedPacketBytes != -1) {
                throw new IllegalArgumentException("Partial Mobile Adapter header has an expected size");
            }
        } else {
            int declared = unsigned16(restoredPacket, 4);
            int expected = Math.addExact(8, declared);
            if (declared > MAX_PACKET_DATA_BYTES || state.expectedPacketBytes != expected ||
                    state.packetCount >= expected) {
                throw new IllegalArgumentException("Mobile Adapter retained packet length is invalid");
            }
        }
        if (state.packetCount >= 2 &&
                ((restoredPacket[0] & 0xff) != MAGIC_1 ||
                        (restoredPacket[1] & 0xff) != MAGIC_2)) {
            throw new IllegalArgumentException("Mobile Adapter retained packet magic is invalid");
        }
        if (state.packetCount >= 4 && restoredPacket[3] != 0) {
            throw new IllegalArgumentException("Mobile Adapter retained packet reserved byte is invalid");
        }
        for (int i = state.packetCount; i < restoredPacket.length; i++) {
            if (restoredPacket[i] != 0) {
                throw new IllegalArgumentException("Mobile Adapter parser contains stale trailing bytes");
            }
        }

        if (state.configuration().length != CONFIGURATION_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter configuration must contain 256 bytes");
        }
        byte[] restoredResponse = state.responsePacket();
        if (restoredResponse.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter response exceeds packet limit");
        }
        if (restoredResponse.length != 0) validateOutputPacket(restoredResponse);
        byte[] restoredAck = state.acknowledgement();
        int ackLength = restoredAck.length;
        if (ackLength != 0 && ackLength != 2) {
            throw new IllegalArgumentException("Mobile Adapter acknowledgement must be empty or two bytes");
        }
        if (ackLength == 2 && (restoredAck[0] & 0xff) != (state.deviceId | 0x80)) {
            throw new IllegalArgumentException("Mobile Adapter acknowledgement device ID is invalid");
        }
        if (state.idlePhaseUnits < 0 || state.idlePhaseUnits > idleBoundaryPhaseUnits) {
            throw new IllegalArgumentException("Mobile Adapter idle timer is outside its boundary");
        }
        if (state.idlePhaseUnits % phaseUnitsPerTick != 0) {
            throw new IllegalArgumentException("Mobile Adapter idle timer is not aligned to a master tick");
        }
        if (!state.serialByteObserved && state.idlePhaseUnits != 0) {
            throw new IllegalArgumentException("Mobile Adapter idle phase exists without serial input");
        }
        if (state.packetCount > 0 && !state.serialByteObserved) {
            throw new IllegalArgumentException("Mobile Adapter retained packet has no serial input owner");
        }
        if (state.pendingPacketSlots < 0 ||
                state.pendingPacketSlots > MAX_PENDING_PACKET_SLOTS) {
            throw new IllegalArgumentException("Mobile Adapter pending packet count is invalid");
        }
        if (state.externalIoAtCapture &&
                (restoredOutcome != Outcome.EXTERNAL_IO_DISCONNECTED ||
                        restoredError != ErrorCode.EXTERNAL_IO_DISCONNECTED ||
                        restoredResponse.length != 0 || restoredAck.length != 0)) {
            throw new IllegalArgumentException(
                    "Captured Mobile Adapter external I/O is not deterministically disconnected");
        }
        validateOutcomeState(
                restoredPhase,
                restoredOutcome,
                restoredError,
                restoredResponse,
                restoredAck,
                state.packetCount,
                state.idlePhaseUnits,
                state.serialByteObserved,
                state.pendingPacketSlots,
                state.externalIoAtCapture);
    }

    private void validateOutcomeState(Phase restoredPhase, Outcome restoredOutcome,
                                      ErrorCode restoredError, byte[] restoredResponse,
                                      byte[] restoredAck, int restoredPacketCount,
                                      long restoredIdlePhaseUnits,
                                      boolean restoredSerialByteObserved,
                                      int restoredPendingSlots,
                                      boolean externalIoAtCapture) {
        if (restoredOutcome == Outcome.BACKEND_PENDING) {
            throw new IllegalArgumentException(
                    "Live Mobile Adapter backend ownership cannot be captured");
        }
        ErrorCode expectedError = switch (restoredOutcome) {
            case CHECKSUM_ERROR -> ErrorCode.CHECKSUM;
            case UNSUPPORTED_COMMAND -> ErrorCode.UNSUPPORTED_COMMAND;
            case MAGIC_ERROR -> ErrorCode.INVALID_MAGIC;
            case RESERVED_ERROR -> ErrorCode.RESERVED_VALUE;
            case LENGTH_LIMIT -> ErrorCode.LENGTH_LIMIT;
            case BUFFER_LIMIT -> ErrorCode.BUFFER_LIMIT;
            case PENDING_LIMIT -> ErrorCode.PENDING_LIMIT;
            case EXTERNAL_IO_DISCONNECTED -> ErrorCode.EXTERNAL_IO_DISCONNECTED;
            default -> ErrorCode.NONE;
        };
        boolean backendError = restoredOutcome == Outcome.BACKEND_ERROR &&
                (restoredError == ErrorCode.BACKEND_BUSY ||
                        restoredError == ErrorCode.BACKEND_UNAVAILABLE ||
                        restoredError == ErrorCode.BACKEND_RESPONSE_INVALID);
        if (!backendError && restoredError != expectedError) {
            throw new IllegalArgumentException("Mobile Adapter outcome/error state is inconsistent");
        }
        if (restoredOutcome == Outcome.TIME_REGRESSION) {
            throw new IllegalArgumentException("Transient Mobile Adapter time regression cannot be captured");
        }

        int expectedAck = switch (restoredOutcome) {
            case SESSION_STARTED -> COMMAND_BEGIN_SESSION ^ 0x80;
            case SESSION_ENDED -> COMMAND_END_SESSION ^ 0x80;
            case SESSION_RESET -> COMMAND_RESET ^ 0x80;
            case CONFIG_READ, CONFIG_READ_BOUNDARY -> COMMAND_CONFIG_READ ^ 0x80;
            case CONFIG_WRITE -> COMMAND_CONFIG_WRITE ^ 0x80;
            case CHECKSUM_ERROR -> ACK_CHECKSUM_ERROR;
            case UNSUPPORTED_COMMAND -> ACK_UNSUPPORTED;
            default -> -1;
        };
        boolean internalErrorAck =
                (restoredOutcome == Outcome.BACKEND_ERROR ||
                        restoredOutcome == Outcome.PENDING_LIMIT) &&
                restoredResponse.length == 0 && restoredAck.length == 2 &&
                (restoredAck[1] & 0xff) == ACK_INTERNAL_ERROR;
        if (!internalErrorAck &&
                ((expectedAck == -1) != (restoredAck.length == 0) ||
                        expectedAck != -1 && (restoredAck[1] & 0xff) != expectedAck)) {
            throw new IllegalArgumentException("Mobile Adapter outcome/acknowledgement is inconsistent");
        }

        boolean expectsResponse = switch (restoredOutcome) {
            case SESSION_STARTED, SESSION_ENDED, SESSION_RESET,
                    CONFIG_READ, CONFIG_READ_BOUNDARY, CONFIG_WRITE,
                    BACKEND_RESPONSE, BACKEND_ERROR, BACKEND_REMOTE_CLOSED -> true;
            default -> false;
        };
        if (internalErrorAck) expectsResponse = false;
        if (expectsResponse == (restoredResponse.length == 0)) {
            throw new IllegalArgumentException("Mobile Adapter outcome/response is inconsistent");
        }
        if (expectsResponse && expectedAck != -1 &&
                (restoredResponse[2] & 0xff) != expectedAck) {
            throw new IllegalArgumentException("Mobile Adapter response command is inconsistent");
        }
        if (expectsResponse) {
            byte[] responseData = Arrays.copyOfRange(
                    restoredResponse, 6, restoredResponse.length - 2);
            switch (restoredOutcome) {
                case SESSION_STARTED -> {
                    if (!Arrays.equals(responseData, BEGIN_SESSION_DATA)) {
                        throw new IllegalArgumentException("Mobile Adapter begin response is invalid");
                    }
                }
                case SESSION_ENDED, SESSION_RESET -> {
                    if (responseData.length != 0) {
                        throw new IllegalArgumentException("Mobile Adapter empty response has data");
                    }
                }
                case CONFIG_READ, CONFIG_READ_BOUNDARY ->
                        validateConfigurationResponse(restoredOutcome, responseData);
                case CONFIG_WRITE -> {
                    if (responseData.length != 1) {
                        throw new IllegalArgumentException(
                                "Mobile Adapter configuration-write response is invalid");
                    }
                }
                case BACKEND_RESPONSE -> validateCapturedBackendResponse(restoredResponse[2] & 0xff,
                        responseData);
                case BACKEND_ERROR -> {
                    if ((restoredResponse[2] & 0xff) != COMMAND_ERROR_STATUS) {
                        throw new IllegalArgumentException(
                                "Mobile Adapter backend error response command is invalid");
                    }
                    validateCapturedBackendError(responseData);
                }
                case BACKEND_REMOTE_CLOSED -> {
                    if ((restoredResponse[2] & 0xff) != (COMMAND_REMOTE_CLOSED | 0x80) ||
                            responseData.length != 0) {
                        throw new IllegalArgumentException(
                                "Mobile Adapter remote-close response is invalid");
                    }
                }
                default -> throw new IllegalArgumentException("Unexpected Mobile Adapter response");
            }
        }

        if ((restoredOutcome == Outcome.SESSION_STARTED ||
                restoredOutcome == Outcome.SESSION_RESET) && restoredPhase != Phase.SESSION) {
            throw new IllegalArgumentException("Mobile Adapter session result has the wrong phase");
        }
        if ((restoredOutcome == Outcome.SESSION_ENDED ||
                restoredOutcome == Outcome.IDLE_TIMEOUT_RESET ||
                restoredOutcome == Outcome.CANCELLED) && restoredPhase != Phase.SLEEP) {
            throw new IllegalArgumentException("Mobile Adapter terminal result has the wrong phase");
        }
        if (restoredOutcome == Outcome.IDLE_BOUNDARY_WAIT &&
                (restoredPacketCount == 0 || restoredIdlePhaseUnits != idleBoundaryPhaseUnits)) {
            throw new IllegalArgumentException("Mobile Adapter idle-boundary state is inconsistent");
        }
        if ((restoredOutcome == Outcome.IDLE_TIMEOUT_RESET ||
                restoredOutcome == Outcome.CANCELLED) && restoredSerialByteObserved) {
            throw new IllegalArgumentException("Mobile Adapter cleanup retained idle ownership");
        }
        if (restoredPhase == Phase.SESSION && !restoredSerialByteObserved) {
            throw new IllegalArgumentException("Mobile Adapter session has no serial input owner");
        }
        boolean commandDerivedOutcome = switch (restoredOutcome) {
            case NEED_MORE, PENDING_LIMIT, IDLE_TIMEOUT_RESET, CANCELLED -> false;
            default -> true;
        };
        if (commandDerivedOutcome && !restoredSerialByteObserved) {
            throw new IllegalArgumentException("Mobile Adapter result has no serial input owner");
        }
        if (restoredOutcome == Outcome.PENDING_LIMIT &&
                restoredPendingSlots != MAX_PENDING_PACKET_SLOTS) {
            throw new IllegalArgumentException("Mobile Adapter pending-limit state is inconsistent");
        }
        if (restoredOutcome != Outcome.NEED_MORE &&
                restoredOutcome != Outcome.IDLE_BOUNDARY_WAIT &&
                restoredOutcome != Outcome.PENDING_LIMIT &&
                !(externalIoAtCapture &&
                        restoredOutcome == Outcome.EXTERNAL_IO_DISCONNECTED) &&
                restoredPacketCount != 0) {
            throw new IllegalArgumentException("Completed Mobile Adapter result retained parser bytes");
        }
        if (externalIoAtCapture && restoredPhase != Phase.SESSION) {
            throw new IllegalArgumentException(
                    "Captured Mobile Adapter external I/O must belong to a session");
        }
    }

    private static void validateCapturedBackendResponse(int responseCommand, byte[] responseData) {
        switch (responseCommand) {
            case COMMAND_TCP_CLOSE | 0x80, COMMAND_UDP_CLOSE | 0x80 -> {
                if (responseData.length != 1 || (responseData[0] & 0xff) >= MAX_LOGICAL_CONNECTIONS) {
                    throw new IllegalArgumentException(
                            "Mobile Adapter close completion is invalid");
                }
            }
            case COMMAND_DNS_QUERY | 0x80 -> {
                if (responseData.length != 4) {
                    throw new IllegalArgumentException(
                            "Mobile Adapter DNS completion is invalid");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Captured Mobile Adapter completion would require a live connection");
        }
    }

    private static void validateCapturedBackendError(byte[] responseData) {
        if (responseData.length != 2) {
            throw new IllegalArgumentException("Mobile Adapter backend error response is invalid");
        }
        int command = responseData[0] & 0xff;
        int error = responseData[1] & 0xff;
        boolean valid = switch (command) {
            case COMMAND_TRANSFER -> error == 0x00 || error == 0x01;
            case COMMAND_TCP_OPEN, COMMAND_UDP_OPEN ->
                    error == 0x00 || error == 0x01 || error == 0x03;
            case COMMAND_TCP_CLOSE, COMMAND_UDP_CLOSE -> error <= 0x02;
            case COMMAND_DNS_QUERY -> error == 0x01 || error == 0x02;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Mobile Adapter backend error code is invalid");
        }
    }

    private static void validateConfigurationResponse(Outcome outcome, byte[] responseData) {
        if (responseData.length < 1 ||
                responseData.length > MAX_CONFIGURATION_OPERATION_BYTES + 1) {
            throw new IllegalArgumentException("Mobile Adapter configuration response is invalid");
        }
        int offset = responseData[0] & 0xff;
        int requested = responseData.length - 1;
        if (offset + requested > CONFIGURATION_BYTES ||
                (outcome == Outcome.CONFIG_READ_BOUNDARY) !=
                        (requested == MAX_CONFIGURATION_OPERATION_BYTES)) {
            throw new IllegalArgumentException("Mobile Adapter configuration response bounds are invalid");
        }
    }

    private static void validateOutputPacket(byte[] bytes) {
        if (bytes.length < 8 || (bytes[0] & 0xff) != MAGIC_1 ||
                (bytes[1] & 0xff) != MAGIC_2 || bytes[3] != 0) {
            throw new IllegalArgumentException("Mobile Adapter response framing is invalid");
        }
        int length = unsigned16(bytes, 4);
        if (length > MAX_PACKET_DATA_BYTES || bytes.length != 8 + length) {
            throw new IllegalArgumentException("Mobile Adapter response length is invalid");
        }
        int expectedChecksum = 0;
        for (int i = 2; i < 6 + length; i++) {
            expectedChecksum = (expectedChecksum + (bytes[i] & 0xff)) & 0xffff;
        }
        if (unsigned16(bytes, 6 + length) != expectedChecksum) {
            throw new IllegalArgumentException("Mobile Adapter response checksum is invalid");
        }
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int requireDeviceId(int value) {
        if (value < 0 || value > 0x7f) {
            throw new IllegalArgumentException("Mobile Adapter device ID must be in 0..127");
        }
        return value;
    }

    private static byte[] requireConfiguration(byte[] value) {
        Objects.requireNonNull(value, "configuration");
        if (value.length != CONFIGURATION_BYTES) {
            throw new IllegalArgumentException("Mobile Adapter configuration must contain 256 bytes");
        }
        return value.clone();
    }

    public enum Phase {
        SLEEP(1),
        SESSION(2);

        private final int id;

        Phase(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Phase fromId(int id) {
            for (Phase value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown Mobile Adapter phase " + id);
        }
    }

    public enum Outcome {
        NEED_MORE(1),
        SESSION_STARTED(2),
        SESSION_ENDED(3),
        SESSION_RESET(4),
        CHECKSUM_ERROR(5),
        IDLE_TIMEOUT_RESET(6),
        IDLE_BOUNDARY_WAIT(7),
        CONFIG_READ(8),
        CONFIG_READ_BOUNDARY(9),
        UNSUPPORTED_COMMAND(10),
        MAGIC_ERROR(11),
        RESERVED_ERROR(12),
        LENGTH_LIMIT(13),
        BUFFER_LIMIT(14),
        TIME_REGRESSION(15),
        CANCELLED(16),
        PENDING_LIMIT(17),
        CONFIG_WRITE(18),
        BACKEND_PENDING(19),
        BACKEND_RESPONSE(20),
        BACKEND_ERROR(21),
        BACKEND_REMOTE_CLOSED(22),
        EXTERNAL_IO_DISCONNECTED(23);

        private final int id;

        Outcome(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Outcome fromId(int id) {
            for (Outcome value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown Mobile Adapter outcome " + id);
        }
    }

    public enum ErrorCode {
        NONE(0),
        INVALID_MAGIC(1),
        RESERVED_VALUE(2),
        LENGTH_LIMIT(3),
        CHECKSUM(4),
        UNSUPPORTED_COMMAND(5),
        BUFFER_LIMIT(6),
        TIME_REGRESSION(7),
        PENDING_LIMIT(8),
        BACKEND_BUSY(9),
        BACKEND_UNAVAILABLE(10),
        BACKEND_RESPONSE_INVALID(11),
        EXTERNAL_IO_DISCONNECTED(12);

        private final int id;

        ErrorCode(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static ErrorCode fromId(int id) {
            for (ErrorCode value : values()) {
                if (value.id == id) return value;
            }
            throw new IllegalArgumentException("Unknown Mobile Adapter error " + id);
        }
    }

    /** Immutable operation result with separately owned response and acknowledgement channels. */
    public record EngineResult(
            Phase phase,
            Outcome outcome,
            ErrorCode error,
            byte[] responsePacket,
            byte[] acknowledgement,
            int retainedBytes,
            int pendingPacketSlots) {

        public EngineResult {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(error, "error");
            responsePacket = responsePacket.clone();
            acknowledgement = acknowledgement.clone();
        }

        @Override
        public byte[] responsePacket() {
            return responsePacket.clone();
        }

        @Override
        public byte[] acknowledgement() {
            return acknowledgement.clone();
        }
    }

    /** Released Phase-1 state inventory; its thirteen record components are immutable. */
    public record MobileAdapterEngineState(
            int phaseId,
            int outcomeId,
            int errorId,
            int deviceId,
            byte[] packetBuffer,
            int packetCount,
            int expectedPacketBytes,
            byte[] configuration,
            byte[] responsePacket,
            byte[] acknowledgement,
            long idlePhaseUnits,
            boolean serialByteObserved,
            int pendingPacketSlots) implements ComponentState<MobileAdapterEngine> {

        public MobileAdapterEngineState {
            packetBuffer = packetBuffer.clone();
            configuration = configuration.clone();
            responsePacket = responsePacket.clone();
            acknowledgement = acknowledgement.clone();
        }

        @Override
        public byte[] packetBuffer() {
            return packetBuffer.clone();
        }

        @Override
        public byte[] configuration() {
            return configuration.clone();
        }

        @Override
        public byte[] responsePacket() {
            return responsePacket.clone();
        }

        @Override
        public byte[] acknowledgement() {
            return acknowledgement.clone();
        }
    }

    /** Additive Phase-2 state used only when capture observes external backend ownership. */
    public record MobileAdapterEngineNetworkState(
            int phaseId,
            int outcomeId,
            int errorId,
            int deviceId,
            byte[] packetBuffer,
            int packetCount,
            int expectedPacketBytes,
            byte[] configuration,
            byte[] responsePacket,
            byte[] acknowledgement,
            long idlePhaseUnits,
            boolean serialByteObserved,
            int pendingPacketSlots,
            boolean externalIoAtCapture) implements ComponentState<MobileAdapterEngine> {

        public MobileAdapterEngineNetworkState {
            packetBuffer = packetBuffer.clone();
            configuration = configuration.clone();
            responsePacket = responsePacket.clone();
            acknowledgement = acknowledgement.clone();
        }

        @Override
        public byte[] packetBuffer() {
            return packetBuffer.clone();
        }

        @Override
        public byte[] configuration() {
            return configuration.clone();
        }

        @Override
        public byte[] responsePacket() {
            return responsePacket.clone();
        }

        @Override
        public byte[] acknowledgement() {
            return acknowledgement.clone();
        }
    }
}
