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

    @Test
    public void speedSwitchTailHoldsCpuAndTimerAndSurvivesRestore() throws IOException {
        byte[] romBytes = cgbSpeedSwitchRom();
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romBytes))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.CGB)
                .setSupportBatterySave(false);
        try (Gameboy gameboy = configuration.build()) {
            for (int i = 0; i < 70_000 && !gameboy.isSpeedSwitchTailActive(); i++) {
                gameboy.tick();
            }
            assertTrue(gameboy.isSpeedSwitchTailActive());

            int pcAtTailStart = gameboy.getCpu().getRegisters().getPC();
            gameboy.getAddressSpace().setByte(0xff04, 0);
            gameboy.getAddressSpace().setByte(0xff05, 0);
            gameboy.getAddressSpace().setByte(0xff07, 0x05);
            var memento = gameboy.saveToMemento();

            for (int i = 0; i < 3; i++) {
                gameboy.tick();
            }
            gameboy.restoreFromMemento(memento);

            for (int i = 0; i < Gameboy.SPEED_SWITCH_TAIL_TICKS; i++) {
                assertTrue(gameboy.isSpeedSwitchTailActive());
                gameboy.tick();
            }
            assertFalse(gameboy.isSpeedSwitchTailActive());
            assertEquals(pcAtTailStart, gameboy.getCpu().getRegisters().getPC());
            assertEquals(0, gameboy.getAddressSpace().getByte(0xff05));

            for (int i = 0; i < 8; i++) {
                gameboy.tick();
            }
            assertEquals(1, gameboy.getAddressSpace().getByte(0xff05));
        }
    }

    @Test
    public void pendingHblankTransferOverlapsLastDoubleSpeedTailMachineCycle()
            throws IOException {
        byte[] romBytes = cgbSpeedSwitchWithPendingHdmaRom();
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romBytes))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.CGB)
                .setSupportBatterySave(false);
        try (Gameboy gameboy = configuration.build()) {
            for (int i = 0; i < 70_000 && !gameboy.isSpeedSwitchTailActive(); i++) {
                gameboy.tick();
            }
            assertTrue(gameboy.isSpeedSwitchTailActive());
            assertEquals(0, gameboy.getAddressSpace().getByte(0xff55) & 0x80);
            var memento = gameboy.saveToMemento();

            for (int i = 0; i < 3; i++) {
                gameboy.tick();
            }
            gameboy.restoreFromMemento(memento);
            assertTrue(gameboy.isSpeedSwitchTailActive());
            assertEquals(0, gameboy.getAddressSpace().getByte(0xff55) & 0x80);

            int tailTicks = 0;
            while (gameboy.isSpeedSwitchTailActive()) {
                gameboy.tick();
                tailTicks++;
            }
            assertEquals(Gameboy.SPEED_SWITCH_TAIL_TICKS
                    - Gameboy.PENDING_HBLANK_SPEED_SWITCH_OVERLAP_TICKS, tailTicks);
        }
    }

    private static byte[] cgbSpeedSwitchRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x3e; // ld a,1
        rom[0x101] = 0x01;
        rom[0x102] = (byte) 0xe0; // ldh [rKEY1],a
        rom[0x103] = 0x4d;
        rom[0x104] = 0x10; // stop
        rom[0x105] = 0x00;
        rom[0x106] = 0x04; // inc b
        rom[0x107] = 0x18; // jr $106
        rom[0x108] = (byte) 0xfd;
        rom[0x143] = (byte) 0x80;
        rom[0x147] = 0;
        return rom;
    }

    private static byte[] cgbSpeedSwitchWithPendingHdmaRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = (byte) 0xaf; // xor a
        rom[0x101] = (byte) 0xe0; // ldh [rLCDC],a
        rom[0x102] = 0x40;
        rom[0x103] = 0x3e; // ld a,$80
        rom[0x104] = (byte) 0x80;
        rom[0x105] = (byte) 0xe0; // ldh [rLCDC],a
        rom[0x106] = 0x40;
        rom[0x107] = 0x3e; // ld a,$80 (one-block HBlank DMA)
        rom[0x108] = (byte) 0x80;
        rom[0x109] = (byte) 0xe0; // ldh [rHDMA5],a
        rom[0x10a] = 0x55;
        rom[0x10b] = 0x3e; // ld a,1
        rom[0x10c] = 0x01;
        rom[0x10d] = (byte) 0xe0; // ldh [rKEY1],a
        rom[0x10e] = 0x4d;
        rom[0x10f] = 0x10; // stop
        rom[0x110] = 0x00;
        rom[0x111] = 0x18; // jr $111
        rom[0x112] = (byte) 0xfe;
        rom[0x143] = (byte) 0x80;
        rom[0x147] = 0;
        return rom;
    }
}
