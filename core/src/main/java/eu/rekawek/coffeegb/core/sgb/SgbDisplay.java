package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.SynchronousBorrowedEvent;
import eu.rekawek.coffeegb.core.gpu.Display.DmgFrameReadyEvent;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.sgb.Commands.MaskEnCmd.GameboyScreenMask;

import java.util.Arrays;

import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent.translateGbcRgb;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SgbDisplay implements StatefulComponent<SgbDisplay> {

    /** Compatible supplement bit carried inside the existing StateFile-v1 borderFade scalar. */
    private static final int STATE_PALETTE_PRIORITY = 0x100;

    private static final int STATE_BORDER_FADE_MASK = 0xff;

    private static final int DMG_TILES_WIDTH = DISPLAY_WIDTH / 8;

    private static final int DMG_TILES_HEIGHT = DISPLAY_HEIGHT / 8;

    private static final int DMG_WINDOW_X = 48;

    private static final int DMG_WINDOW_Y = 40;

    private static final long INVALID_BORDER_GENERATION = Long.MIN_VALUE;

    private volatile boolean sgbBorder;

    private final boolean sgb;

    private final EventBus sgbBus;

    private EventBus eventBus;

    private final int[] predefinedPalette;

    private final int[] sgbBuffer = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    private final int[] sgbMask = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    /**
     * Private, canonical border/base images. These arrays are never published to subscribers;
     * every render lease copies its geometry's generation into its callback-scoped target before
     * repainting the Game Boy window.
     */
    private final int[] borderedBase;

    private final int[] centerBase;

    private final int[][] palettes = new int[4][4];

    private final int[][] systemPalettes = new int[512][4];

    private final int[] paletteMap = new int[DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private final int[][] attributeFiles = new int[45][DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private GameboyScreenMask screenMask = GameboyScreenMask.CANCEL;

    private int borderFade;

    /**
     * Border data is derived presentation state. A new generation is published only when a
     * committed background, an actually different fade, or a state restore changes its source.
     * Render leases materialize that generation lazily for each synchronous re-entrant depth.
     */
    private long borderGeneration = 1;

    private long borderedBaseGeneration = INVALID_BORDER_GENERATION;

    private long centerBaseGeneration = INVALID_BORDER_GENERATION;

    private final transient ThreadLocal<RenderLeasePool> renderLeasePool;

    /**
     * PAL_PRI controls whether later game palette commands reclaim a palette selected in the
     * SNES firmware UI. Coffee GB has no SNES palette-selection UI, so game palettes are always
     * active; retaining this bit is still required for deterministic state and future adapters.
     */
    private boolean palettePriority;

    public SgbDisplay(Rom rom, EventBus sgbBus, boolean sgb, boolean sgbBorder) {
        this.sgbBorder = sgbBorder;
        this.sgb = sgb;
        this.sgbBus = sgbBus;
        borderedBase = sgb ? new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT] : null;
        centerBase = sgb ? new int[DISPLAY_WIDTH * DISPLAY_HEIGHT] : null;
        renderLeasePool = sgb ? ThreadLocal.withInitial(RenderLeasePool::new) : null;
        predefinedPalette = DefinedPalettes.getPalette(rom.getTitle().trim());
    }

    public void init(EventBus eventBus) {
        this.palettes[0] = predefinedPalette;
        if (sgb) {
            this.eventBus = eventBus;
            eventBus.register(this::onSgbBackground, Background.SgbBackgroundReadyEvent.class);
            eventBus.register(this::onSgbBackgroundFade, Background.SgbBackgroundFadeEvent.class);
            eventBus.register(this::onDmgFrame, DmgFrameReadyEvent.class);
            eventBus.register(e -> this.sgbBorder = e.borderEnabled, SetSgbBorder.class);

            sgbBus.register(this::onAttrBlk, Commands.AttrBlkCmd.class);
            sgbBus.register(this::onAttrLin, Commands.AttrLinCmd.class);
            sgbBus.register(this::onAttrDiv, Commands.AttrDivCmd.class);
            sgbBus.register(e -> setPalettes(0, e.getPalette0(), 1, e.getPalette1()), Commands.Pal01Cmd.class);
            sgbBus.register(e -> setPalettes(0, e.getPalette0(), 3, e.getPalette3()), Commands.Pal03Cmd.class);
            sgbBus.register(e -> setPalettes(1, e.getPalette1(), 2, e.getPalette2()), Commands.Pal12Cmd.class);
            sgbBus.register(e -> setPalettes(2, e.getPalette2(), 3, e.getPalette3()), Commands.Pal23Cmd.class);
            sgbBus.register(this::onPalTransfer, Commands.PalTrnCmd.class);
            sgbBus.register(this::onPalSet, Commands.PalSetCmd.class);
            sgbBus.register(this::onAttrTransfer, Commands.AttrTrnCmd.class);
            sgbBus.register(this::onAttrSet, Commands.AttrSetCmd.class);
            sgbBus.register(this::onAttrChr, Commands.AttrChrCmd.class);
            sgbBus.register(e -> screenMask = e.getScreenMask(), Commands.MaskEnCmd.class);
            sgbBus.register(e -> palettePriority = e.getPriority(), Commands.PalPriCmd.class);
        }
    }

    private void setPalettes(int firstId, int[] first, int secondId, int[] second) {
        palettes[firstId] = first;
        palettes[secondId] = second;
        if (firstId != 0) {
            palettes[0] = palettes[0].clone();
        }
        palettes[0][0] = first[0];
    }

    private void onAttrTransfer(Commands.AttrTrnCmd attrTrnCmd) {
        int[][] updated = new int[attributeFiles.length][];
        for (int atfId = 0; atfId < 45; atfId++) {
            updated[atfId] = new int[DMG_TILES_WIDTH * DMG_TILES_HEIGHT];
            Commands.AttrTrnCmd.AttributeFile file = attrTrnCmd.getAttributeFile(atfId);
            for (int i = 0; i < DMG_TILES_WIDTH * DMG_TILES_HEIGHT; i++) {
                updated[atfId][i] = file.getColor(i);
            }
        }
        for (int i = 0; i < attributeFiles.length; i++) {
            attributeFiles[i] = updated[i];
        }
    }

    private void onPalTransfer(Commands.PalTrnCmd command) {
        int[][] updated = new int[systemPalettes.length][];
        for (int i = 0; i < updated.length; i++) {
            updated[i] = command.getPalette(i);
        }
        for (int i = 0; i < updated.length; i++) {
            systemPalettes[i] = updated[i];
        }
    }

    private void onPalSet(Commands.PalSetCmd palSetCmd) {
        int[][] selected = new int[4][];
        for (int i = 0; i < 4; i++) {
            selected[i] = systemPalettes[palSetCmd.getPaletteIds()[i]].clone();
        }
        for (int i = 0; i < 4; i++) {
            palettes[i] = selected[i];
        }
        if (palSetCmd.getApplyAtf()) {
            loadAttributeFile(palSetCmd.getAtfNumber());
        }
        if (palSetCmd.getCancelMaskEn()) {
            screenMask = GameboyScreenMask.CANCEL;
        }
    }

    private void onAttrSet(Commands.AttrSetCmd attrSetCmd) {
        if (attrSetCmd.getCancelMask()) {
            screenMask = GameboyScreenMask.CANCEL;
        }
        loadAttributeFile(attrSetCmd.getAttributeFileNumber());
    }

    private void loadAttributeFile(int id) {
        if (id >= attributeFiles.length) {
            return;
        }
        System.arraycopy(attributeFiles[id], 0, paletteMap, 0, paletteMap.length);
    }

    private void onAttrChr(Commands.AttrChrCmd attrChrCmd) {
        int x = attrChrCmd.getX();
        int y = attrChrCmd.getY();
        int[] updated = paletteMap.clone();
        for (int i = 1; i <= attrChrCmd.getDataSetCount(); i++) {
            updated[x + y * DMG_TILES_WIDTH] = attrChrCmd.getDataSet(i);
            if (attrChrCmd.getWritingStyle() != 0) {
                y++;
                if (y == DMG_TILES_HEIGHT) {
                    x++;
                    y = 0;
                    if (x == DMG_TILES_WIDTH) {
                        break;
                    }
                }
            } else {
                x++;
                if (x == DMG_TILES_WIDTH) {
                    y++;
                    x = 0;
                    if (y == DMG_TILES_HEIGHT) {
                        break;
                    }
                }
            }
        }
        System.arraycopy(updated, 0, paletteMap, 0, paletteMap.length);
    }

    private void onAttrBlk(Commands.AttrBlkCmd attrBlkCmd) {
        int[] updated = paletteMap.clone();
        for (int i = 1; i <= attrBlkCmd.getDataSetsCount(); i++) {
            Commands.AttrBlkCmd.DataSet dataSet = attrBlkCmd.getDataSet(i);
            for (int x = 0; x < DMG_TILES_WIDTH; x++) {
                for (int y = 0; y < DMG_TILES_HEIGHT; y++) {
                    int z = x + y * DMG_TILES_WIDTH;
                    if (dataSet.isOutside(x, y) && dataSet.changeColorsOutside()) {
                        updated[z] = dataSet.paletteNumberOutside();
                    }
                    if (dataSet.isOnLine(x, y) && dataSet.changeLineColor()) {
                        updated[z] = dataSet.paletteNumberLine();
                    }
                    if (dataSet.isOnLine(x, y) && dataSet.changeColorsInside() && !dataSet.changeColorsOutside()) {
                        updated[z] = dataSet.paletteNumberInside();
                    }
                    if (dataSet.isOnLine(x, y) && !dataSet.changeColorsInside() && dataSet.changeColorsOutside()) {
                        updated[z] = dataSet.paletteNumberOutside();
                    }
                    if (dataSet.isInside(x, y) && dataSet.changeColorsInside()) {
                        updated[z] = dataSet.paletteNumberInside();
                    }
                }
            }
        }
        System.arraycopy(updated, 0, paletteMap, 0, paletteMap.length);
    }

    private void onAttrLin(Commands.AttrLinCmd command) {
        int[] updated = paletteMap.clone();
        for (int i = 1; i <= command.getDataSetsCount(); i++) {
            Commands.AttrLinCmd.DataSet dataSet = command.getDataSet(i);
            int line = dataSet.getLineNumber();
            int palette = dataSet.getPaletteNumber();
            if (dataSet.getHVMode() == 'H') {
                Arrays.fill(updated, line * DMG_TILES_WIDTH,
                        (line + 1) * DMG_TILES_WIDTH, palette);
            } else {
                for (int y = 0; y < DMG_TILES_HEIGHT; y++) {
                    updated[line + y * DMG_TILES_WIDTH] = palette;
                }
            }
        }
        System.arraycopy(updated, 0, paletteMap, 0, paletteMap.length);
    }

    private void onAttrDiv(Commands.AttrDivCmd command) {
        int[] updated = new int[paletteMap.length];
        boolean horizontal = command.getHVMode() == 'H';
        int divider = command.getXY();
        for (int y = 0; y < DMG_TILES_HEIGHT; y++) {
            for (int x = 0; x < DMG_TILES_WIDTH; x++) {
                int coordinate = horizontal ? y : x;
                int palette = coordinate < divider
                        ? command.getPaletteNumberAboveLeft()
                        : coordinate == divider
                        ? command.getPaletteNumberDivisionLine()
                        : command.getPaletteNumberBelowRight();
                updated[x + y * DMG_TILES_WIDTH] = palette;
            }
        }
        System.arraycopy(updated, 0, paletteMap, 0, paletteMap.length);
    }

    private void onSgbBackgroundFade(Background.SgbBackgroundFadeEvent event) {
        int amount = event.amount();
        if (borderFade != amount) {
            borderFade = amount;
            invalidateBorderCache();
        }
    }

    private void onDmgFrame(DmgFrameReadyEvent dmgFrameReadyEvent) {
        if (screenMask == GameboyScreenMask.FREEZE) {
            return;
        }
        boolean includeBorder = sgbBorder;
        RenderLeasePool pool = renderLeasePool.get();
        RenderLease lease = pool.acquire(includeBorder);
        try {
            int[] result = lease.frame;
            int[] base = canonicalBase(includeBorder);
            if (includeBorder) {
                System.arraycopy(base, 0, result, 0, base.length);
            }
            renderCenter(result, base, dmgFrameReadyEvent.pixels(), includeBorder);
            // The leased array remains valid through every synchronous subscriber callback. It is
            // returned to this depth-specific pool only after the complete post has unwound.
            eventBus.post(new SgbFrameReadyEvent(result, includeBorder));
        } finally {
            pool.release(lease);
        }
    }

    /** Returns the generation-cached canonical base for the requested output geometry. */
    private int[] canonicalBase(boolean includeBorder) {
        if (includeBorder) {
            if (borderedBaseGeneration != borderGeneration) {
                rebuildBorder(borderedBase, true);
                borderedBaseGeneration = borderGeneration;
            }
            return borderedBase;
        }
        if (centerBaseGeneration != borderGeneration) {
            rebuildBorder(centerBase, false);
            centerBaseGeneration = borderGeneration;
        }
        return centerBase;
    }

    /** Rebuilds the private canonical border/base image for one output geometry. */
    private void rebuildBorder(int[] target, boolean includeBorder) {
        int width = includeBorder ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
        int height = includeBorder ? SGB_DISPLAY_HEIGHT : DISPLAY_HEIGHT;
        int sourceOffsetX = includeBorder ? 0 : DMG_WINDOW_X;
        int sourceOffsetY = includeBorder ? 0 : DMG_WINDOW_Y;
        for (int y = 0; y < height; y++) {
            int sourceRow = (y + sourceOffsetY) * SGB_DISPLAY_WIDTH + sourceOffsetX;
            int targetRow = y * width;
            for (int x = 0; x < width; x++) {
                int source = sourceRow + x;
                target[targetRow + x] = sgbMask[source] == 0
                        ? 0
                        : translateGbcRgb(fadeBorderColor(sgbBuffer[source], borderFade));
            }
        }
    }

    /** Repaints the only part of an SGB output that changes at every Game Boy frame. */
    private void renderCenter(int[] target, int[] base, int[] dmgPixels,
                              boolean includeBorder) {
        int width = includeBorder ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
        int sourceOffsetX = DMG_WINDOW_X;
        int sourceOffsetY = DMG_WINDOW_Y;
        for (int dmgY = 0; dmgY < DISPLAY_HEIGHT; dmgY++) {
            int sourceRow = (dmgY + sourceOffsetY) * SGB_DISPLAY_WIDTH + sourceOffsetX;
            int dmgRow = dmgY * DISPLAY_WIDTH;
            int targetRow = (includeBorder ? dmgY + sourceOffsetY : dmgY) * width
                    + (includeBorder ? sourceOffsetX : 0);
            for (int dmgX = 0; dmgX < DISPLAY_WIDTH; dmgX++) {
                int source = sourceRow + dmgX;
                int output = targetRow + dmgX;
                // A nonzero SGB mask keeps the cached border pixel in the Game Boy window.
                if (sgbMask[source] != 0) {
                    if (!includeBorder) {
                        target[output] = base[output];
                    }
                    continue;
                }
                int p = dmgPixels[dmgRow + dmgX];
                if (screenMask == GameboyScreenMask.BLANK_COLOR0) {
                    p = 0;
                }
                int paletteId = p == 0 ? 0 : paletteMap[(dmgX / 8) + (dmgY / 8) * DMG_TILES_WIDTH];
                int dmgPixel = palettes[paletteId][p];
                if (screenMask == GameboyScreenMask.BLANK_BLACK) {
                    dmgPixel = 0;
                }
                target[output] = translateGbcRgb(dmgPixel);
            }
        }
    }

    private void onSgbBackground(Background.SgbBackgroundReadyEvent sgbBackgroundReadyEvent) {
        System.arraycopy(sgbBackgroundReadyEvent.buffer(), 0, sgbBuffer, 0, sgbBuffer.length);
        System.arraycopy(sgbBackgroundReadyEvent.mask(), 0, sgbMask, 0, sgbMask.length);
        invalidateBorderCache();
    }

    private void invalidateBorderCache() {
        borderGeneration++;
    }

    private static int fadeBorderColor(int color, int fade) {
        int red = Math.max(0, (color & 0x1f) - fade);
        int green = Math.max(0, ((color >> 5) & 0x1f) - fade);
        int blue = Math.max(0, ((color >> 10) & 0x1f) - fade);
        return red | (green << 5) | (blue << 10);
    }

    @Override
    public ComponentState<SgbDisplay> captureState() {
        return new SgbDisplayState(sgbBuffer.clone(), sgbMask.clone(), clone2(palettes),
                clone2(systemPalettes), paletteMap.clone(), clone2(attributeFiles), screenMask,
                encodedBorderFade());
    }

    @Override
    public ComponentState<SgbDisplay> captureState(MachineStateCapture capture) {
        return new SgbDisplayState(
                capture.ints(sgbBuffer),
                capture.ints(sgbMask),
                capture.ints2(palettes),
                capture.ints2(systemPalettes),
                capture.ints(paletteMap),
                capture.ints2(attributeFiles),
                screenMask,
                encodedBorderFade());
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts(sgbBuffer);
        capture.declareInts(sgbMask);
        capture.declareInts2(palettes);
        capture.declareInts2(systemPalettes);
        capture.declareInts(paletteMap);
        capture.declareInts2(attributeFiles);
    }

    @Override
    public void restoreState(ComponentState<SgbDisplay> state) {
        if (!(state instanceof SgbDisplayState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (mem.borderFade < 0 || (mem.borderFade & ~(STATE_PALETTE_PRIORITY | STATE_BORDER_FADE_MASK)) != 0
                || (mem.borderFade & STATE_BORDER_FADE_MASK) > 32) {
            throw new IllegalArgumentException("Invalid encoded SGB display fade/priority state");
        }
        if (this.sgbBuffer.length != mem.sgbBuffer.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        if (this.sgbMask.length != mem.sgbMask.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        if (this.palettes.length != mem.palettes.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        if (this.systemPalettes.length != mem.systemPalettes.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        if (this.paletteMap.length != mem.paletteMap.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        if (this.attributeFiles.length != mem.attributeFiles.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        int[][] restoredPalettes = copyRows(mem.palettes, 4, false);
        int[][] restoredSystemPalettes = copyRows(mem.systemPalettes, 4, true);
        int[][] restoredAttributeFiles = copyRows(
                mem.attributeFiles, DMG_TILES_WIDTH * DMG_TILES_HEIGHT, false);

        // Prepare and own every row before replacing live display state. Released snapshots can
        // contain unavailable PAL_TRN rows; their deterministic compatibility value is all zero.
        System.arraycopy(mem.sgbBuffer, 0, this.sgbBuffer, 0, this.sgbBuffer.length);
        System.arraycopy(mem.sgbMask, 0, this.sgbMask, 0, this.sgbMask.length);
        System.arraycopy(restoredPalettes, 0, this.palettes, 0, this.palettes.length);
        System.arraycopy(restoredSystemPalettes, 0, this.systemPalettes, 0,
                this.systemPalettes.length);
        System.arraycopy(mem.paletteMap, 0, this.paletteMap, 0, this.paletteMap.length);
        System.arraycopy(restoredAttributeFiles, 0, this.attributeFiles, 0,
                this.attributeFiles.length);
        this.screenMask = mem.screenMask;
        this.borderFade = mem.borderFade & STATE_BORDER_FADE_MASK;
        this.palettePriority = (mem.borderFade & STATE_PALETTE_PRIORITY) != 0;
        invalidateBorderCache();
    }

    boolean isPalettePriorityEnabled() {
        return palettePriority;
    }

    private int encodedBorderFade() {
        return borderFade | (palettePriority ? STATE_PALETTE_PRIORITY : 0);
    }

    private static int[][] copyRows(int[][] src, int expectedRowLength,
                                    boolean normalizeNullRows) {
        int[][] result = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) {
                if (!normalizeNullRows) {
                    throw new IllegalArgumentException("Required array row is absent at i=" + i);
                }
                result[i] = new int[expectedRowLength];
                continue;
            }
            if (src[i].length != expectedRowLength) {
                throw new IllegalArgumentException("Array length doesn't match at i=" + i);
            }
            // PAL_SET makes active palette rows aliases of system palette rows. Replace
            // each row so restore cannot write through aliases left by the abandoned timeline.
            result[i] = src[i].clone();
        }
        return result;
    }

    private static int[][] clone2(int[][] src) {
        int[][] clone = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            clone[i] = src[i] == null ? null : src[i].clone();
        }
        return clone;
    }

    /** One reusable render target per synchronous re-entrant depth and output geometry. */
    private static final class RenderLease {
        private int[] borderedFrame;
        private int[] centerFrame;
        private int[] frame;

        private void select(boolean includeBorder) {
            if (includeBorder) {
                if (borderedFrame == null) {
                    borderedFrame = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
                }
                frame = borderedFrame;
            } else {
                if (centerFrame == null) {
                    centerFrame = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
                }
                frame = centerFrame;
            }
        }
    }

    private static final class RenderLeasePool {
        private RenderLease[] leases = new RenderLease[1];
        private int depth;

        private RenderLease acquire(boolean includeBorder) {
            int index = depth++;
            if (index == leases.length) {
                leases = Arrays.copyOf(leases, leases.length * 2);
            }
            RenderLease lease = leases[index];
            if (lease == null) {
                lease = new RenderLease();
                leases[index] = lease;
            }
            lease.select(includeBorder);
            return lease;
        }

        private void release(RenderLease lease) {
            if (depth <= 0 || leases[depth - 1] != lease) {
                throw new IllegalStateException("Unbalanced SGB render lease");
            }
            depth--;
        }
    }

    private record SgbDisplayState(int[] sgbBuffer, int[] sgbMask, int[][] palettes, int[][] systemPalettes,
                                     int[] paletteMap, int[][] attributeFiles, GameboyScreenMask screenMask,
                                     int borderFade) implements ComponentState<SgbDisplay> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SgbDisplayMemento(int[] sgbBuffer, int[] sgbMask, int[][] palettes, int[][] systemPalettes,
                                     int[] paletteMap, int[][] attributeFiles, GameboyScreenMask screenMask,
                                     int borderFade) implements Memento<SgbDisplay> {
    }

    /**
     * SGB output backed by a producer-owned render lease.
     *
     * <p>The buffer is valid only for the duration of the synchronous event-bus callback. A
     * subscriber that needs to retain a frame must copy it before returning; the producer may
     * reuse this exact array on the next publication (or a nested publication may use another
     * lease at the same time).</p>
     */
    public record SgbFrameReadyEvent(int[] buffer, boolean includeBorder)
            implements SynchronousBorrowedEvent {
        public void toRgb(int[] target, boolean unused) {
            System.arraycopy(buffer, 0, target, 0, buffer.length);
        }

        /** Copies this callback-scoped RGB payload into opaque Android-style ARGB storage. */
        public void copyToOpaqueArgb(int[] target) {
            if (target == null || target.length < buffer.length) {
                throw new IllegalArgumentException(
                        "Target pixel array is shorter than the SGB frame payload");
            }
            for (int i = 0; i < buffer.length; i++) {
                target[i] = buffer[i] | 0xff000000;
            }
        }
    }

    public record SetSgbBorder(boolean borderEnabled) implements Event {
    }
}
