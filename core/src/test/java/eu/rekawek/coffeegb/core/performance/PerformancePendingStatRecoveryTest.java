package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.Profile;
import static org.junit.Assert.*;

/** An enabled, uncleared mode-2 request must not make DI a permanent batching veto. */
public class PerformancePendingStatRecoveryTest {
    @Test
    public void cpuWrittenPendingMode2RetainsSustainedWorkAcrossEveryClockFamily() throws Exception {
        for (Profile profile : Profile.values()) {
            try (Gameboy scalar = settled(profile); Gameboy candidate = settled(profile)) {
                candidate.setPerformanceBatchingEnabled(true);
                for (int frame = 0; frame < 3; frame++) {
                    assertUsefulSteadyFrame(profile + " pending frame=" + frame, scalar, candidate);
                }
            }
        }
    }

    @Test
    public void pendingMode2SurvivesRestoreThenEiAcknowledgeIfClearAndHaltWake() throws Exception {
        for (Profile profile : Profile.values()) {
            try (Gameboy scalar = settled(profile); Gameboy candidate = settled(profile)) {
                candidate.setPerformanceBatchingEnabled(true);
                var scalarStart = scalar.captureStateWithoutTimeSource();
                var candidateStart = candidate.captureStateWithoutTimeSource();
                int cpuPeriod = profile.doubleSpeed ? 2 : 4;
                for (int phase = 0; phase < cpuPeriod; phase++) {
                    scalar.restoreStateSilently(scalarStart);
                    candidate.restoreStateSilently(candidateStart);
                    if (phase > 0) {
                        scalar.runTicks(phase);
                        candidate.runTicks(phase);
                    }
                    // A RAM command selects code already present in the authored ROM.
                    // The CPU itself executes EI, acknowledges the IRQ, clears IF and HALTs.
                    scalar.getAddressSpace().setByte(0xc100, 1);
                    candidate.getAddressSpace().setByte(0xc100, 1);
                    for (int ticks : new int[]{1, 2, 3, 7, 13, 31, 127, 509, 1021}) {
                        String context = profile + " recovery phase=" + phase + " ticks=" + ticks;
                        assertEquals(context, scalar.runTicks(ticks), candidate.runTicks(ticks));
                        same(context, scalar, candidate);
                    }
                    assertTrue(profile + " EI did not dispatch the pending STAT request",
                            candidate.getAddressSpace().getByte(0xc010) > 0);
                    assertEquals(profile + " IF clear / HALT wake did not return to useful work",
                            1, candidate.getAddressSpace().getByte(0xc011));
                }
                assertUsefulSteadyFrame(profile + " after lifecycle recovery", scalar, candidate);
            }
        }
    }

    private static void assertUsefulSteadyFrame(String label, Gameboy scalar, Gameboy candidate) {
        int ticks = candidate.getClockSpec().controllerTicksPerFrame();
        long before = candidate.getPerformanceEpochTicks();
        assertEquals(label, scalar.runTicks(ticks), candidate.runTicks(ticks));
        same(label, scalar, candidate);
        assertEquals(label + " STAT request unexpectedly disappeared", 2,
                candidate.getAddressSpace().getByte(0xff0f) & 2);
        assertEquals(label + " IE changed", 2, candidate.getAddressSpace().getByte(0xffff));
        assertTrue(label + " sustained CPU work lost useful epochs: "
                        + (candidate.getPerformanceEpochTicks() - before) + '/' + ticks,
                candidate.getPerformanceEpochTicks() - before > ticks / 2);
    }

    private static Gameboy settled(Profile profile) throws Exception {
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image(profile)))
                .setHardwareProfile(profile.hardware)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.runTicks(210_000);
        assertEquals(profile + " initial STAT enable", 0x20,
                gameboy.getAddressSpace().getByte(0xff41) & 0x78);
        assertEquals(profile + " initial enabled STAT request", 2,
                gameboy.getAddressSpace().getByte(0xff0f) & 2);
        assertEquals(profile + " speed setup incomplete", profile.doubleSpeed ? 2 : 1,
                gameboy.getSpeedMode().getSpeedMode());
        return gameboy;
    }

    private static void same(String label, Gameboy scalar, Gameboy candidate) {
        assertStateEquals(label, scalar.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    private static byte[] image(Profile profile) {
        byte[] image = new byte[32768];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0x50;
        image[0x102] = 1;
        image[0x143] = profile.color ? (byte) 0x80 : 0;
        image[0x300] = 0x39;
        // STAT handler records actual CPU dispatch and returns through canonical RETI.
        int[] handler = {0xf5, 0xfa, 0x10, 0xc0, 0x3c, 0xea, 0x10, 0xc0, 0xf1, 0xd9};
        for (int i = 0; i < handler.length; i++) image[0x48 + i] = (byte) handler[i];
        Program p = new Program();
        p.emit(0xf3, 0x31, 0xfe, 0xff); // DI; SP=FFFE.
        p.write(0xffff, 0).io(0x0f, 0);
        if (profile.doubleSpeed) p.io(0x4d, 1).emit(0x10, 0);
        p.write(0xc000, 0).write(0xc010, 0).write(0xc011, 0).write(0xc100, 0);
        p.io(0x41, 0x20).io(0x0f, 0).write(0xffff, 2);
        int loop = p.address();
        p.emit(0x21, 0x00, 0xc0, 0x34, 0x7e); // WRAM read/modify/write.
        p.emit(0xe0, 0x80, 0xf0, 0x80); // HRAM data write/read.
        p.emit(0xfa, 0x00, 0x03, 0xa8, 0x04); // ROM data read, XOR B, INC B.
        p.emit(0xfa, 0x00, 0xc1, 0xb7, 0xca, loop & 255, loop >> 8);
        p.write(0xc100, 0);
        p.emit(0xfb, 0x00, 0x00, 0xf3); // EI; NOP; NOP; DI, with a pending STAT IRQ.
        p.io(0x0f, 0).emit(0x76, 0x00, 0xf3); // Clear IF; HALT; NOP; DI.
        p.io(0x0f, 0).emit(0x21, 0x11, 0xc0, 0x34);
        p.emit(0xc3, loop & 255, loop >> 8);
        byte[] program = p.bytes.toByteArray();
        System.arraycopy(program, 0, image, 0x150, program.length);
        return image;
    }

    private static final class Program {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int address() { return 0x150 + bytes.size(); }
        Program emit(int... data) { for (int value : data) bytes.write(value); return this; }
        Program io(int address, int value) { return emit(0x3e, value, 0xe0, address); }
        Program write(int address, int value) {
            return emit(0x3e, value, 0xea, address & 255, address >> 8);
        }
    }
}
