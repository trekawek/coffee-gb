package eu.rekawek.coffeegb.core.memory;

/**
 * Borrowed live HRAM read view for one bounded owner-thread native-x2 detailed CPU packet.
 * Providers certify that every bypassed layer is inert for FF80..FFFD. CPU writes still
 * use the canonical bus and every later read observes their current value; this is neither
 * a byte snapshot nor an instruction cache. No mutable backing storage is exposed.
 *
 * <p>Discard the view at every packet/state/observer boundary. Mapper or cheat topology
 * must remain owner-thread stable during the transaction, as for the existing ROM lease.
 */
public interface PerformanceHramReadAccess {
    /** Live byte, or -1 outside FF80..FFFD; an out-of-range request must not touch the bus. */
    int readCpuByte(int address);
}
