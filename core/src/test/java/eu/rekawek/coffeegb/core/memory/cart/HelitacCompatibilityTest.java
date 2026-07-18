package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HelitacCompatibilityTest {

    @Test
    public void detectsTheHelitacDemoAudioCompatibility() throws IOException {
        Rom rom = new Rom(helitacDemo());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.DMA_BLOCKED_READS_RETURN_FF));
    }

    @Test
    public void doesNotMatchAnotherHelitacBuild() throws IOException {
        byte[] data = helitacDemo();
        data[0x014f] ^= 1;

        assertFalse(new Rom(data).getCartridgeProperties().has(
                CartridgeProperties.Feature.DMA_BLOCKED_READS_RETURN_FF));
    }

    private static byte[] helitacDemo() {
        byte[] data = new byte[0x100000];
        byte[] title = "Helitac".getBytes();
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x1c;
        data[0x0148] = 0x05;
        data[0x014e] = 0x35;
        data[0x014f] = (byte) 0xbc;
        return data;
    }
}
