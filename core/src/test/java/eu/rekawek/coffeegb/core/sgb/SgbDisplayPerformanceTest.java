package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.events.SynchronousBorrowedEvent;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_HEIGHT;
import static eu.rekawek.coffeegb.core.sgb.SuperGameboy.SGB_DISPLAY_WIDTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class SgbDisplayPerformanceTest {

    @Test
    public void nonSgbDisplayDoesNotAllocateCompositorCaches() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, "dmg-render", false);
        try {
            SgbDisplay display = new SgbDisplay(testRom(), eventBus, false, false);
            display.init(eventBus);
            eventBus.post(new Display.DmgFrameReadyEvent(
                    new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT]));
            assertNull(privateField("borderedBase").get(display));
            assertNull(privateField("centerBase").get(display));
            assertNull(privateField("renderLeasePool").get(display));
        } finally {
            eventBus.close();
        }
    }

    @Test
    public void reusesPrimaryGeometryBuffersForStableGeometry() throws IOException {
        EventBusImpl eventBus = new EventBusImpl(null, "sgb-render", false);
        SgbDisplay display = new SgbDisplay(testRom(), eventBus, true, false);
        display.init(eventBus);
        AtomicReference<int[]> frame = new AtomicReference<>();
        eventBus.register(event -> frame.set(event.buffer()), SgbDisplay.SgbFrameReadyEvent.class);
        try {
            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            eventBus.post(new Display.DmgFrameReadyEvent(dmg));
            int[] first = frame.get();
            eventBus.post(new Display.DmgFrameReadyEvent(dmg));

            assertSame(first, frame.get());
            assertEquals(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT, first.length);
        } finally {
            eventBus.close();
        }
    }

    @Test
    public void retainsSeparateExactGeometryBuffersAndSupportsNestedPublication() throws IOException {
        EventBusImpl eventBus = new EventBusImpl(null, "sgb-nested", false);
        SgbDisplay display = new SgbDisplay(testRom(), eventBus, true, false);
        display.init(eventBus);
        AtomicReference<int[]> center = new AtomicReference<>();
        AtomicReference<int[]> bordered = new AtomicReference<>();
        AtomicReference<int[]> outer = new AtomicReference<>();
        AtomicReference<int[]> nested = new AtomicReference<>();
        AtomicReference<int[]> outerSnapshot = new AtomicReference<>();
        AtomicBoolean inOuter = new AtomicBoolean();
        int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        try {
            eventBus.register(event -> {
                if (event.includeBorder()) {
                    bordered.set(event.buffer());
                } else if (center.get() == null) {
                    center.set(event.buffer());
                }
                if (outer.compareAndSet(null, event.buffer())) {
                    outerSnapshot.set(event.buffer().clone());
                    inOuter.set(true);
                    eventBus.post(new Display.DmgFrameReadyEvent(dmg));
                    inOuter.set(false);
                    assertArrayEquals(outerSnapshot.get(), outer.get());
                } else if (inOuter.get()) {
                    nested.set(event.buffer());
                }
            }, SgbDisplay.SgbFrameReadyEvent.class);

            eventBus.post(new Display.DmgFrameReadyEvent(dmg));
            assertNotNull(nested.get());
            assertNotSame(outer.get(), nested.get());

            eventBus.post(new SgbDisplay.SetSgbBorder(true));
            eventBus.post(new Display.DmgFrameReadyEvent(dmg));
            assertEquals(SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT, bordered.get().length);
            assertNotSame(center.get(), bordered.get());
        } finally {
            eventBus.close();
        }
    }

    @Test
    public void mutatingPublishedBorderAndMaskedCenterDoesNotCorruptNextFrame() throws IOException {
        EventBusImpl eventBus = new EventBusImpl(null, "sgb-published-mutation", false);
        EventBusImpl sgbBus = new EventBusImpl(null, "sgb-published-mutation-commands", false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, true);
        display.init(eventBus);
        AtomicReference<int[]> first = new AtomicReference<>();
        AtomicReference<int[]> next = new AtomicReference<>();
        AtomicBoolean mutate = new AtomicBoolean(true);
        int borderIndex = 3 + 2 * SGB_DISPLAY_WIDTH;
        int centerIndex = 48 + 40 * SGB_DISPLAY_WIDTH;
        int[] border = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
        int[] mask = new int[border.length];
        Arrays.fill(border, 0x7fff);
        mask[borderIndex] = 1;
        mask[centerIndex] = 1;
        eventBus.register(event -> {
            if (mutate.getAndSet(false)) {
                first.set(event.buffer().clone());
                event.buffer()[borderIndex] = 0x13572468;
                event.buffer()[centerIndex] = 0x24681357;
            } else {
                next.set(event.buffer().clone());
            }
        }, SgbDisplay.SgbFrameReadyEvent.class);
        try {
            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            eventBus.post(new Background.SgbBackgroundReadyEvent(border, mask));
            eventBus.post(new Display.DmgFrameReadyEvent(dmg));
            eventBus.post(new Display.DmgFrameReadyEvent(dmg));

            assertArrayEquals(first.get(), next.get());
            assertTrue(first.get()[borderIndex] != 0x13572468);
            assertTrue(first.get()[centerIndex] != 0x24681357);
        } finally {
            eventBus.close();
            sgbBus.close();
        }
    }

    @Test
    public void randomizedBorderAndCenterPixelsMatchTheScalarComposition() throws IOException {
        EventBusImpl eventBus = new EventBusImpl(null, "sgb-random", false);
        EventBusImpl sgbBus = new EventBusImpl(null, "sgb-random-commands", false);
        SgbDisplay display = new SgbDisplay(testRom(), sgbBus, true, true);
        display.init(eventBus);
        AtomicReference<int[]> rendered = new AtomicReference<>();
        eventBus.register(event -> rendered.set(event.buffer().clone()),
                SgbDisplay.SgbFrameReadyEvent.class);
        try {
            Random random = new Random(0x5347422d50455246L);
            int[] border = new int[SGB_DISPLAY_WIDTH * SGB_DISPLAY_HEIGHT];
            int[] mask = new int[border.length];
            for (int i = 0; i < border.length; i++) {
                border[i] = random.nextInt(0x8000);
                mask[i] = random.nextInt(16);
            }
            eventBus.post(new Background.SgbBackgroundReadyEvent(border, mask));

            int[] palettes = new int[Commands.TRANSFER_SIZE];
            int[][] selectedPalettes = new int[4][4];
            for (int palette = 0; palette < 512; palette++) {
                for (int color = 0; color < 4; color++) {
                    int value = random.nextInt(0x8000);
                    setLittleEndian16(palettes, palette * 8 + color * 2, value);
                    if (palette < 4) {
                        selectedPalettes[palette][color] = value;
                    }
                }
            }
            Commands.PalTrnCmd palTrn = (Commands.PalTrnCmd) Commands.toCommand(commandPacket(0x0b));
            palTrn.setDataTransfer(palettes);
            sgbBus.post(palTrn);
            int[] palSetPacket = commandPacket(0x0a);
            setLittleEndian16(palSetPacket, 1, 0);
            setLittleEndian16(palSetPacket, 3, 1);
            setLittleEndian16(palSetPacket, 5, 2);
            setLittleEndian16(palSetPacket, 7, 3);
            sgbBus.post(Commands.toCommand(palSetPacket));

            int[] attributes = new int[Commands.TRANSFER_SIZE];
            int[] paletteMap = new int[20 * (Display.DISPLAY_HEIGHT / 8)];
            for (int charId = 0; charId < paletteMap.length; charId++) {
                paletteMap[charId] = random.nextInt(4);
                attributes[charId / 4] |= paletteMap[charId] << (2 * (3 - charId % 4));
            }
            Commands.AttrTrnCmd attrTrn = (Commands.AttrTrnCmd) Commands.toCommand(commandPacket(0x15));
            attrTrn.setDataTransfer(attributes);
            sgbBus.post(attrTrn);
            sgbBus.post(Commands.toCommand(commandPacket(0x16)));

            int[] dmg = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
            for (int i = 0; i < dmg.length; i++) {
                dmg[i] = random.nextInt(4);
            }
            int[] masks = {0, 2, 3};
            for (boolean includeBorder : new boolean[]{true, false}) {
                eventBus.post(new SgbDisplay.SetSgbBorder(includeBorder));
                for (int fade : new int[]{0, 7, 31}) {
                    eventBus.post(new Background.SgbBackgroundFadeEvent(fade));
                    for (int screenMask : masks) {
                        int[] packet = commandPacket(0x17);
                        packet[1] = screenMask;
                        sgbBus.post(Commands.toCommand(packet));
                        eventBus.post(new Display.DmgFrameReadyEvent(dmg));
                        assertArrayEquals(
                                scalarCompose(border, mask, dmg, selectedPalettes, paletteMap,
                                        fade, screenMask, includeBorder),
                                rendered.get());
                    }
                }
            }
        } finally {
            eventBus.close();
            sgbBus.close();
        }
    }

    @Test
    public void SgbFramePayloadIsBorrowedAndOpaqueCopyIsFused() {
        int[] source = {0, 0x00010203, 0x7fffffff};
        SgbDisplay.SgbFrameReadyEvent event =
                new SgbDisplay.SgbFrameReadyEvent(source, false);
        assertTrue(event instanceof SynchronousBorrowedEvent);
        int[] target = {7, 7, 7, 7};
        event.copyToOpaqueArgb(target);
        assertArrayEquals(new int[]{0xff000000, 0xff010203, 0xffffffff, 7}, target);
        assertThrows(IllegalArgumentException.class,
                () -> event.copyToOpaqueArgb(new int[source.length - 1]));
    }

    private static Rom testRom() throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x147] = 0;
        return new Rom(bytes);
    }

    private static java.lang.reflect.Field privateField(String name) throws Exception {
        java.lang.reflect.Field field = SgbDisplay.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int[] scalarCompose(int[] border, int[] mask, int[] dmg,
                                       int[][] palettes, int[] paletteMap, int fade,
                                       int screenMask, boolean includeBorder) {
        int width = includeBorder ? SGB_DISPLAY_WIDTH : Display.DISPLAY_WIDTH;
        int height = includeBorder ? SGB_DISPLAY_HEIGHT : Display.DISPLAY_HEIGHT;
        int offsetX = includeBorder ? 0 : 48;
        int offsetY = includeBorder ? 0 : 40;
        int[] result = new int[width * height];
        for (int y = offsetY; y < offsetY + height; y++) {
            for (int x = offsetX; x < offsetX + width; x++) {
                int sgbIndex = x + y * SGB_DISPLAY_WIDTH;
                int dmgPixel = 0;
                if (x >= 48 && x < 208 && y >= 40 && y < 184) {
                    int dmgX = x - 48;
                    int dmgY = y - 40;
                    int p = dmg[dmgX + dmgY * Display.DISPLAY_WIDTH];
                    if (screenMask == 3) {
                        p = 0;
                    }
                    int paletteId = paletteMap[(dmgX / 8) + (dmgY / 8) * 20];
                    if (p == 0) {
                        paletteId = 0;
                    }
                    dmgPixel = palettes[paletteId][p];
                    if (screenMask == 2) {
                        dmgPixel = 0;
                    }
                }
                int value;
                if (mask[sgbIndex] == 0) {
                    value = Display.GbcFrameReadyEvent.translateGbcRgb(dmgPixel);
                } else {
                    int raw = border[sgbIndex];
                    int red = Math.max(0, (raw & 0x1f) - fade);
                    int green = Math.max(0, ((raw >> 5) & 0x1f) - fade);
                    int blue = Math.max(0, ((raw >> 10) & 0x1f) - fade);
                    value = Display.GbcFrameReadyEvent.translateGbcRgb(
                            red | (green << 5) | (blue << 10));
                }
                result[(x - offsetX) + (y - offsetY) * width] = value;
            }
        }
        return result;
    }

    private static void setLittleEndian16(int[] target, int offset, int value) {
        target[offset] = value & 0xff;
        target[offset + 1] = value >>> 8;
    }

    private static int[] commandPacket(int code) {
        int[] packet = new int[16];
        packet[0] = (code << 3) | 1;
        return packet;
    }
}
