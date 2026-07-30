package eu.rekawek.coffeegb.core.debug;

/** Memory-bus access identity shared by watchpoints and trace events. */
public enum DebugMemoryAccess {
    READ,
    WRITE,
    EXECUTE
}
