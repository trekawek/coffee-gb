package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import java.util.function.IntUnaryOperator;

public interface SerialEndpoint extends StatefulComponent<SerialEndpoint> {

    int PERFORMANCE_CLOCK_IDLE = 1;
    int PERFORMANCE_CLOCK_INTERNAL = 2;
    int PERFORMANCE_CLOCK_EXTERNAL_WAIT = 4;

    /**
     * Metadata for opt-in diagnostics, never an admission proof. Bits describe the endpoint's
     * advertised clock contracts even when an actual event makes their current horizon zero.
     * A missing bit denotes an unbounded or unadvertised contract for that port state. The
     * event-horizon methods remain authoritative; this query must not poll or advance a device.
     */
    default int performanceClockCapabilities() {
        return 0;
    }

    /** Stable zero-based player index for an in-process link, or {@code -1}. */
    default int linkPlayerIndex() {
        return -1;
    }

    /** Enables a narrowly detected cartridge compatibility profile for this endpoint. */
    default void enableCompatibilityProfile(SerialCompatibilityProfile profile) {
    }

    /**
     * Returns the largest span which can advance this endpoint without entering its scalar
     * master-clock callback. During the admitted span {@link #setExternalTransfer(boolean)} with
     * a false argument must be inert, {@link #recvBit()} must return {@code -1}, and
     * {@link #isSerialInputHigh()} must not change. Stateful endpoints advance their private
     * clocks through {@link #tickPerformanceQuietSpanTrusted(int)}; inert endpoints may retain
     * the default no-op implementation. PERFORMANCE uses this capability instead of assuming
     * that an endpoint's default methods are inert; external endpoints must opt in explicitly.
     * Endpoint topology is configured before installation, or by the Gameboy owner thread
     * between ticks, and must not change concurrently with this query or the admitted span.
     */
    default int performanceQuietSpanLimit(int requested) {
        return 0;
    }

    /**
     * Additional opt-in for omitting {@link #setExternalTransfer(boolean)} with a true argument
     * while the Game Boy waits for an external serial clock. PERFORMANCE combines this with
     * {@link #performanceQuietSpanLimit(int)}, so the ordinary quiet-endpoint guarantees still
     * apply. Endpoints which observe active external-clock waits must retain the default zero.
     */
    default int performanceExternalClockWaitSpanLimit(int requested) {
        return 0;
    }

    /**
     * Endpoint master-clock horizon while the Game Boy supplies the serial clock. The port keeps
     * every falling-bit exchange scalar, so this capability only omits {@link #tick()} calls
     * between those edges. Unlike the external-clock contract, it makes no promise about
     * {@link #recvBit()}, which is not called in this state. The input pin must remain unchanged
     * and no callback may become due inside the span. Unknown endpoints stay scalar; endpoints
     * already advertising an ordinary quiet horizon satisfy this narrower contract too.
     */
    default int performanceInternalClockSpanLimit(int requested) {
        return performanceQuietSpanLimit(requested);
    }

    /**
     * Stable serial-input pin horizon for RP bit 4. SerialPort independently bounds and advances
     * the endpoint's clock; this read-only capability promises that the exposed pin does not
     * change in the interval. It does not permit omitting recvBit or other serial callbacks.
     */
    default int performanceInputPinSpanLimit(int requested) {
        return performanceQuietSpanLimit(requested);
    }

    /** Advances a preflighted endpoint interval between Game Boy supplied clock edges. */
    default void tickPerformanceInternalClockSpanTrusted(int ticks) {
        tickPerformanceQuietSpanTrusted(ticks);
    }

    /** Returns whether the endpoint is quiet for the requested PERFORMANCE span. */
    default boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    /** Advances an endpoint span after the caller has established its quiet horizon. */
    default boolean tickPerformanceQuietSpan(int ticks) {
        return canTickPerformanceQuietSpan(ticks);
    }

    /** Applies a span after the caller has passed {@link #performanceQuietSpanLimit(int)}. */
    default void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        if (!tickPerformanceQuietSpan(ticks)) {
            throw new IllegalStateException("Serial-endpoint quiet span is not eligible: " + ticks);
        }
    }

    /**
     * Releases deterministic ownership held by this endpoint before it is detached or discarded.
     *
     * <p>Most synchronous peripherals own no external lifecycle and therefore need no action. An
     * endpoint with queued protocol work must override this method and make repeated calls safe.
     */
    default void disconnect() {
    }

    /** Advances external-device wall-clock state by one Game Boy master tick. */
    default void tick() {
    }

    /**
     * Returns the electrical level on the CGB link port's serial-input pin.
     *
     * <p>The CGB also exposes this pin through the undocumented bit 4 of RP (FF56),
     * which software UARTs such as GPS Boy use without arming a hardware transfer.
     */
    default boolean isSerialInputHigh() {
        return true;
    }

    /**
     * Listener waiting for any updates of the SB byte, so it can be shared with the other side.
     */
    void setSb(int sb);

    /**
     * Returns the bit transferred from the active side or -1 if no bit has been received.
     */
    int recvBit();

    /**
     * Notifies the endpoint whether the Game Boy currently has an external-clock transfer
     * armed (SC bit 7). An external device that drives its own byte framing (the Barcode
     * Boy) uses this to align its bytes to the Game Boy's transfers; most endpoints ignore
     * it.
     */
    default void setExternalTransfer(boolean inProgress) {
    }

    /**
     * Binds the owning serial port's external-clock exchange. In-process peer cables invoke this
     * function synchronously on the clock master's falling edge; it returns the current outgoing
     * data bit before shifting the supplied incoming bit. Device endpoints may ignore it.
     */
    default void setExternalClockReceiver(IntUnaryOperator receiver) {
    }

    /**
     * Returns the received byte.
     */
    default int recvByte() {
        throw new UnsupportedOperationException();
    }

    /**
     * Starts byte transfer, should reset the index of bit to send.
     */
    void startSending();

    /**
     * Sends following SB bit. Returns the received bit.
     */
    int sendBit();

    /**
     * Exchanges one bit while this Game Boy supplies the clock. Endpoints which do not model an
     * in-process peer retain the legacy {@link #sendBit()} contract.
     */
    default int exchangeBit(int outgoingBit) {
        return sendBit();
    }

    /**
     * Sends the SB bit and returns the received byte.
     */
    default int sendByte() {
        throw new UnsupportedOperationException();
    }

    SerialEndpoint NULL_ENDPOINT =
            new SerialEndpoint() {
                @Override
                public ComponentState<SerialEndpoint> captureState() {
                    return null;
                }

                @Override
                public void restoreState(ComponentState<SerialEndpoint> state) {
                }

                @Override
                public void setSb(int sb) {
                }

                @Override
                public int recvBit() {
                    return -1;
                }

                @Override
                public void startSending() {
                }

                @Override
                public int sendBit() {
                    return 1;
                }

                @Override
                public int performanceQuietSpanLimit(int requested) {
                    return requested > 0 ? requested : 0;
                }

                @Override
                public int performanceClockCapabilities() {
                    return PERFORMANCE_CLOCK_IDLE | PERFORMANCE_CLOCK_INTERNAL
                            | PERFORMANCE_CLOCK_EXTERNAL_WAIT;
                }

                @Override
                public int performanceExternalClockWaitSpanLimit(int requested) {
                    return requested > 0 ? requested : 0;
                }
            };
}
