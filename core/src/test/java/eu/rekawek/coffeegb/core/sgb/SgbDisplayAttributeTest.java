package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent.translateGbcRgb;
import static org.junit.Assert.assertEquals;

public class SgbDisplayAttributeTest {

    private EventBusImpl eventBus;

    private EventBusImpl sgbBus;

    private AtomicReference<int[]> frame;

    @Before
    public void setUp() throws IOException {
        eventBus = new EventBusImpl(null, null, false);
        sgbBus = new EventBusImpl(null, null, false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, false);
        display.init(eventBus);
        frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.buffer().clone()), SgbDisplay.SgbFrameReadyEvent.class);
    }

    @Test
    public void attrChrUpdatesMapLoadedByAttrSet() {
        setDirectPalettes();
        transferAttributeFile(23, 3);
        sgbBus.post(attrSet(23));

        int[] packet = commandPacket(0x07);
        packet[1] = 4;
        packet[2] = 14;
        packet[3] = 3;
        packet[5] = 0;
        packet[6] = 0b00011000; // palettes 0, 1, 2 (most significant pair first)
        sgbBus.post(Commands.toCommand(packet));

        render(1);
        assertEquals(translateGbcRgb(0x0111), pixelAtTile(4, 14));
        assertEquals(translateGbcRgb(0x0222), pixelAtTile(5, 14));
        assertEquals(translateGbcRgb(0x0333), pixelAtTile(6, 14));
        assertEquals(translateGbcRgb(0x0444), pixelAtTile(7, 14));
    }

    @Test
    public void palSetUsesPaletteZeroColorAsSharedBackdrop() {
        Commands.PalTrnCmd transfer = (Commands.PalTrnCmd) command(0x0b);
        int[] data = new int[0x1000];
        setPaletteColor(data, 0, 0, 0x0111);
        setPaletteColor(data, 1, 0, 0x0222);
        setPaletteColor(data, 2, 0, 0x0333);
        setPaletteColor(data, 3, 0, 0x0444);
        transfer.setDataTransfer(data);
        sgbBus.post(transfer);

        int[] packet = commandPacket(0x0a);
        packet[1] = 0;
        packet[3] = 1;
        packet[5] = 2;
        packet[7] = 3;
        sgbBus.post(Commands.toCommand(packet));
        transferAttributeFile(23, 3);
        sgbBus.post(attrSet(23));

        render(0);
        assertEquals(translateGbcRgb(0x0111), pixelAtTile(0, 0));
    }

    private void setDirectPalettes() {
        int[] pal01 = commandPacket(0x00);
        setCommandColor(pal01, 3, 0x0111);
        setCommandColor(pal01, 9, 0x0222);
        sgbBus.post(Commands.toCommand(pal01));

        int[] pal23 = commandPacket(0x01);
        setCommandColor(pal23, 3, 0x0333);
        setCommandColor(pal23, 9, 0x0444);
        sgbBus.post(Commands.toCommand(pal23));
    }

    private void transferAttributeFile(int id, int palette) {
        Commands.AttrTrnCmd transfer = (Commands.AttrTrnCmd) command(0x15);
        int[] data = new int[0x1000];
        Arrays.fill(data, id * 90, (id + 1) * 90, palette * 0x55);
        transfer.setDataTransfer(data);
        sgbBus.post(transfer);
    }

    private void render(int color) {
        int[] pixels = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        Arrays.fill(pixels, color);
        eventBus.post(new Display.DmgFrameReadyEvent(pixels));
    }

    private int pixelAtTile(int x, int y) {
        return frame.get()[x * 8 + y * 8 * Display.DISPLAY_WIDTH];
    }

    private static Commands.AttrSetCmd attrSet(int id) {
        int[] packet = commandPacket(0x16);
        packet[1] = id;
        return (Commands.AttrSetCmd) Commands.toCommand(packet);
    }

    private static Commands.AbstractCommand command(int code) {
        return Commands.toCommand(commandPacket(code));
    }

    private static int[] commandPacket(int code) {
        int[] packet = new int[16];
        packet[0] = (code << 3) | 1;
        return packet;
    }

    private static void setCommandColor(int[] packet, int offset, int color) {
        packet[offset] = color & 0xff;
        packet[offset + 1] = color >> 8;
    }

    private static void setPaletteColor(int[] data, int palette, int colorId, int color) {
        int offset = palette * 8 + colorId * 2;
        data[offset] = color & 0xff;
        data[offset + 1] = color >> 8;
    }

    private static Rom testRom() throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x147] = 0;
        return new Rom(bytes);
    }
}
