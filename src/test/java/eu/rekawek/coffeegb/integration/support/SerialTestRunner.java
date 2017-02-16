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

import static eu.rekawek.coffeegb.cpu.BitUtils.getLSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.getMSB;

public class SerialTestRunner implements SerialEndpoint {

    private final Gameboy gb;

    private final StringBuilder text;

    private final OutputStream os;

    public SerialTestRunner(File romFile, OutputStream os) throws IOException {
        GameboyOptions options = new GameboyOptions(romFile);
        Cartridge cart = new Cartridge(options);
        gb = new Gameboy(options, cart, Display.NULL_DISPLAY, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT, this);
        text = new StringBuilder();
        this.os = os;
    }

    public String runTest() throws IOException {
        int divider = 0;
        while (true) {
            gb.tick();
            if (++divider == 4) {
                if (isInfiniteLoop(gb)) {
                    break;
                }
                divider = 0;
            }
        }
        return text.toString();
    }

    @Override
    public int transfer(int outgoing) throws IOException {
        text.append((char) outgoing);
        os.write(outgoing);
        os.flush();
        return 0;
    }

    static boolean isInfiniteLoop(Gameboy gb) {
        Cpu cpu = gb.getCpu();
        if (cpu.getState() != Cpu.State.OPCODE) {
            return false;
        }
        Registers regs = cpu.getRegisters();
        AddressSpace mem = gb.getAddressSpace();

        int i = regs.getPC();
        boolean found = true;
        for (int v : new int[] { 0x18, 0xfe }) { // jr fe
            if (mem.getByte(i++) != v) {
                found = false;
                break;
            }
        }
        if (found) {
            return true;
        }

        i = regs.getPC();
        for (int v : new int[] { 0xc3, getLSB(i), getMSB(i) }) { // jp pc
            if (mem.getByte(i++) != v) {
                return false;
            }
        }
        return true;
    }
}
