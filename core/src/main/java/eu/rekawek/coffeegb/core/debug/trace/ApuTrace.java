package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Objects;

/** APU register, channel, or frame-sequencer transition. */
public record ApuTrace(Kind kind, int channel, int register, int value) implements TraceEvent {

    /** Channel {@code -1} and register/value {@code -1} mean not applicable for the event kind. */
    public enum Kind {
        REGISTER_WRITTEN,
        CHANNEL_TRIGGERED,
        CHANNEL_DISABLED,
        FRAME_SEQUENCER_STEP
    }

    public ApuTrace {
        Objects.requireNonNull(kind, "kind");
        TraceChecks.range("channel", channel, -1, 4);
        TraceChecks.range("register", register, -1, 0xffff);
        TraceChecks.range("value", value, -1, 0xff);
    }

    @Override
    public TraceCategory category() {
        return TraceCategory.APU;
    }
}
