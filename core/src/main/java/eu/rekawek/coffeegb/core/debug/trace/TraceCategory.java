package eu.rekawek.coffeegb.core.debug.trace;

/** Coarse event groups used to negotiate and cheaply filter trace capture. */
public enum TraceCategory {
    CPU,
    MEMORY,
    INTERRUPT,
    PPU,
    DMA,
    TIMER,
    SERIAL_IR,
    INPUT,
    MAPPER_RTC,
    APU
}
