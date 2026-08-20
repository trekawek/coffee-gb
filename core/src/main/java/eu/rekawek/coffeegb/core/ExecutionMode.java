package eu.rekawek.coffeegb.core;

/**
 * Execution strategy selected for one emulation session.
 *
 * <p>The mode is session metadata: it is not part of the emulated hardware state and is
 * therefore not saved or restored with a save state. {@link #ACCURACY} is the reference
 * implementation and the default for every session.</p>
 */
public enum ExecutionMode {
    /** Cycle- and dot-accurate reference execution. */
    ACCURACY,

    /** Guarded performance execution, with deoptimization to {@link #ACCURACY} as needed. */
    PERFORMANCE
}
