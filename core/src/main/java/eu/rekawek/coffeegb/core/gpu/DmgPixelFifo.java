package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class DmgPixelFifo implements PixelFifo, Serializable, Originator<DmgPixelFifo> {

    private final IntQueue pixels = new IntQueue(16);

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Display display;

    private final Lcdc lcdc;

    private final GpuRegisterValues registers;

    private final VRamTransfer vRamTransfer;

    public DmgPixelFifo(Display display, Lcdc lcdc, GpuRegisterValues registers, VRamTransfer vRamTransfer) {
        this.display = display;
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
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = 0;
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

    @Override
    public void outputTick() {
        outputTicks++;
        while (delaySize > 0 && delayStamp[delayHead] + OUTPUT_DELAY <= outputTicks) {
            int entry = delayEntry[delayHead];
            delayHead = (delayHead + 1) & 7;
            delaySize--;
            display.putDmgPixel(resolvePixel(entry));
        }
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
    public Memento<DmgPixelFifo> saveToMemento() {
        return new DmgPixelFifoMemento(pixels.saveToMemento(), spriteFifo.saveToMemento(),
                delayEntry.clone(), delayStamp.clone(), delayHead, delaySize, outputTicks);
    }

    @Override
    public void restoreFromMemento(Memento<DmgPixelFifo> memento) {
        if (!(memento instanceof DmgPixelFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        pixels.restoreFromMemento(mem.pixels);
        spriteFifo.restoreFromMemento(mem.spriteFifo);
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

    private record DmgPixelFifoMemento(Memento<IntQueue> pixels, Memento<SpriteFifo> spriteFifo,
                                       int[] delayEntry, long[] delayStamp, int delayHead,
                                       int delaySize, long outputTicks)
            implements Memento<DmgPixelFifo> {
    }
}
