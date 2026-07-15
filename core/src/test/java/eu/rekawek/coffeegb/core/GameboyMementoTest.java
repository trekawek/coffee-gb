package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class GameboyMementoTest {

    @Test
    public void restoresElapsedLcdOffTicks() throws IOException {
        byte[] romBytes = new byte[0x8000];
        romBytes[0x147] = 0;
        try (Gameboy gameboy = new Gameboy(new Rom(romBytes))) {
            gameboy.getGpu().setByte(0xff40, 0);
            int elapsedBeforeSave = 123;
            for (int i = 0; i < elapsedBeforeSave; i++) {
                gameboy.tick();
            }
            var memento = gameboy.saveToMemento();

            for (int i = 0; i < 456; i++) {
                gameboy.tick();
            }
            gameboy.restoreFromMemento(memento);

            int ticksUntilBlank = 0;
            do {
                ticksUntilBlank++;
            } while (!gameboy.tick());
            assertEquals(Gameboy.TICKS_PER_FRAME - elapsedBeforeSave, ticksUntilBlank);
        }
    }
}
