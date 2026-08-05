package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class DmgPixelFifo implements PixelFifo, StatefulComponent<DmgPixelFifo> {

    private final IntQueue pixels = new IntQueue(16);

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Display display;

    private final boolean renderOutput;

    private final Lcdc lcdc;

    private final GpuRegisterValues registers;

    private final VRamTransfer vRamTransfer;

    public DmgPixelFifo(Display display, Lcdc lcdc, GpuRegisterValues registers, VRamTransfer vRamTransfer) {
        this(display, lcdc, registers, vRamTransfer, true);
    }

    public DmgPixelFifo(Display display, Lcdc lcdc, GpuRegisterValues registers,
                        VRamTransfer vRamTransfer, boolean renderOutput) {
        this.display = display;
        this.renderOutput = renderOutput;
        this.lcdc = lcdc;
        this.registers = registers;
        this.vRamTransfer = vRamTransfer;
    }

    @Override
    public int getLength() {
        return pixels.size();
    }

    // The DMG applies the palettes, the LCDC enable bits and the object/background
    // muxing at the LCD interface, OUTPUT_DELAY dots after the pixel leaves the FIFO.
    // Popped pixels travel through this delay line as raw color indices and are
    // resolved against the *current* register values when they reach the screen
    // (mealybug m3_bgp_change and friends pin this to the dot).
    static final int OUTPUT_DELAY = 3;

    private final int[] delayEntry = new int[8];

    private final long[] delayStamp = new long[8];

    private int delayHead, delaySize;

    private long outputTicks;

    @Override
    public void putInsertedPixel() {
        linePixels++;
        // the synthetic blank replaces only the BACKGROUND pixel; the object FIFO still
        // pops, so a sprite covering the inserted dot draws over it (SameBoy's
        // insert_bg_pixel path; m3_wx_4_change_sprites)
        spriteFifo.pop();
        int entry = (spriteFifo.poppedPixel << 2)
                | (spriteFifo.poppedPalette << 4)
                | (spriteFifo.poppedBgPriority ? 1 << 5 : 0);
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        int entry = popEntry();
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    // pack bg raw (2b), sprite raw (2b), sprite palette (1b), sprite priority (1b)
    private int popEntry() {
        int bgRaw = pixels.dequeue();
        spriteFifo.pop();
        return bgRaw
                | (spriteFifo.poppedPixel << 2)
                | (spriteFifo.poppedPalette << 4)
                | (spriteFifo.poppedBgPriority ? 1 << 5 : 0);
    }

    // test helper: pop and resolve in one step (production defers by OUTPUT_DELAY)
    int dequeuePixel() {
        return resolvePixel(popEntry());
    }

    // The line's FIRST pixel resolves its LCDC muxing one dot later than the mid-line
    // pixels OUTPUT_DELAY is calibrated to, while its palettes resolve at the normal dot
    // (m3_lcdc_bg_en_change / m3_lcdc_obj_en_change at x=0; hardware photos).
    private int outCount;

    private int firstEntry = -1;

    private int firstBgp, firstObp0, firstObp1;

    @Override
    public void outputTick() {
        outputTicks++;
        if (firstEntry >= 0) {
            // second phase of the first pixel: mux with the current LCDC, palettes from
            // the previous tick
            if (renderOutput) {
                display.putDmgPixel(resolveSplit(firstEntry, firstBgp, firstObp0, firstObp1));
            }
            firstEntry = -1;
        }
        while (delaySize > 0 && delayStamp[delayHead] + OUTPUT_DELAY <= outputTicks) {
            int entry = delayEntry[delayHead];
            delayHead = (delayHead + 1) & 7;
            delaySize--;
            if (outCount == 0) {
                outCount++;
                firstEntry = entry;
                firstBgp = registers.getEffective(GpuRegister.BGP);
                firstObp0 = registers.getEffective(GpuRegister.OBP0);
                firstObp1 = registers.getEffective(GpuRegister.OBP1);
                // hold emission for one tick; anything else due this tick waits behind it
                break;
            }
            outCount++;
            if (renderOutput) {
                display.putDmgPixel(resolvePixel(entry));
            }
        }
    }

    private int resolveSplit(int entry, int bgp, int obp0, int obp1) {
        int bgRaw = entry & 0b11;
        if (!lcdc.isBgAndWindowDisplayEffective()) {
            bgRaw = 0;
        }
        int spritePixel = (entry >> 2) & 0b11;
        boolean spriteBgPriority = (entry & (1 << 5)) != 0;

        int raw;
        int palette;
        if (spritePixel != 0
                && lcdc.isObjDisplayEffective()
                && !(spriteBgPriority && bgRaw != 0)) {
            raw = spritePixel;
            palette = ((entry >> 4) & 1) == 0 ? obp0 : obp1;
        } else {
            raw = bgRaw;
            palette = bgp;
        }
        if (vRamTransfer != null) {
            vRamTransfer.putPixel(raw);
        }
        return getColor(palette, raw);
    }



    private int resolvePixel(int entry) {
        int bgRaw = entry & 0b11;
        if (!lcdc.isBgAndWindowDisplayEffective()) {
            bgRaw = 0;
        }
        int spritePixel = (entry >> 2) & 0b11;
        boolean spriteBgPriority = (entry & (1 << 5)) != 0;

        int raw;
        int palette;
        if (spritePixel != 0
                && lcdc.isObjDisplayEffective()
                && !(spriteBgPriority && bgRaw != 0)) {
            raw = spritePixel;
            palette = registers.getEffective(((entry >> 4) & 1) == 0 ? GpuRegister.OBP0 : GpuRegister.OBP1);
        } else {
            raw = bgRaw;
            palette = registers.getEffective(GpuRegister.BGP);
        }
        if (vRamTransfer != null) {
            vRamTransfer.putPixel(raw);
        }
        return getColor(palette, raw);
    }

    // pixels of the current line popped towards the LCD (SameBoy's lcd_x); the
    // window-activation desync only steps back when the line has output something
    private int linePixels;

    @Override
    public void startLine() {
        linePixels = 0;
        outCount = 0;
        firstEntry = -1;
    }

    @Override
    public void rewindOnePixel() {
        if (linePixels == 0) {
            return;
        }
        linePixels--;
        // the rolled-back pixel popped the object FIFO too; un-pop it so an object merged
        // before a window activation stays aligned (mealybug left-clipped ® sprites)
        spriteFifo.rewind();
        if (delaySize > 0) {
            // the previous pixel is still in the output delay line: remove it, so the
            // next pixel takes its output slot
            delaySize--;
        } else {
            display.rewindPixel();
        }
    }

    @Override
    public void dropPixel() {
        pixels.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void enqueuePixel(int pixel) {
        pixels.enqueue(pixel);
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.enqueue(p);
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes flags, int oamIndex) {
        int paletteSelector = flags.getDmgPalette() == GpuRegister.OBP1 ? 1 : 0;
        // on the DMG the first fetched object wins, so overlaying fills transparent
        // pixels only (constant priority)
        spriteFifo.overlay(pixelLine, offset, paletteSelector, flags.isPriority(), 0);
    }

    /**
     * Re-resolves the pixels of the most recent 8-pixel push with fresh tile data: entries
     * still in the FIFO are rewritten in place, and entries already popped (at
     * {@code fromIndex-1-k} pops ago) are patched in the output delay line if they have
     * not reached the LCD (the window D1 refresh, m3_lcdc_tile_sel_win_change).
     */
    @Override
    public void refreshBgPixels(int[] oldLine, int[] newLine, int popped) {
        for (int i = popped; i < 8; i++) {
            int idx = i - popped;
            if (idx >= pixels.size()) {
                break;
            }
            if (pixels.get(idx) == oldLine[i]) {
                pixels.set(idx, newLine[i]);
            }
        }
        for (int i = 0; i < popped; i++) {
            int agesBack = popped - 1 - i;
            if (agesBack >= delaySize) {
                continue;
            }
            int idx = (delayHead + delaySize - 1 - agesBack) & 7;
            int entry = delayEntry[idx];
            if ((entry & 0b11) == oldLine[i]) {
                delayEntry[idx] = (entry & ~0b11) | newLine[i];
            }
        }
    }

    @Override
    public void refreshOverlay(int[] oldLine, int[] newLine, int fromIndex, TileAttributes flags) {
        int paletteSelector = flags.getDmgPalette() == GpuRegister.OBP1 ? 1 : 0;
        spriteFifo.refresh(fromIndex, oldLine, newLine, paletteSelector, flags.isPriority());
    }

    IntQueue getPixels() {
        return pixels;
    }

    private static int getColor(int palette, int colorIndex) {
        return 0b11 & (palette >> (colorIndex * 2));
    }

    @Override
    public void clear() {
        pixels.clear();
        spriteFifo.clear();
    }

    @Override
    public void clearBg() {
        // the window activation clears the background FIFO but keeps the object FIFO, so
        // objects already merged before the window triggered still show (SameBoy only
        // calls fifo_clear on bg_fifo; mealybug's ® sprite staircase clipped at the left
        // edge in m3_lcdc_win_map/tile_sel_win/obj_en_change_variant)
        pixels.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    @Override
    public ComponentState<DmgPixelFifo> captureState() {
        return new DmgPixelFifoState(pixels.captureState(), spriteFifo.captureState(),
                delayEntry.clone(), delayStamp.clone(), delayHead, delaySize, outputTicks);
    }

    @Override
    public ComponentState<DmgPixelFifo> captureState(MachineStateCapture capture) {
        return new DmgPixelFifoState(
                pixels.captureState(capture),
                spriteFifo.captureState(capture),
                capture.ints(delayEntry),
                capture.longs(delayStamp),
                delayHead,
                delaySize,
                outputTicks);
    }

    /**
     * Captures fields added after the pinned legacy Java-memento shape was established.
     *
     * <p>The first-pixel latch is deliberately outside {@link DmgPixelFifoState}: changing
     * that record would change its Java serialization descriptor and reject the supported
     * 1.7.13/1.7.14 fixtures. The Phase-1 detached machine seam owns this supplement for both
     * PPU dot machines.
     */
    public RuntimeState captureRuntimeState() {
        return new RuntimeState(
                linePixels, outCount, firstEntry, firstBgp, firstObp0, firstObp1);
    }

    public void validateRuntimeState(RuntimeState state) {
        if (state == null) {
            throw new IllegalArgumentException("DMG pixel FIFO runtime state is missing");
        }
        if (state.linePixels < 0 || state.linePixels > 160) {
            throw new IllegalArgumentException("DMG pixel FIFO line position is outside 0..160");
        }
        if (state.outCount < 0) {
            throw new IllegalArgumentException("DMG pixel FIFO output count cannot be negative");
        }
        if (state.firstEntry < -1 || state.firstEntry > 0x3f) {
            throw new IllegalArgumentException("Invalid pending DMG first-pixel entry");
        }
        // The latch is installed only for the first due output entry, which increments
        // outCount from zero to one. The following output tick clears firstEntry before it
        // considers another due entry, so firstEntry == -1 does not imply any outCount value.
        // outCount deliberately has no upper bound: it is bookkeeping, not an array cursor,
        // and window/timing rewinds do not establish a tighter invariant.
        if (state.firstEntry >= 0 && state.outCount != 1) {
            throw new IllegalArgumentException("Pending DMG first pixel requires output count 1");
        }
        if (!isByte(state.firstBgp) || !isByte(state.firstObp0) || !isByte(state.firstObp1)) {
            throw new IllegalArgumentException("Invalid pending DMG first-pixel palette");
        }
    }

    public void restoreRuntimeState(RuntimeState state) {
        validateRuntimeState(state);
        linePixels = state.linePixels;
        outCount = state.outCount;
        firstEntry = state.firstEntry;
        firstBgp = state.firstBgp;
        firstObp0 = state.firstObp0;
        firstObp1 = state.firstObp1;
    }

    private static boolean isByte(int value) {
        return value >= 0 && value <= 0xff;
    }

    @Override
    public void restoreState(ComponentState<DmgPixelFifo> state) {
        if (!(state instanceof DmgPixelFifoState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        pixels.restoreState(mem.pixels);
        spriteFifo.restoreState(mem.spriteFifo);
        // mementos serialized by older versions lack the delay-line fields
        if (mem.delayEntry != null && mem.delayStamp != null) {
            System.arraycopy(mem.delayEntry, 0, delayEntry, 0, delayEntry.length);
            System.arraycopy(mem.delayStamp, 0, delayStamp, 0, delayStamp.length);
            delayHead = mem.delayHead;
            delaySize = mem.delaySize;
            outputTicks = mem.outputTicks;
        } else {
            delayHead = 0;
            delaySize = 0;
        }
    }

    private record DmgPixelFifoState(ComponentState<IntQueue> pixels, ComponentState<SpriteFifo> spriteFifo,
                                       int[] delayEntry, long[] delayStamp, int delayHead,
                                       int delaySize, long outputTicks)
            implements ComponentState<DmgPixelFifo> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record DmgPixelFifoMemento(Memento<IntQueue> pixels, Memento<SpriteFifo> spriteFifo,
                                       int[] delayEntry, long[] delayStamp, int delayHead,
                                       int delaySize, long outputTicks)
            implements Memento<DmgPixelFifo> {
    }

    /** Service-free supplement retained by the immutable machine-state seam. */
    public record RuntimeState(
            int linePixels,
            int outCount,
            int firstEntry,
            int firstBgp,
            int firstObp0,
            int firstObp1) {
    }
}
