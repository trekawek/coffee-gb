package eu.rekawek.coffeegb.core.experimental.dma;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detached request/grant experiment for the DMG/CGB DMA buses.
 *
 * <p>The topology deliberately separates four things which are interleaved by the production
 * {@code Gameboy.tick()} callback order:
 *
 * <ol>
 *   <li>a three-cell PPU-to-CPU HBlank request synchronizer,</li>
 *   <li>a retained CPU/VRAM-DMA lease,</li>
 *   <li>clock-domain sequencers which emit bus intents, and</li>
 *   <li>a pure resolver followed by a write-only commit.</li>
 * </ol>
 *
 * <p>The DMA fabric never sees an opcode byte, a future micro-op, a PPU mode number, or Java
 * callback order. The CPU says whether its current slot is claimed, relinquished (HALT/STOP), or
 * an interrupt lease. The PPU submits an intent only while a physical OAM/VRAM port is actually in
 * use.
 *
 * <p><strong>Evidence label: behavioral request/grant decomposition, self-test, and production
 * differential.</strong> Inputs such as {@code dmaPreemptsThisPhase},
 * {@code lateInterruptAccepted}, lease retirement, and PPU port intent are still semantic oracles;
 * calibrated startup/profile logic also remains in the sequencers. The experiment demonstrates a
 * candidate dependency direction, but it cannot yet claim to replace opcode lookahead or access
 * overrides until persistent raw CPU/PPU strobes derive those inputs and the profile tables can be
 * deleted.
 */
final class DmaRequestGrantTopology {

    enum Board {
        DMG,
        CGB
    }

    enum Speed {
        NORMAL(1),
        DOUBLE(2);

        final int cpuClocksPerMasterTick;

        Speed(int cpuClocksPerMasterTick) {
            this.cpuClocksPerMasterTick = cpuClocksPerMasterTick;
        }
    }

    enum Master {
        CPU,
        PPU,
        OAM_DMA,
        VRAM_DMA
    }

    enum Bus {
        CARTRIDGE,
        WRAM,
        VRAM,
        OAM,
        HRAM
    }

    private enum Wire {
        DMG_MAIN,
        CARTRIDGE,
        WRAM,
        VRAM,
        OAM,
        HRAM
    }

    enum Cycle {
        READ,
        WRITE
    }

    /** A condition for which this bounded model refuses to invent a result. */
    enum Falsifier {
        DUPLICATE_MASTER_PORT_INTENT,
        CPU_AND_VRAM_DMA_EXECUTE_TOGETHER,
        PPU_FETCH_DURING_VRAM_DMA_COMMIT,
        CPU_WRITE_ON_OAM_DMA_SOURCE_WIRE,
        OAM_COPY_WITHOUT_SOURCE
    }

    /**
     * Profiles intentionally outside this experiment. A production migration is gated on an
     * independent model or hardware vector for each one.
     */
    enum UnmodeledProfile {
        HALT_WAKE_REQUEST_LEVEL_HISTORY,
        STOP_AND_SPEED_SWITCH_REVERSE_PHASE,
        TERMINAL_HBLANK_REQUEST_CUTOFF,
        OVERLAPPING_HBLANK_REQUEST_QUEUE,
        HDMA_DISABLE_DURING_REQUEST_HANDOFF,
        OAM_DMA_HALT_ENTRY_LATENCY,
        OAM_DMA_RESTART_OWNERSHIP,
        PPU_OAM_READER_ACQUIRE_RELEASE_HISTORY,
        DMG_PARTIAL_SOURCE_ADDRESS_DECODE,
        CGB_OAM_SOURCE_A12_WRAM_ALIAS,
        INTERRUPT_STACK_WRITE_DELAYED_COLLISION,
        CPU_OAM_DMA_ANALOG_WRITE_CORRUPTION,
        INVALID_VRAM_SOURCE_OPEN_BUS_DECAY,
        VRAM_BLOCK_DESTINATION_VISIBILITY
    }

    record Intent(Master master, Bus bus, Cycle cycle, int address, int value) {

        Intent {
            address &= 0xffff;
            value &= 0xff;
        }

        static Intent read(Master master, Bus bus, int address) {
            return new Intent(master, bus, Cycle.READ, address, 0xff);
        }

        static Intent write(Master master, Bus bus, int address, int value) {
            return new Intent(master, bus, Cycle.WRITE, address, value);
        }
    }

    interface Snapshot {
        int read(Bus bus, int address);
    }

    interface WriteSink {
        void write(Bus bus, int address, int value);
    }

    record ResolvedWrite(Master master, Bus bus, int address, int value) {

        ResolvedWrite {
            address &= 0xffff;
            value &= 0xff;
        }
    }

    record Resolution(
            Map<Master, Integer> readValues,
            List<ResolvedWrite> writes,
            Set<Master> denied,
            Set<Falsifier> falsifiers) {

        Resolution {
            readValues = Map.copyOf(readValues);
            writes = List.copyOf(writes);
            denied = Set.copyOf(denied);
            falsifiers = Set.copyOf(falsifiers);
        }
    }

    private static final Comparator<Intent> INTENT_ORDER = Comparator
            .comparing((Intent i) -> i.master().ordinal())
            .thenComparing(i -> i.bus().ordinal())
            .thenComparing(i -> i.cycle().ordinal())
            .thenComparingInt(Intent::address)
            .thenComparingInt(Intent::value);

    /** Resolves a complete set of same-cycle intents without mutating memory. */
    static Resolution resolve(Board board, Snapshot snapshot, Collection<Intent> unorderedIntents) {
        List<Intent> intents = unorderedIntents.stream().sorted(INTENT_ORDER).toList();
        EnumSet<Falsifier> falsifiers = EnumSet.noneOf(Falsifier.class);
        detectDuplicatePorts(intents, falsifiers);

        Intent cpu = first(intents, Master.CPU, null, null);
        Intent ppuVramRead = first(intents, Master.PPU, Bus.VRAM, Cycle.READ);
        Intent oamSource = first(intents, Master.OAM_DMA, null, Cycle.READ);
        Intent oamDestination = first(intents, Master.OAM_DMA, Bus.OAM, Cycle.WRITE);
        Intent vramSource = first(intents, Master.VRAM_DMA, null, Cycle.READ);
        Intent vramDestination = first(intents, Master.VRAM_DMA, Bus.VRAM, Cycle.WRITE);

        if (cpu != null && (vramSource != null || vramDestination != null)) {
            // A valid retained grant makes these intents mutually exclusive. Seeing both is an
            // architectural falsifier, not a reason to teach the bus about CPU opcodes.
            falsifiers.add(Falsifier.CPU_AND_VRAM_DMA_EXECUTE_TOGETHER);
        }
        if (ppuVramRead != null && vramDestination != null) {
            falsifiers.add(Falsifier.PPU_FETCH_DURING_VRAM_DMA_COMMIT);
        }

        int dmaDrivenOamAddress = -1;
        int dmaDrivenOamValue = -1;
        if (oamDestination != null) {
            if (vramSource != null) {
                // Both engines meet at the DMA source mux. VRAM DMA's sample strobe wins; its
                // source low byte is consequently decoded as the OAM destination address.
                dmaDrivenOamAddress = 0xfe00 | (vramSource.address() & 0xff);
                dmaDrivenOamValue = read(snapshot, vramSource);
            } else if (oamSource != null) {
                dmaDrivenOamAddress = oamDestination.address();
                dmaDrivenOamValue = read(snapshot, oamSource);
            } else {
                falsifiers.add(Falsifier.OAM_COPY_WITHOUT_SOURCE);
            }
        }

        EnumMap<Master, Integer> reads = new EnumMap<>(Master.class);
        EnumSet<Master> denied = EnumSet.noneOf(Master.class);
        List<ResolvedWrite> writes = new ArrayList<>();

        for (Intent intent : intents) {
            if (intent.cycle() != Cycle.READ) {
                continue;
            }
            switch (intent.master()) {
                case CPU -> reads.put(Master.CPU, resolveCpuRead(
                        board, snapshot, intent, intents, oamSource, oamDestination,
                        vramSource, denied));
                case PPU -> {
                    int value = intent.bus() == Bus.OAM && dmaDrivenOamValue >= 0
                            ? dmaDrivenOamValue
                            : read(snapshot, intent);
                    reads.put(Master.PPU, value);
                }
                case OAM_DMA -> {
                    // When VRAM DMA drives the shared source mux the ordinary OAM source port
                    // is not sampled at all.
                    if (vramSource == null) {
                        reads.put(Master.OAM_DMA, read(snapshot, intent));
                    }
                }
                case VRAM_DMA -> reads.put(Master.VRAM_DMA, read(snapshot, intent));
            }
        }

        boolean emittedOamWrite = false;
        for (Intent intent : intents) {
            if (intent.cycle() != Cycle.WRITE) {
                continue;
            }
            switch (intent.master()) {
                case CPU -> {
                    if (cpuWriteDenied(board, intent, intents, oamSource, oamDestination,
                            vramSource, denied, falsifiers)) {
                        continue;
                    }
                    writes.add(writeFrom(intent));
                }
                case PPU -> writes.add(writeFrom(intent));
                case OAM_DMA -> {
                    if (!emittedOamWrite && dmaDrivenOamAddress >= 0) {
                        writes.add(new ResolvedWrite(
                                Master.OAM_DMA, Bus.OAM,
                                dmaDrivenOamAddress, dmaDrivenOamValue));
                        emittedOamWrite = true;
                    }
                }
                case VRAM_DMA -> {
                    if (ppuVramRead == null) {
                        writes.add(writeFrom(intent));
                    }
                }
            }
        }

        writes.sort(Comparator
                .comparing((ResolvedWrite w) -> w.bus().ordinal())
                .thenComparingInt(ResolvedWrite::address)
                .thenComparing(w -> w.master().ordinal())
                .thenComparingInt(ResolvedWrite::value));
        return new Resolution(reads, writes, denied, falsifiers);
    }

    /** Applies only the already-resolved write strobes. */
    static void commit(Resolution resolution, WriteSink sink) {
        for (ResolvedWrite write : resolution.writes()) {
            sink.write(write.bus(), write.address(), write.value());
        }
    }

    private static int resolveCpuRead(
            Board board,
            Snapshot snapshot,
            Intent cpu,
            List<Intent> intents,
            Intent oamSource,
            Intent oamDestination,
            Intent vramSource,
            EnumSet<Master> denied) {
        if ((cpu.bus() == Bus.VRAM && first(intents, Master.PPU, Bus.VRAM, Cycle.READ) != null)
                || (cpu.bus() == Bus.OAM
                && (first(intents, Master.PPU, Bus.OAM, Cycle.READ) != null
                || oamDestination != null))) {
            denied.add(Master.CPU);
            return 0xff;
        }
        if (oamSource != null && wire(board, cpu.bus()) == wire(board, oamSource.bus())) {
            denied.add(Master.CPU);
            return read(snapshot, oamSource);
        }
        if (vramSource != null && wire(board, cpu.bus()) == wire(board, vramSource.bus())) {
            denied.add(Master.CPU);
            return read(snapshot, vramSource);
        }
        return read(snapshot, cpu);
    }

    private static boolean cpuWriteDenied(
            Board board,
            Intent cpu,
            List<Intent> intents,
            Intent oamSource,
            Intent oamDestination,
            Intent vramSource,
            EnumSet<Master> denied,
            EnumSet<Falsifier> falsifiers) {
        if ((cpu.bus() == Bus.VRAM && first(intents, Master.PPU, Bus.VRAM, Cycle.READ) != null)
                || (cpu.bus() == Bus.OAM
                && (first(intents, Master.PPU, Bus.OAM, Cycle.READ) != null
                || oamDestination != null))) {
            denied.add(Master.CPU);
            return true;
        }
        if (oamSource != null && wire(board, cpu.bus()) == wire(board, oamSource.bus())) {
            // Read ownership is digital. The data-dependent OAM corruption caused by a CPU
            // write is an electrical rule and remains an explicit migration blocker.
            denied.add(Master.CPU);
            falsifiers.add(Falsifier.CPU_WRITE_ON_OAM_DMA_SOURCE_WIRE);
            return true;
        }
        if (vramSource != null && wire(board, cpu.bus()) == wire(board, vramSource.bus())) {
            denied.add(Master.CPU);
            return true;
        }
        return false;
    }

    private static ResolvedWrite writeFrom(Intent intent) {
        return new ResolvedWrite(intent.master(), intent.bus(), intent.address(), intent.value());
    }

    private static int read(Snapshot snapshot, Intent intent) {
        return snapshot.read(intent.bus(), intent.address()) & 0xff;
    }

    private static Wire wire(Board board, Bus bus) {
        if (board == Board.DMG && (bus == Bus.CARTRIDGE || bus == Bus.WRAM)) {
            return Wire.DMG_MAIN;
        }
        return switch (bus) {
            case CARTRIDGE -> Wire.CARTRIDGE;
            case WRAM -> Wire.WRAM;
            case VRAM -> Wire.VRAM;
            case OAM -> Wire.OAM;
            case HRAM -> Wire.HRAM;
        };
    }

    private static Intent first(
            List<Intent> intents, Master master, Bus bus, Cycle cycle) {
        for (Intent intent : intents) {
            if (intent.master() == master
                    && (bus == null || intent.bus() == bus)
                    && (cycle == null || intent.cycle() == cycle)) {
                return intent;
            }
        }
        return null;
    }

    private static void detectDuplicatePorts(
            List<Intent> intents, EnumSet<Falsifier> falsifiers) {
        Set<String> ports = new HashSet<>();
        for (Intent intent : intents) {
            String port = intent.master() + ":" + intent.bus() + ":" + intent.cycle();
            if (!ports.add(port)) {
                falsifiers.add(Falsifier.DUPLICATE_MASTER_PORT_INTENT);
            }
        }
    }

    static Set<UnmodeledProfile> unmodeledProfiles() {
        return Set.copyOf(EnumSet.allOf(UnmodeledProfile.class));
    }

    enum Lease {
        NONE,
        CPU_INSTRUCTION,
        CPU_INTERRUPT,
        VRAM_DMA
    }

    enum CpuClaim {
        NONE,
        INSTRUCTION,
        INTERRUPT,
        RELINQUISH
    }

    /** The three retained cells needed by request arbitration. */
    record GrantState(boolean requestLatched, Lease lease, boolean lateInterruptEligible) {

        static GrantState idle() {
            return new GrantState(false, Lease.NONE, false);
        }
    }

    /**
     * Signals are produced by owners, not inferred by DMA. In particular, HALT is simply
     * {@link CpuClaim#RELINQUISH}; no {@code 0x76} comparison exists here.
     */
    record GrantSignals(
            boolean request,
            CpuClaim cpuClaim,
            boolean dmaPreemptsThisPhase,
            boolean interruptAlreadyPending,
            boolean cpuLeaseEnds,
            boolean lateInterruptAccepted,
            boolean burstEnds) {

        static GrantSignals request(CpuClaim claim) {
            return new GrantSignals(true, claim, false, false, false, false, false);
        }

        static GrantSignals idle() {
            return new GrantSignals(false, CpuClaim.NONE,
                    false, false, false, false, false);
        }
    }

    /** Pure next-state logic for the request and lease latches. */
    static GrantState resolveGrant(GrantState old, GrantSignals signals) {
        if (signals.burstEnds()) {
            return GrantState.idle();
        }

        boolean requestLatched = old.requestLatched() || signals.request();
        Lease lease = old.lease();
        boolean lateInterruptEligible = old.lateInterruptEligible();

        if (lease == Lease.CPU_INSTRUCTION && signals.cpuLeaseEnds()) {
            if (lateInterruptEligible && signals.lateInterruptAccepted()) {
                lease = Lease.CPU_INTERRUPT;
            } else {
                lease = Lease.VRAM_DMA;
            }
            lateInterruptEligible = false;
        } else if (lease == Lease.CPU_INTERRUPT && signals.cpuLeaseEnds()) {
            lease = Lease.VRAM_DMA;
        } else if (lease == Lease.NONE && requestLatched) {
            if (!signals.dmaPreemptsThisPhase()
                    && signals.cpuClaim() == CpuClaim.INSTRUCTION) {
                lease = Lease.CPU_INSTRUCTION;
                lateInterruptEligible = !signals.interruptAlreadyPending();
            } else if (!signals.dmaPreemptsThisPhase()
                    && signals.cpuClaim() == CpuClaim.INTERRUPT) {
                lease = Lease.CPU_INTERRUPT;
                lateInterruptEligible = false;
            } else {
                lease = Lease.VRAM_DMA;
                lateInterruptEligible = false;
            }
        }
        return new GrantState(requestLatched, lease, lateInterruptEligible);
    }

    /** A resolve-then-commit wrapper used to prove old state is stable during resolution. */
    static final class GrantLatch {

        private GrantState state = GrantState.idle();

        GrantState state() {
            return state;
        }

        GrantState resolve(GrantSignals signals) {
            return resolveGrant(state, signals);
        }

        void commit(GrantState resolved) {
            state = resolved;
        }

        void step(GrantSignals signals) {
            commit(resolve(signals));
        }
    }

    record SyncState(boolean stage0, boolean stage1, boolean request) {

        static SyncState clear() {
            return new SyncState(false, false, false);
        }
    }

    /** One HBlank level crossing three explicit synchronizer cells. */
    static SyncState resolveSynchronizer(
            SyncState old, boolean ppuHblankLevel, boolean cpuClockEnabled) {
        if (!cpuClockEnabled) {
            return old;
        }
        return new SyncState(ppuHblankLevel, old.stage0(), old.stage1());
    }

    record OamState(boolean active, int transferClocks, int nextByte) {

        static OamState started() {
            return new OamState(true, 0, 0);
        }
    }

    record OamCopyEdge(int byteIndex, int transferClock) {}

    record OamStep(OamState next, List<OamCopyEdge> copyEdges) {

        OamStep {
            copyEdges = List.copyOf(copyEdges);
        }
    }

    /** OAM DMA advances in CPU clocks; speed changes affect only future clocks. */
    static OamStep resolveOam(OamState old, Speed speed, boolean cpuClockPaused) {
        if (!old.active() || cpuClockPaused) {
            return new OamStep(old, List.of());
        }
        int clocks = old.transferClocks();
        int nextByte = old.nextByte();
        boolean active = old.active();
        List<OamCopyEdge> edges = new ArrayList<>(1);
        for (int i = 0; i < speed.cpuClocksPerMasterTick && active; i++) {
            clocks++;
            if (clocks >= 8 && clocks <= 644 && clocks % 4 == 0) {
                edges.add(new OamCopyEdge(nextByte++, clocks));
            }
            if (clocks >= 648) {
                active = false;
            }
        }
        return new OamStep(new OamState(active, clocks, nextByte), edges);
    }

    enum VramTransfer {
        GENERAL,
        HBLANK
    }

    record VramState(boolean active, int phase, int sourceBase) {

        static VramState started(VramTransfer transfer, Speed speed,
                                 int hblankStartDot, int sourceBase) {
            return new VramState(
                    true,
                    -startupTicks(transfer, speed, hblankStartDot),
                    sourceBase & 0xffff);
        }
    }

    record VramSourceSlot(int byteIndex, int address) {}

    record VramStep(VramState next, VramSourceSlot sourceSlot, boolean blockCommit) {}

    /** VRAM DMA uses one fixed-rate bus phase per granted master tick. */
    static VramStep resolveVram(VramState old, boolean grant) {
        if (!old.active() || !grant) {
            return new VramStep(old, null, false);
        }
        int phase = old.phase() + 1;
        VramSourceSlot sourceSlot = null;
        if (phase > 0 && phase < 0x20 && (phase & 1) != 0) {
            int index = phase >> 1;
            sourceSlot = new VramSourceSlot(index, (old.sourceBase() + index) & 0xffff);
        }
        boolean commit = phase == 0x20;
        return new VramStep(
                new VramState(!commit, phase, old.sourceBase()), sourceSlot, commit);
    }

    static int startupTicks(VramTransfer transfer, Speed speed, int hblankStartDot) {
        if (transfer == VramTransfer.GENERAL) {
            return speed == Speed.DOUBLE ? 2 : 6;
        }
        if (speed == Speed.NORMAL) {
            return 4;
        }
        return 2 + ((hblankStartDot & 1) == 0 ? 1 : 0);
    }

    private DmaRequestGrantTopology() {
    }
}
