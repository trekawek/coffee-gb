package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SgbMementoTest {

    @Test
    public void backgroundRestoreClearsPictureThatIsAbsentFromMemento() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        Background background = new Background(sgbBus);
        AtomicInteger renderedPictures = new AtomicInteger();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        eventBus.register(event -> renderedPictures.incrementAndGet(), Background.SgbBackgroundReadyEvent.class);
        background.init(eventBus);
        var emptyMemento = background.saveToMemento();

        Commands.PctTrnCmd picture = (Commands.PctTrnCmd) command(0x14);
        picture.setDataTransfer(new int[0x1000]);
        sgbBus.post(picture);
        advanceFrames(eventBus, 73);
        assertEquals(1, renderedPictures.get());

        background.restoreFromMemento(emptyMemento);
        renderedPictures.set(0);
        Commands.ChrTrnCmd characters = (Commands.ChrTrnCmd) command(0x13);
        characters.setDataTransfer(new int[0x1000]);
        sgbBus.post(characters);
        advanceFrames(eventBus, 105);

        assertEquals(0, renderedPictures.get());
    }

    @Test
    public void backgroundMementoRestoresItsOwnPictureData() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        Background background = new Background(sgbBus);
        AtomicReference<int[]> rendered = new AtomicReference<>();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        eventBus.register(event -> rendered.set(event.buffer().clone()),
                Background.SgbBackgroundReadyEvent.class);
        background.init(eventBus);

        Commands.PctTrnCmd picture = (Commands.PctTrnCmd) command(0x14);
        int[] pictureData = new int[0x1000];
        pictureData[0x780] = 0x34;
        pictureData[0x781] = 0x12;
        picture.setDataTransfer(pictureData);
        sgbBus.post(picture);
        var memento = background.saveToMemento();

        pictureData[0x780] = 0x78;
        pictureData[0x781] = 0x56;
        background.restoreFromMemento(memento);
        rendered.set(null);
        Commands.ChrTrnCmd characters = (Commands.ChrTrnCmd) command(0x13);
        characters.setDataTransfer(new int[0x1000]);
        sgbBus.post(characters);
        advanceFrames(eventBus, 73);

        assertEquals(0x1234, rendered.get()[0]);
    }

    @Test
    public void borderCharactersRemainPendingUntilThePictureTransition() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        Background background = new Background(sgbBus);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AtomicInteger renderedPictures = new AtomicInteger();
        eventBus.register(event -> renderedPictures.incrementAndGet(),
                Background.SgbBackgroundReadyEvent.class);
        background.init(eventBus);

        Commands.ChrTrnCmd characters = (Commands.ChrTrnCmd) command(0x13);
        characters.setDataTransfer(new int[0x1000]);
        sgbBus.post(characters);
        advanceFrames(eventBus, 100);
        assertEquals(0, renderedPictures.get());

        Commands.PctTrnCmd picture = (Commands.PctTrnCmd) command(0x14);
        picture.setDataTransfer(new int[0x1000]);
        sgbBus.post(picture);
        advanceFrames(eventBus, 72);
        assertEquals(0, renderedPictures.get());
        advanceFrames(eventBus, 1);
        assertEquals(1, renderedPictures.get());
    }

    @Test
    public void displayAppliesAndRestoresBorderFade() throws IOException {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, true);
        display.init(eventBus);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.buffer().clone()), SgbDisplay.SgbFrameReadyEvent.class);

        int[] border = new int[SuperGameboy.SGB_DISPLAY_WIDTH * SuperGameboy.SGB_DISPLAY_HEIGHT];
        int[] mask = new int[border.length];
        Arrays.fill(border, 0x7fff);
        Arrays.fill(mask, 1);
        eventBus.post(new Background.SgbBackgroundReadyEvent(border, mask));
        eventBus.post(new Background.SgbBackgroundFadeEvent(1));
        var memento = display.saveToMemento();

        eventBus.post(new Background.SgbBackgroundFadeEvent(31));
        display.restoreFromMemento(memento);
        eventBus.post(new Display.DmgFrameReadyEvent(new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));

        assertEquals(Display.GbcFrameReadyEvent.translateGbcRgb(0x7bde), frame.get()[0]);
    }

    @Test
    public void displayMementoDeepCopiesAttributeFiles() throws IOException {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, false);
        display.init(eventBus);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.buffer().clone()), SgbDisplay.SgbFrameReadyEvent.class);

        int[] attrSetPacket = new int[16];
        attrSetPacket[0] = (0x16 << 3) | 1;
        sgbBus.post(Commands.toCommand(attrSetPacket));
        var memento = display.saveToMemento();

        Commands.AttrTrnCmd attributes = (Commands.AttrTrnCmd) command(0x15);
        int[] attributeData = new int[0x1000];
        Arrays.fill(attributeData, 0xff);
        attributes.setDataTransfer(attributeData);
        sgbBus.post(attributes);
        display.restoreFromMemento(memento);

        int[] dmgPixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(dmgPixels, 1);
        eventBus.post(new Display.DmgFrameReadyEvent(dmgPixels));
        assertNotEquals(0, frame.get()[0]);
    }

    @Test
    public void displayRestoreBreaksAbandonedPaletteAliases() throws IOException {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, false);
        display.init(eventBus);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.buffer().clone()), SgbDisplay.SgbFrameReadyEvent.class);

        Commands.PalTrnCmd palettes = (Commands.PalTrnCmd) command(0x0b);
        int[] paletteData = new int[0x1000];
        paletteData[0] = 0x11;
        paletteData[1] = 0x01;
        paletteData[8] = 0x22;
        paletteData[9] = 0x02;
        palettes.setDataTransfer(paletteData);
        sgbBus.post(palettes);
        sgbBus.post(paletteSet(0));
        var memento = display.saveToMemento();

        // All active rows now alias system palette 1. Restoring by copying into these
        // rows would leave the active palette and/or the saved system table corrupted.
        sgbBus.post(paletteSet(1));
        display.restoreFromMemento(memento);

        int[] dmgPixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        eventBus.post(new Display.DmgFrameReadyEvent(dmgPixels));
        assertEquals(Display.GbcFrameReadyEvent.translateGbcRgb(0x0111), frame.get()[0]);

        sgbBus.post(paletteSet(1));
        eventBus.post(new Display.DmgFrameReadyEvent(dmgPixels));
        assertEquals(Display.GbcFrameReadyEvent.translateGbcRgb(0x0222), frame.get()[0]);
    }

    private static Commands.AbstractCommand command(int code) {
        int[] packet = new int[16];
        packet[0] = (code << 3) | 1;
        return Commands.toCommand(packet);
    }

    private static void advanceFrames(EventBusImpl eventBus, int count) {
        int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        for (int i = 0; i < count; i++) {
            eventBus.post(new Display.DmgFrameReadyEvent(pixels));
        }
    }

    private static Commands.PalSetCmd paletteSet(int paletteId) {
        int[] packet = new int[16];
        packet[0] = (0x0a << 3) | 1;
        packet[1] = paletteId;
        packet[3] = paletteId;
        packet[5] = paletteId;
        packet[7] = paletteId;
        return (Commands.PalSetCmd) Commands.toCommand(packet);
    }

    private static Rom testRom() throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x147] = 0;
        return new Rom(bytes);
    }
}
