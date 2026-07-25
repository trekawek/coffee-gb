package eu.rekawek.coffeegb.core.state;

/**
 * Internal, service-free state value owned by one live emulator component.
 *
 * <p>Implementations are immutable records. This marker deliberately does not extend {@code
 * java.io.Serializable}; disk and network boundaries encode the detached state model explicitly.
 */
public interface ComponentState<T> {}
