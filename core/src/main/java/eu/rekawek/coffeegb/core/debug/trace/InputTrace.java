package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** Joypad state transition using bit masks owned by the trace value. */
public record InputTrace(Kind kind, int buttonMask, int changedMask) implements TraceEvent {

    public enum Kind {
        PRESSED,
        RELEASED,
        STATE_CHANGED
    }

    public InputTrace {
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("buttonMask", buttonMask, 0, 0xff);
        TraceChecks.range("changedMask", changedMask, 0, 0xff);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.INPUT;
    }
}
