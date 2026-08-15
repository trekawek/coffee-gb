package eu.rekawek.coffeegb.core.experimental.apu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detached fixed-speed DMG path from an ordinary CPU NR44.7 write through HOGA/GYSU into the
 * channel-4 clock cone.
 *
 * <p>This is deliberately a raw-clock experiment, not another trigger-alignment table. A caller
 * queues a CPU write and supplies only master T-cycles. The observed CPU write aperture, DOVA's
 * APU_PHI level, GYSU sampling, and JESO/HAMA half all emerge from one reset-seeded four-state
 * ring. There is no public phase, countdown, or event-offset input.
 *
 * <p>Static provenance: {@code https://github.com/msinger/dmg-sim} revision
 * {@value #NETLIST_REVISION}, {@code dmg_cpu_b/dmg_cpu_b.sv}. DOVA inverts DATA_PHASE into
 * APU_PHI; FOXE/Goxo gate FF23 writes into HOGA; GYSU samples HOGA on APU_PHI; GUZY lets
 * CH4_START asynchronously clear HOGA. ATYK/AVOK form CH4_1MHZ and JESO retains its HAMA half.
 * The downstream restart chain is HAZO -> GONE -> GORA -> GATY -> JERY.
 *
 * <p>External trace provenance: Icarus Verilog 14.0-devel (1d2aa1b), {@code TIMING=nodelay},
 * the same revision and top-level {@code dmg_cpu_b_gameboy.sv}. The minimal generated boot
 * program was {@code LD SP,$fffe; LD A,$80; LDH ($26),A; LD A,$f0; LDH ($21),A;
 * LD A,$09; LDH ($22),A; LD A,$80; LDH ($23),A; JR -2}. A second trace used the local
 * SameSuite {@code apu/channel_4/channel_4_frequency_alignment.gb} fixture. Event monitors
 * sampled one ps after each edge so nodelay delta cycles had settled. These are logic-level
 * observations, not measured silicon and not a propagation-delay claim.
 */
final class DmgNoiseTriggerWriteCone {

    static final String NETLIST_REVISION = "ee559e1d963e1cc522df512e3bae1b4e5ff96fb5";

    enum Evidence {
        STATIC_NETLIST_CONNECTIVITY,
        IVERILOG_NODELAY_MINIMAL_BOOT_TRACE,
        IVERILOG_NODELAY_SAMESUITE_TRACE
    }

    enum Falsifier {
        TIMING_ENABLED_PROPAGATION_REORDERS_T_EVENTS,
        RESET_RELEASE_OR_CLOCK_GATING_CHANGES_RAW_SEED,
        CPU_WRITE_USES_ANOTHER_RAW_APERTURE,
        STOP_OR_NON_CPU_WRITE_PATH,
        SIMULTANEOUS_APU_RESET_OR_DAC_DISABLE,
        LIVE_NR43_WRITE_COLLISION,
        APU_TEST_MODE_BYPASS,
        CGB_OR_DOUBLE_SPEED_CONTROL_PROFILE
    }

    enum InputBoundary {
        RESET_RELEASED_DMG_DIVIDER_STATE,
        FIXED_SPEED_MASTER_TICK,
        LATCHED_NR43_BITS,
        DAC_ALREADY_ENABLED,
        QUEUED_CPU_NR44_D7_WRITE
    }

    record Provenance(
            String repository,
            String revision,
            String netlist,
            String topLevel,
            String timingProfile,
            String simulator) {
    }

    record Observation(
            long tick,
            int rawClockPhase,
            boolean cpuWriteCommitted,
            boolean apuPhiRising,
            boolean apuPhiHigh,
            boolean hogaHigh,
            boolean ch4StartRising,
            boolean ch4StartHigh,
            boolean hamaHigh,
            boolean frequencyDisabled,
            int ratioCounter,
            DmgNoiseGateTopology.Observation downstream) {
    }

    private final DmgNoiseGateTopology downstream;

    private boolean writeQueued;

    /** HOGA, the NR44.D7 write latch before GYSU. */
    private boolean hoga;

    /** GYSU.q. */
    private boolean ch4Start;

    private long ticks;

    private DmgNoiseTriggerWriteCone(int nr43) {
        downstream = DmgNoiseGateTopology.resetSeed(nr43);
    }

    static DmgNoiseTriggerWriteCone resetSeed(int nr43) {
        return new DmgNoiseTriggerWriteCone(nr43);
    }

    static Provenance provenance() {
        return new Provenance(
                "https://github.com/msinger/dmg-sim",
                NETLIST_REVISION,
                "dmg_cpu_b/dmg_cpu_b.sv",
                "dmg_cpu_b_gameboy.sv",
                "TIMING=nodelay",
                "Icarus Verilog 14.0-devel (1d2aa1b)");
    }

    static Set<Evidence> evidence() {
        return Set.copyOf(EnumSet.allOf(Evidence.class));
    }

    static Set<Falsifier> falsifiers() {
        return Set.copyOf(EnumSet.allOf(Falsifier.class));
    }

    static Set<InputBoundary> inputBoundaries() {
        return Set.copyOf(EnumSet.allOf(InputBoundary.class));
    }

    /** Stages an ordinary fixed-speed CPU write of one to NR44.D7. */
    void queueCpuTriggerWrite() {
        if (writeQueued) {
            throw new IllegalStateException("an NR44 trigger write is already queued");
        }
        writeQueued = true;
    }

    /** Advances one raw 4.194304 MHz master T-cycle. */
    Observation tick() {
        int oldClockPhase = downstream.masterPhase();
        boolean oldApuPhi = apuPhi(oldClockPhase);
        DmgNoiseGateTopology.Observation downstreamObservation = downstream.tick();
        int newClockPhase = downstream.masterPhase();
        if (newClockPhase != (oldClockPhase + 1 & 3)) {
            throw new IllegalStateException("downstream raw clock ring lost lock");
        }

        boolean newApuPhi = apuPhi(newClockPhase);
        boolean apuPhiRising = !oldApuPhi && newApuPhi;
        boolean ch4StartRising = false;
        if (apuPhiRising) {
            // GYSU samples the old HOGA value. Its asserted q then clears HOGA through GUZY.
            boolean oldCh4Start = ch4Start;
            ch4Start = hoga;
            ch4StartRising = !oldCh4Start && ch4Start;
            if (ch4StartRising) {
                downstream.latchSynchronizedStart();
            }
            if (ch4Start) {
                hoga = false;
            }
        }

        boolean cpuWriteCommitted = writeQueued && cpuWriteAperture(newClockPhase);
        if (cpuWriteCommitted) {
            writeQueued = false;
            // HOGA's asynchronous reset remains dominant during GYSU's high pulse.
            if (!ch4Start) {
                hoga = true;
            }
        }

        ticks++;
        return new Observation(ticks, newClockPhase, cpuWriteCommitted, apuPhiRising,
                newApuPhi, hoga, ch4StartRising, ch4Start, downstream.hama(),
                downstream.frequencyDisabled(), downstream.ratioCounter(),
                downstreamObservation);
    }

    boolean writeQueued() {
        return writeQueued;
    }

    private static boolean cpuWriteAperture(int rawClockPhase) {
        // External traces place HOGA's write assertion in CH4-low/APU_PHI-low, one T before
        // CH4_1MHZ rises and two T before the next APU_PHI rising edge.
        return rawClockPhase == 3;
    }

    private static boolean apuPhi(int rawClockPhase) {
        return rawClockPhase == 1 || rawClockPhase == 2;
    }
}
