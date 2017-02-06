package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Fetcher;
import eu.rekawek.coffeegb.gpu.Lcdc;
import eu.rekawek.coffeegb.gpu.DmgPixelFifo;
import eu.rekawek.coffeegb.gpu.PixelFifo;
import eu.rekawek.coffeegb.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.gpu.GpuRegister.BGP;
import static eu.rekawek.coffeegb.gpu.GpuRegister.LY;
import static eu.rekawek.coffeegb.gpu.GpuRegister.SCX;
import static eu.rekawek.coffeegb.gpu.GpuRegister.SCY;
import static eu.rekawek.coffeegb.gpu.GpuRegister.WX;
import static eu.rekawek.coffeegb.gpu.GpuRegister.WY;

public class PixelTransfer implements GpuPhase {

    private final PixelFifo fifo;

    private final Fetcher fetcher;

    private final Display display;

    private final MemoryRegisters r;

    private final Lcdc lcdc;

    private final SpritePosition[] sprites;

    private final boolean gbc;

    private int droppedPixels;

    private int x;

    private boolean window;

    public PixelTransfer(AddressSpace videoRam0, AddressSpace videoRam1, AddressSpace oemRam, Display display, MemoryRegisters r, SpritePosition[] sprites, boolean gbc) {
        this.r = r;
        this.lcdc = new Lcdc(r);
        this.gbc = gbc;
        this.fifo = new DmgPixelFifo(display, r);
        this.fetcher = new Fetcher(fifo, videoRam0, videoRam1, oemRam, r, gbc);
        this.display = display;
        this.sprites = sprites;

        if (lcdc.isBgAndWindowDisplay() || gbc) {
            startFetchingBackground();
        } else {
            fetcher.fetchingDisabled();
        }
    }

    @Override
    public boolean tick() {
        fetcher.tick();
        if (lcdc.isBgAndWindowDisplay() || gbc) {
            if (fifo.getLength() <= 8) {
                return true;
            }
            if (droppedPixels < r.get(SCX) % 8) {
                fifo.dropPixel();
                droppedPixels++;
                return true;
            }
            if (!window && lcdc.isWindowDisplay() && r.get(LY) >= r.get(WY) && x == r.get(WX) - 7) {
                window = true;
                startFetchingWindow();
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
                if (s == null) {
                    continue;
                }
                if (x == 0 && s.getX() < 8) {
                    if (!spriteAdded) {
                        fetcher.addSprite(s, 8 - s.getX(), i);
                        spriteAdded = true;
                    }
                    sprites[i] = null;
                } else if (s.getX() - 8 == x) {
                    if (!spriteAdded) {
                        fetcher.addSprite(s, 0, i);
                        spriteAdded = true;
                    }
                    sprites[i] = null;
                }
                if (spriteAdded) {
                    return true;
                }
            }
        }

        fifo.putPixelToScreen();
        if (++x == 160) {
            return false;
        }
        return true;
    }

    private void startFetchingBackground() {
        int bgX = r.get(SCX) / 0x08;
        int bgY = (r.get(SCY) + r.get(LY)) % 0x100;

        fetcher.startFetching(lcdc.getBgTileMapDisplay() + (bgY / 0x08) * 0x20, lcdc.getBgWindowTileData(), bgX, lcdc.isBgWindowTileDataSigned(), bgY % 0x08);
    }

    private void startFetchingWindow() {
        int winX = (this.x - r.get(WX) + 7) / 0x08;
        int winY = r.get(LY) - r.get(WY);

        fetcher.startFetching(lcdc.getWindowTileMapDisplay() + (winY / 0x08) * 0x20, lcdc.getBgWindowTileData(), winX, lcdc.isBgWindowTileDataSigned(), winY % 0x08);
    }

}
