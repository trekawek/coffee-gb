package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

public class Background implements Originator<Background> {

    private final int[] tiles = new int[0x2000];

    private EventBus eventBus;

    private Commands.PctTrnCmd lastPicture;

    public Background(EventBus sgbBus) {
        sgbBus.register(this::onPictureTransfer, Commands.PctTrnCmd.class);
        sgbBus.register(this::onCharTransfer, Commands.ChrTrnCmd.class);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    private void onCharTransfer(Commands.ChrTrnCmd e) {
        // the tile type bit does not affect where the data is stored
        System.arraycopy(e.dataTransfer, 0, tiles, e.getTileOffset() * 32, 0x1000);
        // the SNES side renders continuously: a border picture sent before its tile
        // data becomes visible once the tiles arrive (Super Snakey sends PCT_TRN
        // before CHR_TRN)
        if (lastPicture != null) {
            render(lastPicture);
        }
    }

    private void onPictureTransfer(Commands.PctTrnCmd picture) {
        lastPicture = picture;
        render(picture);
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
                lastPicture == null ? null : lastPicture.saveToMemento());
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
        if (mem.lastPictureMemento == null) {
            this.lastPicture = null;
        } else {
            var restored = Commands.TransferCommand.restoreFromMemento(mem.lastPictureMemento);
            if (!(restored instanceof Commands.PctTrnCmd picture)) {
                throw new IllegalArgumentException("Memento does not contain a picture transfer command");
            }
            this.lastPicture = picture;
        }
    }

    private record BackgroundMemento(int[] tiles,
                                     Memento<Commands.TransferCommand> lastPictureMemento)
            implements Memento<Background> {
    }

    public record SgbBackgroundReadyEvent(int[] buffer, int[] mask) implements Event {
    }
}
