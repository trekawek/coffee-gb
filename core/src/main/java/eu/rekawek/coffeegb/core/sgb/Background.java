package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

public class Background implements Originator<Background> {

    // SGB2 measurement used by SameBoy: wait 41 frames, fade the old border
    // out for 32, swap at black, then fade the new border in for 32.
    private static final int BORDER_ANIMATION_FRAMES = 105;

    private static final int BORDER_SWAP_FRAME = 32;

    private final int[] tiles = new int[0x2000];

    private EventBus eventBus;

    private Commands.PctTrnCmd pendingPicture;

    private int borderAnimation;

    public Background(EventBus sgbBus) {
        sgbBus.register(this::onPictureTransfer, Commands.PctTrnCmd.class);
        sgbBus.register(this::onCharTransfer, Commands.ChrTrnCmd.class);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
        eventBus.register(e -> onFrame(), Display.DmgFrameReadyEvent.class);
    }

    private void onCharTransfer(Commands.ChrTrnCmd e) {
        // the tile type bit does not affect where the data is stored
        System.arraycopy(e.dataTransfer, 0, tiles, e.getTileOffset() * 32, 0x1000);
    }

    private void onPictureTransfer(Commands.PctTrnCmd picture) {
        pendingPicture = picture;
        borderAnimation = BORDER_ANIMATION_FRAMES;
        if (eventBus != null) {
            eventBus.post(new SgbBackgroundFadeEvent(0));
        }
    }

    private void onFrame() {
        if (borderAnimation == 0) {
            return;
        }
        borderAnimation--;
        int fade;
        if (borderAnimation >= 64) {
            fade = 0;
        } else if (borderAnimation > BORDER_SWAP_FRAME) {
            fade = 64 - borderAnimation;
        } else {
            fade = borderAnimation;
        }
        if (borderAnimation == BORDER_SWAP_FRAME && pendingPicture != null) {
            // CHR_TRN updates the pending tile data in either valid ordering:
            // Galaga/Galaxian sends CHR then PCT, while Super Snakey sends PCT
            // then CHR. Commit the complete pending border only at the black
            // midpoint so neither ordering exposes half-updated graphics.
            render(pendingPicture);
        }
        if (eventBus != null) {
            eventBus.post(new SgbBackgroundFadeEvent(fade));
        }
    }

    private void render(Commands.PctTrnCmd picture) {
        int[] buffer = new int[SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
        int[] mask = new int[SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
        for (int i = 0; i < buffer.length; i++) {
            int x = i % SuperGameboy.SGB_DISPLAY_WIDTH;
            int y = i / SuperGameboy.SGB_DISPLAY_WIDTH;

            int charX = x / 8;
            int charY = y / 8;

            Commands.PctTrnCmd.BgMapEntry e = picture.getBgMapEntry(charX + charY * 32);

            int charPixelX = x % 8;
            int charPixelY = y % 8;

            if (e.getXFlip()) {
                charPixelX = 7 - charPixelX;
            }
            if (e.getYFlip()) {
                charPixelY = 7 - charPixelY;
            }

            int pixel = getPixel(e.getCharNumber(), charPixelX, charPixelY);
            mask[i] = pixel;
            buffer[i] = picture.getPaletteColor(e.getPaletteNumber(), pixel);
        }
        if (eventBus != null) {
            eventBus.post(new SgbBackgroundReadyEvent(buffer, mask));
        }
    }

    private int getPixel(int tileId, int x, int y) {
        int offset = tileId * 32 + y * 2;
        int result = (tiles[offset] & (1 << (7 - x))) == 0 ? 0 : 1;
        result |= (tiles[offset + 1] & (1 << (7 - x))) == 0 ? 0 : 2;
        result |= (tiles[offset + 16] & (1 << (7 - x))) == 0 ? 0 : 4;
        result |= (tiles[offset + 17] & (1 << (7 - x))) == 0 ? 0 : 8;
        return result;
    }

    @Override
    public Memento<Background> saveToMemento() {
        return new BackgroundMemento(tiles.clone(),
                pendingPicture == null ? null : pendingPicture.saveToMemento(), borderAnimation);
    }

    @Override
    public void restoreFromMemento(Memento<Background> memento) {
        if (!(memento instanceof BackgroundMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.tiles.length != mem.tiles.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(mem.tiles, 0, this.tiles, 0, this.tiles.length);
        if (mem.pendingPictureMemento == null) {
            this.pendingPicture = null;
        } else {
            var restored = Commands.TransferCommand.restoreFromMemento(mem.pendingPictureMemento);
            if (!(restored instanceof Commands.PctTrnCmd picture)) {
                throw new IllegalArgumentException("Memento does not contain a picture transfer command");
            }
            this.pendingPicture = picture;
        }
        this.borderAnimation = mem.borderAnimation;
    }

    private record BackgroundMemento(int[] tiles,
                                     Memento<Commands.TransferCommand> pendingPictureMemento,
                                     int borderAnimation)
            implements Memento<Background> {
    }

    public record SgbBackgroundReadyEvent(int[] buffer, int[] mask) implements Event {
    }

    public record SgbBackgroundFadeEvent(int amount) implements Event {
    }
}
