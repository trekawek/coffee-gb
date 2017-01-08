package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.gpu.Display;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class GameboyTest {

    @Test
    public void testBoot() throws IOException {
        byte[] rom = new byte[32768];
        IOUtils.read(new FileInputStream(new File("src/test/resources/dr-mario.gb")), rom);

        int[] r = new int[rom.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = rom[i] & 0xff;
        }

        new Gameboy(r, new Display() {
            @Override
            public void setPixel(int x, int y, int color) {
            }

            @Override
            public void refresh() {
            }
        }).run();
    }

}