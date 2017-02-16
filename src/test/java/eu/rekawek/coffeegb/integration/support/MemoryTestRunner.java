package eu.rekawek.coffeegb.integration.support;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.GameboyOptions;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.sound.SoundOutput;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class MemoryTestRunner {

    private final Gameboy gb;

    private final StringBuilder text;

    private final OutputStream os;

    private boolean testStarted;

    public MemoryTestRunner(File romFile, OutputStream os) throws IOException {
        GameboyOptions options = new GameboyOptions(romFile);
        Cartridge cart = new Cartridge(options);
        gb = new Gameboy(options, cart, Display.NULL_DISPLAY, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT, SerialEndpoint.NULL_ENDPOINT);
        text = new StringBuilder();
        this.os = os;
    }

    public TestResult runTest() throws IOException {
        int status = 0x80;
        int divider = 0;
        while(status == 0x80 && !SerialTestRunner.isInfiniteLoop(gb)) {
            gb.tick();
            if (++divider >= (gb.getSpeedMode().getSpeedMode() == 2 ? 1 : 4)) {
                status = getTestResult(gb);
                divider = 0;
            }
        }
        return new TestResult(status, text.toString());
    }

    private int getTestResult(Gameboy gb) throws IOException {
        AddressSpace mem = gb.getAddressSpace();
        if (!testStarted) {
            int i = 0xa000;
            for (int v : new int[] { 0x80, 0xde, 0xb0, 0x61 } ) {
                if (mem.getByte(i++) != v) {
                    return 0x80;
                }
            }
            testStarted = true;
        }
        int status = mem.getByte(0xa000);

        if (gb.getCpu().getState() != Cpu.State.OPCODE) {
            return status;
        }

        Registers reg = gb.getCpu().getRegisters();
        int i = reg.getPC();
        for (int v : new int[]{0xe5, 0xf5, 0xfa, 0x83, 0xd8}) {
            if (mem.getByte(i++) != v) {
                return status;
            }
        }
        char c = (char) reg.getA();
        text.append(c);
        if (os != null) {
            os.write(c);
        }
        reg.setPC(reg.getPC() + 0x19);
        return status;
    }

    public static class TestResult {

        private final int status;

        private final String text;

        public TestResult(int status, String text) {
            this.status = status;
            this.text = text;
        }

        public int getStatus() {
            return status;
        }

        public String getText() {
            return text;
        }
    }

}
