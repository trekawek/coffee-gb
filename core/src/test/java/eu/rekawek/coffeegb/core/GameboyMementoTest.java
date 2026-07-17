package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
            assertEquals(Gameboy.LCD_OFF_BLANK_DELAY - elapsedBeforeSave, ticksUntilBlank);
        }
    }

    @Test
    public void briefLcdOffDoesNotPublishBlankFrame() throws IOException {
        byte[] romBytes = new byte[0x8000];
        romBytes[0x147] = 0;
        try (Gameboy gameboy = new Gameboy(new Rom(romBytes))) {
            gameboy.getGpu().setByte(0xff40, 0);
            for (int i = 1; i < Gameboy.LCD_OFF_BLANK_DELAY; i++) {
                assertFalse(gameboy.tick());
            }

            gameboy.getGpu().setByte(0xff40, 0x91);
            assertFalse(gameboy.tick());
        }
    }

    @Test
    public void sustainedLcdOffKeepsNormalCadenceAfterEarlyBlank() throws IOException {
        byte[] romBytes = new byte[0x8000];
        romBytes[0x147] = 0;
        try (Gameboy gameboy = new Gameboy(new Rom(romBytes))) {
            gameboy.getGpu().setByte(0xff40, 0);
            for (int i = 1; i < Gameboy.LCD_OFF_BLANK_DELAY; i++) {
                assertFalse(gameboy.tick());
            }
            assertTrue(gameboy.tick());

            int ticksUntilNextBlank = 0;
            do {
                ticksUntilNextBlank++;
            } while (!gameboy.tick());
            assertEquals(Gameboy.TICKS_PER_FRAME, ticksUntilNextBlank);
        }
    }
}
