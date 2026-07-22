package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DungeonWarriorBootWramTest {

    @Test
    public void clearsOnlyTheUninitializedRendererRecordCount() throws IOException {
        Rom rom = new Rom(dungeonWarriorPrototype());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_DUNGEON_WARRIOR_RENDERER_COUNT));

        Gameboy compatible = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();

        byte[] nearMatch = dungeonWarriorPrototype();
        nearMatch[0x1be1] ^= 1;
        Gameboy ordinary = new Gameboy.GameboyConfiguration(new Rom(nearMatch))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();

        assertEquals(0, compatible.getAddressSpace().getByte(0xc0bc));
        assertEquals(0xf1, ordinary.getAddressSpace().getByte(0xc0bc));
        for (int address = 0xc000; address < 0xe000; address++) {
            if (address != 0xc0bc) {
                assertEquals(Integer.toHexString(address),
                        ordinary.getAddressSpace().getByte(address),
                        compatible.getAddressSpace().getByte(address));
            }
        }
        boolean hasNonZeroMinesweeperSeed = false;
        for (int address = 0xc100; address < 0xc110; address++) {
            hasNonZeroMinesweeperSeed |= compatible.getAddressSpace().getByte(address) != 0;
        }
        assertTrue(hasNonZeroMinesweeperSeed);
    }

    @Test
    public void nearMatchKeepsTheHardwarePowerOnValue() throws IOException {
        byte[] data = dungeonWarriorPrototype();
        data[0x1be1] ^= 1;

        Rom rom = new Rom(data);

        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_DUNGEON_WARRIOR_RENDERER_COUNT));
        Gameboy gb = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
        assertEquals(0xf1, gb.getAddressSpace().getByte(0xc0bc));
    }

    private static byte[] dungeonWarriorPrototype() {
        byte[] data = new byte[0x10000];
        byte[] title = "DUNGEON WARRIOR ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0147] = 0x01;
        data[0x0148] = 0x01;
        data[0x014d] = 0x6f;
        data[0x014e] = 0x6b;
        data[0x014f] = (byte) 0x86;
        int[] rendererRecordSetup = {
                0xfa, 0xbc, 0xc0, 0xfe, 0x08, 0xd0, 0xc5, 0x4f,
                0x87, 0x81, 0x01, 0xa0, 0xc2, 0x81, 0x4f
        };
        for (int i = 0; i < rendererRecordSetup.length; i++) {
            data[0x1be1 + i] = (byte) rendererRecordSetup[i];
        }
        // Four synthetic padding bytes give this minimal signature fixture the
        // prototype's exact CRC32 identity without embedding any game assets.
        data[0xfffc] = (byte) 0x8d;
        data[0xfffd] = 0x4a;
        data[0xfffe] = 0x5b;
        data[0xffff] = (byte) 0xdf;
        return data;
    }
}
