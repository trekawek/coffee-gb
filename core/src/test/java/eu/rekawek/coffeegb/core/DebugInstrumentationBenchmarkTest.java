package eu.rekawek.coffeegb.core;

import com.sun.management.ThreadMXBean;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceFilter;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Manual measurements of the Phase 2/3 breakpoint and trace producer paths.
 *
 * <p>Run in a fresh Maven test JVM immediately after the portable disabled baseline using:
 * {@code mvn -B -pl core -am
 * -Dtest=DebugInstrumentationBenchmarkTest
 * -Dsurefire.failIfNoSpecifiedTests=false -Dcoffeegb.debug.benchmark=true test}
 *
 * <p>Timing and allocation values are observations, not assertions. Normal test runs skip every
 * measured loop. The only assertions below pin deterministic breakpoint/trace accounting.
 */
public class DebugInstrumentationBenchmarkTest {

    private static volatile long sink;

    @Test
    public void measureNoHitBreakpointAndTraceProducerModes() throws Exception {
        assumeTrue(Boolean.getBoolean(BENCHMARK_PROPERTY));

        measure(
                "pc-no-hit",
                DebugInstrumentationBenchmarkTest::configureNoHitPcBreakpoint,
                HOT_PATH_WARMUP_TICKS,
                HOT_PATH_SAMPLES,
                HOT_PATH_TICKS_PER_SAMPLE,
                DebugInstrumentationBenchmarkTest::validateNoHitPcBreakpoint);
        measure(
                "cpu-memory-filter-rejects-all",
                DebugInstrumentationBenchmarkTest::configureRejectingTrace,
                HOT_PATH_WARMUP_TICKS,
                HOT_PATH_SAMPLES,
                HOT_PATH_TICKS_PER_SAMPLE,
                DebugInstrumentationBenchmarkTest::validateRejectingTrace);
        measure(
                "cpu-memory-trace-enabled",
                DebugInstrumentationBenchmarkTest::configureFullTrace,
                TRACE_WARMUP_TICKS,
                TRACE_SAMPLES,
                TRACE_TICKS_PER_SAMPLE,
                DebugInstrumentationBenchmarkTest::validateFullTrace);
        measure(
                "all-categories-trace-enabled",
                DebugInstrumentationBenchmarkTest::configureAllCategoryTrace,
                TRACE_WARMUP_TICKS,
                TRACE_SAMPLES,
                TRACE_TICKS_PER_SAMPLE,
                DebugInstrumentationBenchmarkTest::validateFullTrace);

        System.out.printf(
                Locale.ROOT,
                "DEBUG_PHASE2_BENCHMARK_ENV java=%s vm=%s os=%s arch=%s processors=%d%n",
                token(System.getProperty("java.version")),
                token(System.getProperty("java.vm.name")),
                token(System.getProperty("os.name")),
                token(System.getProperty("os.arch")),
                Runtime.getRuntime().availableProcessors());
    }

    private static void measure(
            String mode,
            InstrumentationSetup setup,
            long warmupTicks,
            int measuredSamples,
            long ticksPerSample,
            InstrumentationValidation validation) throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "debug-phase2-benchmark-" + mode, false);
        Gameboy gameboy = configuration().build();
        try {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            gameboy.getAddressSpace().setByte(0xff40, 0);
            gameboy.getAddressSpace().setByte(0xff26, 0);

            DebugInstrumentation instrumentation = instrumentation();
            setup.configure(instrumentation);
            gameboy.updateDebugInstrumentation(instrumentation, 0);

            runTicks(gameboy, warmupTicks);
            long firstMeasuredSequence = traceState(instrumentation).nextSequence();

            AllocationCounter allocation = threadAllocation();
            long[] sampleNanos = new long[measuredSamples];
            long[] sampleAllocatedBytes = allocation == null ? null : new long[measuredSamples];
            for (int sample = 0; sample < measuredSamples; sample++) {
                long allocatedBefore = allocation == null ? 0 : allocation.current();
                long started = System.nanoTime();
                runTicks(gameboy, ticksPerSample);
                sampleNanos[sample] = System.nanoTime() - started;
                if (allocation != null) {
                    sampleAllocatedBytes[sample] = allocation.current() - allocatedBefore;
                }
                sink = sink * 31 + gameboy.getCpu().getRegisters().getPC();
            }

            if (gameboy.getCpu().getState() == Cpu.State.LOCKED) {
                throw new AssertionError("Benchmark ROM entered the CPU locked state");
            }

            TraceReadResult finalTrace = traceState(instrumentation);
            validation.validate(instrumentation, finalTrace);
            long eventsCaptured = finalTrace.nextSequence() - firstMeasuredSequence;
            long totalMeasuredTicks = Math.multiplyExact(ticksPerSample, measuredSamples);

            long[] sortedNanos = sampleNanos.clone();
            Arrays.sort(sortedNanos);
            long medianNanos = sortedNanos[sortedNanos.length / 2];
            double medianTicksPerSecond = ticksPerSample * 1_000_000_000.0 / medianNanos;

            String allocatedBytes = "unavailable";
            String allocatedBytesPerMillionTicks = "unavailable";
            String grossAllocatedBytesPerCapturedEvent = "unavailable";
            if (sampleAllocatedBytes != null) {
                long totalAllocatedBytes = Arrays.stream(sampleAllocatedBytes).sum();
                allocatedBytes = Long.toString(totalAllocatedBytes);
                allocatedBytesPerMillionTicks = String.format(
                        Locale.ROOT,
                        "%.3f",
                        totalAllocatedBytes * 1_000_000.0 / totalMeasuredTicks);
                if (eventsCaptured != 0) {
                    grossAllocatedBytesPerCapturedEvent = String.format(
                            Locale.ROOT,
                            "%.3f",
                            (double) totalAllocatedBytes / eventsCaptured);
                }
            }

            System.out.printf(
                    Locale.ROOT,
                    "DEBUG_PHASE2_BENCHMARK mode=%s warmupTicks=%d samples=%d "
                            + "ticksPerSample=%d totalMeasuredTicks=%d medianNanos=%d "
                            + "minNanos=%d maxNanos=%d medianTicksPerSecond=%.3f "
                            + "allocatedBytes=%s allocatedBytesPerMillionTicks=%s "
                            + "eventsCaptured=%d eventsPerMillionTicks=%.3f "
                            + "grossAllocatedBytesPerCapturedEvent=%s retainedEntries=%d "
                            + "nextSequence=%d droppedEventCount=%d oldestAvailableSequence=%d "
                            + "sink=%d%n",
                    mode,
                    warmupTicks,
                    measuredSamples,
                    ticksPerSample,
                    totalMeasuredTicks,
                    medianNanos,
                    sortedNanos[0],
                    sortedNanos[sortedNanos.length - 1],
                    medianTicksPerSecond,
                    allocatedBytes,
                    allocatedBytesPerMillionTicks,
                    eventsCaptured,
                    eventsCaptured * 1_000_000.0 / totalMeasuredTicks,
                    grossAllocatedBytesPerCapturedEvent,
                    finalTrace.entries().size(),
                    finalTrace.nextSequence(),
                    finalTrace.droppedEventCount(),
                    finalTrace.oldestAvailableSequence(),
                    sink);
            System.out.printf(
                    Locale.ROOT,
                    "DEBUG_PHASE2_BENCHMARK_SAMPLES mode=%s nanos=%s allocatedBytes=%s%n",
                    mode,
                    Arrays.toString(sampleNanos),
                    sampleAllocatedBytes == null
                            ? "unavailable" : Arrays.toString(sampleAllocatedBytes));
        } finally {
            try {
                eventBus.close();
            } finally {
                gameboy.closeSilently();
            }
        }
    }

    private static void configureNoHitPcBreakpoint(DebugInstrumentation instrumentation) {
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(1), true, DebugPcCondition.at(0x2345)));
    }

    private static void configureRejectingTrace(DebugInstrumentation instrumentation) {
        TraceFilter rejectingFilter = new TraceFilter(
                0x2345,
                0x2345,
                0xc000,
                0xc000,
                EnumSet.noneOf(DebugMemoryAccess.class),
                EnumSet.noneOf(DebugInterruptType.class));
        instrumentation.configureTrace(new TraceConfiguration(
                TRACE_CAPACITY,
                EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY),
                rejectingFilter));
    }

    private static void configureFullTrace(DebugInstrumentation instrumentation) {
        instrumentation.configureTrace(new TraceConfiguration(
                TRACE_CAPACITY,
                EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY),
                TraceFilter.all()));
    }

    private static void configureAllCategoryTrace(DebugInstrumentation instrumentation) {
        instrumentation.configureTrace(new TraceConfiguration(
                TRACE_CAPACITY,
                EnumSet.allOf(TraceCategory.class),
                TraceFilter.all()));
    }

    private static void validateNoHitPcBreakpoint(
            DebugInstrumentation instrumentation, TraceReadResult trace) {
        assertNull(instrumentation.pollBreakpointMatch());
        assertEquals(0, trace.nextSequence());
        assertEquals(0, trace.droppedEventCount());
    }

    private static void validateRejectingTrace(
            DebugInstrumentation instrumentation, TraceReadResult trace) {
        assertNull(instrumentation.pollBreakpointMatch());
        assertEquals(0, trace.nextSequence());
        assertEquals(0, trace.droppedEventCount());
        assertTrue(trace.entries().isEmpty());
    }

    private static void validateFullTrace(
            DebugInstrumentation instrumentation, TraceReadResult trace) {
        assertNull(instrumentation.pollBreakpointMatch());
        assertEquals(TRACE_CAPACITY, trace.entries().size());
        assertTrue(trace.nextSequence() > TRACE_CAPACITY);
        assertEquals(trace.nextSequence() - TRACE_CAPACITY, trace.droppedEventCount());
        assertEquals(trace.droppedEventCount(), trace.missedEventCount());
        assertEquals(trace.nextSequence() - TRACE_CAPACITY, trace.oldestAvailableSequence());
    }

    private static DebugInstrumentation instrumentation() {
        return new DebugInstrumentation(
                1,
                TRACE_CAPACITY,
                TRACE_CAPACITY,
                Set.of(DebugBreakpointKind.PROGRAM_COUNTER),
                EnumSet.allOf(TraceCategory.class));
    }

    private static TraceReadResult traceState(DebugInstrumentation instrumentation) {
        return instrumentation.readTrace(TraceReadRequest.initial(TRACE_CAPACITY));
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

    @FunctionalInterface
    private interface InstrumentationSetup {

        void configure(DebugInstrumentation instrumentation);
    }

    @FunctionalInterface
    private interface InstrumentationValidation {

        void validate(DebugInstrumentation instrumentation, TraceReadResult trace);
    }

    private record AllocationCounter(ThreadMXBean bean, long threadId) {

        private long current() {
            return bean.getThreadAllocatedBytes(threadId);
        }
    }

    private static final String BENCHMARK_PROPERTY = "coffeegb.debug.benchmark";
    private static final int TRACE_CAPACITY = 4096;
    private static final long HOT_PATH_WARMUP_TICKS = 30_000_000L;
    private static final int HOT_PATH_SAMPLES = 9;
    private static final long HOT_PATH_TICKS_PER_SAMPLE = 5_000_000L;
    private static final long TRACE_WARMUP_TICKS = 5_000_000L;
    private static final int TRACE_SAMPLES = 7;
    private static final long TRACE_TICKS_PER_SAMPLE = 1_000_000L;
}
