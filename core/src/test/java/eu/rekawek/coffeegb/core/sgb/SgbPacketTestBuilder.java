package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.state.ComponentState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ROM-independent SGB packet fixture.
 *
 * <p>The fixture deliberately drives the production JOYP pulse receiver and
 * {@link SuperGameboy} packet collector. It validates only fixture-side bounds; raw headers can
 * still describe malformed production inputs so current recovery behavior can be baselined.
 */
public final class SgbPacketTestBuilder implements AutoCloseable {

    public static final int PACKET_BYTES = 16;

    public static final int MAX_PACKETS = 7;

    public static final int MAX_PAYLOAD_BYTES = PACKET_BYTES * MAX_PACKETS - 1;

    private final EventBusImpl sgbBus = new EventBusImpl(null, null, false);

    private final Joypad joypad;

    private final SuperGameboy superGameboy = new SuperGameboy(sgbBus);

    private final List<int[]> receivedPackets = new ArrayList<>();

    private final List<Commands.AbstractCommand> commands = new ArrayList<>();

    public SgbPacketTestBuilder() {
        this(PlayerInputSource.RELEASED);
    }

    public SgbPacketTestBuilder(PlayerInputSource playerInputSource) {
        joypad = new Joypad(new InterruptManager(false), sgbBus, true, playerInputSource);
        sgbBus.register(event -> receivedPackets.add(event.packet().clone()),
                SuperGameboy.PacketReceivedEvent.class);
        sgbBus.register(commands::add, Commands.AbstractCommand.class);
    }

    public static List<int[]> command(int commandId, int packetCount, int... payload) {
        if (commandId < 0 || commandId > 0x1f) {
            throw new IllegalArgumentException("SGB command ID must be in 0x00..0x1f");
        }
        if (packetCount < 1 || packetCount > MAX_PACKETS) {
            throw new IllegalArgumentException("SGB packet count must be in 1..7");
        }
        int capacity = packetCount * PACKET_BYTES - 1;
        if (payload.length > capacity) {
            throw new IllegalArgumentException("Payload exceeds the declared SGB packet count");
        }
        List<int[]> packets = new ArrayList<>(packetCount);
        for (int i = 0; i < packetCount; i++) {
            packets.add(new int[PACKET_BYTES]);
        }
        packets.get(0)[0] = commandId << 3 | packetCount;
        for (int i = 0; i < payload.length; i++) {
            int flatIndex = i + 1;
            int packetIndex = flatIndex / PACKET_BYTES;
            int byteIndex = flatIndex % PACKET_BYTES;
            int value = payload[i];
            if (value < 0 || value > 0xff) {
                throw new IllegalArgumentException("SGB payload values must be bytes");
            }
            packets.get(packetIndex)[byteIndex] = value;
        }
        return Collections.unmodifiableList(packets);
    }

    public static int[] rawPacket(int header, int... data) {
        if (header < 0 || header > 0xff || data.length > PACKET_BYTES - 1) {
            throw new IllegalArgumentException("Raw SGB packet fields exceed one packet");
        }
        int[] packet = new int[PACKET_BYTES];
        packet[0] = header;
        for (int i = 0; i < data.length; i++) {
            if (data[i] < 0 || data[i] > 0xff) {
                throw new IllegalArgumentException("SGB packet values must be bytes");
            }
            packet[i + 1] = data[i];
        }
        return packet;
    }

    public void sendCommand(int commandId, int packetCount, int... payload) {
        command(commandId, packetCount, payload).forEach(this::sendPacket);
    }

    public void sendPacket(int[] packet) {
        requirePacket(packet);
        startPacket();
        writeBits(packet, 0, PACKET_BYTES * 8);
        writeSelector(0x20); // stop pulse; production ignores its value
        writeSelector(0x30);
    }

    public void sendIncomplete(int[] packet, int bits) {
        requirePacket(packet);
        if (bits < 0 || bits > PACKET_BYTES * 8) {
            throw new IllegalArgumentException("Incomplete transfer length is outside 0..128 bits");
        }
        startPacket();
        writeBits(packet, 0, bits);
    }

    public void writeRemainingBits(int[] packet, int fromBit) {
        requirePacket(packet);
        if (fromBit < 0 || fromBit > PACKET_BYTES * 8) {
            throw new IllegalArgumentException("Resume bit is outside 0..128");
        }
        writeBits(packet, fromBit, PACKET_BYTES * 8);
        writeSelector(0x20);
        writeSelector(0x30);
    }

    public void restartReceiver() {
        writeSelector(0x00);
        writeSelector(0x30);
    }

    public ComponentState<SuperGameboy> captureCollectorState() {
        return superGameboy.captureState();
    }

    public void restoreCollectorState(ComponentState<SuperGameboy> state) {
        superGameboy.restoreState(state);
    }

    public Joypad joypad() {
        return joypad;
    }

    EventBusImpl sgbBus() {
        return sgbBus;
    }

    public List<int[]> receivedPackets() {
        List<int[]> copy = new ArrayList<>(receivedPackets.size());
        receivedPackets.forEach(packet -> copy.add(packet.clone()));
        return Collections.unmodifiableList(copy);
    }

    public List<Commands.AbstractCommand> commands() {
        return Collections.unmodifiableList(new ArrayList<>(commands));
    }

    private void startPacket() {
        writeSelector(0x30);
        writeSelector(0x00);
        writeSelector(0x30);
    }

    private void writeBits(int[] packet, int fromBit, int toBit) {
        for (int bitIndex = fromBit; bitIndex < toBit; bitIndex++) {
            int bit = packet[bitIndex / 8] >> (bitIndex & 7) & 1;
            writeSelector(bit == 0 ? 0x20 : 0x10);
            writeSelector(0x30);
        }
    }

    private void writeSelector(int selector) {
        joypad.setByte(0xff00, selector);
    }

    private static void requirePacket(int[] packet) {
        if (packet == null || packet.length != PACKET_BYTES) {
            throw new IllegalArgumentException("An SGB packet must contain exactly 16 bytes");
        }
        if (Arrays.stream(packet).anyMatch(value -> value < 0 || value > 0xff)) {
            throw new IllegalArgumentException("SGB packet values must be bytes");
        }
    }

    @Override
    public void close() {
        sgbBus.close();
    }
}
