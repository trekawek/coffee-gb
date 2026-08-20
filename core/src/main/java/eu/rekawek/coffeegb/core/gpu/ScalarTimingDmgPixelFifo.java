package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;

/**
 * Timing-only DMG FIFO used by the unshifted mode-3 skeleton.
 *
 * <p>No pixel, palette, or display payload is retained here.  The queue and object occupancy,
 * output-delay timestamps, first-pixel latch, and line cursor are nevertheless kept exactly so
 * that fetcher-visible behavior and STAT timing are unchanged.  The shifted output machine
 * always uses {@link DmgPixelFifo}.</p>
 */
public final class ScalarTimingDmgPixelFifo implements PixelFifo {

    public static final int OUTPUT_DELAY = DmgPixelFifo.OUTPUT_DELAY;

    private final ScalarTimingFifoSupport.Queue pixels = new ScalarTimingFifoSupport.Queue();

    private final ScalarTimingFifoSupport.Sprite spriteFifo = new ScalarTimingFifoSupport.Sprite();

    private final long[] delayStamp = new long[8];

    private int delayHead;

    private int delaySize;

    private long outputTicks;

    private int linePixels;

    private int outCount;

    private boolean firstEntryPresent;

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        popBackgroundAndSprite();
        enqueueDelay();
    }

    @Override
    public void putInsertedPixel() {
        linePixels++;
        // The insertion glitch supplies a blank background pixel but still pops the object
        // FIFO; only that structural pop matters to this machine.
        spriteFifo.pop();
        enqueueDelay();
    }

    @Override
    public void outputTick() {
        outputTicks++;
        if (delaySize == 0 && !firstEntryPresent) {
            return;
        }
        if (firstEntryPresent) {
            firstEntryPresent = false;
        }
        while (isDue()) {
            popDelay();
            if (outCount == 0) {
                outCount++;
                // The pixel payload is deliberately discarded; only this one-tick latch is
                // observable by the timing skeleton.
                firstEntryPresent = true;
                break;
            }
            outCount++;
        }
    }

    @Override
    public void rewindOnePixel() {
        if (linePixels == 0) {
            return;
        }
        linePixels--;
        spriteFifo.rewind();
        if (delaySize > 0) {
            delaySize--;
        }
    }

    @Override
    public void startLine() {
        linePixels = 0;
        outCount = 0;
        firstEntryPresent = false;
    }

    @Override
    public void dropPixel() {
        pixels.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        pixels.enqueue8();
    }

    @Override
    public void enqueuePixel(int pixel) {
        pixels.enqueue();
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        spriteFifo.overlay();
    }

    @Override
    public void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
        // Background refresh changes payload only.
    }

    @Override
    public void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
        spriteFifo.refresh();
    }

    /** Test/debug helper mirroring the production FIFO's structural pop. */
    public int dequeuePixel() {
        popBackgroundAndSprite();
        return 0;
    }

    @Override
    public void clear() {
        pixels.clear();
        spriteFifo.clear();
    }

    @Override
    public void clearBg() {
        pixels.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    @Override
    public void resetForMissingState() {
        clear();
        java.util.Arrays.fill(delayStamp, 0L);
        delayHead = 0;
        delaySize = 0;
        outputTicks = 0;
        linePixels = 0;
        outCount = 0;
        firstEntryPresent = false;
    }

    /** Captures all scalar state; the returned array is detached from the live FIFO. */
    public State captureTimingState() {
        ScalarTimingFifoSupport.Sprite.State sprite = spriteFifo.captureState();
        return new State(
                pixels.size(),
                sprite.size(),
                sprite.underflow(),
                delayStamp.clone(),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                outCount,
                firstEntryPresent);
    }

    /** State-component spelling used by the regular PixelTransfer/Gpu memento graph. */
    public State captureState() {
        return captureTimingState();
    }

    /** Builds the transient safe-point view without cloning the live delay-age ring. */
    public State captureState(MachineStateCapture capture) {
        ScalarTimingFifoSupport.Sprite.State sprite = spriteFifo.captureState();
        return new State(
                pixels.size(),
                sprite.size(),
                sprite.underflow(),
                capture.longs(delayStamp),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                outCount,
                firstEntryPresent);
    }

    public void restoreTimingState(State state) {
        validate(state);
        ScalarTimingFifoSupport.Queue.State pixelsState =
                new ScalarTimingFifoSupport.Queue.State(state.backgroundSize);
        ScalarTimingFifoSupport.Sprite.State spriteState =
                new ScalarTimingFifoSupport.Sprite.State(state.spriteSize, state.spriteUnderflow);
        pixels.restoreState(pixelsState);
        spriteFifo.restoreState(spriteState);
        System.arraycopy(state.delayStamp, 0, delayStamp, 0, delayStamp.length);
        delayHead = state.delayHead;
        delaySize = state.delaySize;
        outputTicks = state.outputTicks;
        linePixels = state.linePixels;
        outCount = state.outCount;
        firstEntryPresent = state.firstEntryPresent;
    }

    public void validate(State state) {
        if (state == null) {
            throw new IllegalArgumentException("DMG scalar FIFO state is missing");
        }
        if (state.backgroundSize < 0 || state.backgroundSize > ScalarTimingFifoSupport.BACKGROUND_CAPACITY) {
            throw new IllegalArgumentException("Invalid DMG scalar background occupancy");
        }
        if (state.spriteSize < 0 || state.spriteSize > ScalarTimingFifoSupport.SPRITE_CAPACITY
                || state.spriteUnderflow < 0) {
            throw new IllegalArgumentException("Invalid DMG scalar object occupancy");
        }
        if (state.delayStamp == null || state.delayStamp.length != delayStamp.length) {
            throw new IllegalArgumentException("DMG scalar delay capacity mismatch");
        }
        if (state.delayHead < 0 || state.delayHead >= delayStamp.length
                || state.delaySize < 0 || state.delaySize > delayStamp.length) {
            throw new IllegalArgumentException("Invalid DMG scalar delay cursor");
        }
        if (state.outputTicks < 0) {
            throw new IllegalArgumentException("DMG scalar output ticks cannot be negative");
        }
        for (long stamp : state.delayStamp) {
            if (stamp < 0 || stamp > state.outputTicks) {
                throw new IllegalArgumentException("DMG scalar delay age is outside the output clock");
            }
        }
        if (state.linePixels < 0 || state.linePixels > 160 || state.outCount < 0) {
            throw new IllegalArgumentException("Invalid DMG scalar output position");
        }
        if (state.firstEntryPresent && state.outCount != 1) {
            throw new IllegalArgumentException("DMG scalar first-pixel latch requires output count 1");
        }
    }

    public int getDelayHead() {
        return delayHead;
    }

    public int getDelaySize() {
        return delaySize;
    }

    public long getOutputTicks() {
        return outputTicks;
    }

    public int getLinePixels() {
        return linePixels;
    }

    public int getOutputCount() {
        return outCount;
    }

    public boolean hasFirstEntry() {
        return firstEntryPresent;
    }

    private void popBackgroundAndSprite() {
        pixels.dequeue();
        spriteFifo.pop();
    }

    private void enqueueDelay() {
        if (delaySize == delayStamp.length) {
            throw new IllegalStateException("Output delay is full");
        }
        int tail = (delayHead + delaySize) & 7;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    private boolean isDue() {
        return delaySize > 0 && delayStamp[delayHead] + OUTPUT_DELAY <= outputTicks;
    }

    private void popDelay() {
        delayHead = (delayHead + 1) & 7;
        delaySize--;
    }

    public record State(
            int backgroundSize,
            int spriteSize,
            int spriteUnderflow,
            long[] delayStamp,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels,
            int outCount,
            boolean firstEntryPresent)
            implements ComponentState<ScalarTimingDmgPixelFifo> {

        /** Keeps the memento graph detached without adding work to the live tick path. */
        @Override
        public long[] delayStamp() {
            return delayStamp.clone();
        }

    }
}
