package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.memory.Rom;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class GameboyTest {

    @Test
    public void testBoot() throws IOException {
        new Gameboy(new Rom(new File("src/test/resources/dr-mario.gb"), 0), new Display() {
            @Override
            public void setPixel(int x, int y, int color) {
            }

            @Override
            public void refresh() {
            }
        }).run();
    }

}