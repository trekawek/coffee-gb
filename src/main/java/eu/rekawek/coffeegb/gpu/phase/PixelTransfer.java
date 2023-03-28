package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.*;
import eu.rekawek.coffeegb.gpu.phase.OamSearch.SpritePosition;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class PixelTransfer implements GpuPhase {

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private final GpuRegisterValues r;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final SpritePosition[] sprites;

    private int droppedPixels;

    private int x;

    private boolean window;

    private int windowLineCounter;

    public PixelTransfer(AddressSpace videoRam0, AddressSpace videoRam1, AddressSpace oemRam, Display display, Lcdc lcdc, GpuRegisterValues r, boolean gbc, ColorPalette bgPalette, ColorPalette oamPalette, SpritePosition[] sprites) {
        this.r = r;
        this.lcdc = lcdc;
        this.gbc = gbc;
        if (gbc) {
            this.fifo = new ColorPixelFifo(lcdc, display, bgPalette, oamPalette);
        } else {
            this.fifo = new DmgPixelFifo(display, lcdc, r);
        }
        this.fetcher = new Fetcher(fifo, videoRam0, videoRam1, oemRam, lcdc, r, gbc);
        this.sprites = sprites;
    }

    public PixelTransfer start() {
        droppedPixels = 0;
        x = 0;
        window = false;

        fetcher.init();
        if (gbc || lcdc.isBgAndWindowDisplay()) {
            startFetchingBackground();
        } else {
            fetcher.fetchingDisabled();
        }
        return this;
    }

    public void resetWindowLineCounter() {
        windowLineCounter = 0;
    }

    public void incrementWindowLineCounter() {
        windowLineCounter++;
    }

    @Override
    public boolean tick() {
        fetcher.tick();
        if (lcdc.isBgAndWindowDisplay() || gbc) {
            if (!window && lcdc.isWindowDisplay() && r.get(LY) >= r.get(WY) && x >= r.get(WX) - 7) {
                window = true;
                startFetchingWindow();
                return true;
            }
            if (fifo.getLength() <= 8) {
                return true;
            }
            if (droppedPixels < r.get(SCX) % 8) {
                fifo.dropPixel();
                droppedPixels++;
                return true;
            }
        }

        if (lcdc.isObjDisplay()) {
            if (fetcher.spriteInProgress()) {
                return true;
            }
            boolean spriteAdded = false;
            for (int i = 0; i < sprites.length; i++) {
                SpritePosition s = sprites[i];
                if (!s.isEnabled()) {
                    continue;
                }
                if (x == 0 && s.getX() < 8) {
                    fetcher.addSprite(s, 8 - s.getX(), i);
                    sprites[i].disable();
                    spriteAdded = true;
                } else if (s.getX() - 8 == x) {
                    fetcher.addSprite(s, 0, i);
                    sprites[i].disable();
                    spriteAdded = true;
                }
                if (spriteAdded) {
                    return true;
                }
            }
        }

        fifo.putPixelToScreen();
        return ++x != 160;
    }

    private void startFetchingBackground() {
        int bgX = r.get(SCX) / 0x08;
        int bgY = (r.get(SCY) + r.get(LY)) % 0x100;

        fetcher.startFetching(lcdc.getBgTileMapDisplay() + (bgY / 0x08) * 0x20, lcdc.getBgWindowTileData(), bgX, lcdc.isBgWindowTileDataSigned(), bgY % 0x08);
    }

    private void startFetchingWindow() {
        int winX = (this.x - r.get(WX) + 7) / 0x08;
        int winY = windowLineCounter;

        fetcher.startFetching(lcdc.getWindowTileMapDisplay() + (winY / 0x08) * 0x20, lcdc.getBgWindowTileData(), winX, lcdc.isBgWindowTileDataSigned(), winY % 0x08);
    }

}
