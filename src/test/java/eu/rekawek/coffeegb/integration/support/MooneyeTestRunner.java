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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MooneyeTestRunner {

    private final Gameboy gb;

    private final Cpu cpu;

    private final AddressSpace mem;

    private final Registers regs;

    private final OutputStream os;

    public MooneyeTestRunner(File romFile, OutputStream os) throws IOException {
        List<String> opts = new ArrayList<>();
        if (romFile.toString().endsWith("-C.gb") || romFile.toString().endsWith("-cgb.gb")) {
            opts.add("c");
        }
        if (romFile.getName().startsWith("boot_")) {
            opts.add("b");
        }
        opts.add("db");
        GameboyOptions options = new GameboyOptions(romFile, Collections.emptyList(), opts);
        Cartridge cart = new Cartridge(options);
        gb = new Gameboy(options, cart, Display.NULL_DISPLAY, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT, SerialEndpoint.NULL_ENDPOINT);
        System.out.println("System type: " + (cart.isGbc() ? "CGB" : "DMG"));
        System.out.println("Bootstrap: " + (options.isUsingBootstrap() ? "enabled" : "disabled"));
        cpu = gb.getCpu();
        regs = cpu.getRegisters();
        mem = gb.getAddressSpace();
        this.os = os;
    }

    public boolean runTest() throws IOException {
        int divider = 0;
        while(!isByteSequenceAtPc(0x00, 0x18, 0xfd)) { // infinite loop
            gb.tick();
            if (++divider >= (gb.getSpeedMode().getSpeedMode() == 2 ? 1 : 4)) {
                displayProgress();
                divider = 0;
            }
        }
        return regs.getA() == 0 && regs.getB() == 3 && regs.getC() == 5 && regs.getD() == 8 && regs.getE() == 13 && regs.getH() == 21 && regs.getL() == 34;
    }

    private void displayProgress() throws IOException {
        if (cpu.getState() == Cpu.State.OPCODE && mem.getByte(regs.getPC()) == 0x22 && regs.getHL() >= 0x9800 && regs.getHL() < 0x9c00) {
            if (regs.getA() != 0) {
                os.write(regs.getA());
            }
        } else if (isByteSequenceAtPc(0x7d, 0xe6, 0x1f, 0xee, 0x1f)) {
            os.write('\n');
        }
    }

    private boolean isByteSequenceAtPc(int... seq) {
        if (cpu.getState() != Cpu.State.OPCODE) {
            return false;
        }

        int i = regs.getPC();
        boolean found = true;
        for (int v : seq) {
            if (mem.getByte(i++) != v) {
                found = false;
                break;
            }
        }
        return found;
    }

}