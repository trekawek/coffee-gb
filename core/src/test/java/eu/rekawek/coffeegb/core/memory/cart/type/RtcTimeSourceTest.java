package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RtcTimeSourceTest {

    @Test
    public void mbc3OwnsSubsecondLatchHaltAndPauseStateWithoutOwningClockService() throws Exception {
        VirtualTimeSource time = new VirtualTimeSource(120_000);
        Mbc3 mbc3 = (Mbc3) cartridge(0x13, 0x03, new MemoryBattery(new byte[0]), time)
                .getMemoryController();
        enableMbc3(mbc3);
        writeMbc3(mbc3, 0x08, 7);
        for (int i = 0; i < Gameboy.TICKS_PER_SEC / 4; i++) {
            mbc3.tick();
        }
        mbc3.setByte(0x6000, 1); // latch at 7.25 seconds
        mbc3.setClockPaused(true);
        time.forward(1500, TimeUnit.MILLISECONDS);
        ComponentState<MemoryController> captured = mbc3.captureState();
        var capturedRuntime = mbc3.captureRtcRuntimeState();

        Object capturedClock = component(captured, "clockMemento");
        assertEquals(8, component(capturedClock, "seconds"));
        assertEquals(3L * Gameboy.TICKS_PER_SEC / 4, component(capturedClock, "subSecondTicks"));
        assertTrue((boolean) component(capturedClock, "latched"));
        assertEquals(7, component(capturedClock, "latchedSeconds"));
        assertTrue(capturedRuntime.emulationPaused());

        writeMbc3(mbc3, 0x08, 33);
        writeMbc3(mbc3, 0x0c, 0x40);
        mbc3.setClockPaused(false);
        mbc3.restoreState(captured);
        mbc3.restoreRtcRuntimeState(capturedRuntime);

        time.forward(250, TimeUnit.MILLISECONDS);
        mbc3.setClockPaused(false);
        Object continuedClock = component(mbc3.captureState(), "clockMemento");
        assertEquals(9, component(continuedClock, "seconds"));
        assertEquals(0L, component(continuedClock, "subSecondTicks"));
        assertEquals(7, readMbc3(mbc3, 0x08)); // the captured latch remains authoritative
        assertFalse(mbc3.captureRtcRuntimeState().emulationPaused());

        writeMbc3(mbc3, 0x0c, 0x40);
        ComponentState<MemoryController> halted = mbc3.captureState();
        writeMbc3(mbc3, 0x0c, 0);
        mbc3.restoreState(halted);
        assertTrue((boolean) component(component(mbc3.captureState(), "clockMemento"), "halt"));
    }

    @Test
    public void mbc3BatteryReferenceUsesInjectedVirtualTime() throws Exception {
        VirtualTimeSource time = new VirtualTimeSource(120_000);
        MemoryBattery battery = new MemoryBattery(new byte[0]);
        Mbc3 first = (Mbc3) cartridge(0x13, 0x03, battery, time).getMemoryController();
        enableMbc3(first);
        writeMbc3(first, 0x08, 10);
        first.flushRam();

        time.forward(5, TimeUnit.SECONDS);
        Mbc3 restored = (Mbc3) cartridge(0x13, 0x03, battery, time).getMemoryController();
        assertEquals(15, component(component(restored.captureState(), "clockMemento"), "seconds"));
    }

    @Test
    public void huc3AdvancesRestoresAndPersistsAgainstInjectedTime() throws Exception {
        VirtualTimeSource time = new VirtualTimeSource(120_000);
        MemoryBattery battery = new MemoryBattery(new byte[0]);
        Huc3 huc3 = (Huc3) cartridge(0xfe, 0x03, battery, time).getMemoryController();

        time.forward(2, TimeUnit.MINUTES);
        readHuc3Register(huc3, 0);
        ComponentState<MemoryController> captured = huc3.captureState();
        assertEquals(2, component(captured, "minutes"));

        time.forward(3, TimeUnit.MINUTES);
        readHuc3Register(huc3, 0);
        assertEquals(5, component(huc3.captureState(), "minutes"));
        huc3.restoreState(captured);
        readHuc3Register(huc3, 0);
        assertEquals(5, component(huc3.captureState(), "minutes"));

        huc3.flushRam();
        time.forward(2, TimeUnit.MINUTES);
        Huc3 fromBattery = (Huc3) cartridge(0xfe, 0x03, battery, time).getMemoryController();
        assertEquals(7, component(fromBattery.captureState(), "minutes"));
    }

    @Test
    public void tama5AdvancesRestoresAndPersistsAgainstInjectedTime() throws Exception {
        VirtualTimeSource time = new VirtualTimeSource(120_000);
        MemoryBattery battery = new MemoryBattery(new byte[0]);
        Tama5 tama5 = (Tama5) cartridge(0xfd, 0, battery, time).getMemoryController();

        time.forward(65, TimeUnit.SECONDS);
        assertEquals(1, readTama5Minutes(tama5));
        ComponentState<MemoryController> captured = tama5.captureState();

        time.forward(125, TimeUnit.SECONDS);
        assertEquals(3, readTama5Minutes(tama5));
        tama5.restoreState(captured);
        assertEquals(3, readTama5Minutes(tama5));

        tama5.flushRam();
        time.forward(2, TimeUnit.MINUTES);
        Tama5 fromBattery = (Tama5) cartridge(0xfd, 0, battery, time).getMemoryController();
        assertEquals(5, readTama5Minutes(fromBattery));
    }

    @Test
    public void mbc3TickOscillatorUsesTheOwningCustomClock() throws Exception {
        VirtualTimeSource time = new VirtualTimeSource(120_000);
        ClockSpec clock = new ClockSpec(1_000, 10, 1);
        Mbc3 mbc3 = (Mbc3) cartridge(
                0x13, 0x03, new MemoryBattery(new byte[0]), time, clock).getMemoryController();
        enableMbc3(mbc3);

        for (int i = 0; i < 999; i++) {
            mbc3.tick();
        }
        assertEquals(0, component(component(mbc3.captureState(), "clockMemento"), "seconds"));
        mbc3.tick();
        assertEquals(1, component(component(mbc3.captureState(), "clockMemento"), "seconds"));
    }

    private static Cartridge cartridge(int type, int ramSize, MemoryBattery battery,
                                       VirtualTimeSource time) throws IOException {
        return cartridge(type, ramSize, battery, time, ClockSpec.LEGACY);
    }

    private static Cartridge cartridge(int type, int ramSize, MemoryBattery battery,
                                       VirtualTimeSource time, ClockSpec clock) throws IOException {
        byte[] data = new byte[0x200000];
        data[0x147] = (byte) type;
        data[0x148] = 0x06;
        data[0x149] = (byte) ramSize;
        return new Cartridge(new Rom(data), battery, time, clock);
    }

    private static void enableMbc3(Mbc3 mbc3) {
        mbc3.setByte(0x0000, 0x0a);
    }

    private static void writeMbc3(Mbc3 mbc3, int register, int value) {
        mbc3.setByte(0x4000, register);
        mbc3.setByte(0xa000, value);
    }

    private static int readMbc3(Mbc3 mbc3, int register) {
        mbc3.setByte(0x4000, register);
        return mbc3.getByte(0xa000);
    }

    private static int readHuc3Register(Huc3 huc3, int register) {
        huc3.setByte(0x0000, 0x0b);
        huc3.setByte(0xa000, 0x40 | (register & 0x0f));
        huc3.setByte(0xa000, 0x50 | ((register >> 4) & 0x0f));
        huc3.setByte(0xa000, 0x10);
        huc3.setByte(0x0000, 0x0c);
        return huc3.getByte(0xa000);
    }

    private static int readTama5Minutes(Tama5 tama5) {
        writeTama5Register(tama5, 0x6, 0x4); // command space
        writeTama5Register(tama5, 0x7, 0x6); // minute-read command
        tama5.setByte(0xa001, 0xc);
        return tama5.getByte(0xa000) & 0x0f;
    }

    private static void writeTama5Register(Tama5 tama5, int register, int value) {
        tama5.setByte(0xa001, register);
        tama5.setByte(0xa000, value);
    }

    private static Object component(Object record, String name)
            throws InvocationTargetException, IllegalAccessException {
        var component = java.util.Arrays.stream(record.getClass().getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow();
        component.getAccessor().setAccessible(true);
        return component.getAccessor().invoke(record);
    }
}
