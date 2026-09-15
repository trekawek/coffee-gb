package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import java.util.Arrays;

/** Singer IZEK-1500 / Jaguar JN-100 and JN-2000 with the EM-2000 embroidery arm.
 * Protocol and coordinate units: Shonumi's Game Boy Sewing Machine Documentation 0.3.
 * The external clock and motor speed are simulation rates, not hardware measurements.
 * All controls and snapshots belong to the emulator owner thread.
 */
public final class SewingMachineSerialEndpoint implements SerialEndpoint {
    public static final int SIZE = 512, BIT_TICKS = 512, MAX_PATTERN = 65536;
    private final int[] packet = new int[128];
    private final int[] staging = new int[MAX_PATTERN];
    private final int[] pattern = new int[MAX_PATTERN];
    private final int[] fabric = new int[SIZE * SIZE];
    private int advanceTicks;
    private int model, color = 0xff245ac1, speed = 60;
    private boolean arm, largeHoop, pedal, paused, advance;
    private int sb, incoming, outgoing, bits, wireTicks, internalBits, payloadClock, lastRequest;
    private boolean armed, external;
    private int sync, packetLength, checksumBytes, terminator, terminatorIndex, stagingLength, patternLength;
    private boolean receiving, firstPacket = true, padding, embroidery, repeat = true;
    private int headerX, headerY, packetShiftRemaining;
    private boolean packetShift;
    private int cursor, x = 192, y = 32, lastY, endPairs, motorTicks;
    private boolean firstStitch = true, shift, finished;
    private int acceptedPackets, rejectedPackets, completedPatterns, stitched;

    public SewingMachineSerialEndpoint() { Arrays.fill(fabric, 0xfffaf7ef); }
    public int getModel() { return model; }
    public boolean isArmAttached() { return arm; }
    public boolean isLargeHoop() { return largeHoop; }
    public boolean isPedalPressed() { return pedal; }
    public boolean isPaused() { return paused; }
    public int getThreadColor() { return color; }
    public int getSpeed() { return speed; }
    public int getStitchCount() { return stitched; }
    public int getPatternLength() { return patternLength; }
    public int getRejectedPackets() { return rejectedPackets; }
    public boolean isFinished() { return finished; }
    public void setModel(int model) {
        if (model < 0 || model > 2) throw new IllegalArgumentException("Unknown sewing machine");
        this.model = model; arm = model == 2; largeHoop = false; resetPattern();
    }
    public void setArmAttached(boolean attached) {
        if (attached && model != 2) throw new IllegalArgumentException("Only JN-2000 supports EM-2000");
        arm = attached; resetPattern();
    }
    public void setLargeHoop(boolean large) { largeHoop = large; }
    public void setPedal(boolean pressed) { pedal = pressed; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public void requestAdvance() { advance = true; advanceTicks = 4194304; }
    public void setThreadColor(int rgb) { color = 0xff000000 | rgb; }
    public void setSpeed(int stitchesPerSecond) {
        if (stitchesPerSecond < 1 || stitchesPerSecond > 600) throw new IllegalArgumentException("Speed must be 1–600 stitches/s");
        speed = stitchesPerSecond; motorTicks = 0;
    }
    public void clearFabric() { Arrays.fill(fabric, 0xfffaf7ef); stitched = 0; }
    public int[] copyFabric() { return fabric.clone(); }
    public String diagnostics() {
        return "packets=" + acceptedPackets + " rejected=" + rejectedPackets + " patterns=" + completedPatterns
                + " patternBytes=" + patternLength + " stitches=" + stitched + " finished=" + finished;
    }
    private int status() {
        return (arm ? (largeHoop ? 7 : 6) : 0) | (advance ? 0x20 : 0)
                | (pedal ? 0x40 : 0) | (paused ? 0x80 : 0);
    }
    @Override public void setSb(int value) {
        sb = value & 255;
        // A write before the first clock edge replaces the request and its status reply.
        if (armed && bits == 0 && internalBits == 0) { incoming = sb; outgoing = reply(); }
    }
    @Override public void startSending() {
        incoming = sb; outgoing = reply();
        bits = wireTicks = internalBits = 0; armed = true;
    }
    private int reply() {
        return (sb == 0x80 || (payloadClock == 2 && lastRequest == 0x80)) ? status() : 0;
    }
    @Override public void setExternalTransfer(boolean active) {
        if (active && !external && !armed) startSending();
        external = active;
        if (!active) armed = false;
    }
    @Override public void tick() {
        if (advanceTicks > 0 && --advanceTicks == 0) advance = false;
        if (armed && external && wireTicks < BIT_TICKS) wireTicks++;
        if (!paused && !finished && patternLength > 0 && (embroidery || pedal)) {
            if (++motorTicks >= 4194304 / speed) { motorTicks = 0; stitchOnce(); }
        }
    }
    @Override public int recvBit() {
        if (!armed || !external || wireTicks < BIT_TICKS) return -1;
        wireTicks = 0;
        int result = (outgoing >>> (7 - bits)) & 1;
        if (++bits == 8) {
            armed = false;
            if (incoming == 0x80) payloadClock = 1;
            if (payloadClock == 1) { lastRequest = incoming; accept(incoming); }
        }
        return result;
    }
    @Override public int sendBit() {
        if (++internalBits == 8) {
            // The USA operation software clocks its payload itself, then reads status on an
            // external zero byte. Also retain the documented external-payload framing.
            if (incoming == 0x80) payloadClock = 2;
            if (payloadClock == 2) { lastRequest = incoming; accept(incoming); }
            armed = false;
        }
        return 1;
    }
    @Override public void disconnect() { pedal = advance = false; advanceTicks = 0; armed = external = false; }

    private void resetPattern() {
        receiving = false; sync = packetLength = checksumBytes = stagingLength = patternLength = cursor = 0;
        firstPacket = true; packetShift = false; packetShiftRemaining = 0; pedal = false; payloadClock = lastRequest = 0;
        finished = false; motorTicks = 0;
    }
    private void reject() {
        rejectedPackets++; receiving = false; packetLength = checksumBytes = stagingLength = sync = 0;
        firstPacket = true; packetShift = false; packetShiftRemaining = 0;
    }
    private void accept(int value) {
        if (!receiving) {
            if (value == 0x80) sync = Math.min(3, sync + 1);
            else if (value == 0x86 && sync == 3) {
                receiving = true; firstPacket = true; patternLength = cursor = 0; stagingLength = packetLength = checksumBytes = 0;
                packetShift = false; packetShiftRemaining = 0; embroidery = arm; sync = 0;
            } else sync = 0;
            return;
        }
        if (packetLength == 0 && value != 0xb9) { reject(); return; }
        if (packetLength >= packet.length) { reject(); return; }
        packet[packetLength++] = value;
        if (checksumBytes > 0) {
            if (--checksumBytes == 0) {
                // Mario Family pads the final packet after BA/BF, before its checksum.
                // A new sync after a damaged short packet still permits resynchronization.
                if (packetLength > terminatorIndex + 3 && packetLength < 126 && value != 0) {
                    reject(); accept(value);
                } else finishPacket();
            }
            return;
        }
        int headerLength = firstPacket ? (embroidery ? 9 : 8) : 1;
        if (packetLength <= headerLength) return;
        // Packet markers interrupt even a split 16-bit shift at the 127/128-byte boundary.
        if ((value == 0xba || value == 0xbb || value == 0xbf)
                && (packetShiftRemaining == 0 || packetLength >= 125)) {
            terminator = value; terminatorIndex = packetLength - 1; checksumBytes = 2; return;
        }
        if (packetShiftRemaining > 0) packetShiftRemaining--;
        else if (value == 0xbe || (packetShift && value == 0xff)) {
            packetShift = true; packetShiftRemaining = 4;
        } else if (value == 0xbd) packetShift = false;
    }
    private void finishPacket() {
        int sum = 0;
        for (int i = 0; i < packetLength - 2; i++) sum += packet[i];
        int expected = packet[packetLength - 2] | (packet[packetLength - 1] << 8);
        if ((sum & 65535) != expected) {
            if (packetLength < 128) checksumBytes = 1;
            else reject();
            return;
        }
        int start = firstPacket ? (embroidery ? 9 : 8) : 1;
        if (packetLength < start + 3 || stagingLength + terminatorIndex - start > MAX_PATTERN) { reject(); return; }
        if (firstPacket) {
            headerX = embroidery ? (packet[5] | packet[6] << 8) : packet[4];
            headerY = embroidery ? (packet[7] | packet[8] << 8) : packet[6];
        }
        for (int i = start; i < terminatorIndex; i++) staging[stagingLength++] = packet[i];
        acceptedPackets++; firstPacket = false; packetLength = 0;
        if (terminator == 0xbb) return;
        System.arraycopy(staging, 0, pattern, 0, stagingLength);
        Arrays.fill(pattern, stagingLength, pattern.length, 0);
        patternLength = stagingLength; receiving = false; completedPatterns++;
        cursor = motorTicks = endPairs = 0; firstStitch = true; padding = shift = finished = false;
        repeat = !embroidery; lastY = headerY;
        x = embroidery ? 408 + headerX - 65535 : 192 + headerX * 4;
        y = embroidery ? 24 + 65535 - headerY : 32;
    }

    /** One simulated needle operation. Jump commands move the fabric without drawing thread. */
    public void stitchOnce() {
        if (patternLength == 0 || finished || paused) return;
        // A malformed all-control pattern must not spin indefinitely or draw outside the canvas.
        for (int processed = 0; processed <= patternLength; processed++) {
            if (cursor >= patternLength || padding) {
                if (!repeat) { finished = true; return; }
                cursor = 0; padding = false;
            }
            int value = pattern[cursor++];
            if (value == 0xbc) { padding = true; continue; }
            if (value >= 0xc1 && value <= 0xc4) continue;
            if (value == 0xc7 || value == 0xe7 || value == 0xf7) { repeat = false; endPairs = 2; continue; }
            if (value == 0xbe || (shift && value == 0xff)) {
                shift = true;
                if (cursor + 4 > patternLength) { finished = true; return; }
                int dx = pattern[cursor++] | pattern[cursor++] << 8;
                int dy = pattern[cursor++] | pattern[cursor++] << 8;
                x += dx >= 0xff00 ? dx - 65536 : dx & 255;
                y += dy >= 0xff00 ? 65536 - dy : -(dy & 255);
                x = Math.max(-1000000, Math.min(1000000, x));
                y = Math.max(-1000000, Math.min(1000000, y));
                continue;
            }
            if (value == 0xbd) { shift = false; continue; }
            // Path variants (including CF/DF) delimit coordinate runs. A control may also
            // interrupt a pending X, as the embroidery software changes paths or starts a jump.
            if ((value & 0xf0) == 0xc0 || (value & 0xf0) == 0xe0
                    || (value & 0xf0) == 0xf0 || value == 0xdf) continue;
            if (cursor >= patternLength) { finished = true; return; }
            int next = pattern[cursor++], nx, ny;
            if (next >= 0xb9) { cursor--; continue; }
            if (embroidery) {
                if ((value & ~0x5f) != 0 || (next & ~0x5f) != 0 || (value & 31) > 16 || (next & 31) > 16) { finished = true; return; }
                nx = x + ((value & 64) == 0 ? value : -(value & 63));
                // The arm moves fabric opposite to the displayed stitch direction.
                ny = y - ((next & 64) == 0 ? next : -(next & 63));
            } else {
                if (value > 31 || next > 32) { finished = true; return; }
                nx = 192 + value * 4;
                ny = y + 20 - (firstStitch && next >= 26 ? next : lastY);
                lastY = next;
            }
            // Bound coordinates after an arbitrarily long repeating feed; clip display only.
            nx = Math.max(-1000000, Math.min(1000000, nx));
            ny = Math.max(-1000000, Math.min(1000000, ny));
            drawLine(x, y, nx, ny); x = nx; y = ny; firstStitch = false; stitched++;
            if (endPairs > 0) endPairs--;
            return;
        }
        finished = true;
    }
    private void drawLine(int x0, int y0, int x1, int y1) {
        // Reject off-sheet segments before walking their bounded coordinate delta.
        if ((x0 < 0 && x1 < 0) || (x0 >= SIZE && x1 >= SIZE)
                || (y0 < 0 && y1 < 0) || (y0 >= SIZE && y1 >= SIZE)) return;
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, error = dx + dy;
        while (true) {
            if (x0 >= 0 && x0 < SIZE && y0 >= 0 && y0 < SIZE) fabric[y0 * SIZE + x0] = color;
            if (x0 == x1 && y0 == y1) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    @Override public ComponentState<SerialEndpoint> captureState() {
        return state(packet.clone(), staging.clone(), pattern.clone(), fabric.clone());
    }
    @Override public ComponentState<SerialEndpoint> captureState(MachineStateCapture capture) {
        return state(capture.ints(packet), capture.ints(staging), capture.ints(pattern), capture.ints(fabric));
    }
    @Override public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts(packet); capture.declareInts(staging); capture.declareInts(pattern); capture.declareInts(fabric);
    }
    private SewingMachineState state(int[] packet, int[] staging, int[] pattern, int[] fabric) {
        return new SewingMachineState(advanceTicks, model, color, speed, sb, incoming, outgoing, bits, wireTicks,
                internalBits, payloadClock, lastRequest, sync, packetLength, checksumBytes, terminator, terminatorIndex,
                stagingLength, patternLength, headerX, headerY, packetShiftRemaining, cursor, x, y, lastY,
                endPairs, motorTicks, acceptedPackets, rejectedPackets, completedPatterns, stitched, arm,
                largeHoop, pedal, paused, advance, armed, external, receiving, firstPacket, padding,
                embroidery, repeat, packetShift, firstStitch, shift, finished, packet, staging, pattern,
                fabric);
    }
    @Override public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof SewingMachineState s)
                || s.packet == null || s.packet.length != 128
                || s.staging == null || s.staging.length != MAX_PATTERN
                || s.pattern == null || s.pattern.length != MAX_PATTERN
                || s.fabric == null || s.fabric.length != SIZE * SIZE
                || s.advanceTicks < 0 || s.advanceTicks > 4194304
                || s.model < 0 || s.model > 2 || (s.arm && s.model != 2)
                || s.internalBits < 0 || s.internalBits > 8 || s.payloadClock < 0 || s.payloadClock > 2 || s.lastRequest < 0 || s.lastRequest > 255
                || s.speed < 1 || s.speed > 600 || s.bits < 0 || s.bits > 8
                || (s.armed && s.bits == 8) || s.wireTicks < 0 || s.wireTicks > BIT_TICKS
                || s.terminatorIndex < 0 || s.terminatorIndex > 125
                || s.packetLength < 0 || s.packetLength > 127 || s.checksumBytes < 0 || s.checksumBytes > 2
                || s.stagingLength < 0 || s.stagingLength > MAX_PATTERN
                || s.patternLength < 0 || s.patternLength > MAX_PATTERN
                || s.cursor < 0 || s.cursor > s.patternLength || s.sync < 0 || s.sync > 3
                || s.packetShiftRemaining < 0 || s.packetShiftRemaining > 4
                || s.endPairs < 0 || s.endPairs > 2 || s.motorTicks < 0 || s.motorTicks > 4194304
                || Math.abs((long) s.x) > 1000000 || Math.abs((long) s.y) > 1000000
                || (s.sb | s.incoming | s.outgoing) < 0 || s.sb > 255 || s.incoming > 255 || s.outgoing > 255
                || Arrays.stream(s.packet).anyMatch(v -> v < 0 || v > 255)
                || Arrays.stream(s.staging).anyMatch(v -> v < 0 || v > 255)
                || Arrays.stream(s.pattern).anyMatch(v -> v < 0 || v > 255)) {
            throw new IllegalArgumentException("Invalid sewing machine state");
        }
        advanceTicks = s.advanceTicks;
        model = s.model;
        color = s.color;
        speed = s.speed;
        sb = s.sb;
        incoming = s.incoming;
        outgoing = s.outgoing;
        bits = s.bits;
        wireTicks = s.wireTicks;
        internalBits = s.internalBits; payloadClock = s.payloadClock; lastRequest = s.lastRequest;
        sync = s.sync;
        packetLength = s.packetLength;
        checksumBytes = s.checksumBytes;
        terminator = s.terminator; terminatorIndex = s.terminatorIndex;
        stagingLength = s.stagingLength;
        patternLength = s.patternLength;
        headerX = s.headerX;
        headerY = s.headerY;
        packetShiftRemaining = s.packetShiftRemaining;
        cursor = s.cursor;
        x = s.x;
        y = s.y;
        lastY = s.lastY;
        endPairs = s.endPairs;
        motorTicks = s.motorTicks;
        acceptedPackets = s.acceptedPackets;
        rejectedPackets = s.rejectedPackets;
        completedPatterns = s.completedPatterns;
        stitched = s.stitched;
        arm = s.arm;
        largeHoop = s.largeHoop;
        pedal = s.pedal;
        paused = s.paused;
        advance = s.advance;
        armed = s.armed;
        external = s.external;
        receiving = s.receiving;
        firstPacket = s.firstPacket;
        padding = s.padding;
        embroidery = s.embroidery;
        repeat = s.repeat;
        packetShift = s.packetShift;
        firstStitch = s.firstStitch;
        shift = s.shift;
        finished = s.finished;
        System.arraycopy(s.packet, 0, packet, 0, packet.length);
        System.arraycopy(s.staging, 0, staging, 0, staging.length);
        System.arraycopy(s.pattern, 0, pattern, 0, pattern.length);
        System.arraycopy(s.fabric, 0, fabric, 0, fabric.length);
    }
    private record SewingMachineState(
            int advanceTicks, int model, int color, int speed, int sb, int incoming, int outgoing,
            int bits, int wireTicks, int internalBits, int payloadClock, int lastRequest,
            int sync, int packetLength, int checksumBytes, int terminator, int terminatorIndex,
            int stagingLength, int patternLength, int headerX, int headerY, int packetShiftRemaining,
            int cursor, int x, int y, int lastY, int endPairs, int motorTicks,
            int acceptedPackets, int rejectedPackets, int completedPatterns, int stitched,
            boolean arm, boolean largeHoop, boolean pedal, boolean paused, boolean advance,
            boolean armed, boolean external, boolean receiving, boolean firstPacket, boolean padding,
            boolean embroidery, boolean repeat, boolean packetShift, boolean firstStitch,
            boolean shift, boolean finished, int[] packet, int[] staging, int[] pattern, int[] fabric)
            implements ComponentState<SerialEndpoint> { }
}
