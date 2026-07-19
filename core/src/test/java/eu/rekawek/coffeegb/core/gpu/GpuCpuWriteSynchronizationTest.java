package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;

public class GpuCpuWriteSynchronizationTest {

    @Test
    public void bgOnlyMode3PaletteWriteIsCpuVisibleBeforePixelCommit() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 100);
        fixture.gpu.setByte(GpuRegister.BGP.getAddress(), 0xe4);

        fixture.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0x1b);

        assertEquals(0x1b, fixture.gpu.getByte(GpuRegister.BGP.getAddress()));
        assertEquals(0xe4, fixture.gpu.getRegisters().get(GpuRegister.BGP));
        fixture.tick(4);
        assertEquals(0xe4, fixture.gpu.getRegisters().get(GpuRegister.BGP));
        fixture.tick();
        assertEquals(0x1b, fixture.gpu.getRegisters().get(GpuRegister.BGP));
    }

    @Test
    public void selectedSubregisterSlicesCrossThePixelDomain() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 100);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 0x80);

        fixture.gpu.setByteFromCpu(GpuRegister.SCX.getAddress(), 0x47);
        fixture.gpu.setByteFromCpu(GpuRegister.WX.getAddress(), 0x55);
        fixture.gpu.setByteFromCpu(0xff40, 0xb1);

        assertEquals(0x47, fixture.gpu.getByte(GpuRegister.SCX.getAddress()));
        assertEquals(0x40, fixture.gpu.getRegisters().get(GpuRegister.SCX));
        assertEquals(0x55, fixture.gpu.getByte(GpuRegister.WX.getAddress()));
        assertEquals(0, fixture.gpu.getRegisters().get(GpuRegister.WX));
        assertEquals(0xb1, fixture.gpu.getByte(0xff40));
        assertEquals(0x91, fixture.gpu.getLcdc().get());

        fixture.tick(5);

        assertEquals(0x47, fixture.gpu.getRegisters().get(GpuRegister.SCX));
        assertEquals(0x55, fixture.gpu.getRegisters().get(GpuRegister.WX));
        assertEquals(0xb1, fixture.gpu.getLcdc().get());
    }

    @Test
    public void lineZeroAndObjectPipelinePaletteWritesNeedNoExtraDelay() {
        Fixture lineZero = new Fixture(false);
        lineZero.advanceTo(0, 100);
        lineZero.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0x1b);
        assertEquals(0x1b, lineZero.gpu.getRegisters().get(GpuRegister.BGP));

        Fixture objectLine = new Fixture(true);
        objectLine.advanceTo(1, 100);
        objectLine.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0xe4);
        assertEquals(0xe4, objectLine.gpu.getRegisters().get(GpuRegister.BGP));
    }

    @Test
    public void disabledObjectPipelineFeedsPaletteOutputDirectly() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 100);
        fixture.gpu.setByte(0xff40, 0x91);

        fixture.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0x1b);

        assertEquals(0x1b, fixture.gpu.getRegisters().get(GpuRegister.BGP));
    }

    @Test
    public void enabledWindowPathUsesPaletteOutputLatch() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 100);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.gpu.setByte(GpuRegister.BGP.getAddress(), 0xe4);

        fixture.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0x1b);

        assertEquals(0xe4, fixture.gpu.getRegisters().get(GpuRegister.BGP));
        fixture.tick(5);
        assertEquals(0x1b, fixture.gpu.getRegisters().get(GpuRegister.BGP));
    }

    @Test
    public void pendingPixelWriteSurvivesMementoRestore() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 100);
        fixture.gpu.setByte(GpuRegister.BGP.getAddress(), 0xe4);
        fixture.gpu.setByteFromCpu(GpuRegister.BGP.getAddress(), 0x1b);
        fixture.tick(2);
        var saved = fixture.gpu.saveToMemento();

        fixture.tick(3);
        assertEquals(0x1b, fixture.gpu.getRegisters().get(GpuRegister.BGP));

        fixture.gpu.restoreFromMemento(saved);
        assertEquals(0x1b, fixture.gpu.getByte(GpuRegister.BGP.getAddress()));
        assertEquals(0xe4, fixture.gpu.getRegisters().get(GpuRegister.BGP));
        fixture.tick(2);
        assertEquals(0xe4, fixture.gpu.getRegisters().get(GpuRegister.BGP));
        fixture.tick();
        assertEquals(0x1b, fixture.gpu.getRegisters().get(GpuRegister.BGP));
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat;

        private final Gpu gpu;

        private Fixture(boolean objectOnLineOne) {
            if (objectOnLineOne) {
                oam.setByte(0xfe00, 17);
                oam.setByte(0xfe01, 8);
            }
            InterruptManager interrupts = new InterruptManager(false);
            stat = new StatRegister(interrupts);
            SpeedMode speedMode = new SpeedMode(false);
            gpu = new Gpu(
                    new Display(false),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    false,
                    speedMode);
            stat.init(gpu);
            gpu.setByte(0xff40, 0x93);
        }

        private void advanceTo(int line, int ticksInLine) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != ticksInLine) {
                tick();
            }
        }

        private void tick(int count) {
            for (int i = 0; i < count; i++) {
                tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
