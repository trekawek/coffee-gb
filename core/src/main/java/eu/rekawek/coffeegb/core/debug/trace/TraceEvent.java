package eu.rekawek.coffeegb.core.debug.trace;

/**
 * Immutable typed payload for one trace entry.
 *
 * <p>The supported payloads are the records in this package. The Java 16 target predates final
 * sealed types, so {@link TraceEntry} validates that a payload is one of those records before it
 * can enter an immutable trace result.
 */
public interface TraceEvent {

    TraceCategory category();
}
