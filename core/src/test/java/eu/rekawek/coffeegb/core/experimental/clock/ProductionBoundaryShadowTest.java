package eu.rekawek.coffeegb.core.experimental.clock;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.core.experimental.clock.ProductionBoundaryShadow.SERIAL_MASK;
import static eu.rekawek.coffeegb.core.experimental.clock.ProductionBoundaryShadow.TIMER_MASK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Differential proof for the passive production-to-signal-scheduler boundary. */
public class ProductionBoundaryShadowTest {

    private static final int IF = 0xff0f;

    private static final int IE = 0xffff;

    @Test
    public void captureIsPassiveAndFindsTheCpuAcknowledgeBoundaryFromOldState() {
        CpuFixture fixture = new CpuFixture(false);
        ProductionBoundaryShadow.Snapshot before = fixture.advanceToAcknowledgeBoundary(TIMER_MASK);

        ComponentState<Timer> timerState = fixture.timer.captureState();
        ComponentState<SerialPort> serialState = fixture.serial.captureState();
        ComponentState<InterruptManager> interruptState = fixture.interrupts.captureState();
        Cpu.State cpuState = fixture.cpu.getState();
        int cpuPhase = fixture.cpu.getDebugMachineCycle();
        int pc = fixture.cpu.getRegisters().getPC();
        int sp = fixture.cpu.getRegisters().getSP();

        ProductionBoundaryShadow.Snapshot second = fixture.capture();

        assertEquals(before, second);
        assertEquals(timerState, fixture.timer.captureState());
        assertEquals(serialState, fixture.serial.captureState());
        assertEquals(interruptState, fixture.interrupts.captureState());
        assertEquals(cpuState, fixture.cpu.getState());
        assertEquals(cpuPhase, fixture.cpu.getDebugMachineCycle());
        assertEquals(pc, fixture.cpu.getRegisters().getPC());
        assertEquals(sp, fixture.cpu.getRegisters().getSP());
        assertTrue(before.cpu().productionAcknowledgeStartsOnNextTick(before.clock()));

        ProductionBoundaryShadow.CpuAcknowledgeDelay acknowledge =
                new ProductionBoundaryShadow.CpuAcknowledgeDelay();
        acknowledge.armAfterProductionClearBoundary(before, TIMER_MASK);

        fixture.cpu.tick();
        assertEquals(Cpu.State.IRQ_JUMP, fixture.cpu.getState());
        assertFalse(fixture.interrupts.isInterruptFlagSet(
                InterruptManager.InterruptType.Timer));

        assertEquals(0, acknowledge.stepCpuClock());
        assertEquals(0, acknowledge.stepCpuClock());
        assertEquals(TIMER_MASK, acknowledge.stepCpuClock());
        assertFalse(acknowledge.pending());
    }

    @Test
    public void currentTimerAndSerialBoundariesDriveOneAtomicOldStateIfCapture() {
        CpuFixture fixture = new CpuFixture(true);
        fixture.interrupts.setByte(IF, 0);
        fixture.interrupts.setByte(IE, TIMER_MASK | SERIAL_MASK);

        // One CPU clock remains before the reload/INT_TIMER edge.
        fixture.timer.restoreState(new Timer.TimerState(
                0, 0, 0x42, 0, false, true, 3, false,
                0, Integer.MAX_VALUE, false, false, false));

        fixture.serial.setByte(0xff02, 0x83);
        for (int tick = 0; tick < 127; tick++) {
            fixture.serial.tick();
        }
        assertEquals(7, fixture.serial.captureDebugSerialInspection().receivedBits());

        ProductionBoundaryShadow.Snapshot oldState = fixture.capture();
        assertEquals(TIMER_MASK,
                oldState.timer().naturalRequestOnNextMasterTick(oldState.clock()));
        assertEquals(SERIAL_MASK,
                oldState.serial().naturalRequestOnNextMasterTick(oldState.clock()));

        int timerThenSerial = resolve(oldState,
                ProductionBoundaryShadow.timerRequestDriver(),
                ProductionBoundaryShadow.serialRequestDriver());
        int serialThenTimer = resolve(oldState,
                ProductionBoundaryShadow.serialRequestDriver(),
                ProductionBoundaryShadow.timerRequestDriver());
        assertEquals(TIMER_MASK | SERIAL_MASK, timerThenSerial);
        assertEquals(timerThenSerial, serialThenTimer);

        // Today's Gameboy order is Timer -> CPU -> Serial. With no acknowledge active, the
        // completed production tick must equal the atomic old-state capture above.
        fixture.timer.tick();
        fixture.cpu.tick();
        fixture.serial.tick();
        assertEquals(timerThenSerial,
                fixture.interrupts.getDebugInterruptFlags() & (TIMER_MASK | SERIAL_MASK));
    }

    @Test
    public void explicitAcknowledgeAndRequestWiresAreClearDominantInEveryDriveOrder() {
        CpuFixture fixture = new CpuFixture(true);
        fixture.interrupts.setByte(IF, SERIAL_MASK);
        ProductionBoundaryShadow.Snapshot oldState = fixture.capture();

        int requestThenAcknowledge = resolve(oldState,
                ProductionBoundaryShadow.requestDriver(SERIAL_MASK),
                ProductionBoundaryShadow.acknowledgeDriver(SERIAL_MASK));
        int acknowledgeThenRequest = resolve(oldState,
                ProductionBoundaryShadow.acknowledgeDriver(SERIAL_MASK),
                ProductionBoundaryShadow.requestDriver(SERIAL_MASK));

        assertEquals(0, requestThenAcknowledge & SERIAL_MASK);
        assertEquals(requestThenAcknowledge, acknowledgeThenRequest);
    }

    @Test
    public void delayedCpuWireReplaysEveryLegacyTimerAndSerialForecastLead() {
        for (boolean cgb : new boolean[]{false, true}) {
            int acknowledgeDelay = cgb ? 8 : 3;
            for (int distance = 1; distance <= acknowledgeDelay + 3; distance++) {
                String prefix = (cgb ? "CGB" : "DMG") + " distance " + distance;
                assertEquals(prefix + " timer",
                        distance > acknowledgeDelay,
                        replayTimerForecast(cgb, distance));
                assertEquals(prefix + " serial",
                        distance > acknowledgeDelay,
                        replaySerialForecast(cgb, distance));
            }
        }
    }

    @Test
    public void migrationBlockersNameEverySignalStillHiddenByThePassiveBoundary() {
        assertEquals(EnumSet.allOf(ProductionBoundaryShadow.MissingSignal.class),
                ProductionBoundaryShadow.migrationBlockers());
        assertTrue(ProductionBoundaryShadow.migrationBlockers().contains(
                ProductionBoundaryShadow.MissingSignal.CPU_BUS_T_STATE));
        assertTrue(ProductionBoundaryShadow.migrationBlockers().contains(
                ProductionBoundaryShadow.MissingSignal.INTERRUPT_SELECTED_SOURCE_LATCH));
        assertTrue(ProductionBoundaryShadow.migrationBlockers().contains(
                ProductionBoundaryShadow.MissingSignal.DOUBLE_SPEED_CPU_SUBEDGE));
    }

    @Test
    public void phaseProtocolRejectsReadingOrCommittingAnUnresolvedBoundary() {
        CpuFixture fixture = new CpuFixture(false);
        ProductionBoundaryShadow.Cycle cycle =
                new ProductionBoundaryShadow.Cycle(fixture.capture());

        assertThrows(IllegalStateException.class, cycle::capture);
        assertThrows(IllegalStateException.class, cycle::commit);
        cycle.resolve();
        assertThrows(IllegalStateException.class,
                () -> cycle.drive(ProductionBoundaryShadow.requestDriver(TIMER_MASK)));
        cycle.capture();
        cycle.commit();
        assertEquals(ProductionBoundaryShadow.Phase.COMMIT, cycle.phase());
    }

    private static boolean replayTimerForecast(boolean cgb, int requestDistance) {
        SpeedMode productionSpeed = new SpeedMode(cgb);
        SpeedMode rawSpeed = new SpeedMode(cgb);
        InterruptManager productionInterrupts = new InterruptManager(cgb);
        InterruptManager rawInterrupts = new InterruptManager(cgb);
        Timer production = new Timer(productionInterrupts, productionSpeed);
        Timer raw = new Timer(rawInterrupts, rawSpeed);
        Timer.TimerState initial = timerStateAtInterruptDistance(requestDistance);
        production.restoreState(initial);
        raw.restoreState(initial);
        productionInterrupts.setByte(IF, 0);
        rawInterrupts.setByte(IF, 0);

        productionInterrupts.requestInterrupt(InterruptManager.InterruptType.Timer);
        productionInterrupts.clearInterrupt(InterruptManager.InterruptType.Timer);

        CpuFixture cpu = new CpuFixture(cgb);
        ProductionBoundaryShadow.Snapshot cpuBoundary =
                cpu.advanceToAcknowledgeBoundary(TIMER_MASK);
        ProductionBoundaryShadow.CpuAcknowledgeDelay acknowledge =
                new ProductionBoundaryShadow.CpuAcknowledgeDelay();
        acknowledge.armAfterProductionClearBoundary(cpuBoundary, TIMER_MASK);

        int shadowFlags = TIMER_MASK;
        int observedRequestClock = -1;
        int horizon = Math.max(requestDistance, cgb ? 8 : 3) + 4;
        for (int clock = 1; clock <= horizon; clock++) {
            ProductionBoundaryShadow.TimerSample oldTimer =
                    ProductionBoundaryShadow.TimerSample.capture(raw);
            int inferredRequest = oldTimer.naturalRequestOnNextMasterTick(
                    ProductionBoundaryShadow.ClockSample.capture(rawSpeed));
            boolean oldIf = rawInterrupts.isInterruptFlagSet(
                    InterruptManager.InterruptType.Timer);
            raw.tick();
            boolean newIf = rawInterrupts.isInterruptFlagSet(
                    InterruptManager.InterruptType.Timer);
            int actualRequest = !oldIf && newIf ? TIMER_MASK : 0;
            assertEquals("timer raw wire at clock " + clock, actualRequest, inferredRequest);
            if (actualRequest != 0) {
                observedRequestClock = clock;
            }

            production.tick();
            int acknowledgeWire = acknowledge.stepCpuClock();
            shadowFlags = resolve(withFlags(cpuBoundary, shadowFlags),
                    ProductionBoundaryShadow.requestDriver(inferredRequest),
                    ProductionBoundaryShadow.acknowledgeDriver(acknowledgeWire));
        }

        assertEquals("timer request clock", requestDistance, observedRequestClock);
        assertEquals((cgb ? "CGB" : "DMG") + " timer distance " + requestDistance,
                (shadowFlags & TIMER_MASK) != 0,
                productionInterrupts.isInterruptFlagSet(
                        InterruptManager.InterruptType.Timer));
        return (shadowFlags & TIMER_MASK) != 0;
    }

    private static boolean replaySerialForecast(boolean cgb, int requestDistance) {
        ComponentState<SerialPort> initial = serialStateAtInterruptDistance(cgb, requestDistance);
        SpeedMode productionSpeed = new SpeedMode(cgb);
        SpeedMode rawSpeed = new SpeedMode(cgb);
        InterruptManager productionInterrupts = new InterruptManager(cgb);
        InterruptManager rawInterrupts = new InterruptManager(cgb);
        SerialPort production = new SerialPort(productionInterrupts, cgb, productionSpeed);
        SerialPort raw = new SerialPort(rawInterrupts, cgb, rawSpeed);
        production.init(new CountingEndpoint());
        raw.init(new CountingEndpoint());
        production.restoreState(initial);
        raw.restoreState(initial);
        productionInterrupts.setByte(IF, 0);
        rawInterrupts.setByte(IF, 0);

        productionInterrupts.requestInterrupt(InterruptManager.InterruptType.Serial);
        productionInterrupts.clearInterrupt(InterruptManager.InterruptType.Serial);

        CpuFixture cpu = new CpuFixture(cgb);
        ProductionBoundaryShadow.Snapshot cpuBoundary =
                cpu.advanceToAcknowledgeBoundary(SERIAL_MASK);
        ProductionBoundaryShadow.CpuAcknowledgeDelay acknowledge =
                new ProductionBoundaryShadow.CpuAcknowledgeDelay();
        acknowledge.armAfterProductionClearBoundary(cpuBoundary, SERIAL_MASK);

        int shadowFlags = SERIAL_MASK;
        int observedRequestClock = -1;
        int horizon = Math.max(requestDistance, cgb ? 8 : 3) + 4;
        for (int clock = 1; clock <= horizon; clock++) {
            ProductionBoundaryShadow.SerialSample oldSerial =
                    ProductionBoundaryShadow.SerialSample.capture(raw);
            int inferredRequest = oldSerial.naturalRequestOnNextMasterTick(
                    ProductionBoundaryShadow.ClockSample.capture(rawSpeed));
            boolean oldIf = rawInterrupts.isInterruptFlagSet(
                    InterruptManager.InterruptType.Serial);
            raw.tick();
            boolean newIf = rawInterrupts.isInterruptFlagSet(
                    InterruptManager.InterruptType.Serial);
            int actualRequest = !oldIf && newIf ? SERIAL_MASK : 0;
            assertEquals("serial raw wire at clock " + clock, actualRequest, inferredRequest);
            if (actualRequest != 0) {
                observedRequestClock = clock;
            }

            production.tick();
            int acknowledgeWire = acknowledge.stepCpuClock();
            shadowFlags = resolve(withFlags(cpuBoundary, shadowFlags),
                    ProductionBoundaryShadow.requestDriver(inferredRequest),
                    ProductionBoundaryShadow.acknowledgeDriver(acknowledgeWire));
        }

        assertEquals("serial request clock", requestDistance, observedRequestClock);
        assertEquals((cgb ? "CGB" : "DMG") + " serial distance " + requestDistance,
                (shadowFlags & SERIAL_MASK) != 0,
                productionInterrupts.isInterruptFlagSet(
                        InterruptManager.InterruptType.Serial));
        return (shadowFlags & SERIAL_MASK) != 0;
    }

    private static Timer.TimerState timerStateAtInterruptDistance(int clocks) {
        if (clocks <= 3) {
            return new Timer.TimerState(
                    0, 0x05, 0x42, 0, false, true, 4 - clocks, false,
                    0, Integer.MAX_VALUE, false, false, false);
        }
        int clocksToFallingEdge = clocks - 3;
        int divider = (16 - clocksToFallingEdge) & 0x0f;
        int tac = 0x05;
        boolean timerInput = (divider & 0x08) != 0;
        return new Timer.TimerState(
                divider, tac, 0x42, 0xff, timerInput, false, 0, false,
                0, Integer.MAX_VALUE, false, false, false);
    }

    private static ComponentState<SerialPort> serialStateAtInterruptDistance(
            boolean cgb, int clocks) {
        SpeedMode speed = new SpeedMode(cgb);
        InterruptManager interrupts = new InterruptManager(cgb);
        SerialPort serial = new SerialPort(interrupts, cgb, speed);
        CountingEndpoint endpoint = new CountingEndpoint();
        serial.init(endpoint);
        serial.setByte(0xff02, cgb ? 0x83 : 0x81);
        while (endpoint.sentBits < 7) {
            serial.tick();
        }
        int period = cgb ? 16 : 512;
        for (int i = 0; i < period - clocks; i++) {
            serial.tick();
        }
        assertEquals(7, endpoint.sentBits);
        return serial.captureState();
    }

    private static int resolve(
            ProductionBoundaryShadow.Snapshot oldState,
            ProductionBoundaryShadow.Driver... drivers) {
        ProductionBoundaryShadow.Cycle cycle = new ProductionBoundaryShadow.Cycle(oldState);
        for (ProductionBoundaryShadow.Driver driver : drivers) {
            cycle.drive(driver);
        }
        cycle.resolve();
        cycle.capture();
        return cycle.commit();
    }

    private static ProductionBoundaryShadow.Snapshot withFlags(
            ProductionBoundaryShadow.Snapshot template, int flags) {
        return new ProductionBoundaryShadow.Snapshot(
                template.clock(), template.cpu(), template.timer(), template.serial(),
                new ProductionBoundaryShadow.InterruptSample(
                        flags, template.interrupts().enable(), template.interrupts().ime()));
    }

    private static final class CpuFixture {

        private final SpeedMode speedMode;

        private final InterruptManager interrupts;

        private final Timer timer;

        private final SerialPort serial;

        private final Cpu cpu;

        private final Ram ram = new Ram(0, 0x10000);

        private CpuFixture(boolean cgb) {
            speedMode = new SpeedMode(cgb);
            interrupts = new InterruptManager(cgb);
            timer = new Timer(interrupts, speedMode);
            serial = new SerialPort(interrupts, cgb, speedMode);
            AddressSpace bus = new AddressSpace() {
                @Override
                public boolean accepts(int address) {
                    return true;
                }

                @Override
                public void setByte(int address, int value) {
                    if (interrupts.accepts(address)) {
                        interrupts.setByte(address, value);
                    } else {
                        ram.setByte(address, value);
                    }
                }

                @Override
                public int getByte(int address) {
                    return interrupts.accepts(address)
                            ? interrupts.getByte(address)
                            : ram.getByte(address);
                }
            };
            cpu = new Cpu(bus, interrupts, null, speedMode, new Display(cgb), timer);
            cpu.getRegisters().setPC(0x0100);
            cpu.getRegisters().setSP(0xfffe);
            ram.setByte(0x0100, 0x00);
            interrupts.setByte(IF, 0);
        }

        private ProductionBoundaryShadow.Snapshot capture() {
            return ProductionBoundaryShadow.Snapshot.capture(
                    cpu, timer, serial, interrupts, speedMode);
        }

        private ProductionBoundaryShadow.Snapshot advanceToAcknowledgeBoundary(int mask) {
            interrupts.setByte(IF, 0);
            interrupts.setByte(IE, mask);
            interrupts.enableInterrupts(false);
            InterruptManager.InterruptType source = Integer.numberOfTrailingZeros(mask) == 2
                    ? InterruptManager.InterruptType.Timer
                    : InterruptManager.InterruptType.Serial;
            interrupts.requestInterrupt(source);

            tickMachineCycle();
            assertEquals(Cpu.State.IRQ_WAIT_2, cpu.getState());
            tickMachineCycle();
            assertEquals(Cpu.State.IRQ_PUSH_1, cpu.getState());
            tickMachineCycle();
            assertEquals(Cpu.State.IRQ_PUSH_2, cpu.getState());
            for (int i = 0; i < 3; i++) {
                cpu.tick();
            }
            ProductionBoundaryShadow.Snapshot result = capture();
            assertTrue(result.cpu().productionAcknowledgeStartsOnNextTick(result.clock()));
            return result;
        }

        private void tickMachineCycle() {
            for (int tick = 0; tick < 4; tick++) {
                cpu.tick();
            }
        }
    }

    private static final class CountingEndpoint implements SerialEndpoint {

        private int sentBits;

        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            sentBits++;
            return 1;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
        }
    }
}
