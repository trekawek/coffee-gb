package eu.rekawek.coffeegb.core;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

/**
 * Manual measurement of the direct {@link Gameboy#tick()} path with debugger retirement
 * observation disabled.
 *
 * <p>Run with:
 * {@code mvn -B -pl core -am -Dtest=DebugDisabledBenchmarkTest
 * -Dsurefire.failIfNoSpecifiedTests=false -Dcoffeegb.debug.benchmark=true test}
 *
 * <p>The benchmark has no timing or allocation assertion. Results are observations to compare
 * across revisions on the same machine; normal test runs skip the workload.
 */
public class DebugDisabledBenchmarkTest {

    private static volatile long sink;

    @Test
    public void measureDirectGameboyTickWithRetirementObservationDisabled() throws Exception {
        assumeTrue(Boolean.getBoolean(BENCHMARK_PROPERTY));

        EventBusImpl eventBus = new EventBusImpl(null, "debug-disabled-benchmark", false);
        Gameboy gameboy = configuration().build();
        try {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            // Keep the workload focused on the CPU retirement path. Disabling LCD and APU output
            // prevents frame/audio delivery from adding unrelated periodic allocations.
            gameboy.getAddressSpace().setByte(0xff40, 0);
            gameboy.getAddressSpace().setByte(0xff26, 0);

            runTicks(gameboy, WARMUP_TICKS);

            AllocationCounter allocation = threadAllocation();
            long[] sampleNanos = new long[MEASURED_SAMPLES];
            long[] sampleAllocatedBytes = allocation == null
                    ? null : new long[MEASURED_SAMPLES];
            for (int sample = 0; sample < MEASURED_SAMPLES; sample++) {
                long allocatedBefore = allocation == null ? 0 : allocation.current();
                long started = System.nanoTime();
                runTicks(gameboy, TICKS_PER_SAMPLE);
                sampleNanos[sample] = System.nanoTime() - started;
                if (allocation != null) {
                    sampleAllocatedBytes[sample] = allocation.current() - allocatedBefore;
                }

                // Make the final machine state observable without adding work inside the measured
                // loop. The benchmark intentionally calls no debug API so this source can also be
                // applied unchanged to the pre-debug baseline.
                sink = sink * 31 + gameboy.getCpu().getRegisters().getPC();
            }

            if (gameboy.getCpu().getState() == Cpu.State.LOCKED) {
                throw new AssertionError("Benchmark ROM entered the CPU locked state");
            }

            long[] sortedNanos = sampleNanos.clone();
            Arrays.sort(sortedNanos);
            long medianNanos = sortedNanos[sortedNanos.length / 2];
            long totalMeasuredTicks = Math.multiplyExact(TICKS_PER_SAMPLE, MEASURED_SAMPLES);
            double medianTicksPerSecond = TICKS_PER_SAMPLE * 1_000_000_000.0 / medianNanos;

            String allocatedBytes = "unavailable";
            String allocatedBytesPerMillionTicks = "unavailable";
            if (sampleAllocatedBytes != null) {
                long totalAllocatedBytes = Arrays.stream(sampleAllocatedBytes).sum();
                allocatedBytes = Long.toString(totalAllocatedBytes);
                allocatedBytesPerMillionTicks = String.format(
                        Locale.ROOT,
                        "%.3f",
                        totalAllocatedBytes * 1_000_000.0 / totalMeasuredTicks);
            }

            System.out.printf(
                    Locale.ROOT,
                    "DEBUG_DISABLED_BENCHMARK mode=direct-gameboy-retirement-disabled "
                            + "warmupTicks=%d samples=%d ticksPerSample=%d totalMeasuredTicks=%d "
                            + "medianNanos=%d minNanos=%d maxNanos=%d "
                            + "medianTicksPerSecond=%.3f allocatedBytes=%s "
                            + "allocatedBytesPerMillionTicks=%s sink=%d%n",
                    WARMUP_TICKS,
                    MEASURED_SAMPLES,
                    TICKS_PER_SAMPLE,
                    totalMeasuredTicks,
                    medianNanos,
                    sortedNanos[0],
                    sortedNanos[sortedNanos.length - 1],
                    medianTicksPerSecond,
                    allocatedBytes,
                    allocatedBytesPerMillionTicks,
                    sink);
            System.out.printf(
                    Locale.ROOT,
                    "DEBUG_DISABLED_BENCHMARK_SAMPLES nanos=%s allocatedBytes=%s%n",
                    Arrays.toString(sampleNanos),
                    sampleAllocatedBytes == null
                            ? "unavailable" : Arrays.toString(sampleAllocatedBytes));
            System.out.printf(
                    Locale.ROOT,
                    "DEBUG_DISABLED_BENCHMARK_ENV java=%s vm=%s os=%s arch=%s processors=%d%n",
                    token(System.getProperty("java.version")),
                    token(System.getProperty("java.vm.name")),
                    token(System.getProperty("os.name")),
                    token(System.getProperty("os.arch")),
                    Runtime.getRuntime().availableProcessors());
        } finally {
            try {
                eventBus.close();
            } finally {
                gameboy.closeSilently();
            }
        }
    }

    private static Gameboy.GameboyConfiguration configuration() throws Exception {
        byte[] rom = new byte[0x8000];
        byte[] title = "DEBUG-BENCH".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0100] = 0x18; // JR -2: deterministic, retirement-dense loop
        rom[0x0101] = (byte) 0xfe;
        rom[0x0143] = (byte) 0x80; // CGB-compatible
        rom[0x0147] = 0; // ROM-only
        rom[0x0148] = 0; // 32 KiB ROM
        rom[0x0149] = 0; // no cartridge RAM
        rom[0x014d] = 0x73; // header checksum for the deterministic bytes above
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setGameboyType(GameboyType.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false);
    }

    private static void runTicks(Gameboy gameboy, long ticks) {
        for (long tick = 0; tick < ticks; tick++) {
            gameboy.tick();
        }
    }

    private static AllocationCounter threadAllocation() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return new AllocationCounter(bean, Thread.currentThread().getId());
    }

    private static String token(String value) {
        return value == null ? "unknown" : value.trim().replaceAll("\\s+", "_");
    }

    private record AllocationCounter(ThreadMXBean bean, long threadId) {

        private long current() {
            return bean.getThreadAllocatedBytes(threadId);
        }
    }

    private static final String BENCHMARK_PROPERTY = "coffeegb.debug.benchmark";
    private static final long WARMUP_TICKS = 30_000_000L;
    private static final int MEASURED_SAMPLES = 9;
    private static final long TICKS_PER_SAMPLE = 5_000_000L;
}
