package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import java.util.Arrays;
import java.util.function.Consumer;

/** ASCII Turbo File GB / Sammy Turbo File Advance, externally clocked eight-bit link storage. */
public final class TurboFileSerialEndpoint implements SerialEndpoint {
    public static final int IMAGE_BYTES = 1024 * 1024;
    public static final int STORAGE_BYTES = 2 * IMAGE_BYTES;
    public static final int BIT_TICKS = 512;
    private static final int SYNC = 0, MAGIC = 1, PACKET = 2, SYNC_F1 = 3, SYNC_7E = 4, RESPONSE = 5;
    private final boolean advance;
    private final Consumer<byte[]> persistence;
    private final byte[] storage = new byte[STORAGE_BYTES];
    private final int[] packet = new int[69];
    private final int[] response = new int[68];
    private int phase, packetLength, expectedLength, responseLength, responseIndex;
    private int readBank, writeBank, currentBank;
    private boolean initialized, cardPresent, writeProtected;
    private int sb, outgoing, incoming, bits, ticks;
    private boolean armed, external;
    // Host persistence bookkeeping, intentionally absent from emulated state and replay hashes.
    private boolean dirty;

    public TurboFileSerialEndpoint() { this(false); }
    public TurboFileSerialEndpoint(boolean advance) { this(advance, null); }
    public TurboFileSerialEndpoint(boolean advance, Consumer<byte[]> persistence) {
        this.advance = advance;
        this.persistence = persistence;
        Arrays.fill(storage, (byte) 0xff);
    }

    public boolean isAdvance() { return advance; }
    public boolean isCardPresent() { return cardPresent; }
    public boolean isWriteProtected() { return writeProtected; }
    public boolean hasUnsavedStorage() { return dirty; }
    public void setCardPresent(boolean present) { cardPresent = present; resetProtocol(); }
    public void setWriteProtected(boolean protectedMode) { writeProtected = protectedMode; }

    public byte[] exportImage(boolean card) {
        return Arrays.copyOfRange(storage, card ? IMAGE_BYTES : 0, card ? STORAGE_BYTES : IMAGE_BYTES);
    }

    public byte[] exportStorage() { return storage.clone(); }

    /** Loads durable flash at attachment; presence of a removable card is a separate switch. */
    public void loadStorage(byte[] data) {
        if (data == null || data.length != STORAGE_BYTES) throw new IllegalArgumentException("Turbo File storage must be 2 MiB");
        System.arraycopy(data, 0, storage, 0, storage.length);
        dirty = false;
        resetProtocol();
    }

    public void importImage(byte[] image, boolean card) {
        if (image == null || image.length != IMAGE_BYTES) throw new IllegalArgumentException("Turbo File images must be 1 MiB");
        System.arraycopy(image, 0, storage, card ? IMAGE_BYTES : 0, IMAGE_BYTES);
        if (card) cardPresent = true;
        dirty = true;
        resetProtocol();
    }

    /** Host flush; failures retain dirty data for a retry and must be surfaced by the host. */
    public void flushStorage() {
        if (dirty && persistence != null) {
            persistence.accept(storage.clone());
            dirty = false;
        }
    }

    @Override public void setSb(int sb) { this.sb = sb & 0xff; }
    @Override public void startSending() {
        incoming = sb;
        outgoing = switch (phase) {
            case SYNC -> sb == 0x6c ? 0xc6 : 0;
            case SYNC_F1, SYNC_7E -> sb == 0xf1 ? 0xe7 : sb == 0x7e ? 0xa5 : 0;
            case RESPONSE -> response[responseIndex];
            default -> 0;
        };
        bits = 0; ticks = 0; armed = true;
    }
    @Override public void setExternalTransfer(boolean active) {
        // A newly attached device may find SC already waiting for its external clock.
        if (active && !external && !armed) startSending();
        external = active;
        if (!active) armed = false;
    }
    @Override public void tick() {
        if (armed && external && ticks < BIT_TICKS) ticks++;
    }
    @Override public int recvBit() {
        if (!armed || !external || ticks < BIT_TICKS) return -1;
        ticks = 0;
        int result = (outgoing >>> (7 - bits)) & 1;
        if (++bits == 8) {
            armed = false;
            accept(incoming);
        }
        return result;
    }
    // Turbo File supplies its own clock; it does not reply to internally clocked transfers.
    @Override public int sendBit() { return 1; }
    @Override public void disconnect() { resetProtocol(); flushStorage(); }

    private void resetProtocol() {
        phase = SYNC; packetLength = expectedLength = responseLength = responseIndex = 0;
        armed = external = false; bits = ticks = 0;
    }

    private void accept(int value) {
        switch (phase) {
            case SYNC -> { if (value == 0x6c) phase = MAGIC; }
            case MAGIC -> {
                if (value == 0x5a) { packet[0] = value; packetLength = 1; expectedLength = 0; phase = PACKET; }
                else phase = SYNC;
            }
            case PACKET -> {
                packet[packetLength++] = value;
                if (packetLength == 2) {
                    expectedLength = switch (value) {
                        case 0x10, 0x24 -> 3;
                        case 0x20 -> 4;
                        case 0x22, 0x23, 0x40 -> 5;
                        case 0x30 -> 69;
                        case 0x34 -> advance ? 6 : 0;
                        default -> 0;
                    };
                    if (expectedLength == 0) { resetProtocol(); return; }
                }
                if (packetLength == expectedLength) {
                    int checksum = 0;
                    for (int i = 0; i < packetLength; i++) checksum += packet[i];
                    if ((checksum & 0xff) != 0) { resetProtocol(); return; }
                    execute(); phase = SYNC_F1;
                }
            }
            case SYNC_F1 -> {
                // RPG Tsukuru GB can send an extra 7E before F1. Wait for the complete pair.
                if (value == 0xf1) phase = SYNC_7E;
                else if (value == 0x6c) phase = MAGIC;
            }
            case SYNC_7E -> { if (value == 0x7e) phase = RESPONSE; else if (value != 0xf1) resetProtocol(); }
            case RESPONSE -> { if (++responseIndex == responseLength) resetProtocol(); }
            default -> throw new IllegalStateException("Invalid Turbo File phase");
        }
    }

    // Bit 1 is observed high in the documented implementation, although its meaning is unknown.
    private int status() { return 3 | (initialized ? 8 : 0) | (writeProtected ? 0x80 : 0); }

    private void execute() {
        int command = packet[1];
        boolean valid = true;
        if (command == 0x22 || command == 0x23) {
            valid = packet[2] <= 1 && packet[3] <= 0x7f;
            if (valid) {
                currentBank = (packet[2] << 7) | packet[3];
                if (command == 0x22) writeBank = currentBank; else readBank = currentBank;
                initialized = true;
            }
        }
        Arrays.fill(response, 0);
        response[0] = command;
        responseLength = command == 0x10 ? 9 : command == 0x40 ? 68 : 4;
        if (command == 0x10) {
            response[3] = cardPresent ? 5 : 1;
            response[4] = currentBank >>> 7; response[5] = currentBank & 0x7f;
        } else if (command == 0x30 || command == 0x34 || command == 0x40) {
            int bank = command == 0x40 ? readBank : writeBank;
            int offset = (packet[2] << 8) | packet[3];
            valid = initialized && offset <= 0x2000 - 64 && (bank < 128 || cardPresent);
            if (valid) {
                int base = bank * 0x2000 + offset;
                if (command == 0x40) {
                    for (int i = 0; i < 64; i++) response[3 + i] = storage[base + i] & 0xff;
                } else if (!writeProtected) {
                    for (int i = 0; i < 64; i++) storage[base + i] = (byte) packet[command == 0x34 ? 4 : 4 + i];
                    dirty = true;
                }
            }
        }
        response[2] = valid ? status() : status() & ~1;
        int checksum = 0xa5;
        for (int i = 0; i < responseLength - 1; i++) checksum += response[i];
        response[responseLength - 1] = -checksum & 0xff;
        responseIndex = 0;
        if (command == 0x24) {
            try { flushStorage(); }
            catch (RuntimeException ignored) {
                // The host reports its I/O failure. Keep dirty storage and the wire reply intact.
            }
        }
    }

    @Override public ComponentState<SerialEndpoint> captureState() {
        return state(storage.clone(), packet.clone(), response.clone());
    }
    @Override public ComponentState<SerialEndpoint> captureState(MachineStateCapture capture) {
        return state(capture.bytes(storage), capture.ints(packet), capture.ints(response));
    }
    @Override public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareBytes(storage); capture.declareInts(packet); capture.declareInts(response);
    }
    private TurboFileState state(byte[] memory, int[] request, int[] reply) {
        return new TurboFileState(advance, memory, request, reply, phase, packetLength, expectedLength,
                responseLength, responseIndex, readBank, writeBank, currentBank, initialized,
                cardPresent, writeProtected, sb, outgoing, incoming, bits, ticks, armed, external);
    }
    @Override public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof TurboFileState s) || s.advance != advance
                || s.storage == null || s.storage.length != STORAGE_BYTES
                || s.packet == null || s.packet.length != 69 || s.response == null || s.response.length != 68
                || s.phase < 0 || s.phase > RESPONSE || s.packetLength < 0 || s.packetLength > 69
                || s.expectedLength < 0 || s.expectedLength > 69
                || s.responseLength < 0 || s.responseLength > 68 || s.responseIndex < 0 || s.responseIndex > 67
                || (s.phase == RESPONSE && s.responseIndex >= s.responseLength)
                || s.bits < 0 || s.bits > 8 || s.ticks < 0 || s.ticks > BIT_TICKS
                || (s.armed && s.bits == 8)
                || (s.phase == PACKET && (s.packetLength < 1 || s.packetLength >= 69
                    || (s.packetLength == 1 ? s.expectedLength != 0
                        : s.packetLength >= s.expectedLength)))
                || (s.readBank | s.writeBank | s.currentBank | s.sb | s.outgoing | s.incoming) < 0
                || s.readBank > 255 || s.writeBank > 255 || s.currentBank > 255
                || s.sb > 255 || s.outgoing > 255 || s.incoming > 255
                || Arrays.stream(s.packet).anyMatch(v -> v < 0 || v > 255)
                || Arrays.stream(s.response).anyMatch(v -> v < 0 || v > 255)) {
            throw new IllegalArgumentException("Invalid Turbo File state");
        }
        dirty |= !Arrays.equals(storage, s.storage);
        System.arraycopy(s.storage, 0, storage, 0, storage.length);
        System.arraycopy(s.packet, 0, packet, 0, packet.length);
        System.arraycopy(s.response, 0, response, 0, response.length);
        phase = s.phase; packetLength = s.packetLength; expectedLength = s.expectedLength;
        responseLength = s.responseLength; responseIndex = s.responseIndex;
        readBank = s.readBank; writeBank = s.writeBank; currentBank = s.currentBank;
        initialized = s.initialized; cardPresent = s.cardPresent; writeProtected = s.writeProtected;
        sb = s.sb; outgoing = s.outgoing; incoming = s.incoming; bits = s.bits; ticks = s.ticks;
        armed = s.armed; external = s.external;
    }
    private record TurboFileState(boolean advance, byte[] storage, int[] packet, int[] response,
            int phase, int packetLength, int expectedLength, int responseLength, int responseIndex,
            int readBank, int writeBank, int currentBank, boolean initialized,
            boolean cardPresent, boolean writeProtected, int sb, int outgoing, int incoming,
            int bits, int ticks, boolean armed, boolean external) implements ComponentState<SerialEndpoint> { }
}
