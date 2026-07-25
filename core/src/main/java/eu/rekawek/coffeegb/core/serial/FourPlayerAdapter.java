package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.cpu.BitUtils;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;

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
    private static final int LEGACY_CLOCK_TICKS_PER_BIT = 67;

    private static final int PING_BYTE_GAP_TICKS = 5_956;

    private static final int PING_PACKET_GAP_TICKS = 51_548;

    private final int clockTicksPerBit;

    private final int pingByteGapTicks;

    private final int pingPacketGapTicks;

    private final int ticksPerMillisecond;

    private final int transmissionByteGapBaseTicks;

    private final int transmissionByteGapRateTicks;

    private final int minimumPacketGapTicks;

    private final Endpoint[] endpoints = new Endpoint[PLAYER_COUNT];

    private final int[] sb = new int[PLAYER_COUNT];

    private final boolean[] transferArmed = new boolean[PLAYER_COUNT];

    private final int[] pendingBits = new int[PLAYER_COUNT];

    private final boolean[] connected = new boolean[PLAYER_COUNT];

    private final int[] consecutiveFf = new int[PLAYER_COUNT];

    private final int[][] replies = new int[PLAYER_COUNT][16];

    private int[] transmissionBuffer = new int[16];

    private int packetByte;

    private int bit = 7;

    private int ticksUntilBit;

    private int rate = 0x10;

    private int size = 1;

    private Phase phase = Phase.PING;

    private boolean transmissionRequested;

    private boolean restartPingRequested;

    public FourPlayerAdapter() {
        this(ClockSpec.LEGACY);
    }

    public FourPlayerAdapter(ClockSpec clockSpec) {
        clockTicksPerBit = scaleLegacyTicks(clockSpec, LEGACY_CLOCK_TICKS_PER_BIT);
        pingByteGapTicks = scaleLegacyTicks(clockSpec, PING_BYTE_GAP_TICKS);
        pingPacketGapTicks = scaleLegacyTicks(clockSpec, PING_PACKET_GAP_TICKS);
        ticksPerMillisecond = requirePositive("ticks per millisecond", Math.toIntExact(
                clockSpec.ticksForMilliseconds(1, ClockSpec.Rounding.FLOOR)));
        transmissionByteGapBaseTicks = scaleLegacyTicks(clockSpec, 3_720);
        transmissionByteGapRateTicks = scaleLegacyTicks(clockSpec, 445);
        minimumPacketGapTicks = scaleLegacyTicks(clockSpec, 1_510);
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
            case TRANSMISSION -> transmissionBuffer[packetByte];
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
            return pingByteGapTicks;
        }
        return Math.addExact(
                transmissionByteGapBaseTicks,
                Math.multiplyExact((rate >>> 4) & 0x0f, transmissionByteGapRateTicks));
    }

    private int packetGapTicks(int packetLength) {
        if (phase == Phase.PING || phase == Phase.TRANSMISSION_INDICATOR) {
            return Math.addExact(
                    pingPacketGapTicks,
                    Math.multiplyExact(rate & 0x0f, ticksPerMillisecond));
        }
        int byteGap = byteGapTicks();
        int elapsed = Math.addExact(
                Math.multiplyExact(packetLength * 8, clockTicksPerBit),
                Math.multiplyExact(packetLength - 1, byteGap));
        int minimumPeriod = Math.multiplyExact(17 + (rate & 0x0f), ticksPerMillisecond);
        return Math.max(minimumPacketGapTicks, Math.subtractExact(minimumPeriod, elapsed));
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
        // The master requests transmission while replying to a ping. The adapter finishes that
        // four-byte ping before returning the CC indicator packet; bytes from the partial command
        // are not a new ACK/rate/size packet.
        if (transmissionRequested) {
            phase = Phase.TRANSMISSION_INDICATOR;
            transmissionRequested = false;
            return;
        }

        for (int player = 0; player < PLAYER_COUNT; player++) {
            // Replies are one byte behind what the Game Boy receives: FE causes software to load
            // ACK1, which is sent alongside STAT1; ACK2 is then sent alongside STAT2.
            connected[player] = replies[player][1] == 0x88 && replies[player][2] == 0x88;
        }

        int newSize = replies[0][0];
        int newRate = replies[0][3];
        if (newRate != 0) {
            rate = newRate;
        }
        if (newSize >= 1 && newSize <= 4) {
            size = newSize;
        }
    }

    private void finishTransmissionPacket() {
        int[] nextBuffer = new int[16];
        for (int player = 0; player < PLAYER_COUNT; player++) {
            // Games load byte 1 after receiving byte 0, so the first SIZE replies are physically
            // transferred in slots 1..SIZE. The other players' outgoing slots are ignored.
            System.arraycopy(replies[player], 1, nextBuffer, player * size, size);
        }
        transmissionBuffer = nextBuffer;
        if (restartPingRequested) {
            phase = Phase.PING_INDICATOR;
            restartPingRequested = false;
        }
    }

    private void observeReplyByte() {
        for (int player = 0; player < PLAYER_COUNT; player++) {
            int reply = replies[player][packetByte];
            if (phase == Phase.PING) {
                // Player 1 is the protocol master. Once it starts the AA command, complete the
                // current ping packet before switching phases. Only three AA replies fit after
                // the FE header; games may send the fourth alongside the first CC response.
                if (player == 0 && reply == 0xaa) {
                    transmissionRequested = true;
                }
            } else if (phase == Phase.TRANSMISSION) {
                consecutiveFf[player] = reply == 0xff ? consecutiveFf[player] + 1 : 0;
                if (consecutiveFf[player] >= 3) {
                    restartPingRequested = true;
                }
            }
        }
    }

    private AdapterState saveState() {
        int[][] repliesCopy = new int[PLAYER_COUNT][];
        for (int i = 0; i < PLAYER_COUNT; i++) {
            repliesCopy[i] = replies[i].clone();
        }
        return new AdapterState(sb.clone(), transferArmed.clone(), pendingBits.clone(),
                connected.clone(), consecutiveFf.clone(), repliesCopy,
                transmissionBuffer.clone(), packetByte, bit, ticksUntilBit, rate, size, phase,
                transmissionRequested, restartPingRequested);
    }

    private void restoreState(AdapterState state) {
        System.arraycopy(state.sb, 0, sb, 0, PLAYER_COUNT);
        System.arraycopy(state.transferArmed, 0, transferArmed, 0, PLAYER_COUNT);
        System.arraycopy(state.pendingBits, 0, pendingBits, 0, PLAYER_COUNT);
        System.arraycopy(state.connected, 0, connected, 0, PLAYER_COUNT);
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
        transmissionRequested = state.transmissionRequested;
        restartPingRequested = state.restartPingRequested;
    }

    private enum Phase {
        PING,
        TRANSMISSION_INDICATOR,
        TRANSMISSION,
        PING_INDICATOR
    }

    private record AdapterState(int[] sb, boolean[] transferArmed, int[] pendingBits,
                                  boolean[] connected, int[] consecutiveFf, int[][] replies,
                                  int[] transmissionBuffer, int packetByte, int bit,
                                  int ticksUntilBit, int rate, int size, Phase phase,
                                  boolean transmissionRequested, boolean restartPingRequested)
            implements ComponentState<SerialEndpoint> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record AdapterMemento(int[] sb, boolean[] transferArmed, int[] pendingBits,
                                  boolean[] connected, int[] consecutiveFf, int[][] replies,
                                  int[] transmissionBuffer, int packetByte, int bit,
                                  int ticksUntilBit, int rate, int size, Phase phase,
                                  boolean transmissionRequested, boolean restartPingRequested)
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
            ticksUntilBit = clockTicksPerBit - 1;
            if (--bit < 0) {
                bit = 7;
                observeReplyByte();
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
        public ComponentState<SerialEndpoint> captureState() {
            return saveState();
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
            if (!(state instanceof AdapterState adapterState)) {
                throw new IllegalArgumentException("Invalid state type");
            }
            FourPlayerAdapter.this.restoreState(adapterState);
        }
    }

    private static int scaleLegacyTicks(ClockSpec clockSpec, int legacyTicks) {
        return requirePositive("scaled serial timing", Math.toIntExact(clockSpec.ticksForRateUnits(
                legacyTicks,
                ClockSpec.LEGACY.ticksPerSecond(),
                ClockSpec.Rounding.NEAREST)));
    }

    private static int requirePositive(String label, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive for the session clock");
        }
        return value;
    }
}
