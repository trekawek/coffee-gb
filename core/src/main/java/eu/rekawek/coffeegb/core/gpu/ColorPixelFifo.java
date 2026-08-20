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

    // Low six bits match the delayed pixel layout: bg pixel (2b), palette (3b), priority (1b).
    private final IntQueue background = new IntQueue(16);

    // StartWindowDraw keeps the old background shift register beside the fresh window
    // fetch. On CGB, disabling LCDC.5 during its six startup states plots these pixels.
    private final IntQueue clearedBackground = new IntQueue(16);

    // The portable state layout predates the packed runtime representation. Materialize its
    // logical ring triples only while capturing or restoring a snapshot.
    private final IntQueue statePixels = new IntQueue(16);

    private final IntQueue statePalettes = new IntQueue(16);

    private final IntQueue statePriorities = new IntQueue(16);

    private final IntQueue stateClearedPixels = new IntQueue(16);

    private final IntQueue stateClearedPalettes = new IntQueue(16);

    private final IntQueue stateClearedPriorities = new IntQueue(16);

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
        return background.size;
    }

    @Override
    public void putPixelToScreen() {
        linePixels++;
        int entry = popEntry(background);
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
    private int popEntry(IntQueue background) {
        int entry = background.array[background.offset++];
        if (background.offset == background.array.length) {
            background.offset = 0;
        }
        background.size--;
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
        return entry
                | (spriteFifo.poppedPixel << 6)
                | (spriteFifo.poppedPalette << 8)
                | (spriteFifo.poppedBgPriority ? 1 << 11 : 0);
    }

    private static void dropEntry(IntQueue background) {
        background.offset = background.offset + 1 == background.array.length
                ? 0 : background.offset + 1;
        background.size--;
    }

    @Override
    public void outputTick() {
        outputTicks++;
        if (!renderOutput) {
            // The GPU advances output before the pixel machine can append this dot's
            // entry. With the one-dot CGB delay, every entry already present is due.
            delayHead = (delayHead + delaySize) & 7;
            delaySize = 0;
            return;
        }
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
        dropEntry(background);
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
        int attributeBits = (tileAttributes.getColorPaletteIndex() << 2)
                | (tileAttributes.isPriority() ? 1 << 5 : 0);
        background.enqueue8Packed(pixelLine, attributeBits);
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
        background.clear();
        spriteFifo.clear();
        discardClearedBg();
    }

    @Override
    public void clearBg() {
        discardClearedBg();
        background.copyTo(clearedBackground);
        background.clear();
    }

    @Override
    public int getClearedBgLength() {
        return clearedBackground.size;
    }

    @Override
    public void putClearedBgToScreen() {
        linePixels++;
        int entry = popEntry(clearedBackground);
        int tail = (delayHead + delaySize) & 7;
        delayEntry[tail] = entry;
        delayStamp[tail] = outputTicks;
        delaySize++;
    }

    @Override
    public void dropClearedBgPixel() {
        dropEntry(clearedBackground);
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
        clearedBackground.clear();
    }

    @Override
    public void clearOutput() {
        delaySize = 0;
    }

    @Override
    public void resetForMissingState() {
        background.clear();
        clearedBackground.clear();
        spriteFifo.clear();
        spriteFifo.poppedPixel = 0;
        spriteFifo.poppedPalette = 0;
        spriteFifo.poppedBgPriority = false;
        java.util.Arrays.fill(delayEntry, 0);
        java.util.Arrays.fill(delayStamp, 0L);
        delayHead = 0;
        delaySize = 0;
        outputTicks = 0;
        linePixels = 0;
    }

    @Override
    public ComponentState<ColorPixelFifo> captureState() {
        materializeStateQueues();
        return new ColorPixelFifoState(
                statePixels.captureState(),
                statePalettes.captureState(),
                statePriorities.captureState(),
                spriteFifo.captureState(),
                delayEntry.clone(),
                delayStamp.clone(),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                stateClearedPixels.captureState(),
                stateClearedPalettes.captureState(),
                stateClearedPriorities.captureState());
    }

    @Override
    public ComponentState<ColorPixelFifo> captureState(MachineStateCapture capture) {
        materializeStateQueues();
        return new ColorPixelFifoState(
                statePixels.captureState(capture),
                statePalettes.captureState(capture),
                statePriorities.captureState(capture),
                spriteFifo.captureState(capture),
                capture.ints(delayEntry),
                capture.longs(delayStamp),
                delayHead,
                delaySize,
                outputTicks,
                linePixels,
                stateClearedPixels.captureState(capture),
                stateClearedPalettes.captureState(capture),
                stateClearedPriorities.captureState(capture));
    }

    @Override
    public void restoreState(ComponentState<ColorPixelFifo> state) {
        ColorPixelFifoState mem = validatedState(state);
        statePixels.restoreState(mem.pixels);
        statePalettes.restoreState(mem.palettes);
        statePriorities.restoreState(mem.priorities);
        restorePackedQueue(statePixels, statePalettes, statePriorities, background);
        spriteFifo.restoreState(mem.spriteFifo);
        if (mem.clearedPixels != null
                && mem.clearedPalettes != null
                && mem.clearedPriorities != null) {
            stateClearedPixels.restoreState(mem.clearedPixels);
            stateClearedPalettes.restoreState(mem.clearedPalettes);
            stateClearedPriorities.restoreState(mem.clearedPriorities);
            restorePackedQueue(
                    stateClearedPixels,
                    stateClearedPalettes,
                    stateClearedPriorities,
                    clearedBackground);
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
            outputTicks = 0;
        }
    }

    /**
     * Validates a full CGB FIFO state without touching the live queues.  All three packed
     * background views must retain the same fixed queue shape and cursor, including the
     * optional retained-background queues used while a window fetch is in flight.
     */
    public static void validateState(ComponentState<?> state) {
        validatedState(state);
    }

    private static ColorPixelFifoState validatedState(ComponentState<?> state) {
        ColorPixelFifoState mem = validateStateAndReturn(state);
        IntQueue.IntQueueState pixels = IntQueue.validateState(mem.pixels, 16);
        IntQueue.IntQueueState palettes = IntQueue.validateState(mem.palettes, 16);
        IntQueue.IntQueueState priorities = IntQueue.validateState(mem.priorities, 16);
        validateAligned(pixels, palettes, priorities, "CGB background");
        SpriteFifo.validateState(mem.spriteFifo);

        boolean clearedAny = mem.clearedPixels != null
                || mem.clearedPalettes != null
                || mem.clearedPriorities != null;
        if (clearedAny) {
            if (mem.clearedPixels == null || mem.clearedPalettes == null
                    || mem.clearedPriorities == null) {
                throw new IllegalArgumentException("CGB cleared background state is incomplete");
            }
            IntQueue.IntQueueState clearedPixels = IntQueue.validateState(mem.clearedPixels, 16);
            IntQueue.IntQueueState clearedPalettes = IntQueue.validateState(mem.clearedPalettes, 16);
            IntQueue.IntQueueState clearedPriorities = IntQueue.validateState(mem.clearedPriorities, 16);
            validateAligned(clearedPixels, clearedPalettes, clearedPriorities,
                    "CGB cleared background");
        }

        if ((mem.delayEntry == null) != (mem.delayStamp == null)) {
            throw new IllegalArgumentException("CGB delay state arrays must be paired");
        }
        if (mem.delayEntry != null
                && (mem.delayEntry.length != 8 || mem.delayStamp.length != 8)) {
            throw new IllegalArgumentException("CGB delay state capacity doesn't match");
        }
        if (mem.delayHead < 0 || mem.delayHead >= 8
                || mem.delaySize < 0 || mem.delaySize > 8) {
            throw new IllegalArgumentException("Invalid CGB delay state cursor");
        }
        if (mem.outputTicks < 0) {
            throw new IllegalArgumentException("CGB output ticks cannot be negative");
        }
        if (mem.delayStamp != null) {
            for (long stamp : mem.delayStamp) {
                if (stamp < 0 || stamp > mem.outputTicks) {
                    throw new IllegalArgumentException("CGB delay age is outside output time");
                }
            }
        }
        if (mem.linePixels < 0 || mem.linePixels > 160) {
            throw new IllegalArgumentException("CGB line pixel count is outside 0..160");
        }
        return mem;
    }

    private static ColorPixelFifoState validateStateAndReturn(ComponentState<?> state) {
        if (!(state instanceof ColorPixelFifoState mem)) {
            throw new IllegalArgumentException("Invalid CGB pixel FIFO state type");
        }
        return mem;
    }

    private static void validateAligned(
            IntQueue.IntQueueState pixels,
            IntQueue.IntQueueState palettes,
            IntQueue.IntQueueState priorities,
            String label) {
        if (pixels.size() != palettes.size() || pixels.size() != priorities.size()
                || pixels.offset() != palettes.offset() || pixels.offset() != priorities.offset()) {
            throw new IllegalArgumentException(label + " queues are not aligned");
        }
    }

    private void materializeStateQueues() {
        splitPackedQueue(background, statePixels, statePalettes, statePriorities);
        splitPackedQueue(
                clearedBackground,
                stateClearedPixels,
                stateClearedPalettes,
                stateClearedPriorities);
    }

    private static void splitPackedQueue(
            IntQueue source, IntQueue pixels, IntQueue palettes, IntQueue priorities) {
        pixels.size = palettes.size = priorities.size = source.size;
        pixels.offset = palettes.offset = priorities.offset = source.offset;
        for (int i = 0; i < source.array.length; i++) {
            int entry = source.array[i];
            pixels.array[i] = entry & 0b11;
            palettes.array[i] = (entry >> 2) & 0b111;
            priorities.array[i] = (entry >> 5) & 1;
        }
    }

    private static void restorePackedQueue(
            IntQueue pixels, IntQueue palettes, IntQueue priorities, IntQueue target) {
        if (pixels.size != palettes.size || pixels.size != priorities.size
                || pixels.offset != palettes.offset || pixels.offset != priorities.offset) {
            throw new IllegalArgumentException("ColorPixelFifo state queues are not aligned");
        }
        target.size = pixels.size;
        target.offset = pixels.offset;
        for (int i = 0; i < target.array.length; i++) {
            target.array[i] = pixels.array[i]
                    | (palettes.array[i] << 2)
                    | (priorities.array[i] << 5);
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
