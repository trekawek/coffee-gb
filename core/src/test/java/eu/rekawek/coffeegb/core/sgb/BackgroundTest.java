package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class BackgroundTest {

    @Test
    public void characterNumbersAboveTransferredRangeRemainUnused() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        Background background = new Background(sgbBus);
        background.init(eventBus);
        AtomicReference<Background.SgbBackgroundReadyEvent> rendered = new AtomicReference<>();
        eventBus.register(rendered::set, Background.SgbBackgroundReadyEvent.class);

        Commands.ChrTrnCmd characters = (Commands.ChrTrnCmd) command(0x13);
        characters.packet[1] = 1; // transfer tiles 0x80-0xff
        int[] characterData = new int[0x1000];
        Arrays.fill(characterData, 0x7f * 32, 0x80 * 32, 0xff);
        characters.setDataTransfer(characterData);
        sgbBus.post(characters);

        Commands.PctTrnCmd picture = (Commands.PctTrnCmd) command(0x14);
        int[] pictureData = new int[0x1000];
        int firstGameboyTile = 6 + 5 * 32;
        setMapEntry(pictureData, firstGameboyTile, 0x02ff);
        setMapEntry(pictureData, firstGameboyTile + 1, 0x10ff);
        pictureData[0x081e] = 0x34;
        pictureData[0x081f] = 0x12;
        picture.setDataTransfer(pictureData);
        sgbBus.post(picture);

        int firstGameboyPixel = 48 + 40 * SuperGameboy.SGB_DISPLAY_WIDTH;
        assertEquals(0, rendered.get().mask()[firstGameboyPixel]);
        assertEquals(0, rendered.get().buffer()[firstGameboyPixel]);

        int nextTilePixel = firstGameboyPixel + 8;
        assertEquals(15, rendered.get().mask()[nextTilePixel]);
        assertEquals(0x1234, rendered.get().buffer()[nextTilePixel]);
    }

    private static Commands.AbstractCommand command(int code) {
        int[] packet = new int[16];
        packet[0] = (code << 3) | 1;
        return Commands.toCommand(packet);
    }

    private static void setMapEntry(int[] pictureData, int index, int value) {
        pictureData[2 * index] = value & 0xff;
        pictureData[2 * index + 1] = value >> 8;
    }
}
