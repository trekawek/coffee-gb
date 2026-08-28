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

    private static final int[] JANTAKU_BOY_CONTROL_PACKET = {0xfd, 0xff, 0xff, 0xff};

    private static final int JANTAKU_BULK_IDLE = 0;

    private static final int JANTAKU_BULK_ANNOUNCEMENT = 1;

    private static final int JANTAKU_BULK_LENGTH = 2;

    private static final int JANTAKU_BULK_RELAY = 3;

    private static final int JANTAKU_BULK_REANCHOR = 4;

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

    private final boolean[] jantakuBoyControlPacket = new boolean[PLAYER_COUNT];

    private final int[] consecutiveFf = new int[PLAYER_COUNT];

    private final int[] jantakuPreviousReply = new int[PLAYER_COUNT];

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

    private boolean jantakuWakePending;

    /** Length command from the [command, FF, FF, non-FF] request block. */
    private int jantakuWakeCommand;

    /** Physical seat whose contiguous reply stream supplies the announced bulk. */
    private int jantakuWakePlayer = -1;

    private int jantakuBulkStage;

    private int jantakuBulkRemaining;

    private int jantakuRelayByte;

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
            case JANTAKU_BOY_CONTROL -> JANTAKU_BOY_CONTROL_PACKET[packetByte];
            case TRANSMISSION -> jantakuBulkStage == JANTAKU_BULK_RELAY
                    ? jantakuRelayByte : transmissionBuffer[packetByte];
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
            case PING, TRANSMISSION_INDICATOR, JANTAKU_BOY_CONTROL -> 4;
            case TRANSMISSION, PING_INDICATOR -> size * PLAYER_COUNT;
        };
    }

    private int byteGapTicks() {
        if (phase == Phase.PING || phase == Phase.TRANSMISSION_INDICATOR
                || phase == Phase.JANTAKU_BOY_CONTROL) {
            return pingByteGapTicks;
        }
        return Math.addExact(
                transmissionByteGapBaseTicks,
                Math.multiplyExact((rate >>> 4) & 0x0f, transmissionByteGapRateTicks));
    }

    private int packetGapTicks(int packetLength) {
        if (phase == Phase.PING || phase == Phase.TRANSMISSION_INDICATOR
                || phase == Phase.JANTAKU_BOY_CONTROL) {
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
            case TRANSMISSION_INDICATOR -> {
                phase = Phase.TRANSMISSION;
            }
            case TRANSMISSION -> finishTransmissionPacket();
            case PING_INDICATOR -> {
                phase = Phase.PING;
                Arrays.fill(connected, false);
            }
            case JANTAKU_BOY_CONTROL -> finishJantakuBoyControlPacket();
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
        boolean jantakuBoy = allPlayersUseJantakuBoyControlPacket() && size == 1;
        if (jantakuBoy && jantakuBulkStage == JANTAKU_BULK_REANCHOR) {
            // A length-prefixed bulk shifts the title's four-byte command framing by length mod 4.
            // End the current adapter packet early and make its next byte slot zero again.
            transmissionBuffer = new int[16];
            jantakuBulkStage = JANTAKU_BULK_IDLE;
            jantakuBulkRemaining = 0;
            jantakuWakePending = false;
            jantakuWakePlayer = -1;
            Arrays.fill(consecutiveFf, 0);
            Arrays.fill(jantakuPreviousReply, 0);
            return;
        }
        if (restartPingRequested) {
            phase = Phase.PING_INDICATOR;
            restartPingRequested = false;
            return;
        }
        int[] nextBuffer = new int[16];
        for (int player = 0; player < PLAYER_COUNT; player++) {
            // Games load byte 1 after receiving byte 0, so the first SIZE replies are physically
            // transferred in slots 1..SIZE. The other players' outgoing slots are ignored.
            System.arraycopy(replies[player], 1, nextBuffer, player * size, size);
        }
        if (jantakuBoy && jantakuBulkStage == JANTAKU_BULK_ANNOUNCEMENT) {
            // The requester may be any physical seat. The command must nevertheless be the first
            // byte after the broadcast wake window, where every console reads the bulk length.
            nextBuffer[0] = jantakuWakeCommand == 0 ? 4 : jantakuWakeCommand;
            jantakuBulkStage = JANTAKU_BULK_LENGTH;
        } else if (jantakuBoy && jantakuWakePending
                && jantakuBulkStage == JANTAKU_BULK_IDLE) {
            System.arraycopy(JANTAKU_BOY_CONTROL_PACKET, 0, nextBuffer, 0,
                    JANTAKU_BOY_CONTROL_PACKET.length);
            jantakuWakePending = false;
            jantakuBulkStage = JANTAKU_BULK_ANNOUNCEMENT;
            Arrays.fill(consecutiveFf, 0);
            Arrays.fill(jantakuPreviousReply, 0);
        }
        transmissionBuffer = nextBuffer;
    }

    private void finishJantakuBoyControlPacket() {
        // Retain the control phase: this exact title expects this bridge response in place of
        // the adapter's normal disconnect/re-ping sequence.
        finishTransmissionPacket();
    }

    private boolean allPlayersUseJantakuBoyControlPacket() {
        for (boolean enabled : jantakuBoyControlPacket) {
            if (!enabled) {
                return false;
            }
        }
        return true;
    }

    private void observeReplyByte() {
        boolean jantakuBoy = allPlayersUseJantakuBoyControlPacket() && size == 1;
        if (phase == Phase.TRANSMISSION && jantakuBoy) {
            if (jantakuBulkStage == JANTAKU_BULK_LENGTH && packetByte == 0) {
                int length = jantakuWakeCommand == 0 ? 4 : jantakuWakeCommand;
                if (jantakuWakePlayer >= 0 && transferArmed[jantakuWakePlayer]) {
                    jantakuRelayByte = replies[jantakuWakePlayer][packetByte];
                }
                if (length <= 1) {
                    jantakuBulkStage = JANTAKU_BULK_REANCHOR;
                } else {
                    jantakuBulkRemaining = length - 1;
                    jantakuBulkStage = JANTAKU_BULK_RELAY;
                }
                return;
            }
            if (jantakuBulkStage == JANTAKU_BULK_RELAY) {
                // During a bulk, every byte from the requesting seat is broadcast contiguously.
                // The normal DMG-07 per-seat slot collector would interleave three unrelated
                // reply streams and corrupt both the payload and the games' command framing.
                if (jantakuWakePlayer >= 0 && transferArmed[jantakuWakePlayer]) {
                    jantakuRelayByte = replies[jantakuWakePlayer][packetByte];
                }
                if (--jantakuBulkRemaining == 0) {
                    jantakuBulkStage = JANTAKU_BULK_REANCHOR;
                }
                return;
            }
        }
        for (int player = 0; player < PLAYER_COUNT; player++) {
            int reply = replies[player][packetByte];
            if (phase == Phase.PING) {
                // Player 1 is the protocol master. Once it starts the AA command, complete the
                // current ping packet before switching phases. Only three AA replies fit after
                // the FE header; games may send the fourth alongside the first CC response.
                if (player == 0 && connected[player] && reply == 0xaa) {
                    transmissionRequested = true;
                }
            } else if (phase == Phase.TRANSMISSION) {
                if (jantakuBoy) {
                    observeJantakuBoyReply(player, reply, transferArmed[player]);
                } else {
                    consecutiveFf[player] = reply == 0xff
                            ? consecutiveFf[player] + 1 : 0;
                    if (consecutiveFf[player] >= 3) {
                        restartPingRequested = true;
                    }
                }
            }
        }
    }

    private void observeJantakuBoyReply(int player, int reply, boolean armed) {
        if (!armed) {
            return;
        }
        if (jantakuBulkStage != JANTAKU_BULK_IDLE) {
            return;
        }
        if (reply == 0xff) {
            if (consecutiveFf[player] < 0xff) {
                consecutiveFf[player]++;
            }
            if (player == 0 && consecutiveFf[player] >= 3) {
                restartPingRequested = true;
            }
            return;
        }
        if (consecutiveFf[player] == 2) {
            jantakuWakePending = true;
            jantakuWakeCommand = jantakuPreviousReply[player];
            jantakuWakePlayer = player;
        }
        consecutiveFf[player] = 0;
        jantakuPreviousReply[player] = reply;
    }

    private AdapterState saveState() {
        int[][] repliesCopy = new int[PLAYER_COUNT][];
        for (int i = 0; i < PLAYER_COUNT; i++) {
            repliesCopy[i] = replies[i].clone();
        }
        return new AdapterState(sb.clone(), transferArmed.clone(), pendingBits.clone(),
                connected.clone(), consecutiveFf.clone(), jantakuPreviousReply.clone(), repliesCopy,
                transmissionBuffer.clone(), packetByte, bit, ticksUntilBit, rate, size, phase,
                transmissionRequested, restartPingRequested, jantakuWakePending,
                jantakuWakeCommand, jantakuWakePlayer, jantakuBulkStage,
                jantakuBulkRemaining, jantakuRelayByte);
    }

    private void restoreState(AdapterState state) {
        System.arraycopy(state.sb, 0, sb, 0, PLAYER_COUNT);
        System.arraycopy(state.transferArmed, 0, transferArmed, 0, PLAYER_COUNT);
        System.arraycopy(state.pendingBits, 0, pendingBits, 0, PLAYER_COUNT);
        System.arraycopy(state.connected, 0, connected, 0, PLAYER_COUNT);
        System.arraycopy(state.consecutiveFf, 0, consecutiveFf, 0, PLAYER_COUNT);
        System.arraycopy(state.jantakuPreviousReply, 0, jantakuPreviousReply, 0, PLAYER_COUNT);
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
        jantakuWakePending = state.jantakuWakePending;
        jantakuWakeCommand = state.jantakuWakeCommand;
        jantakuWakePlayer = state.jantakuWakePlayer;
        jantakuBulkStage = state.jantakuBulkStage;
        jantakuBulkRemaining = state.jantakuBulkRemaining;
        jantakuRelayByte = state.jantakuRelayByte;
    }

    private enum Phase {
        PING,
        TRANSMISSION_INDICATOR,
        TRANSMISSION,
        PING_INDICATOR,
        JANTAKU_BOY_CONTROL
    }

    private record AdapterState(int[] sb, boolean[] transferArmed, int[] pendingBits,
                                  boolean[] connected, int[] consecutiveFf,
                                  int[] jantakuPreviousReply, int[][] replies,
                                  int[] transmissionBuffer, int packetByte, int bit,
                                  int ticksUntilBit, int rate, int size, Phase phase,
                                  boolean transmissionRequested, boolean restartPingRequested,
                                  boolean jantakuWakePending, int jantakuWakeCommand,
                                  int jantakuWakePlayer, int jantakuBulkStage,
                                  int jantakuBulkRemaining, int jantakuRelayByte)
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
        public void enableCompatibilityProfile(SerialCompatibilityProfile profile) {
            if (profile == SerialCompatibilityProfile.JANTAKU_BOY_FOUR_PLAYER_CONTROL_PACKET) {
                jantakuBoyControlPacket[player] = true;
            }
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
                if (jantakuBulkStage == JANTAKU_BULK_REANCHOR) {
                    packetByte = packetLength;
                }
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
