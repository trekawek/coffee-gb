package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
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

    private volatile boolean sgbBorder;

    private final boolean sgb;

    private final EventBus sgbBus;

    private EventBus eventBus;

    private final int[] predefinedPalette;

    private final int[] sgbBuffer = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    private final int[] sgbMask = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    private final int[][] palettes = new int[4][4];

    private final int[][] systemPalettes = new int[512][4];

    private final int[] paletteMap = new int[DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private final int[][] attributeFiles = new int[45][DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private GameboyScreenMask screenMask = GameboyScreenMask.CANCEL;

    private int borderFade;

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
        predefinedPalette = DefinedPalettes.getPalette(rom.getTitle().trim());
    }

    public void init(EventBus eventBus) {
        this.palettes[0] = predefinedPalette;
        if (sgb) {
            this.eventBus = eventBus;
            eventBus.register(this::onSgbBackground, Background.SgbBackgroundReadyEvent.class);
            eventBus.register(e -> borderFade = e.amount(), Background.SgbBackgroundFadeEvent.class);
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

    private void onDmgFrame(DmgFrameReadyEvent dmgFrameReadyEvent) {
        if (screenMask == GameboyScreenMask.FREEZE) {
            return;
        }
        int offsetX = sgbBorder ? 0 : 48;
        int offsetY = sgbBorder ? 0 : 40;
        int width = sgbBorder ? SGB_DISPLAY_WIDTH : DISPLAY_WIDTH;
        int height = sgbBorder ? SGB_DISPLAY_HEIGHT : DISPLAY_HEIGHT;
        int[] result = new int[width * height];

        for (int x = offsetX; x < offsetX + width; x++) {
            for (int y = offsetY; y < offsetY + height; y++) {
                int sgbPixel = sgbBuffer[x + y * SGB_DISPLAY_WIDTH];
                int mask = sgbMask[x + y * SGB_DISPLAY_WIDTH];
                int dmgPixel;
                if (x >= DMG_WINDOW_X && x < DMG_WINDOW_X + DISPLAY_WIDTH && y >= DMG_WINDOW_Y && y < DMG_WINDOW_Y + DISPLAY_HEIGHT) {
                    int dmgX = x - DMG_WINDOW_X;
                    int dmgY = y - DMG_WINDOW_Y;
                    int tileX = dmgX / 8;
                    int tileY = dmgY / 8;
                    int charId = tileX + tileY * DMG_TILES_WIDTH;
                    int paletteId = paletteMap[charId];
                    int p = dmgFrameReadyEvent.pixels()[dmgX + dmgY * DISPLAY_WIDTH];
                    if (screenMask == GameboyScreenMask.BLANK_COLOR0) {
                        p = 0;
                    }
                    if (p == 0) {
                        paletteId = 0;
                    }
                    dmgPixel = palettes[paletteId][p];
                    if (screenMask == GameboyScreenMask.BLANK_BLACK) {
                        dmgPixel = 0;
                    }
                } else {
                    dmgPixel = 0;
                }
                int i = (x - offsetX) + (y - offsetY) * width;
                if (mask == 0) {
                    result[i] = translateGbcRgb(dmgPixel);
                } else {
                    result[i] = translateGbcRgb(fadeBorderColor(sgbPixel, borderFade));
                }
            }
        }
        eventBus.post(new SgbFrameReadyEvent(result, sgbBorder));
    }

    private void onSgbBackground(Background.SgbBackgroundReadyEvent sgbBackgroundReadyEvent) {
        System.arraycopy(sgbBackgroundReadyEvent.buffer(), 0, sgbBuffer, 0, sgbBuffer.length);
        System.arraycopy(sgbBackgroundReadyEvent.mask(), 0, sgbMask, 0, sgbMask.length);
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
        System.arraycopy(mem.sgbBuffer, 0, this.sgbBuffer, 0, this.sgbBuffer.length);
        System.arraycopy(mem.sgbMask, 0, this.sgbMask, 0, this.sgbMask.length);
        replaceRows(mem.palettes, this.palettes, 4);
        replaceRows(mem.systemPalettes, this.systemPalettes, 4);
        System.arraycopy(mem.paletteMap, 0, this.paletteMap, 0, this.paletteMap.length);
        replaceRows(mem.attributeFiles, this.attributeFiles, DMG_TILES_WIDTH * DMG_TILES_HEIGHT);
        this.screenMask = mem.screenMask;
        this.borderFade = mem.borderFade & STATE_BORDER_FADE_MASK;
        this.palettePriority = (mem.borderFade & STATE_PALETTE_PRIORITY) != 0;
    }

    boolean isPalettePriorityEnabled() {
        return palettePriority;
    }

    private int encodedBorderFade() {
        return borderFade | (palettePriority ? STATE_PALETTE_PRIORITY : 0);
    }

    private static void replaceRows(int[][] src, int[][] dst, int expectedRowLength) {
        if (src.length != dst.length) {
            throw new IllegalArgumentException("Array length doesn't match");
        }
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) {
                dst[i] = null;
                continue;
            }
            if (src[i].length != expectedRowLength) {
                throw new IllegalArgumentException("Array length doesn't match at i=" + i);
            }
            // PAL_SET makes active palette rows aliases of system palette rows. Replace
            // each row so restore cannot write through aliases left by the abandoned timeline.
            dst[i] = src[i].clone();
        }
    }

    private static int[][] clone2(int[][] src) {
        int[][] clone = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            clone[i] = src[i] == null ? null : src[i].clone();
        }
        return clone;
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

    public record SgbFrameReadyEvent(int[] buffer, boolean includeBorder) implements Event {
        public void toRgb(int[] target, boolean unused) {
            System.arraycopy(buffer, 0, target, 0, buffer.length);
        }
    }

    public record SetSgbBorder(boolean borderEnabled) implements Event {
    }
}
