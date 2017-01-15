package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.sound.SoundOutput;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class GameboyTest {

    @Test
    public void testBoot() throws IOException {
        Gameboy gb = new Gameboy(new Cartridge(new File("src/test/resources/tetris.gb")), Display.NULL_DISPLAY, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT);
        gb.getMmu().setByte(0xff50, 0);
        gb.getCpu().getRegisters().setPC(0x0000);
        while (gb.getCpu().getRegisters().getPC() != 0x0100) {
            gb.tick();
        }
    }

}