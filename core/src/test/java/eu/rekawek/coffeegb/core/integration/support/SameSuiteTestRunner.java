package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Registers;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.IOException;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.isByteSequenceAtPc;

/** Runs SameSuite ROMs through their automated register-based result protocol. */
public class SameSuiteTestRunner {

    private static final long MAX_TICKS = 50_000_000L;

    private final Gameboy gb;

    private final AddressSpace memory;

    private final Registers registers;

    public SameSuiteTestRunner(File romFile, GameboyType gameboyType) throws IOException {
        EventBus eventBus = new EventBusImpl();
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false);
        if (gameboyType != null) {
            configuration.setGameboyType(gameboyType);
        }
        gb = configuration.build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        memory = gb.getAddressSpace();
        registers = gb.getCpu().getRegisters();
    }

    public TestResult runTest() {
        long ticks = 0;
        while (!isByteSequenceAtPc(gb, 0x40, 0x76, 0x00)) { // ld b,b; halt; nop
            if (++ticks > MAX_TICKS) {
                return new TestResult(false, true, dumpState());
            }
            gb.tick();
        }

        boolean passed = registers.getA() == 0
                && registers.getB() == 3
                && registers.getC() == 5
                && registers.getD() == 8
                && registers.getE() == 13
                && registers.getH() == 21
                && registers.getL() == 34;
        return new TestResult(passed, false, dumpState());
    }

    private String dumpState() {
        return String.format(
                "SameSuite result code=%02x, A=%02x B=%02x C=%02x D=%02x E=%02x H=%02x L=%02x, PC=%04x",
                memory.getByte(0xcffe), registers.getA(), registers.getB(), registers.getC(),
                registers.getD(), registers.getE(), registers.getH(), registers.getL(), registers.getPC());
    }

    public static class TestResult {

        private final boolean passed;

        private final boolean timedOut;

        private final String output;

        public TestResult(boolean passed, boolean timedOut, String output) {
            this.passed = passed;
            this.timedOut = timedOut;
            this.output = output;
        }

        public boolean isPassed() {
            return passed;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public String getOutput() {
            return output;
        }
    }
}
