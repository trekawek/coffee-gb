package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** A serial-transfer or CGB infrared-port transition. */
public record SerialIrTrace(Endpoint endpoint, Kind kind, int value) implements TraceEvent {

    public enum Endpoint {
        SERIAL,
        INFRARED
    }

    public enum Kind {
        TRANSFER_STARTED,
        BIT_SHIFTED,
        BYTE_TRANSFERRED,
        SIGNAL_CHANGED
    }

    public SerialIrTrace {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("value", value, 0, 0xff);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.SERIAL_IR;
    }
}
