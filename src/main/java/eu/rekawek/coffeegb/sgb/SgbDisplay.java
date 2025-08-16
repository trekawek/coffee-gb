package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.gpu.Display.DmgFrameReadyEvent;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.sgb.Commands.MaskEnCmd.GameboyScreenMask;

import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.gpu.Display.DISPLAY_WIDTH;
import static eu.rekawek.coffeegb.gpu.Display.GbcFrameReadyEvent.translateGbcRgb;
import static eu.rekawek.coffeegb.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;

public class SgbDisplay implements Originator<SgbDisplay> {

    private static final int DMG_TILES_WIDTH = DISPLAY_WIDTH / 8;

    private static final int DMG_TILES_HEIGHT = DISPLAY_HEIGHT / 8;

    private static final int DMG_WINDOW_X = 48;

    private static final int DMG_WINDOW_Y = 40;

    private volatile boolean sgbBorder;

    private final boolean sgb;

    private final EventBus sgbBus;

    private EventBus eventBus;

    private final int[] sgbBuffer = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    private final int[] sgbMask = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];

    private final int[][] palettes = new int[4][4];

    private final int[][] systemPalettes = new int[512][];

    private final int[] paletteMap = new int[DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private final int[][] attributeFiles = new int[45][DMG_TILES_WIDTH * DMG_TILES_HEIGHT];

    private GameboyScreenMask screenMask = GameboyScreenMask.CANCEL;

    private int atfNumber = -1;

    public SgbDisplay(EventBus sgbBus, boolean sgb, boolean sgbBorder) {
        this.sgbBorder = sgbBorder;
        this.sgb = sgb;
        this.sgbBus = sgbBus;
    }

    public void init(EventBus eventBus) {
        if (sgb) {
            this.eventBus = eventBus;
            eventBus.register(this::onSgbBackground, Background.SgbBackgroundReadyEvent.class);
            eventBus.register(this::onDmgFrame, DmgFrameReadyEvent.class);
            eventBus.register(e -> this.sgbBorder = e.borderEnabled, SetSgbBorder.class);

            sgbBus.register(this::onAttrBlk, Commands.AttrBlkCmd.class);
            sgbBus.register(e -> {
                palettes[0] = e.getPalette0();
                palettes[1] = e.getPalette1();
            }, Commands.Pal01Cmd.class);
            sgbBus.register(e -> {
                palettes[0] = e.getPalette0();
                palettes[3] = e.getPalette3();
            }, Commands.Pal03Cmd.class);
            sgbBus.register(e -> {
                palettes[1] = e.getPalette1();
                palettes[2] = e.getPalette2();
            }, Commands.Pal12Cmd.class);
            sgbBus.register(e -> {
                palettes[2] = e.getPalette2();
                palettes[3] = e.getPalette3();
            }, Commands.Pal23Cmd.class);
            sgbBus.register(e -> {
                for (int i = 0; i < 512; i++) {
                    systemPalettes[i] = e.getPalette(i);
                }
            }, Commands.PalTrnCmd.class);
            sgbBus.register(this::onPalSet, Commands.PalSetCmd.class);
            sgbBus.register(this::onAttrTransfer, Commands.AttrTrnCmd.class);
            sgbBus.register(this::onAttrSet, Commands.AttrSetCmd.class);
            sgbBus.register(e -> screenMask = e.getScreenMask(), Commands.MaskEnCmd.class);
        }
    }

    private void onAttrTransfer(Commands.AttrTrnCmd attrTrnCmd) {
        for (int atfId = 0; atfId < 45; atfId++) {
            for (int i = 0; i < DMG_TILES_WIDTH * DMG_TILES_HEIGHT; i++) {
                attributeFiles[atfId][i] = attrTrnCmd.getAttributeFile(atfId).getColor(i);
            }
        }
    }

    private void onPalSet(Commands.PalSetCmd palSetCmd) {
        for (int i = 0; i < 4; i++) {
            palettes[i] = systemPalettes[palSetCmd.getPaletteIds()[i]];
        }
        if (palSetCmd.getApplyAtf()) {
            atfNumber = palSetCmd.getAtfNumber();
        }
        if (palSetCmd.getCancelMaskEn()) {
            screenMask = GameboyScreenMask.CANCEL;
        }
    }

    private void onAttrSet(Commands.AttrSetCmd attrSetCmd) {
        if (attrSetCmd.getCancelMask()) {
            screenMask = GameboyScreenMask.CANCEL;
        }
        atfNumber = attrSetCmd.getAttributeFileNumber();
    }

    private void onAttrBlk(Commands.AttrBlkCmd attrBlkCmd) {
        for (int i = 1; i <= attrBlkCmd.getDataSetsCount(); i++) {
            Commands.AttrBlkCmd.DataSet dataSet = attrBlkCmd.getDataSet(i);
            for (int x = 0; x < DMG_TILES_WIDTH; x++) {
                for (int y = 0; y < DMG_TILES_HEIGHT; y++) {
                    int z = x + y * DMG_TILES_WIDTH;
                    if (dataSet.isOutside(x, y) && dataSet.changeColorsOutside()) {
                        paletteMap[z] = dataSet.paletteNumberOutside();
                    }
                    if (dataSet.isOnLine(x, y) && dataSet.changeLineColor()) {
                        paletteMap[z] = dataSet.paletteNumberLine();
                    }
                    if (dataSet.isOnLine(x, y) && dataSet.changeColorsInside() && !dataSet.changeColorsOutside()) {
                        paletteMap[z] = dataSet.paletteNumberInside();
                    }
                    if (dataSet.isOnLine(x, y) && !dataSet.changeColorsInside() && dataSet.changeColorsOutside()) {
                        paletteMap[z] = dataSet.paletteNumberOutside();
                    }
                    if (dataSet.isInside(x, y) && dataSet.changeColorsInside()) {
                        paletteMap[z] = dataSet.paletteNumberInside();
                    }
                }
            }
        }
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

        int lastPaletteId = paletteMap[paletteMap.length - 1];
        if (atfNumber != -1) {
            var atf = attributeFiles[atfNumber];
            lastPaletteId = atf[atf.length - 1];
        }

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
                    if (atfNumber >= 0) {
                        paletteId = attributeFiles[atfNumber][charId];
                    }
                    int p = dmgFrameReadyEvent.pixels()[dmgX + dmgY * DISPLAY_WIDTH];
                    if (screenMask == GameboyScreenMask.BLANK_COLOR0) {
                        p = 0;
                    }
                    if (p == 0) {
                        paletteId = lastPaletteId;
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
                    result[i] = translateGbcRgb(sgbPixel);
                }
            }
        }
        eventBus.post(new SgbFrameReadyEvent(result, sgbBorder));
    }

    private void onSgbBackground(Background.SgbBackgroundReadyEvent sgbBackgroundReadyEvent) {
        System.arraycopy(sgbBackgroundReadyEvent.buffer(), 0, sgbBuffer, 0, sgbBuffer.length);
        System.arraycopy(sgbBackgroundReadyEvent.mask(), 0, sgbMask, 0, sgbMask.length);
    }

    @Override
    public Memento<SgbDisplay> saveToMemento() {
        return new SgbDisplayMemento(sgbBuffer.clone(), sgbMask.clone(), palettes.clone(), systemPalettes.clone(), paletteMap.clone(), attributeFiles.clone(), screenMask, atfNumber);
    }

    @Override
    public void restoreFromMemento(Memento<SgbDisplay> memento) {
        if (!(memento instanceof SgbDisplayMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.sgbBuffer.length != mem.sgbBuffer.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.sgbMask.length != mem.sgbMask.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.palettes.length != mem.palettes.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.systemPalettes.length != mem.systemPalettes.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.paletteMap.length != mem.paletteMap.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        if (this.attributeFiles.length != mem.attributeFiles.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(mem.sgbBuffer, 0, this.sgbBuffer, 0, this.sgbBuffer.length);
        System.arraycopy(mem.sgbMask, 0, this.sgbMask, 0, this.sgbMask.length);
        arraycopy2(mem.palettes, this.palettes);
        arraycopy2(mem.systemPalettes, this.systemPalettes);
        System.arraycopy(mem.paletteMap, 0, this.paletteMap, 0, this.paletteMap.length);
        arraycopy2(mem.attributeFiles, this.attributeFiles);
        this.screenMask = mem.screenMask;
        this.atfNumber = mem.atfNumber;
    }

    private static void arraycopy2(int[][] src, int[][] dst) {
        if (src.length != dst.length) {
            throw new IllegalArgumentException("Array length doesn't match");
        }
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) {
                dst[i] = null;
                continue;
            }
            if (src[i].length != dst[i].length) {
                throw new IllegalArgumentException("Array length doesn't match at i=" + i);
            }
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
    }

    private record SgbDisplayMemento(int[] sgbBuffer, int[] sgbMask, int[][] palettes, int[][] systemPalettes,
                                     int[] paletteMap, int[][] attributeFiles, GameboyScreenMask screenMask,
                                     int atfNumber) implements Memento<SgbDisplay> {
    }

    public record SgbFrameReadyEvent(int[] buffer, boolean includeBorder) implements Event {
        public void toRgb(int[] target, boolean unused) {
            System.arraycopy(buffer, 0, target, 0, buffer.length);
        }
    }

    public record SetSgbBorder(boolean borderEnabled) implements Event {
    }
}
