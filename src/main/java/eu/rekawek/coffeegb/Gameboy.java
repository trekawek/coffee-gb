package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.Joypad;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Ram;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.serial.SerialPort;
import eu.rekawek.coffeegb.sound.Sound;
import eu.rekawek.coffeegb.sound.SoundOutput;
import eu.rekawek.coffeegb.timer.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gameboy implements Runnable {

    public static final int TICKS_PER_SEC = 4_194_304;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    private final Timer timer;

    private final Dma dma;

    private final Display display;

    private final Sound sound;

    private final SerialPort serialPort;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private volatile boolean doStop;

    public Gameboy(GameboyOptions options, Cartridge rom, Display display, Controller controller, SoundOutput soundOutput, SerialEndpoint serialEndpoint) {
        Cartridge.GameboyTypeFlag gameboyType = rom.getGameboyType();
        if (gameboyType == Cartridge.GameboyTypeFlag.NON_CGB) {
            gbc = false;
        } else if (gameboyType == Cartridge.GameboyTypeFlag.CGB) {
            gbc = true;
        } else { // UNIVERSAL
            gbc = !options.isForceDmg();
        }


        this.display = display;
        speedMode = new SpeedMode();
        interruptManager = new InterruptManager();
        timer = new Timer(interruptManager, speedMode);
        gpu = new Gpu(display, interruptManager);
        mmu = new Mmu();
        dma = new Dma(mmu, speedMode);
        sound = new Sound(soundOutput);
        serialPort = new SerialPort(interruptManager, serialEndpoint, speedMode);
        mmu.addAddressSpace(rom);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(new Joypad(interruptManager, controller));
        mmu.addAddressSpace(interruptManager);
        mmu.addAddressSpace(serialPort);
        mmu.addAddressSpace(timer);
        mmu.addAddressSpace(dma);
        mmu.addAddressSpace(sound);

        if (gbc) {
            mmu.addAddressSpace(speedMode);
        }

        cpu = new Cpu(mmu, interruptManager, gpu, display, speedMode);
        init();
    }

    private void init() {
        Registers r = cpu.getRegisters();

        r.setAF(0x01b0);
        if (gbc) {
            r.setA(0x11);
        }
        r.setBC(0x0013);
        r.setDE(0x00d8);
        r.setHL(0x014d);
        r.setSP(0xfffe);
        r.setPC(0x0100);

        mmu.setByte(0xff05, 0x00);
        mmu.setByte(0xff06, 0x00);
        mmu.setByte(0xff07, 0x00);
        mmu.setByte(0xff10, 0x80);
        mmu.setByte(0xff11, 0xbf);
        mmu.setByte(0xff12, 0xf3);
        mmu.setByte(0xff14, 0xbf);
        mmu.setByte(0xff16, 0x3f);
        mmu.setByte(0xff17, 0x00);
        mmu.setByte(0xff19, 0xbf);
        mmu.setByte(0xff1a, 0x7f);
        mmu.setByte(0xff1b, 0xff);
        mmu.setByte(0xff1c, 0x9f);
        mmu.setByte(0xff1e, 0xbf);
        mmu.setByte(0xff20, 0xff);
        mmu.setByte(0xff21, 0x00);
        mmu.setByte(0xff22, 0x00);
        mmu.setByte(0xff23, 0xbf);
        mmu.setByte(0xff24, 0x77);
        mmu.setByte(0xff25, 0xf3);
        mmu.setByte(0xff26, 0xf1);
        mmu.setByte(0xff40, 0x91);
        mmu.setByte(0xff42, 0x00);
        mmu.setByte(0xff43, 0x00);
        mmu.setByte(0xff45, 0x00);
        mmu.setByte(0xff47, 0xfc);
        mmu.setByte(0xff48, 0xff);
        mmu.setByte(0xff49, 0xff);
        mmu.setByte(0xff4a, 0x00);
        mmu.setByte(0xff4b, 0x00);
        mmu.setByte(0xffff, 0x00);

        interruptManager.disableInterrupts(false);
    }

    public void run() {
        boolean requestedScreenRefresh = false;
        boolean lcdDisabled = false;
        doStop = false;
        while (!doStop) {
            Gpu.Mode newMode = tick();

            if (!gpu.isLcdEnabled() && !lcdDisabled) {
                lcdDisabled = true;
                display.requestRefresh();
            } else if (newMode == Gpu.Mode.VBlank) {
                requestedScreenRefresh = true;
                display.requestRefresh();
            }

            if (lcdDisabled && gpu.isLcdEnabled()) {
                lcdDisabled = false;
                display.waitForRefresh();
            } else if (requestedScreenRefresh && newMode == Gpu.Mode.OamSearch) {
                requestedScreenRefresh = false;
                display.waitForRefresh();
            }
        }
    }

    public void stop() {
        doStop = true;
    }

    public Gpu.Mode tick() {
        timer.tick();
        cpu.tick();
        dma.tick();
        sound.tick();
        serialPort.tick();
        return gpu.tick();
    }

    public AddressSpace getAddressSpace() {
        return mmu;
    }

    public Cpu getCpu() {
        return cpu;
    }
}
