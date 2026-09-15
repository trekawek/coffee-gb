package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PocketSonarTest {
    public static Rom rom(String title, int type) throws Exception {
        byte[] data = new byte[0x10000];
        for (int bank = 0; bank < 4; bank++) Arrays.fill(data, bank * 0x4000, (bank + 1) * 0x4000, (byte) bank);
        Arrays.fill(data, 0x134, 0x144, (byte) 0);
        System.arraycopy(title.getBytes(StandardCharsets.US_ASCII), 0, data, 0x134, title.length());
        data[0x147] = (byte) type; data[0x148] = 1; data[0x149] = 0;
        return new Rom(data);
    }

    @Test public void detectsOnlyTheMbc1sHeader() throws Exception {
        assertEquals(CartridgeProperties.Mapper.POCKET_SONAR, rom("POCKETSONAR", 1).getCartridgeProperties().getMapper());
        assertNotEquals(CartridgeProperties.Mapper.POCKET_SONAR, rom("POCKETSONARX", 1).getCartridgeProperties().getMapper());
        assertNotEquals(CartridgeProperties.Mapper.POCKET_SONAR, rom("POCKETSONAR", 3).getCartridgeProperties().getMapper());
    }

    @Test public void pulseResetsSamplesAndPreservesRomBanking() throws Exception {
        var sonar = new PocketSonar(rom("POCKETSONAR", 1), false);
        byte[] samples = SonarScene.openWater().copySamples();
        samples[0] = 1; samples[160] = 2; samples[1] = 3;
        sonar.configure(new SonarScene(samples), true);
        samples[0] = 0; // host caller cannot mutate the sensor
        assertEquals(255, sonar.getByte(0xa000));
        sonar.setByte(0x2000, 2);
        sonar.setByte(0x6000, 1);
        sonar.setByte(0x4000, 1); sonar.setByte(0x4000, 0);
        assertEquals(2, sonar.getByte(0x4000));
        assertEquals(1, sonar.getByte(0xa000));
        assertEquals(255, sonar.getByte(0xa001));
        var partial = sonar.captureState();
        assertEquals(2, sonar.getByte(0xa000));
        sonar.setByte(0x4000, 1); sonar.setByte(0x4000, 0);
        assertEquals(3, sonar.getByte(0xa000));
        sonar.restoreState(partial);
        assertEquals(2, sonar.getByte(0xa000));
        for (int i = 0; i < 400; i++) assertEquals(7, sonar.getByte(0xa000));
        sonar.configure(null, false);
        assertEquals(255, sonar.getByte(0xa000));
        sonar.setByte(0x2000, 0);
        assertEquals(1, sonar.getByte(0x4000));
    }

    @Test public void consoleFactoryModelsCgbSensorIncompatibility() throws Exception {
        for (GameboyType type : new GameboyType[] {GameboyType.DMG, GameboyType.CGB}) {
            var gb = new Gameboy.GameboyConfiguration(rom("POCKETSONAR", 1))
                    .setGameboyType(type).setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setSupportBatterySave(false).build();
            try {
                assertTrue(gb.configurePocketSonar(SonarScene.openWater(), true));
                gb.getAddressSpace().setByte(0x6000, 1);
                gb.getAddressSpace().setByte(0x4000, 1);
                gb.getAddressSpace().setByte(0x4000, 0);
                assertEquals(type == GameboyType.CGB ? 0 : 7, gb.getAddressSpace().getByte(0xa000));
            } finally { gb.close(); }
        }
    }
}
