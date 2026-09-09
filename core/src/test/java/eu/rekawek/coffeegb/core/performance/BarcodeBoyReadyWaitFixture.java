package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * Test-only authored cartridge and harness for the real Barcode Boy endpoint.
 *
 * <p>The cartridge performs the documented internal-clock handshake, enters an external-clock
 * wait, receives the two-frame scan, repeats the handshake, and leaves a completion marker in
 * WRAM. The host owns the endpoint and injects a scan only after a returned emulation boundary.
 * This class deliberately does not change any production endpoint capability or scheduler rule.</p>
 */
public final class BarcodeBoyReadyWaitFixture {
    public static final String BARCODE = "4901234567894";

    public static final int PHASE_ADDRESS = 0xc000;
    public static final int HANDSHAKE_REPLY_ADDRESS = 0xc080;
    public static final int FOLLOWUP_REPLY_ADDRESS = 0xc090;
    public static final int PAYLOAD_ADDRESS = 0xc100;
    public static final int PAYLOAD_LENGTH = 30;

    public static final int STARTED_PHASE = 0xa1;
    public static final int WAIT_PHASE = 1;
    public static final int COMPLETE_PHASE = 2;

    private static final int ENTRY = 0x0150;
    private static final int COLOR_FLAG = 0x0143;
    private static final int SC = 0xff02;

    private BarcodeBoyReadyWaitFixture() {
    }

    public enum Profile {
        DMG(HardwareProfileRegistry.DMG, false),
        CGB_X2(HardwareProfileRegistry.CGB, true);

        public final HardwareProfile hardware;
        public final boolean doubleSpeed;

        Profile(HardwareProfile hardware, boolean doubleSpeed) {
            this.hardware = hardware;
            this.doubleSpeed = doubleSpeed;
        }
    }

    public static byte[] image(Profile profile) {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = (byte) ENTRY;
        image[0x102] = (byte) (ENTRY >> 8);
        image[COLOR_FLAG] = profile == Profile.DMG ? 0 : (byte) 0x80;
        image[0x147] = 0;

        Assembler p = new Assembler(image, ENTRY);
        p.emit(0xf3); // DI: the fixture polls serial state and never needs an ISR.
        p.emit(0x31, 0xfe, 0xff); // SP = FFFE.
        p.writeAbs(0xffff, 0);
        p.writeIo(0x0f, 0);

        // The marker is written after STOP returns. The host therefore checks the actual speed
        // mode only after the speed-switch setup has run, rather than trusting ROM metadata.
        if (profile.doubleSpeed) {
            p.writeIo(0x4d, 1);
            p.emit(0x10, 0x00); // STOP; KEY1 requests the CGB double-speed transition.
        }
        p.writeAbs(PHASE_ADDRESS, STARTED_PHASE);
        p.clearBytes(HANDSHAKE_REPLY_ADDRESS, 4);
        p.clearBytes(FOLLOWUP_REPLY_ADDRESS, 4);
        p.clearBytes(PAYLOAD_ADDRESS, PAYLOAD_LENGTH);

        p.emit(0x21, HANDSHAKE_REPLY_ADDRESS & 0xff, HANDSHAKE_REPLY_ADDRESS >> 8);
        emitHandshake(p, 4);

        p.writeAbs(PHASE_ADDRESS, WAIT_PHASE);
        p.emit(0x06, PAYLOAD_LENGTH); // B = bytes in the two scanner frames.
        p.emit(0x21, PAYLOAD_ADDRESS & 0xff, PAYLOAD_ADDRESS >> 8);
        p.writeIo(0x02, 0x80); // external clock, transfer armed

        int externalWait = p.position();
        p.readIoAndMask(0x02, 0x80);
        p.jr(0x20, externalWait); // JR NZ, externalWait while SC bit 7 remains set.
        p.readIo(0x01);
        p.emit(0x22); // LD (HL+), A
        p.emit(0x05); // DEC B
        int afterPayload = p.position();
        p.emit(0x28, 0); // JR Z, completion path; displacement patched after the re-arm branch.
        p.writeIo(0x02, 0x80); // re-arm the next scanner byte
        p.jr(0x18, externalWait);
        p.patchRelative(afterPayload + 1, 0x28, p.position());

        p.emit(0x21, FOLLOWUP_REPLY_ADDRESS & 0xff, FOLLOWUP_REPLY_ADDRESS >> 8);
        emitHandshake(p, 4);
        p.writeAbs(PHASE_ADDRESS, COMPLETE_PHASE);
        int quiet = p.position();
        p.jr(0x18, quiet);
        p.finish();
        return image;
    }

    /**
     * Emits one complete transfer, storing the endpoint reply at the current HL and advancing HL.
     */
    private static void emitHandshake(Assembler p, int count) {
        int[] bytes = {0x10, 0x07, 0x10, 0x07};
        for (int i = 0; i < count; i++) {
            p.writeIo(0x01, bytes[i]);
            p.writeIo(0x02, 0x81); // internal clock, normal link rate
            int wait = p.position();
            p.readIoAndMask(0x02, 0x80);
            p.jr(0x20, wait);
            p.readIo(0x01);
            p.emit(0x22);
        }
    }

    public static int[] expectedHandshakeReply() {
        return new int[]{0xff, 0xff, 0x10, 0x07};
    }

    public static int[] expectedPayload() {
        int[] payload = new int[PAYLOAD_LENGTH];
        for (int repeat = 0; repeat < 2; repeat++) {
            int base = repeat * 15;
            payload[base] = 0x02;
            for (int i = 0; i < BARCODE.length(); i++) {
                payload[base + i + 1] = BARCODE.charAt(i);
            }
            payload[base + 14] = 0x03;
        }
        return payload;
    }

    public static final class Session implements AutoCloseable {
        public final Profile profile;
        public final boolean batching;
        public final EventBusImpl eventBus;
        public final Gameboy gameboy;
        public final BarcodeBoySerialEndpoint endpoint;

        private Session(Profile profile, boolean batching, EventBusImpl eventBus,
                        Gameboy gameboy, BarcodeBoySerialEndpoint endpoint) {
            this.profile = profile;
            this.batching = batching;
            this.eventBus = eventBus;
            this.gameboy = gameboy;
            this.endpoint = endpoint;
        }

        public static Session open(Profile profile, boolean batching) throws IOException {
            BarcodeBoySerialEndpoint endpoint = new BarcodeBoySerialEndpoint();
            Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image(profile)))
                    .setHardwareProfile(profile.hardware)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(ExecutionMode.PERFORMANCE)
                    .setPlayerInputSource(new PlayerInputHub())
                    .setSupportBatterySave(false)
                    .setRtcTimeSource(() -> 0L)
                    .build();
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            gameboy.init(eventBus, endpoint, null);
            if (!batching) {
                configureScalarReference(gameboy);
            }
            return new Session(profile, batching, eventBus, gameboy, endpoint);
        }

        @Override
        public void close() {
            try {
                endpoint.disconnect();
            } finally {
                try {
                    gameboy.closeSilently();
                } finally {
                    eventBus.close();
                }
            }
        }
    }

    /** Small structural snapshot used by the driver receipt without touching debug hooks. */
    public record Observation(int phase, int sb, int sc, boolean externalTransfer,
                              boolean scanPending, boolean transferArmed, int[] pending,
                              int[] payload, int[] handshakeReply, int[] followupReply,
                              int speedMode) {
        public Observation {
            pending = pending == null ? null : pending.clone();
            payload = payload.clone();
            handshakeReply = handshakeReply.clone();
            followupReply = followupReply.clone();
        }
    }

    public static Observation observe(Session session) {
        var mmu = session.gameboy.getAddressSpace();
        int[] payload = read(mmu, PAYLOAD_ADDRESS, PAYLOAD_LENGTH);
        int[] handshake = read(mmu, HANDSHAKE_REPLY_ADDRESS, 4);
        int[] followup = read(mmu, FOLLOWUP_REPLY_ADDRESS, 4);
        BarcodeBoySerialEndpoint.RuntimeState runtime = session.endpoint.captureRuntimeState();
        return new Observation(
                mmu.getByte(PHASE_ADDRESS),
                mmu.getByte(0xff01),
                mmu.getByte(SC),
                session.gameboy.isExternalClockTransferActive(),
                session.endpoint.isScanPending(),
                runtime.transferArmed(),
                runtime.copyPending(),
                payload,
                handshake,
                followup,
                session.gameboy.getSpeedMode().getSpeedMode());
    }

    public static int[] read(eu.rekawek.coffeegb.core.AddressSpace mmu, int start, int length) {
        int[] bytes = new int[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = mmu.getByte(start + i) & 0xff;
        }
        return bytes;
    }

    public static void requirePhase(Session session, int phase, long maxTicks) {
        long elapsed = 0;
        while (elapsed < maxTicks && session.gameboy.getAddressSpace().getByte(PHASE_ADDRESS) != phase) {
            long step = Math.min(2048, maxTicks - elapsed);
            session.gameboy.runTicks(step);
            elapsed += step;
        }
        int actual = session.gameboy.getAddressSpace().getByte(PHASE_ADDRESS);
        if (actual != phase) {
            throw new AssertionError("phase timeout expected=" + phase + " actual=" + actual
                    + " elapsed=" + elapsed + " profile=" + session.profile
                    + " batching=" + session.batching);
        }
    }

    public static void requireExternalWait(Session session, long maxTicks) {
        long elapsed = 0;
        while (elapsed < maxTicks
                && !(session.gameboy.getAddressSpace().getByte(PHASE_ADDRESS) == WAIT_PHASE
                && session.gameboy.isExternalClockTransferActive())) {
            session.gameboy.runTicks(Math.min(2048, maxTicks - elapsed));
            elapsed += Math.min(2048, maxTicks - elapsed);
        }
        if (session.gameboy.getAddressSpace().getByte(PHASE_ADDRESS) != WAIT_PHASE
                || !session.gameboy.isExternalClockTransferActive()) {
            throw new AssertionError("external wait timeout elapsed=" + elapsed
                    + " observation=" + observe(session));
        }
    }

    public static void compareEndpointRuntime(String label, Session expected, Session actual) {
        BarcodeBoySerialEndpoint.RuntimeState a = expected.endpoint.captureRuntimeState();
        BarcodeBoySerialEndpoint.RuntimeState b = actual.endpoint.captureRuntimeState();
        if (a.transferArmed() != b.transferArmed()) {
            throw new AssertionError(label + " transferArmed expected=" + a.transferArmed()
                    + " actual=" + b.transferArmed());
        }
        int[] ap = a.copyPending();
        int[] bp = b.copyPending();
        if (!java.util.Arrays.equals(ap, bp)) {
            throw new AssertionError(label + " pending expected=" + java.util.Arrays.toString(ap)
                    + " actual=" + java.util.Arrays.toString(bp));
        }
        PerformanceStateAssertions.assertStateEquals(label + " endpoint state",
                expected.endpoint.captureState(), actual.endpoint.captureState());
    }

    /**
     * Selects the scalar scheduler for the candidate scalar reference. The normal
     * batching=true path never calls this method, so the baseline driver remains compatible
     * without depending on this candidate-only setter. A candidate scalar differential test
     * must fail if the setter is absent rather than silently changing its reference mode.
     */
    public static void configureScalarReference(Gameboy gameboy) {
        try {
            gameboy.getClass().getMethod("setPerformanceBatchingEnabled", boolean.class)
                    .invoke(gameboy, false);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Candidate scalar reference requires performance batching control", e);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Unable to select candidate scalar reference", e);
        }
    }

    /** Resets candidate scheduler counters when the selected production build exposes them. */
    public static void resetPerformanceCounters(Gameboy gameboy) {
        try {
            gameboy.getClass().getMethod("resetPerformanceBulkCounters").invoke(gameboy);
        } catch (NoSuchMethodException ignored) {
            // Baseline driver output reports unsupported counters as -1.
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Unable to reset performance counters", e);
        }
    }

    /** Returns a candidate counter, or -1 when an older baseline has no such telemetry. */
    public static long performanceCounter(Gameboy gameboy, String methodName) {
        try {
            Object value = gameboy.getClass().getMethod(methodName).invoke(gameboy);
            return ((Number) value).longValue();
        } catch (NoSuchMethodException ignored) {
            return -1L;
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Unable to read performance counter " + methodName, e);
        }
    }

    private static final class Assembler {
        private final byte[] image;
        private int position;

        Assembler(byte[] image, int base) {
            this.image = image;
            this.position = base;
        }

        int position() {
            return position;
        }

        void emit(int... bytes) {
            for (int value : bytes) {
                if (position >= image.length) {
                    throw new IllegalStateException("Barcode fixture ROM overflow at " + hex(position));
                }
                image[position++] = (byte) value;
            }
        }

        void writeIo(int offset, int value) {
            emit(0x3e, value, 0xe0, offset);
        }

        void writeAbs(int address, int value) {
            emit(0x3e, value, 0xea, address & 0xff, address >> 8);
        }

        void readIo(int offset) {
            emit(0xf0, offset);
        }

        void readIoAndMask(int offset, int mask) {
            readIo(offset);
            emit(0xe6, mask);
        }

        void clearBytes(int address, int count) {
            emit(0xaf, 0x21, address & 0xff, address >> 8, 0x06, count);
            int loop = position;
            emit(0x22, 0x05);
            jr(0x20, loop);
        }

        void jr(int opcode, int target) {
            emit(opcode, 0);
            patchRelative(position - 1, opcode, target);
        }

        void patchRelative(int operandPosition, int opcode, int target) {
            if ((image[operandPosition - 1] & 0xff) != opcode) {
                throw new IllegalStateException("unexpected JR opcode at " + hex(operandPosition - 1));
            }
            int displacement = target - (operandPosition + 1);
            if (displacement < -128 || displacement > 127) {
                throw new IllegalStateException("JR out of range from " + hex(operandPosition)
                        + " to " + hex(target));
            }
            image[operandPosition] = (byte) displacement;
        }

        void finish() {
            // A fixture image is intentionally small; leave the rest of the ROM as zero padding.
            if (position >= image.length) {
                throw new IllegalStateException("Barcode fixture ROM overflow at " + hex(position));
            }
        }

        private static String hex(int value) {
            return String.format("0x%04x", value);
        }
    }
}
