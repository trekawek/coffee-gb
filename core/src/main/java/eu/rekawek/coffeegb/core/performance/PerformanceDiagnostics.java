package eu.rekawek.coffeegb.core.performance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Optional owner-thread execution accounting. No observer hooks or emulated state are involved.
 * Recording is allocation-free; detached snapshots allocate only when explicitly requested.
 */
public final class PerformanceDiagnostics {
    public enum Execution { SCALAR, EPOCH, HALT, TRANSFER, PHASE }

    public enum Subsystem {
        PPU_REPLAY, STAT_REPLAY, AUDIO_MATERIALIZED, DMA_REPLAY, PPU_QUIET_DURING_DMA,
        DMA_BATCHED_COPY, PPU_QUIET_AFTER_REPLAY, PPU_QUIET_STEADY
    }

    public enum Blocker {
        BOOT, OBSERVATION, RESET, CPU_STATE, CPU_INTERRUPT, CPU_PHASE, DMA, HDMA,
        SERIAL, INFRARED, INPUT, TIMER, AUDIO, CARTRIDGE, LCD_OFF, PROFILE,
        PPU_OBSERVATION, PPU_ALIAS, PPU_DMA, PPU_LATCH, PPU_FIRST_LINE, PPU_PROFILE,
        PPU_OUTPUT, PPU_CHECKPOINT, PPU_LINE_END, STAT, EVENT_BOUNDARY,
        SERIAL_UNBOUNDED_ENDPOINT;

        public long mask() { return 1L << ordinal(); }
    }

    public enum Fence { UNKNOWN, ROM_CONTROL, VRAM, CARTRIDGE, OAM, JOYPAD, SERIAL,
        TIMER, INTERRUPT, SOUND, STAT, LY, PPU, OTHER_IO, RAM }

    public record Window(long startTick, long ticks, long scalarTicks, long epochTicks,
                         long longestScalarRun, long rejectedLines) {}

    public record Rejection(List<Blocker> reasons, long count) {}

    public record Snapshot(long ticks, Map<Execution, Long> executionTicks,
                           Map<Blocker, Long> blockers, Map<Fence, Long> fences,
                           Map<Subsystem, Long> subsystemTicks, List<Long> spanHistogram,
                           long longestScalarRun, long directLines, long rejectedLines,
                           long longestRejectedLineRun, List<Window> windows,
                           long rejectionCombinations, long overflowCombinations,
                           List<Rejection> rejectionReasons) {}

    private static final Execution[] EXECUTIONS = Execution.values();
    private static final Blocker[] BLOCKERS = Blocker.values();
    private final long[] executionTicks = new long[EXECUTIONS.length];
    private final long[] blockers = new long[BLOCKERS.length];
    private final long[] fences = new long[Fence.values().length];
    private final long[] subsystemTicks = new long[Subsystem.values().length];
    private final long[] spanHistogram = new long[65];
    private final long[] combinations = new long[32];
    private final long[] combinationCounts = new long[32];
    private final long[][] windows = new long[60][6];
    private final int ticksPerWindow;
    private long ticks;
    private long scalarRun;
    private long longestScalarRun;
    private long directLines;
    private long rejectedLines;
    private long rejectedLineRun;
    private long longestRejectedLineRun;
    private long windowStart;
    private int windowTicks;
    private long windowScalar;
    private long windowEpoch;
    private long windowLongestScalar;
    private long windowRejectedLines;
    private int completedWindows;
    private long overflowCombinations;

    public PerformanceDiagnostics(int ticksPerWindow) {
        if (ticksPerWindow <= 0) {
            throw new IllegalArgumentException("window must contain positive master ticks");
        }
        this.ticksPerWindow = ticksPerWindow;
    }

    public void recordTicks(Execution execution, int count) {
        if (count <= 0) {
            return;
        }
        executionTicks[execution.ordinal()] += count;
        spanHistogram[Math.min(count, 64)]++;
        if (execution != Execution.SCALAR) {
            scalarRun = 0;
        }
        int remaining = count;
        while (remaining > 0) {
            int part = Math.min(remaining, ticksPerWindow - windowTicks);
            if (execution == Execution.SCALAR) {
                scalarRun += part;
                windowScalar += part;
                longestScalarRun = Math.max(longestScalarRun, scalarRun);
                windowLongestScalar = Math.max(windowLongestScalar, scalarRun);
            } else if (execution == Execution.EPOCH) {
                windowEpoch += part;
            }
            ticks += part;
            windowTicks += part;
            remaining -= part;
            if (windowTicks == ticksPerWindow) {
                long[] row = windows[completedWindows++ % windows.length];
                row[0] = windowStart;
                row[1] = windowTicks;
                row[2] = windowScalar;
                row[3] = windowEpoch;
                row[4] = windowLongestScalar;
                row[5] = windowRejectedLines;
                windowStart = ticks;
                windowTicks = 0;
                windowScalar = 0;
                windowEpoch = 0;
                windowLongestScalar = 0;
                windowRejectedLines = 0;
            }
        }
    }

    /** Multiple simultaneous reasons are retained; this is not a first-failing-guard counter. */
    public void recordRejected(long mask) {
        if (mask == 0) {
            mask = Blocker.EVENT_BOUNDARY.mask();
        }
        for (int bit = 0; bit < blockers.length; bit++) {
            if ((mask & (1L << bit)) != 0) {
                blockers[bit]++;
            }
        }
        for (int i = 0; i < combinations.length; i++) {
            if (combinations[i] == mask || combinations[i] == 0) {
                combinations[i] = mask;
                combinationCounts[i]++;
                return;
            }
        }
        overflowCombinations++;
    }

    public void recordScanline(boolean direct, long mask) {
        if (direct) {
            directLines++;
            rejectedLineRun = 0;
        } else {
            rejectedLines++;
            windowRejectedLines++;
            longestRejectedLineRun = Math.max(longestRejectedLineRun, ++rejectedLineRun);
            recordRejected(mask);
        }
    }

    /** Records emulated dots handled by a subsystem strategy, not measured host work. */
    public void recordReplay(Subsystem subsystem, int count) {
        subsystemTicks[subsystem.ordinal()] += count;
    }

    public void recordFence(int address) {
        fences[classifyFence(address).ordinal()]++;
    }

    private static Fence classifyFence(int address) {
        if (address < 0 || address > 0xffff) return Fence.UNKNOWN;
        if (address < 0x8000) return Fence.ROM_CONTROL;
        if (address < 0xa000) return Fence.VRAM;
        if (address < 0xc000) return Fence.CARTRIDGE;
        if (address < 0xfe00) return Fence.RAM;
        if (address < 0xff00) return Fence.OAM;
        if (address == 0xff00) return Fence.JOYPAD;
        if (address <= 0xff02) return Fence.SERIAL;
        if (address >= 0xff04 && address <= 0xff07) return Fence.TIMER;
        if (address == 0xff0f || address == 0xffff) return Fence.INTERRUPT;
        if (address >= 0xff10 && address <= 0xff3f) return Fence.SOUND;
        if (address == 0xff41) return Fence.STAT;
        if (address == 0xff44) return Fence.LY;
        if (address >= 0xff40 && address <= 0xff6b) return Fence.PPU;
        return address >= 0xff80 ? Fence.RAM : Fence.OTHER_IO;
    }

    public Snapshot snapshot() {
        List<Window> detachedWindows = new ArrayList<>();
        int first = Math.max(0, completedWindows - windows.length);
        for (int i = first; i < completedWindows; i++) {
            long[] row = windows[i % windows.length];
            detachedWindows.add(new Window(row[0], row[1], row[2], row[3], row[4], row[5]));
        }
        if (windowTicks > 0) {
            detachedWindows.add(new Window(windowStart, windowTicks, windowScalar, windowEpoch,
                    windowLongestScalar, windowRejectedLines));
        }
        List<Long> histogram = new ArrayList<>(spanHistogram.length);
        for (long count : spanHistogram) histogram.add(count);
        long recordedCombinations = 0;
        for (long count : combinationCounts) recordedCombinations += count;
        List<Rejection> rejectionReasons = new ArrayList<>();
        for (int i = 0; i < combinations.length; i++) {
            if (combinationCounts[i] == 0) continue;
            List<Blocker> reasons = new ArrayList<>();
            for (Blocker reason : BLOCKERS) {
                if ((combinations[i] & reason.mask()) != 0) reasons.add(reason);
            }
            rejectionReasons.add(new Rejection(List.copyOf(reasons), combinationCounts[i]));
        }
        return new Snapshot(ticks, counts(Execution.class, executionTicks),
                counts(Blocker.class, blockers), counts(Fence.class, fences),
                counts(Subsystem.class, subsystemTicks), List.copyOf(histogram), longestScalarRun,
                directLines, rejectedLines, longestRejectedLineRun, List.copyOf(detachedWindows),
                recordedCombinations, overflowCombinations, List.copyOf(rejectionReasons));
    }

    private static <E extends Enum<E>> Map<E, Long> counts(Class<E> type, long[] values) {
        Map<E, Long> result = new EnumMap<>(type);
        for (E key : type.getEnumConstants()) result.put(key, values[key.ordinal()]);
        return Map.copyOf(result);
    }
}
