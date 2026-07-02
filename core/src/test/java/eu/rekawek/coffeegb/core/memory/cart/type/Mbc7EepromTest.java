package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Mbc7EepromTest {

    private static final int CS = 0x80;
    private static final int CLK = 0x40;
    private static final int DI = 0x02;

    private static class CapturingBattery implements Battery {
        int[] saved;
        boolean flushed;

        @Override
        public void loadRam(int[] ram) {
        }

        @Override
        public void saveRam(int[] ram) {
            saved = ram.clone();
        }

        @Override
        public void loadRamWithClock(int[] ram, long[] clockData) {
        }

        @Override
        public void saveRamWithClock(int[] ram, long[] clockData) {
            saved = ram.clone();
        }

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public Memento<Battery> saveToMemento() {
            return null;
        }

        @Override
        public void restoreFromMemento(Memento<Battery> memento) {
        }
    }

    private final CapturingBattery battery = new CapturingBattery();

    private final Mbc7Eeprom eeprom = new Mbc7Eeprom(battery);

    private void sendBit(boolean bit) {
        int di = bit ? DI : 0;
        eeprom.write(CS | di);
        eeprom.write(CS | CLK | di);
    }

    private void sendBits(int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            sendBit(((value >> i) & 1) != 0);
        }
    }

    private void endCommand() {
        eeprom.write(0); // CS low
    }

    private void command(int op, int addr) {
        sendBit(true); // start bit
        sendBits(op, 2);
        sendBits(addr, 8);
    }

    private int readWord() {
        int result = 0;
        for (int i = 0; i < 17; i++) {
            eeprom.write(CS);
            eeprom.write(CS | CLK);
            int bit = eeprom.read() & 1;
            if (i > 0) {
                result = (result << 1) | bit;
            }
        }
        return result;
    }

    @Test
    public void writeAndReadBack() {
        command(0b00, 0b11000000); // EWEN
        endCommand();

        command(0b01, 0x12); // WRITE address 0x12
        sendBits(0xbeef, 16);
        endCommand();

        command(0b10, 0x12); // READ address 0x12
        assertEquals(0xbeef, readWord());
        endCommand();
    }

    @Test
    public void writeWithoutEwenIsIgnored() {
        command(0b01, 0x08); // WRITE without EWEN
        sendBits(0x1234, 16);
        endCommand();

        command(0b10, 0x08);
        assertEquals(0xffff, readWord());
        endCommand();
    }

    @Test
    public void eraseSetsAllOnes() {
        command(0b00, 0b11000000); // EWEN
        endCommand();
        command(0b01, 0x05);
        sendBits(0x1234, 16);
        endCommand();
        command(0b11, 0x05); // ERASE
        endCommand();
        command(0b10, 0x05);
        assertEquals(0xffff, readWord());
        endCommand();
    }

    @Test
    public void writeMarksBatteryDirty() {
        command(0b00, 0b11000000); // EWEN
        endCommand();
        command(0b01, 0x00);
        sendBits(0xa55a, 16);
        endCommand();

        eeprom.flush();
        assertTrue("EEPROM write did not reach the battery", battery.flushed);
        assertEquals(0xa5, battery.saved[0]);
        assertEquals(0x5a, battery.saved[1]);
    }
}
