package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class ColorPixelFifo implements PixelFifo, Serializable, Originator<ColorPixelFifo> {

    // Like on the DMG, the CGB resolves pixels at the LCD interface, a few dots after the
    // FIFO pop. The CGB palette writes settle one T-cycle earlier than the DMG ones and
    // don't produce the old|new mix (SameBoy's PALETTE_CGB vs PALETTE_DMG conflicts), which
    // in this model corresponds to a one-dot shorter delay line with live register reads.
    static final int OUTPUT_DELAY = 2;

    private final IntQueue pixels = new IntQueue(16);

    private final IntQueue palettes = new IntQueue(16);

    private final IntQueue priorities = new IntQueue(16); // bg attribute priority flag

    private final SpriteFifo spriteFifo = new SpriteFifo();

    private final Lcdc lcdc;

    private final Display display;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final GpuRegisterValues r;

    private final SpeedMode speedMode;

    private final int[] delayEntry = new int[8];

    private final long[] delayStamp = new long[8];

    private int delayHead, delaySize;

    private long outputTicks;

    public ColorPixelFifo(
            Display display, Lcdc lcdc, ColorPalette bgPalette, ColorPalette oamPalette,
            GpuRegisterValues r, SpeedMode speedMode) {
        this.display = display;
        this.lcdc = lcdc;
        this.bgPalette = bgPalette;
        this.oamPalette = oamPalette;
        this.r = r;
        this.speedMode = speedMode;
    }

    @Override
    public int getLength() {
        return pixels.size();
    }

    @Override
    public void putPixelToScreen() {
        int entry = popEntry();
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    // pack bg pixel (2b), bg palette (3b), bg priority (1b), sprite pixel (2b),
    // sprite palette (3b), sprite bg-priority (1b)
    private int popEntry() {
        int bgPixel = pixels.dequeue();
        int bgPaletteIndex = palettes.dequeue();
        int bgAttrPriority = priorities.dequeue();
        spriteFifo.pop();
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
            display.putColorPixel(resolvePixel(entry));
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
        boolean compatMode = speedMode != null && speedMode.isDmgCompat();
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
        pixels.dequeue();
        palettes.dequeue();
        priorities.dequeue();
        spriteFifo.pop();
    }

    @Override
    public void enqueue8Pixels(int[] pixelLine, TileAttributes tileAttributes) {
        for (int p : pixelLine) {
            pixels.enqueue(p);
            palettes.enqueue(tileAttributes.getColorPaletteIndex());
            priorities.enqueue(tileAttributes.isPriority() ? 1 : 0);
        }
    }

    @Override
    public void setOverlay(int[] pixelLine, int offset, TileAttributes spriteAttr, int oamIndex) {
        boolean compat = speedMode != null && speedMode.isDmgCompat();
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
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    @Override
    public Memento<ColorPixelFifo> saveToMemento() {
        return new ColorPixelFifoMemento(
                pixels.saveToMemento(),
                palettes.saveToMemento(),
                priorities.saveToMemento(),
                spriteFifo.saveToMemento(),
                delayEntry.clone(),
                delayStamp.clone(),
                delayHead,
                delaySize,
                outputTicks);
    }

    @Override
    public void restoreFromMemento(Memento<ColorPixelFifo> memento) {
        if (!(memento instanceof ColorPixelFifoMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        pixels.restoreFromMemento(mem.pixels);
        palettes.restoreFromMemento(mem.palettes);
        priorities.restoreFromMemento(mem.priorities);
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

    private record ColorPixelFifoMemento(
            Memento<IntQueue> pixels,
            Memento<IntQueue> palettes,
            Memento<IntQueue> priorities,
            Memento<SpriteFifo> spriteFifo,
            int[] delayEntry,
            long[] delayStamp,
            int delayHead,
            int delaySize,
            long outputTicks)
            implements Memento<ColorPixelFifo> {
    }
}
