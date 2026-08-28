package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public interface SerialEndpoint extends StatefulComponent<SerialEndpoint> {

    /** Stable zero-based player index for an in-process link, or {@code -1}. */
    default int linkPlayerIndex() {
        return -1;
    }

    /** Enables a narrowly detected cartridge compatibility profile for this endpoint. */
    default void enableCompatibilityProfile(SerialCompatibilityProfile profile) {
    }

    /**
     * Returns the largest span for which omitting this endpoint's master-clock callback is exact.
     * During the admitted span {@link #tick()} and {@link #setExternalTransfer(boolean)} with a
     * false argument must be inert, {@link #recvBit()} must return {@code -1}, and
     * {@link #isSerialInputHigh()} must remain true. PERFORMANCE uses this capability instead of
     * assuming that an endpoint's default methods are inert; external endpoints must opt in
     * explicitly. Endpoint topology is configured before installation, or by the Gameboy owner
     * thread between ticks, and must not change concurrently with this query or the admitted span.
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

    /** Returns whether the endpoint is quiet for the requested PERFORMANCE span. */
    default boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
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
                public int performanceExternalClockWaitSpanLimit(int requested) {
                    return requested > 0 ? requested : 0;
                }
            };
}
