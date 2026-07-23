package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.memento.Memento;

import java.util.Arrays;

/**
 * Emulates Nintendo's DMG-07 four-player adapter.
 *
 * <p>The adapter owns the serial clock and broadcasts one byte to all four ports at once. During
 * the ping phase it assigns the physical player number and reports the connected-port mask. In the
 * transmission phase it returns the previous packet's data, ordered by player, while collecting
 * the next packet from all four Game Boys.</p>
 */
public final class FourPlayerAdapter {

    public static final int PLAYER_COUNT = 4;

    // The measured DMG-07 serial clock is 62.66 kHz, or about 66.9 DMG T-cycles per bit.
    private static final int CLOCK_TICKS_PER_BIT = 67;

    private static final int PING_BYTE_GAP_TICKS = 5_956;

    private static final int PING_PACKET_GAP_TICKS = 51_548;

    private static final int TICKS_PER_MILLISECOND = 4_194;

    private final Endpoint[] endpoints = new Endpoint[PLAYER_COUNT];

    private final int[] sb = new int[PLAYER_COUNT];

    private final boolean[] transferArmed = new boolean[PLAYER_COUNT];

    private final int[] pendingBits = new int[PLAYER_COUNT];

    private final boolean[] connected = new boolean[PLAYER_COUNT];

    private final int[] consecutiveAa = new int[PLAYER_COUNT];

    private final int[] consecutiveFf = new int[PLAYER_COUNT];

    private final int[][] replies = new int[PLAYER_COUNT][16];

    private int[] transmissionBuffer = new int[16];

    private int packetByte;

    private int bit = 7;

    private int ticksUntilBit;

    private int rate = 0x10;

    private int size = 1;

    private Phase phase = Phase.PING;

    private boolean restartPingRequested;

    public FourPlayerAdapter() {
        Arrays.fill(pendingBits, -1);
        for (int i = 0; i < PLAYER_COUNT; i++) {
            endpoints[i] = new Endpoint(i);
        }
    }

    public SerialEndpoint endpoint(int player) {
        if (player < 0 || player >= PLAYER_COUNT) {
            throw new IllegalArgumentException("Invalid player: " + player);
        }
        return endpoints[player];
    }

    private int outgoingByte() {
        return switch (phase) {
            case PING -> packetByte == 0 ? 0xfe : statusMask();
            case TRANSMISSION_INDICATOR -> 0xcc;
            case PING_INDICATOR -> 0xff;
            // The transmitted stream leads the capture counter by one byte. At the packet
            // boundary, continue with the first reply already captured for the next packet
            // instead of wrapping to stale data from the old packet.
            case TRANSMISSION -> packetByte + 1 < packetLength()
                    ? transmissionBuffer[packetByte + 1]
                    : replies[0][0];
        };
    }

    private int statusMask() {
        int mask = 0;
        for (int i = 0; i < PLAYER_COUNT; i++) {
            if (connected[i]) {
                mask |= 1 << (4 + i);
            }
        }
        // The same connection mask is broadcast, but the low bits are hard-wired per port.
        return mask;
    }

    private int outgoingByte(int player) {
        if (phase == Phase.PING && packetByte != 0) {
            return statusMask() | (player + 1);
        }
        return outgoingByte();
    }

    private int packetLength() {
        return switch (phase) {
            case PING, TRANSMISSION_INDICATOR -> 4;
            case TRANSMISSION, PING_INDICATOR -> size * PLAYER_COUNT;
        };
    }

    private int byteGapTicks() {
        if (phase == Phase.PING || phase == Phase.TRANSMISSION_INDICATOR) {
            return PING_BYTE_GAP_TICKS;
        }
        return 3_720 + ((rate >>> 4) & 0x0f) * 445;
    }

    private int packetGapTicks(int packetLength) {
        if (phase == Phase.PING || phase == Phase.TRANSMISSION_INDICATOR) {
            return PING_PACKET_GAP_TICKS + (rate & 0x0f) * TICKS_PER_MILLISECOND;
        }
        int byteGap = byteGapTicks();
        int elapsed = packetLength * 8 * CLOCK_TICKS_PER_BIT + (packetLength - 1) * byteGap;
        int minimumPeriod = 17 * TICKS_PER_MILLISECOND
                + (rate & 0x0f) * TICKS_PER_MILLISECOND;
        return Math.max(1_510, minimumPeriod - elapsed);
    }

    private void finishPacket() {
        switch (phase) {
            case PING -> finishPingPacket();
            case TRANSMISSION_INDICATOR -> phase = Phase.TRANSMISSION;
            case TRANSMISSION -> finishTransmissionPacket();
            case PING_INDICATOR -> {
                phase = Phase.PING;
                Arrays.fill(connected, false);
            }
        }
        for (int[] playerReplies : replies) {
            Arrays.fill(playerReplies, 0);
        }
    }

    private void finishPingPacket() {
        for (int player = 0; player < PLAYER_COUNT; player++) {
            // Replies are one byte behind what the Game Boy receives: FE causes software to load
            // ACK1, which is sent alongside STAT1; ACK2 is then sent alongside STAT2.
            connected[player] = replies[player][1] == 0x88 && replies[player][2] == 0x88;
        }

        int newSize = replies[0][0];
        int newRate = replies[0][3];
        // A correctly aligned switch command starts after the ping header: its first three AA
        // replies therefore occupy this packet's ACK1, ACK2 and RATE positions, and the fourth
        // arrives with the next header. The RATE slot is command data in that case, not a new
        // adapter rate; retain the last complete ping's rate as the hardware does.
        if (newRate != 0 && consecutiveAa[0] < 3) {
            rate = newRate;
        }
        if (newSize >= 1 && newSize <= 4) {
            size = newSize;
        }
    }

    private void finishTransmissionPacket() {
        int[] nextBuffer = new int[16];
        for (int player = 0; player < PLAYER_COUNT; player++) {
            // Each Game Boy loads its packet data before the first transfer. Only its first SIZE
            // replies enter the DMG-07 buffer; replies during the other players' slots are ignored.
            System.arraycopy(replies[player], 0, nextBuffer, player * size, size);
        }
        transmissionBuffer = nextBuffer;
        if (restartPingRequested) {
            phase = Phase.PING_INDICATOR;
            restartPingRequested = false;
        }
    }

    private boolean observeReplyByte() {
        for (int player = 0; player < PLAYER_COUNT; player++) {
            int reply = replies[player][packetByte];
            if (phase == Phase.PING) {
                consecutiveAa[player] = reply == 0xaa ? consecutiveAa[player] + 1 : 0;
                if (consecutiveAa[player] >= 4) {
                    Arrays.fill(consecutiveAa, 0);
                    phase = Phase.TRANSMISSION_INDICATOR;
                    return true;
                }
            } else if (phase == Phase.TRANSMISSION) {
                consecutiveFf[player] = reply == 0xff ? consecutiveFf[player] + 1 : 0;
                if (consecutiveFf[player] >= 3) {
                    restartPingRequested = true;
                }
            }
        }
        return false;
    }

    private AdapterMemento saveState() {
        int[][] repliesCopy = new int[PLAYER_COUNT][];
        for (int i = 0; i < PLAYER_COUNT; i++) {
            repliesCopy[i] = replies[i].clone();
        }
        return new AdapterMemento(sb.clone(), transferArmed.clone(), pendingBits.clone(),
                connected.clone(), consecutiveAa.clone(), consecutiveFf.clone(), repliesCopy,
                transmissionBuffer.clone(), packetByte, bit, ticksUntilBit, rate, size, phase,
                restartPingRequested);
    }

    private void restoreState(AdapterMemento state) {
        System.arraycopy(state.sb, 0, sb, 0, PLAYER_COUNT);
        System.arraycopy(state.transferArmed, 0, transferArmed, 0, PLAYER_COUNT);
        System.arraycopy(state.pendingBits, 0, pendingBits, 0, PLAYER_COUNT);
        System.arraycopy(state.connected, 0, connected, 0, PLAYER_COUNT);
        System.arraycopy(state.consecutiveAa, 0, consecutiveAa, 0, PLAYER_COUNT);
        System.arraycopy(state.consecutiveFf, 0, consecutiveFf, 0, PLAYER_COUNT);
        for (int i = 0; i < PLAYER_COUNT; i++) {
            System.arraycopy(state.replies[i], 0, replies[i], 0, replies[i].length);
        }
        transmissionBuffer = state.transmissionBuffer.clone();
        packetByte = state.packetByte;
        bit = state.bit;
        ticksUntilBit = state.ticksUntilBit;
        rate = state.rate;
        size = state.size;
        phase = state.phase;
        restartPingRequested = state.restartPingRequested;
    }

    private enum Phase {
        PING,
        TRANSMISSION_INDICATOR,
        TRANSMISSION,
        PING_INDICATOR
    }

    private record AdapterMemento(int[] sb, boolean[] transferArmed, int[] pendingBits,
                                  boolean[] connected, int[] consecutiveAa, int[] consecutiveFf,
                                  int[][] replies,
                                  int[] transmissionBuffer, int packetByte, int bit,
                                  int ticksUntilBit, int rate, int size, Phase phase,
                                  boolean restartPingRequested)
            implements Memento<SerialEndpoint> {
    }

    private final class Endpoint implements SerialEndpoint {

        private final int player;

        private Endpoint(int player) {
            this.player = player;
        }

        @Override
        public void setSb(int value) {
            sb[player] = value & 0xff;
        }

        @Override
        public int recvBit() {
            if (player == 0) {
                tickClockForPlayers();
            }
            int result = pendingBits[player];
            pendingBits[player] = -1;
            return transferArmed[player] ? result : -1;
        }

        private void tickClockForPlayers() {
            if (ticksUntilBit > 0) {
                ticksUntilBit--;
                return;
            }
            if (bit == 7) {
                for (int p = 0; p < PLAYER_COUNT; p++) {
                    replies[p][packetByte] = sb[p];
                }
            }
            for (int p = 0; p < PLAYER_COUNT; p++) {
                pendingBits[p] = BitUtils.getBit(outgoingByte(p), bit) ? 1 : 0;
            }
            ticksUntilBit = CLOCK_TICKS_PER_BIT - 1;
            if (--bit < 0) {
                bit = 7;
                if (observeReplyByte()) {
                    packetByte = 0;
                    ticksUntilBit += PING_BYTE_GAP_TICKS;
                    return;
                }
                packetByte++;
                int packetLength = packetLength();
                if (packetByte < packetLength) {
                    ticksUntilBit += byteGapTicks();
                } else {
                    finishPacket();
                    packetByte = 0;
                    ticksUntilBit += packetGapTicks(packetLength);
                }
            }
        }

        @Override
        public void setExternalTransfer(boolean inProgress) {
            transferArmed[player] = inProgress;
        }

        @Override
        public void startSending() {
            // The DMG-07 owns framing and clock phase; arming SC does not reset the adapter.
        }

        @Override
        public int sendBit() {
            // Internal clock mode is unsupported by the physical adapter and reads as an idle line.
            return 1;
        }

        @Override
        public Memento<SerialEndpoint> saveToMemento() {
            return saveState();
        }

        @Override
        public void restoreFromMemento(Memento<SerialEndpoint> memento) {
            if (!(memento instanceof AdapterMemento state)) {
                throw new IllegalArgumentException("Invalid memento type");
            }
            restoreState(state);
        }
    }
}
