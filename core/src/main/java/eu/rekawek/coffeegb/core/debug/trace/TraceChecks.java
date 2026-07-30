package eu.rekawek.coffeegb.core.debug.trace;

final class TraceChecks {

    private TraceChecks() {
    }

    static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]: " + value);
        }
    }

    static void nonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    static void knownEvent(TraceEvent event) {
        if (!(event instanceof CpuInstructionTrace)
                && !(event instanceof MemoryAccessTrace)
                && !(event instanceof InterruptTrace)
                && !(event instanceof PpuTrace)
                && !(event instanceof DmaTrace)
                && !(event instanceof TimerTrace)
                && !(event instanceof SerialIrTrace)
                && !(event instanceof InputTrace)
                && !(event instanceof MapperRtcTrace)
                && !(event instanceof ApuTrace)) {
            throw new IllegalArgumentException(
                    "Unsupported TraceEvent implementation: " + event.getClass().getName());
        }
    }
}
