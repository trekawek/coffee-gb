package eu.rekawek.coffeegb.core.state.bess;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class BessHuc3Test {
    @Test
    public void restoresClockAlarmAndBankedRamWithoutCatchingUpOldTimestamp() throws Exception {
        byte[] image = new byte[0x10000];
        image[0x147] = (byte) 0xfe;
        image[0x148] = 1;
        image[0x149] = 3;
        Cartridge cartridge = new Cartridge(new Rom(image), Battery.NULL_BATTERY, () -> 120_000L);
        byte[] ram = new byte[0x8000];
        ram[0x6123] = 0x55;
        byte[] rtc = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(60).putShort((short) 1234).putShort((short) 500)
                .putShort((short) 1235).putShort((short) 501).put((byte) 1).array();
        cartridge.restoreBessState(ram, List.of(new BessState.MbcWrite(0, 0x0a),
                new BessState.MbcWrite(0x4000, 3)), Map.of("HUC3", rtc));
        assertEquals(0x55, cartridge.getByte(0xa123));
        var saved = cartridge.captureBessState();
        var clock = ByteBuffer.wrap(saved.extensions().get("HUC3")).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(120, clock.getLong());
        assertEquals(1234, clock.getShort());
        assertEquals(500, clock.getShort());
        assertEquals(1235, clock.getShort());
        assertEquals(501, clock.getShort());
        assertEquals(1, clock.get());
    }
}
