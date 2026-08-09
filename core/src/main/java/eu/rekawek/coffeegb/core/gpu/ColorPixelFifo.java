package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class ColorPixelFifo implements PixelFifo, StatefulComponent<ColorPixelFifo> {

    // Like on the DMG, the CGB resolves pixels at the LCD interface after the FIFO pop.
    // CGB palette writes do not produce the DMG's old|new mix, and Daid's scanline palette
    // capture pins the CGB sample two dots ahead of the DMG one. With the pixel machine's
    // four-dot entry skew, that leaves one dot in this final output stage.
    static final int OUTPUT_DELAY = 1;

    private final IntQueue pixels = new IntQueue(16);

    private final IntQueue palettes = new IntQueue(16);

    private final IntQueue priorities = new IntQueue(16); // bg attribute priority flag

    // StartWindowDraw keeps the old background shift register beside the fresh window
    // fetch. On CGB, disabling LCDC.5 during its six startup states plots these pixels.
    private final IntQueue clearedPixels = new IntQueue(16);

    private final IntQueue clearedPalettes = new IntQueue(16);

    private final IntQueue clearedPriorities = new IntQueue(16);

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Lcdc lcdc;

    private final Display display;

    // The timing-only PPU machine advances the LCD delay line without resolving
    // pixels into its throwaway Display.
    private boolean renderOutput = true;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final GpuRegisterValues r;

    private boolean dmgCompatValue;

    private final int[] delayEntry = new int[8];

    private final long[] delayStamp = new long[8];

    private int delayHead, delaySize;

    private long outputTicks;

    // pixels of the current line popped towards the LCD, for the window-activation rewind
    // bookkeeping (mirrors DmgPixelFifo); reset at startLine
    private int linePixels;

    public ColorPixelFifo(
            Display display, Lcdc lcdc, ColorPalette bgPalette, ColorPalette oamPalette,
            GpuRegisterValues r, SpeedMode speedMode) {
        this.display = display;
        this.lcdc = lcdc;
        this.bgPalette = bgPalette;
        this.oamPalette = oamPalette;
        this.r = r;
        this.dmgCompatValue = speedMode != null && speedMode.isDmgCompat();
    }

    public void setDmgCompat(boolean dmgCompatValue) {
        this.dmgCompatValue = dmgCompatValue;
    }

    public void setRenderOutput(boolean renderOutput) {
        this.renderOutput = renderOutput;
    }

    @Override
    public int getLength() {
        return pixels.size;
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        int entry = popEntry(pixels, palettes, priorities);
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    @Override
    public void startLine() {
        linePixels = 0;
    }

    @Override
    public void rewindOnePixel() {
        // the CGB window-activation rollback (a pending WX match whose position advanced)
        // steps the LCD x back one pixel: without this the popped pixel stays in the output
        // and every window line drifts right by one, shearing a full-screen window into a
        // diagonal (issue #80). Mirrors DmgPixelFifo.
        if (linePixels == 0) {
            return;
        }
        linePixels--;
        spriteFifo.rewind();
        if (delaySize > 0) {
            delaySize--;
        } else if (renderOutput) {
            display.rewindPixel();
        }
    }

    // pack bg pixel (2b), bg palette (3b), bg priority (1b), sprite pixel (2b),
    // sprite palette (3b), sprite bg-priority (1b)
    private int popEntry(IntQueue bgPixels, IntQueue bgPalettes, IntQueue bgPriorities) {
        int bgPixel = bgPixels.array[bgPixels.offset++];
        if (bgPixels.offset == bgPixels.array.length) {
            bgPixels.offset = 0;
        }
        bgPixels.size--;
        int bgPaletteIndex = bgPalettes.array[bgPalettes.offset++];
        if (bgPalettes.offset == bgPalettes.array.length) {
            bgPalettes.offset = 0;
        }
        bgPalettes.size--;
        int bgAttrPriority = bgPriorities.array[bgPriorities.offset++];
        if (bgPriorities.offset == bgPriorities.array.length) {
            bgPriorities.offset = 0;
        }
        bgPriorities.size--;
        if (spriteFifo.size == 0) {
            spriteFifo.poppedPixel = 0;
            spriteFifo.poppedPalette = 0;
            spriteFifo.poppedBgPriority = false;
            spriteFifo.underflow++;
        } else {
            spriteFifo.poppedPixel = spriteFifo.pixel[spriteFifo.head];
            spriteFifo.poppedPalette = spriteFifo.palette[spriteFifo.head];
            spriteFifo.poppedBgPriority = spriteFifo.bgPriority[spriteFifo.head];
            spriteFifo.head = (spriteFifo.head + 1) & 7;
            spriteFifo.size--;
        }
        return bgPixel
                | (bgPaletteIndex << 2)
                | (bgAttrPriority << 5)
                | (spriteFifo.poppedPixel << 6)
                | (spriteFifo.poppedPalette << 8)
                | (spriteFifo.poppedBgPriority ? 1 << 11 : 0);
    }

    @Override
    public void outputTick() {
        outputTicks++;
        while (delaySize > 0 && delayStamp[delayHead] + OUTPUT_DELAY <= outputTicks) {
            int entry = delayEntry[delayHead];
            delayHead = (delayHead + 1) & 7;
            delaySize--;
            if (renderOutput) {
                display.putColorPixel(resolvePixel(entry));
            }
        }
    }

    private int resolvePixel(int entry) {
        int bgPixel = entry & 0b11;
        int bgPaletteIndex = (entry >> 2) & 0b111;
        boolean bgAttrPriority = (entry & (1 << 5)) != 0;
        int spritePixel = (entry >> 6) & 0b11;
        int spritePalette = (entry >> 8) & 0b111;
        boolean spriteBgPriority = (entry & (1 << 11)) != 0;

        // in DMG compatibility mode LCDC.0 blanks the background like on the DMG;
        // in CGB mode it only drops the background's priority
        boolean compatMode = dmgCompatValue;
        if (compatMode && !lcdc.isBgAndWindowDisplay()) {
            bgPixel = 0;
            bgAttrPriority = false;
        }

        boolean drawSprite = false;
        if (spritePixel != 0 && lcdc.isObjDisplay()) {
            if (!lcdc.isBgAndWindowDisplay() && !compatMode) {
                // "master priority": sprites always on top
                drawSprite = true;
            } else if (bgAttrPriority) {
                drawSprite = bgPixel == 0;
            } else if (spriteBgPriority) {
                drawSprite = bgPixel == 0;
            } else {
                drawSprite = true;
            }
        }
        // in DMG compatibility mode the BGP/OBPx registers remap the color index before
        // the palette RAM lookup (the boot ROM loads the compatibility colors there)
        if (drawSprite) {
            int pixel = spritePixel;
            if (compatMode) {
                int obp = r.get(spritePalette == 0 ? GpuRegister.OBP0 : GpuRegister.OBP1);
                pixel = (obp >> (pixel * 2)) & 0b11;
            }
            return oamPalette.getPalette(spritePalette)[pixel];
        } else {
            int pixel = bgPixel;
            if (compatMode) {
                pixel = (r.get(GpuRegister.BGP) >> (pixel * 2)) & 0b11;
            }
            return bgPalette.getPalette(bgPaletteIndex)[pixel];
        }
    }

    @Override
    public void dropPixel() {
        pixels.offset = pixels.offset + 1 == pixels.array.length ? 0 : pixels.offset + 1;
        pixels.size--;
        palettes.offset = palettes.offset + 1 == palettes.array.length ? 0 : palettes.offset + 1;
        palettes.size--;
        priorities.offset = priorities.offset + 1 == priorities.array.length ? 0 : priorities.offset + 1;
        priorities.size--;
        if (spriteFifo.size == 0) {
            spriteFifo.poppedPixel = 0;
            spriteFifo.poppedPalette = 0;
            spriteFifo.poppedBgPriority = false;
            spriteFifo.underflow++;
        } else {
            spriteFifo.poppedPixel = spriteFifo.pixel[spriteFifo.head];
            spriteFifo.poppedPalette = spriteFifo.palette[spriteFifo.head];
            spriteFifo.poppedBgPriority = spriteFifo.bgPriority[spriteFifo.head];
            spriteFifo.head = (spriteFifo.head + 1) & 7;
            spriteFifo.size--;
        }
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        int paletteIndex = tileAttributes.getColorPaletteIndex();
        int priority = tileAttributes.isPriority() ? 1 : 0;
        pixels.enqueue8(pixelLine);
        palettes.enqueue8(paletteIndex);
        priorities.enqueue8(priority);
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes spriteAttr, int oamIndex) {
        boolean compat = dmgCompatValue;
        int paletteIndex = compat
                ? (spriteAttr.getDmgPalette() == GpuRegister.OBP1 ? 1 : 0)
                : spriteAttr.getColorPaletteIndex();
        spriteFifo.overlay(
                pixelLine,
                offset,
                paletteIndex,
                spriteAttr.isPriority(),
                oamIndex);
    }

    @Override
    public void clear() {
        pixels.clear();
        palettes.clear();
        priorities.clear();
        spriteFifo.clear();
        discardClearedBg();
    }

    @Override
    public void clearBg() {
        discardClearedBg();
        pixels.copyTo(clearedPixels);
        palettes.copyTo(clearedPalettes);
        priorities.copyTo(clearedPriorities);
        pixels.clear();
        palettes.clear();
        priorities.clear();
    }

    @Override
    public int getClearedBgLength() {
        return clearedPixels.size;
    }

    @Override
    public void putClearedBgToScreen() {
        linePixels++;
        int entry = popEntry(clearedPixels, clearedPalettes, clearedPriorities);
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    @Override
    public void dropClearedBgPixel() {
        clearedPixels.offset = clearedPixels.offset + 1 == clearedPixels.array.length
                ? 0 : clearedPixels.offset + 1;
        clearedPixels.size--;
        clearedPalettes.offset = clearedPalettes.offset + 1 == clearedPalettes.array.length
                ? 0 : clearedPalettes.offset + 1;
        clearedPalettes.size--;
        clearedPriorities.offset = clearedPriorities.offset + 1 == clearedPriorities.array.length
                ? 0 : clearedPriorities.offset + 1;
        clearedPriorities.size--;
        if (spriteFifo.size == 0) {
            spriteFifo.poppedPixel = 0;
            spriteFifo.poppedPalette = 0;
            spriteFifo.poppedBgPriority = false;
            spriteFifo.underflow++;
        } else {
            spriteFifo.poppedPixel = spriteFifo.pixel[spriteFifo.head];
            spriteFifo.poppedPalette = spriteFifo.palette[spriteFifo.head];
            spriteFifo.poppedBgPriority = spriteFifo.bgPriority[spriteFifo.head];
            spriteFifo.head = (spriteFifo.head + 1) & 7;
            spriteFifo.size--;
        }
    }

    @Override
    public void discardClearedBg() {
        clearedPixels.clear();
        clearedPalettes.clear();
        clearedPriorities.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    @Override
    public ComponentState<ColorPixelFifo> captureState() {
        return new ColorPixelFifoState(
                pixels.captureState(),
                palettes.captureState(),
                priorities.captureState(),
                spriteFifo.captureState(),
                delayEntry.clone(),
                delayStamp.clone(),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                clearedPixels.captureState(),
                clearedPalettes.captureState(),
                clearedPriorities.captureState());
    }

    @Override
    public ComponentState<ColorPixelFifo> captureState(MachineStateCapture capture) {
        return new ColorPixelFifoState(
                pixels.captureState(capture),
                palettes.captureState(capture),
                priorities.captureState(capture),
                spriteFifo.captureState(capture),
                capture.ints(delayEntry),
                capture.longs(delayStamp),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                clearedPixels.captureState(capture),
                clearedPalettes.captureState(capture),
                clearedPriorities.captureState(capture));
    }

    @Override
    public void restoreState(ComponentState<ColorPixelFifo> state) {
        if (!(state instanceof ColorPixelFifoState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        pixels.restoreState(mem.pixels);
        palettes.restoreState(mem.palettes);
        priorities.restoreState(mem.priorities);
        spriteFifo.restoreState(mem.spriteFifo);
        if (mem.clearedPixels != null
                && mem.clearedPalettes != null
                && mem.clearedPriorities != null) {
            clearedPixels.restoreState(mem.clearedPixels);
            clearedPalettes.restoreState(mem.clearedPalettes);
            clearedPriorities.restoreState(mem.clearedPriorities);
        } else {
            discardClearedBg();
        }
        // mementos serialized by older versions lack the delay-line fields
        if (mem.delayEntry != null && mem.delayStamp != null) {
            System.arraycopy(mem.delayEntry, 0, delayEntry, 0, delayEntry.length);
            System.arraycopy(mem.delayStamp, 0, delayStamp, 0, delayStamp.length);
            delayHead = mem.delayHead;
            delaySize = mem.delaySize;
            outputTicks = mem.outputTicks;
            linePixels = mem.linePixels;
        } else {
            delayHead = 0;
            delaySize = 0;
        }
    }

    private record ColorPixelFifoState(
            ComponentState<IntQueue> pixels,
            ComponentState<IntQueue> palettes,
            ComponentState<IntQueue> priorities,
            ComponentState<SpriteFifo> spriteFifo,
            int[] delayEntry,
            long[] delayStamp,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels,
            ComponentState<IntQueue> clearedPixels,
            ComponentState<IntQueue> clearedPalettes,
            ComponentState<IntQueue> clearedPriorities)
            implements ComponentState<ColorPixelFifo> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record ColorPixelFifoMemento(
            Memento<IntQueue> pixels,
            Memento<IntQueue> palettes,
            Memento<IntQueue> priorities,
            Memento<SpriteFifo> spriteFifo,
            int[] delayEntry,
            long[] delayStamp,
            int delayHead,
            int delaySize,
            long outputTicks,
            int linePixels,
            Memento<IntQueue> clearedPixels,
            Memento<IntQueue> clearedPalettes,
            Memento<IntQueue> clearedPriorities)
            implements Memento<ColorPixelFifo> {
    }
}
