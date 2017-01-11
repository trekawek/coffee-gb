package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.ButtonListener;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class GameboyTest {

    @Test
    public void testBoot() throws IOException {
        new Gameboy(new Cartridge(new File("src/test/resources/tetris.gb")), new Display() {
            @Override
            public void setPixel(int x, int y, int color) {
            }

            @Override
            public void refresh() {
            }
        }, listener -> {}).run();
    }

}