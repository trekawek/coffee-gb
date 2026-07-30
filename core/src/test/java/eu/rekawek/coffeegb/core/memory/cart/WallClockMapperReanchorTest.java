package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.type.Huc3;
import eu.rekawek.coffeegb.core.memory.cart.type.Tama5;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WallClockMapperReanchorTest {

    @Test
    public void wallClockRuntimeRejectsNegativeAndMismatchedBoundaries() throws IOException {
        assertThrows(IllegalArgumentException.class, () ->
                new MemoryController.WallClockRuntimeState(
                        MemoryController.WallClockKind.HUC3, -1));

        Cartridge huc3 = cartridge(0xfe, new VirtualTimeSource());
        assertThrows(IllegalArgumentException.class, () ->
                huc3.validateWallClockRuntimeState(
                        new MemoryController.WallClockRuntimeState(
                                MemoryController.WallClockKind.TAMA5, 0)));
    }

    @Test
    public void huc3RestoreKeepsUnlatchedCheckpointTimeAndDiscardsTheFuture() throws IOException {
        VirtualTimeSource time = new VirtualTimeSource();
        Cartridge cartridge = cartridge(0xfe, time);
        Huc3 mapper = (Huc3) cartridge.getMemoryController();

        time.forward(2, TimeUnit.MINUTES);
        // Capture before any RTC command has latched the elapsed host time into mapper state.
        ComponentState<MemoryController> checkpoint = mapper.captureState();
        MemoryController.WallClockRuntimeState boundary =
                cartridge.captureWallClockRuntimeState();

        time.forward(10, TimeUnit.MINUTES);
        assertEquals(12, huc3Minutes(mapper));
        mapper.restoreState(checkpoint);
        cartridge.restoreWallClockRuntimeState(boundary);
        cartridge.reanchorRtcEmulationPause(true);
        assertEquals(2, huc3Minutes(mapper));

        time.forward(1, TimeUnit.MINUTES);
        assertEquals(3, huc3Minutes(mapper));
    }

    @Test
    public void tama5RestoreKeepsUnlatchedCheckpointTimeAndDiscardsTheFuture() throws IOException {
        VirtualTimeSource time = new VirtualTimeSource();
        Cartridge cartridge = cartridge(0xfd, time);
        Tama5 mapper = (Tama5) cartridge.getMemoryController();

        time.forward(2, TimeUnit.MINUTES);
        // Capture before any RTC port read has latched the elapsed host time into mapper state.
        ComponentState<MemoryController> checkpoint = mapper.captureState();
        MemoryController.WallClockRuntimeState boundary =
                cartridge.captureWallClockRuntimeState();

        time.forward(10, TimeUnit.MINUTES);
        assertEquals(12, tama5Minutes(mapper));
        mapper.restoreState(checkpoint);
        cartridge.restoreWallClockRuntimeState(boundary);
        cartridge.reanchorRtcEmulationPause(true);
        assertEquals(2, tama5Minutes(mapper));

        time.forward(1, TimeUnit.MINUTES);
        assertEquals(3, tama5Minutes(mapper));
    }

    private static Cartridge cartridge(int type, VirtualTimeSource time) throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x147] = (byte) type;
        bytes[0x149] = 0x03;
        return new Cartridge(new Rom(bytes), Battery.NULL_BATTERY, time);
    }

    private static int huc3Minutes(Huc3 mapper) {
        mapper.setByte(0x0000, 0x0b);
        mapper.setByte(0xa000, 0x40);
        mapper.setByte(0xa000, 0x50);
        mapper.setByte(0xa000, 0x10);
        mapper.setByte(0x0000, 0x0c);
        int low = mapper.getByte(0xa000) & 0x0f;
        mapper.setByte(0x0000, 0x0b);
        mapper.setByte(0xa000, 0x10);
        mapper.setByte(0x0000, 0x0c);
        int middle = mapper.getByte(0xa000) & 0x0f;
        return middle * 16 + low;
    }

    private static int tama5Minutes(Tama5 mapper) {
        tama5WriteRegister(mapper, 0x4, 0x6);
        tama5WriteRegister(mapper, 0x5, 0x0);
        tama5WriteRegister(mapper, 0x6, 0x4);
        tama5WriteRegister(mapper, 0x7, 0x6);
        mapper.setByte(0xa001, 0x0c);
        int low = mapper.getByte(0xa000) & 0x0f;
        mapper.setByte(0xa001, 0x0d);
        int high = mapper.getByte(0xa000) & 0x0f;
        return high * 10 + low;
    }

    private static void tama5WriteRegister(Tama5 mapper, int register, int value) {
        mapper.setByte(0xa001, register);
        mapper.setByte(0xa000, value);
    }
}
