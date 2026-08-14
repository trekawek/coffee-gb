package eu.rekawek.coffeegb.core.experimental.clock;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.experimental.clock.CgbSpeedSwitchClockMachine.Speed.DOUBLE;
import static eu.rekawek.coffeegb.core.experimental.clock.CgbSpeedSwitchClockMachine.Speed.NORMAL;
import static eu.rekawek.coffeegb.core.experimental.clock.CgbSpeedSwitchClockMachine.State.MUX_SETTLE;
import static eu.rekawek.coffeegb.core.experimental.clock.CgbSpeedSwitchClockMachine.State.SWITCH_DELAY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Executable evidence and falsifiers for {@link CgbSpeedSwitchClockMachine}. */
public class CgbSpeedSwitchClockMachineTest {

    @Test
    public void runStateRoutesOnlyCpuDerivedDomainsAtDoubleRate() {
        for (CgbSpeedSwitchClockMachine.Speed speed
                : CgbSpeedSwitchClockMachine.Speed.values()) {
            CgbSpeedSwitchClockMachine machine = machine(speed, 0);

            step(machine, 32);

            assertEquals(16, machine.fixedEdges());
            assertEquals(16, machine.ppuClockEdges());
            assertEquals(16, machine.apuOscillatorEdges());
            assertEquals(16, machine.hdmaClockEdges());
            long expectedSelectedEdges = speed == DOUBLE ? 32 : 16;
            assertEquals(expectedSelectedEdges, machine.cpuRunEdges());
            assertEquals(expectedSelectedEdges, machine.dividerClockEdges());
            assertEquals(expectedSelectedEdges, machine.serialClockEdges());
            assertEquals(expectedSelectedEdges, machine.oamDmaClockEdges());
        }
    }

    @Test
    public void switchDelayGatesCpuDivSerialAndOamButNotFixedDomains() {
        CgbSpeedSwitchClockMachine machine = machine(NORMAL, 0);
        machine.requestSpeedSwitch();
        enterDelay(machine);
        Counts before = counts(machine);

        step(machine, 512);

        Counts delta = counts(machine).minus(before);
        assertEquals(SWITCH_DELAY, machine.state());
        assertEquals(0, delta.cpuRun());
        assertEquals(0, delta.divider());
        assertEquals(0, delta.serial());
        assertEquals(0, delta.oamDma());
        assertEquals(256, delta.ppu());
        assertEquals(256, delta.apuOscillator());
        assertEquals(256, delta.hdma());
        assertEquals(512, delta.switchDelay());
        assertEquals(0, machine.divider());
    }

    @Test
    public void explicitStopEntryReproducesTimerPlusFourWithoutARepairOperation() {
        // The current callback reaches Timer.onSpeedSwitch after four normal clocks have moved
        // the divider from $0008 to $000c. Its +4 repair reaches the same falling edge.
        SpeedMode currentSpeed = new SpeedMode(true);
        Timer current = new Timer(new InterruptManager(true), currentSpeed);
        current.presetDiv(0x000c);
        current.setByte(0xff05, 0x20);
        current.setByte(0xff07, 0x05);
        current.onSpeedSwitch();
        current.setByte(0xff04, 0);
        assertEquals(0x21, current.getByte(0xff05));

        // The candidate begins before those hidden T states. Eight destination-clock edges occupy
        // the same four fixed dots, naturally exposing the four double-speed half-edges omitted by
        // the old one-dot callback.
        CgbSpeedSwitchClockMachine candidate = machine(NORMAL, 0);
        candidate.presetDivider(0x0008);
        candidate.configureTimer(true, 3, 0x20);
        candidate.requestSpeedSwitch();
        enterDelay(candidate);

        assertEquals(8, candidate.stopEntryEdges());
        assertEquals(4, candidate.fixedEdges());
        assertEquals(8, candidate.dividerClockEdges());
        assertEquals(1, candidate.timerFallingEdges());
        assertEquals(0x21, candidate.tima());
        assertEquals(0, candidate.divider());
    }

    @Test
    public void selectedClockRippleExplainsBothDirectionalWallClockDurations() {
        DelayTrace normalToDouble = runDelay(NORMAL);
        DelayTrace doubleToNormal = runDelay(DOUBLE);

        long sequencerModulus = 1L << CgbSpeedSwitchClockMachine.SWITCH_SEQUENCER_BITS;
        assertEquals(sequencerModulus, normalToDouble.sequencerEdges());
        assertEquals(sequencerModulus, doubleToNormal.sequencerEdges());
        assertEquals(65_536, normalToDouble.fixedEdges());
        assertEquals(131_072, doubleToNormal.fixedEdges());
        assertEquals(0, normalToDouble.dividerEdges());
        assertEquals(0, doubleToNormal.dividerEdges());
        assertEquals(0, normalToDouble.cpuRunEdges());
        assertEquals(0, doubleToNormal.cpuRunEdges());
    }

    @Test
    public void freeRunningReleaseRingProducesEveryTailIncludingObservedTwoAndEight() {
        Set<Long> tailDots = candidateTailDots();

        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), tailDots);
        assertTrue(tailDots.contains(2L));
        assertTrue(tailDots.contains(8L));
    }

    @Test
    public void currentSchedulerMatchesRippleDurationButAdvancesDivInsideThePause()
            throws IOException {
        try (Gameboy current = newGameboy(false)) {
            advanceToCpuSwitch(current);
            int pc = current.getCpu().getRegisters().getPC();
            int line = current.getGpu().getLine();
            int dot = current.getGpu().getTicksInLine();

            for (int i = 0; i < 128; i++) {
                current.tick();
            }

            assertTrue(current.getCpu().isSpeedSwitching());
            assertEquals(pc, current.getCpu().getRegisters().getPC());
            assertNotEquals(line * 456 + dot,
                    current.getGpu().getLine() * 456 + current.getGpu().getTicksInLine());
            // Current Timer.tick() emits two divider clocks per dot during the delay.
            assertEquals(1, current.getAddressSpace().getByte(0xff04));

            int delayDots = 128;
            while (current.getCpu().isSpeedSwitching() && delayDots < 70_000) {
                current.tick();
                delayDots++;
            }
            assertFalse(current.getCpu().isSpeedSwitching());
            assertEquals(65_536, delayDots);
            // 0x20000 clocks alias to zero in a 16-bit counter. A post-STOP DIV check therefore
            // cannot tell a genuinely gated divider from the current free-running one.
            assertEquals(0, current.getAddressSpace().getByte(0xff04));
        }

        CgbSpeedSwitchClockMachine candidate = machine(NORMAL, 0);
        candidate.requestSpeedSwitch();
        enterDelay(candidate);
        long fixedBefore = candidate.fixedEdges();
        step(candidate, 256);
        assertEquals(SWITCH_DELAY, candidate.state());
        assertEquals(0, candidate.divider());
        while (candidate.state() == SWITCH_DELAY) {
            candidate.stepHalfDot();
        }
        assertEquals(65_536, candidate.fixedEdges() - fixedBefore);
        assertEquals(0, candidate.divider());
    }

    @Test
    public void timerAccumulationWouldDistinguishFreeRunningAndGatedDivOnHardware()
            throws IOException {
        int productionDelta;
        try (Gameboy current = newGameboy(timerSpeedSwitchRom())) {
            advanceToCpuSwitch(current);
            int before = current.getAddressSpace().getByte(0xff05);
            int delayDots = 0;
            while (current.getCpu().isSpeedSwitching() && delayDots < 70_000) {
                current.tick();
                delayDots++;
            }

            assertEquals(65_536, delayDots);
            int after = current.getAddressSpace().getByte(0xff05);
            productionDelta = (after - before) & 0xff;
            // TAC bit 9 falls once per 1024 CPU clocks. The current loop emits 0x20000 clocks.
            assertEquals(128, productionDelta);
        }

        CgbSpeedSwitchClockMachine gated = machine(NORMAL, 0);
        gated.configureTimer(true, 9, 0);
        gated.requestSpeedSwitch();
        enterDelay(gated);
        int before = gated.tima();
        while (gated.state() == SWITCH_DELAY) {
            gated.stepHalfDot();
        }

        assertEquals(0, (gated.tima() - before) & 0xff);
        assertNotEquals("a hardware TIMA capture after STOP would decide the model",
                productionDelta, (gated.tima() - before) & 0xff);
    }

    @Test
    public void naiveImmediateMuxAndNaiveGlobalStopAreBothFalsified() {
        CgbSpeedSwitchClockMachine candidate = machine(NORMAL, 0);
        candidate.requestSpeedSwitch();
        enterDelay(candidate);
        Counts before = counts(candidate);
        while (candidate.state() == SWITCH_DELAY) {
            candidate.stepHalfDot();
        }
        Counts delta = counts(candidate).minus(before);

        int selectedClocks = 1 << CgbSpeedSwitchClockMachine.SWITCH_SEQUENCER_BITS;
        assertEquals(0, delta.cpuRun());
        assertEquals(0, delta.divider());
        assertEquals(65_536, delta.ppu());
        assertEquals(65_536, delta.apuOscillator());
        assertEquals(0, delta.apuFrame());

        // Immediate mux + ungated DIV emits clocks and eight bit-13 falling edges. It can still
        // finish at DIV=0 because the 16-bit counter wrapped exactly twice.
        assertEquals(0, selectedClocks & 0xffff);
        assertEquals(8, naiveFallingEdges(selectedClocks, 13));
        assertNotEquals(0, selectedClocks);

        // Conversely a global STOP would freeze LY/STAT along with the CPU, contradicting the
        // Daid LY/STAT captures. The fixed branch must remain independent.
        int naiveGlobalStopPpuEdges = 0;
        assertNotEquals(naiveGlobalStopPpuEdges, delta.ppu());
    }

    @Test
    public void divApuEdgeComesFromTapMuxAndResetWithoutSoundPhaseCallback() {
        CgbSpeedSwitchClockMachine candidate = machine(NORMAL, 0);
        // Normal bit 12 is high while double-speed bit 13 is low. Selecting the destination tap
        // lowers the DIV-APU wire on the first explicit half-dot.
        candidate.presetDivider(0x1000);
        candidate.requestSpeedSwitch();

        CgbSpeedSwitchClockMachine.Signals first = candidate.stepHalfDot();
        assertTrue(first.apuFrameFallingEdge());
        enterDelay(candidate);

        assertEquals(1, candidate.apuFrameFallingEdges());
    }

    @Test
    public void currentTwoAndEightDotTailSamplesFitRingButDoNotFixItsWiring()
            throws IOException {
        Set<Long> candidateTails = candidateTailDots();

        try (Gameboy longPhase = newGameboy(false);
                Gameboy shortPhase = newGameboy(true)) {
            advanceToTail(longPhase);
            advanceToTail(shortPhase);
            long currentLong = drainTail(longPhase);
            long currentShort = drainTail(shortPhase);

            assertEquals(8, currentLong);
            assertEquals(2, currentShort);
            assertTrue(candidateTails.contains(currentLong));
            assertTrue(candidateTails.contains(currentShort));
        }
    }

    private static CgbSpeedSwitchClockMachine machine(
            CgbSpeedSwitchClockMachine.Speed speed, int phase) {
        return new CgbSpeedSwitchClockMachine(speed, phase);
    }

    private static void step(CgbSpeedSwitchClockMachine machine, int halfDots) {
        for (int i = 0; i < halfDots; i++) {
            machine.stepHalfDot();
        }
    }

    private static void enterDelay(CgbSpeedSwitchClockMachine machine) {
        for (int i = 0; i < 32 && machine.state() != SWITCH_DELAY; i++) {
            machine.stepHalfDot();
        }
        assertEquals(SWITCH_DELAY, machine.state());
        assertEquals(0, machine.divider());
    }

    private static DelayTrace runDelay(CgbSpeedSwitchClockMachine.Speed initialSpeed) {
        CgbSpeedSwitchClockMachine machine = machine(initialSpeed, 0);
        machine.requestSpeedSwitch();
        enterDelay(machine);
        Counts before = counts(machine);
        while (machine.state() == SWITCH_DELAY) {
            machine.stepHalfDot();
        }
        assertEquals(MUX_SETTLE, machine.state());
        Counts delta = counts(machine).minus(before);
        return new DelayTrace(
                delta.switchDelay(), delta.fixed(), delta.divider(), delta.cpuRun());
    }

    private static Set<Long> candidateTailDots() {
        Set<Long> tailDots = new HashSet<>();
        for (int initialPhase = 0; initialPhase < (1 << CgbSpeedSwitchClockMachine.RELEASE_PHASE_BITS);
                initialPhase++) {
            CgbSpeedSwitchClockMachine machine = machine(NORMAL, initialPhase);
            machine.requestSpeedSwitch();
            enterDelay(machine);
            while (machine.state() == SWITCH_DELAY) {
                machine.stepHalfDot();
            }
            long fixedBefore = machine.fixedEdges();
            while (machine.state() == MUX_SETTLE) {
                machine.stepHalfDot();
            }
            tailDots.add(machine.fixedEdges() - fixedBefore);
        }
        return tailDots;
    }

    private static Counts counts(CgbSpeedSwitchClockMachine machine) {
        return new Counts(
                machine.fixedEdges(),
                machine.cpuRunEdges(),
                machine.dividerClockEdges(),
                machine.serialClockEdges(),
                machine.oamDmaClockEdges(),
                machine.ppuClockEdges(),
                machine.apuOscillatorEdges(),
                machine.hdmaClockEdges(),
                machine.switchDelayEdges(),
                machine.apuFrameFallingEdges());
    }

    private static int naiveFallingEdges(int clocks, int bit) {
        int div = 0;
        boolean previous = false;
        int edges = 0;
        for (int i = 0; i < clocks; i++) {
            div = (div + 1) & 0xffff;
            boolean next = (div & (1 << bit)) != 0;
            if (previous && !next) {
                edges++;
            }
            previous = next;
        }
        return edges;
    }

    private static Gameboy newGameboy(boolean shiftPpuByTwoDots) throws IOException {
        Gameboy gameboy = newGameboy(speedSwitchRom());
        if (shiftPpuByTwoDots) {
            gameboy.getGpu().tick();
            gameboy.getGpu().tick();
        }
        return gameboy;
    }

    private static Gameboy newGameboy(byte[] rom) throws IOException {
        return new GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.CGB)
                .setSupportBatterySave(false)
                .build();
    }

    private static void advanceToCpuSwitch(Gameboy gameboy) {
        for (int i = 0; i < 100 && !gameboy.getCpu().isSpeedSwitching(); i++) {
            gameboy.tick();
        }
        assertEquals(Cpu.State.SPEED_SWITCH, gameboy.getCpu().getState());
        assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
    }

    private static void advanceToTail(Gameboy gameboy) {
        for (int i = 0; i < 70_000 && speedSwitchTailTicks(gameboy) == 0; i++) {
            gameboy.tick();
        }
        assertTrue(speedSwitchTailTicks(gameboy) > 0);
    }

    private static long drainTail(Gameboy gameboy) {
        long dots = 0;
        while (speedSwitchTailTicks(gameboy) > 0) {
            gameboy.tick();
            dots++;
        }
        return dots;
    }

    /** The production observation is package-private; reflection keeps this experiment detached. */
    private static int speedSwitchTailTicks(Gameboy gameboy) {
        try {
            var field = Gameboy.class.getDeclaredField("speedSwitchTailTicks");
            field.setAccessible(true);
            return field.getInt(gameboy);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("speed-switch tail observation seam changed", e);
        }
    }

    private static byte[] speedSwitchRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x3e; // ld a,1
        rom[0x101] = 0x01;
        rom[0x102] = (byte) 0xe0; // ldh [rKEY1],a
        rom[0x103] = 0x4d;
        rom[0x104] = 0x10; // stop
        rom[0x105] = 0x00;
        rom[0x106] = 0x04; // inc b
        rom[0x107] = 0x18; // jr $106
        rom[0x108] = (byte) 0xfd;
        rom[0x143] = (byte) 0x80;
        rom[0x147] = 0;
        return rom;
    }

    private static byte[] timerSpeedSwitchRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = (byte) 0xaf; // xor a
        rom[0x101] = (byte) 0xe0; // ldh [rDIV],a
        rom[0x102] = 0x04;
        rom[0x103] = (byte) 0xe0; // ldh [rTIMA],a
        rom[0x104] = 0x05;
        rom[0x105] = 0x3e; // ld a,$04: timer on, DIV bit 9
        rom[0x106] = 0x04;
        rom[0x107] = (byte) 0xe0; // ldh [rTAC],a
        rom[0x108] = 0x07;
        rom[0x109] = 0x3e; // ld a,1
        rom[0x10a] = 0x01;
        rom[0x10b] = (byte) 0xe0; // ldh [rKEY1],a
        rom[0x10c] = 0x4d;
        rom[0x10d] = 0x10; // stop
        rom[0x10e] = 0x00;
        rom[0x10f] = 0x18; // jr $10f
        rom[0x110] = (byte) 0xfe;
        rom[0x143] = (byte) 0x80;
        rom[0x147] = 0;
        return rom;
    }

    private record DelayTrace(
            long sequencerEdges, long fixedEdges, long dividerEdges, long cpuRunEdges) {
    }

    private record Counts(
            long fixed,
            long cpuRun,
            long divider,
            long serial,
            long oamDma,
            long ppu,
            long apuOscillator,
            long hdma,
            long switchDelay,
            long apuFrame) {

        Counts minus(Counts before) {
            return new Counts(
                    fixed - before.fixed,
                    cpuRun - before.cpuRun,
                    divider - before.divider,
                    serial - before.serial,
                    oamDma - before.oamDma,
                    ppu - before.ppu,
                    apuOscillator - before.apuOscillator,
                    hdma - before.hdma,
                    switchDelay - before.switchDelay,
                    apuFrame - before.apuFrame);
        }
    }
}
