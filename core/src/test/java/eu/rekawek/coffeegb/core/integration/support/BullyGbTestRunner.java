package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.Registers;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.IOException;

/** Captures BullyGB's debugger-style writes to SB and verifies its terminal message. */
public class BullyGbTestRunner {

    private static final long MAX_TICKS = 50_000_000L;

    private final Gameboy gb;

    private final Cpu cpu;

    private final AddressSpace memory;

    private final StringBuilder output = new StringBuilder();

    public BullyGbTestRunner(File romFile, GameboyType gameboyType) throws IOException {
        EventBus eventBus = new EventBusImpl();
        gb = new Gameboy.GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setGameboyType(gameboyType)
                .setSupportBatterySave(false)
                .build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        cpu = gb.getCpu();
        memory = gb.getAddressSpace();
    }

    public TestResult runTest() {
        boolean capturedCurrentWrite = false;
        long ticks = 0;
        while (!SerialTestRunner.isInfiniteLoop(gb)) {
            boolean writingSb = isWritingSb();
            if (writingSb && !capturedCurrentWrite) {
                output.append((char) cpu.getRegisters().getA());
            }
            capturedCurrentWrite = writingSb;

            if (++ticks > MAX_TICKS) {
                return new TestResult(false, true, output.toString(), dumpState());
            }
            gb.tick();
        }

        String text = output.toString();
        return new TestResult(text.contains("All tests OK!"), false, text, dumpState());
    }

    private boolean isWritingSb() {
        if (cpu.getState() != Cpu.State.OPCODE) {
            return false;
        }
        int pc = cpu.getRegisters().getPC();
        return memory.getByte(pc) == 0xe0 && memory.getByte(pc + 1) == 0x01; // ldh [rSB],a
    }

    private String dumpState() {
        Registers registers = cpu.getRegisters();
        return String.format("A=%02x B=%02x C=%02x D=%02x E=%02x H=%02x L=%02x, PC=%04x",
                registers.getA(), registers.getB(), registers.getC(), registers.getD(),
                registers.getE(), registers.getH(), registers.getL(), registers.getPC());
    }

    public static class TestResult {

        private final boolean passed;

        private final boolean timedOut;

        private final String output;

        private final String state;

        public TestResult(boolean passed, boolean timedOut, String output, String state) {
            this.passed = passed;
            this.timedOut = timedOut;
            this.output = output;
            this.state = state;
        }

        public boolean isPassed() {
            return passed;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public String getDiagnostic() {
            return "BullyGB output: " + output + "\n" + state;
        }
    }
}
