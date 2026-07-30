package eu.rekawek.coffeegb.core.debug.trace;

/** Emulator component that observed or emitted a trace event. */
public enum TraceSource {
    CPU,
    MEMORY_BUS,
    INTERRUPT_CONTROLLER,
    PPU,
    DMA,
    TIMER,
    SERIAL,
    INFRARED,
    INPUT,
    MAPPER,
    RTC,
    APU
}
