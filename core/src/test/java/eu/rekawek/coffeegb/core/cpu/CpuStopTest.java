package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CpuStopTest {

    private static final int PROGRAM = 0x100;

    @Test
    public void heldJoypadLineMakesStopFallThroughWithoutConsumingKey1() {
        Ram memory = stopProgram(0xce);
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setByte(0xff4d, 0x01);
        Cpu cpu = cpu(memory, speedMode);

        runUntilNotExecutingStop(cpu);

        assertEquals(Cpu.State.OPCODE, cpu.getState());
        assertEquals(PROGRAM + 2, cpu.getRegisters().getPC());
        assertEquals(1, speedMode.getSpeedMode());
        assertEquals(0x7f, speedMode.getByte(0xff4d));
    }

    @Test
    public void joypadLineWakesStoppedCpuEvenWithInterruptsDisabled() {
        Ram memory = stopProgram(0xcf);
        TrackingDisplay display = new TrackingDisplay(false);
        Cpu cpu = cpu(memory, new InterruptManager(false), new SpeedMode(false), display);

        runUntilNotExecutingStop(cpu);
        assertEquals(Cpu.State.STOPPED, cpu.getState());
        assertFalse(display.isLcdEnabled());

        memory.setByte(0xff00, 0xce);
        tickMachineCycle(cpu);

        assertEquals(Cpu.State.OPCODE, cpu.getState());
        assertTrue(display.isLcdEnabled());
        // The wake-up cycle immediately executes the following NOP.
        assertEquals(PROGRAM + 3, cpu.getRegisters().getPC());
    }

    @Test
    public void dmgTimerAndSerialInterruptsDoNotWakeStoppedCpu() {
        assertDmgInterruptDoesNotWake(InterruptManager.InterruptType.Timer);
        assertDmgInterruptDoesNotWake(InterruptManager.InterruptType.Serial);
    }

    @Test
    public void cgbInterruptWakeBehaviorIsPreserved() {
        Ram memory = stopProgram(0xcf);
        InterruptManager interrupts = new InterruptManager(true);
        TrackingDisplay display = new TrackingDisplay(true);
        Cpu cpu = cpu(memory, interrupts, new SpeedMode(true), display);

        runUntilNotExecutingStop(cpu);
        assertEquals(Cpu.State.STOPPED, cpu.getState());
        assertFalse(display.isLcdEnabled());

        requestEnabledInterrupt(interrupts, InterruptManager.InterruptType.Timer);
        tickMachineCycle(cpu);

        assertEquals(Cpu.State.IRQ_WAIT_2, cpu.getState());
        assertTrue(display.isLcdEnabled());
    }

    @Test
    public void speedModeListenerUpdatesGpuMachineCycleTimingSynchronously() {
        SpeedMode speedMode = new SpeedMode(true);
        Gpu gpu = gpu(speedMode);

        assertEquals(4, gpu.getCpuMachineCycleDots());
        speedMode.setByte(0xff4d, 1);
        assertTrue(speedMode.onStop());
        assertEquals(2, gpu.getCpuMachineCycleDots());
        speedMode.setByte(0xff4d, 1);
        assertTrue(speedMode.onStop());
        assertEquals(4, gpu.getCpuMachineCycleDots());
    }

    private static Ram stopProgram(int joyp) {
        Ram memory = new Ram(0, 0x10000);
        memory.setByte(PROGRAM, 0x10);
        memory.setByte(PROGRAM + 1, 0x00);
        memory.setByte(0xff00, joyp);
        return memory;
    }

    private static Cpu cpu(AddressSpace memory, SpeedMode speedMode) {
        return cpu(memory, new InterruptManager(false), speedMode, new Display(false));
    }

    private static Cpu cpu(AddressSpace memory, InterruptManager interrupts, SpeedMode speedMode,
                           Display display) {
        Cpu cpu = new Cpu(memory, interrupts, null, speedMode, display);
        cpu.getRegisters().setPC(PROGRAM);
        return cpu;
    }

    private static void assertDmgInterruptDoesNotWake(InterruptManager.InterruptType type) {
        Ram memory = stopProgram(0xcf);
        InterruptManager interrupts = new InterruptManager(false);
        TrackingDisplay display = new TrackingDisplay(false);
        Cpu cpu = cpu(memory, interrupts, new SpeedMode(false), display);

        runUntilNotExecutingStop(cpu);
        assertEquals(Cpu.State.STOPPED, cpu.getState());
        assertFalse(display.isLcdEnabled());

        requestEnabledInterrupt(interrupts, type);
        for (int i = 0; i < 4; i++) {
            tickMachineCycle(cpu);
        }

        assertEquals(Cpu.State.STOPPED, cpu.getState());
        assertEquals(PROGRAM + 2, cpu.getRegisters().getPC());
        assertFalse(display.isLcdEnabled());
        assertTrue(interrupts.isInterruptFlagSet(type));
    }

    private static void requestEnabledInterrupt(InterruptManager interrupts,
                                                InterruptManager.InterruptType type) {
        interrupts.setByte(0xff0f, 0);
        interrupts.setByte(0xffff, 1 << type.ordinal());
        interrupts.enableInterrupts(false);
        interrupts.requestInterrupt(type);
    }

    private static Gpu gpu(SpeedMode speedMode) {
        Ram oam = new Ram(0xfe00, 0xa0);
        StatRegister stat = new StatRegister(new InterruptManager(true));
        Gpu gpu = new Gpu(new Display(true),
                new Dma(new Ram(0, 0x10000), oam, speedMode),
                oam,
                new VRamTransfer(EventBus.NULL_EVENT_BUS),
                stat,
                true,
                speedMode);
        stat.init(gpu);
        return gpu;
    }

    private static void runUntilNotExecutingStop(Cpu cpu) {
        for (int i = 0; i < 20 && cpu.getState() != Cpu.State.STOPPED
                && (cpu.getState() != Cpu.State.OPCODE || cpu.getRegisters().getPC() == PROGRAM); i++) {
            cpu.tick();
        }
    }

    private static void tickMachineCycle(Cpu cpu) {
        for (int i = 0; i < 4; i++) {
            cpu.tick();
        }
    }

    private static final class TrackingDisplay extends Display {

        private boolean lcdEnabled = true;

        private TrackingDisplay(boolean gbc) {
            super(gbc);
        }

        @Override
        public void enableLcd() {
            super.enableLcd();
            lcdEnabled = true;
        }

        @Override
        public void disableLcd() {
            super.disableLcd();
            lcdEnabled = false;
        }

        private boolean isLcdEnabled() {
            return lcdEnabled;
        }
    }
}
