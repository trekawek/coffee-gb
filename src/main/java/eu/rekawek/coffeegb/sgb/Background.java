package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

public class Background implements Originator<Background> {

    private final int[] tiles = new int[0x2000];

    private EventBus eventBus;

    public Background(EventBus sgbBus) {
        sgbBus.register(this::onPictureTransfer, Commands.PctTrnCmd.class);
        sgbBus.register(this::onCharTransfer, Commands.ChrTrnCmd.class);
    }

    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    private void onCharTransfer(Commands.ChrTrnCmd e) {
        if (e.getTileType() == 'B') {
            System.arraycopy(e.dataTransfer, 0, tiles, e.getTileOffset() * 32, 0x1000);
            System.out.println("Char transfer: " + e.getTileOffset());
        }
    }

    private void onPictureTransfer(Commands.PctTrnCmd picture) {
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
        return new BackgroundMemento(tiles.clone());
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
    }

    private record BackgroundMemento(int[] tiles) implements Memento<Background> {
    }

    public record SgbBackgroundReadyEvent(int[] buffer, int[] mask) implements Event {
    }
}
